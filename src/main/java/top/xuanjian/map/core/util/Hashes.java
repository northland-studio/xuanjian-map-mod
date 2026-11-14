package top.xuanjian.map.core.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** 哈希与幂等键工具。 */
public final class Hashes {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private Hashes() {
    }

    public static String sha1Hex(String text) {
        return hex(digest("SHA-1", text.getBytes(StandardCharsets.UTF_8)));
    }

    public static String sha1Hex(byte[] data) {
        return hex(digest("SHA-1", data));
    }

    public static byte[] sha1(byte[] data) {
        return digest("SHA-1", data);
    }

    /** 字节数组转小写十六进制。 */
    public static String hex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            out[i * 2] = HEX[v >>> 4];
            out[i * 2 + 1] = HEX[v & 0x0F];
        }
        return new String(out);
    }

    /**
     * 按契约生成 Idempotency-Key：只允许 [A-Za-z0-9_-]，长度 8..64。
     *
     * <p>由 (worldId, dimension, batchSeq, 该批 chunk 坐标) 派生，重试时复用同一 key，
     * 服务端可据此识别重放；不含用户身份信息。
     */
    public static String idempotencyKey(long worldId, String dimension, long batchSeq, long chunkHash) {
        String seed = worldId + "|" + dimension + "|" + batchSeq + "|" + Long.toHexString(chunkHash);
        return "xj" + sha1Hex(seed).substring(0, 30);
    }

    private static byte[] digest(String algorithm, byte[] data) {
        try {
            return MessageDigest.getInstance(algorithm).digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 缺少摘要算法: " + algorithm, e);
        }
    }
}
