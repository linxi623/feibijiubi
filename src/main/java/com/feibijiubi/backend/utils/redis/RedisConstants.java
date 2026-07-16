package com.feibijiubi.backend.utils.redis;

public final class RedisConstants {
    public static final String LOGIN_CODE_PREFIX = "auth:login-code:";
    public static final String JWT_TOKEN_PREFIX = "auth:jwt-token:";
    public static final String LOGIN_FAIL_PREFIX = "auth:login-fail:";
    public static final String VIDEO_DETAIL_PREFIX = "video:detail:v1:";
    public static final String RATE_UPLOAD_PREFIX = "rate:upload-token:user:";
    public static final String CATEGORY_PREFIX = "category:tree:v1:";

    public static final long CATEGORY_EXPIRE_TIME = 60 * 60 * 24;

    public static final long LOGIN_FAIL_EXPIRE_TIME = 60 * 10;
    public static final long LOGIN_FAIL_MAX_TIMES = 10;

    public static final long RATE_UPLOAD_WINDOW_SECONDS = 60;
    public static final long RATE_UPLOAD_MAX_REQUESTS = 10;

    private RedisConstants() {}
}
