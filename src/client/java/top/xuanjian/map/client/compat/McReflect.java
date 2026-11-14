package top.xuanjian.map.client.compat;

import top.xuanjian.map.core.util.GisLog;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 跨版本反射适配层（26.2 / 1.21.11）。
 *
 * <p>为什么用反射读区块数据：Minecraft 内部读取 API（heightmap/biome/registry/结构）
 * 在小版本间频繁重命名；反射 + 候选方法名可以让两条分支共用一套采集器，并把失败原因
 * 写进日志/CI，而不是编译期直接失败。GUI/HUD 等有稳定文档的 API 仍然直接编译期调用。
 *
 * <p>所有方法都做了缓存，失败只记一次日志，不会刷屏。
 */
public final class McReflect {

    private static final String TAG = "mc-reflect";
    private static final Map<String, Method> METHOD_CACHE = new ConcurrentHashMap<>();
    private static final Set<String> MISSING_LOGGED = ConcurrentHashMap.newKeySet();
    /** 已确认找不到的方法（避免每次点击/每 tick 重新枚举，减少反射开销与日志噪音）。 */
    private static final Set<String> FAILED_LOOKUPS = ConcurrentHashMap.newKeySet();
    private static final Method[] EMPTY_METHODS = new Method[0];
    private static final Map<String, Class<?>> CLASS_CACHE = new ConcurrentHashMap<>();

    private McReflect() {
    }

    /* ==================== 类 ==================== */

    public static Class<?> cls(String... fqns) {
        for (String fqn : fqns) {
            Class<?> cached = CLASS_CACHE.get(fqn);
            if (cached != null) {
                return cached;
            }
            try {
                Class<?> c = Class.forName(fqn);
                CLASS_CACHE.put(fqn, c);
                return c;
            } catch (ClassNotFoundException ignored) {
                // 尝试下一个名字
            }
        }
        return null;
    }

    public static boolean classPresent(String fqn) {
        return cls(fqn) != null;
    }

    /* ==================== 字段 ==================== */

    /** 取实例字段（含父类），失败返回 null。 */
    public static Object field(Object target, String... names) {
        if (target == null) {
            return null;
        }
        for (String name : names) {
            Class<?> c = target.getClass();
            while (c != null) {
                try {
                    Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    return f.get(target);
                } catch (NoSuchFieldException e) {
                    c = c.getSuperclass();
                } catch (Throwable e) {
                    logMissing(target.getClass().getSimpleName() + "#" + name, e);
                    break;
                }
            }
        }
        return null;
    }

    public static Object staticField(Class<?> type, String... names) {
        if (type == null) {
            return null;
        }
        for (String name : names) {
            try {
                Field f = type.getField(name);
                return f.get(null);
            } catch (Throwable ignored) {
                // 下一个
            }
        }
        return null;
    }

    /** 取常量（静态字段或枚举常量）。 */
    public static Object constant(Class<?> type, String... names) {
        if (type == null) {
            return null;
        }
        Object v = staticField(type, names);
        if (v != null) {
            return v;
        }
        if (type.isEnum()) {
            Object[] values;
            try {
                values = type.getEnumConstants();
            } catch (Throwable t) {
                logMissing(type.getSimpleName() + "#<enum>", t);
                values = null;
            }
            if (values != null) {
                for (String name : names) {
                    for (Object value : values) {
                        if (((Enum<?>) value).name().equalsIgnoreCase(name)) {
                            return value;
                        }
                    }
                }
            }
        }
        return null;
    }

    /* ==================== 方法 ==================== */

    /** 按候选方法名依次尝试调用（同名重载按参数个数/可赋值匹配）。 */
    public static Object call(Object target, String[] names, Object... args) {
        if (target == null) {
            return null;
        }
        for (String name : names) {
            Method m = findMethod(target.getClass(), name, args);
            if (m != null) {
                try {
                    m.setAccessible(true);
                    return m.invoke(target, args);
                } catch (Throwable e) {
                    logMissing(target.getClass().getSimpleName() + "." + name, e);
                }
            }
        }
        return null;
    }

    public static Object call(Object target, String name, Object... args) {
        return call(target, new String[]{name}, args);
    }

    /** 方法是否存在（按名字+参数匹配），用于跨版本选择不同签名。 */
    public static boolean hasMethod(Object target, String name, Object... args) {
        return target != null && findMethod(target.getClass(), name, args) != null;
    }

    /** 静态方法调用。 */
    public static Object callStatic(Class<?> type, String[] names, Object... args) {
        if (type == null) {
            return null;
        }
        for (String name : names) {
            Method m = findMethod(type, name, args);
            if (m != null) {
                try {
                    m.setAccessible(true);
                    return m.invoke(null, args);
                } catch (Throwable e) {
                    logMissing(type.getSimpleName() + "." + name, e);
                }
            }
        }
        return null;
    }

    public static Object construct(Class<?> type, Object... args) {
        if (type == null) {
            return null;
        }
        Constructor<?>[] constructors;
        try {
            constructors = type.getDeclaredConstructors();
        } catch (Throwable t) {
            logMissing(type.getSimpleName() + "#<constructors>", t);
            return null;
        }
        for (Constructor<?> ctor : constructors) {
            if (matches(ctor.getParameterTypes(), args)) {
                try {
                    ctor.setAccessible(true);
                    return ctor.newInstance(args);
                } catch (Exception e) {
                    logMissing(type.getSimpleName() + "#<init>", e);
                }
            }
        }
        return null;
    }

    /* ==================== 内部 ==================== */

    private static Method findMethod(Class<?> type, String name, Object[] args) {
        String key = type.getName() + "#" + name + "#" + (args == null ? 0 : args.length);
        Method cached = METHOD_CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        if (FAILED_LOOKUPS.contains(key)) {
            return null;
        }
        List<Class<?>> candidates = new ArrayList<>();
        Class<?> c = type;
        while (c != null) {
            candidates.add(c);
            c = c.getSuperclass();
        }
        for (Class<?> cls : candidates) {
            for (Method m : declaredMethodsSafe(cls)) {
                if (!m.getName().equals(name) || m.isSynthetic()) {
                    continue;
                }
                if (Modifier.isStatic(m.getModifiers())) {
                    continue;
                }
                if (matches(m.getParameterTypes(), args)) {
                    METHOD_CACHE.put(key, m);
                    return m;
                }
            }
        }
        // 接口方法（例如 Holder 的 unwrapKey 可能来自接口）
        for (Class<?> cls : candidates) {
            try {
                for (Class<?> itf : cls.getInterfaces()) {
                    for (Method m : itf.getMethods()) {
                        if (m.getName().equals(name) && matches(m.getParameterTypes(), args)) {
                            METHOD_CACHE.put(key, m);
                            return m;
                        }
                    }
                }
            } catch (Throwable t) {
                // 某些类的签名引用了运行期不存在的可选库类型（如 renderpearl GpuBackend），
                // 枚举方法会抛 NoClassDefFoundError —— 跳过即可，不能让模组崩溃。
                logMissing(cls.getSimpleName() + "#<interfaces>", t);
            }
        }
        FAILED_LOOKUPS.add(key);
        return null;
    }

    /** 安全枚举方法：签名中引用了缺失类的类会抛 LinkageError，必须吞掉。 */
    private static Method[] declaredMethodsSafe(Class<?> cls) {
        try {
            return cls.getDeclaredMethods();
        } catch (Throwable t) {
            logMissing(cls.getSimpleName() + "#<methods>", t);
            return EMPTY_METHODS;
        }
    }

    private static boolean matches(Class<?>[] paramTypes, Object[] args) {
        if (args == null) {
            return paramTypes.length == 0;
        }
        if (paramTypes.length != args.length) {
            return false;
        }
        for (int i = 0; i < paramTypes.length; i++) {
            Object arg = args[i];
            if (arg == null) {
                if (paramTypes[i].isPrimitive()) {
                    return false;
                }
                continue;
            }
            Class<?> p = box(paramTypes[i]);
            if (!p.isInstance(arg)) {
                return false;
            }
        }
        return true;
    }

    private static Class<?> box(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        return switch (type.getName()) {
            case "int" -> Integer.class;
            case "long" -> Long.class;
            case "short" -> Short.class;
            case "byte" -> Byte.class;
            case "boolean" -> Boolean.class;
            case "double" -> Double.class;
            case "float" -> Float.class;
            case "char" -> Character.class;
            default -> type;
        };
    }

    private static void logMissing(String what, Throwable error) {
        if (MISSING_LOGGED.add(what)) {
            GisLog.warn(TAG, "反射调用失败: " + what + " -> " + error);
        }
    }

    /** debug 用：把类的方法签名打到日志，便于对齐跨版本 API。 */
    public static String describe(Class<?> type, int max) {
        if (type == null) {
            return "<missing>";
        }
        StringBuilder sb = new StringBuilder(type.getName()).append(": ");
        Method[] methods;
        try {
            methods = type.getMethods();
        } catch (Throwable t) {
            return sb.append("<methods unavailable: ").append(t.getClass().getSimpleName()).append('>').toString();
        }
        int n = 0;
        for (Method m : methods) {
            if (m.isSynthetic() || m.getDeclaringClass() == Object.class) {
                continue;
            }
            if (n++ >= max) {
                sb.append("...");
                break;
            }
            try {
                sb.append(m.getName()).append('(');
                Class<?>[] ps = m.getParameterTypes();
                for (int i = 0; i < ps.length; i++) {
                    if (i > 0) {
                        sb.append(',');
                    }
                    sb.append(ps[i].getSimpleName());
                }
                sb.append(") ");
            } catch (Throwable t) {
                sb.append("<signature unavailable> ");
            }
        }
        return sb.toString();
    }
}
