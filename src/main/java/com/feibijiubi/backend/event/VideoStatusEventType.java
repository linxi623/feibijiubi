package com.feibijiubi.backend.event;

public enum VideoStatusEventType {
    PLAY("playTimes", "playDelta", 1.0),
    LIKE("likeTimes", "likeDelta", 1.5),
    UNLIKE("unlikeTimes", "unlikeDelta", -1.0),
    COMMENT("commentTimes", "commentDelta", 3.5),
    COIN("coinTimes", "coinDelta", 4.0),
    SHARE("shareTimes", "shareDelta", 2.5),
    COLLECT("collectTimes", "collectDelta", 4.0),
    DANMU("danmuTimes", "danmuDelta", 2.0);

    private final String redisField;
    private final String deltaField;
    private final double scorePerUnit;



    VideoStatusEventType(String redisField, String deltaField, double scorePerUnit) {
        this.redisField = redisField;
        this.deltaField = deltaField;
        this.scorePerUnit = scorePerUnit;
    }

    public String deltaField() {
        return deltaField;
    }
    public String redisField() {
        return redisField;
    }
    public double calculateHotScore(long delta) {
        return scorePerUnit * delta;
    }
}
