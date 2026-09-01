# Spring Boot 后端分层开发指南：Controller、Service、Mapper

这份笔记用于记录在当前 `feibijiubi` 后端项目中，创建实体类之后，如何规范地继续创建 `Controller`、`Service`、`Mapper`、`DTO`、`VO` 等代码结构。

目标不是“能跑就行”，而是建立清晰、可维护、可扩展的后端分层思维。

---

## 1. 标准后端分层结构

推荐目录结构：

```text
src/main/java/com/feibijiubi/backend
├── BackendApplication.java
├── common
│   ├── ApiResponse.java
│   ├── BusinessException.java
│   └── GlobalExceptionHandler.java
├── controller
│   └── UserController.java
├── dto
│   └── UserRegisterRequest.java
├── entity
│   └── User.java
├── mapper
│   └── UserMapper.java
├── service
│   ├── UserService.java
│   └── impl
│       └── UserServiceImpl.java
└── vo
    └── UserVO.java
```

各层职责：

| 层 | 作用 |
|---|---|
| `controller` | 接收 HTTP 请求，返回响应 |
| `service` | 处理业务逻辑 |
| `mapper` | 操作数据库 |
| `entity` | 和数据库表对应的 Java 对象 |
| `dto` | 接收前端请求参数 |
| `vo` | 返回给前端的数据对象 |
| `common` | 通用响应、异常、工具类等 |

---

## 2. 核心调用链

以前端注册用户为例：

```text
前端 POST /api/users
        ↓
UserController.register()
        ↓
UserService.register()
        ↓
UserMapper.selectByUsername()
        ↓
UserMapper.insert()
        ↓
数据库 users 表
```

分层原则：

```text
Controller 不直接操作数据库
Service 不关心 HTTP 细节
Mapper 不写业务判断
Entity 不直接暴露给前端
```

---

## 3. Entity：数据库实体类

`Entity` 用来对应数据库表。

如果数据库字段为：

```sql
id
username
password_hash
nickname
avatar_url
background_url
gender
description
experience
coin
vip
status
role
auth
auth_msg
created_at
updated_at
deleted_at
```

对应实体类可以写成：

```java
package com.feibijiubi.backend.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class User {
    private Long id;
    private String username;
    private String passwordHash;
    private String nickname;
    private String avatarUrl;
    private String backgroundUrl;
    private Integer gender;
    private String description;
    private Integer experience;
    private Integer coin;
    private Integer vip;
    private Integer status;
    private Integer role;
    private Integer auth;
    private String authMsg;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime deletedAt;
}
```

常见类型对应关系：

| MySQL 类型 | Java 类型 |
|---|---|
| `BIGINT` | `Long` |
| `INT` | `Integer` |
| `TINYINT` | `Integer` 或 `Byte` |
| `VARCHAR` | `String` |
| `DATETIME` | `LocalDateTime` |

注意：

- 数据库字段推荐用下划线：`password_hash`。
- Java 字段推荐用驼峰：`passwordHash`。
- `DATETIME` 推荐用 `LocalDateTime`，不要用 `java.sql.Date`。

---

## 4. DTO：接收前端请求参数

不要直接用 `User` 接收前端参数。

原因是：注册接口只需要前端传：

```json
{
  "username": "linxi",
  "password": "123456",
  "nickname": "林夕"
}
```

但 `User` 实体类中有很多不应该由前端控制的字段，例如：

```text
id
role
status
coin
vip
auth
createdAt
updatedAt
```

所以应该创建 DTO。

路径：

```text
src/main/java/com/feibijiubi/backend/dto/UserRegisterRequest.java
```

示例：

```java
package com.feibijiubi.backend.dto;

import lombok.Data;

@Data
public class UserRegisterRequest {
    private String username;
    private String password;
    private String nickname;
}
```

DTO 的作用：

> 专门接收前端请求参数。

常见 DTO 命名：

```text
UserRegisterRequest
UserLoginRequest
UserUpdateProfileRequest
UserChangePasswordRequest
```

---

## 5. VO：返回给前端的数据

不要直接把 `User` 实体类返回给前端。

原因是 `User` 中可能包含敏感字段：

```java
private String passwordHash;
```

密码哈希不应该返回给前端。

路径：

```text
src/main/java/com/feibijiubi/backend/vo/UserVO.java
```

示例：

```java
package com.feibijiubi.backend.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class UserVO {
    private Long id;
    private String username;
    private String nickname;
    private String avatarUrl;
    private String backgroundUrl;
    private Integer gender;
    private String description;
    private Integer experience;
    private Integer coin;
    private Integer vip;
    private Integer status;
    private Integer role;
    private Integer auth;
    private String authMsg;
    private LocalDateTime createdAt;
}
```

VO 的作用：

> 专门控制返回给前端的数据结构。

---

## 6. Common：统一响应类 ApiResponse

接口文档约定统一响应格式：

```json
{
  "code": 200,
  "message": "success",
  "data": null
}
```

路径：

```text
src/main/java/com/feibijiubi/backend/common/ApiResponse.java
```

示例：

```java
package com.feibijiubi.backend.common;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ApiResponse<T> {
    private Integer code;
    private String message;
    private T data;

    public static <T> ApiResponse<T> success(String message, T data) {
        return new ApiResponse<>(200, message, data);
    }

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(200, "success", data);
    }

    public static <T> ApiResponse<T> successMessage(String message) {
        return new ApiResponse<>(200, message, null);
    }

    public static <T> ApiResponse<T> fail(Integer code, String message) {
        return new ApiResponse<>(code, message, null);
    }
}
```

以后 Controller 统一返回 `ApiResponse`。

---

## 7. Mapper：数据库访问层

当前项目使用的是普通 MyBatis，可以先使用注解 SQL，简单直观。

路径：

```text
src/main/java/com/feibijiubi/backend/mapper/UserMapper.java
```

示例：

```java
package com.feibijiubi.backend.mapper;

import com.feibijiubi.backend.entity.User;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserMapper {

    @Select("""
            SELECT
                id,
                username,
                password_hash AS passwordHash,
                nickname,
                avatar_url AS avatarUrl,
                background_url AS backgroundUrl,
                gender,
                description,
                experience,
                coin,
                vip,
                status,
                role,
                auth,
                auth_msg AS authMsg,
                created_at AS createdAt,
                updated_at AS updatedAt,
                deleted_at AS deletedAt
            FROM users
            WHERE username = #{username}
            """)
    User selectByUsername(String username);

    @Select("""
            SELECT
                id,
                username,
                password_hash AS passwordHash,
                nickname,
                avatar_url AS avatarUrl,
                background_url AS backgroundUrl,
                gender,
                description,
                experience,
                coin,
                vip,
                status,
                role,
                auth,
                auth_msg AS authMsg,
                created_at AS createdAt,
                updated_at AS updatedAt,
                deleted_at AS deletedAt
            FROM users
            WHERE id = #{id}
            """)
    User selectById(Long id);

    @Insert("""
            INSERT INTO users (
                username,
                password_hash,
                nickname,
                avatar_url,
                background_url,
                gender,
                description,
                experience,
                coin,
                vip,
                status,
                role,
                auth,
                auth_msg
            ) VALUES (
                #{username},
                #{passwordHash},
                #{nickname},
                #{avatarUrl},
                #{backgroundUrl},
                #{gender},
                #{description},
                #{experience},
                #{coin},
                #{vip},
                #{status},
                #{role},
                #{auth},
                #{authMsg}
            )
            """)
    int insert(User user);
}
```

### Mapper 原理

`Mapper` 本质上是一个接口。

```java
@Mapper
public interface UserMapper {
}
```

Spring Boot 启动时，MyBatis 会扫描这个接口，并为它生成代理对象。

当调用：

```java
userMapper.selectByUsername("linxi");
```

MyBatis 实际会执行对应 SQL：

```sql
SELECT ... FROM users WHERE username = 'linxi';
```

然后把查询结果封装成 `User` 对象。

---

## 8. Service：业务接口

Service 是业务逻辑层。

路径：

```text
src/main/java/com/feibijiubi/backend/service/UserService.java
```

示例：

```java
package com.feibijiubi.backend.service;

import com.feibijiubi.backend.dto.UserRegisterRequest;

public interface UserService {
    void register(UserRegisterRequest request);
}
```

为什么要有接口？

```text
UserService 接口
UserServiceImpl 实现类
```

好处：

- 结构清晰；
- 方便后期扩展；
- 方便单元测试；
- 符合 Spring 常见开发习惯。

---

## 9. ServiceImpl：业务实现类

路径：

```text
src/main/java/com/feibijiubi/backend/service/impl/UserServiceImpl.java
```

先给一个基础示例：

```java
package com.feibijiubi.backend.service.impl;

import com.feibijiubi.backend.dto.UserRegisterRequest;
import com.feibijiubi.backend.entity.User;
import com.feibijiubi.backend.mapper.UserMapper;
import com.feibijiubi.backend.service.user.UserAccountService;
import com.feibijiubi.backend.service.user.UserService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class UserServiceImpl implements UserAccountService {

    private final UserMapper userMapper;

    public UserServiceImpl(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    @Override
    public void register(UserRegisterRequest request) {
        if (request == null) {
            throw new RuntimeException("请求参数不能为空");
        }

        if (!StringUtils.hasText(request.getUsername())) {
            throw new RuntimeException("用户名不能为空");
        }

        if (!StringUtils.hasText(request.getPassword())) {
            throw new RuntimeException("密码不能为空");
        }

        User existUser = userMapper.selectByUsername(request.getUsername());
        if (existUser != null) {
            throw new RuntimeException("用户名已存在");
        }

        User user = new User();
        user.setUsername(request.getUsername());

        // 示例写法：真实项目不能明文存密码，后续应改成 BCrypt 加密
        user.setPasswordHash(request.getPassword());

        if (StringUtils.hasText(request.getNickname())) {
            user.setNickname(request.getNickname());
        } else {
            user.setNickname(request.getUsername());
        }

        user.setAvatarUrl(null);
        user.setBackgroundUrl(null);
        user.setGender(2);
        user.setDescription(null);
        user.setExperience(0);
        user.setCoin(0);
        user.setVip(0);
        user.setStatus(1);
        user.setRole(0);
        user.setAuth(0);
        user.setAuthMsg(null);

        userMapper.insert(user);
    }
}
```

### Service 的原理

Service 负责业务判断，比如：

```text
用户名不能为空
密码不能为空
用户名不能重复
昵称为空时默认使用用户名
新用户默认普通用户
新用户默认正常状态
```

这些逻辑都应该写在 Service 中。

不要写在 Mapper 中，因为 Mapper 只负责数据库操作。

也不要全部写在 Controller 中，否则 Controller 会越来越臃肿。

---

## 10. Controller：接口层

路径：

```text
src/main/java/com/feibijiubi/backend/controller/UserController.java
```

示例：

```java
package com.feibijiubi.backend.controller;

import com.feibijiubi.backend.common.ApiResponse;
import com.feibijiubi.backend.dto.UserRegisterRequest;
import com.feibijiubi.backend.service.user.UserAccountService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserAccountService userService;

    public UserController(com.feibijiubi.backend.service.user.UserAccountService userService) {
        this.userService = userService;
    }

    @PostMapping
    public ApiResponse<Void> register(@RequestBody UserRegisterRequest request) {
        userService.register(request);
        return ApiResponse.successMessage("注册成功");
    }
}
```

### Controller 原理

#### `@RestController`

```java
@RestController
```

表示这是一个接口控制器。

它相当于：

```java
@Controller
@ResponseBody
```

方法返回的 Java 对象会自动转换成 JSON 返回给前端。

#### `@RequestMapping("/api/users")`

表示这个 Controller 下所有接口都以 `/api/users` 开头。

#### `@PostMapping`

```java
@PostMapping
```

结合类上的：

```java
@RequestMapping("/api/users")
```

最终完整接口为：

```http
POST /api/users
```

#### `@RequestBody`

```java
@RequestBody UserRegisterRequest request
```

表示从 HTTP 请求体 JSON 中读取参数，并自动转换为 Java 对象。

前端请求：

```json
{
  "username": "linxi",
  "password": "123456",
  "nickname": "林夕"
}
```

Spring 会自动封装成：

```java
UserRegisterRequest
```

---

## 11. 完整注册接口执行流程

```text
1. 前端发送 POST /api/users
   请求体：
   {
     "username": "linxi",
     "password": "123456",
     "nickname": "林夕"
   }

2. UserController.register() 接收请求

3. Controller 调用 userService.register(request)

4. UserServiceImpl 校验参数

5. UserServiceImpl 调用 userMapper.selectByUsername(username)

6. Mapper 查询数据库，判断用户名是否存在

7. 如果不存在，Service 组装 User 对象

8. Service 调用 userMapper.insert(user)

9. Mapper 插入 users 表

10. Controller 返回：
    {
      "code": 200,
      "message": "注册成功",
      "data": null
    }
```

---

## 12. 为什么不要直接在 Controller 里写所有代码

不推荐这样写：

```java
@PostMapping
public ApiResponse<Void> register(@RequestBody UserRegisterRequest request) {
    // 参数校验
    // 查数据库
    // 插数据库
    // 返回结果
}
```

因为 Controller 会变成：

```text
Controller
├── 接收请求
├── 参数校验
├── 业务判断
├── 数据库查询
├── 数据库插入
├── 密码加密
├── token 生成
├── 异常处理
└── 返回数据
```

接口多了之后，代码会非常混乱。

规范写法是：

```text
Controller：只负责 HTTP 请求和响应
Service：只负责业务逻辑
Mapper：只负责 SQL 和数据库访问
```

---

## 13. 更规范：统一业务异常

基础示例里用了：

```java
throw new RuntimeException("用户名不能为空");
```

但真实项目中更推荐自定义业务异常。

推荐目录：

```text
common
├── ApiResponse.java
├── BusinessException.java
└── GlobalExceptionHandler.java
```

---

### 13.1 BusinessException

路径：

```text
src/main/java/com/feibijiubi/backend/common/BusinessException.java
```

代码：

```java
package com.feibijiubi.backend.common;

public class BusinessException extends RuntimeException {
    private final Integer code;

    public BusinessException(Integer code, String message) {
        super(message);
        this.code = code;
    }

    public Integer getCode() {
        return code;
    }
}
```

---

### 13.2 GlobalExceptionHandler

路径：

```text
src/main/java/com/feibijiubi/backend/common/GlobalExceptionHandler.java
```

代码：

```java
package com.feibijiubi.backend.common;

import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ApiResponse<Void> handleBusinessException(BusinessException e) {
        return ApiResponse.fail(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ApiResponse<Void> handleException(Exception e) {
        return ApiResponse.fail(500, "服务器内部错误");
    }
}
```

---

### 13.3 使用 BusinessException 的 ServiceImpl

```java
package com.feibijiubi.backend.service.impl;

import com.feibijiubi.backend.common.BusinessException;
import com.feibijiubi.backend.dto.UserRegisterRequest;
import com.feibijiubi.backend.entity.User;
import com.feibijiubi.backend.mapper.UserMapper;
import com.feibijiubi.backend.service.user.UserAccountService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class UserServiceImpl implements UserAccountService {

    private final UserMapper userMapper;

    public UserServiceImpl(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    @Override
    public void register(UserRegisterRequest request) {
        if (request == null) {
            throw new BusinessException(400, "请求参数不能为空");
        }

        if (!StringUtils.hasText(request.getUsername())) {
            throw new BusinessException(400, "用户名不能为空");
        }

        if (!StringUtils.hasText(request.getPassword())) {
            throw new BusinessException(400, "密码不能为空");
        }

        User existUser = userMapper.selectByUsername(request.getUsername());
        if (existUser != null) {
            throw new BusinessException(400, "用户名已存在");
        }

        User user = new User();
        user.setUsername(request.getUsername());

        // 后续建议改成 BCrypt 加密
        user.setPasswordHash(request.getPassword());

        if (StringUtils.hasText(request.getNickname())) {
            user.setNickname(request.getNickname());
        } else {
            user.setNickname(request.getUsername());
        }

        user.setGender(2);
        user.setExperience(0);
        user.setCoin(0);
        user.setVip(0);
        user.setStatus(1);
        user.setRole(0);
        user.setAuth(0);

        userMapper.insert(user);
    }
}
```

这样 Service 抛出：

```java
throw new BusinessException(400, "用户名不能为空");
```

最终前端收到：

```json
{
  "code": 400,
  "message": "用户名不能为空",
  "data": null
}
```

---

## 14. 数据库字段和 Java 字段映射

数据库字段一般使用下划线：

```sql
password_hash
avatar_url
created_at
```

Java 字段一般使用驼峰：

```java
passwordHash
avatarUrl
createdAt
```

可以在 SQL 中使用别名映射：

```sql
password_hash AS passwordHash,
avatar_url AS avatarUrl,
created_at AS createdAt
```

也可以在 `application.properties` 中开启 MyBatis 下划线转驼峰：

```properties
mybatis.configuration.map-underscore-to-camel-case=true
```

开启后，MyBatis 会自动映射：

```text
password_hash -> passwordHash
avatar_url    -> avatarUrl
created_at    -> createdAt
```

---

## 15. 一个成熟后端应该遵守的规范

### 15.1 Controller 不写业务

好的 Controller：

```java
@PostMapping
public ApiResponse<Void> register(@RequestBody UserRegisterRequest request) {
    userService.register(request);
    return ApiResponse.successMessage("注册成功");
}
```

不好的 Controller：

```java
@PostMapping
public ApiResponse<Void> register(@RequestBody UserRegisterRequest request) {
    // 一堆 if
    // 一堆 SQL
    // 一堆业务逻辑
}
```

---

### 15.2 Service 负责业务流程

例如注册用户：

```text
校验参数
判断用户名是否重复
设置默认昵称
设置默认角色
设置默认状态
调用 Mapper 插入数据库
```

这些都属于 Service。

---

### 15.3 Mapper 只操作数据库

Mapper 只写数据库操作：

```text
selectByUsername
selectById
insert
update
delete
```

不要在 Mapper 中判断：

```text
这个用户能不能注册
这个用户有没有权限
这个用户是不是管理员
```

这些是 Service 的职责。

---

### 15.4 Entity 不直接暴露给前端

不要直接返回 `User`，因为里面可能有：

```java
passwordHash
```

应该返回 `UserVO`。

---

### 15.5 请求参数使用 DTO

前端传什么，就建对应的 DTO。

例如：

```text
UserRegisterRequest
UserLoginRequest
UserUpdateProfileRequest
UserChangePasswordRequest
```

不要所有接口都用 `User` 接收。

---

### 15.6 异常统一处理

不要每个 Controller 都写：

```java
try {
    ...
} catch (Exception e) {
    ...
}
```

应该使用：

```java
@RestControllerAdvice
```

统一处理异常。

---

## 16. 推荐实现顺序

第一阶段不要一次做太多，先把注册接口跑通。

建议顺序：

```text
1. common/ApiResponse.java
2. common/BusinessException.java
3. common/GlobalExceptionHandler.java
4. dto/UserRegisterRequest.java
5. entity/User.java
6. mapper/UserMapper.java
7. service/UserService.java
8. service/impl/UserServiceImpl.java
9. controller/UserController.java
```

实现完成后，应该可以请求：

```http
POST /api/users
Content-Type: application/json
```

请求体：

```json
{
  "username": "linxi",
  "password": "123456",
  "nickname": "林夕"
}
```

成功响应：

```json
{
  "code": 200,
  "message": "注册成功",
  "data": null
}
```

失败响应示例：

```json
{
  "code": 400,
  "message": "用户名已存在",
  "data": null
}
```

---

## 17. 大师级后端思维

后端不是简单地“写接口”。

真正的后端开发需要思考：

```text
请求怎么进来？
参数怎么校验？
业务规则在哪里处理？
数据怎么持久化？
异常怎么统一返回？
哪些数据不能返回给前端？
数据库字段和 Java 字段怎么映射？
以后功能变多时，代码还能不能维护？
```

每写一个接口，都可以按照下面的问题拆解：

```text
Controller：这个接口地址是什么？请求方式是什么？
DTO：前端要传什么？
Service：业务规则是什么？
Mapper：需要查哪些表？改哪些表？
Entity：数据库表怎么映射？
VO：最终返回给前端什么？
Exception：失败时怎么返回？
```

长期按照这种方式练习，就不是“能跑就行”的后端，而是在走向规范、成熟、可维护的后端工程师。