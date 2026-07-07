package com.queqiao.sync.exception;

/**
 * 同步客户端异常：调用环保小脑同步接口失败（不可达 / 非 2xx / 解析失败）。
 */
public class SyncClientException extends RuntimeException {
    public SyncClientException(String message) {
        super(message);
    }

    public SyncClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
