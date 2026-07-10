# 菲比啾比后端 API 测试文档

本文档根据当前后端代码整理，供 Postman、Apifox、curl 等工具测试使用。

## 1. 通用约定

### 1.1 基础地址

本地开发默认地址通常为：

```text
http://localhost:8080
```

如果你修改了 Spring Boot 端口，请以实际端口为准。

### 1.2 通用响应格式

当前项目所有 Controller 都使用 `ApiResponse<T>` 作为统一响应结构：

```json
{
  "code": 200,
  "message": "success",
  "data": null
}
```

字段说明：

| 字段 | 类型 | 说明 |
|---|---|---|
| code | number | 业务状态码。`200` 表示成功，其他值表示失败 |
| message | string | 响应提示信息 |
| data | object / string / null | 响应数据，无数据时为 `null` |

### 1.3 常见业务状态码

| code | 说明 |
|---|---|
| 200 | 成功 |
| 400 | 请求参数错误或业务规则不满足 |
| 401 | 未登录或登录状态异常 |
| 403 | 无权限或账号状态异常 |
| 500 | 服务器内部错误 |

### 1.4 登录鉴权

除注册、登录接口外，当前 `/api/**` 下的接口都会经过登录拦截器校验。

需要登录的接口必须在请求头携带：

```http
Authorization: Bearer <登录接口返回的 token>
```

如果未携带或格式错误，会返回：

```json
{
  "code": 401,
  "message": "请先登录",
  "data": null
}
```

### 1.5 当前接口清单

| 模块 | 方法 | 路径 | 是否需要登录 |
|---|---:|---|---|
| 账号 | POST | `/api/auth/register` | 否 |
| 账号 | POST | `/api/auth/login` | 否 |
| 用户 | GET | `/api/users/me` | 是 |
| 用户 | PUT | `/api/users/me` | 是 |
| 用户 | PUT | `/api/users/me/password` | 是 |
| 用户 | PUT | `/api/users/me/avatar` | 是 |
| 视频 | POST | `/api/videos/upload-url` | 是 |
| 视频 | POST | `/api/videos/cover` | 是 |
| 视频 | POST | `/api/videos` | 是 |
| 视频 | GET | `/api/videos/{vid}` | 可选登录 |
| 视频 | GET | `/api/videos/feed` | 是 |
| 用户视频 | POST | `/api/videos/{vid}/play-count` | 否 |
| 用户视频 | PUT | `/api/videos/{vid}/progress` | 是 |

---

## 2. 账号接口

### 2.1 用户注册

#### 基本信息

| 项 | 内容 |
|---|---|
| 请求方法 | `POST` |
| 请求路径 | `/api/auth/register` |
| Content-Type | `application/json` |
| 是否需要登录 | 否 |
| 说明 | 注册一个新用户 |

#### 请求体

```json
{
  "username": "linxi",
  "password": "123456",
  "confirmedPassword": "123456"
}
```

#### 请求字段说明

| 字段 | 类型 | 是否必填 | 说明 |
|---|---|---|---|
| username | string | 是 | 用户名，不能为空，不能重复 |
| password | string | 是 | 密码，不能为空 |
| confirmedPassword | string | 是 | 确认密码，必须与 `password` 一致 |

#### 成功响应

```json
{
  "code": 200,
  "message": "恭喜你成功注册F站",
  "data": null
}
```

#### 常见失败响应

用户名已存在：

```json
{
  "code": 400,
  "message": "用户名已存在",
  "data": null
}
```

两次密码不一致：

```json
{
  "code": 400,
  "message": "前后两次密码输入不一致",
  "data": null
}
```

#### curl 示例

```bash
curl -X POST "http://localhost:8080/api/auth/register" \
  -H "Content-Type: application/json" \
  -d '{"username":"linxi","password":"123456","confirmedPassword":"123456"}'
```

---

### 2.2 用户登录

#### 基本信息

| 项 | 内容 |
|---|---|
| 请求方法 | `POST` |
| 请求路径 | `/api/auth/login` |
| Content-Type | `application/json` |
| 是否需要登录 | 否 |
| 说明 | 用户登录，成功后返回 token 和当前用户信息 |

#### 请求体

```json
{
  "username": "linxi",
  "password": "123456"
}
```

#### 请求字段说明

| 字段 | 类型 | 是否必填 | 说明 |
|---|---|---|---|
| username | string | 是 | 用户名 |
| password | string | 是 | 密码 |

#### 成功响应

```json
{
  "code": 200,
  "message": "登录成功",
  "data": {
    "token": "eyJhbGciOi...",
    "user": {
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
      "createdAt": "2026-07-09T10:00:00"
    }
  }
}
```

> 注意：示例中的 token 和时间只是示例，实际值以接口返回为准。

#### 常见失败响应

```json
{
  "code": 400,
  "message": "用户名或密码错误",
  "data": null
}
```

```json
{
  "code": 403,
  "message": "账号状态异常，无法登录",
  "data": null
}
```

#### curl 示例

```bash
curl -X POST "http://localhost:8080/api/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"username":"linxi","password":"123456"}'
```

---

## 3. 用户接口

### 3.1 获取当前登录用户信息

#### 基本信息

| 项 | 内容 |
|---|---|
| 请求方法 | `GET` |
| 请求路径 | `/api/users/me` |
| 是否需要登录 | 是 |
| 说明 | 查询当前 token 对应的用户信息 |

#### 请求头

```http
Authorization: Bearer <token>
```

#### 成功响应

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
    "createdAt": "2026-07-09T10:00:00"
  }
}
```

#### curl 示例

```bash
curl -X GET "http://localhost:8080/api/users/me" \
  -H "Authorization: Bearer <token>"
```

---

### 3.2 修改当前用户资料

#### 基本信息

| 项 | 内容 |
|---|---|
| 请求方法 | `PUT` |
| 请求路径 | `/api/users/me` |
| Content-Type | `application/json` |
| 是否需要登录 | 是 |
| 说明 | 修改当前用户的昵称、性别、个人简介 |

#### 请求头

```http
Authorization: Bearer <token>
```

#### 请求体

```json
{
  "nickname": "新的昵称",
  "gender": 1,
  "description": "这是我的个人简介"
}
```

#### 请求字段说明

| 字段 | 类型 | 是否必填 | 说明 |
|---|---|---|---|
| nickname | string | 否 | 昵称 |
| gender | number | 否 | 性别。数据库约定：`0` 女，`1` 男，`2` 未知 |
| description | string | 否 | 个人简介 |

#### 成功响应

```json
{
  "code": 200,
  "message": "修改成功",
  "data": null
}
```

#### curl 示例

```bash
curl -X PUT "http://localhost:8080/api/users/me" \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"nickname":"新的昵称","gender":1,"description":"这是我的个人简介"}'
```

---

### 3.3 修改当前用户密码

#### 基本信息

| 项 | 内容 |
|---|---|
| 请求方法 | `PUT` |
| 请求路径 | `/api/users/me/password` |
| Content-Type | `application/json` |
| 是否需要登录 | 是 |
| 说明 | 修改当前用户密码 |

#### 请求头

```http
Authorization: Bearer <token>
```

#### 请求体

```json
{
  "oldPassword": "123456",
  "newPassword": "654321",
  "confirmedPassword": "654321"
}
```

#### 请求字段说明

| 字段 | 类型 | 是否必填 | 说明 |
|---|---|---|---|
| oldPassword | string | 是 | 旧密码 |
| newPassword | string | 是 | 新密码，不能与旧密码一致 |
| confirmedPassword | string | 是 | 确认新密码，必须与 `newPassword` 一致 |

#### 成功响应

```json
{
  "code": 200,
  "message": "修改成功",
  "data": null
}
```

#### 常见失败响应

```json
{
  "code": 400,
  "message": "旧密码错误",
  "data": null
}
```

```json
{
  "code": 400,
  "message": "两次密码不一致",
  "data": null
}
```

#### curl 示例

```bash
curl -X PUT "http://localhost:8080/api/users/me/password" \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"oldPassword":"123456","newPassword":"654321","confirmedPassword":"654321"}'
```

---

### 3.4 修改当前用户头像

#### 基本信息

| 项 | 内容 |
|---|---|
| 请求方法 | `PUT` |
| 请求路径 | `/api/users/me/avatar` |
| Content-Type | `multipart/form-data` |
| 是否需要登录 | 是 |
| 说明 | 上传头像图片，并更新当前用户头像 |

#### 请求头

```http
Authorization: Bearer <token>
```

#### 表单参数

| 字段 | 类型 | 是否必填 | 说明 |
|---|---|---|---|
| file | file | 是 | 头像图片文件 |

#### 文件限制

| 限制项 | 当前约定 |
|---|---|
| 最大大小 | 2MB |
| 支持类型 | `image/jpg`、`image/jpeg`、`image/png` |

#### 成功响应

```json
{
  "code": 200,
  "message": "修改成功",
  "data": {
    "id": 1,
    "username": "linxi",
    "nickname": "新的昵称",
    "avatarUrl": "https://example-cos-domain/avatar/1/20260709/xxx.png",
    "backgroundUrl": null,
    "gender": 1,
    "description": "这是我的个人简介",
    "experience": 0,
    "coin": 0,
    "vip": 0,
    "status": 0,
    "role": 0,
    "auth": 0,
    "authMsg": null,
    "createdAt": "2026-07-09T10:00:00"
  }
}
```

#### curl 示例

```bash
curl -X PUT "http://localhost:8080/api/users/me/avatar" \
  -H "Authorization: Bearer <token>" \
  -F "file=@/path/to/avatar.png"
```

---

## 4. 视频接口

### 4.1 获取视频直传临时密钥

#### 基本信息

| 项 | 内容 |
|---|---|
| 请求方法 | `POST` |
| 请求路径 | `/api/videos/upload-url` |
| Content-Type | `application/json` |
| 是否需要登录 | 是 |
| 说明 | 后端生成视频临时 object key，并返回腾讯云 COS 临时上传凭证。前端拿到凭证后直接上传视频到 COS |

#### 请求头

```http
Authorization: Bearer <token>
```

#### 请求体

```json
{
  "fileName": "demo.mp4",
  "contentType": "video/mp4",
  "fileSize": 10485760
}
```

#### 请求字段说明

| 字段 | 类型 | 是否必填 | 说明 |
|---|---|---|---|
| fileName | string | 是 | 原始文件名，必须包含后缀 |
| contentType | string | 是 | 视频 MIME 类型 |
| fileSize | number | 是 | 文件大小，单位字节 |

#### 文件限制

| 限制项 | 当前约定 |
|---|---|
| 最大大小 | 2GB |
| 支持类型 | `video/mp4`、`video/3gp`、`video/mpeg` |

#### 成功响应

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "tempKey": "temp/videos/1/20260709/xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx.mp4",
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

> 测试投稿时需要保存 `data.tempKey`，后续调用投稿接口时传给 `tempVideoKey`。

#### 常见失败响应

```json
{
  "code": 400,
  "message": "该视频文件格式不支持",
  "data": null
}
```

```json
{
  "code": 400,
  "message": "该文件超出限制",
  "data": null
}
```

#### curl 示例

```bash
curl -X POST "http://localhost:8080/api/videos/upload-url" \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"fileName":"demo.mp4","contentType":"video/mp4","fileSize":10485760}'
```

---

### 4.2 上传视频封面

#### 基本信息

| 项 | 内容 |
|---|---|
| 请求方法 | `POST` |
| 请求路径 | `/api/videos/cover` |
| Content-Type | `multipart/form-data` |
| 是否需要登录 | 是 |
| 说明 | 上传视频封面图片到临时目录，返回封面 URL |

#### 请求头

```http
Authorization: Bearer <token>
```

#### 表单参数

| 字段 | 类型 | 是否必填 | 说明 |
|---|---|---|---|
| file | file | 是 | 封面图片文件 |

#### 文件限制

| 限制项 | 当前约定 |
|---|---|
| 最大大小 | 2MB |
| 支持类型 | `image/jpg`、`image/jpeg`、`image/png` |

#### 成功响应

```json
{
  "code": 200,
  "message": "success",
  "data": "https://example-cos-domain/temp/covers/1/20260709/xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx.png"
}
```

> 投稿接口需要传的是 `tempCoverKey`，不是完整 URL。当前后端的临时封面 key 可以通过去掉 COS `base-url` 前缀得到，例如：
>
> 完整 URL：`https://example-cos-domain/temp/covers/1/20260709/xxx.png`
> tempCoverKey：`temp/covers/1/20260709/xxx.png`

#### curl 示例

```bash
curl -X POST "http://localhost:8080/api/videos/cover" \
  -H "Authorization: Bearer <token>" \
  -F "file=@/path/to/cover.png"
```

---

### 4.3 投稿视频

#### 基本信息

| 项 | 内容 |
|---|---|
| 请求方法 | `POST` |
| 请求路径 | `/api/videos` |
| Content-Type | `application/json` |
| 是否需要登录 | 是 |
| 说明 | 将已上传到临时目录的视频和封面提交为正式视频 |

#### 请求头

```http
Authorization: Bearer <token>
```

#### 请求体

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
  "tempCoverKey": "temp/covers/1/20260709/xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx.png",
  "tempVideoKey": "temp/videos/1/20260709/xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx.mp4"
}
```

#### 请求字段说明

| 字段 | 类型 | 是否必填 | 说明 |
|---|---|---|---|
| title | string | 是 | 视频标题 |
| sourceType | number | 否 | 来源类型。数据库约定：`1` 自制，`2` 转载 |
| visibility | number | 否 | 可见性。数据库约定：`0` 公开，`1` 私密 |
| duration | number | 是 | 视频时长，单位秒，必须大于 0 |
| mcId | string | 是 | 主分区 ID |
| scId | string | 是 | 子分区 ID |
| tags | string | 否 | 标签，建议用逗号分隔 |
| description | string | 否 | 视频简介 |
| tempCoverKey | string | 是 | 临时封面对象 key，必须属于当前用户 |
| tempVideoKey | string | 是 | 临时视频对象 key，必须属于当前用户 |

#### 成功响应

```json
{
  "code": 200,
  "message": "投稿成功",
  "data": {
    "vid": 1,
    "title": "我的第一个视频",
    "coverUrl": "https://example-cos-domain/covers/1/20260709/xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx.png",
    "videoUrl": "https://example-cos-domain/videos/1/20260709/xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx.mp4",
    "status": 0
  }
}
```

#### 视频状态说明

| status | 说明 |
|---|---|
| 0 | 审核中 |
| 1 | 通过审核 |
| 2 | 打回整改 |
| 3 | 违规删除 |

#### 常见失败响应

```json
{
  "code": 400,
  "message": "标题不能为空",
  "data": null
}
```

```json
{
  "code": 400,
  "message": "视频临时文件记录不存在",
  "data": null
}
```

```json
{
  "code": 400,
  "message": "封面临时文件路径不合法",
  "data": null
}
```

#### curl 示例

```bash
curl -X POST "http://localhost:8080/api/videos" \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"title":"我的第一个视频","sourceType":1,"visibility":0,"duration":120.5,"mcId":"douga","scId":"mad","tags":"测试,菲比啾比,投稿","description":"这是我的第一个投稿视频","tempCoverKey":"temp/covers/1/20260709/xxx.png","tempVideoKey":"temp/videos/1/20260709/xxx.mp4"}'
```

---

### 4.4 增加播放量

#### 基本信息

| 项 | 内容 |
|---|---|
| 请求方法 | `POST` |
| 请求路径 | `/api/videos/{vid}/play-count` |
| 是否需要登录 | 否 |
| 负责模块 | `UserVideo` 用户—视频关系模块 |
| 说明 | 将指定视频的总播放量增加一次，不保存用户播放进度 |

前端当前约定在用户关闭、离开或切换播放器时调用一次。游客和登录用户都可以调用。

#### 成功响应

```json
{
  "code": 200,
  "message": "操作成功",
  "data": null
}
```

#### curl 示例

```bash
curl -X POST "http://localhost:8080/api/videos/1/play-count"
```

> 当前是 MVP 计数方案，服务端尚未实现 IP、用户或时间窗口去重，重复请求仍会重复增加播放量。

---

### 4.5 保存播放进度

#### 基本信息

| 项 | 内容 |
|---|---|
| 请求方法 | `PUT` |
| 请求路径 | `/api/videos/{vid}/progress` |
| Content-Type | 无请求体，使用查询参数 |
| 是否需要登录 | 是 |
| 负责模块 | `UserVideo` 用户—视频关系模块 |
| 说明 | 只保存当前登录用户的播放进度，不增加视频播放量 |

#### 请求参数

| 参数 | 位置 | 类型 | 是否必填 | 说明 |
|---|---|---|---|---|
| vid | 路径 | number | 是 | 视频 ID，必须存在 |
| playTime | Query | number | 是 | 当前播放进度，单位秒；不能小于 0 或超过视频总时长 |

#### 成功响应

```json
{
  "code": 200,
  "message": "操作成功",
  "data": null
}
```

成功后，后端会创建或复用当前用户与视频的 `user_video` 关系记录，并更新 `play_time` 和 `played_at`。这个接口不会修改 `video_status.play_times`。

#### 常见失败响应

```json
{
  "code": 401,
  "message": "请先登录",
  "data": null
}
```

```json
{
  "code": 400,
  "message": "播放进度不能超过视频时长",
  "data": null
}
```

#### curl 示例

```bash
curl -X PUT "http://localhost:8080/api/videos/1/progress?playTime=35.5" \
  -H "Authorization: Bearer <token>"
```

#### 前端调用建议

- 游客关闭或离开播放器：调用一次 `POST /play-count`。
- 登录用户关闭或离开播放器：调用一次 `POST /play-count`，再调用一次 `PUT /progress` 保存进度。
- 如果以后改成定时保存进度，可以重复调用 `PUT /progress`；它不会导致播放量重复增加。

---

## 5. 推荐测试顺序

### 5.1 用户基础功能测试

1. 调用 `POST /api/auth/register` 注册用户。
2. 调用 `POST /api/auth/login` 登录用户。
3. 复制登录响应中的 `data.token`。
4. 后续需要登录的接口都添加请求头：

```http
Authorization: Bearer <token>
```

5. 调用 `GET /api/users/me` 验证 token 是否可用。
6. 调用 `PUT /api/users/me` 修改资料。
7. 调用 `PUT /api/users/me/password` 修改密码。
8. 使用新密码重新调用登录接口。

### 5.2 视频投稿流程测试

1. 登录并获取 token。
2. 调用 `POST /api/videos/upload-url` 获取视频临时上传凭证和 `tempKey`。
3. 前端或测试工具使用 COS 临时凭证把视频上传到 `tempKey` 对应位置。
4. 调用 `POST /api/videos/cover` 上传封面，得到封面 URL。
5. 从封面 URL 中取出临时封面 key，作为 `tempCoverKey`。
6. 调用 `POST /api/videos` 投稿：
   - `tempVideoKey` 使用第 2 步返回的 `data.tempKey`。
   - `tempCoverKey` 使用第 5 步得到的封面 key。
7. 成功后返回正式视频 `vid`、`coverUrl`、`videoUrl` 和审核状态。

---

## 6. 测试注意事项

1. 当前密码逻辑仍是明文比对，适合学习阶段测试；正式项目需要使用 BCrypt 等方式保存密码摘要。
2. 当前返回的用户角色 `role` 是数字：`0` 普通用户，`1` 管理员，`2` 超级管理员。
3. 当前用户状态 `status` 是数字：`0` 正常，`1` 封禁，`2` 注销。
4. 视频投稿依赖腾讯云 COS 配置，测试视频接口前要确认 COS 配置可用。
5. `POST /api/videos` 会校验 COS 中临时视频和临时封面对象是否真实存在，所以只传数据库记录但没有实际上传文件会投稿失败。
6. 全局异常处理会把未处理异常包装成：`code = 500`，`message = "服务器内部错误" + 异常信息`。
