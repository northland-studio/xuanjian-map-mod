package top.xuanjian.map.core.model;

/**
 * 二维轴对齐包围盒（Minecraft X/Z 平面）。
 *
 * <p>GIS 几何统一：X/Z 为平面坐标，Y 只作为高度属性；因此包围盒只含 X/Z。
 */
public record BBox(double minX, double minZ, double maxX, double maxZ) {

    public BBox {
        if (minX > maxX) {
            double t = minX;
            minX = maxX;
            maxX = t;
        }
        if (minZ > maxZ) {
            double t = minZ;
            minZ = maxZ;
            maxZ = t;
        }
    }

    public static BBox of(double x1, double z1, double x2, double z2) {
        return new BBox(x1, z1, x2, z2);
    }

    public static BBox point(double x, double z) {
        return new BBox(x, z, x, z);
    }

    /** 契约 bbox 参数：minX,minZ,maxX,maxZ。 */
    public static BBox parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("bbox 为空");
        }
        String[] parts = text.trim().split(",");
        if (parts.length != 4) {
            throw new IllegalArgumentException("bbox 必须是 minX,minZ,maxX,maxZ 四段: " + text);
        }
        try {
            return new BBox(
                    Double.parseDouble(parts[0].trim()),
                    Double.parseDouble(parts[1].trim()),
                    Double.parseDouble(parts[2].trim()),
                    Double.parseDouble(parts[3].trim()));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("bbox 含非数字: " + text, e);
        }
    }

    public double width() {
        return maxX - minX;
    }

    public double depth() {
        return maxZ - minZ;
    }

    public double area() {
        return width() * depth();
    }

    public boolean contains(double x, double z) {
        return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
    }

    public boolean contains(BBox other) {
        return other.minX >= minX && other.maxX <= maxX && other.minZ >= minZ && other.maxZ <= maxZ;
    }

    public boolean intersects(BBox other) {
        return other.maxX >= minX && other.minX <= maxX && other.maxZ >= minZ && other.minZ <= maxZ;
    }

    public BBox expand(double amount) {
        return new BBox(minX - amount, minZ - amount, maxX + amount, maxZ + amount);
    }

    public BBox union(BBox other) {
        return new BBox(
                Math.min(minX, other.minX),
                Math.min(minZ, other.minZ),
                Math.max(maxX, other.maxX),
                Math.max(maxZ, other.maxZ));
    }

    public double centerX() {
        return (minX + maxX) / 2.0;
    }

    public double centerZ() {
        return (minZ + maxZ) / 2.0;
    }

    @Override
    public String toString() {
        return minX + "," + minZ + "," + maxX + "," + maxZ;
    }
}
