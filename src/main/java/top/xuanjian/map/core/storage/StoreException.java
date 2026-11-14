package top.xuanjian.map.core.storage;

/** 本地缓存存储异常（统一 runtime，调用方在 DB 线程捕获并上报到 UI）。 */
public class StoreException extends RuntimeException {

    public StoreException(String message) {
        super(message);
    }

    public StoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
