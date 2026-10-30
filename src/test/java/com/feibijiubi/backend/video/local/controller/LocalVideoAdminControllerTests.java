package com.feibijiubi.backend.video.local.controller;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.feibijiubi.backend.common.BusinessException;
import com.feibijiubi.backend.common.GlobalExceptionHandler;
import com.feibijiubi.backend.entity.User;
import com.feibijiubi.backend.interceptor.AdminInterceptor;
import com.feibijiubi.backend.interceptor.LoginInterceptor;
import com.feibijiubi.backend.mapper.UserMapper;
import com.feibijiubi.backend.service.auth.TokenContext;
import com.feibijiubi.backend.service.auth.TokenService;
import com.feibijiubi.backend.video.local.service.LocalVideoCatalogService;
import com.feibijiubi.backend.video.local.vo.LocalVideoEpisodeVO;
import com.feibijiubi.backend.video.local.vo.LocalVideoPageVO;
import com.feibijiubi.backend.video.local.vo.LocalVideoSeriesVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * S1：四个管理员本地作品/剧集接口的 HTTP 接线测试（{@code MockMvc} standalone，不启动容器、不连库/Redis）。
 *
 * <p>拦截器用<b>真实</b>实现：{@link LoginInterceptor}（只把 token 校验替换成 mock 的
 * {@link TokenService}/{@link UserMapper}）与 {@link AdminInterceptor}，因此 401/403 来自真实登录/权限流程，
 * 而不是“看到 {@code @AdminOnly} 注解就算通过”。操作人走真实约定：拦截器把 {@code currentUserId} 写进
 * 请求属性，controller 只从那里读。</p>
 *
 * <p>Bean Validation 用显式装配的 {@link LocalValidatorFactoryBean}，JSON 用与 Spring Boot 同策略的
 * mapper（关闭 {@code FAIL_ON_UNKNOWN_PROPERTIES}）：这样“多传 {@code vid}/{@code durationSeconds}/
 * {@code createdBy} 被忽略且不生效”是真的按生产方式跑的，而不是靠测试偶然默认值。</p>
 *
 * <p>服务层是 mock：本类只验证“参数怎么传、400/404/500 怎么变成 HTTP 状态、响应里有什么”。
 * 服务层自身的校验与 mapper 交互在 {@code LocalVideoCatalogServiceTests} 里验证。</p>
 */
class LocalVideoAdminControllerTests {

    private static final Integer ADMIN_UID = 7;
    private static final String BEARER = "Bearer token-value";
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 1, 2, 3, 4, 5);

    private static final String SERIES_PATH = "/api/admin/local-video-series";
    private static final String EPISODES_PATH = "/api/admin/local-video-series/9/episodes";

    private final TokenService tokenService = mock(TokenService.class);
    private final UserMapper userMapper = mock(UserMapper.class);
    private final LocalVideoCatalogService catalogService = mock(LocalVideoCatalogService.class);

    private LocalValidatorFactoryBean validator;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders
                .standaloneSetup(new LocalVideoAdminController(catalogService))
                .setValidator(validator)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(
                        Jackson2ObjectMapperBuilder.json()
                                .featuresToDisable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                                .build()))
                .addInterceptors(new LoginInterceptor(tokenService, userMapper), new AdminInterceptor())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void tearDown() {
        validator.close();
    }

    @Test
    void missingOrMalformedBodiesAre400AndUnsupportedTypeIs415() throws Exception {
        loginAs((byte) 1);
        for (String path : List.of(SERIES_PATH, EPISODES_PATH)) {
            for (String body : List.of("", "{broken-json")) {
                mockMvc.perform(post(path).header("Authorization", BEARER)
                                .contentType(MediaType.APPLICATION_JSON).content(body))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.code").value(400));
            }
            mockMvc.perform(post(path).header("Authorization", BEARER)
                            .contentType(MediaType.TEXT_PLAIN).content("not-json"))
                    .andExpect(status().isUnsupportedMediaType())
                    .andExpect(jsonPath("$.code").value(415));
        }
        verifyNoInteractions(catalogService);
    }

    // ---------------------------------------------------------------- 鉴权

    @Test
    void missingTokenIs401OnAllFourRoutesWithoutTouchingTheService() throws Exception {
        for (MockHttpServletRequestBuilder request : List.of(
                post(SERIES_PATH).contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"作品甲\"}"),
                get(SERIES_PATH),
                post(EPISODES_PATH).contentType(MediaType.APPLICATION_JSON).content("{}"),
                get(EPISODES_PATH))) {
            mockMvc.perform(request)
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value(401));
        }

        verifyNoInteractions(catalogService);
    }

    @Test
    void commonUserIs403ByTheRealAdminInterceptorOnAllFourRoutes() throws Exception {
        loginAs((byte) 0);

        for (MockHttpServletRequestBuilder request : List.of(
                post(SERIES_PATH).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"作品甲\"}").header("Authorization", BEARER),
                get(SERIES_PATH).header("Authorization", BEARER),
                post(EPISODES_PATH).contentType(MediaType.APPLICATION_JSON)
                        .content("{}").header("Authorization", BEARER),
                get(EPISODES_PATH).header("Authorization", BEARER))) {
            mockMvc.perform(request)
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(403));
        }

        verifyNoInteractions(catalogService);
    }

    // ---------------------------------------------------------------- 创建作品

    @Test
    void createSeriesPassesOnlyTheLoginUidAndTitleAndIgnoresClientSuppliedFields() throws Exception {
        loginAs((byte) 1);
        // controller 原样转发 title（trim 是服务层的职责，见 LocalVideoCatalogServiceTests）。
        when(catalogService.createSeries(ADMIN_UID, "  示例作品  ")).thenReturn(seriesVO());

        mockMvc.perform(post(SERIES_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"  示例作品  \",\"localIdentity\":\"客户端伪造\","
                                + "\"createdBy\":99,\"vid\":5,\"durationSeconds\":12.5}")
                        .header("Authorization", BEARER))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("本地作品已创建"))
                .andExpect(jsonPath("$.data.id").value(41))
                .andExpect(jsonPath("$.data.title").value("示例作品"))
                .andExpect(jsonPath("$.data.createdBy").value(ADMIN_UID))
                .andExpect(jsonPath("$.data.localIdentity").value("local-identity-from-server"));

        // 只有 (登录 uid, title) 进入服务层：客户端传的 identity/createdBy/vid/duration 都没有入口。
        verify(catalogService).createSeries(ADMIN_UID, "  示例作品  ");
        verify(catalogService, never()).createEpisode(any(), anyLong(), any());
    }

    @Test
    void blankOrTooLongDeclaredFieldsAre400WithoutCallingTheService() throws Exception {
        loginAs((byte) 1);

        // 作品的 title 是必填：空白 / 空串 / 缺失 / 超长全都应在 Bean Validation 处被拒。
        for (String body : List.of(
                "{\"title\":\"   \"}",
                "{\"title\":\"\"}",
                "{}",
                "{\"title\":\"" + "长".repeat(201) + "\"}")) {
            mockMvc.perform(post(SERIES_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .header("Authorization", BEARER))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(400));
        }

        // 剧集的 displayTitle 可空，所以这里只能断言"长度超上限"这一类 Bean Validation 规则；
        // "非 null 但 trim 后为空"是服务层规则，由 LocalVideoCatalogServiceTests 验证（本类的服务是 mock）。
        mockMvc.perform(post(EPISODES_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayTitle\":\"" + "长".repeat(256) + "\"}")
                        .header("Authorization", BEARER))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        verify(catalogService, never()).createSeries(any(), any());
        verify(catalogService, never()).createEpisode(any(), anyLong(), any());
    }

    // ---------------------------------------------------------------- 创建剧集

    @Test
    void createEpisodeAllowsOmittedDisplayTitleAndReturnsNullableFutureFields() throws Exception {
        loginAs((byte) 1);
        when(catalogService.createEpisode(ADMIN_UID, 9L, null)).thenReturn(episodeVO());

        mockMvc.perform(post(EPISODES_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"vid\":5,\"durationSeconds\":12.5,\"createdBy\":99}")
                        .header("Authorization", BEARER))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.id").value(51))
                .andExpect(jsonPath("$.data.seriesId").value(9))
                // Jackson 默认把 null 字段也序列化出来（字段存在、值为 null），
                // doesNotExist 对 null 通过，因此这里表达的是“值必须是 null”。
                .andExpect(jsonPath("$.data.displayTitle").doesNotExist())
                .andExpect(jsonPath("$.data.vid").doesNotExist())
                .andExpect(jsonPath("$.data.durationSeconds").doesNotExist());

        verify(catalogService).createEpisode(ADMIN_UID, 9L, null);
    }

    // ---------------------------------------------------------------- 参数与分页

    @Test
    void nonNumericSeriesIdIs400AndNeverReachesTheService() throws Exception {
        loginAs((byte) 1);

        mockMvc.perform(get("/api/admin/local-video-series/abc/episodes").header("Authorization", BEARER))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
        mockMvc.perform(post("/api/admin/local-video-series/abc/episodes")
                        .contentType(MediaType.APPLICATION_JSON).content("{}")
                        .header("Authorization", BEARER))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(catalogService);
    }

    @Test
    void unknownParentSeriesIs404AndKeepsTheNoStoreHeader() throws Exception {
        loginAs((byte) 1);
        when(catalogService.listEpisodes(9L, 0L, 20))
                .thenThrow(new BusinessException(404, "本地作品不存在"));

        mockMvc.perform(get(EPISODES_PATH).header("Authorization", BEARER))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    void serviceRefusalsBecomeTheirOwnStatusesAndDatabaseFailuresAre500Not409() throws Exception {
        loginAs((byte) 1);

        // seriesId=0 由服务层判 400，经全局异常处理器变成 400。
        when(catalogService.listEpisodes(0L, 0L, 20))
                .thenThrow(new BusinessException(400, "seriesId 必须是正整数"));
        mockMvc.perform(get("/api/admin/local-video-series/0/episodes").header("Authorization", BEARER))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        // 非业务异常（这里是数据访问失败）按全局处理器是 500，**不**统一翻译成 409。
        when(catalogService.createSeries(ADMIN_UID, "作品甲"))
                .thenThrow(new DataAccessResourceFailureException("db down"));
        mockMvc.perform(post(SERIES_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"作品甲\"}")
                        .header("Authorization", BEARER))
                .andExpect(status().isInternalServerError())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value(500));
    }

    @Test
    void pagingBoundsAre400WithoutCallingTheService() throws Exception {
        loginAs((byte) 1);

        for (String query : List.of("?afterId=-1", "?limit=0", "?limit=101", "?afterId=-1&limit=101")) {
            mockMvc.perform(get(SERIES_PATH + query).header("Authorization", BEARER))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(400));
            mockMvc.perform(get(EPISODES_PATH + query).header("Authorization", BEARER))
                    .andExpect(status().isBadRequest());
        }

        verify(catalogService, never()).listSeries(anyLong(), anyInt());
        verify(catalogService, never()).listEpisodes(anyLong(), anyLong(), anyInt());
    }

    // ---------------------------------------------------------------- 分页读

    @Test
    void listEndpointsReturnTheApiEnvelopeWithPagingAndNoStoreHeader() throws Exception {
        loginAs((byte) 1);
        when(catalogService.listSeries(0L, 20)).thenReturn(
                new LocalVideoPageVO<>(List.of(seriesVO()), 0L, 20, false, null));
        when(catalogService.listEpisodes(9L, 10L, 2)).thenReturn(
                new LocalVideoPageVO<>(List.of(episodeVO()), 10L, 2, true, 51L));

        mockMvc.perform(get(SERIES_PATH).header("Authorization", BEARER))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.afterId").value(0))
                .andExpect(jsonPath("$.data.limit").value(20))
                .andExpect(jsonPath("$.data.hasMore").value(false))
                .andExpect(jsonPath("$.data.nextAfterId").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].id").value(41))
                .andExpect(jsonPath("$.data.items[0].title").value("示例作品"));

        mockMvc.perform(get(EPISODES_PATH).param("afterId", "10").param("limit", "2")
                        .header("Authorization", BEARER))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.afterId").value(10))
                .andExpect(jsonPath("$.data.limit").value(2))
                .andExpect(jsonPath("$.data.hasMore").value(true))
                .andExpect(jsonPath("$.data.nextAfterId").value(51))
                .andExpect(jsonPath("$.data.items[0].id").value(51));

        verify(catalogService).listSeries(0L, 20);
        verify(catalogService).listEpisodes(9L, 10L, 2);
    }

    // ---------------------------------------------------------------- helpers

    private void loginAs(byte role) {
        when(tokenService.parseToken("token-value"))
                .thenReturn(new TokenContext("token-value", ADMIN_UID, role, "jti-1",
                        new Date(), new Date(), 1));
        when(tokenService.isBlacklisted("jti-1")).thenReturn(false);
        User user = new User();
        user.setId(ADMIN_UID);
        user.setStatus((byte) 0);
        user.setRole(role);
        user.setTokenVersion(1);
        when(userMapper.selectAuthStateById(ADMIN_UID)).thenReturn(user);
    }

    private static LocalVideoSeriesVO seriesVO() {
        return new LocalVideoSeriesVO(41L, "local-identity-from-server", "示例作品", ADMIN_UID, CREATED_AT);
    }

    private static LocalVideoEpisodeVO episodeVO() {
        return new LocalVideoEpisodeVO(51L, "22222222-2222-4222-8222-222222222222", 9L,
                null, null, null, ADMIN_UID, CREATED_AT);
    }
}
