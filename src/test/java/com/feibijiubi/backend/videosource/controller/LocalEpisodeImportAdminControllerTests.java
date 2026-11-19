package com.feibijiubi.backend.videosource.controller;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.feibijiubi.backend.auth.interceptor.AdminInterceptor;
import com.feibijiubi.backend.auth.interceptor.LoginInterceptor;
import com.feibijiubi.backend.auth.service.TokenContext;
import com.feibijiubi.backend.auth.service.TokenService;
import com.feibijiubi.backend.shared.BusinessException;
import com.feibijiubi.backend.shared.GlobalExceptionHandler;
import com.feibijiubi.backend.user.entity.User;
import com.feibijiubi.backend.user.mapper.UserMapper;
import com.feibijiubi.backend.videosource.dto.LocalEpisodeImportDTO;
import com.feibijiubi.backend.videosource.service.LocalEpisodeImportService;
import com.feibijiubi.backend.videosource.vo.LocalEpisodeImportVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Date;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LocalEpisodeImportAdminControllerTests {
    final LocalEpisodeImportService service = mock(LocalEpisodeImportService.class);
    final TokenService tokens = mock(TokenService.class);
    final UserMapper users = mock(UserMapper.class);
    static final String BASE = "/api/admin/local-video-episodes/1/import";
    MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new LocalEpisodeImportAdminController(service))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(Jackson2ObjectMapperBuilder.json()
                        .featuresToDisable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build()))
                .addInterceptors(new LoginInterceptor(tokens, users), new AdminInterceptor())
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    void login(byte role) {
        when(tokens.parseToken("test")).thenReturn(new TokenContext("test", 7, role, "jti",
                new Date(), new Date(), 1));
        var user = new User();
        user.setId(7);
        user.setRole(role);
        user.setStatus((byte) 0);
        user.setTokenVersion(1);
        when(users.selectAuthStateById(7)).thenReturn(user);
    }

    List<MockHttpServletRequestBuilder> routes() {
        return List.of(post(BASE).contentType(MediaType.APPLICATION_JSON).content("{}"));
    }

    @Test
    void routeRequiresLogin() throws Exception {
        for (var request : routes()) mvc.perform(request).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void routeRequiresAdministrator() throws Exception {
        login((byte) 0);
        for (var request : routes()) {
            mvc.perform(request.header("Authorization", "Bearer test")).andExpect(status().isForbidden());
        }
        verifyNoInteractions(service);
    }

    @Test
    void actorComesFromLoginAndClientCannotSupplyImportFields() throws Exception {
        login((byte) 1);
        when(service.importEpisode(any(), anyLong(), any())).thenReturn(
                new LocalEpisodeImportVO(1L, 55, 12.5, 0L, 0, false));

        mvc.perform(post(BASE).header("Authorization", "Bearer test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {"expectedRevision":0,"tempCoverKey":"temp/covers/7/cover.png",
                         "title":"导入标题","mcId":"movie","scId":"action","visibility":1,
                         "uid":99,"vid":123,"sourceType":1,"status":1,"duration":999,
                         "videoUrl":"https://evil.invalid/v.mp4","videoKey":"fake/key"}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.episodeId").value(1))
                .andExpect(jsonPath("$.data.vid").value(55))
                .andExpect(jsonPath("$.data.reused").value(false))
                .andExpect(header().string("Cache-Control", "no-store"));

        verify(service).importEpisode(7, 1,
                new LocalEpisodeImportDTO(0L, "temp/covers/7/cover.png",
                        "导入标题", "movie", "action", 1));
    }

    @Test
    void businessConflictKeepsNoStoreHeader() throws Exception {
        login((byte) 1);
        when(service.importEpisode(any(), anyLong(), any()))
                .thenThrow(new BusinessException(409, "EPISODE_REVISION_CONFLICT"));

        mvc.perform(post(BASE).header("Authorization", "Bearer test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":0,\"tempCoverKey\":\"temp/covers/7/c.png\","
                                + "\"title\":\"t\",\"mcId\":\"movie\",\"scId\":\"action\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void invalidBodyAndMediaTypeAreLocal400And415() throws Exception {
        login((byte) 1);
        mvc.perform(post(BASE).header("Authorization", "Bearer test")
                        .contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(post(BASE).header("Authorization", "Bearer test")
                        .contentType(MediaType.TEXT_PLAIN).content("x"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(header().string("Cache-Control", "no-store"));
        verifyNoInteractions(service);
    }
}
