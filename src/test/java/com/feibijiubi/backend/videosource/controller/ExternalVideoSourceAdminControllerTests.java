package com.feibijiubi.backend.videosource.controller;

import com.feibijiubi.backend.shared.BusinessException;
import com.feibijiubi.backend.shared.GlobalExceptionHandler;
import com.feibijiubi.backend.user.entity.User;
import com.feibijiubi.backend.auth.interceptor.AdminInterceptor;
import com.feibijiubi.backend.auth.interceptor.LoginInterceptor;
import com.feibijiubi.backend.user.mapper.UserMapper;
import com.feibijiubi.backend.auth.service.TokenContext;
import com.feibijiubi.backend.auth.service.TokenService;
import com.feibijiubi.backend.videosource.service.ExternalVideoSourcePreviewService;
import com.feibijiubi.backend.videosource.vo.ExternalVideoSourceCatalogVO;
import com.feibijiubi.backend.videosource.vo.ExternalVideoSourceDetailVO;
import com.feibijiubi.backend.videosource.vo.ExternalVideoSourceVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Date;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M3-B：管理员预览接口的 HTTP 接线测试（{@code MockMvc} standalone，不启动 Web 容器、不连数据库/Redis）。
 *
 * <p>拦截器用的是<b>真实</b>实现：{@link LoginInterceptor}（只把 token 校验替换成 mock 的
 * {@link TokenService}/{@link UserMapper}）与 {@link AdminInterceptor}。因此 401/403 来自真实的登录/权限
 * 判定流程，而不是"反射看到有 {@code @AdminOnly} 注解就算授权通过"。uid 也走真实约定：
 * 拦截器把 {@code currentUserId} 写进请求属性，controller 只从那里读。</p>
 *
 * <p>服务层用 mock：本类只验证"参数怎么传、错误码怎么变成 HTTP 状态、响应里有什么字段"。
 * 上游调用、配额与 permit 的行为在 {@code ExternalVideoSourcePreviewServiceTests} 里验证。</p>
 *
 * <p>{@code Cache-Control: no-store} 只在 controller 真正执行过之后才存在：成功响应与服务层自己抛出的
 * 业务错误（如 503）都断言带这条 header；401/403 由拦截器提前结束，所以反向断言它<b>不存在</b>。
 * 这不是遗漏，而是本片的范围声明。</p>
 */
class ExternalVideoSourceAdminControllerTests {

    private static final Integer ADMIN_UID = 7;
    private static final String BEARER = "Bearer token-value";

    private final TokenService tokenService = mock(TokenService.class);
    private final UserMapper userMapper = mock(UserMapper.class);
    private final ExternalVideoSourcePreviewService previewService =
            mock(ExternalVideoSourcePreviewService.class);

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new ExternalVideoSourceAdminController(previewService))
                .addInterceptors(new LoginInterceptor(tokenService, userMapper), new AdminInterceptor())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void missingTokenIs401WithoutTouchingTheService() throws Exception {
        mockMvc.perform(get("/api/admin/video-sources"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401))
                // 拦截器在进入 controller 之前就结束了请求，因此这条拒绝响应没有 no-store（controller
                // 根本还没执行）；本片不把 401/403 计入"带 no-store"的范围。
                .andExpect(header().doesNotExist("Cache-Control"));

        verifyNoInteractions(previewService);
    }

    @Test
    void commonUserIs403ByTheRealAdminInterceptor() throws Exception {
        loginAs((byte) 0);

        mockMvc.perform(get("/api/admin/video-sources").header("Authorization", BEARER))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));

        verifyNoInteractions(previewService);
    }

    @Test
    void adminGetsOnlyCodeAndNameWithoutAnyUpstreamConfiguration() throws Exception {
        loginAs((byte) 1);
        when(previewService.listAvailableSources())
                .thenReturn(List.of(new ExternalVideoSourceVO("demo", "示例来源")));

        mockMvc.perform(get("/api/admin/video-sources").header("Authorization", BEARER))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data[0].code").value("demo"))
                .andExpect(jsonPath("$.data[0].name").value("示例来源"))
                .andExpect(jsonPath("$.data[0].apiBase").doesNotExist())
                .andExpect(content().string(not(containsString("example.invalid"))));
    }

    @Test
    void catalogPassesLoginUserIdAndDefaultsPageToOne() throws Exception {
        loginAs((byte) 1);
        when(previewService.catalog(ADMIN_UID, "demo", 2, 21)).thenReturn(catalog());
        when(previewService.catalog(ADMIN_UID, "demo", 1, null)).thenReturn(catalog());

        mockMvc.perform(get("/api/admin/video-sources/demo/catalog")
                        .param("page", "2")
                        .param("categoryId", "21")
                        .header("Authorization", BEARER))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.sourceCode").value("demo"))
                .andExpect(jsonPath("$.data.items[0].workIdentity").value("100001"));

        mockMvc.perform(get("/api/admin/video-sources/demo/catalog").header("Authorization", BEARER))
                .andExpect(status().isOk());

        verify(previewService).catalog(ADMIN_UID, "demo", 2, 21);
        verify(previewService).catalog(ADMIN_UID, "demo", 1, null);
    }

    @Test
    void detailResponseCarriesNoPlaybackAddressOrLineKey() throws Exception {
        loginAs((byte) 1);
        when(previewService.detail(ADMIN_UID, "demo", "100001")).thenReturn(detail());

        mockMvc.perform(get("/api/admin/video-sources/demo/detail")
                        .param("workId", "100001")
                        .header("Authorization", BEARER))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.workIdentity").value("100001"))
                .andExpect(jsonPath("$.data.hasDurationHint").value(false))
                .andExpect(jsonPath("$.data.lines[0].lineIndex").value(1))
                .andExpect(jsonPath("$.data.lines[0].entries[0].label").value("第1集"))
                .andExpect(jsonPath("$.data.lines[0].lineKey").doesNotExist())
                .andExpect(content().string(not(containsString("m3u8"))))
                .andExpect(content().string(not(containsString("example.invalid"))));
    }

    @Test
    void serviceRefusalKeepsTheNoStoreHeader() throws Exception {
        loginAs((byte) 1);
        when(previewService.catalog(ADMIN_UID, "demo", 1, null))
                .thenThrow(new BusinessException(503, ExternalVideoSourcePreviewService.REASON_PREVIEW_DISABLED));

        mockMvc.perform(get("/api/admin/video-sources/demo/catalog").header("Authorization", BEARER))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value(503));

        verify(previewService).catalog(ADMIN_UID, "demo", 1, null);
    }

    @Test
    void missingWorkIdAndNonNumericPageAre400WithoutCallingTheService() throws Exception {
        loginAs((byte) 1);

        mockMvc.perform(get("/api/admin/video-sources/demo/detail").header("Authorization", BEARER))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        mockMvc.perform(get("/api/admin/video-sources/demo/catalog")
                        .param("page", "abc")
                        .header("Authorization", BEARER))
                .andExpect(status().isBadRequest());

        verify(previewService, never()).detail(any(), any(), any());
        verify(previewService, never()).catalog(any(), any(), anyInt(), any());
    }

    // ---------------------------------------------------------------- helpers

    /**
     * 走真实登录流程：token → {@link TokenContext} → 数据库登录态 → {@code currentUserId}/{@code currentUserRole}
     * 请求属性，随后由 {@link AdminInterceptor} 判定权限。
     */
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

    private static ExternalVideoSourceCatalogVO catalog() {
        return new ExternalVideoSourceCatalogVO("demo", 1, 3, 41, List.of(
                new ExternalVideoSourceCatalogVO.Item("100001", "合成作品甲", "21", "合成分类")));
    }

    private static ExternalVideoSourceDetailVO detail() {
        return new ExternalVideoSourceDetailVO("demo", "100001", "合成作品甲", "21", "合成分类", false,
                List.of(new ExternalVideoSourceDetailVO.Line(1, List.of(
                        new ExternalVideoSourceDetailVO.Entry(1, "第1集")))));
    }
}
