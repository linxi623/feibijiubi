package com.feibijiubi.backend.utils.redis;

public final class RedisConstants {
    public static final String LOGIN_CODE_PREFIX = "auth:login-code:";
    public static final String JWT_TOKEN_PREFIX = "auth:jwt-token:";
    public static final String VIDEO_DETAIL_PREFIX = "video:detail:v1:";

    public static final long LOGIN_CODE_EXPIRE_TIME = 60;
    public static final long VIDEO_DETAIL_EXPIRE_TIME = 60 * 30;
}
