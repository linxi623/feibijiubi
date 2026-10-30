package com.feibijiubi.backend.auth.service;

import java.util.Date;

public record TokenContext(
        String token,
        Integer userId,
        Byte role,
        String jti,
        Date issuedAt,
        Date expireTime,
        Integer tokenVersion
) {
}
