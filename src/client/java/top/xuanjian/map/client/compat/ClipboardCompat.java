package top.xuanjian.map.client.compat;

import top.xuanjian.map.core.util.GisLog;

/**
 * 系统剪贴板读写（OAuth 授权链接复制 / 回调 code 读取），**多重兜底**。
 *
 * <p>真实环境中不是所有客户端都能用 GLFW 剪贴板（沙箱/远程桌面/Wayland/权限受限等会失败），
 * 因此按顺序尝试：
 * <ol>
 *   <li>GLFW {@code glfwSetClipboardString/glfwGetClipboardString}（首选，跨版本稳定）；</li>
 *   <li>Minecraft {@code KeyboardHandler}（GLFW 失败时兜底，反射调用避开版本差异）；</li>
 *   <li>AWT 系统剪贴板（仅非 macOS，避免 AWT/GLFW 线程冲突）。</li>
 * </ol>
 * 全部失败时返回失败原因，由上层降级到「写文件 / 手动输入框」，保证 OAuth 流程可闭环。
 */
public final class ClipboardCompat {

    private static final String TAG = "clipboard";

    /** 写入结果：成功与否 + 生效方式（glfw/keyboard/awt）。 */
    public record WriteResult(boolean ok, String method) {
        public static final WriteResult FAILED = new WriteResult(false, "none");
    }

    private ClipboardCompat() {
    }

    public static WriteResult write(String text) {
        if (text == null || text.isEmpty()) {
            return WriteResult.FAILED;
        }
        if (tryGlfwWrite(text)) {
            return new WriteResult(true, "glfw");
        }
        if (tryKeyboardHandlerWrite(text)) {
            return new WriteResult(true, "keyboard");
        }
        if (tryAwtWrite(text)) {
            return new WriteResult(true, "awt");
        }
        GisLog.warn(TAG, "所有剪贴板写入方式均失败（将降级为写文件/手动复制）");
        return WriteResult.FAILED;
    }

    public static String read() {
        String text = tryGlfwRead();
        if (isUsable(text)) {
            return text;
        }
        text = tryKeyboardHandlerRead();
        if (isUsable(text)) {
            return text;
        }
        text = tryAwtRead();
        return isUsable(text) ? text : "";
    }

    private static boolean isUsable(String text) {
        return text != null && !text.isBlank();
    }

    /* ==================== GLFW ==================== */

    private static boolean tryGlfwWrite(String text) {
        try {
            long handle = windowHandle();
            if (handle == 0L) {
                return false;
            }
            org.lwjgl.glfw.GLFW.glfwSetClipboardString(handle, text);
            return true;
        } catch (Throwable t) {
            GisLog.debug(TAG, "GLFW 写剪贴板失败: " + t);
            return false;
        }
    }

    private static String tryGlfwRead() {
        try {
            long handle = windowHandle();
            if (handle == 0L) {
                return "";
            }
            String text = org.lwjgl.glfw.GLFW.glfwGetClipboardString(handle);
            return text == null ? "" : text;
        } catch (Throwable t) {
            GisLog.debug(TAG, "GLFW 读剪贴板失败: " + t);
            return "";
        }
    }

    private static long windowHandle() {
        // 首选：GLFW 当前上下文句柄。剪贴板操作发生在渲染线程（按钮点击/每帧），
        // 此时 current context 就是游戏窗口，完全不需要反射 Minecraft/Window 内部类，
        // 避免它们的方法签名引用运行期缺失的可选库类型（如 renderpearl）时抛 NoClassDefFoundError。
        try {
            long context = org.lwjgl.glfw.GLFW.glfwGetCurrentContext();
            if (context != 0L) {
                return context;
            }
        } catch (Throwable ignored) {
            // 继续走反射兜底
        }
        try {
            Object mc = net.minecraft.client.Minecraft.getInstance();
            if (mc == null) {
                return 0L;
            }
            Object window = McReflect.field(mc, "window");
            if (window == null) {
                window = McReflect.call(mc, new String[]{"getWindow"});
            }
            if (window == null) {
                return 0L;
            }
            Object handle = McReflect.call(window, new String[]{"getWindow", "getHandle"});
            if (handle instanceof Number num) {
                return num.longValue();
            }
            Object field = McReflect.field(window, "handle", "window");
            return field instanceof Number num ? num.longValue() : 0L;
        } catch (Throwable t) {
            GisLog.debug(TAG, "获取窗口句柄失败: " + t);
            return 0L;
        }
    }

    /* ==================== Minecraft KeyboardHandler（兜底） ==================== */

    private static Object keyboardHandler() {
        Object mc = net.minecraft.client.Minecraft.getInstance();
        if (mc == null) {
            return null;
        }
        Object handler = McReflect.field(mc, "keyboardHandler");
        if (handler == null) {
            handler = McReflect.call(mc, new String[]{"getKeyboardHandler"});
        }
        return handler;
    }

    private static boolean tryKeyboardHandlerWrite(String text) {
        try {
            Object handler = keyboardHandler();
            if (handler == null) {
                return false;
            }
            McReflect.call(handler, new String[]{"setClipboard"}, text);
            return true;
        } catch (Throwable t) {
            GisLog.debug(TAG, "KeyboardHandler 写剪贴板失败: " + t);
            return false;
        }
    }

    private static String tryKeyboardHandlerRead() {
        try {
            Object handler = keyboardHandler();
            if (handler == null) {
                return "";
            }
            Object value = McReflect.call(handler, new String[]{"getClipboard"});
            return value instanceof String s ? s : "";
        } catch (Throwable t) {
            GisLog.debug(TAG, "KeyboardHandler 读剪贴板失败: " + t);
            return "";
        }
    }

    /* ==================== AWT（最后兜底，避开 macOS） ==================== */

    private static boolean isMac() {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        return os.contains("mac") || os.contains("darwin");
    }

    private static boolean tryAwtWrite(String text) {
        if (isMac()) {
            return false;
        }
        try {
            java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new java.awt.datatransfer.StringSelection(text), null);
            return true;
        } catch (Throwable t) {
            GisLog.debug(TAG, "AWT 写剪贴板失败: " + t);
            return false;
        }
    }

    private static String tryAwtRead() {
        if (isMac()) {
            return "";
        }
        try {
            Object data = java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                    .getData(java.awt.datatransfer.DataFlavor.stringFlavor);
            return data instanceof String s ? s : "";
        } catch (Throwable t) {
            GisLog.debug(TAG, "AWT 读剪贴板失败: " + t);
            return "";
        }
    }
}
