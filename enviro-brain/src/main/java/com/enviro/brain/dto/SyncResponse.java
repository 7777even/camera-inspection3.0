package com.enviro.brain.dto;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import java.util.List;

@Getter
@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
public class SyncResponse<T> extends ApiResponse<List<T>> {

    private static final long serialVersionUID = 1L;

    private boolean hasMore;
    private long nextSince;

    public SyncResponse(int code, String message, List<T> data,
                        boolean hasMore, long nextSince) {
        super(code, message, data);
        this.hasMore = hasMore;
        this.nextSince = nextSince;
    }

    public static <T> SyncResponse<T> of(List<T> data, boolean hasMore, long nextSince) {
        return new SyncResponse<>(200, "success", data, hasMore, nextSince);
    }
}
