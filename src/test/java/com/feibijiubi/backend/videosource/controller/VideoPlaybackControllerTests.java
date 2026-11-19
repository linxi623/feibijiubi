package com.feibijiubi.backend.videosource.controller;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.feibijiubi.backend.auth.interceptor.LoginInterceptor;
import com.feibijiubi.backend.auth.service.TokenContext;
import com.feibijiubi.backend.auth.service.TokenService;
import com.feibijiubi.backend.shared.BusinessException;
import com.feibijiubi.backend.shared.GlobalExceptionHandler;
import com.feibijiubi.backend.user.entity.User;
import com.feibijiubi.backend.user.mapper.UserMapper;
import com.feibijiubi.backend.videosource.service.VideoPlaybackService;
import com.feibijiubi.backend.videosource.vo.VideoEpisodePageVO;
import com.feibijiubi.backend.videosource.vo.VideoPlaybackVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Date;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M6-A controller with the real optional-login interceptor and global error mapping. The service is
 * mocked; no database/Redis/network is touched.
 */
class VideoPlaybackControllerTests {

    final VideoPlaybackService service = mock(VideoPlaybackService.class);
    final TokenService tokens = mock(TokenService.class);
    final UserMapper users = mock(UserMapper.class);
    MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new VideoPlaybackController(service))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(Jackson2ObjectMapperBuilder.json()
                        .featuresToDisable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build()))
                .addInterceptors(new LoginInterceptor(tokens, users))
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

    static VideoPlaybackVO playback() {
        return new VideoPlaybackVO(1, true, "OK", "HLS",
                "https://media.example.invalid/a.m3u8", 5L, "线路一",
                List.of(new VideoPlaybackVO.Line(5L, "线路一", true)));
    }

    @Test
    void anonymousAndLoggedInCallersAreBothAcceptedWithTheExpectedActor() throws Exception {
        when(service.playback(isNull(), any(), any())).thenReturn(playback());
        mvc.perform(get("/api/videos/1/playback"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.playable").value(true))
                .andExpect(jsonPath("$.data.url").value("https://media.example.invalid/a.m3u8"));
        verify(service).playback(null, 1, null);

        login((byte) 0);
        mvc.perform(get("/api/videos/1/playback").header("Authorization", "Bearer test"))
                .andExpect(status().isOk());
        verify(service).playback(7, 1, null);
    }

    @Test
    void playbackForwardsLineIdAsTextAndSetsNoStore() throws Exception {
        when(service.playback(any(), any(), any())).thenReturn(playback());

        mvc.perform(get("/api/videos/1/playback").param("lineId", "7"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"));

        verify(service).playback(null, 1, "7");
    }

    @Test
    void episodesForwardCursorAndSizeAndSetNoStore() throws Exception {
        when(service.episodes(any(), any(), any(), any())).thenReturn(
                new VideoEpisodePageVO(List.of(new VideoEpisodePageVO.Item(2, "第二集")), true, "11"));

        mvc.perform(get("/api/videos/1/episodes").param("cursor", "11").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.items[0].vid").value(2))
                .andExpect(jsonPath("$.data.items[0].displayTitle").value("第二集"))
                .andExpect(jsonPath("$.data.hasMore").value(true))
                .andExpect(jsonPath("$.data.nextCursor").value("11"));

        verify(service).episodes(null, 1, "11", "20");
    }

    @Test
    void serviceBusinessErrorsKeepTheirHttpStatus() throws Exception {
        when(service.playback(any(), any(), any()))
                .thenThrow(new BusinessException(403, "你无权查看此视频"));
        when(service.episodes(any(), any(), any(), any()))
                .thenThrow(new BusinessException(404, "视频不存在"));

        mvc.perform(get("/api/videos/1/playback"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
        mvc.perform(get("/api/videos/1/episodes"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    void invalidVidPathIs400() throws Exception {
        when(service.playback(null, 0, null))
                .thenThrow(new BusinessException(400, "视频参数不合法"));

        mvc.perform(get("/api/videos/not-a-number/playback")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/videos/0/playback"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
        verify(service).playback(null, 0, null);
    }
}
