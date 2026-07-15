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

    public static String rateUpload(Integer uid) {
        return RedisConstants.RATE_UPLOAD_PREFIX + uid;
    }

    public static String loginFailTimes(String username) {
        return RedisConstants.LOGIN_FAIL_PREFIX + username;
    }
}
