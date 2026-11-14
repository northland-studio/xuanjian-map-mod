package top.xuanjian.map.core.model;

/**
 * 服务端 limits（契约 bootstrap.limits，字段缺省时用这里的保守默认值）。
 */
public record Limits(
        int maxPlanChunks,
        int maxBatchChunks,
        long maxCompressedBytes,
        long maxRawBytes,
        int maxSampleSize,
        int requestsPerMinute,
        int maxObjectsPerPage) {

    public static final Limits DEFAULT = new Limits(
            4096, 64,
            4L * 1024 * 1024,
            8L * 1024 * 1024,
            32,
            60,
            1000);

    public Limits(int maxPlanChunks, int maxBatchChunks, long maxCompressedBytes,
                  long maxRawBytes, int maxSampleSize, int requestsPerMinute) {
        this(maxPlanChunks, maxBatchChunks, maxCompressedBytes, maxRawBytes, maxSampleSize,
                requestsPerMinute, 1000);
    }

    public Limits {
        if (maxPlanChunks <= 0) {
            maxPlanChunks = DEFAULT.maxPlanChunks;
        }
        if (maxBatchChunks <= 0) {
            maxBatchChunks = DEFAULT.maxBatchChunks;
        }
        if (maxCompressedBytes <= 0) {
            maxCompressedBytes = DEFAULT.maxCompressedBytes;
        }
        if (maxRawBytes <= 0) {
            maxRawBytes = DEFAULT.maxRawBytes;
        }
        if (maxSampleSize <= 0) {
            maxSampleSize = DEFAULT.maxSampleSize;
        }
        if (requestsPerMinute <= 0) {
            requestsPerMinute = DEFAULT.requestsPerMinute;
        }
        if (maxObjectsPerPage <= 0) {
            maxObjectsPerPage = DEFAULT.maxObjectsPerPage;
        }
    }
}
