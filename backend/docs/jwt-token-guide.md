# JWT Token 实现流程指南

本文档整理 JWT Token 在 Spring Boot 后端项目中的实现流程，适用于当前 `feibijiubi` 后端项目后续从“内存 token”升级到更标准的 JWT 登录认证方案。

---

## 1. JWT 是什么

JWT 全称是 `JSON Web Token`。

它是一种常见的登录认证方案，可以理解为：

> 后端签发给前端的一张“带签名的电子身份证”。

用户登录成功后，后端生成一个 JWT Token 返回给前端。前端之后访问需要登录的接口时，把 JWT 放到请求头里。后端收到 JWT 后，可以解析出当前用户是谁。

---

## 2. JWT 和普通随机 token 的区别

普通随机 token 通常长这样：

```text
550e8400-e29b-41d4-a716-446655440000
```

它本身没有业务含义。后端必须保存：

```text
token -> userId
```

JWT 通常长这样：

```text
eyJhbGciOiJIUzI1NiJ9.eyJ1c2VySWQiOjEsInJvbGUiOiJVU0VSIn0.abcxxx
```

JWT 本身可以携带用户身份信息，例如：

```json
{
  "userId": 1,
  "username": "linxi",
  "role": "USER"
}
```

所以后端拿到 JWT 后，可以直接解析出用户身份。

---

## 3. JWT 的三段结构

JWT 由三段组成，中间用 `.` 分隔：

```text
Header.Payload.Signature
```

例如：

```text
xxxxx.yyyyy.zzzzz
```

### 3.1 Header：头部

Header 说明 token 类型和签名算法。

示例：

```json
{
  "alg": "HS256",
  "typ": "JWT"
}
```

含义：

| 字段 | 含义 |
|---|---|
| `alg` | 签名算法，例如 `HS256` |
| `typ` | token 类型，通常是 `JWT` |

---

### 3.2 Payload：载荷

Payload 存放需要携带的信息。

示例：

```json
{
  "userId": 1,
  "username": "linxi",
  "role": "USER",
  "exp": 1790000000
}
```

可以放：

- 用户 id
- 用户名
- 用户角色
- token 过期时间

不应该放：

- 密码
- 密码摘要 `passwordHash`
- 手机号、身份证等敏感信息
- 任何不希望前端看到的数据

注意：JWT 的 Payload 不是加密的，只是 Base64 编码。前端或其他人拿到 token 后，有办法解码看到里面的内容。

---

### 3.3 Signature：签名

Signature 是后端用密钥生成的签名。

它的作用是防止 token 被篡改。

例如，后端生成 token 时使用密钥：

```text
feibijiubi-secret-key
```

如果有人把 Payload 里的：

```json
{
  "role": "USER"
}
```

改成：

```json
{
  "role": "ADMIN"
}
```

那么签名校验就会失败，后端会认为这个 token 无效。

---

## 4. JWT 登录认证完整流程

### 4.1 用户登录

前端请求：

```http
POST /api/auth/login
Content-Type: application/json
```

请求体：

```json
{
  "username": "linxi",
  "password": "123456"
}
```

后端处理流程：

```text
接收用户名和密码
  ↓
校验参数是否为空
  ↓
根据 username 查询用户
  ↓
校验密码是否正确
  ↓
校验用户状态是否正常
  ↓
生成 JWT Token
  ↓
返回 token 和用户基本信息
```

返回示例：

```json
{
  "code": 200,
  "message": "登录成功",
  "data": {
    "token": "eyJhbGciOiJIUzI1NiJ9.xxx.xxx",
    "user": {
      "id": 1,
      "username": "linxi",
      "nickname": "用户linxi",
      "role": "USER"
    }
  }
}
```

---

### 4.2 前端保存 JWT

前端保存 token：

```js
localStorage.setItem("token", token);
```

之后请求需要登录的接口时携带：

```http
Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.xxx.xxx
```

---

### 4.3 后端解析 JWT

后端从请求头取出 token：

```java
String authorization = request.getHeader("Authorization");
```

检查格式：

```java
if (authorization == null || !authorization.startsWith("Bearer ")) {
    throw new BusinessException(401, "请先登录");
}
```

取出 token：

```java
String token = authorization.substring(7);
```

解析 token，得到用户 id：

```java
Long userId = jwtTokenService.getUserId(token);
```

然后查询数据库：

```java
User user = userMapper.selectById(userId);
```

---

## 5. Spring Boot 中实现 JWT 的推荐步骤

### 第一步：引入 JWT 依赖

常用库是 `jjwt`。

可以在 `pom.xml` 中加入：

```xml
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-api</artifactId>
    <version>0.12.6</version>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-impl</artifactId>
    <version>0.12.6</version>
    <scope>runtime</scope>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-jackson</artifactId>
    <version>0.12.6</version>
    <scope>runtime</scope>
</dependency>
```

---

### 第二步：配置 JWT 参数

推荐把密钥和过期时间放到配置文件中。

例如 `application.yml`：

```yaml
jwt:
  secret: feibijiubi-user-login-secret-key-please-change-in-production
  expire-minutes: 1440
```

含义：

| 配置 | 含义 |
|---|---|
| `jwt.secret` | JWT 签名密钥 |
| `jwt.expire-minutes` | token 有效期，单位分钟 |

正式项目中密钥不应该直接写死在代码里，更推荐通过环境变量、配置中心或安全配置管理。

---

### 第三步：创建配置属性类

推荐新增：

```text
src/main/java/com/feibijiubi/backend/config/JwtProperties.java
```

示例：

```java
package com.feibijiubi.backend.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "jwt")
public class JwtProperties {
    private String secret;
    private Long expireMinutes;
}
```

这样就可以把配置文件中的 `jwt.secret` 和 `jwt.expire-minutes` 读取到 Java 对象中。

---

### 第四步：创建 JWT 服务

推荐定义接口：

```text
src/main/java/com/feibijiubi/backend/service/auth/TokenService.java
```

```java
package com.feibijiubi.backend.service.auth;

public interface TokenService {

    String createToken(Long userId, String username, String role);

    Long getUserId(String token);
}
```

然后创建 JWT 实现：

```text
src/main/java/com/feibijiubi/backend/service/impl/auth/JwtTokenServiceImpl.java
```

示例：

```java
package com.feibijiubi.backend.service.impl.auth;

import com.feibijiubi.backend.common.BusinessException;
import com.feibijiubi.backend.config.JwtProperties;
import com.feibijiubi.backend.service.auth.TokenService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Service
public class JwtTokenServiceImpl implements TokenService {

    private final JwtProperties jwtProperties;

    public JwtTokenServiceImpl(JwtProperties jwtProperties) {
        this.jwtProperties = jwtProperties;
    }

    @Override
    public String createToken(Long userId, String username, String role) {
        Date now = new Date();
        Date expireTime = new Date(now.getTime() + jwtProperties.getExpireMinutes() * 60 * 1000);

        return Jwts.builder()
                .claim("userId", userId)
                .claim("username", username)
                .claim("role", role)
                .issuedAt(now)
                .expiration(expireTime)
                .signWith(getSecretKey())
                .compact();
    }

    @Override
    public Long getUserId(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(getSecretKey())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            Object userId = claims.get("userId");
            if (userId == null) {
                throw new BusinessException(401, "请先登录");
            }

            return Long.valueOf(userId.toString());
        } catch (Exception e) {
            throw new BusinessException(401, "请先登录");
        }
    }

    private SecretKey getSecretKey() {
        return Keys.hmacShaKeyFor(jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8));
    }
}
```

注意：`HS256` 通常要求密钥长度足够长，建议至少 32 字节。

---

### 第五步：登录成功时生成 JWT

登录 Service 中，原本可能只是校验账号密码：

```java
public void login(UserLoginDTO request) {
    // 校验用户名密码
}
```

推荐改成返回登录结果：

```java
public UserLoginVO login(UserLoginDTO request) {
    validateLoginRequest(request);

    User user = userMapper.selectByUsernameForLogin(request.getUsername());
    if (user == null || !Objects.equals(request.getPassword(), user.getPasswordHash())) {
        throw new BusinessException(400, "用户名或密码错误");
    }

    if (user.getStatus() != null && user.getStatus() == 0) {
        throw new BusinessException(403, "账号已被禁用");
    }

    String token = tokenService.createToken(user.getId(), user.getUsername(), user.getRole());

    UserLoginVO result = new UserLoginVO();
    result.setToken(token);
    result.setUser(convertToUserVO(user));
    return result;
}
```

---

### 第六步：新增登录结果 VO

新增：

```text
src/main/java/com/feibijiubi/backend/vo/UserLoginVO.java
```

示例：

```java
package com.feibijiubi.backend.vo;

import lombok.Data;

@Data
public class UserLoginVO {
    private String token;
    private UserVO user;
}
```

---

### 第七步：查询当前用户时解析 JWT

接口设计：

```http
GET /api/users/me
Authorization: Bearer jwt-token
```

Controller 示例：

```java
@GetMapping("/me")
public ApiResponse<UserVO> getCurrentUser(
        @RequestHeader("Authorization") String authorization) {
    UserVO user = userAccountService.getCurrentUser(authorization);
    return ApiResponse.success("查询成功", user);
}
```

Service 示例：

```java
public UserVO getCurrentUser(String authorization) {
    String token = parseToken(authorization);

    Long userId = tokenService.getUserId(token);
    User user = userMapper.selectById(userId);

    if (user == null) {
        throw new BusinessException(401, "登录状态异常，请重新登录");
    }

    return convertToUserVO(user);
}
```

解析 Authorization：

```java
private String parseToken(String authorization) {
    if (!StringUtils.hasText(authorization) || !authorization.startsWith("Bearer ")) {
        throw new BusinessException(401, "请先登录");
    }

    String token = authorization.substring(7);
    if (!StringUtils.hasText(token)) {
        throw new BusinessException(401, "请先登录");
    }

    return token;
}
```

---

## 6. JWT 过期时间

JWT 一般要设置过期时间。

原因：如果 token 永不过期，一旦泄露，别人就可以长期冒充用户。

常见过期时间：

| 场景 | 建议 |
|---|---|
| 学习项目 | 1 天 |
| 普通 Web 应用 | 几小时到几天 |
| 高安全场景 | 更短时间，并配合刷新 token |

当前项目学习阶段可以设置：

```yaml
jwt:
  expire-minutes: 1440
```

也就是 1 天。

---

## 7. JWT 的安全注意点

### 7.1 不要在 JWT 里放敏感信息

不要放：

```text
password
passwordHash
手机号
身份证号
密保信息
```

因为 JWT 的 Payload 可以被解码查看。

---

### 7.2 密钥不能太短

如果使用 `HS256`，密钥建议至少 32 字节。

不推荐：

```yaml
jwt:
  secret: 123456
```

推荐：

```yaml
jwt:
  secret: feibijiubi-user-login-secret-key-please-change-in-production
```

---

### 7.3 正式环境不要把密钥提交到代码仓库

学习项目可以先写在 `application.yml` 中。

正式项目更推荐：

- 环境变量
- 配置中心
- 服务器安全配置

---

### 7.4 修改密码后 token 如何失效

普通 JWT 的一个问题是：

> token 一旦签发，在过期前默认一直有效。

如果用户修改密码，旧 token 可能仍然可用。

正式项目常见解决方式：

1. 缩短 token 有效期。
2. 配合 Redis 保存黑名单。
3. 用户表增加 `tokenVersion` 或 `passwordUpdatedAt` 字段，解析 token 后再校验版本或时间。

学习阶段可以先不做复杂失效机制，但要知道这个问题存在。

---

## 8. JWT 和拦截器的关系

初学阶段可以在每个需要登录的接口里手动解析 token。

但接口越来越多后，不推荐每个接口都写：

```java
parseToken(authorization)
tokenService.getUserId(token)
```

更规范的方式是使用拦截器 `HandlerInterceptor`。

流程：

```text
请求进入后端
  ↓
拦截器先执行
  ↓
检查 Authorization 请求头
  ↓
解析 JWT
  ↓
把当前用户 id 保存到上下文
  ↓
Controller 正常执行
```

这样业务接口里就不用反复解析 token。

不过对于当前学习阶段，建议分两步：

1. 先手动解析 token，把流程跑通。
2. 再抽象成拦截器，学习更规范的统一认证机制。

---

## 9. 推荐开发顺序

当前项目如果要实现 JWT，建议按这个顺序：

1. 在 `pom.xml` 加入 JWT 依赖。
2. 在 `application.yml` 增加 `jwt.secret` 和 `jwt.expire-minutes`。
3. 创建 `JwtProperties` 读取配置。
4. 创建 `TokenService` 接口。
5. 创建 `JwtTokenServiceImpl` 实现生成和解析 JWT。
6. 新增 `UserLoginVO`。
7. 修改登录接口，让它返回 token 和用户信息。
8. 修改 `UserAccountService.login()`，登录成功后生成 JWT。
9. 给 `UserMapper` 增加 `selectById`。
10. 新增 `/api/users/me` 接口。
11. 在查询当前用户接口中解析 JWT，得到当前用户 id。
12. 统一处理 token 缺失、格式错误、过期、签名错误等异常。
13. 后续再升级为拦截器统一认证。

---

## 10. 当前阶段的规范化建议

为了写出规范化、标准化代码，JWT 功能应该注意：

1. 不要把 token 生成逻辑写在 Controller 中。
2. 不要把 JWT 解析逻辑散落在很多业务方法里。
3. 用 `TokenService` 封装 token 的创建和解析。
4. 登录接口返回 `UserLoginVO`，不要直接返回 `Map`。
5. 返回给前端的用户信息使用 `UserVO`，不要直接返回 `User` Entity。
6. 不要把 `passwordHash` 放进 JWT 或返回给前端。
7. 未登录、token 过期、token 无效统一返回 401。
8. 普通用户查询自己用 `/api/users/me`，不要让前端传用户 id。
9. 管理员查询别人再使用 `/api/admin/users/{id}`。

---

## 11. 核心总结

JWT Token 的实现流程可以总结为：

```text
用户登录
  ↓
后端校验用户名密码
  ↓
后端用用户信息 + 密钥生成 JWT
  ↓
前端保存 JWT
  ↓
前端请求需要登录的接口时携带 JWT
  ↓
后端校验 JWT 签名和过期时间
  ↓
后端从 JWT 中解析 userId
  ↓
根据 userId 查询当前用户信息或执行业务逻辑
```

JWT 的关键点：

- JWT 是登录凭证。
- JWT 可以携带用户身份信息。
- JWT 通过签名防止篡改。
- JWT Payload 不是加密的，不能放敏感信息。
- 后端通过 JWT 判断当前请求属于哪个用户。

对于当前项目，推荐先实现基础 JWT 登录和 `/api/users/me`，等流程熟悉后，再引入拦截器、刷新 token、Redis 黑名单等更完整的认证体系。
