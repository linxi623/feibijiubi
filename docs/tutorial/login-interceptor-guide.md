# 登录拦截器讲解与放置位置

> 本文档用于解释当前 `feibijiubi` 后端项目中“登录拦截器”是什么、为什么需要它、它和当前 JWT 代码是什么关系，以及如果要写，推荐放在哪里。

---

## 1. 当前项目登录校验的问题

当前项目已经有 JWT 登录流程：

```text
登录成功
  ↓
后端生成 JWT token
  ↓
前端保存 token
  ↓
前端访问需要登录的接口时携带 Authorization 请求头
```

例如：

```http
GET /api/users/me
Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.xxx.xxx
```

当前 `/api/users/me` 的写法是：

```java
@GetMapping("/me")
public ApiResponse<UserVO> getCurrentUser(
        @RequestHeader(value = "Authorization", required = false) String authorization) {
    UserVO user = userService.getCurrentUser(authorization);
    return ApiResponse.success("查询成功", user);
}
```

也就是说：

```text
每个需要登录的接口，都要自己从 Header 里取 Authorization。
```

这样会有几个问题：

1. 每个 Controller 都要重复写 `@RequestHeader("Authorization")`。
2. 每个 Service 都可能要处理 token。
3. 登录校验逻辑分散，不够统一。
4. 后续接口多了以后，代码会越来越重复。

登录拦截器就是为了解决这个问题。

---

## 2. 登录拦截器是什么

登录拦截器可以理解为：

> 在请求真正进入 Controller 之前，先统一检查用户有没有登录。

请求流程原来是：

```text
前端请求
  ↓
Controller
  ↓
Service
  ↓
Mapper
```

加了登录拦截器之后变成：

```text
前端请求
  ↓
登录拦截器
  ↓
Controller
  ↓
Service
  ↓
Mapper
```

如果 token 正确：

```text
放行，继续访问 Controller
```

如果 token 不存在、格式不对、过期、签名错误：

```text
直接返回 401，请先登录
```

---

## 3. 拦截器适合做什么

登录拦截器适合做：

- 判断请求是否携带 `Authorization`。
- 判断格式是不是 `Bearer token`。
- 解析 JWT。
- 校验 JWT 是否过期。
- 校验 JWT 签名是否正确。
- 从 token 中取出 `userId`。
- 把当前用户 id 保存到本次请求上下文中。

登录拦截器不适合做：

- 查询复杂业务数据。
- 修改数据库。
- 写具体业务逻辑。
- 判断用户能不能点赞某个视频。
- 判断用户能不能删除某条评论。

一句话：

```text
拦截器只解决“你有没有登录”。
具体业务权限仍然放在 Service 层判断。
```

---

## 4. 拦截器和过滤器的区别，初学阶段怎么选

Spring Web 里常见两种东西：

| 名称 | 英文 | 常见用途 |
|---|---|---|
| 过滤器 | Filter | 更底层，Servlet 级别，常用于编码、日志、安全框架 |
| 拦截器 | Interceptor | Spring MVC 级别，常用于登录校验、权限检查、请求日志 |

对于当前项目来说，先用 Spring MVC 的 `HandlerInterceptor` 更适合新手。

原因：

- 写法比 Filter 简单。
- 和 Controller 关系更直观。
- 适合做登录校验。
- 后续如果引入 Spring Security，再考虑更专业的过滤器链。

---

## 5. 如果要写，应该放在哪里

结合当前项目结构，推荐新增两个位置。

### 5.1 拦截器类放在 `interceptor` 包

推荐路径：

```text
src/main/java/com/feibijiubi/backend/interceptor/LoginInterceptor.java
```

职责：

```text
负责拦截请求，检查 token，解析当前用户 id。
```

### 5.2 拦截器注册配置放在 `config` 包

推荐路径：

```text
src/main/java/com/feibijiubi/backend/config/WebMvcConfig.java
```

职责：

```text
告诉 Spring 哪些接口需要拦截，哪些接口放行。
```

例如：

```text
/api/auth/register  放行
/api/auth/login     放行
其他需要登录的接口  拦截
```

---

## 6. 为什么不放在 utils 里

不推荐放在：

```text
utils/LoginInterceptor.java
```

原因：

- `utils` 应该放纯工具类，例如字符串处理、JWT 工具。
- 拦截器是 Web 层组件，不是普通工具类。
- 拦截器需要被 Spring 管理，通常会注入 Service。

所以更推荐：

```text
interceptor/LoginInterceptor.java
config/WebMvcConfig.java
```

---

## 7. 推荐目录结构

加上登录拦截器后，当前项目结构可以这样：

```text
src/main/java/com/feibijiubi/backend
├── common
│   ├── ApiResponse.java
│   ├── BusinessException.java
│   └── GlobalExceptionHandler.java
├── config
│   ├── JwtProperties.java
│   └── WebMvcConfig.java
├── controller
│   ├── UserAccountController.java
│   └── UserController.java
├── interceptor
│   └── LoginInterceptor.java
├── service
│   ├── auth
│   │   ├── CurrentUserService.java
│   │   └── TokenService.java
│   └── impl
│       └── auth
│           ├── CurrentUserServiceImpl.java
│           └── JwtTokenServiceImpl.java
└── utils
    └── JwtUtils.java
```

---

## 8. 登录拦截器大概怎么写

### 8.1 LoginInterceptor 示例

```java
package com.feibijiubi.backend.interceptor;

import com.feibijiubi.backend.common.BusinessException;
import com.feibijiubi.backend.service.auth.TokenService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class LoginInterceptor implements HandlerInterceptor {
    private final TokenService tokenService;

    public LoginInterceptor(TokenService tokenService) {
        this.tokenService = tokenService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) {
        String authorization = request.getHeader("Authorization");
        if (!StringUtils.hasText(authorization) || !authorization.startsWith("Bearer ")) {
            throw new BusinessException(401, "请先登录");
        }

        String token = authorization.substring(7);
        if (!StringUtils.hasText(token)) {
            throw new BusinessException(401, "请先登录");
        }

        Long userId = tokenService.getUserId(token);
        request.setAttribute("currentUserId", userId);

        return true;
    }
}
```

重点解释：

```java
preHandle()
```

表示在进入 Controller 方法之前执行。

```java
return true;
```

表示校验通过，继续访问 Controller。

如果抛出：

```java
throw new BusinessException(401, "请先登录");
```

请求就不会继续进入 Controller，而是交给全局异常处理器返回错误响应。

---

## 9. WebMvcConfig 怎么注册拦截器

```java
package com.feibijiubi.backend.config;

import com.feibijiubi.backend.interceptor.LoginInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {
    private final LoginInterceptor loginInterceptor;

    public WebMvcConfig(LoginInterceptor loginInterceptor) {
        this.loginInterceptor = loginInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(loginInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns(
                        "/api/auth/register",
                        "/api/auth/login"
                );
    }
}
```

含义：

```java
.addPathPatterns("/api/**")
```

表示拦截所有 `/api` 开头的接口。

```java
.excludePathPatterns("/api/auth/register", "/api/auth/login")
```

表示注册和登录接口不需要登录，可以放行。

---

## 10. 加了拦截器后 Controller 可以怎么变简单

当前写法：

```java
@GetMapping("/me")
public ApiResponse<UserVO> getCurrentUser(
        @RequestHeader(value = "Authorization", required = false) String authorization) {
    UserVO user = userService.getCurrentUser(authorization);
    return ApiResponse.success("查询成功", user);
}
```

加拦截器后，可以变成：

```java
@GetMapping("/me")
public ApiResponse<UserVO> getCurrentUser(HttpServletRequest request) {
    Long currentUserId = (Long) request.getAttribute("currentUserId");
    UserVO user = userService.getCurrentUser(currentUserId);
    return ApiResponse.success("查询成功", user);
}
```

然后 `UserService` 方法也可以从：

```java
UserVO getCurrentUser(String authorization);
```

改成：

```java
UserVO getCurrentUser(Long currentUserId);
```

这样 Service 就不需要关心请求头，也不需要知道 `Authorization` 是什么。

这是更标准的分层：

```text
拦截器处理登录身份
Controller 获取当前用户 id
Service 处理业务逻辑
```

---

## 11. 更进一步：CurrentUserHolder

如果你不想在每个 Controller 都写：

```java
Long currentUserId = (Long) request.getAttribute("currentUserId");
```

后续可以封装一个当前用户上下文，例如：

```text
security/context/CurrentUserHolder.java
```

或者简单放在：

```text
auth/CurrentUserContext.java
```

但对当前新手阶段来说，先用 `request.setAttribute()` 更容易理解。

等你熟悉后，再升级为：

- `ThreadLocal` 当前用户上下文
- 自定义参数解析器
- Spring Security 的 `SecurityContextHolder`

---

## 12. 哪些接口应该放行

一般这些接口不需要登录：

```text
POST /api/auth/register
POST /api/auth/login
GET  /api/videos
GET  /api/videos/{id}
GET  /api/comments
```

一般这些接口需要登录：

```text
GET    /api/users/me
PUT    /api/users/me
POST   /api/videos
POST   /api/comments
POST   /api/videos/{id}/likes
DELETE /api/videos/{id}/likes
POST   /api/favorites
```

但是当前项目刚开始，可以先简单一点：

```text
拦截 /api/**
放行 /api/auth/register 和 /api/auth/login
```

后面做公开视频列表、视频详情时，再把这些查询接口加到放行列表。

---

## 13. 登录拦截器和当前 CurrentUserService 的关系

当前已有：

```text
CurrentUserServiceImpl
```

它现在做了两件事：

1. 从 `Authorization` 请求头里截取 token。
2. 调用 `TokenService` 解析 userId。

如果加了拦截器，这两件事就可以放到 `LoginInterceptor` 里。

后续可以选择：

### 方案一：保留 CurrentUserService

拦截器里继续调用：

```java
currentUserService.getCurrentUserId(authorization)
```

优点：复用现有代码。

### 方案二：简化 CurrentUserService

让拦截器负责解析 token，然后把 userId 放到 request attribute。

之后 Service 直接接收 `Long currentUserId`。

优点：分层更清晰，Service 不再关心 HTTP 请求头。

当前更推荐方案二。

---

## 14. 推荐演进步骤

如果你后面要真正实现登录拦截器，建议按这个顺序：

1. 新建 `interceptor/LoginInterceptor.java`。
2. 在拦截器中注入 `TokenService`。
3. 在 `preHandle()` 中读取 `Authorization`。
4. 校验 `Bearer ` 格式。
5. 截取 token。
6. 调用 `tokenService.getUserId(token)`。
7. 把 `userId` 放入 `request.setAttribute("currentUserId", userId)`。
8. 新建 `config/WebMvcConfig.java` 注册拦截器。
9. 放行 `/api/auth/register` 和 `/api/auth/login`。
10. 修改 `/api/users/me`，让它从 request attribute 中取 `currentUserId`。
11. 修改 `UserService.getCurrentUser()` 参数，从 `String authorization` 改为 `Long currentUserId`。
12. 编译测试。

---

## 15. 一句话总结

登录拦截器的作用是：

```text
在请求进入 Controller 前，统一校验 token，把“当前用户是谁”提前解析出来。
```

推荐放置位置是：

```text
src/main/java/com/feibijiubi/backend/interceptor/LoginInterceptor.java
src/main/java/com/feibijiubi/backend/config/WebMvcConfig.java
```

当前项目先用 Spring MVC 的 `HandlerInterceptor` 就够了，不需要一开始上 Spring Security。
