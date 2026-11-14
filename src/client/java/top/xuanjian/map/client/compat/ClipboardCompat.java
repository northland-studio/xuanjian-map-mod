package top.xuanjian.map.client.compat;

import top.xuanjian.map.client.compat.McReflect;
import top.xuanjian.map.core.util.GisLog;

/**
 * 系统剪贴板读写（OAuth 授权链接复制 / 回调 code 读取）。
 *
 * <p>按要求直接使用 GLFW 的 clipboard API（{@code glfwSetClipboardString} /
 * {@code glfwGetClipboardString}），不依赖各版本差异较大的 KeyboardHandler。
 * 窗口句柄通过反射获取（26 与 1.21 的字段/方法名可能不同），取不到时降级为“复制失败”
 * 并提示用户手动复制日志中的链接，绝不因剪贴板问题导致游戏崩溃。
 */
public final class ClipboardCompat {

    private static final String TAG = "clipboard";

    private ClipboardCompat() {
    }

    /** 写入系统剪贴板；成功返回 true。 */
    public static boolean write(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        try {
            long handle = windowHandle();
            if (handle == 0L) {
                GisLog.warn(TAG, "拿不到 GLFW 窗口句柄，无法写入剪贴板");
                return false;
            }
            org.lwjgl.glfw.GLFW.glfwSetClipboardString(handle, text);
            return true;
        } catch (Throwable t) {
            GisLog.warn(TAG, "写入剪贴板失败: " + t, t);
            return false;
        }
    }

    /** 读取系统剪贴板；失败/为空返回空串。 */
    public static String read() {
        try {
            long handle = windowHandle();
            if (handle == 0L) {
                return "";
            }
            String text = org.lwjgl.glfw.GLFW.glfwGetClipboardString(handle);
            return text == null ? "" : text;
        } catch (Throwable t) {
            GisLog.warn(TAG, "读取剪贴板失败: " + t, t);
            return "";
        }
    }

    private static long windowHandle() {
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
    }
}
