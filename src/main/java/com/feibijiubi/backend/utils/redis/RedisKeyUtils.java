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

    /**
     * T15 准入的单动作维度：同一 actor 对同一动作的每窗口请求数。
     *
     * <p>键里只有 actor 哈希与动作名，不含 uid、访客原文、IP 或 UA。</p>
     */
    public static String rateVideoInteractionAction(String actorHash, String action) {
        return RedisConstants.RATE_VIDEO_INTERACTION_ACTION_PREFIX + actorHash + ":" + action;
    }

    /** T15 准入的合计维度：同一 actor 所有动作的每窗口请求数。 */
    public static String rateVideoInteractionActor(String actorHash) {
        return RedisConstants.RATE_VIDEO_INTERACTION_ACTOR_PREFIX + actorHash;
    }

    public static String categoryTree() {
        return RedisConstants.CATEGORY_PREFIX;
    }

    public static String videoStatus(Integer vid) {
        return RedisConstants.VIDEO_STATUS_PREFIX + vid;
    }

    /** 不设置 TTL：重建崩溃后由下一位持有 vid 锁的调用者接管清理。 */
    public static String videoStatusRebuildGate(Integer vid) {
        return RedisConstants.VIDEO_STATUS_PREFIX + "rebuild-gate:" + vid;
    }

    public static String feedHotVideos() {
        return RedisConstants.FEED_HOT_VIDEOS_PREFIX;
    }

    public static String processedKey(String eventId) {
        return RedisConstants.PROCESSED_PREFIX + eventId;
    }

    public static String dirtyVideo() {
        return RedisConstants.DIRTY_VIDEO_PREFIX;
    }

    public static String lockKey(Integer vid) {
        return RedisConstants.LOCK_KEY + vid;
    }
}
