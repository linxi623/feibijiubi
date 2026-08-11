package com.feibijiubi.backend.common;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GlobalExceptionHandlerTests {
    private final GlobalExceptionHandler exceptionHandler = new GlobalExceptionHandler();

    @Test
    void mapsBusinessCodeToHttpStatus() {
        ResponseEntity<ApiResponse<Void>> response = exceptionHandler.handleBusinessException(
                new BusinessException(401, "请先登录")
        );

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertEquals(401, response.getBody().getCode());
    }

    @Test
    void mapsRedisFailureToServiceUnavailable() {
        ResponseEntity<ApiResponse<Void>> response =
                exceptionHandler.handleRedisOperationException(
                        new RedisOperationException(
                                "Redis 操作失败",
                                new RuntimeException("connection failed")
                        )
                );

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals(503, response.getBody().getCode());
        assertEquals("缓存服务暂不可用，请稍后再试", response.getBody().getMessage());
    }

    @Test
    void fallsBackToInternalServerErrorForInvalidCode() {
        ResponseEntity<ApiResponse<Void>> response = exceptionHandler.handleBusinessException(
                new BusinessException(999, "非法状态码")
        );

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals(500, response.getBody().getCode());
    }
}
