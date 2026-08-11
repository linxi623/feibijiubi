# 菲比啾比后端 API 文档

> 本文档依据当前 Controller、DTO、VO、Service 和拦截器实现整理，用于 Postman、Apifox、curl 以及前后端联调。
>
> 文档只描述当前已经存在的接口；代码发生变化后，应同步更新本文档。

---

## 1. 通用约定

### 1.1 基础地址

仓库没有固定配置 `server.port`。如果本地配置或环境变量未覆盖，Spring Boot 默认地址为：

```text
http://localhost:8080
```

如果修改了 Spring Boot 端口，以实际配置为准。

### 1.2 HTTP 方法约定

当前代码统一采用：**`GET` 用于读取，`POST` 用于一切写操作**（新增、修改、删除、互动动作）。
目前没有使用 `PUT` / `DELETE`。因此像“修改资料”“删除视频”“审核视频”这类接口也都是 `POST`。

### 1.3 Content-Type

| 请求类型 | Content-Type |
|---|---|
| JSON 请求体 | `application/json` |
| 文件上传 | `multipart/form-data` |
| Query / Path 参数 | 通常不需要设置 Content-Type |

### 1.4 通用响应结构

所有 Controller 使用 `ApiResponse<T>`：

```json
{
  "code": 200,
  "message": "success",
  "data": null
}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `code` | number | 业务状态码 |
| `message` | string | 响应提示信息 |
| `data` | object / array / string / null | 响应数据，无数据时为 `null` |

> 当前全局异常处理器没有显式设置 HTTP Status，因此已处理的业务异常通常仍返回 HTTP 200；调用方应主要根据响应 JSON 中的 `code` 判断结果。

### 1.5 常见业务状态码

| code | 说明 |
|---:|---|
| `200` | 请求成功 |
| `400` | 参数错误或业务规则不满足 |
| `401` | 未登录、Token 无效或登录状态异常 |
| `403` | 没有权限或账号状态异常 |
| `404` | 资源不存在 |
| `409` | 资源状态冲突或并发操作冲突 |
| `429` | 触发限流（登录失败过多、上传凭证请求过于频繁等） |
| `500` | 服务端内部错误或数据异常 |

### 1.6 登录认证

除注册、登录以及标记为“可选登录”的接口外，`/api/**` 默认都经过登录拦截器。当前拦截器只对
`/api/auth/register` 和 `/api/auth/login` 放行，其余路径（包括 `/api/category`）都需要有效 Token。

需要登录时，请携带：

```http
Authorization: Bearer <token>
```

缺少请求头、格式错误或 Token 无效时，通常返回：

```json
{
  "code": 401,
  "message": "请先登录",
  "data": null
}
```

修改密码会提升服务端 `token_version`，退出登录会把该 Token 的 `jti` 写入 Redis 黑名单，因此旧 Token
在这两种情况下会立即失效。

### 1.7 权限等级

| 权限 | 说明 |
|---|---|
| 公开 | 不需要 Token |
| 可选登录 | 可以不带 Token；如果携带，Token 必须合法 |
| 登录 | 必须携带有效 Token |
| 管理员 | 必须登录，且角色为管理员或超级管理员 |

管理员路径 `/api/admin/**` 会经过管理员拦截器。

### 1.8 参数位置说明

| 名称 | 说明 |
|---|---|
| Path | URL 路径参数，例如 `/api/videos/{vid}` |
| Query | URL 查询参数，例如 `?size=15` |
| Body | JSON 请求体 |
| Form | `multipart/form-data` 表单字段 |

缺少必填 Query 参数或参数类型转换失败时，通常返回：

```json
{
  "code": 400,
  "message": "请求参数格式不正确",
  "data": null
}
```

> 当前没有单独统一处理所有 JSON 解析错误；请求体缺失或 JSON 格式错误时，可能返回通用错误响应。

---

## 2. 接口总览

当前 Controller 共提供 25 个接口。

### 2.1 账号与用户

| 模块 | 方法 | 路径 | 权限 |
|---|---|---|---|
| 账号 | POST | `/api/auth/register` | 公开 |
| 账号 | POST | `/api/auth/login` | 公开 |
| 账号 | POST | `/api/auth/logout` | 登录 |
| 用户 | GET | `/api/users/me` | 登录 |
| 用户 | GET | `/api/users/{uid}` | 可选登录 |
| 用户 | POST | `/api/users/me` | 登录 |
| 用户 | POST | `/api/users/me/password` | 登录 |
| 用户 | POST | `/api/users/me/avatar` | 登录 |
| 关注 | POST | `/api/users/{uid}/subscribe` | 登录 |

### 2.2 分类

| 模块 | 方法 | 路径 | 权限 |
|---|---|---|---|
| 分类 | GET | `/api/category` | 登录 |

### 2.3 视频与互动

| 模块 | 方法 | 路径 | 权限 |
|---|---|---|---|
| 视频 | POST | `/api/videos/upload-url` | 登录 |
| 视频 | POST | `/api/videos/cover` | 登录 |
| 视频 | POST | `/api/videos` | 登录 |
| 视频 | POST | `/api/videos/{vid}/delete` | 登录且为作者 |
| 视频 | GET | `/api/videos/{vid}` | 可选登录 |
| 视频 | GET | `/api/videos/feed` | 可选登录 |
| 互动 | POST | `/api/videos/{vid}/play-count` | 可选登录 |
| 互动 | POST | `/api/videos/{vid}/progress` | 登录 |
| 互动 | POST | `/api/videos/{vid}/islike` | 登录 |
| 互动 | POST | `/api/videos/{vid}/coin` | 登录 |
| 互动 | POST | `/api/videos/{vid}/share` | 可选登录 |
| 互动 | POST | `/api/videos/{vid}/collect` | 登录 |

### 2.4 管理员视频审核

| 模块 | 方法 | 路径 | 权限 |
|---|---|---|---|
| 审核 | POST | `/api/admin/videos/{vid}/review` | 管理员 |
| 审核 | GET | `/api/admin/videos/{vid}` | 管理员 |
| 审核 | GET | `/api/admin/videos/page` | 管理员 |

---

# 3. 账号接口

## 3.1 用户注册

```http
POST /api/auth/register
Content-Type: application/json
```

权限：**公开**

### 请求体

```json
{
  "username": "linxi",
  "password": "123456",
  "confirmedPassword": "123456"
}
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `username` | string | 是 | 用户名，不能为空且不能重复 |
| `password` | string | 是 | 密码，不能为空 |
| `confirmedPassword` | string | 是 | 必须与 `password` 一致 |

### 成功响应

```json
{
  "code": 200,
  "message": "恭喜你成功注册F站",
  "data": null
}
```

### 常见错误

| code | message | 触发条件 |
|---:|---|---|
| 400 | `用户名已存在` | 用户名重复 |
| 400 | `前后两次密码输入不一致` | 两次密码不同 |
| 400 | DTO 校验提示 | 必填字段为空 |

### curl

```bash
curl -X POST "http://localhost:8080/api/auth/register" \
  -H "Content-Type: application/json" \
  -d '{"username":"linxi","password":"123456","confirmedPassword":"123456"}'
```

---

## 3.2 用户登录

```http
POST /api/auth/login
Content-Type: application/json
```

权限：**公开**

### 请求体

```json
{
  "username": "linxi",
  "password": "123456"
}
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `username` | string | 是 | 用户名 |
| `password` | string | 是 | 密码 |

### 成功响应

当前 `UserLoginVO` 只返回 Token，不返回用户对象：

```json
{
  "code": 200,
  "message": "登录成功",
  "data": {
    "token": "eyJhbGciOi..."
  }
}
```

### 登录失败限流

登录接口带有基于 Redis 的固定窗口失败限流：连续登录失败达到上限后，在冷却时间内会直接拒绝登录并返回
`429`；登录成功后会清空该用户名的失败计数。

### 常见错误

| code | message | 触发条件 |
|---:|---|---|
| 400 | `用户名或密码错误` | 用户不存在或密码错误 |
| 403 | `账号状态异常，无法登录` | 账号不是正常状态 |
| 429 | `尝试次数过多，请稍后再试` | 登录失败次数达到上限 |

### curl

```bash
curl -X POST "http://localhost:8080/api/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"username":"linxi","password":"123456"}'
```

---

## 3.3 退出登录

```http
POST /api/auth/logout
Authorization: Bearer <token>
```

权限：**登录**

### 请求参数

无请求体。当前用户身份和 Token 上下文由登录拦截器写入请求。

### 成功响应

```json
{
  "code": 200,
  "message": "退出成功",
  "data": null
}
```

### 当前实现说明

- 退出登录会把当前 Token 的 `jti` 写入 Redis 黑名单，剩余 TTL 与 Token 过期时间对齐，之后该 Token 无法
  再通过登录拦截器（服务端立即失效）。
- 该接口标记为“允许已撤销 Token 访问”（`@AllowRevokedToken`），因此已在黑名单中的 Token 再次调用退出会
  幂等地直接返回成功。
- 客户端退出后仍应删除本地 Token。

### 常见错误

| code | message |
|---:|---|
| 401 | `登录状态失效` |
| 401 | `登录状态已失效` |

### curl

```bash
curl -X POST "http://localhost:8080/api/auth/logout" \
  -H "Authorization: Bearer <token>"
```

---

# 4. 用户接口

## 4.1 获取当前用户信息

```http
GET /api/users/me
Authorization: Bearer <token>
```

权限：**登录**

### 成功响应

```json
{
  "code": 200,
  "message": "查询成功",
  "data": {
    "id": 1,
    "username": "linxi",
    "nickname": "用户linxi",
    "avatarUrl": null,
    "backgroundUrl": null,
    "gender": 2,
    "description": null,
    "experience": 0,
    "coin": 0,
    "vip": 0,
    "status": 0,
    "role": 0,
    "auth": 0,
    "authMsg": null,
    "createdAt": "2026-07-09T10:00:00",
    "userCount": {
      "fansCount": 0,
      "starCount": 0,
      "loveCount": 0,
      "videoCount": 0
    },
    "subscribed": false
  }
}
```

### 常见错误

| code | message |
|---:|---|
| 401 | `请先登录` |
| 401 | `查询用户异常` |
| 403 | `账号状态异常，无法访问` |

### curl

```bash
curl "http://localhost:8080/api/users/me" \
  -H "Authorization: Bearer <token>"
```

---

## 4.2 获取指定用户信息

```http
GET /api/users/{uid}
```

权限：**可选登录**

### Path 参数

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `uid` | integer | 是 | 目标用户 ID |

### 响应

响应结构与 `GET /api/users/me` 的 `UserVO` 相同。

### 当前实现说明

当前 Service 复用了查询用户信息的方法，`subscribed` 暂时固定为 `false`，尚未根据当前访问者计算是否关注目标用户。`GET /api/users/me` 当前也固定返回 `subscribed: false`。该可选登录接口会校验所携带 Token，但业务逻辑暂不使用当前访问者身份。

### curl

```bash
curl "http://localhost:8080/api/users/2"
```

携带可选登录状态：

```bash
curl "http://localhost:8080/api/users/2" \
  -H "Authorization: Bearer <token>"
```

---

## 4.3 修改当前用户资料

```http
POST /api/users/me
Authorization: Bearer <token>
Content-Type: application/json
```

权限：**登录**

### 请求体

```json
{
  "nickname": "新的昵称",
  "gender": 1,
  "description": "这是我的个人简介"
}
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `nickname` | string | 否 | 昵称 |
| `gender` | number | 否 | `0` 女，`1` 男，`2` 未知 |
| `description` | string | 否 | 个人简介 |

> 具体可接受范围以 DTO 校验、Service 逻辑和数据库约束为准。

### 成功响应

```json
{
  "code": 200,
  "message": "修改成功",
  "data": null
}
```

### curl

```bash
curl -X POST "http://localhost:8080/api/users/me" \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"nickname":"新的昵称","gender":1,"description":"这是我的个人简介"}'
```

---

## 4.4 修改当前用户密码

```http
POST /api/users/me/password
Authorization: Bearer <token>
Content-Type: application/json
```

权限：**登录**

### 请求体

```json
{
  "oldPassword": "123456",
  "newPassword": "654321",
  "confirmedPassword": "654321"
}
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `oldPassword` | string | 是 | 当前密码 |
| `newPassword` | string | 是 | 新密码，不能与旧密码相同 |
| `confirmedPassword` | string | 是 | 必须与 `newPassword` 相同 |

### 成功响应

```json
{
  "code": 200,
  "message": "修改成功",
  "data": null
}
```

修改成功后会提升 `token_version`，此前签发的所有 Token 立即失效，需要重新登录。

### 常见错误

| code | message |
|---:|---|
| 400 | `旧密码错误` |
| 400 | `两次密码不一致` |
| 400 | `新密码不能与旧密码一致` |
| 403 | `账号状态异常，无法修改` |

### curl

```bash
curl -X POST "http://localhost:8080/api/users/me/password" \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"oldPassword":"123456","newPassword":"654321","confirmedPassword":"654321"}'
```

---

## 4.5 修改当前用户头像

```http
POST /api/users/me/avatar
Authorization: Bearer <token>
Content-Type: multipart/form-data
```

权限：**登录**

### Form 参数

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `file` | file | 是 | 头像文件 |

### 文件限制

| 限制 | 当前实现 |
|---|---|
| 最大大小 | 2MB |
| MIME 类型 | `image/jpg`、`image/jpeg`、`image/png` |
| 文件名 | 必须包含后缀 |

### 成功响应

`data` 直接是头像 URL 字符串：

```json
{
  "code": 200,
  "message": "修改成功",
  "data": "https://example-cos-domain/avatar/1/xxx.png"
}
```

### 常见错误

| code | message |
|---:|---|
| 400 | `图片不能为空` |
| 400 | `图片大小不能超过2MB` |
| 400 | `图片格式不支持` |
| 400 | `文件后缀不能为空` |
| 500 | `文件上传失败` |

### curl

```bash
curl -X POST "http://localhost:8080/api/users/me/avatar" \
  -H "Authorization: Bearer <token>" \
  -F "file=@/path/to/avatar.png"
```

---

## 4.6 关注或取消关注用户

```http
POST /api/users/{uid}/subscribe?isSet={boolean}
Authorization: Bearer <token>
```

权限：**登录**

### 参数

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `uid` | Path | integer | 是 | 被关注用户 ID |
| `isSet` | Query | boolean | 是 | `true` 关注，`false` 取消关注 |

### 成功响应

```json
{
  "code": 200,
  "message": "success",
  "data": null
}
```

### 常见错误

| code | message |
|---:|---|
| 400 | `请求参数不能为空` |
| 400 | `不能重复关注` |
| 400 | `不能重复取消关注` |
| 500 | `关注失败` |
| 500 | `取消关注失败` |

### curl

关注：

```bash
curl -X POST "http://localhost:8080/api/users/2/subscribe?isSet=true" \
  -H "Authorization: Bearer <token>"
```

取消关注：

```bash
curl -X POST "http://localhost:8080/api/users/2/subscribe?isSet=false" \
  -H "Authorization: Bearer <token>"
```

---

# 5. 分类接口

## 5.1 获取分类树

```http
GET /api/category
Authorization: Bearer <token>
```

权限：**登录**

返回全部主分区及其子分区（两级结构），结果使用 Redis Cache-Aside 缓存。

### 成功响应

```json
{
  "code": 200,
  "message": "success",
  "data": [
    {
      "mcId": "douga",
      "mcName": "动画",
      "children": [
        {
          "scId": "mad",
          "scName": "MAD·AMV",
          "description": "具有一定制作程度的动画或静画的二次创作视频",
          "rcmTags": ["MAD", "AMV"]
        }
      ]
    }
  ]
}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `mcId` | string | 主分区 ID |
| `mcName` | string | 主分区名称 |
| `children` | array | 子分区列表 |
| `children[].scId` | string | 子分区 ID |
| `children[].scName` | string | 子分区名称 |
| `children[].description` | string | 子分区描述 |
| `children[].rcmTags` | array\<string> | 推荐标签 |

### curl

```bash
curl "http://localhost:8080/api/category" \
  -H "Authorization: Bearer <token>"
```

---

# 6. 视频接口

## 6.1 获取视频直传临时凭证

```http
POST /api/videos/upload-url
Authorization: Bearer <token>
Content-Type: application/json
```

权限：**登录**

该接口生成视频临时 Object Key 和腾讯云 COS 临时凭证，前端随后直接上传视频到 COS。接口带有基于 Redis 的
上传凭证请求限流，短时间内请求过多会返回 `429`。

### 请求体

```json
{
  "fileName": "demo.mp4",
  "contentType": "video/mp4",
  "fileSize": 10485760
}
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `fileName` | string | 是 | 原始文件名，必须包含后缀 |
| `contentType` | string | 是 | 视频 MIME 类型 |
| `fileSize` | long | 是 | 文件大小，单位字节，必须大于 0 |

### 文件限制

| 限制 | 当前实现 |
|---|---|
| 最大大小 | 2GB |
| MIME 类型 | `video/mp4`、`video/3gp`、`video/mpeg` |

### 成功响应

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "tempKey": "temp/videos/1/20260709/xxx.mp4",
    "bucket": "your-bucket-name",
    "region": "ap-shenzhen-fsi",
    "tmpSecretId": "临时 SecretId",
    "tmpSecretKey": "临时 SecretKey",
    "sessionToken": "临时 sessionToken",
    "startTime": 1783580000,
    "expiredTime": 1783581800,
    "maxFileSize": 2147483648
  }
}
```

> 后续投稿时，`data.tempKey` 应作为 `tempVideoKey`。

### 常见错误

| code | message |
|---:|---|
| 400 | `请求参数不能为空` |
| 400 | `该视频文件格式不支持` |
| 400 | `文件大小不合法` |
| 400 | `该文件超出限制` |
| 400 | `文件后缀不能为空` |
| 429 | `请求过于频繁，请稍后再试` |

### curl

```bash
curl -X POST "http://localhost:8080/api/videos/upload-url" \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"fileName":"demo.mp4","contentType":"video/mp4","fileSize":10485760}'
```

---

## 6.2 上传视频封面

```http
POST /api/videos/cover
Authorization: Bearer <token>
Content-Type: multipart/form-data
```

权限：**登录**

### Form 参数

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `file` | file | 是 | 视频封面图片 |

### 文件限制

| 限制 | 当前实现 |
|---|---|
| 最大大小 | 2MB |
| MIME 类型 | `image/jpg`、`image/jpeg`、`image/png` |
| 文件名 | 必须包含后缀 |

### 成功响应

`data` 是临时封面 Object Key：

```json
{
  "code": 200,
  "message": "success",
  "data": "temp/covers/1/20260709/xxx.png"
}
```

### curl

```bash
curl -X POST "http://localhost:8080/api/videos/cover" \
  -H "Authorization: Bearer <token>" \
  -F "file=@/path/to/cover.png"
```

---

## 6.3 投稿视频

```http
POST /api/videos
Authorization: Bearer <token>
Content-Type: application/json
```

权限：**登录**

投稿前必须已经：

1. 调用 `/api/videos/upload-url` 并将视频上传至返回的 `tempKey`。
2. 调用 `/api/videos/cover` 获得临时封面 Key。

### 请求体

```json
{
  "title": "我的第一个视频",
  "sourceType": 1,
  "visibility": 0,
  "duration": 120.5,
  "mcId": "douga",
  "scId": "mad",
  "tags": "测试,菲比啾比,投稿",
  "description": "这是我的第一个投稿视频",
  "tempCoverKey": "temp/covers/1/20260709/xxx.png",
  "tempVideoKey": "temp/videos/1/20260709/xxx.mp4"
}
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `title` | string | 是 | 视频标题 |
| `sourceType` | integer | 是 | 来源类型约定：`1` 自制、`2` 转载；当前 Service 未校验取值范围 |
| `visibility` | integer | 是 | `0` 公开，`1` 私密 |
| `duration` | double | 是 | 时长，单位秒，必须大于 0 |
| `mcId` | string | 是 | 主分区 ID；当前仅校验非空，未校验分区是否存在 |
| `scId` | string | 是 | 子分区 ID；当前仅校验非空，未校验分区是否存在或是否属于主分区 |
| `tags` | string | 否 | 标签，可使用逗号分隔 |
| `description` | string | 否 | 视频简介 |
| `tempCoverKey` | string | 是 | 当前用户未过期、未提交的临时封面 Key |
| `tempVideoKey` | string | 是 | 当前用户未过期、未提交的临时视频 Key |

### 成功响应

```json
{
  "code": 200,
  "message": "投稿成功",
  "data": {
    "vid": 1,
    "title": "我的第一个视频",
    "coverUrl": "https://example-cos-domain/covers/1/xxx.png",
    "videoUrl": "https://example-cos-domain/videos/1/xxx.mp4"
  }
}
```

投稿成功后，视频状态为 `0`（审核中），但当前响应不返回 `status`。

### 视频状态

| status | 枚举 | 说明 |
|---:|---|---|
| 0 | `PENDING` | 审核中 |
| 1 | `APPROVED` | 审核通过 |
| 2 | `REJECTED` | 打回整改 |
| 3 | `REMOVED` | 违规删除 |

### 常见错误

| code | message |
|---:|---|
| 400 | `视频时长不合法` |
| 400 | `视频可见性不合法` |
| 400 | `视频临时文件路径不合法` |
| 400 | `封面临时文件路径不合法` |
| 400 | `视频临时文件记录不存在` |
| 400 | `封面临时文件记录不存在` |
| 403 | `视频临时文件不属于当前用户` |
| 403 | `封面临时文件不属于当前用户` |
| 500 | `视频投稿失败` |
| 500 | `视频统计初始化失败` |

### curl

```bash
curl -X POST "http://localhost:8080/api/videos" \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"title":"我的第一个视频","sourceType":1,"visibility":0,"duration":120.5,"mcId":"douga","scId":"mad","tags":"测试,菲比啾比,投稿","description":"这是我的第一个投稿视频","tempCoverKey":"temp/covers/1/xxx.png","tempVideoKey":"temp/videos/1/xxx.mp4"}'
```

---

## 6.4 投稿者删除自己的视频

```http
POST /api/videos/{vid}/delete
Authorization: Bearer <token>
```

权限：**登录且必须是视频作者**

### Path 参数

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `vid` | integer | 是 | 视频 ID，必须大于 0 |

### 成功响应

```json
{
  "code": 200,
  "message": "删除成功",
  "data": null
}
```

### 当前删除行为

- 使用 `deleted_at` 逻辑删除视频。
- SQL 同时校验视频 ID、作者 ID 和未删除状态。
- 数据库事务提交后，再清理腾讯云 COS 中的正式视频和封面文件。
- 不物理删除 `video_status` 和 `user_video` 数据。

### 常见错误

| code | message |
|---:|---|
| 400 | `视频参数不合法` |
| 403 | `你无权删除该视频` |
| 404 | `视频不存在` |
| 404 | `视频已经删除` |
| 409 | `视频已经被删除，请勿重复操作` |
| 500 | `视频删除事务未正确开启` |

### curl

```bash
curl -X POST "http://localhost:8080/api/videos/1/delete" \
  -H "Authorization: Bearer <token>"
```

---

## 6.5 获取视频详情

```http
GET /api/videos/{vid}
```

权限：**可选登录**

接口只查询审核通过且未删除的视频。私密视频仅作者本人可以查看。

### Path 参数

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `vid` | integer | 是 | 视频 ID，必须大于 0 |

### 成功响应

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "vid": 1,
    "uid": 2,
    "title": "我的第一个视频",
    "sourceType": 1,
    "duration": 120.5,
    "mcId": "douga",
    "scId": "mad",
    "tags": "测试,菲比啾比,投稿",
    "description": "这是视频简介",
    "coverUrl": "https://example-cos-domain/covers/1/cover.png",
    "videoUrl": "https://example-cos-domain/videos/1/video.mp4",
    "createdAt": "2026-07-12T10:00:00",
    "playTimes": 100,
    "likeTimes": 20,
    "coinTimes": 3,
    "collectTimes": 8,
    "commentTimes": 0,
    "danmuTimes": 0,
    "shareTimes": 5,
    "liked": false,
    "coin": 0,
    "collected": false,
    "playTime": 0.0,
    "avatarUrl": "https://example-cos-domain/avatar/2.png",
    "nickname": "投稿用户",
    "videoCount": 1,
    "fansCount": 0,
    "subscribed": false
  }
}
```

未登录或不存在用户互动记录时，当前转换器使用以下默认值：

```text
liked = false
coin = 0
collected = false
playTime = 0.0
```

### 常见错误

| code | message |
|---:|---|
| 400 | `视频参数不合法` |
| 403 | `你无权查看此视频` |
| 404 | `视频不存在` |
| 500 | `视频统计数据异常` |
| 500 | `视频作者数据异常` |

### curl

游客：

```bash
curl "http://localhost:8080/api/videos/1"
```

登录用户：

```bash
curl "http://localhost:8080/api/videos/1" \
  -H "Authorization: Bearer <token>"
```

---

## 6.6 获取视频 Feed

```http
GET /api/videos/feed?cursor={cursor}&size={size}&mcId={mcId}&scId={scId}
```

权限：**可选登录**

### Query 参数

| 参数 | 类型 | 必填 | 默认值 | 说明 |
|---|---|---|---|---|
| `cursor` | string | 否 | 无 | 下一页游标，首次请求不传 |
| `size` | integer | 否 | `15` | 每页数量，取值范围 `1`～`25` |
| `mcId` | string | 否 | 无 | 主分区 ID，按主分区过滤 |
| `scId` | string | 否 | 无 | 子分区 ID，按子分区过滤 |

`size` 由 Bean Validation 约束在 `1`～`25`，越界会返回 `400`（如 `每页数量不能小于1`、`每页数量不能多余25`）。

内部游标格式为：

```text
{createdAt}_{vid}
```

客户端应直接使用响应中的 `nextCursor`，不要自行拼接。

### 成功响应

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "items": [
      {
        "vid": 1,
        "uid": 2,
        "title": "我的第一个视频",
        "coverUrl": "https://example-cos-domain/covers/1/cover.png",
        "duration": 120.5,
        "playTimes": 100,
        "commentTimes": 0,
        "createdAt": "2026-07-12T10:00:00",
        "nickname": "投稿用户"
      }
    ],
    "nextCursor": "2026-07-12T10:00:00_1",
    "hasMore": true
  }
}
```

### 当前实现注意

- 游标格式错误当前可能被包装为 `500`，而不是更合理的 `400`。
- 该接口可携带 Token，但当前 Feed 业务逻辑不使用当前登录用户。

### curl

首次请求：

```bash
curl "http://localhost:8080/api/videos/feed?size=15"
```

按分区过滤 + 下一页：

```bash
curl "http://localhost:8080/api/videos/feed?cursor=<nextCursor>&size=15&mcId=douga&scId=mad"
```

---

# 7. 用户视频互动接口

## 7.1 增加播放量

```http
POST /api/videos/{vid}/play-count
```

权限：**可选登录**

### Path 参数

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `vid` | integer | 是 | 已发布视频 ID |

### 成功响应

```json
{
  "code": 200,
  "message": "success",
  "data": null
}
```

### 当前实现注意

- 只增加视频总播放量，不保存播放进度。
- 尚未按用户、IP 或时间窗口去重，重复请求会重复计数。
- 该接口可携带 Token，但当前计数逻辑不使用当前登录用户。

### curl

```bash
curl -X POST "http://localhost:8080/api/videos/1/play-count"
```

---

## 7.2 保存播放进度

```http
POST /api/videos/{vid}/progress?playTime={seconds}
Authorization: Bearer <token>
```

权限：**登录**

### 参数

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `vid` | Path | integer | 是 | 已发布视频 ID |
| `playTime` | Query | double | 是 | 当前播放秒数，必须有限、非负且不超过视频总时长 |

### 成功响应

```json
{
  "code": 200,
  "message": "success",
  "data": null
}
```

### 常见错误

| code | message |
|---:|---|
| 400 | `播放进度不合法` |
| 400 | `播放进度不能超过视频时长` |
| 404 | `视频不存在` |
| 500 | `用户视频关系创建失败` |
| 500 | `播放进度保存失败` |

### curl

```bash
curl -X POST "http://localhost:8080/api/videos/1/progress?playTime=35.5" \
  -H "Authorization: Bearer <token>"
```

---

## 7.3 点赞、取消点赞、点踩或取消点踩

```http
POST /api/videos/{vid}/islike?islike={boolean}&isSet={boolean}
Authorization: Bearer <token>
```

权限：**登录**

### 参数

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `vid` | Path | integer | 是 | 已发布视频 ID |
| `islike` | Query | boolean | 是 | `true` 操作点赞，`false` 操作点踩 |
| `isSet` | Query | boolean | 是 | `true` 设置，`false` 取消 |

| islike | isSet | 操作 |
|---|---|---|
| true | true | 点赞 |
| true | false | 取消点赞 |
| false | true | 点踩 |
| false | false | 取消点踩 |

重复设置相同状态时，当前 Service 直接返回成功，不重复修改统计。

> 当前点赞和点踩状态尚未实现自动互斥：设置点赞不会自动取消已有点踩，设置点踩也不会自动取消已有点赞。

### 成功响应

```json
{
  "code": 200,
  "message": "success",
  "data": null
}
```

### curl

```bash
curl -X POST "http://localhost:8080/api/videos/1/islike?islike=true&isSet=true" \
  -H "Authorization: Bearer <token>"
```

---

## 7.4 视频投币

```http
POST /api/videos/{vid}/coin?coin={number}
Authorization: Bearer <token>
```

权限：**登录**

### 参数

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `vid` | Path | integer | 是 | 已发布视频 ID |
| `coin` | Query | byte | 是 | 只能传 `1` 或 `2` |

当前实现不允许同一用户对同一视频重复投币。

### 成功响应

```json
{
  "code": 200,
  "message": "success",
  "data": null
}
```

### 常见错误

| code | message |
|---:|---|
| 400 | `单次只能投一个或两个硬币` |
| 400 | `硬币数量不够` |
| 400 | `用户无法对同一个视频多次投币` |
| 404 | `用户不存在` |
| 404 | `视频不存在` |

### curl

```bash
curl -X POST "http://localhost:8080/api/videos/1/coin?coin=1" \
  -H "Authorization: Bearer <token>"
```

---

## 7.5 增加分享量

```http
POST /api/videos/{vid}/share
```

权限：**可选登录**

### 成功响应

```json
{
  "code": 200,
  "message": "success",
  "data": null
}
```

当前接口未按用户、IP 或时间窗口去重，重复请求会重复增加分享量。该接口可携带 Token，但当前分享计数逻辑不使用当前登录用户。

### curl

```bash
curl -X POST "http://localhost:8080/api/videos/1/share"
```

---

## 7.6 收藏或取消收藏

```http
POST /api/videos/{vid}/collect?isCollect={boolean}
Authorization: Bearer <token>
```

权限：**登录**

### 参数

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `vid` | Path | integer | 是 | 已发布视频 ID |
| `isCollect` | Query | boolean | 是 | `true` 收藏，`false` 取消收藏 |

重复设置相同状态时，当前 Service 直接返回成功，不重复修改统计。

### 成功响应

```json
{
  "code": 200,
  "message": "success",
  "data": null
}
```

### curl

收藏：

```bash
curl -X POST "http://localhost:8080/api/videos/1/collect?isCollect=true" \
  -H "Authorization: Bearer <token>"
```

取消收藏：

```bash
curl -X POST "http://localhost:8080/api/videos/1/collect?isCollect=false" \
  -H "Authorization: Bearer <token>"
```

> 互动计数（播放/点赞/投币/收藏/分享）不会直接落库，而是先更新 Redis 计数，再经 RabbitMQ 事件与批量
> 聚合任务异步写入 `video_status`。相关设计见 `docs/video-status-full-flow-design.md`。

---

# 8. 管理员视频审核接口

以下接口均要求：

```http
Authorization: Bearer <管理员或超级管理员 token>
```

## 8.1 审核视频

```http
POST /api/admin/videos/{vid}/review
Content-Type: application/json
```

权限：**管理员**

### Path 参数

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `vid` | integer | 是 | 视频 ID，必须大于 0 |

### 请求体

审核通过：

```json
{
  "result": "APPROVED",
  "reason": null
}
```

审核驳回：

```json
{
  "result": "REJECTED",
  "reason": "标题或投稿信息不符合要求"
}
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `result` | string | 是 | 当前只支持 `APPROVED` 或 `REJECTED`，大小写不敏感 |
| `reason` | string | 条件必填 | `REJECTED` 时必须填写 |

### 成功响应

```json
{
  "code": 200,
  "message": "success",
  "data": null
}
```

### 常见错误

| code | message |
|---:|---|
| 400 | `视频参数不合法` |
| 400 | `审核结果只能是 APPROVED 或 REJECTED` |
| 400 | `驳回视频必须存在原因` |
| 403 | `你的权限不足` |
| 404 | `视频不存在` |
| 409 | 状态不允许流转的动态提示 |
| 409 | `视频已经由其他管理员审核，请刷新后重试` |

### curl

```bash
curl -X POST "http://localhost:8080/api/admin/videos/1/review" \
  -H "Authorization: Bearer <管理员token>" \
  -H "Content-Type: application/json" \
  -d '{"result":"APPROVED","reason":null}'
```

---

## 8.2 查询管理员视频详情

```http
GET /api/admin/videos/{vid}
```

权限：**管理员**

该接口用于审核场景，不受公开视频审核通过条件限制。

### 成功响应

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "vid": 1,
    "uid": 2,
    "title": "待审核视频",
    "sourceType": 1,
    "visibility": 0,
    "duration": 120.5,
    "mcId": "douga",
    "scId": "mad",
    "tags": "测试,投稿",
    "description": "视频简介",
    "coverUrl": "https://example-cos-domain/covers/1/cover.png",
    "videoUrl": "https://example-cos-domain/videos/1/video.mp4",
    "status": 0,
    "createdAt": "2026-07-12T10:00:00",
    "playTimes": 0,
    "likeTimes": 0,
    "unlikeTimes": 0,
    "commentTimes": 0,
    "coinTimes": 0,
    "shareTimes": 0,
    "collectTimes": 0,
    "danmuTimes": 0,
    "avatarUrl": "https://example-cos-domain/avatar/2.png",
    "nickname": "投稿用户",
    "videoCount": 1,
    "fansCount": 0
  }
}
```

### 常见错误

| code | message |
|---:|---|
| 400 | `视频参数无效` |
| 403 | `你的权限不足` |
| 404 | `视频不存在` |
| 404 | `视频状态为空` |
| 500 | `视频作者数据异常` |

### curl

```bash
curl "http://localhost:8080/api/admin/videos/1" \
  -H "Authorization: Bearer <管理员token>"
```

---

## 8.3 分页查询指定状态的视频

```http
GET /api/admin/videos/page?page={page}&status={status}&quantity={quantity}
```

权限：**管理员**

### Query 参数

| 参数 | 类型 | 必填 | 默认值 | 说明 |
|---|---|---|---|---|
| `page` | integer | 是 | 无 | 页码，从 1 开始 |
| `status` | byte | 否 | `1` | 视频审核状态 |
| `quantity` | integer | 否 | `10` | 每页数量 |

> Controller 当前将 `page` 声明为必填参数，因此 HTTP 请求不能省略。
>
> HTTP 请求省略 `status` 时，Controller 会传入默认值 `1`。
>
> 当前未校验 `page`、`quantity` 必须为正数，也未限制最大 `quantity`。`status` 约定为 `0`～`3`，但当前没有范围校验。查询结果按 `created_at` 升序排列。

### 状态值

| status | 说明 |
|---:|---|
| 0 | 待审核 |
| 1 | 审核通过 |
| 2 | 审核驳回 |
| 3 | 违规删除 |

### 成功响应

```json
{
  "code": 200,
  "message": "success",
  "data": [
    {
      "vid": 1,
      "uid": 2,
      "title": "待审核视频",
      "coverUrl": "https://example-cos-domain/covers/1/cover.png",
      "duration": 120.5,
      "createdAt": "2026-07-12T10:00:00"
    }
  ]
}
```

### curl

```bash
curl "http://localhost:8080/api/admin/videos/page?page=1&status=0&quantity=10" \
  -H "Authorization: Bearer <管理员token>"
```

---

# 9. 推荐联调顺序

## 9.1 用户功能

1. `POST /api/auth/register` 注册。
2. `POST /api/auth/login` 登录。
3. 保存响应中的 `data.token`。
4. `GET /api/users/me` 验证 Token。
5. `POST /api/users/me` 修改资料。
6. `POST /api/users/me/avatar` 修改头像。
7. `POST /api/users/me/password` 修改密码（旧 Token 随之失效）。
8. 使用新密码重新登录。
9. `GET /api/users/{uid}` 查询其他用户。
10. `POST /api/users/{uid}/subscribe` 测试关注和取消关注。
11. `POST /api/auth/logout` 退出，确认旧 Token 立即失效。

## 9.2 视频投稿

1. 登录并取得 Token。
2. `GET /api/category` 获取可用分区，选择 `mcId` / `scId`。
3. 调用 `POST /api/videos/upload-url` 获取临时上传凭证和 `tempKey`。
4. 使用临时凭证将视频上传到 COS 的 `tempKey`。
5. 调用 `POST /api/videos/cover` 上传封面并取得临时封面 Key。
6. 调用 `POST /api/videos` 完成投稿。
7. 使用管理员接口查询待审核视频。
8. 使用管理员接口审核通过。
9. 调用 `GET /api/videos/{vid}` 查询公开视频。
10. 调用 `GET /api/videos/feed` 验证 Feed（可带 `mcId` / `scId`）。

## 9.3 视频互动

1. `POST /api/videos/{vid}/play-count` 增加播放量。
2. `POST /api/videos/{vid}/progress` 保存登录用户播放进度。
3. `POST /api/videos/{vid}/islike` 测试点赞、取消点赞、点踩和取消点踩。
4. `POST /api/videos/{vid}/coin` 测试投币。
5. `POST /api/videos/{vid}/share` 增加分享量。
6. `POST /api/videos/{vid}/collect` 测试收藏和取消收藏。
7. 等待聚合任务落库后，再次查询视频详情，核对统计和当前用户互动状态。

## 9.4 视频删除

1. 使用视频作者 Token 调用 `POST /api/videos/{vid}/delete`。
2. 确认公开详情和 Feed 不再返回该视频。
3. 确认数据库 `video.deleted_at` 已写入。
4. 确认 COS 视频和封面在事务提交后被清理。
5. 使用非作者 Token 删除视频，预期返回 `403`。
6. 重复或并发删除，预期返回 `404` 或 `409`。

---

# 10. 当前实现注意事项

1. 当前密码仍以明文形式保存和比对，仅适合学习阶段；正式环境应使用 BCrypt 等密码哈希算法。
2. 用户角色：`0` 普通用户、`1` 管理员、`2` 超级管理员。
3. 用户状态：`0` 正常、`1` 封禁、`2` 注销。
4. 退出登录已支持服务端 Token 撤销：`jti` 写入 Redis 黑名单后旧 Token 立即失效；修改密码会提升
   `token_version`，同样使此前所有 Token 失效。
5. 视频投稿依赖腾讯云 COS，临时文件数据库记录和 COS 对象都必须真实存在。
6. 视频详情和公开 Feed 只处理审核通过且未逻辑删除的视频。
7. 播放量和分享量暂未实现用户、IP 或时间窗口去重。
8. 互动统计经 Redis + RabbitMQ 异步聚合后才落库，读取到的统计值可能有短暂延迟。
9. 指定用户详情接口暂未根据当前访问者计算 `subscribed`。
10. 管理员分页接口的 `page` 当前为必填参数，`status` 的 Controller 默认值为 `1`。
11. 全局异常处理器会把未单独处理的异常包装为 `code = 500`，具体提示以当前实现为准。
12. 登录失败限流与上传凭证限流基于 Redis 固定窗口实现，触发时返回 `429`。
