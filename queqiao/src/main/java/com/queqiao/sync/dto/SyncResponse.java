package com.queqiao.sync.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 增量同步响应体，与环保小脑 {@code SyncResponse} 契约一致：
 * 包含 {@code hasMore} 与 {@code nextSince} 游标字段（非文档中的 watermark）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
public class SyncResponse<T> extends ApiResponse<List<T>> {

    private boolean hasMore;
    private long nextSince;

    public SyncResponse(int code, String message, List<T> data, boolean hasMore, long nextSince) {
        super(code, message, data);
        this.hasMore = hasMore;
        this.nextSince = nextSince;
    }
}
