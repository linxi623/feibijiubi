# 接口设计入门指南

本文档用于记录本项目后端接口设计的基本原则、技巧和推荐落地方式。项目当前目标是一个 mini-Bilibili 风格的后端应用。

## 1. 什么是接口

在 Spring Boot 后端项目中，前端通常不能直接调用 Java 方法，而是通过 HTTP 请求访问后端提供的接口。

例如用户注册功能，在 Java 中可能对应一个方法：

```java
public User register(String username, String password) {
    // ...
}
```

但前端真正调用的是一个 HTTP 接口：

```http
POST /api/users/register
```

前端发送 JSON：

```json
{
  "username": "zhangsan",
  "password": "123456",
  "nickname": "张三"
}
```

后端返回 JSON：

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "id": 1,
    "username": "zhangsan",
    "nickname": "张三"
  }
}
```

一句话理解：

> 接口就是前端和后端之间约定好的“请求地址、请求方式、请求参数、返回结果”。

## 2. 设计一个接口时要明确什么

一个接口至少要明确以下内容：

1. 请求方式：`GET`、`POST`、`PUT`、`DELETE`
2. 请求路径：例如 `/api/users/register`
3. 请求参数：前端需要传什么
4. 返回结果：后端返回什么
5. 错误情况：失败时返回什么

例如注册接口：

```http
POST /api/users/register
```

请求参数：

```json
{
  "username": "zhangsan",
  "password": "123456",
  "nickname": "张三"
}
```

成功返回：

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "id": 1,
    "username": "zhangsan",
    "nickname": "张三"
  }
}
```

失败返回：

```json
{
  "code": 400,
  "message": "用户名已存在",
  "data": null
}
```

## 3. 常见 HTTP 请求方式

### GET：查询数据

用于获取数据，不应该修改数据库。

示例：

```http
GET /api/videos?page=1&pageSize=10
GET /api/users/1
```

### POST：新增数据或执行业务操作

常用于新增数据，或者执行注册、登录、点赞等业务动作。

示例：

```http
POST /api/users/register
POST /api/videos
POST /api/comments
```

### PUT：修改数据

通常用于更新已有资源。

示例：

```http
PUT /api/users/1
```

请求体：

```json
{
  "nickname": "新昵称",
  "avatarUrl": "https://example.com/avatar.png"
}
```

### DELETE：删除数据

用于删除资源。

示例：

```http
DELETE /api/comments/10
DELETE /api/videos/3
```

## 4. 接口路径设计原则

推荐规则：

> 路径用名词表示资源，请求方式表示动作。

不推荐：

```http
GET /api/getUser
POST /api/addVideo
POST /api/deleteComment
```

推荐：

```http
GET    /api/users/1
POST   /api/videos
DELETE /api/comments/10
```

也就是说：

```text
/api/users       用户资源
/api/videos      视频资源
/api/comments    评论资源
```

通过 `GET`、`POST`、`PUT`、`DELETE` 表达“查、增、改、删”。

## 5. RESTful 风格的简单理解

RESTful API 可以简单理解为下面这种风格：

```http
GET    /api/users          查询用户列表
GET    /api/users/1        查询 id=1 的用户
POST   /api/users          新增用户
PUT    /api/users/1        修改 id=1 的用户
DELETE /api/users/1        删除 id=1 的用户
```

本项目不需要一开始追求完全标准，但可以尽量接近这种风格。

对于初学阶段，一些业务动作也可以写得更直观一些，例如：

```http
POST /api/users/register
POST /api/users/login
```

这比完全 RESTful 的写法更容易理解，也可以接受。

## 6. 请求参数应该放在哪里

常见参数位置有三种。

### 6.1 路径参数

用于定位某一个具体资源。

示例：

```http
GET /api/users/1
GET /api/videos/100
DELETE /api/comments/10
```

Spring Boot 示例：

```java
@GetMapping("/users/{id}")
public Result<UserVO> getUser(@PathVariable Long id) {
    // ...
}
```

### 6.2 查询参数

常用于分页、搜索、筛选。

示例：

```http
GET /api/videos?page=1&pageSize=10&keyword=java
```

Spring Boot 示例：

```java
@GetMapping("/videos")
public Result<PageResult<VideoVO>> listVideos(
        @RequestParam Integer page,
        @RequestParam Integer pageSize,
        @RequestParam(required = false) String keyword) {
    // ...
}
```

### 6.3 请求体 JSON

常用于新增或修改复杂数据。

示例：

```http
POST /api/users/register
```

请求体：

```json
{
  "username": "zhangsan",
  "password": "123456",
  "nickname": "张三"
}
```

Spring Boot 示例：

```java
@PostMapping("/users/register")
public Result<UserVO> register(@RequestBody UserRegisterRequest request) {
    // ...
}
```

### 6.4 判断技巧

可以按下面的规则判断参数放在哪里：

```text
如果是资源 id：放路径里
如果是分页、搜索、筛选条件：放 URL 查询参数里
如果是新增或修改的数据：放 JSON 请求体里
```

## 7. 接口返回格式要统一

建议本项目所有接口都返回统一格式：

```json
{
  "code": 200,
  "message": "success",
  "data": {}
}
```

成功示例：

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "id": 1,
    "username": "zhangsan",
    "nickname": "张三"
  }
}
```

失败示例：

```json
{
  "code": 400,
  "message": "用户名已存在",
  "data": null
}
```

后端可以设计一个通用返回类：

```java
public class Result<T> {
    private Integer code;
    private String message;
    private T data;
}
```

## 8. 状态码约定

初学阶段可以先使用简单约定：

| code | 含义 |
|---|---|
| 200 | 成功 |
| 400 | 请求参数错误或业务错误 |
| 401 | 未登录 |
| 403 | 没有权限 |
| 404 | 数据不存在 |
| 500 | 服务器内部错误 |

示例：

```json
{
  "code": 404,
  "message": "视频不存在",
  "data": null
}
```

## 9. 接口设计原则

### 9.1 一个接口只做一件事

不推荐：

```http
POST /api/users/registerAndLoginAndCreateProfile
```

推荐拆开：

```http
POST /api/users/register
POST /api/users/login
PUT  /api/users/{id}
```

### 9.2 接口路径要看得懂

推荐：

```http
/api/users
/api/videos
/api/comments
/api/favorites
```

不推荐：

```http
/api/doSomething
/api/data
/api/test
/api/handle
```

### 9.3 不要把数据库字段完全暴露给前端

数据库表中可能有：

```text
id
username
password
nickname
avatar_url
created_at
updated_at
deleted
```

但返回给前端时，不应该返回密码等敏感字段。

建议区分：

```text
Entity：数据库对象
DTO / Request：前端请求对象
VO / Response：后端响应对象
```

例如：

```text
User.java                  对应 user 表
UserRegisterRequest.java   注册请求参数
UserLoginRequest.java      登录请求参数
UserVO.java                返回给前端的用户信息
```

### 9.4 分页接口要从一开始就设计

视频列表、评论列表、收藏列表等不要一次性返回全部数据。

推荐：

```http
GET /api/videos?page=1&pageSize=10
GET /api/videos/{videoId}/comments?page=1&pageSize=20
GET /api/users/{userId}/favorites?page=1&pageSize=10
```

分页返回可以统一为：

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "total": 100,
    "list": []
  }
}
```

后端可以设计一个通用分页类：

```java
public class PageResult<T> {
    private Long total;
    private List<T> list;
}
```

### 9.5 新增和修改接口不要直接接收 Entity

不推荐：

```java
@PostMapping("/users/register")
public Result<User> register(@RequestBody User user) {
    // ...
}
```

推荐：

```java
@PostMapping("/users/register")
public Result<UserVO> register(@RequestBody UserRegisterRequest request) {
    // ...
}
```

原因是：

```text
Entity 是数据库结构
Request 是接口结构
它们不一定完全一样
```

### 9.6 错误信息要明确

不推荐：

```json
{
  "code": 400,
  "message": "error"
}
```

推荐：

```json
{
  "code": 400,
  "message": "用户名不能为空",
  "data": null
}
```

或者：

```json
{
  "code": 404,
  "message": "视频不存在",
  "data": null
}
```

## 10. mini-Bilibili 第一版接口建议

### 用户模块

```http
POST /api/users/register       用户注册
POST /api/users/login          用户登录
GET  /api/users/{id}           查询用户信息
PUT  /api/users/{id}           修改用户资料
```

### 视频模块

```http
POST   /api/videos             发布视频
GET    /api/videos             分页查询视频列表
GET    /api/videos/{id}        查询视频详情
PUT    /api/videos/{id}        修改视频信息
DELETE /api/videos/{id}        删除视频
```

分页查询示例：

```http
GET /api/videos?page=1&pageSize=10
```

带搜索条件：

```http
GET /api/videos?page=1&pageSize=10&keyword=java
```

### 评论模块

```http
POST   /api/videos/{videoId}/comments       发表评论
GET    /api/videos/{videoId}/comments       查询某个视频的评论
DELETE /api/comments/{id}                   删除评论
```

例如：

```http
POST /api/videos/100/comments
```

表示给 id 为 `100` 的视频发表评论。

### 点赞模块

推荐：

```http
POST   /api/videos/{videoId}/likes       点赞视频
DELETE /api/videos/{videoId}/likes       取消点赞
```

初学阶段也可以写成更直观的形式：

```http
POST /api/videos/{videoId}/like
POST /api/videos/{videoId}/unlike
```

但更推荐第一种。

### 收藏模块

```http
POST   /api/videos/{videoId}/favorites       收藏视频
DELETE /api/videos/{videoId}/favorites       取消收藏
GET    /api/users/{userId}/favorites         查询某个用户收藏的视频
```

## 11. 接口文档放在哪里

推荐将接口文档放在项目根目录下的：

```text
docs/api.md
```

也就是：

```text
backend/
├── docs/
│   └── api.md
├── database/
│   └── feibijiubi.sql
├── src/
├── pom.xml
└── CLAUDE.md
```

建议分工：

```text
database/  放建表 SQL
docs/      放说明文档
src/       放 Java 代码
```

目前项目刚开始，不建议把文档拆得太细。可以先使用：

```text
docs/api.md
```

如果以后文档多了，再拆成：

```text
docs/
├── api/
│   ├── user.md
│   ├── video.md
│   ├── comment.md
│   └── favorite.md
├── database/
│   └── schema.md
└── development.md
```

## 12. api.md 接口文档模板

后续正式写接口文档时，可以使用下面的模板。

```markdown
# 接口文档

## 通用返回格式

```json
{
  "code": 200,
  "message": "success",
  "data": {}
}
```

## 状态码约定

| code | 含义 |
|---|---|
| 200 | 成功 |
| 400 | 请求参数错误或业务错误 |
| 401 | 未登录 |
| 403 | 无权限 |
| 404 | 数据不存在 |
| 500 | 服务器内部错误 |

## 用户模块

### 用户注册

请求方式：

```http
POST /api/users/register
```

请求参数：

```json
{
  "username": "zhangsan",
  "password": "123456",
  "nickname": "张三"
}
```

响应示例：

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "id": 1,
    "username": "zhangsan",
    "nickname": "张三"
  }
}
```

失败示例：

```json
{
  "code": 400,
  "message": "用户名已存在",
  "data": null
}
```
```

## 13. 当前推荐开发顺序

建议项目初期按下面顺序推进：

```text
1. 新建并维护 docs/api.md
2. 先写通用返回格式和状态码约定
3. 先设计用户模块接口
4. 根据用户接口反推 user 表
5. 编写 User 实体类
6. 编写 Mapper
7. 编写 Service
8. 编写 Controller
9. 运行接口并测试
10. 再进入视频、评论、点赞、收藏等模块
```

整体顺序是：

```text
接口文档 → 数据库表 → Java 实体类 → Mapper → Service → Controller
```

## 14. 每次设计接口前可以问自己的问题

每设计一个接口前，先回答以下问题：

```text
1. 这个接口是给谁用的？
2. 它要完成什么业务动作？
3. 前端需要传哪些数据？
4. 后端需要返回哪些数据？
5. 哪些情况会失败？
6. 这个接口会不会修改数据库？
7. 是否需要分页？
8. 是否需要登录？
```

如果这些问题能回答清楚，这个接口基本就设计清楚了。