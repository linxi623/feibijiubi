package com.feibijiubi.backend.controller;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.feibijiubi.backend.common.*;
import com.feibijiubi.backend.dto.*;
import com.feibijiubi.backend.entity.User;
import com.feibijiubi.backend.interceptor.*;
import com.feibijiubi.backend.mapper.UserMapper;
import com.feibijiubi.backend.service.auth.*;
import com.feibijiubi.backend.service.localvideo.LocalEpisodeSourceService;
import com.feibijiubi.backend.vo.LocalEpisodeSourceVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class LocalEpisodeSourceAdminControllerTests {
    final LocalEpisodeSourceService service = mock(LocalEpisodeSourceService.class);
    final TokenService tokens = mock(TokenService.class);
    final UserMapper users = mock(UserMapper.class);
    static final String BASE = "/api/admin/local-video-episodes/1/sources";
    MockMvc mvc;
    @BeforeEach void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new LocalEpisodeSourceAdminController(service))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(Jackson2ObjectMapperBuilder.json()
                        .featuresToDisable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build()))
                .addInterceptors(new LoginInterceptor(tokens, users), new AdminInterceptor())
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }
    void login(byte role) {
        when(tokens.parseToken("test")).thenReturn(new TokenContext("test", 7, role, "jti", new Date(), new Date(), 1));
        var user = new User(); user.setId(7); user.setRole(role); user.setStatus((byte)0); user.setTokenVersion(1);
        when(users.selectAuthStateById(7)).thenReturn(user);
    }
    List<MockHttpServletRequestBuilder> routes() {
        return List.of(get(BASE), get(BASE + "/candidates"),
                post(BASE + "/candidates").contentType(MediaType.APPLICATION_JSON).content("{}"),
                post(BASE + "/candidates/9/confirm").contentType(MediaType.APPLICATION_JSON).content("{}"));
    }
    @Test void allRoutesRequireLogin() throws Exception {
        for (var request : routes()) mvc.perform(request).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }
    @Test void allRoutesRequireAdministrator() throws Exception {
        login((byte)0);
        for (var request : routes()) mvc.perform(request.header("Authorization", "Bearer test")).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }
    @Test void captureTakesActorFromLoginAndIgnoresClientEvidence() throws Exception {
        login((byte)1);
        mvc.perform(post(BASE + "/candidates").header("Authorization", "Bearer test").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {"sourceCode":"demo","workId":"7","lineOrdinal":1,"entryOrdinal":2,
                         "expectedRevision":0,"adminId":99,"address":"https://evil.invalid/secret","probeStatus":"SUCCEEDED"}
                        """))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
        verify(service).capture(7, 1, new LocalEpisodeSourceCaptureDTO("demo", "7", 1, 2, 0L, null));
    }
    @Test void confirmMappingAndConflictStatus() throws Exception {
        login((byte)1);
        when(service.confirm(any(), anyLong(), anyLong(), any())).thenThrow(new BusinessException(409, "EPISODE_REVISION_CONFLICT"));
        mvc.perform(post(BASE + "/candidates/9/confirm").header("Authorization", "Bearer test").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":0,\"durationSeconds\":12.5,\"reason\":\"checked\",\"adminId\":99}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(409))
                .andExpect(header().string("Cache-Control", "no-store"));
        verify(service).confirm(7, 1, 9, new LocalEpisodeSourceConfirmDTO(0L, 12.5, "checked"));
    }
    @Test void invalidBodyAndMediaTypeAreLocal400And415() throws Exception {
        login((byte)1);
        for (String suffix : List.of("/candidates", "/candidates/9/confirm")) {
            mvc.perform(post(BASE + suffix).header("Authorization", "Bearer test").contentType(MediaType.APPLICATION_JSON).content("{"))
                    .andExpect(status().isBadRequest());
            mvc.perform(post(BASE + suffix).header("Authorization", "Bearer test").contentType(MediaType.TEXT_PLAIN).content("x"))
                    .andExpect(status().isUnsupportedMediaType());
        }
        verifyNoInteractions(service);
    }
    @Test void currentAndCandidatePagingAreReadOnlyProjections() throws Exception {
        login((byte)1);
        when(service.current(1)).thenReturn(new LocalEpisodeSourceVO(2, 12.5, List.of()));
        when(service.candidates(1, 9, 20)).thenReturn(List.of());
        mvc.perform(get(BASE).header("Authorization", "Bearer test")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.revision").value(2)).andExpect(jsonPath("$.data.durationSeconds").value(12.5))
                .andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(get(BASE + "/candidates").param("afterId", "9").header("Authorization", "Bearer test"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
        verify(service).candidates(1, 9, 20);
    }
}
