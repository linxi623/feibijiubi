# feibijiubi 接口开发文档

## 1. 通用约定

### 1.1 通用响应格式

所有接口统一返回以下结构：

```json
{
  "code": 200,
  "message": "success",
  "data": null
}
```

字段说明：

| 字段名 | 类型 | 说明 |
|---|---|---|
| code | number | 业务状态码，200 表示成功 |
| message | string | 响应提示信息 |
| data | object / array / null | 具体响应数据，没有数据时为 null |

### 1.2 状态码约定

| code | 含义 |
|---|---|
| 200 | 成功 |
| 400 | 请求参数错误或业务错误 |
| 401 | 未登录或登录状态失效 |
| 403 | 无权限访问 |
| 404 | 数据不存在 |
| 500 | 服务器内部错误 |

### 1.3 登录凭证约定

需要登录的接口，需要在请求头中携带登录成功后返回的 token：

```http
Authorization: Bearer xxx
```

### 1.4 用户角色约定

| role | 含义 |
|---|---|
| USER | 普通用户 |
| ADMIN | 管理员 |

说明：

- 普通注册接口只能创建普通用户，后端默认设置角色为 `USER`。
- 管理员账号不通过普通注册接口创建，可以由数据库初始化或已有管理员创建。
- 前端可以根据 `role` 控制页面显示，但真正的权限判断必须由后端完成。

---

## 2. 普通用户接口

### 2.1 注册用户

#### 基本信息

> 请求路径：`/api/users`  
> 请求方式：`POST`  
> 权限要求：无需登录  
> 接口描述：该接口用于注册一个普通用户。注册成功后用户角色默认为 `USER`，前端不能指定用户角色。

#### 请求参数

```json
{
  "username": "linxi",
  "password": "123456",
  "nickname": "林夕"
}
```

参数说明：

| 参数名 | 类型 | 是否必填 | 说明 |
|---|---|---|---|
| username | string | 是 | 用户名，不能重复 |
| password | string | 是 | 登录密码 |
| nickname | string | 否 | 用户昵称，不填时可以默认使用 username |

#### 响应数据

成功响应：

```json
{
  "code": 200,
  "message": "注册成功",
  "data": null
}
```

失败响应：用户名已存在

```json
{
  "code": 400,
  "message": "用户名已存在",
  "data": null
}
```

失败响应：参数错误

```json
{
  "code": 400,
  "message": "用户名不能为空",
  "data": null
}
```

---

### 2.2 用户登录

#### 基本信息

> 请求路径：`/api/users/login`  
> 请求方式：`POST`  
> 权限要求：无需登录  
> 接口描述：普通用户和管理员共用该登录接口。登录成功后返回 token 和当前用户基本信息。

#### 请求参数

```json
{
  "username": "linxi",
  "password": "123456"
}
```

参数说明：

| 参数名 | 类型 | 是否必填 | 说明 |
|---|---|---|---|
| username | string | 是 | 用户名 |
| password | string | 是 | 登录密码 |

#### 响应数据

成功响应：普通用户登录

```json
{
  "code": 200,
  "message": "登录成功",
  "data": {
    "token": "xxx",
    "user": {
      "id": 1,
      "username": "linxi",
      "nickname": "林夕",
      "avatarUrl": null,
      "role": "USER"
    }
  }
}
```

成功响应：管理员登录

```json
{
  "code": 200,
  "message": "登录成功",
  "data": {
    "token": "xxx",
    "user": {
      "id": 2,
      "username": "admin",
      "nickname": "系统管理员",
      "avatarUrl": null,
      "role": "ADMIN"
    }
  }
}
```

失败响应：账号或密码错误

```json
{
  "code": 400,
  "message": "用户名或密码错误",
  "data": null
}
```

失败响应：账号被禁用

```json
{
  "code": 403,
  "message": "账号已被禁用",
  "data": null
}
```

---

### 2.3 获取当前登录用户信息

#### 基本信息

> 请求路径：`/api/users/me`  
> 请求方式：`GET`  
> 权限要求：需要登录  
> 接口描述：获取当前登录用户的基本信息。

#### 请求头

| 请求头 | 是否必填 | 说明 |
|---|---|---|
| Authorization | 是 | Bearer token |

#### 请求参数

无。

#### 响应数据

成功响应：

```json
{
  "code": 200,
  "message": "查询成功",
  "data": {
    "id": 1,
    "username": "linxi",
    "nickname": "林夕",
    "avatarUrl": null,
    "role": "USER",
    "status": 1,
    "createdAt": "2026-07-02 10:00:00"
  }
}
```

失败响应：未登录

```json
{
  "code": 401,
  "message": "请先登录",
  "data": null
}
```

---

### 2.4 修改当前用户资料

#### 基本信息

> 请求路径：`/api/users/me`  
> 请求方式：`PUT`  
> 权限要求：需要登录  
> 接口描述：修改当前登录用户的基本资料。

#### 请求头

| 请求头 | 是否必填 | 说明 |
|---|---|---|
| Authorization | 是 | Bearer token |

#### 请求参数

```json
{
  "nickname": "新的昵称",
  "avatarUrl": "https://example.com/avatar.png"
}
```

参数说明：

| 参数名 | 类型 | 是否必填 | 说明 |
|---|---|---|---|
| nickname | string | 否 | 用户昵称 |
| avatarUrl | string | 否 | 用户头像地址 |

#### 响应数据

成功响应：

```json
{
  "code": 200,
  "message": "修改成功",
  "data": null
}
```

失败响应：未登录

```json
{
  "code": 401,
  "message": "请先登录",
  "data": null
}
```

失败响应：参数错误

```json
{
  "code": 400,
  "message": "昵称长度不能超过50个字符",
  "data": null
}
```

---

### 2.5 修改当前用户密码

#### 基本信息

> 请求路径：`/api/users/me/password`  
> 请求方式：`PUT`  
> 权限要求：需要登录  
> 接口描述：修改当前登录用户的密码。

#### 请求头

| 请求头 | 是否必填 | 说明 |
|---|---|---|
| Authorization | 是 | Bearer token |

#### 请求参数

```json
{
  "oldPassword": "123456",
  "newPassword": "654321"
}
```

参数说明：

| 参数名 | 类型 | 是否必填 | 说明 |
|---|---|---|---|
| oldPassword | string | 是 | 原密码 |
| newPassword | string | 是 | 新密码 |

#### 响应数据

成功响应：

```json
{
  "code": 200,
  "message": "密码修改成功",
  "data": null
}
```

失败响应：原密码错误

```json
{
  "code": 400,
  "message": "原密码错误",
  "data": null
}
```

失败响应：未登录

```json
{
  "code": 401,
  "message": "请先登录",
  "data": null
}
```

---

### 2.6 退出登录

#### 基本信息

> 请求路径：`/api/users/logout`  
> 请求方式：`POST`  
> 权限要求：需要登录  
> 接口描述：退出当前登录状态。

#### 请求头

| 请求头 | 是否必填 | 说明 |
|---|---|---|
| Authorization | 是 | Bearer token |

#### 请求参数

无。

#### 响应数据

成功响应：

```json
{
  "code": 200,
  "message": "退出登录成功",
  "data": null
}
```

---

## 3. 管理员用户接口

### 3.1 管理员创建用户

#### 基本信息

> 请求路径：`/api/admin/users`  
> 请求方式：`POST`  
> 权限要求：需要管理员权限  
> 接口描述：管理员创建普通用户或管理员用户。该接口不能给普通用户调用。

#### 请求头

| 请求头 | 是否必填 | 说明 |
|---|---|---|
| Authorization | 是 | Bearer token，且当前用户必须是 ADMIN |

#### 请求参数

```json
{
  "username": "newadmin",
  "password": "123456",
  "nickname": "新管理员",
  "role": "ADMIN"
}
```

参数说明：

| 参数名 | 类型 | 是否必填 | 说明 |
|---|---|---|---|
| username | string | 是 | 用户名，不能重复 |
| password | string | 是 | 登录密码 |
| nickname | string | 否 | 用户昵称 |
| role | string | 是 | 用户角色，可选值：USER、ADMIN |

#### 响应数据

成功响应：

```json
{
  "code": 200,
  "message": "创建成功",
  "data": {
    "id": 3,
    "username": "newadmin",
    "nickname": "新管理员",
    "role": "ADMIN",
    "status": 1
  }
}
```

失败响应：无权限

```json
{
  "code": 403,
  "message": "无权限访问",
  "data": null
}
```

失败响应：用户名已存在

```json
{
  "code": 400,
  "message": "用户名已存在",
  "data": null
}
```

---

### 3.2 管理员查询用户列表

#### 基本信息

> 请求路径：`/api/admin/users`  
> 请求方式：`GET`  
> 权限要求：需要管理员权限  
> 接口描述：管理员分页查询用户列表，可按用户名、角色、状态筛选。

#### 请求头

| 请求头 | 是否必填 | 说明 |
|---|---|---|
| Authorization | 是 | Bearer token，且当前用户必须是 ADMIN |

#### 查询参数

```http
GET /api/admin/users?page=1&pageSize=10&keyword=linxi&role=USER&status=1
```

参数说明：

| 参数名 | 类型 | 是否必填 | 说明 |
|---|---|---|---|
| page | number | 是 | 页码，从 1 开始 |
| pageSize | number | 是 | 每页条数 |
| keyword | string | 否 | 用户名或昵称关键词 |
| role | string | 否 | 用户角色：USER、ADMIN |
| status | number | 否 | 用户状态：1 正常，0 禁用 |

#### 响应数据

成功响应：

```json
{
  "code": 200,
  "message": "查询成功",
  "data": {
    "total": 2,
    "list": [
      {
        "id": 1,
        "username": "linxi",
        "nickname": "林夕",
        "avatarUrl": null,
        "role": "USER",
        "status": 1,
        "createdAt": "2026-07-02 10:00:00"
      },
      {
        "id": 2,
        "username": "admin",
        "nickname": "系统管理员",
        "avatarUrl": null,
        "role": "ADMIN",
        "status": 1,
        "createdAt": "2026-07-02 10:10:00"
      }
    ]
  }
}
```

失败响应：无权限

```json
{
  "code": 403,
  "message": "无权限访问",
  "data": null
}
```

---

### 3.3 管理员查询用户详情

#### 基本信息

> 请求路径：`/api/admin/users/{id}`  
> 请求方式：`GET`  
> 权限要求：需要管理员权限  
> 接口描述：管理员根据用户 id 查询用户详情。

#### 请求头

| 请求头 | 是否必填 | 说明 |
|---|---|---|
| Authorization | 是 | Bearer token，且当前用户必须是 ADMIN |

#### 路径参数

| 参数名 | 类型 | 是否必填 | 说明 |
|---|---|---|---|
| id | number | 是 | 用户 id |

#### 响应数据

成功响应：

```json
{
  "code": 200,
  "message": "查询成功",
  "data": {
    "id": 1,
    "username": "linxi",
    "nickname": "林夕",
    "avatarUrl": null,
    "role": "USER",
    "status": 1,
    "createdAt": "2026-07-02 10:00:00",
    "updatedAt": "2026-07-02 10:00:00"
  }
}
```

失败响应：用户不存在

```json
{
  "code": 404,
  "message": "用户不存在",
  "data": null
}
```

失败响应：无权限

```json
{
  "code": 403,
  "message": "无权限访问",
  "data": null
}
```

---

### 3.4 管理员修改用户状态

#### 基本信息

> 请求路径：`/api/admin/users/{id}/status`  
> 请求方式：`PUT`  
> 权限要求：需要管理员权限  
> 接口描述：管理员启用或禁用用户账号。

#### 请求头

| 请求头 | 是否必填 | 说明 |
|---|---|---|
| Authorization | 是 | Bearer token，且当前用户必须是 ADMIN |

#### 路径参数

| 参数名 | 类型 | 是否必填 | 说明 |
|---|---|---|---|
| id | number | 是 | 用户 id |

#### 请求参数

```json
{
  "status": 0
}
```

参数说明：

| 参数名 | 类型 | 是否必填 | 说明 |
|---|---|---|---|
| status | number | 是 | 用户状态：1 正常，0 禁用 |

#### 响应数据

成功响应：

```json
{
  "code": 200,
  "message": "修改成功",
  "data": null
}
```

失败响应：用户不存在

```json
{
  "code": 404,
  "message": "用户不存在",
  "data": null
}
```

失败响应：无权限

```json
{
  "code": 403,
  "message": "无权限访问",
  "data": null
}
```

---

### 3.5 管理员修改用户角色

#### 基本信息

> 请求路径：`/api/admin/users/{id}/role`  
> 请求方式：`PUT`  
> 权限要求：需要管理员权限  
> 接口描述：管理员修改指定用户的角色。

#### 请求头

| 请求头 | 是否必填 | 说明 |
|---|---|---|
| Authorization | 是 | Bearer token，且当前用户必须是 ADMIN |

#### 路径参数

| 参数名 | 类型 | 是否必填 | 说明 |
|---|---|---|---|
| id | number | 是 | 用户 id |

#### 请求参数

```json
{
  "role": "ADMIN"
}
```

参数说明：

| 参数名 | 类型 | 是否必填 | 说明 |
|---|---|---|---|
| role | string | 是 | 用户角色，可选值：USER、ADMIN |

#### 响应数据

成功响应：

```json
{
  "code": 200,
  "message": "修改成功",
  "data": null
}
```

失败响应：用户不存在

```json
{
  "code": 404,
  "message": "用户不存在",
  "data": null
}
```

失败响应：无权限

```json
{
  "code": 403,
  "message": "无权限访问",
  "data": null
}
```

---

### 3.6 管理员删除用户

#### 基本信息

> 请求路径：`/api/admin/users/{id}`  
> 请求方式：`DELETE`  
> 权限要求：需要管理员权限  
> 接口描述：管理员删除指定用户。实际开发中建议优先使用“禁用用户”，谨慎物理删除用户数据。

#### 请求头

| 请求头 | 是否必填 | 说明 |
|---|---|---|
| Authorization | 是 | Bearer token，且当前用户必须是 ADMIN |

#### 路径参数

| 参数名 | 类型 | 是否必填 | 说明 |
|---|---|---|---|
| id | number | 是 | 用户 id |

#### 请求参数

无。

#### 响应数据

成功响应：

```json
{
  "code": 200,
  "message": "删除成功",
  "data": null
}
```

失败响应：用户不存在

```json
{
  "code": 404,
  "message": "用户不存在",
  "data": null
}
```

失败响应：无权限

```json
{
  "code": 403,
  "message": "无权限访问",
  "data": null
}
```