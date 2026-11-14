package top.xuanjian.map.core.geo;

/**
 * HUD 定位结果（当前城区 / 当前道路 / 附近 Marker 数）。
 *
 * @param districtId    所在城区 ID，null 表示不在任何城区
 * @param districtName  所在城区名
 * @param roadId        所在道路 ID，null 表示不在任何道路缓冲区
 * @param roadName      所在道路名
 * @param nearbyMarkers 附近 GIS Marker 数量（HUD 展开态与 Minimap 用，不是实时上传数据）
 */
public record LocationInfo(String districtId, String districtName,
                           String roadId, String roadName,
                           int nearbyMarkers) {

    public static final LocationInfo EMPTY = new LocationInfo(null, null, null, null, 0);

    public boolean hasDistrict() {
        return districtId != null;
    }

    public boolean hasRoad() {
        return roadId != null;
    }

    public String districtLabel() {
        return districtName == null || districtName.isBlank() ? "未知区域" : districtName;
    }

    public String roadLabel() {
        return roadName == null || roadName.isBlank() ? "无道路" : roadName;
    }
}
