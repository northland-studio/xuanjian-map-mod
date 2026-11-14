package top.xuanjian.map.core.net;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** JSON 小工具（Gson 由 Minecraft 运行时提供；单元测试显式依赖同版本）。 */
public final class Json {

    public static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private Json() {
    }

    public static JsonObject parseObject(String text) {
        if (text == null || text.isBlank()) {
            return new JsonObject();
        }
        JsonElement e = JsonParser.parseString(text);
        return e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
    }

    public static JsonObject parseObjectQuietly(String text) {
        try {
            return parseObject(text);
        } catch (Exception e) {
            return new JsonObject();
        }
    }

    public static String write(Object value) {
        return GSON.toJson(value);
    }

    public static String optString(JsonObject o, String key, String def) {
        try {
            if (o == null || !o.has(key) || o.get(key).isJsonNull()) {
                return def;
            }
            return o.get(key).getAsString();
        } catch (Exception e) {
            return def;
        }
    }

    public static int optInt(JsonObject o, String key, int def) {
        try {
            if (o == null || !o.has(key) || o.get(key).isJsonNull()) {
                return def;
            }
            return o.get(key).getAsInt();
        } catch (Exception e) {
            return def;
        }
    }

    public static long optLong(JsonObject o, String key, long def) {
        try {
            if (o == null || !o.has(key) || o.get(key).isJsonNull()) {
                return def;
            }
            return o.get(key).getAsLong();
        } catch (Exception e) {
            return def;
        }
    }

    public static double optDouble(JsonObject o, String key, double def) {
        try {
            if (o == null || !o.has(key) || o.get(key).isJsonNull()) {
                return def;
            }
            return o.get(key).getAsDouble();
        } catch (Exception e) {
            return def;
        }
    }

    public static boolean optBool(JsonObject o, String key, boolean def) {
        try {
            if (o == null || !o.has(key) || o.get(key).isJsonNull()) {
                return def;
            }
            return o.get(key).getAsBoolean();
        } catch (Exception e) {
            return def;
        }
    }

    public static JsonObject optObject(JsonObject o, String key) {
        try {
            if (o == null || !o.has(key) || !o.get(key).isJsonObject()) {
                return null;
            }
            return o.getAsJsonObject(key);
        } catch (Exception e) {
            return null;
        }
    }

    public static JsonArray optArray(JsonObject o, String key) {
        try {
            if (o == null || !o.has(key) || !o.get(key).isJsonArray()) {
                return new JsonArray();
            }
            return o.getAsJsonArray(key);
        } catch (Exception e) {
            return new JsonArray();
        }
    }
}
