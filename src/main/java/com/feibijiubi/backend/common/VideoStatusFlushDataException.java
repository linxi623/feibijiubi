package com.feibijiubi.backend.common;

import java.util.List;

public class VideoStatusFlushDataException extends RuntimeException {

    private final Integer vid;
    private final List<Long> consumedEventIds;
    private final String errorSummary;

    public VideoStatusFlushDataException(
            Integer vid,
            List<Long> consumedEventIds,
            String errorSummary
    ) {
        super(errorSummary);
        this.vid = vid;
        this.consumedEventIds = List.copyOf(consumedEventIds);
        this.errorSummary = errorSummary;
    }

    public Integer getVid() {
        return vid;
    }

    public List<Long> getConsumedEventIds() {
        return consumedEventIds;
    }

    public String getErrorSummary() {
        return errorSummary;
    }
}
