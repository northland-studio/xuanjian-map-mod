package top.xuanjian.map.core.net;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import top.xuanjian.map.core.dimension.Dimensions;
import top.xuanjian.map.core.model.BBox;
import top.xuanjian.map.core.model.Geometry;
import top.xuanjian.map.core.model.GisObject;
import top.xuanjian.map.core.model.GisObjectKind;
import top.xuanjian.map.core.storage.GisStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GIS 对象的 JSON 序列化：兼容 GeoJSON 几何（Point/LineString/Polygon），
 * 同时负责「API 对象 ↔ SQLite StoredObject」转换，让存储层保持纯 SQL。
 */
public final class ObjectJson {

    private ObjectJson() {
    }

    /* ==================== 几何 → GeoJSON ==================== */

    public static JsonObject geometryToJson(Geometry geometry) {
        JsonObject o = new JsonObject();
        if (geometry instanceof Geometry.GisPoint p) {
            o.addProperty("type", "Point");
            o.add("coordinates", array(p.x(), p.z()));
        } else if (geometry instanceof Geometry.GisLine line) {
            o.addProperty("type", "LineString");
            JsonArray coords = new JsonArray();
            for (double[] pt : line.points()) {
                coords.add(array(pt[0], pt[1]));
            }
            o.add("coordinates", coords);
        } else if (geometry instanceof Geometry.GisPolygon polygon) {
            o.addProperty("type", "Polygon");
            JsonArray rings = new JsonArray();
            for (Geometry.Ring ring : polygon.rings()) {
                JsonArray coords = new JsonArray();
                for (int i = 0; i < ring.xs().length; i++) {
                    coords.add(array(ring.xs()[i], ring.zs()[i]));
                }
                rings.add(coords);
            }
            o.add("coordinates", rings);
        }
        return o;
    }

    public static String geometryToString(Geometry geometry) {
        return Json.write(geometryToJson(geometry));
    }

    /** 解析 GeoJSON 几何；无法识别返回 null。 */
    public static Geometry geometryFromJson(JsonObject geometry) {
        if (geometry == null) {
            return null;
        }
        String type = Json.optString(geometry, "type", "");
        JsonElement coords = geometry.get("coordinates");
        if (coords == null || !coords.isJsonArray()) {
            return null;
        }
        try {
            return switch (type) {
                case "Point" -> {
                    JsonArray a = coords.getAsJsonArray();
                    yield new Geometry.GisPoint(num(a, 0), num(a, 1));
                }
                case "LineString" -> {
                    JsonArray a = coords.getAsJsonArray();
                    double[][] pts = new double[a.size()][2];
                    for (int i = 0; i < a.size(); i++) {
                        JsonArray p = a.get(i).getAsJsonArray();
                        pts[i][0] = num(p, 0);
                        pts[i][1] = num(p, 1);
                    }
                    yield new Geometry.GisLine(pts);
                }
                case "Polygon" -> {
                    JsonArray rings = coords.getAsJsonArray();
                    List<Geometry.Ring> out = new ArrayList<>();
                    for (JsonElement r : rings) {
                        JsonArray a = r.getAsJsonArray();
                        double[] xs = new double[a.size()];
                        double[] zs = new double[a.size()];
                        for (int i = 0; i < a.size(); i++) {
                            JsonArray p = a.get(i).getAsJsonArray();
                            xs[i] = num(p, 0);
                            zs[i] = num(p, 1);
                        }
                        out.add(new Geometry.Ring(xs, zs));
                    }
                    yield new Geometry.GisPolygon(out);
                }
                default -> null;
            };
        } catch (Exception e) {
            return null;
        }
    }

    public static Geometry geometryFromString(String text) {
        return geometryFromJson(Json.parseObjectQuietly(text));
    }

    /* ==================== API 对象 ↔ GisObject ==================== */

    /** 解析服务端返回的单个对象（对字段名做兼容：type/kind、properties/props、geometry/geojson）。 */
    public static GisObject fromApi(JsonObject obj, long worldId, String dimension) {
        if (obj == null) {
            return null;
        }
        String type = Json.optString(obj, "type", Json.optString(obj, "kind", "other"));
        GisObjectKind kind = GisObjectKind.fromWire(type);
        String id = Json.optString(obj, "id", "");
        if (id.isBlank()) {
            return null;
        }
        String name = Json.optString(obj, "name", "");
        JsonObject geometryJson = Json.optObject(obj, "geometry");
        if (geometryJson == null) {
            geometryJson = Json.optObject(obj, "geojson");
        }
        Geometry geometry = geometryFromJson(geometryJson);
        if (geometry == null) {
            return null;
        }
        JsonObject propsJson = Json.optObject(obj, "properties");
        if (propsJson == null) {
            propsJson = Json.optObject(obj, "props");
        }
        Map<String, Object> props = new LinkedHashMap<>();
        if (propsJson != null) {
            for (Map.Entry<String, JsonElement> e : propsJson.entrySet()) {
                props.put(e.getKey(), jsonValue(e.getValue()));
            }
        }
        long effectiveWorldId = Json.optLong(obj, "worldId", worldId);
        String dim = Dimensions.normalize(Json.optString(obj, "dimension", dimension));
        int revision = Json.optInt(obj, "revision", 0);
        long updatedAt = Json.optLong(obj, "updatedAt", 0);
        return new GisObject(id, kind, effectiveWorldId, dim, name, geometry, revision, updatedAt,
                props, geometry.bbox());
    }

    public static List<GisObject> fromApiList(JsonArray array, long worldId, String dimension) {
        List<GisObject> out = new ArrayList<>();
        for (JsonElement e : array) {
            if (e.isJsonObject()) {
                GisObject o = fromApi(e.getAsJsonObject(), worldId, dimension);
                if (o != null) {
                    out.add(o);
                }
            }
        }
        return out;
    }

    /* ==================== GisObject ↔ StoredObject ==================== */

    public static GisStore.StoredObject toStored(GisObject o) {
        BBox b = o.bbox() == null ? o.geometry().bbox() : o.bbox();
        return new GisStore.StoredObject(o.worldId(), Dimensions.normalize(o.dimension()),
                o.id(), o.kind().wire(), o.name(), o.revision(),
                geometryToString(o.geometry()), Json.write(new LinkedHashMap<>(o.props())),
                b.minX(), b.minZ(), b.maxX(), b.maxZ(), o.updatedAt(), false);
    }

    public static GisObject fromStored(GisStore.StoredObject s) {
        Geometry geometry = geometryFromString(s.geometryJson());
        if (geometry == null) {
            return null;
        }
        Map<String, Object> props = new LinkedHashMap<>();
        JsonObject propsJson = Json.parseObjectQuietly(s.propsJson());
        for (Map.Entry<String, JsonElement> e : propsJson.entrySet()) {
            props.put(e.getKey(), jsonValue(e.getValue()));
        }
        BBox bbox = new BBox(s.minX(), s.minZ(), s.maxX(), s.maxZ());
        return new GisObject(s.objectId(), GisObjectKind.fromWire(s.kind()), s.worldId(),
                Dimensions.normalize(s.dimension()), s.name(), geometry, s.revision(),
                s.updatedAt(), props, bbox);
    }

    /* ==================== 工具 ==================== */

    public static JsonArray array(double... values) {
        JsonArray a = new JsonArray();
        for (double v : values) {
            a.add(v);
        }
        return a;
    }

    private static double num(JsonArray a, int idx) {
        return a.size() > idx && !a.get(idx).isJsonNull() ? a.get(idx).getAsDouble() : 0.0;
    }

    private static Object jsonValue(JsonElement e) {
        if (e == null || e.isJsonNull()) {
            return null;
        }
        if (e.isJsonPrimitive()) {
            var p = e.getAsJsonPrimitive();
            if (p.isBoolean()) {
                return p.getAsBoolean();
            }
            if (p.isNumber()) {
                return p.getAsDouble();
            }
            return p.getAsString();
        }
        return e.toString();
    }
}
