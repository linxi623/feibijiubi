package com.feibijiubi.backend.video.controller;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.feibijiubi.backend.auth.interceptor.AdminInterceptor;
import com.feibijiubi.backend.auth.interceptor.LoginInterceptor;
import com.feibijiubi.backend.auth.service.TokenContext;
import com.feibijiubi.backend.auth.service.TokenService;
import com.feibijiubi.backend.shared.BusinessException;
import com.feibijiubi.backend.shared.GlobalExceptionHandler;
import com.feibijiubi.backend.user.entity.User;
import com.feibijiubi.backend.user.mapper.UserMapper;
import com.feibijiubi.backend.video.service.VideoReviewService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Date;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M6-A regression for the only public review-status write endpoint: real login/admin interceptors,
 * operator taken from the login context. The service is mocked; the media gate itself is covered by
 * {@code VideoReviewMediaGateTests} and {@code VideoReviewPlaybackGateTests}.
 */
class VideoReviewAdminControllerTests {

    final VideoReviewService service = mock(VideoReviewService.class);
    final TokenService tokens = mock(TokenService.class);
    final UserMapper users = mock(UserMapper.class);
    MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new VideoReviewController(service))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(Jackson2ObjectMapperBuilder.json()
                        .featuresToDisable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build()))
                .addInterceptors(new LoginInterceptor(tokens, users), new AdminInterceptor())
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    void login(byte role) {
        when(tokens.parseToken("test")).thenReturn(new TokenContext(
                "test", 7, role, "jti", new Date(), new Date(), 1));
        User user = new User();
        user.setId(7);
        user.setRole(role);
        user.setStatus((byte) 0);
        user.setTokenVersion(1);
        when(users.selectAuthStateById(7)).thenReturn(user);
    }

    static String approve() {
        return "{\"result\":\"APPROVED\",\"reason\":null}";
    }

    @Test
    void reviewRequiresLogin() throws Exception {
        mvc.perform(post("/api/admin/videos/1/review")
                        .contentType(MediaType.APPLICATION_JSON).content(approve()))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void reviewRequiresAdministratorAndNormalUsersAreForbidden() throws Exception {
        login((byte) 0);

        mvc.perform(post("/api/admin/videos/1/review")
                        .header("Authorization", "Bearer test")
                        .contentType(MediaType.APPLICATION_JSON).content(approve()))
                .andExpect(status().isForbidden());

        verifyNoInteractions(service);
    }

    @Test
    void administratorReviewUsesTheLoginOperatorAndIgnoresClientSuppliedFields() throws Exception {
        login((byte) 1);

        mvc.perform(post("/api/admin/videos/1/review")
                        .header("Authorization", "Bearer test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"result\":\"APPROVED\",\"reason\":null,\"uid\":99,\"vid\":123}"))
                .andExpect(status().isOk());

        verify(service).videoReview(eq(7), eq(1), any());
    }

    @Test
    void superAdministratorCanAlsoReachTheReviewService() throws Exception {
        login((byte) 2);

        mvc.perform(post("/api/admin/videos/1/review")
                        .header("Authorization", "Bearer test")
                        .contentType(MediaType.APPLICATION_JSON).content(approve()))
                .andExpect(status().isOk());

        verify(service).videoReview(eq(7), eq(1), any());
    }

    @Test
    void invalidReviewBodyIs400BeforeTheService() throws Exception {
        login((byte) 1);

        mvc.perform(post("/api/admin/videos/1/review")
                        .header("Authorization", "Bearer test")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    @Test
    void mediaGateConflictKeeps409() throws Exception {
        login((byte) 1);
        doThrow(new BusinessException(409, "视频尚无可用播放媒体，不能审核通过"))
                .when(service).videoReview(eq(7), eq(1), any());

        mvc.perform(post("/api/admin/videos/1/review")
                        .header("Authorization", "Bearer test")
                        .contentType(MediaType.APPLICATION_JSON).content(approve()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409));
    }
}
