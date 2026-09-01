# 菲比啾比：Redis + Lua 固定窗口接口限流实践指南

> 适用项目：菲比啾比后端  
> 适用接口：`POST /api/videos/upload-url`（获取 COS 视频上传临时凭证）  
> 限流目标：同一个登录用户每 60 秒最多请求 10 次  
> 技术栈：Java 17、Spring Boot 3.5、Spring Data Redis、Redis 7、Lua

---

## 1. 这一阶段要解决什么问题

菲比啾比目前通过下面的接口向前端发放腾讯云 COS 临时上传凭证：

```http
POST /api/videos/upload-url
Authorization: Bearer <token>
Content-Type: application/json
```

这个接口不是普通的数据库查询接口，它会：

1. 校验登录用户；
2. 校验视频文件信息；
3. 生成一个临时 COS Object Key；
4. 向腾讯云申请临时密钥；
5. 将临时文件信息写入数据库；
6. 把临时凭证返回给前端。

如果没有限流，用户或者异常客户端可以在短时间内连续请求大量临时凭证，造成：

- 无意义地消耗腾讯云接口资源；
- 产生大量临时文件记录；
- 增加服务器、数据库和第三方服务压力；
- 被恶意脚本批量申请云资源权限；
- 程序发生重试风暴时放大故障。

因此，这里先实现一个简单但实用的规则：

> 同一个登录用户，在一个 60 秒窗口内，最多成功请求 10 次上传凭证。

Redis Key 设计为：

```text
rate:upload-token:user:{uid}
```

例如用户 ID 为 `1001`：

```text
rate:upload-token:user:1001
```

Value 保存当前窗口内已经请求的次数：

```text
1
2
3
...
10
11
```

Key 的 TTL 为 60 秒。TTL 到期后 Redis 自动删除 Key，下一次请求重新开始计数。

---

## 2. 为什么这个接口适合按用户限流

当前上传凭证接口必须登录。

菲比啾比的登录拦截器会解析 JWT，并把用户 ID 写入请求属性：

```java
request.setAttribute("currentUserId", tokenContext.userId());
```

Controller 再从请求属性中获得用户 ID：

```java
Integer currentUserId = (Integer) httprequest.getAttribute("currentUserId");
```

因此，限流维度不需要相信前端传来的 `uid`，而是使用服务端从合法 JWT 中解析出的用户 ID。

这是非常重要的安全原则：

> 与身份、权限、限流相关的用户标识，不能直接相信前端提交的数据。

如果让前端在请求参数中传入 `uid`，恶意用户可以不停更换 `uid`，轻松绕过限流。

---

## 3. 固定窗口限流是什么

### 3.1 基本思想

固定窗口限流会把时间切分为一个个固定长度的窗口，并在每个窗口中维护一个计数器。

例如：

```text
窗口长度：60 秒
最大请求数：10 次
```

执行过程：

```text
第 1 次请求  -> 计数 1  -> 放行
第 2 次请求  -> 计数 2  -> 放行
...
第 10 次请求 -> 计数 10 -> 放行
第 11 次请求 -> 计数 11 -> 拒绝
第 12 次请求 -> 计数 12 -> 拒绝
...
窗口结束，Key 自动过期
下一次请求  -> 重新创建 Key，计数从 1 开始
```

### 3.2 本文实现的窗口从什么时候开始

本文采用：

```text
第一次请求到达时创建窗口，窗口持续 60 秒
```

假设用户第一次请求发生在 `12:00:25`，那么这个窗口大约在 `12:01:25` 结束。

这也是实践中很常见的“固定时长计数窗口”。

另一种做法是按照自然分钟对齐：

```text
12:00:00 ~ 12:00:59
12:01:00 ~ 12:01:59
```

自然分钟对齐通常需要把窗口编号拼进 Key：

```text
rate:upload-token:user:1001:29683200
```

其中最后一段可以通过下面的方式计算：

```java
long windowId = System.currentTimeMillis() / 1000 / 60;
```

本阶段先使用更容易理解的“第一次请求开始计时”方案：

```text
rate:upload-token:user:{uid}
```

---

## 4. 为什么使用 Redis

如果把请求次数保存在 Java 内存中，例如：

```java
Map<Integer, Integer> requestCountMap = new ConcurrentHashMap<>();
```

会遇到以下问题：

1. 应用重启后计数全部丢失；
2. 多个后端实例各自保存一份计数，无法共享；
3. 需要自己处理过期和清理；
4. 长期运行可能因为没有及时清理而占用大量内存；
5. 很难保证多个操作之间的原子性。

Redis 更适合保存这种临时状态：

- 支持原子自增 `INCR`；
- 支持 TTL；
- Key 到期后自动删除；
- 多个后端实例共享同一个计数；
- 单线程命令执行模型容易保证计数操作的原子性；
- 支持 Lua 脚本把多个 Redis 命令组合成一个原子操作。

---

## 5. 先理解 INCR 与 TTL

### 5.1 INCR

Redis 命令：

```redis
INCR rate:upload-token:user:1001
```

如果 Key 不存在，Redis 会先把它当作 `0`，再自增为 `1`：

```text
不存在 -> 1
```

后续继续执行：

```text
1 -> 2
2 -> 3
3 -> 4
```

`INCR` 本身是原子操作。

即使两个请求同时执行 `INCR`，Redis 也不会让它们都拿到同一个结果。一个请求会得到 `1`，另一个会得到 `2`。

### 5.2 EXPIRE

Redis 命令：

```redis
EXPIRE rate:upload-token:user:1001 60
```

表示让这个 Key 在 60 秒后自动过期。

### 5.3 TTL

查看 Key 剩余过期时间：

```redis
TTL rate:upload-token:user:1001
```

可能返回：

```text
57
```

表示还有 57 秒过期。

`TTL` 还有两个特殊返回值：

```text
-1：Key 存在，但没有设置过期时间
-2：Key 不存在
```

这两个值在排查 Redis 限流问题时非常有用。

---

## 6. 初级写法有什么问题

最容易想到的 Java 逻辑是：

```java
Long count = stringRedisTemplate.opsForValue().increment(key);

if (count != null && count == 1) {
    stringRedisTemplate.expire(key, Duration.ofSeconds(60));
}
```

它对应两条 Redis 命令：

```redis
INCR key
EXPIRE key 60
```

这段代码在普通情况下能够工作，但它不是一个完整的原子操作。

可能发生下面的情况：

```text
1. INCR 执行成功，Key 的值变成 1
2. 应用进程崩溃、线程异常或网络中断
3. EXPIRE 没有执行
```

最终 Redis 中会遗留：

```text
Key：rate:upload-token:user:1001
Value：1
TTL：-1
```

这个 Key 永远不会自动过期。

用户后续每次请求都会继续自增，最终可能永久处于被限流状态。

### 6.1 为什么不能简单交换命令顺序

不能先执行 `EXPIRE` 再执行 `INCR`，因为 Key 还不存在：

```redis
EXPIRE not-exist-key 60
```

对不存在的 Key 设置过期时间不会生效。

因此，问题不是简单调整命令顺序，而是需要让下面两件事不可分割：

```text
计数器自增
第一次请求时设置过期时间
```

解决方案就是 Redis Lua 脚本。

---

## 7. Lua 脚本为什么能够解决原子性问题

Redis 执行 Lua 脚本时，会把整个脚本看成一个不可被其他 Redis 命令插入的执行单元。

也就是说，下面的脚本在执行期间：

```lua
local current = redis.call('INCR', KEYS[1])

if current == 1 then
    redis.call('EXPIRE', KEYS[1], ARGV[1])
end

return current
```

其他客户端的命令不能插入到 `INCR` 和 `EXPIRE` 中间。

它实现了：

```text
INCR + 判断 + EXPIRE + 返回计数
```

这一整组逻辑的原子执行。

需要注意：

> 原子性不代表脚本执行到一半发生运行时错误时会像数据库事务一样自动回滚。

Redis Lua 的主要价值是：脚本执行过程中不会被其他客户端命令穿插。编写脚本时仍然要保持逻辑简单，并提前避免类型错误。

---

## 8. 本项目推荐的整体结构

不要把限流逻辑全部塞进 `VideoController`，也不要把所有业务都堆进通用的 `RedisUtils`。

推荐结构：

```text
src/main/java/com/feibijiubi/backend/
├── config/
│   └── RedisScriptConfig.java
├── service/
│   └── ratelimit/
│       ├── RateLimitService.java
│       └── impl/
│           └── RedisRateLimitServiceImpl.java
├── utils/
│   └── redis/
│       ├── RedisConstants.java
│       └── RedisKeyUtils.java
└── controller/
    └── VideoController.java

src/main/resources/
└── lua/
    └── fixed-window-rate-limit.lua
```

各层职责：

| 位置 | 职责 |
|---|---|
| `fixed-window-rate-limit.lua` | 描述 Redis 中原子执行的计数和过期逻辑 |
| `RedisScriptConfig` | 将 Lua 文件加载为 Spring Bean |
| `RedisConstants` | 保存 Key 前缀、窗口秒数、最大请求数 |
| `RedisKeyUtils` | 统一构造 Redis Key |
| `RateLimitService` | 定义限流能力 |
| `RedisRateLimitServiceImpl` | 执行 Lua、判断是否超过限制、抛出业务异常 |
| `VideoController` | 在发放 COS 凭证之前调用限流服务 |

这样的结构比在 Controller 中直接写 Redis 代码更容易测试、复用和维护。

---

## 9. 第一步：编写 Lua 脚本

创建文件：

```text
src/main/resources/lua/fixed-window-rate-limit.lua
```

内容：

```lua
-- KEYS[1]：限流计数器的 Redis Key
-- ARGV[1]：窗口长度，单位为秒

local current = redis.call('INCR', KEYS[1])
local ttl = redis.call('TTL', KEYS[1])

-- current == 1：正常情况下，这是该窗口内的第一次请求
-- ttl < 0：兼容并修复历史上可能遗留的“没有过期时间”的计数器
if current == 1 or ttl < 0 then
    redis.call('EXPIRE', KEYS[1], ARGV[1])
end

return current
```

### 9.1 为什么同时判断 `current == 1` 和 `ttl < 0`

最简单的脚本只判断：

```lua
if current == 1 then
    redis.call('EXPIRE', KEYS[1], ARGV[1])
end
```

这对全新的 Key 已经足够。

本文额外判断：

```lua
ttl < 0
```

是为了兼容系统中可能已经存在的异常 Key。

例如以前使用过非原子的 `INCR + EXPIRE`，留下了没有 TTL 的计数器。此时它的值可能已经不是 `1`，仅判断 `current == 1` 无法修复它。

加上 `ttl < 0` 后，只要发现计数器没有有效过期时间，就重新设置 60 秒 TTL。

### 9.2 为什么 Lua 返回当前计数，而不是只返回 0 或 1

脚本返回：

```lua
return current
```

Java 可以根据当前计数判断：

```java
current <= maxRequests
```

同时，当前计数还可以用于：

- 调试；
- 日志记录；
- 监控；
- 将来返回剩余请求次数；
- 验证并发请求有没有丢失计数。

---

## 10. 第二步：配置 Lua 脚本 Bean

创建文件：

```text
src/main/java/com/feibijiubi/backend/config/RedisScriptConfig.java
```

代码：

```java
package com.feibijiubi.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;

@Configuration
public class RedisScriptConfig {

    @Bean
    public DefaultRedisScript<Long> fixedWindowRateLimitScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(
                new ClassPathResource("lua/fixed-window-rate-limit.lua")
        ));
        script.setResultType(Long.class);
        return script;
    }
}
```

### 10.1 为什么把 Lua 放在 resources 中

也可以在 Java 中直接写字符串：

```java
String script = "local current = redis.call('INCR', KEYS[1]) ...";
```

但不推荐这样做，因为：

- Java 字符串可读性差；
- Lua 语法高亮丢失；
- 多行字符串容易出现转义问题；
- Lua 变长后 Java 配置类会很混乱；
- 独立文件更容易使用 `redis-cli` 单独测试。

因此，生产项目更适合将脚本放在：

```text
src/main/resources/lua/
```

### 10.2 是否需要额外依赖

当前 `pom.xml` 已经引入：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
```

因此可以直接使用：

- `StringRedisTemplate`；
- `DefaultRedisScript`；
- `ClassPathResource`；
- Redis Lua 执行能力。

不需要为了这个功能额外引入 Redisson。

---

## 11. 第三步：补充 Redis 常量

当前项目已经有：

```java
public static final String RATE_UPLOAD_PREFIX = "rate:upload-token:user:";
```

建议继续加入限流参数：

```java
package com.feibijiubi.backend.utils.redis;

public final class RedisConstants {
    public static final String LOGIN_CODE_PREFIX = "auth:login-code:";
    public static final String JWT_TOKEN_PREFIX = "auth:jwt-token:";
    public static final String VIDEO_DETAIL_PREFIX = "video:detail:v1:";
    public static final String RATE_UPLOAD_PREFIX = "rate:upload-token:user:";

    public static final long LOGIN_CODE_EXPIRE_TIME = 60;
    public static final long VIDEO_DETAIL_EXPIRE_TIME = 60 * 30;

    public static final long RATE_UPLOAD_WINDOW_SECONDS = 60;
    public static final long RATE_UPLOAD_MAX_REQUESTS = 10;

    private RedisConstants() {
    }
}
```

### 11.1 为什么窗口和次数也要定义成常量

不建议在业务代码里直接写：

```java
if (current > 10) {
    // ...
}
```

和：

```java
String.valueOf(60)
```

因为 `10` 和 `60` 离开上下文后无法表达业务含义，属于魔法数字。

命名常量可以直接说明含义：

```java
RedisConstants.RATE_UPLOAD_MAX_REQUESTS
RedisConstants.RATE_UPLOAD_WINDOW_SECONDS
```

后续如果规则改成每 2 分钟 20 次，只需要修改统一配置位置。

### 11.2 常量还是配置文件

本阶段使用 Java 常量最容易理解。

如果后续不同环境需要不同规则，例如：

```text
开发环境：每分钟 100 次
测试环境：每分钟 20 次
生产环境：每分钟 10 次
```

则应进一步迁移到 `application.yml`：

```yaml
rate-limit:
  upload-token:
    max-requests: 10
    window-seconds: 60
```

再使用 `@ConfigurationProperties` 读取。

学习顺序建议：

```text
先用常量完成闭环
    ↓
理解功能后改为配置化
    ↓
最后再考虑通用注解限流
```

---

## 12. 第四步：统一构造限流 Key

当前 `RedisKeyUtils` 已经有：

```java
public static String rateUpload(Integer uid) {
    return RedisConstants.RATE_UPLOAD_PREFIX + uid;
}
```

调用结果：

```java
RedisKeyUtils.rateUpload(1001)
```

得到：

```text
rate:upload-token:user:1001
```

统一使用 Key 工具类有以下好处：

- 避免不同业务代码手写出不同格式；
- 修改 Key 规则时只改一个地方；
- 防止漏写冒号；
- 更容易搜索 Key 的使用位置；
- Key 的业务语义更清晰。

不要在 Controller 中这样拼接：

```java
String key = "rate:upload-token:user:" + currentUserId;
```

Controller 不应该知道 Redis Key 的具体格式。

---

## 13. 第五步：定义限流 Service

创建接口：

```text
src/main/java/com/feibijiubi/backend/service/ratelimit/RateLimitService.java
```

代码：

```java
package com.feibijiubi.backend.service.ratelimit;

public interface RateLimitService {

    void checkUploadTokenLimit(Integer userId);
}
```

这里使用：

```java
void checkUploadTokenLimit(Integer userId)
```

它表达的语义是：

```text
检查是否允许请求
允许：正常返回
不允许：抛出统一业务异常
```

这种设计适合当前项目，因为 Controller 已经通过 `GlobalExceptionHandler` 统一处理 `BusinessException`。

另一种设计是返回布尔值：

```java
boolean tryAcquire(String key, long maxRequests, long windowSeconds);
```

它更通用，但 Controller 或业务 Service 每次都需要重复写异常判断。

本阶段建议先使用业务语义明确的方法。等多个接口都需要限流时，再提炼通用的 `tryAcquire` 方法。

---

## 14. 第六步：实现 Redis 限流 Service

创建文件：

```text
src/main/java/com/feibijiubi/backend/service/ratelimit/impl/RedisRateLimitServiceImpl.java
```

代码：

```java
package com.feibijiubi.backend.service.ratelimit.impl;

import com.feibijiubi.backend.common.BusinessException;
import com.feibijiubi.backend.service.ratelimit.RateLimitService;
import com.feibijiubi.backend.utils.redis.RedisConstants;
import com.feibijiubi.backend.utils.redis.RedisKeyUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.Collections;

@Service
@RequiredArgsConstructor
public class RedisRateLimitServiceImpl implements RateLimitService {

    private final StringRedisTemplate stringRedisTemplate;
    private final DefaultRedisScript<Long> fixedWindowRateLimitScript;

    @Override
    public void checkUploadTokenLimit(Integer userId) {
        if (userId == null) {
            throw new BusinessException(
                    HttpStatus.UNAUTHORIZED.value(),
                    "登录失效"
            );
        }

        String key = RedisKeyUtils.rateUpload(userId);

        final Long current;
        try {
            current = stringRedisTemplate.execute(
                    fixedWindowRateLimitScript,
                    Collections.singletonList(key),
                    String.valueOf(RedisConstants.RATE_UPLOAD_WINDOW_SECONDS)
            );
        } catch (DataAccessException e) {
            throw new BusinessException(
                    HttpStatus.SERVICE_UNAVAILABLE.value(),
                    "限流服务暂不可用，请稍后再试"
            );
        }

        if (current == null) {
            throw new BusinessException(
                    HttpStatus.INTERNAL_SERVER_ERROR.value(),
                    "限流结果异常"
            );
        }

        if (current > RedisConstants.RATE_UPLOAD_MAX_REQUESTS) {
            throw new BusinessException(
                    HttpStatus.TOO_MANY_REQUESTS.value(),
                    "请求过于频繁，请稍后再试"
            );
        }
    }
}
```

### 14.1 `execute` 的参数分别是什么

```java
stringRedisTemplate.execute(
        fixedWindowRateLimitScript,
        Collections.singletonList(key),
        String.valueOf(windowSeconds)
);
```

对应关系：

| Java 参数 | Lua 中的值 |
|---|---|
| `Collections.singletonList(key)` | `KEYS[1]` |
| `String.valueOf(windowSeconds)` | `ARGV[1]` |

也就是：

```lua
KEYS[1] = "rate:upload-token:user:1001"
ARGV[1] = "60"
```

### 14.2 为什么 Redis Key 必须通过 KEYS 传入

不建议把 Key 当普通参数传入：

```lua
local key = ARGV[1]
```

Redis 约定脚本访问的 Key 应通过 `KEYS` 数组传递。

这样做有利于：

- Redis 知道脚本会操作哪些 Key；
- Redis Cluster 判断 Key 所在的槽位；
- 多 Key 脚本更容易做集群兼容检查；
- 脚本参数的语义更清晰。

### 14.3 为什么捕获 `DataAccessException`

Redis 连接失败、命令执行失败等 Spring Data Redis 问题通常会转换成 `DataAccessException`。

这里不要捕获所有 `Exception`：

```java
catch (Exception e) {
    // 不推荐
}
```

因为过大的捕获范围容易掩盖编程错误。

本文选择 Redis 失败时返回：

```http
503 Service Unavailable
```

因为上传凭证属于敏感资源发放接口。Redis 无法完成限流判断时，暂时拒绝发放凭证更安全，这叫作：

```text
fail closed（失败时关闭）
```

如果是一个不敏感、可用性优先的普通查询接口，也可以讨论 Redis 故障时直接放行：

```text
fail open（失败时放行）
```

但不能不经过业务分析就统一选择其中一种。

### 14.4 为什么使用 429

限流触发时使用：

```java
HttpStatus.TOO_MANY_REQUESTS.value()
```

也就是：

```http
429 Too Many Requests
```

它比 `400`、`403`、`500` 更准确：

- `400`：请求参数或格式有问题；
- `403`：身份存在，但没有访问权限；
- `429`：请求本身可以理解，但发送得太频繁；
- `500`：服务器内部发生未知错误。

---

## 15. 第七步：接入 COS 上传凭证接口

当前接口：

```java
@PostMapping("/upload-url")
public ApiResponse<VideoUploadPrepareVO> uploadUrl(
        HttpServletRequest httprequest,
        @Valid @RequestBody VideoUploadPrepareDTO request
) {
    Integer currentUserId = (Integer) httprequest.getAttribute("currentUserId");
    VideoUploadPrepareVO vo = fileStorageService.uploadPrepare(currentUserId, request);
    return ApiResponse.success(vo);
}
```

注入限流服务：

```java
private final FileStorageService fileStorageService;
private final VideoService videoService;
private final RateLimitService rateLimitService;

public VideoController(FileStorageService fileStorageService,
                       VideoService videoService,
                       RateLimitService rateLimitService) {
    this.fileStorageService = fileStorageService;
    this.videoService = videoService;
    this.rateLimitService = rateLimitService;
}
```

在生成凭证之前执行限流检查：

```java
@PostMapping("/upload-url")
public ApiResponse<VideoUploadPrepareVO> uploadUrl(
        HttpServletRequest httprequest,
        @Valid @RequestBody VideoUploadPrepareDTO request
) {
    Integer currentUserId = (Integer) httprequest.getAttribute("currentUserId");

    rateLimitService.checkUploadTokenLimit(currentUserId);

    VideoUploadPrepareVO vo = fileStorageService.uploadPrepare(currentUserId, request);
    return ApiResponse.success(vo);
}
```

完整调用链：

```text
前端请求 POST /api/videos/upload-url
        ↓
LoginInterceptor 校验 JWT
        ↓
把 currentUserId 写入 HttpServletRequest
        ↓
VideoController 读取 currentUserId
        ↓
RateLimitService 执行 Redis Lua
        ↓
当前次数 <= 10？
  ┌─────┴─────┐
  是          否
  ↓           ↓
继续申请凭证   抛出 BusinessException(429)
  ↓           ↓
返回 COS 凭证  GlobalExceptionHandler 统一返回
```

### 15.1 为什么要在调用 COS 之前限流

必须先执行：

```java
rateLimitService.checkUploadTokenLimit(currentUserId);
```

再执行：

```java
fileStorageService.uploadPrepare(currentUserId, request);
```

否则第 11 次请求虽然最终返回 429，但可能已经：

- 调用了腾讯云 STS；
- 创建了临时上传记录；
- 消耗了业务资源。

那就失去了限流保护昂贵操作的意义。

---

## 16. 统一异常返回会是什么样

项目的 `GlobalExceptionHandler` 已经可以处理 `BusinessException`：

```java
@ExceptionHandler(BusinessException.class)
public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException e) {
    HttpStatus status = resolveStatus(e.getCode());
    return ResponseEntity.status(status)
            .body(ApiResponse.fail(status.value(), e.getMessage()));
}
```

当限流 Service 抛出：

```java
throw new BusinessException(
        HttpStatus.TOO_MANY_REQUESTS.value(),
        "请求过于频繁，请稍后再试"
);
```

HTTP 状态码为：

```http
HTTP/1.1 429 Too Many Requests
```

响应体为：

```json
{
  "code": 429,
  "message": "请求过于频繁，请稍后再试",
  "data": null
}
```

前端可以统一判断：

```javascript
if (response.status === 429) {
  // 提示用户不要重复点击
}
```

不要在 Controller 中手写不统一的响应：

```java
return ApiResponse.fail(500, "请求太多");
```

更不要在限流时返回 HTTP 200，再把业务 code 写成 429。当前项目的统一异常体系已经能够让 HTTP 状态码和响应体 code 保持一致。

---

## 17. 第 10 次和第 11 次请求到底如何判断

假设最大请求数为：

```java
RATE_UPLOAD_MAX_REQUESTS = 10
```

Java 判断：

```java
if (current > RATE_UPLOAD_MAX_REQUESTS) {
    throw new BusinessException(429, "请求过于频繁，请稍后再试");
}
```

结果：

| Lua 返回值 | 是否放行 |
|---:|---|
| 1 | 放行 |
| 2 | 放行 |
| ... | ... |
| 10 | 放行 |
| 11 | 拒绝 |
| 12 | 拒绝 |

注意条件必须是：

```java
current > 10
```

而不是：

```java
current >= 10
```

如果写成 `>= 10`，第 10 次请求也会被拒绝，实际只能成功 9 次。

---

## 18. 为什么被拒绝的请求也继续计数

当前脚本先执行：

```lua
INCR
```

然后 Java 再判断是否超过限制。

因此第 11 次、第 12 次请求都会继续增加计数器。

例如：

```text
11、12、13、14...
```

这是可以接受的，因为：

1. Key 仍然会在窗口结束后自动删除；
2. 计数可以反映用户实际发起了多少请求；
3. 逻辑简单，容易理解和验证；
4. 不会因为已经超过限制就产生并发判断漏洞。

如果恶意客户端在一分钟内请求极多次，Redis 整数计数范围足够大，正常业务下不需要担心溢出。

后续也可以让 Lua 直接接收最大请求数，并只返回允许或拒绝，但本阶段返回当前计数更利于学习和排查。

---

## 19. 按用户限流与按 IP 限流的区别

### 19.1 按用户限流

Key：

```text
rate:upload-token:user:{uid}
```

优点：

- 登录用户身份稳定；
- 同一用户切换网络仍然共享限额；
- 同一个 IP 下的不同用户互不影响；
- 适合登录后的业务接口；
- 更容易针对会员、普通用户设置不同额度。

缺点：

- 必须先完成身份认证；
- 攻击者可以注册多个账号绕过单账号限制。

适合：

- 获取 COS 上传凭证；
- 发布视频；
- 点赞、收藏、关注；
- 修改密码；
- 其他登录后操作。

### 19.2 按 IP 限流

Key：

```text
rate:login:ip:{ip}
```

优点：

- 未登录时也能使用；
- 适合保护登录、注册、验证码接口；
- 可以限制单一来源的大量请求。

缺点：

- 公司、学校、宿舍可能多人共享公网 IP；
- 移动网络的 IP 可能频繁变化；
- 攻击者可以使用代理 IP；
- 反向代理环境下不能直接相信错误的请求头。

适合：

- 登录接口；
- 注册接口；
- 发送短信或邮箱验证码；
- 公开搜索接口；
- 未登录可访问的敏感接口。

### 19.3 用户 + IP 组合限流

安全要求更高时，可以同时设置：

```text
rate:upload-token:user:{uid}
rate:upload-token:ip:{ip}
```

规则示例：

```text
单用户：每分钟最多 10 次
单 IP：每分钟最多 100 次
```

只有两项都通过才放行。

这样可以同时防止：

- 单个账号滥用；
- 同一 IP 批量控制多个账号。

但本阶段先实现按用户限流，不要一开始就把设计复杂化。

---

## 20. 获取客户端 IP 时要注意什么

如果未来实现按 IP 限流，不要无条件相信：

```http
X-Forwarded-For
```

因为客户端可以自己伪造请求头。

只有当请求一定经过你信任的 Nginx、网关或云负载均衡，并且代理会覆盖或清理客户端伪造的头时，才能按照代理规则提取真实 IP。

直接连接应用时可以使用：

```java
request.getRemoteAddr()
```

经过可信反向代理时，需要结合部署结构配置：

- Spring Boot Forward Headers；
- Nginx `proxy_set_header`；
- 可信代理层级；
- `X-Forwarded-For` 中 IP 的顺序。

因此：

> 按 IP 限流不仅是写一个 Redis Key，还涉及可信代理边界。

---

## 21. 固定窗口的边界突发问题

固定窗口实现简单、性能好，但存在边界突发问题。

假设按自然分钟限制每分钟 10 次：

```text
12:00:50 ~ 12:00:59：请求 10 次
12:01:00 ~ 12:01:09：再请求 10 次
```

从每个自然分钟看都没有超过 10 次，但在连续 20 秒内实际完成了 20 次请求。

这就是固定窗口的边界问题。

本文使用“第一次请求开始 60 秒窗口”，同样不能彻底消除所有边界突发，只是窗口边界不再固定对齐自然分钟。

更严格的算法包括：

- 滑动窗口日志；
- 滑动窗口计数；
- 令牌桶；
- 漏桶。

但学习顺序应当是：

```text
固定窗口
  ↓
理解原子计数和 TTL
  ↓
理解 Lua
  ↓
再学习滑动窗口或令牌桶
```

对于当前 COS 上传凭证接口，“每 60 秒 10 次”的固定窗口已经能解决大部分重复点击和简单滥用问题。

---

## 22. 手动使用 redis-cli 验证 Lua

### 22.1 进入 Redis 容器

项目使用 Docker Compose 启动 Redis，可以执行：

```bash
docker exec -it feibijiubi-redis redis-cli
```

### 22.2 手动执行 Lua

在 `redis-cli` 中执行：

```redis
EVAL "local current = redis.call('INCR', KEYS[1]); local ttl = redis.call('TTL', KEYS[1]); if current == 1 or ttl < 0 then redis.call('EXPIRE', KEYS[1], ARGV[1]); end; return current" 1 rate:upload-token:user:1001 60
```

参数解释：

```text
EVAL <脚本> <KEY 数量> <KEYS...> <ARGV...>
```

这里：

```text
KEY 数量 = 1
KEYS[1] = rate:upload-token:user:1001
ARGV[1] = 60
```

第一次返回：

```text
(integer) 1
```

再次执行返回：

```text
(integer) 2
```

### 22.3 查看计数

```redis
GET rate:upload-token:user:1001
```

### 22.4 查看 TTL

```redis
TTL rate:upload-token:user:1001
```

应该得到 `0 ~ 60` 范围内的整数，而不是：

```text
-1
```

### 22.5 验证过期

等待 60 秒后执行：

```redis
GET rate:upload-token:user:1001
```

应返回：

```text
(nil)
```

再次执行 Lua，结果应重新从 `1` 开始。

### 22.6 清理测试 Key

```redis
DEL rate:upload-token:user:1001
```

不要使用：

```redis
FLUSHALL
```

因为它会删除 Redis 当前数据库中的所有 Key，包括登录验证码、JWT 黑名单和视频缓存。

---

## 23. 使用接口进行功能测试

### 23.1 前置条件

1. MySQL 正常运行；
2. Redis 正常运行；
3. Spring Boot 应用正常启动；
4. 已经登录并获得有效 JWT；
5. COS 配置可用。

### 23.2 连续请求接口

请求示例：

```bash
curl -X POST "http://localhost:8080/api/videos/upload-url" \
  -H "Authorization: Bearer <你的JWT>" \
  -H "Content-Type: application/json" \
  -d '{
    "fileName": "demo.mp4",
    "contentType": "video/mp4",
    "fileSize": 1048576
  }'
```

预期：

```text
第 1 ~ 10 次：HTTP 200
第 11 次开始：HTTP 429
等待 Key 过期后：重新 HTTP 200
```

限流响应示例：

```json
{
  "code": 429,
  "message": "请求过于频繁，请稍后再试",
  "data": null
}
```

### 23.3 同时观察 Redis

请求过程中执行：

```redis
GET rate:upload-token:user:<你的用户ID>
TTL rate:upload-token:user:<你的用户ID>
```

需要验证：

- Value 每次请求都增加；
- TTL 不会在每次请求时重置为 60；
- 超过 10 后接口返回 429；
- 到期后 Key 自动删除。

---

## 24. 为什么不能每次请求都重置 TTL

错误脚本：

```lua
local current = redis.call('INCR', KEYS[1])
redis.call('EXPIRE', KEYS[1], ARGV[1])
return current
```

这会导致每次请求都把 TTL 重新设置为 60 秒。

假设用户每 30 秒请求一次：

```text
第 1 次：TTL = 60
30 秒后第 2 次：TTL 又变成 60
30 秒后第 3 次：TTL 又变成 60
```

只要请求持续发生，Key 就可能一直不过期，计数也一直不归零。

这已经不再是固定窗口，而更接近一种惩罚不断延长的规则。

正确做法是只在第一次创建计数器时设置 TTL：

```lua
if current == 1 or ttl < 0 then
    redis.call('EXPIRE', KEYS[1], ARGV[1])
end
```

---

## 25. 为什么不推荐在 Controller 中直接执行 Lua

下面的代码虽然能工作，但不推荐：

```java
@PostMapping("/upload-url")
public ApiResponse<VideoUploadPrepareVO> uploadUrl(...) {
    Integer userId = ...;
    String key = "rate:upload-token:user:" + userId;
    Long current = stringRedisTemplate.execute(...);

    if (current > 10) {
        throw new BusinessException(429, "请求过于频繁");
    }

    // 获取凭证
}
```

问题：

- Controller 同时承担 HTTP、Redis、限流规则和业务流程；
- 其他接口不能方便复用；
- 测试 Controller 时必须关心 Redis 脚本细节；
- Key 格式和魔法数字散落；
- 后续切换算法时需要修改 Controller。

规范分层应该是：

```text
Controller：接收参数、获取当前用户、调用业务能力、返回响应
RateLimitService：执行限流规则
Redis/Lua：完成底层原子计数
```

---

## 26. 要不要把 Lua 执行方法放进 RedisUtils

当前 `RedisUtils` 主要封装：

- String 读写；
- JSON 读写；
- 删除 Key；
- 判断 Key；
- 设置和读取 TTL。

不建议直接添加一个业务方法：

```java
redisUtils.checkUploadTokenRateLimit(userId)
```

因为 `RedisUtils` 是基础设施工具，不应该知道“上传凭证”这种具体业务。

可以接受的通用封装是：

```java
Long executeLongScript(
        DefaultRedisScript<Long> script,
        List<String> keys,
        String... args
)
```

但对于当前只有一个 Lua 脚本的项目，这层封装的收益并不大。

本阶段直接在 `RedisRateLimitServiceImpl` 中注入 `StringRedisTemplate` 更清晰。

原则是：

> 不要为了“看起来封装了”而增加没有实际价值的转发层。

---

## 27. Lua 脚本长时间运行有什么风险

Redis 执行 Lua 脚本时，为了保持原子性，其他命令需要等待脚本执行完成。

因此 Lua 脚本必须：

- 足够短；
- 不做复杂循环；
- 不扫描大量 Key；
- 不执行耗时计算；
- 不把业务数据处理全部塞进脚本。

本文脚本只有：

```text
INCR
TTL
条件判断
可能执行 EXPIRE
RETURN
```

执行时间非常短，适合在请求链路中使用。

不要在限流 Lua 中使用：

```redis
KEYS rate:*
```

也不要遍历大量集合或执行不确定次数的循环。

---

## 28. Redis Cluster 下要注意什么

当前脚本只操作一个 Key：

```text
rate:upload-token:user:{uid}
```

因此不会出现多 Key 跨槽问题。

如果未来一个 Lua 脚本同时操作用户限流和 IP 限流：

```text
rate:upload-token:user:1001
rate:upload-token:ip:127.0.0.1
```

在 Redis Cluster 中，这两个 Key 可能位于不同槽位，脚本会执行失败。

常见解决方法：

1. 用户和 IP 分别执行脚本；
2. 使用 Hash Tag，让相关 Key 进入同一槽；
3. 在设计阶段避免一个脚本操作跨槽 Key。

本项目当前使用单 Redis，本阶段不需要提前复杂化，但应该知道这个限制。

---

## 29. 限流失败时是否应该记录日志

可以记录被限流事件，但要避免每次都输出高等级日志。

例如：

```java
log.warn(
        "上传凭证请求触发限流，userId={}, current={}",
        userId,
        current
);
```

如果攻击者持续请求，这可能产生大量日志，形成日志放大。

更稳妥的做法是：

- 普通限流不一定逐条记录；
- 通过 Redis 指标或应用监控统计 429 数量；
- 只对明显异常的高频行为做采样日志；
- 不在日志中记录 COS 临时密钥、JWT 等敏感信息。

尤其不能打印：

- `tmpSecretKey`；
- `sessionToken`；
- 完整 JWT；
- 腾讯云 SecretKey。

---

## 30. 可选增强：返回剩余等待时间

基础版本统一返回：

```json
{
  "code": 429,
  "message": "请求过于频繁，请稍后再试",
  "data": null
}
```

后续可以增强为：

```json
{
  "code": 429,
  "message": "请求过于频繁，请在 37 秒后重试",
  "data": null
}
```

也可以添加标准响应头：

```http
Retry-After: 37
```

要实现它，可以让 Lua 返回数组：

```lua
return {current, redis.call('TTL', KEYS[1])}
```

或者在被拒绝后额外查询一次 TTL。

但当前 `BusinessException` 只携带：

```text
code
message
```

不能直接携带响应头。

如果要规范支持 `Retry-After`，可以扩展异常结构，或者为限流异常单独增加异常类型和处理器。

本阶段建议先完成稳定的 429 统一返回，再做这个增强。

---

## 31. 可选增强：将限流规则配置化

当限流接口变多后，不建议所有规则都写进 `RedisConstants`。

可以配置：

```yaml
rate-limit:
  upload-token:
    max-requests: 10
    window-seconds: 60
  login:
    max-requests: 5
    window-seconds: 60
```

配置类：

```java
@ConfigurationProperties(prefix = "rate-limit.upload-token")
@Data
public class UploadTokenRateLimitProperties {
    private long maxRequests = 10;
    private long windowSeconds = 60;
}
```

这样可以：

- 不修改代码就调整阈值；
- 不同环境配置不同限额；
- 配合配置中心动态管理；
- 让业务规则更集中。

但不要一开始就过度设计成支持所有算法、所有维度、动态表达式的“万能限流框架”。

---

## 32. 可选增强：注解 + AOP 限流

未来多个接口都需要限流时，可以设计：

```java
@RateLimit(
        key = "upload-token",
        dimension = RateLimitDimension.USER,
        maxRequests = 10,
        windowSeconds = 60
)
@PostMapping("/upload-url")
public ApiResponse<VideoUploadPrepareVO> uploadUrl(...) {
    // ...
}
```

AOP 负责：

1. 读取注解；
2. 获取当前用户 ID；
3. 构造 Redis Key；
4. 执行 Lua；
5. 超限时抛出 429。

优点：

- Controller 更简洁；
- 多个接口复用方便；
- 规则声明化。

缺点：

- 调用过程更隐式；
- 初学阶段更难调试；
- SpEL、切面顺序、身份获取会增加复杂度；
- 业务特殊规则不一定适合统一注解。

推荐学习顺序：

```text
先手动调用 RateLimitService
        ↓
至少完成 2~3 个真实限流场景
        ↓
识别出真正重复的代码
        ↓
再提炼注解和 AOP
```

---

## 33. 建议补充的测试

### 33.1 Lua 基础测试

验证：

1. 第一次执行返回 1；
2. 第一次执行后 TTL 大于 0；
3. 连续执行返回 2、3、4；
4. TTL 不会因每次请求而重置；
5. Key 到期后重新返回 1；
6. 没有 TTL 的历史 Key 能被脚本修复。

修复历史 Key 的测试方式：

```redis
SET rate:upload-token:user:1001 5
TTL rate:upload-token:user:1001
```

此时 TTL 应为：

```text
-1
```

执行 Lua 后：

```text
返回 6
TTL 变为接近 60
```

### 33.2 业务边界测试

验证：

- 第 9 次放行；
- 第 10 次放行；
- 第 11 次返回 429；
- 等待窗口结束后重新放行；
- 用户 A 被限流不会影响用户 B。

### 33.3 并发测试

同时发送 20 个请求，期望：

```text
大约 10 个成功
大约 10 个返回 429
Redis 最终计数为 20
```

这里说“大约”是因为如果 COS 或其他业务校验先失败，请求可能不会完整进入后续流程。为了精确验证限流本身，最好直接对 `RateLimitService` 做集成测试。

并发测试重点不是请求顺序，而是确认：

- 计数没有丢失；
- 不会有 11 个以上请求因为竞争条件同时通过；
- Key 一定拥有 TTL。

### 33.4 Redis 故障测试

关闭 Redis 后请求上传凭证接口，验证：

- 不返回 COS 凭证；
- 返回 HTTP 503；
- 响应体仍然符合 `ApiResponse` 结构；
- 日志中能够看到真正的 Redis 异常；
- 不把底层连接异常详情直接暴露给前端。

注意：如果只把 `DataAccessException` 转换成 `BusinessException` 而完全不记录原异常，排查会变困难。生产实现可以在转换前记录一次错误日志，但不要记录敏感凭证。

---

## 34. 常见错误清单

### 错误 1：INCR 和 EXPIRE 分开执行

```java
increment(key);
expire(key, 60);
```

问题：中间异常会产生永不过期的 Key。

正确方式：使用 Lua 原子组合。

### 错误 2：每次请求都重置 TTL

```lua
redis.call('EXPIRE', KEYS[1], 60)
```

问题：持续请求会让窗口一直不结束。

正确方式：只在第一次计数或发现 TTL 异常时设置。

### 错误 3：使用前端传来的 uid

问题：用户可以修改 uid 绕过限制。

正确方式：使用登录拦截器解析 JWT 后写入的 `currentUserId`。

### 错误 4：使用 `current >= 10`

问题：第 10 次也会被拒绝，实际只允许 9 次。

正确方式：

```java
current > 10
```

### 错误 5：限流放在获取凭证之后

问题：昂贵操作已经发生，限流失去保护作用。

正确方式：先限流，再调用 COS。

### 错误 6：返回 HTTP 200 + 业务 code 429

问题：HTTP 语义不统一，网关、监控和前端都难以正确判断。

正确方式：HTTP 状态和响应体 code 都返回 429。

### 错误 7：Redis 异常直接抛给前端

问题：泄露内部实现，响应结构不统一。

正确方式：转为统一业务异常，返回 503 或按业务制定降级策略。

### 错误 8：把所有限流逻辑写进 RedisUtils

问题：通用工具类与上传凭证业务耦合。

正确方式：业务规则放在 `RateLimitService`，Redis 工具保持基础通用。

### 错误 9：用 `FLUSHALL` 清理一个测试 Key

问题：会删除验证码、JWT 黑名单、缓存等所有 Redis 数据。

正确方式：精确执行：

```redis
DEL rate:upload-token:user:1001
```

### 错误 10：Lua 中执行耗时扫描

问题：Lua 执行期间会阻塞 Redis 处理其他命令。

正确方式：脚本只做少量、确定次数的 Redis 操作。

---

## 35. 本次实现后的知识闭环

完成这个功能后，你应该能够回答下面的问题。

### 35.1 什么是固定窗口限流

在一个固定长度的时间窗口内对请求计数，超过阈值后拒绝请求，窗口结束后计数清零。

### 35.2 Redis 的 INCR 有什么特点

- Key 不存在时从 0 开始；
- 每次自增 1；
- 单条命令原子执行；
- 适合实现计数器。

### 35.3 为什么还需要 TTL

限流计数是临时状态。TTL 让 Redis 在窗口结束后自动删除计数器，不需要人工清理。

### 35.4 为什么 INCR 和 EXPIRE 分开执行有风险

两条命令之间可能发生异常，导致计数器存在但没有过期时间。

### 35.5 Lua 的主要作用是什么

把多个 Redis 操作组合成一个不会被其他客户端命令穿插的原子执行单元。

### 35.6 为什么登录后的上传接口优先按用户限流

用户 ID 由服务端登录系统确认，身份稳定，不会误伤同 IP 下的其他正常用户。

### 35.7 为什么限流返回 429

429 的 HTTP 语义就是客户端在一段时间内发送了过多请求。

### 35.8 Redis 故障时应该放行还是拒绝

取决于业务：

- 安全、资金、权限发放类接口通常倾向 fail closed；
- 非敏感、可用性优先的接口可以考虑 fail open；
- 必须明确选择并记录原因。

---

## 36. 推荐的最终代码清单

完成基础版本预计涉及：

```text
新增：
src/main/resources/lua/fixed-window-rate-limit.lua
src/main/java/com/feibijiubi/backend/config/RedisScriptConfig.java
src/main/java/com/feibijiubi/backend/service/ratelimit/RateLimitService.java
src/main/java/com/feibijiubi/backend/service/ratelimit/impl/RedisRateLimitServiceImpl.java

修改：
src/main/java/com/feibijiubi/backend/utils/redis/RedisConstants.java
src/main/java/com/feibijiubi/backend/utils/redis/RedisKeyUtils.java
src/main/java/com/feibijiubi/backend/controller/VideoController.java
```

当前项目已经存在：

```java
RATE_UPLOAD_PREFIX = "rate:upload-token:user:"
```

以及：

```java
RedisKeyUtils.rateUpload(Integer uid)
```

因此实现时只需要在此基础上继续补充窗口、最大请求数、Lua 配置和限流 Service。

---

## 37. 最终实现顺序

建议严格按照下面的顺序动手：

```text
1. 确认 Redis 正常运行
2. 创建 fixed-window-rate-limit.lua
3. 使用 redis-cli 手动验证脚本
4. 创建 RedisScriptConfig 加载脚本
5. 补充限流窗口和次数常量
6. 创建 RateLimitService
7. 创建 RedisRateLimitServiceImpl
8. 在 VideoController 获取凭证前调用限流
9. 连续请求 11 次验证 429
10. 检查 Redis Value 和 TTL
11. 等待 60 秒验证窗口重置
12. 使用两个用户验证相互隔离
13. 最后补充自动化测试
```

不要一开始就同时加入：

- AOP 注解；
- 多种限流算法；
- 用户与 IP 双重限流；
- 动态规则后台；
- 分布式配置中心；
- 复杂监控系统。

先把一个真实接口的完整闭环做正确，再逐步抽象。

---

## 38. 一句话总结

菲比啾比的 COS 上传凭证限流可以概括为：

```text
使用服务端解析出的 uid 构造 Redis Key，
通过 Lua 原子执行 INCR、TTL 检查和首次 EXPIRE，
Java 根据当前计数判断是否超过每 60 秒 10 次，
超过后抛出 BusinessException(429)，
由 GlobalExceptionHandler 返回统一 ApiResponse。
```

核心不是“Redis 中存一个数字”这么简单，而是同时保证：

- 身份维度可信；
- 计数并发安全；
- Key 一定会过期；
- 多条 Redis 操作原子执行；
- 限流发生在昂贵操作之前；
- 异常状态码和响应格式统一；
- Redis 故障时有明确策略；
- 代码分层清晰，后续可以继续扩展。