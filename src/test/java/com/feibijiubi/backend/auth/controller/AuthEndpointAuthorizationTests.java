package com.feibijiubi.backend.auth.controller;

import com.feibijiubi.backend.auth.annotation.AllowRevokedToken;
import com.feibijiubi.backend.auth.annotation.OptionalLogin;
import com.feibijiubi.backend.auth.interceptor.AdminInterceptor;
import com.feibijiubi.backend.auth.interceptor.LoginInterceptor;
import com.feibijiubi.backend.auth.service.TokenService;
import com.feibijiubi.backend.shared.BusinessException;
import com.feibijiubi.backend.config.WebMvcConfig;
import com.feibijiubi.backend.user.controller.UserController;
import com.feibijiubi.backend.user.mapper.UserMapper;
import com.feibijiubi.backend.user.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.handler.MappedInterceptor;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * R2：注解、拦截器、账户/用户控制器一起迁到 {@code auth}/{@code user} 之后的<b>装配证据</b>。
 *
 * <p>断言四件事，都是“包名变了但对外契约没变”：</p>
 * <ol>
 *   <li>账户接口仍是 {@code /api/auth} 下的 {@code @RestController}，{@code logout} 仍带
 *       {@link AllowRevokedToken}（重复退出是幂等路径）；</li>
 *   <li>用户接口仍是 {@code /api/users}，{@code GET /{uid}} 仍带 {@link OptionalLogin}（匿名可读）；</li>
 *   <li>真实 {@link LoginInterceptor} 按搬走后的注解类型工作：匿名访问 {@code GET /{uid}} 放行，
 *       匿名访问 {@code GET /me} 仍返回 401；</li>
 *   <li>用<b>真实</b> {@link LoginInterceptor}/{@link AdminInterceptor} 装配 {@link WebMvcConfig}，
 *       拦截路径与免鉴权排除项都不变。</li>
 * </ol>
 *
 * <p>不启动 Web 容器、不连任何外部基础设施：拦截器行为用 {@code MockHttpServletRequest} +
 * {@link HandlerMethod} 直接驱动，映射结果从 {@link InterceptorRegistry} 反射取出
 * {@link MappedInterceptor} 做结构断言（与既有管理员鉴权测试同一手法）。</p>
 */
class AuthEndpointAuthorizationTests {

    @Test
    void relocatedAccountEndpointsKeepTheirAnnotations() {
        assertEquals("/api/auth", UserAccountController.class.getAnnotation(RequestMapping.class).value()[0]);
        assertNotNull(UserAccountController.class.getAnnotation(RestController.class));

        assertEquals(List.of("/login", "/logout", "/register"), postPaths(UserAccountController.class));
        assertNotNull(method(UserAccountController.class, "logout").getAnnotation(AllowRevokedToken.class),
                "logout 必须保留 @AllowRevokedToken");
    }

    @Test
    void relocatedUserEndpointsKeepTheirLoginAnnotations() {
        assertEquals("/api/users", UserController.class.getAnnotation(RequestMapping.class).value()[0]);
        assertNotNull(UserController.class.getAnnotation(RestController.class));

        Method getUser = method(UserController.class, "getUser");
        assertEquals("/{uid}", getUser.getAnnotation(GetMapping.class).value()[0]);
        assertNotNull(getUser.getAnnotation(OptionalLogin.class), "GET /{uid} 必须保留 @OptionalLogin");
    }

    @Test
    void relocatedOptionalLoginAnnotationStillAllowsAnonymousRequests() throws Exception {
        LoginInterceptor loginInterceptor =
                new LoginInterceptor(mock(TokenService.class), mock(UserMapper.class));
        UserController controller = new UserController(mock(UserService.class));

        assertTrue(loginInterceptor.preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(),
                        new HandlerMethod(controller, method(UserController.class, "getUser"))),
                "GET /{uid} 带 @OptionalLogin，匿名请求必须放行");

        BusinessException error = assertThrows(BusinessException.class, () -> loginInterceptor.preHandle(
                new MockHttpServletRequest(), new MockHttpServletResponse(),
                new HandlerMethod(controller, method(UserController.class, "getCurrentUser"))));
        assertEquals(401, error.getCode(), "GET /me 没有 @OptionalLogin，必须要求登录");
    }

    @Test
    void webMvcConfigStillWiresBothInterceptorsOnTheSamePatterns() throws Exception {
        LoginInterceptor loginInterceptor =
                new LoginInterceptor(mock(TokenService.class), mock(UserMapper.class));
        AdminInterceptor adminInterceptor = new AdminInterceptor();
        InterceptorRegistry registry = new InterceptorRegistry();

        new WebMvcConfig(loginInterceptor, adminInterceptor).addInterceptors(registry);

        MappedInterceptor login = mapped(registry, loginInterceptor);
        assertArrayEquals(new String[]{"/api/**"}, login.getIncludePathPatterns());
        assertArrayEquals(new String[]{"/api/auth/register", "/api/auth/login"},
                login.getExcludePathPatterns());

        MappedInterceptor admin = mapped(registry, adminInterceptor);
        assertArrayEquals(new String[]{"/api/admin/**"}, admin.getIncludePathPatterns());
    }

    private static List<String> postPaths(Class<?> controller) {
        return Arrays.stream(controller.getDeclaredMethods())
                .filter(candidate -> candidate.getAnnotation(PostMapping.class) != null)
                .flatMap(candidate -> Arrays.stream(candidate.getAnnotation(PostMapping.class).value()))
                .sorted()
                .toList();
    }

    private static Method method(Class<?> controller, String name) {
        return Arrays.stream(controller.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private static MappedInterceptor mapped(InterceptorRegistry registry, HandlerInterceptor interceptor)
            throws Exception {
        // Spring 6 只开放 protected 访问器：反射取出映射后的 MappedInterceptor。
        Method getInterceptors = InterceptorRegistry.class.getDeclaredMethod("getInterceptors");
        getInterceptors.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<Object> registrations = (List<Object>) getInterceptors.invoke(registry);
        return registrations.stream()
                .filter(MappedInterceptor.class::isInstance)
                .map(MappedInterceptor.class::cast)
                .filter(candidate -> candidate.getInterceptor() == interceptor)
                .findFirst()
                .orElseThrow(() -> new AssertionError("未注册拦截器：" + interceptor.getClass().getName()));
    }
}
