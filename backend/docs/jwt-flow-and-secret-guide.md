# JWT 流程与 secret 讲解

> 本文档用于解释当前 `feibijiubi` 项目中 JWT 登录认证的整体流程，以及 `jwt.secret` 到底是什么。对应代码主要包括：
>
> - `src/main/java/com/feibijiubi/backend/utils/JwtUtils.java`
> - `src/main/java/com/feibijiubi/backend/config/JwtProperties.java`
> - `src/main/java/com/feibijiubi/backend/service/impl/auth/JwtTokenServiceImpl.java`
> - `src/main/java/com/feibijiubi/backend/service/impl/auth/CurrentUserServiceImpl.java`
> - `src/main/resources/application.yml`

---

## 1. JWT 是什么

JWT 全称是 `JSON Web Token`。

你可以先把它理解成：

> 后端发给前端的一张“带签名的登录凭证”。

用户登录成功后，后端生成一个 token 返回给前端。前端以后请求需要登录的接口时，把这个 token 放到请求头里。后端收到 token 后，就可以知道：

- 这个用户是谁
- 这个 token 有没有过期
- 这个 token 有没有被别人篡改过

---

## 2. JWT 长什么样

JWT 通常长这样：

```text
eyJhbGciOiJIUzI1NiJ9.eyJ1c2VySWQiOjEsInVzZXJuYW1lIjoibGlueGkiLCJyb2xlIjowfQ.xxx
```

它由三段组成，中间用 `.` 分隔：

```text
Header.Payload.Signature
```

也就是：

```text
头部.载荷.签名
```

---

## 3. JWT 的三部分

### 3.1 Header：头部

Header 主要说明这个 token 的类型和签名算法。

大概类似：

```json
{
  "alg": "HS256",
  "typ": "JWT"
}
```

在你的项目里，使用 `jjwt` 的：

```java
.signWith(secretKey)
```

如果没有额外指定算法，JJWT 会根据密钥类型选择合适的 HMAC 签名算法。

---

### 3.2 Payload：载荷

Payload 用来保存一些登录身份信息。

你的项目在 `JwtUtils.createToken()` 里放了这些内容：

```java
.claim("userId", userId)
.claim("username", username)
.claim("role", role)
.setIssuedAt(now)
.setExpiration(expireTime)
```

也就是说 token 里会包含：

| 字段 | 含义 |
|---|---|
| `userId` | 用户 id |
| `username` | 用户名 |
| `role` | 用户角色 |
| `iat` | token 签发时间 |
| `exp` | token 过期时间 |

注意：

> JWT 的 Payload 不是加密的，只是编码。别人拿到 token 后，是可以解码看到 Payload 内容的。

所以 JWT 里面不能放：

- 密码
- `passwordHash`
- 手机号
- 身份证
- 银行卡
- 任何不想让前端或别人看到的信息

你的项目现在放 `userId`、`username`、`role` 是可以接受的。

---

### 3.3 Signature：签名

Signature 是 JWT 最关键的部分。

它的作用是：

> 防止 token 被篡改。

比如后端生成了一个 token，里面有：

```json
{
  "userId": 1,
  "username": "linxi",
  "role": 0
}
```

如果有人想把 `role` 从普通用户改成管理员：

```json
{
  "userId": 1,
  "username": "linxi",
  "role": 1
}
```

虽然他可以修改 Payload，但是他没有后端的 `secret`，就无法重新生成正确的 Signature。

后端解析 token 时会重新验签，发现签名对不上，就会认为这个 token 是伪造的。

---

## 4. secret 是什么

你的配置里有：

```yaml
jwt:
  secret: feibijiubi-user-login-secret-key-please-change-in-production
  expire-minutes: 1440
```

这里的 `secret` 可以理解成：

> 后端用来给 JWT 签名和验签的一把秘密钥匙。

它不是 token。

它不是用户密码。

它不是前端需要知道的东西。

它是后端自己保存的密钥。

---

## 5. secret 在代码里怎么用

你的 `JwtUtils` 中有这个方法：

```java
private static SecretKey createSecretKey(String secret) {
    return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
}
```

意思是：

1. 从配置文件读取字符串形式的 `secret`。
2. 把字符串转成字节数组。
3. 用 JJWT 的 `Keys.hmacShaKeyFor()` 转成 HMAC 签名需要的 `SecretKey`。

生成 token 时：

```java
.signWith(secretKey)
```

解析 token 时：

```java
.setSigningKey(secretKey)
.parseClaimsJws(token)
```

也就是说：

```text
生成 token 用同一个 secret 签名
解析 token 用同一个 secret 验签
```

如果生成时和解析时用的 secret 不一样，token 就解析失败。

---

## 6. 当前项目的 JWT 登录流程

### 6.1 用户登录

前端调用：

```http
POST /api/auth/login
Content-Type: application/json

{
  "username": "linxi",
  "password": "123456"
}
```

进入：

```java
UserAccountController.login()
```

代码大概是：

```java
@PostMapping("/login")
public ApiResponse<UserLoginVO> login(@RequestBody UserLoginDTO request) {
    UserLoginVO loginResult = userAccountService.login(request);
    return ApiResponse.success("登录成功", loginResult);
}
```

Controller 不自己生成 token，而是调用 Service。

---

### 6.2 Service 校验用户名密码

进入：

```java
UserAccountServiceImpl.login()
```

主要流程：

```text
校验参数不能为空
  ↓
根据 username 查询用户
  ↓
判断用户是否存在
  ↓
判断密码是否正确
  ↓
判断账号状态是否正常
  ↓
创建 UserLoginVO
  ↓
调用 tokenService.createToken(user)
```

当前代码中：

```java
loginVO.setToken(tokenService.createToken(user));
loginVO.setUser(UserConverter.toUserVO(user));
```

这一步会把 token 和用户基本信息一起返回给前端。

---

### 6.3 TokenService 生成 token

进入：

```java
JwtTokenServiceImpl.createToken()
```

代码：

```java
return JwtUtils.createToken(
        user.getId(),
        user.getUsername(),
        user.getRole(),
        jwtProperties.getSecret(),
        jwtProperties.getExpireMinutes()
);
```

这里有两个重要配置来自 `application.yml`：

```yaml
jwt:
  secret: feibijiubi-user-login-secret-key-please-change-in-production
  expire-minutes: 1440
```

含义：

| 配置 | 含义 |
|---|---|
| `jwt.secret` | JWT 签名和验签密钥 |
| `jwt.expire-minutes` | token 过期分钟数 |

`1440` 分钟就是 24 小时。

---

### 6.4 JwtUtils 真正创建 token

进入：

```java
JwtUtils.createToken()
```

核心代码：

```java
Date now = new Date();
Date expireTime = new Date(now.getTime() + expireMinutes * 60 * 1000);
SecretKey secretKey = createSecretKey(secret);

return Jwts.builder()
        .claim("userId", userId)
        .claim("username", username)
        .claim("role", role)
        .setIssuedAt(now)
        .setExpiration(expireTime)
        .signWith(secretKey)
        .compact();
```

这段代码做了几件事：

1. 获取当前时间 `now`。
2. 根据 `expireMinutes` 算出过期时间 `expireTime`。
3. 根据 `secret` 创建签名密钥 `secretKey`。
4. 把 `userId`、`username`、`role` 放入 token。
5. 设置签发时间。
6. 设置过期时间。
7. 使用 `secretKey` 签名。
8. 生成最终的 JWT 字符串。

---

## 7. 前端怎么使用 token

登录成功后，后端返回：

```json
{
  "code": 200,
  "message": "登录成功",
  "data": {
    "token": "eyJhbGciOiJIUzI1NiJ9.xxx.xxx",
    "user": {
      "id": 1,
      "username": "linxi",
      "nickname": "用户linxi"
    }
  }
}
```

前端保存 token。

例如：

```js
localStorage.setItem("token", response.data.token);
```

之后访问需要登录的接口时，加请求头：

```http
Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.xxx.xxx
```

注意格式：

```text
Bearer + 空格 + token
```

---

## 8. 当前项目解析 token 的流程

以 `/api/users/me` 为例。

前端请求：

```http
GET /api/users/me
Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.xxx.xxx
```

进入：

```java
UserController.getCurrentUser()
```

代码：

```java
@GetMapping("/me")
public ApiResponse<UserVO> getCurrentUser(
        @RequestHeader(value = "Authorization", required = false) String authorization) {
    UserVO user = userService.getCurrentUser(authorization);
    return ApiResponse.success("查询成功", user);
}
```

这里先从请求头拿到 `Authorization`。

---

## 9. CurrentUserService 处理请求头

进入：

```java
CurrentUserServiceImpl.getCurrentUserId()
```

代码：

```java
String token = parseToken(authorization);
return tokenService.getUserId(token);
```

`parseToken()` 做的事情：

```java
if (!StringUtils.hasText(authorization) || !authorization.startsWith("Bearer ")) {
    throw new BusinessException(401, "请先登录");
}

String token = authorization.substring(7);
```

为什么是 `substring(7)`？

因为：

```text
Bearer  一共 7 个字符
```

分别是：

```text
B e a r e r 空格
1 2 3 4 5 6 7
```

所以截掉前面的 `Bearer `，剩下的才是真正的 JWT。

---

## 10. TokenService 从 token 里拿 userId

进入：

```java
JwtTokenServiceImpl.getUserId()
```

代码：

```java
Long userId = JwtUtils.getUserId(token, jwtProperties.getSecret());
```

如果 token 无效、过期、签名不对，都会抛异常。

当前项目会统一转成：

```java
throw new BusinessException(401, "请先登录");
```

---

## 11. JwtUtils 解析 token

最终进入：

```java
JwtUtils.parseToken()
```

代码：

```java
SecretKey secretKey = createSecretKey(secret);

return Jwts.parserBuilder()
        .setSigningKey(secretKey)
        .build()
        .parseClaimsJws(token)
        .getBody();
```

这一步会做两件重要的事：

1. 用 `secret` 验证 token 的签名。
2. 检查 token 是否过期。

如果验证成功，就可以拿到 Payload 里的信息。

然后：

```java
Object userId = claims.get("userId");
return Long.valueOf(userId.toString());
```

后端拿到 `userId` 后，再去数据库查询当前用户。

---

## 12. 为什么有了 JWT 还要查数据库

JWT 里面已经有 `userId`，为什么还要查数据库？

因为 token 只能证明：

```text
这个 token 是后端签发的，并且没有过期、没有被篡改。
```

但它不能证明：

- 这个用户现在是否被封禁
- 这个用户是否被删除
- 用户昵称、头像是否已经更新
- 用户权限是否已经变化

所以当前项目在 `UserServiceImpl.getCurrentUser()` 中又查询了数据库：

```java
User user = userMapper.selectById(userId);
```

这是合理的。

---

## 13. secret 为什么必须保密

如果别人知道了你的 `secret`，他就可以自己伪造 token。

比如他可以生成一个：

```json
{
  "userId": 1,
  "username": "admin",
  "role": 1
}
```

然后用同一个 secret 签名。

后端收到后会以为这个 token 是合法的。

所以：

> secret 泄露，就相当于 JWT 认证体系失守。

---

## 14. 当前这个 secret 的含义

当前配置：

```yaml
secret: feibijiubi-user-login-secret-key-please-change-in-production
```

这句话里有一个明显提示：

```text
please-change-in-production
```

意思是：

> 这个密钥只是开发阶段临时使用的，生产环境请更换。

学习阶段可以先这样写，方便理解。

但是正式项目中不应该把真实 secret 直接提交到 Git 仓库。

---

## 15. secret 应该怎么设置

### 15.1 长度要足够

对于 HMAC-SHA 算法，secret 不能太短。

你的代码使用：

```java
Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8))
```

如果 secret 太短，JJWT 可能会直接报错。

当前这个字符串比较长，所以可以正常使用。

### 15.2 内容要随机

不推荐：

```yaml
secret: 123456
secret: feibijiubi
secret: password
```

推荐类似随机长字符串。

例如：

```yaml
secret: 9f1b0c7a4d3e8f6a2b5c9d0e7f1a3b6c9d2e5f8a1b4c7d0e3f6a9b2c5d8e1f4a
```

### 15.3 不要提交真实生产密钥

更规范的方式是使用环境变量：

```yaml
jwt:
  secret: ${JWT_SECRET}
  expire-minutes: 1440
```

启动项目时由环境变量提供：

```bash
JWT_SECRET=一个很长的随机字符串
```

---

## 16. 一个简单比喻

可以把 JWT 想成一张学生证。

Payload 是学生证上的信息：

```text
姓名：linxi
学号：1
身份：普通用户
有效期：2026-07-07
```

Signature 是学校盖的章。

secret 就是学校保管印章的钥匙。

别人可以看到学生证上的字，但不能伪造学校的章。

如果别人偷到了盖章钥匙，就可以伪造学生证。

所以 secret 必须保密。

---

## 17. 当前项目完整流程总结

```text
登录阶段：

前端提交用户名密码
  ↓
UserAccountController.login
  ↓
UserAccountServiceImpl.login
  ↓
UserMapper 查询用户
  ↓
校验密码和账号状态
  ↓
JwtTokenServiceImpl.createToken
  ↓
JwtUtils.createToken
  ↓
使用 jwt.secret 签名
  ↓
返回 token 给前端
```

```text
访问登录接口阶段：

前端携带 Authorization: Bearer token
  ↓
UserController.getCurrentUser
  ↓
CurrentUserServiceImpl 取出 token
  ↓
JwtTokenServiceImpl.getUserId
  ↓
JwtUtils.parseToken
  ↓
使用 jwt.secret 验签
  ↓
检查 token 是否过期
  ↓
取出 userId
  ↓
UserMapper 查询数据库
  ↓
返回当前用户信息
```

---

## 18. 你现在需要记住的重点

1. JWT 是后端发给前端的登录凭证。
2. JWT 不是加密数据，Payload 可以被看到。
3. JWT 的签名可以防止内容被篡改。
4. `secret` 是生成签名和验证签名用的后端密钥。
5. `secret` 不能告诉前端，也不能泄露。
6. 生成 token 和解析 token 必须使用同一个 secret。
7. token 过期后，解析会失败，需要重新登录。
8. 正式环境不要把真实 secret 写死在仓库里。
9. JWT 中不要放密码、手机号等敏感信息。
10. 有了 JWT，也通常还要查数据库确认用户当前状态。
