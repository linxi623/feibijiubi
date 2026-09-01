# Token 登录机制入门指南

本文档整理本项目中关于 `token` 的概念、作用、开发流程和规范化落地方式。适用于当前 `feibijiubi` Spring Boot 后端项目的用户登录、查询当前用户信息等功能。

---

## 1. token 是什么

`token` 可以理解为用户登录后的“登录凭证”。

现实生活中的类比：

> 用户进入游乐园时先买票，后面玩项目时不用每次重新证明身份，只需要出示票。

后端项目中也是类似的：

> 用户登录成功后，后端给前端一个 token。前端之后访问需要登录的接口时，把 token 带给后端。后端通过 token 判断当前请求对应哪个用户。

一句话总结：

> token 不是用户信息本身，而是用来证明“我已经登录过”的凭证。

---

## 2. 为什么需要 token

HTTP 请求默认是“无状态”的。

例如：

```http
POST /api/auth/login
```

和：

```http
GET /api/users/me
```

在服务器看来默认是两个独立请求。服务器不会天然记得：

> 刚才登录的是 linxi，所以现在查询个人信息的也是 linxi。

因此，登录成功后需要给前端一个凭证。前端后续请求带上这个凭证，后端才能识别用户身份。

---

## 3. token 的基本工作流程

### 3.1 用户登录

前端发送登录请求：

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

后端校验用户名和密码。

如果登录成功，后端生成 token，并返回给前端：

```json
{
  "code": 200,
  "message": "登录成功",
  "data": {
    "token": "abc123",
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

### 3.2 前端保存 token

前端拿到 token 后保存起来，例如：

```js
localStorage.setItem("token", token);
```

之后访问需要登录的接口时，从本地取出 token 并放到请求头中。

---

### 3.3 后续请求携带 token

查询当前登录用户信息时，推荐接口设计为：

```http
GET /api/users/me
Authorization: Bearer abc123
```

其中：

```http
Authorization: Bearer abc123
```

表示：

> 我带着登录凭证来了，请后端识别我是谁。

---

### 3.4 后端验证 token

后端收到请求后，从请求头里取出 token：

```java
String authorization = request.getHeader("Authorization");
```

校验格式是否正确：

```java
if (authorization == null || !authorization.startsWith("Bearer ")) {
    throw new BusinessException(401, "请先登录");
}
```

取出真正的 token：

```java
String token = authorization.substring(7);
```

为什么是 `7`？

因为 `Bearer ` 一共有 7 个字符，包括后面的空格。

然后通过 token 找到用户 id：

```java
Long userId = tokenService.getUserId(token);
```

最后根据用户 id 查询数据库：

```java
User user = userMapper.selectById(userId);
```

---

## 4. 为什么查询个人信息不应该传 userId

不推荐这样设计普通用户个人信息接口：

```http
GET /api/users/1
```

因为用户可能把地址改成：

```http
GET /api/users/2
```

如果后端没有严格权限校验，就可能泄露别人的信息。

更推荐的设计是：

```http
GET /api/users/me
Authorization: Bearer token
```

`/me` 的意思是：

> 查询当前登录用户的信息。

当前用户是谁，不由前端传 `id` 决定，而由后端根据 token 判断。

普通用户查询自己：

```http
GET /api/users/me
```

管理员查询别人：

```http
GET /api/admin/users/{id}
```

这两个场景应该分开设计。

---

## 5. 常见 token 方案

### 5.1 简单随机 token

登录成功后，后端生成一个随机字符串：

```java
String token = UUID.randomUUID().toString();
```

例如：

```text
550e8400-e29b-41d4-a716-446655440000
```

这个 token 本身没有业务含义，只是随机凭证。

后端需要保存 token 和用户 id 的对应关系：

```text
token -> userId
```

例如：

```text
550e8400-e29b-41d4-a716-446655440000 -> 1
```

优点：

- 容易理解
- 适合初学阶段
- 实现简单

缺点：

- 如果只保存在 Java 内存中，项目重启后登录状态会丢失
- 多台服务器部署时不方便
- 正式项目通常要配合 Redis

---

### 5.2 JWT token

JWT 是正式项目中常见的 token 方案。

JWT 中可以包含一些用户身份信息，例如：

```json
{
  "userId": 1,
  "username": "linxi",
  "role": "USER",
  "expireTime": "2026-07-07 12:00:00"
}
```

后端生成 JWT 后返回给前端。之后前端带 JWT 请求接口，后端可以解析出用户 id。

优点：

- 后端不一定需要保存 token
- 可以直接解析出用户身份
- 前后端分离项目常用

缺点：

- 对初学者来说理解成本更高
- 需要理解签名、密钥、过期时间等概念
- token 一旦签发，在过期前不容易主动失效

---

## 6. 当前项目推荐开发路线

当前项目处于学习和基础功能搭建阶段，建议先实现“简单随机 token”版本，等登录流程、权限流程理解清楚后，再升级到 JWT 或 Redis。

推荐顺序：

1. 登录成功后返回 token 和用户信息。
2. 新增 `TokenService` 管理 token。
3. 用内存 `ConcurrentHashMap` 保存 token 和用户 id 的关系。
4. 新增 `/api/users/me` 查询当前用户信息接口。
5. 从请求头 `Authorization` 中解析 token。
6. 根据 token 找到 userId。
7. 根据 userId 查询用户信息并返回。
8. 处理未登录、token 无效、用户不存在等异常情况。

---

## 7. 推荐代码结构

为了写出规范化、标准化代码，不建议把所有逻辑都写在 Controller 里。

推荐分层：

```text
src/main/java/com/feibijiubi/backend
├── controller
│   └── UserAccountController.java
├── dto
│   └── UserLoginDTO.java
├── entity
│   └── User.java
├── mapper
│   └── UserMapper.java
├── service
│   ├── auth
│   │   └── TokenService.java
│   ├── user
│   │   └── UserAccountService.java
│   └── impl
│       ├── auth
│       │   └── MemoryTokenServiceImpl.java
│       └── user
│           └── UserAccountServiceImpl.java
└── vo
    ├── UserLoginVO.java
    └── UserVO.java
```

各层职责：

| 层 | 职责 |
|---|---|
| Controller | 接收请求、调用 Service、返回响应 |
| Service | 处理业务逻辑，例如登录、生成 token、查询当前用户 |
| Mapper | 访问数据库 |
| DTO | 接收前端请求参数 |
| VO | 返回给前端的数据 |
| Entity | 对应数据库表 |

---

## 8. TokenService 设计

推荐先定义接口：

```java
public interface TokenService {

    String createToken(Long userId);

    Long getUserId(String token);

    void removeToken(String token);
}
```

简单内存实现：

```java
@Service
public class MemoryTokenServiceImpl implements TokenService {

    private final Map<String, Long> tokenUserMap = new ConcurrentHashMap<>();

    @Override
    public String createToken(Long userId) {
        String token = UUID.randomUUID().toString();
        tokenUserMap.put(token, userId);
        return token;
    }

    @Override
    public Long getUserId(String token) {
        return tokenUserMap.get(token);
    }

    @Override
    public void removeToken(String token) {
        tokenUserMap.remove(token);
    }
}
```

这里推荐使用 `ConcurrentHashMap`，而不是普通 `HashMap`。

原因：Web 项目是多线程环境，多个用户可能同时请求，`ConcurrentHashMap` 更适合并发场景。

---

## 9. 登录返回对象设计

当前登录接口不应该只返回“登录成功”，还应该返回 token 和用户基本信息。

推荐新增：

```text
src/main/java/com/feibijiubi/backend/vo/UserLoginVO.java
```

示例：

```java
@Data
public class UserLoginVO {
    private String token;
    private UserVO user;
}
```

登录接口返回：

```java
@PostMapping("/login")
public ApiResponse<UserLoginVO> login(@RequestBody UserLoginDTO request) {
    UserLoginVO loginResult = userAccountService.login(request);
    return ApiResponse.success("登录成功", loginResult);
}
```

Service 接口从：

```java
void login(UserLoginDTO request);
```

改成：

```java
UserLoginVO login(UserLoginDTO request);
```

---

## 10. 查询当前用户接口设计

推荐接口：

```http
GET /api/users/me
Authorization: Bearer token
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

Service 逻辑：

```java
public UserVO getCurrentUser(String authorization) {
    String token = parseToken(authorization);

    Long userId = tokenService.getUserId(token);
    if (userId == null) {
        throw new BusinessException(401, "请先登录");
    }

    User user = userMapper.selectById(userId);
    if (user == null) {
        throw new BusinessException(401, "登录状态异常，请重新登录");
    }

    return convertToUserVO(user);
}
```

---

## 11. 需要处理的异常情况

查询当前用户信息时，至少要考虑这些情况：

| 情况 | 推荐响应 |
|---|---|
| 没有 `Authorization` 请求头 | `401 请先登录` |
| 请求头格式不是 `Bearer xxx` | `401 请先登录` |
| token 为空 | `401 请先登录` |
| token 不存在或已失效 | `401 请先登录` |
| token 对应的用户不存在 | `401 登录状态异常，请重新登录` |

统一响应示例：

```json
{
  "code": 401,
  "message": "请先登录",
  "data": null
}
```

---

## 12. 规范化开发原则

为了成为能写出规范化、标准化代码的后端工程师，这个功能要重点遵守以下原则：

1. Controller 不写复杂业务逻辑。
2. Service 负责登录、token、当前用户识别等业务流程。
3. Mapper 只负责数据库查询，不判断业务规则。
4. 不把 `passwordHash` 返回给前端。
5. 普通用户查询自己使用 `/me`，不要让前端传 userId。
6. 登录成功返回 token 和必要的用户信息。
7. 未登录或 token 无效统一返回 401。
8. 代码命名要清晰，例如 `TokenService`、`UserLoginVO`、`getCurrentUser`。

---

## 13. 核心总结

`token` 的本质是登录凭证。

完整逻辑是：

```text
用户登录
  ↓
后端校验账号密码
  ↓
后端生成 token
  ↓
前端保存 token
  ↓
前端访问需要登录的接口时携带 token
  ↓
后端根据 token 找到 userId
  ↓
后端根据 userId 查询当前用户信息
```

个人信息接口应该设计成：

```http
GET /api/users/me
Authorization: Bearer token
```

而不是让普通用户传：

```http
GET /api/users/{id}
```

因为“当前用户是谁”应该由后端根据 token 判断，而不是由前端自己声明。
