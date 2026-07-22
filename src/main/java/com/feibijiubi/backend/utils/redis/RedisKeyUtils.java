package com.feibijiubi.backend.utils.redis;

public class RedisKeyUtils {
    private RedisKeyUtils() {}
    public static String jwtToken(String jti) {
        return RedisConstants.JWT_TOKEN_PREFIX + jti;
    }

    public static String rateUpload(Integer uid) {
        return RedisConstants.RATE_UPLOAD_PREFIX + uid;
    }

    public static String loginFailTimes(String username) {
        return RedisConstants.LOGIN_FAIL_PREFIX + username;
    }

    public static String categoryTree() {
        return RedisConstants.CATEGORY_PREFIX;
    }

    public static String videoStatus(Integer vid) {
        return RedisConstants.VIDEO_STATUS_PREFIX + vid;
    }

    public static String feedHotVideos() {
        return RedisConstants.FEED_HOT_VIDEOS_PREFIX;
    }

    public static String processedKey(String eventId) {
        return RedisConstants.PROCESSED_PREFIX + eventId;
    }

    public static String videoDelta(Integer vid) {
        return RedisConstants.VIDEO_STATUS_DELTA_PREFIX + vid;
    }

    public static String videoStatusDelta(Integer vid) {
        return RedisConstants.VIDEO_STATUS_DELTA_PREFIX + vid;
    }

    public static String dirtyVideo() {
        return RedisConstants.DIRTY_VIDEO_PREFIX;
    }


    public static String flushVideo(String batchId) {
        return RedisConstants.VIDEO_FLUSH_CLEAN_PREFIX + batchId;
    }

    public static String videoStatusInitLock(Integer vid) {
        return RedisConstants.VIDEO_STATUS_INIT_LOCK_PREFIX + vid;
    }
}
