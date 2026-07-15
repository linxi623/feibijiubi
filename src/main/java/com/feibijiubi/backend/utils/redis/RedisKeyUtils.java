package com.feibijiubi.backend.utils.redis;

public class RedisKeyUtils {
    private RedisKeyUtils() {}

    public static String loginCode(String phone) {
        return RedisConstants.LOGIN_CODE_PREFIX + phone;
    }

    public static String videoDetail(Integer videoId) {
        return RedisConstants.VIDEO_DETAIL_PREFIX + videoId;
    }

    public static String jwtToken(String jti) {
        return RedisConstants.JWT_TOKEN_PREFIX + jti;
    }
}
