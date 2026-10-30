package com.feibijiubi.backend.utils.redis;

public final class RedisConstants {
    // 登录相关
    public static final String JWT_TOKEN_PREFIX = "auth:jwt-token:";
    public static final String LOGIN_FAIL_PREFIX = "auth:login-fail:";

    // 客户端请求cosKey的限流
    public static final String RATE_UPLOAD_PREFIX = "rate:upload-token:user:";

    // T15：播放/分享准入的固定窗口计数（只做准入，不做去重；去重在 MySQL 幂等表）
    public static final String RATE_VIDEO_INTERACTION_ACTION_PREFIX =
            "rate:video-interaction:action:";
    public static final String RATE_VIDEO_INTERACTION_ACTOR_PREFIX =
            "rate:video-interaction:actor:";

    // M3-B：管理员外部来源预览的每管理员固定窗口配额（与视频互动限流互不影响）
    public static final String RATE_EXTERNAL_VIDEO_SOURCE_PREVIEW_PREFIX =
            "rate:external-video-source:preview:admin:";

    // 分类
    public static final String CATEGORY_PREFIX = "category:tree:v1";

    // 视频状态的统计
    public static final String VIDEO_STATUS_PREFIX = "video:status:v1:";
    public static final String DIRTY_VIDEO_PREFIX = "dirty:video:v1";
    // 分布式锁
    public static final String LOCK_KEY = "lock:video-status:vid:";
    // redis的幂等Key
    public static final String PROCESSED_PREFIX = "video:status:process:v1:";
    public static final String FEED_HOT_VIDEOS_PREFIX = "feed:hot:videos:v1";






    public static final long CATEGORY_EXPIRE_TIME = 60 * 60 * 24;

    public static final long LOGIN_FAIL_EXPIRE_TIME = 60 * 10;
    public static final long LOGIN_FAIL_MAX_TIMES = 10;

    public static final long RATE_UPLOAD_WINDOW_SECONDS = 60;
    public static final long RATE_UPLOAD_MAX_REQUESTS = 10;

    private RedisConstants() {}
}
