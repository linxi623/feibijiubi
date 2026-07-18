package com.feibijiubi.backend.event;

public enum VideoStatusEventType {
    PLAY("playTimes", 1.0),
    LIKE("likeTimes", 1.5),
    UNLIKE("unlikeTimes", -1.0),
    COMMENT("commentTimes", 3.5),
    COIN("coinTimes", 4.0),
    SHARE("shareTimes", 2.5),
    COLLECT("collectTimes", 4.0),
    DANMU("danmuTimes", 2.0);

    private final String redisField;
    private final double scorePerUnit;

    VideoStatusEventType(String redisField, double scorePerUnit) {
        this.redisField = redisField;
        this.scorePerUnit = scorePerUnit;
    }

    public String redisField() {
        return redisField;
    }
    public double calculateHotScore(long delta) {
        return scorePerUnit * delta;
    }
}
