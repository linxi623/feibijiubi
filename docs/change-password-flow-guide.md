# 修改密码规范流程讲解

> 本文档讲解当前 `feibijiubi` 后端项目中“修改密码”功能的推荐设计方式。重点是：当前登录用户是谁由 JWT 决定，旧密码用于确认身份，新密码需要加密后保存。

---

## 1. 修改密码和修改资料不一样

修改资料一般只改：

```text
nickname
gender
description
avatarUrl
```

这些属于普通资料字段。

修改密码更敏感，因为它会影响账号登录安全，所以流程要更严格。

修改资料可以是：

```http
PUT /api/users/me
```

修改密码建议单独做一个接口：

```http
PUT /api/users/me/password
```

不要把修改密码混在修改资料接口里。

---

## 2. 推荐接口设计

### 请求方式

```http
PUT /api/users/me/password
```

含义：

```text
修改当前登录用户自己的密码
```

### 请求头

```http
Authorization: Bearer token
```

用来确认当前登录用户是谁。

### 请求体

```json
{
  "oldPassword": "123456",
  "newPassword": "abc123456",
  "confirmedPassword": "abc123456"
}
```

---

## 3. DTO 设计

推荐新建：

```text
src/main/java/com/feibijiubi/backend/dto/UserChangePasswordRequest.java
```

内容类似：

```java
package com.feibijiubi.backend.dto;

import lombok.Data;

@Data
public class UserChangePasswordRequest {
    private String oldPassword;
    private String newPassword;
    private String confirmedPassword;
}
```

字段含义：

| 字段 | 含义 |
|---|---|
| `oldPassword` | 当前旧密码，用于确认本人操作 |
| `newPassword` | 新密码 |
| `confirmedPassword` | 确认新密码，防止输错 |

不需要传：

```text
id
userId
username
```

因为当前用户是谁应该从 JWT token 中获取。

---

## 4. 为什么不能让前端传 userId

不推荐这样：

```json
{
  "userId": 2,
  "oldPassword": "123456",
  "newPassword": "abc123456"
}
```

原因是前端传来的 `userId` 不可信。

如果当前登录用户是 1，但他传：

```json
{
  "userId": 2
}
```

如果后端直接按这个 id 修改，就可能导致用户 1 修改用户 2 的密码。

所以规范原则是：

```text
谁在操作，用 token 判断。
修改什么内容，用请求体传。
```

---

## 5. Controller 层流程

如果已经有登录拦截器，并且拦截器把当前用户 id 放进 request：

```java
request.setAttribute("currentUserId", userId);
```

Controller 可以这样写：

```java
@PutMapping("/me/password")
public ApiResponse<Void> changePassword(
        HttpServletRequest httpRequest,
        @RequestBody UserChangePasswordRequest request) {

    Long currentUserId = (Long) httpRequest.getAttribute("currentUserId");
    userService.changePassword(currentUserId, request);
    return ApiResponse.successMessage("密码修改成功，请重新登录");
}
```

Controller 只负责：

```text
取当前用户 id
接收请求体
调用 Service
返回统一响应
```

不要在 Controller 里写密码校验逻辑。

---

## 6. Service 接口设计

在 `UserService` 中新增：

```java
void changePassword(Long currentUserId, UserChangePasswordRequest request);
```

为什么返回 `void`？

因为修改密码成功后，一般不需要返回用户完整信息，返回成功提示即可。

如果你想让前端刷新用户信息，也可以返回 `UserVO`，但修改密码本身不需要。

---

## 7. ServiceImpl 规范流程

核心流程应该是：

```text
1. 校验请求参数不能为空
2. 校验旧密码不能为空
3. 校验新密码不能为空
4. 校验确认密码不能为空
5. 校验新密码和确认密码一致
6. 根据 currentUserId 查询用户
7. 判断用户是否存在
8. 判断账号状态是否正常
9. 校验 oldPassword 是否正确
10. 判断新密码不能和旧密码一样
11. 加密新密码
12. 更新数据库 password_hash
13. 返回成功
```

---

## 8. 伪代码示例

```java
@Override
public void changePassword(Long currentUserId, UserChangePasswordRequest request) {
    validateChangePasswordRequest(request);

    User user = userMapper.selectById(currentUserId);
    if (user == null) {
        throw new BusinessException(401, "登录状态异常，请重新登录");
    }

    if (user.getStatus() != null && user.getStatus() != 0) {
        throw new BusinessException(403, "账号状态异常，无法修改密码");
    }

    if (!passwordEncoder.matches(request.getOldPassword(), user.getPasswordHash())) {
        throw new BusinessException(400, "旧密码错误");
    }

    if (passwordEncoder.matches(request.getNewPassword(), user.getPasswordHash())) {
        throw new BusinessException(400, "新密码不能和旧密码相同");
    }

    String newPasswordHash = passwordEncoder.encode(request.getNewPassword());
    userMapper.updatePassword(currentUserId, newPasswordHash);
}
```

注意：上面使用了 `passwordEncoder`，当前项目如果还没有接入 BCrypt，需要后续先补密码加密能力。

---

## 9. 当前项目如果还没接 BCrypt 怎么办

当前项目注册逻辑里有类似：

```java
// TODO 后续接入密码加密后，这里应保存加密后的密码摘要。
user.setPasswordHash(request.getPassword());
```

这说明当前项目还没有真正加密密码。

所以修改密码功能建议不要长期使用明文比较。

临时学习阶段可以理解为：

```java
if (!Objects.equals(request.getOldPassword(), user.getPasswordHash())) {
    throw new BusinessException(400, "旧密码错误");
}

userMapper.updatePassword(currentUserId, request.getNewPassword());
```

但这只能作为学习过渡。

真正规范做法一定是：

```text
注册：保存 BCrypt 加密后的 passwordHash
登录：BCrypt 校验密码
修改密码：BCrypt 校验旧密码，再保存新密码的 BCrypt hash
```

---

## 10. Mapper 设计

推荐在 `UserMapper` 中新增：

```java
int updatePassword(Long id, String passwordHash);
```

对应 XML：

```xml
<update id="updatePassword">
    update user
    set password_hash = #{passwordHash},
        updated_at = now()
    where id = #{id}
      and deleted_at is null
</update>
```

如果你的表没有 `deleted_at`，就先不写这一行。

---

## 11. 修改成功后要不要让用户重新登录

推荐：

```text
修改密码成功后，让用户重新登录。
```

原因：

- 密码是敏感信息。
- 修改成功后，旧 token 是否继续有效需要明确策略。
- 当前项目没有 Redis token 黑名单，也没有 token 版本号。

当前阶段可以先返回：

```text
密码修改成功，请重新登录
```

前端收到后删除本地 token，跳转登录页。

但是要注意：

> 如果只是删除前端 token，旧 token 在过期前理论上仍然可以被使用。

更严格的方案需要引入：

- Redis token 黑名单
- 用户 tokenVersion
- 修改密码后更新 passwordUpdatedAt
- JWT 中加入 tokenVersion 或 iat 校验

这些可以后续再做。

---

## 12. 错误返回建议

常见错误：

| 场景 | code | message |
|---|---:|---|
| 未登录 | 401 | 请先登录 |
| 旧密码为空 | 400 | 旧密码不能为空 |
| 新密码为空 | 400 | 新密码不能为空 |
| 两次密码不一致 | 400 | 两次输入的新密码不一致 |
| 旧密码错误 | 400 | 旧密码错误 |
| 新旧密码相同 | 400 | 新密码不能和旧密码相同 |
| 用户不存在 | 401 | 登录状态异常，请重新登录 |
| 账号状态异常 | 403 | 账号状态异常，无法修改密码 |

---

## 13. 修改密码和找回密码的区别

修改密码：

```text
用户已经登录
需要输入旧密码
通过 token 确认当前用户
```

找回密码：

```text
用户可能没登录
需要邮箱验证码、手机验证码或其他身份验证
不能只靠 userId 修改
```

所以当前阶段建议只做“修改密码”，不要急着做“忘记密码/找回密码”。

---

## 14. 最推荐的当前实现版本

当前项目推荐：

```text
接口：PUT /api/users/me/password
DTO：UserChangePasswordRequest
Controller：UserController.changePassword
Service：UserService.changePassword
Mapper：UserMapper.updatePassword
```

请求体：

```json
{
  "oldPassword": "123456",
  "newPassword": "abc123456",
  "confirmedPassword": "abc123456"
}
```

用户 id 来源：

```text
JWT token -> 登录拦截器 -> request attribute -> Controller
```

不要从请求体传用户 id。

---

## 15. 一句话总结

修改密码的规范流程是：

```text
用 token 确认当前用户是谁，
用 oldPassword 确认本人操作，
校验 newPassword 和 confirmedPassword，
用 BCrypt 加密新密码，
只更新当前登录用户的 password_hash。
```
