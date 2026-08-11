# Redis 工具包的实际工作开发模式

## 1. 文档目标

本文档用于说明“菲比啾比”后端项目引入 Redis 后，如何按照更接近实际工作的方式组织 Redis 基础设施代码。

核心目标不是简单地复制一个包含几十个方法的 `RedisUtil`，也不是为了避免封装而在所有业务类中重复使用 `RedisTemplate`，而是做到：

- Redis 公共操作能够复用；
- 包和类的职责清晰；
- Key 命名与 TTL 统一管理；
- 序列化规则明确且保持一致；
- 业务逻辑不会进入通用 Redis 工具类；
- 对危险命令、并发和原子性有明确认识；
- 能够通过测试验证 Redis 行为；
- 结构可以随着项目规模逐步扩展。

可以在 `utils` 下创建独立的 `redis` package：

```text
com.feibijiubi.backend.utils.redis
```

这种设计本身没有问题。真正需要关注的是：

> `utils.redis` 内部应该继续划分职责，而不是把所有 Redis 操作、业务规则、Key、TTL 和序列化全部塞进一个巨大的 `RedisUtil`。

---

## 2. 为什么需要封装 Redis 操作

Spring Data Redis 原生写法通常是：

```java
redisTemplate.opsForValue().set(key, value, ttl);
redisTemplate.opsForSet().add(key, value);
redisTemplate.opsForZSet().incrementScore(key, value, delta);
redisTemplate.delete(key);
```

直接使用这些 API 没有问题，但是当项目逐渐变大后，可能出现以下需求：

- 多个业务重复执行相同操作；
- 统一处理空返回值；
- 统一使用 `Duration` 表达 TTL；
- 统一 Key 和 Value 的序列化方式；
- 统一记录日志和监控指标；
- 统一约束危险命令；
- 隔离部分 Spring Data Redis API 的细节；
- 提供更符合项目习惯的类型安全接口。

因此，在实际工作中封装 Redis 基础操作非常常见。

需要注意：

> 封装的目的不是把 Redis 原生命令全部重新抄写一遍，而是统一项目真正需要统一的规则。

---

## 3. 推荐目录结构

### 3.1 项目初期结构

项目刚开始使用 Redis、操作还不多时，可以采用：

```text
com.feibijiubi.backend
├── config
│   └── RedisConfig.java
│
├── utils
│   └── redis
│       ├── RedisUtils.java
│       ├── RedisKeyUtils.java
│       └── constants
│           ├── AuthRedisConstants.java
│           ├── UserRedisConstants.java
│           └── VideoRedisConstants.java
│
└── service
    ├── auth
    ├── user
    └── video
```

其中：

| 类或包 | 职责 |
| --- | --- |
| `RedisConfig` | 配置连接工厂、RedisTemplate 和序列化方式 |
| `RedisUtils` | 封装 Key、TTL 和少量常用 Value 操作 |
| `RedisKeyUtils` | 根据业务参数生成统一格式的 Redis Key |
| `constants` | 管理各业务模块的 Key 前缀和默认 TTL |
| `service` | 实现验证码、缓存、点赞、排行等具体业务规则 |

### 3.2 Redis 操作增多后的结构

当 Set、Hash、ZSet 等操作明显增多时，可以进一步拆分：

```text
com.feibijiubi.backend
├── config
│   └── RedisConfig.java
│
├── utils
│   └── redis
│       ├── RedisUtils.java
│       ├── RedisKeyUtils.java
│       │
│       ├── constants
│       │   ├── AuthRedisConstants.java
│       │   ├── UserRedisConstants.java
│       │   └── VideoRedisConstants.java
│       │
│       └── operation
│           ├── RedisValueOperations.java
│           ├── RedisHashOperations.java
│           ├── RedisSetOperations.java
│           └── RedisZSetOperations.java
│
└── service
    ├── auth
    ├── user
    └── video
```

不要在一开始为了看起来“完整”，就创建大量暂时没有任何用途的空类。合理的演进方式是：

```text
先使用 RedisUtils
        ↓
某类操作不断增加
        ↓
出现清晰的职责边界
        ↓
再提取对应的 Operations 类
```

---

## 4. `utils.redis` 是否合理

可以把 Redis 通用组件放在：

```text
utils.redis
```

这在中小型项目中是可接受的，而且比直接把 `RedisUtils` 和字符串、日期、文件等工具类混放在 `utils` 根目录更清晰。

不过从更严格的架构语义看，Redis 操作依赖：

- Spring Bean；
- Redis 网络连接；
- 序列化配置；
- 外部基础设施；
- Redis 服务的可用性。

因此它不是传统意义上的纯工具方法。随着项目继续发展，也可以将其迁移到：

```text
infrastructure.redis
```

或者：

```text
common.redis
```

对于当前“菲比啾比”项目，可以先使用用户更直观的：

```text
utils.redis
```

重点是内部职责清晰，而不是机械纠结包名。

---

## 5. Redis 配置与序列化

## 5.1 为什么必须明确序列化方式

`RedisTemplate` 在把 Java 对象写入 Redis 前，需要将对象转换为字节；读取时，再把字节恢复成 Java 对象。这个过程就是序列化和反序列化。

如果不统一序列化配置，可能出现：

- RedisInsight 中看到不可读的二进制内容；
- 同一个 Key 被不同方法写成不同格式；
- 写入时是对象，读取时却强制转换成字符串；
- 更换序列化器后，旧缓存无法读取；
- 不同服务之间无法共享缓存内容；
- Java 类型信息暴露或兼容性不明确。

因此，序列化方式应该在项目中集中配置。

## 5.2 推荐原则

通常可以采用：

```text
Key             → String
Hash Key        → String
Value           → JSON
Hash Value      → JSON
```

这样 Key 在 RedisInsight 和 `redis-cli` 中可读，Value 也可以通过 JSON 查看。

示意配置如下：

```java
@Configuration
public class RedisConfig {

    @Bean
    public RedisTemplate<String, Object> redisTemplate(
            RedisConnectionFactory connectionFactory,
            ObjectMapper objectMapper
    ) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        StringRedisSerializer stringSerializer =
                new StringRedisSerializer();

        GenericJackson2JsonRedisSerializer jsonSerializer =
                new GenericJackson2JsonRedisSerializer(objectMapper);

        template.setKeySerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);
        template.setValueSerializer(jsonSerializer);
        template.setHashValueSerializer(jsonSerializer);

        template.afterPropertiesSet();
        return template;
    }
}
```

> 以上代码用于说明设计方向。项目实际实现时，需要根据当前 Spring Boot、Spring Data Redis 和 Jackson 版本确认构造方法及 API 是否适用。

## 5.3 不要混合多套隐式规则

不建议在同一个工具类中同时提供：

```java
setValue(key, object);
setObjectValue(key, object);
setJsonValue(key, object);
```

同时又提供：

```java
getValue(key);
getObjectString(key);
getObject(key, clazz);
```

如果每种写法使用不同的序列化方式，调用者必须额外记住哪些写入方法对应哪些读取方法，容易出现类型转换错误。

推荐方案是：

1. 在 `RedisConfig` 中统一序列化协议；
2. 通用操作遵循同一协议；
3. 如果确实需要纯字符串操作，则明确使用 `StringRedisTemplate`；
4. 不要让方法名相似但实际存储格式不同。

---

## 6. 基础 `RedisUtils` 的职责

项目初期的 `RedisUtils` 可以负责：

- 写入和读取普通 Value；
- 删除一个或多个 Key；
- 判断 Key 是否存在；
- 设置和获取 TTL；
- 封装项目真正重复使用的少量操作。

示例：

```java
@Component
@RequiredArgsConstructor
public class RedisUtils {

    private final RedisTemplate<String, Object> redisTemplate;

    public void set(String key, Object value) {
        redisTemplate.opsForValue().set(key, value);
    }

    public void set(String key, Object value, Duration ttl) {
        redisTemplate.opsForValue().set(key, value, ttl);
    }

    public Object get(String key) {
        return redisTemplate.opsForValue().get(key);
    }

    public <T> T get(String key, Class<T> type) {
        Object value = redisTemplate.opsForValue().get(key);

        if (value == null) {
            return null;
        }

        if (!type.isInstance(value)) {
            throw new IllegalStateException(
                    "Redis value type mismatch, key: " + key
            );
        }

        return type.cast(value);
    }

    public boolean delete(String key) {
        return Boolean.TRUE.equals(redisTemplate.delete(key));
    }

    public long delete(Collection<String> keys) {
        Long deletedCount = redisTemplate.delete(keys);
        return deletedCount == null ? 0L : deletedCount;
    }

    public boolean hasKey(String key) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }

    public boolean expire(String key, Duration ttl) {
        return Boolean.TRUE.equals(redisTemplate.expire(key, ttl));
    }

    public Long getExpireSeconds(String key) {
        return redisTemplate.getExpire(key, TimeUnit.SECONDS);
    }
}
```

## 6.1 为什么使用构造器注入

推荐：

```java
@RequiredArgsConstructor
public class RedisUtils {

    private final RedisTemplate<String, Object> redisTemplate;
}
```

不推荐：

```java
@Autowired
private RedisTemplate redisTemplate;
```

构造器注入具有以下优点：

- 依赖关系明确；
- 字段可以是 `final`；
- 对象创建后依赖一定完整；
- 单元测试更容易替换依赖；
- 避免原始类型 `RedisTemplate` 带来的类型安全问题。

## 6.2 为什么使用 `Duration`

不推荐只提供：

```java
set(key, value, 60, TimeUnit.SECONDS);
```

更推荐：

```java
set(key, value, Duration.ofMinutes(1));
```

`Duration` 能让调用方直接表达时间含义，减少秒、分钟、毫秒等单位传错的概率。

---

## 7. Redis Key 管理

## 7.1 不要在业务代码中到处拼接 Key

不推荐：

```java
String key = "login:code:" + phone;
```

如果多个地方独立拼接，很容易出现不同格式：

```text
login:code:13800138000
login-code:13800138000
code:login:13800138000
```

应集中生成 Key：

```java
public final class RedisKeyUtils {

    private RedisKeyUtils() {
    }

    public static String loginCode(String phone) {
        return AuthRedisConstants.LOGIN_CODE_PREFIX + phone;
    }

    public static String videoDetail(Long videoId) {
        return VideoRedisConstants.VIDEO_DETAIL_PREFIX + videoId;
    }
}
```

使用时：

```java
String key = RedisKeyUtils.loginCode(phone);
```

## 7.2 推荐 Key 格式

推荐使用冒号分隔层级：

```text
业务:资源:标识:属性
```

例如：

```text
auth:login-code:13800138000
user:profile:1001
video:detail:2001
video:likes:2001
video:ranking:daily
```

Key 应满足：

- 能够看出所属业务；
- 格式统一；
- 避免过长；
- 不直接包含敏感信息；
- 能够根据业务 ID 精确删除；
- 必要时可以带版本号。

例如：

```text
video:detail:v1:2001
```

当缓存结构发生不兼容变化时，可以切换到 `v2`，避免读取旧格式缓存。

---

## 8. TTL 管理

## 8.1 哪些内容写 Java 常量，哪些写 `application.yml`

你说得对：**如果 TTL 需要根据环境、运营策略或部署要求调整，实际开发中通常更适合写到 `application.yml`，而不是全部硬编码在常量类中。**

需要区分两类内容。

### 适合写入 `application.yml` 的内容

这些内容属于可配置参数：

- Redis Host、Port、Password、Database；
- 连接超时和命令超时；
- 验证码有效期；
- 视频详情缓存时间；
- 热门榜单统计周期；
- 不同环境可能不同的缓存 TTL；
- 运维或运营期间可能调整的参数。

例如：

```yaml
app:
  redis:
    key-prefix: feibijiubi
    auth:
      login-code-ttl: 5m
    video:
      detail-ttl: 30m
```

使用类型安全的配置映射：

```java
@ConfigurationProperties(prefix = "app.redis")
public record RedisProperties(
        String keyPrefix,
        Auth auth,
        Video video
) {

    public record Auth(Duration loginCodeTtl) {
    }

    public record Video(Duration detailTtl) {
    }
}
```

在配置类中启用：

```java
@Configuration
@EnableConfigurationProperties(RedisProperties.class)
public class RedisConfig {
}
```

业务代码通过构造器注入：

```java
@Service
@RequiredArgsConstructor
public class VerificationCodeService {

    private final RedisUtils redisUtils;
    private final RedisProperties redisProperties;

    public void saveLoginCode(String phone, String code) {
        redisUtils.set(
                RedisKeyUtils.loginCode(phone),
                code,
                redisProperties.auth().loginCodeTtl()
        );
    }
}
```

Spring Boot 可以直接把：

```yaml
login-code-ttl: 5m
```

绑定成：

```java
Duration.ofMinutes(5)
```

这样比自己使用整数并约定单位更安全。

### 适合保留在 Java 中的内容

这些内容属于代码协议或稳定标识：

- Key 的结构规则；
- 固定的 Key 片段；
- Lua 脚本返回码；
- 不应由部署人员随意修改的内部约定；
- 与代码实现紧密绑定的常量。

例如：

```java
public final class RedisKeyConstants {

    public static final String LOGIN_CODE = "auth:login-code";
    public static final String VIDEO_DETAIL = "video:detail:v1";

    private RedisKeyConstants() {
    }
}
```

不过，完整 Key 的生成仍然应该集中管理：

```java
@Component
@RequiredArgsConstructor
public class RedisKeyGenerator {

    private final RedisProperties redisProperties;

    public String loginCode(String phone) {
        return String.join(
                ":",
                redisProperties.keyPrefix(),
                RedisKeyConstants.LOGIN_CODE,
                phone
        );
    }

    public String videoDetail(Long videoId) {
        return String.join(
                ":",
                redisProperties.keyPrefix(),
                RedisKeyConstants.VIDEO_DETAIL,
                videoId.toString()
        );
    }
}
```

最终生成的 Key 类似：

```text
feibijiubi:auth:login-code:13800138000
feibijiubi:video:detail:v1:2001
```

这里的划分是：

```text
可调整的项目命名空间和 TTL
        → application.yml + @ConfigurationProperties

稳定的 Key 结构和版本约定
        → Java 常量或 Key 生成器
```

### 为什么不建议把所有 Key 前缀都放到 YAML

虽然技术上可以这样写：

```yaml
app:
  redis:
    login-code-prefix: auth:login-code
    video-detail-prefix: video:detail
```

但是如果 Key 结构与代码读取、删除、Lua 脚本和缓存兼容性紧密相关，部署人员随意修改前缀可能导致：

- 应用读取不到已有缓存；
- 新旧实例使用不同 Key；
- 删除逻辑无法命中旧 Key；
- Lua 脚本使用的 Key 规则不一致；
- 灰度发布期间出现兼容问题。

因此，Key 的稳定结构通常保留在代码中；只有应用级命名空间等确实需要按环境变化的部分放入配置。

### 对“菲比啾比”的推荐方案

当前项目建议采用混合方案：

```yaml
app:
  redis:
    key-prefix: feibijiubi
    auth:
      login-code-ttl: 5m
    video:
      detail-ttl: 30m
```

```java
public final class RedisKeyConstants {

    public static final String LOGIN_CODE = "auth:login-code";
    public static final String VIDEO_DETAIL = "video:detail:v1";

    private RedisKeyConstants() {
    }
}
```

也就是说：

- Redis 连接参数写入配置文件；
- 需要调整的 TTL 写入配置文件；
- 应用命名空间可以写入配置文件；
- 稳定的 Key 结构和版本写在 Java 代码中；
- 使用 `@ConfigurationProperties`，不要在各处散落大量 `@Value`；
- 使用 `Duration` 类型，避免手动约定时间单位。

## 8.2 TTL 属于整个 Key

Redis TTL 设置在整个 Key 上，不是集合中的单个元素上。

例如：

```java
redisTemplate.opsForSet().add(key, userId);
redisTemplate.expire(key, Duration.ofMinutes(10));
```

这里过期的是整个 Set，不是刚添加的 `userId`。

如果多次添加成员并重新设置 TTL，整个集合的过期时间都会被延长。

如果业务要求每个元素独立过期，普通 Set 无法直接满足，可以考虑：

- 使用 ZSet，将过期时间戳作为 score；
- 定期删除 score 小于当前时间的元素；
- 为每个成员建立独立 Key；
- 根据具体业务选择其他数据模型。

## 8.3 TTL 返回值语义

执行：

```java
redisTemplate.getExpire(key, TimeUnit.SECONDS);
```

通常需要保留 Redis 原本的语义：

| 返回值 | 含义 |
| ---: | --- |
| `>= 0` | 剩余秒数 |
| `-1` | Key 存在但没有过期时间 |
| `-2` | Key 不存在 |

不要简单地把所有负数都转成 `null`，否则调用者无法区分“永久 Key”和“不存在的 Key”。

---

## 9. 按数据结构拆分 Operations

当一个 `RedisUtils` 同时包含大量 String、Hash、List、Set 和 ZSet 方法时，可以按数据结构拆分。

## 9.1 `RedisSetOperations`

```java
@Component
@RequiredArgsConstructor
public class RedisSetOperations {

    private final RedisTemplate<String, Object> redisTemplate;

    public long add(String key, Object... values) {
        Long addedCount = redisTemplate.opsForSet().add(key, values);
        return addedCount == null ? 0L : addedCount;
    }

    public long remove(String key, Object... values) {
        Long removedCount = redisTemplate.opsForSet().remove(key, values);
        return removedCount == null ? 0L : removedCount;
    }

    public boolean isMember(String key, Object value) {
        return Boolean.TRUE.equals(
                redisTemplate.opsForSet().isMember(key, value)
        );
    }

    public Set<Object> members(String key) {
        Set<Object> members = redisTemplate.opsForSet().members(key);
        return members == null ? Collections.emptySet() : members;
    }

    public long size(String key) {
        Long size = redisTemplate.opsForSet().size(key);
        return size == null ? 0L : size;
    }
}
```

## 9.2 `RedisZSetOperations`

```java
@Component
@RequiredArgsConstructor
public class RedisZSetOperations {

    private final RedisTemplate<String, Object> redisTemplate;

    public boolean add(String key, Object value, double score) {
        return Boolean.TRUE.equals(
                redisTemplate.opsForZSet().add(key, value, score)
        );
    }

    public Double incrementScore(
            String key,
            Object value,
            double delta
    ) {
        return redisTemplate.opsForZSet()
                .incrementScore(key, value, delta);
    }

    public Long reverseRank(String key, Object value) {
        return redisTemplate.opsForZSet()
                .reverseRank(key, value);
    }

    public Set<Object> reverseRange(
            String key,
            long start,
            long end
    ) {
        Set<Object> values = redisTemplate.opsForZSet()
                .reverseRange(key, start, end);

        return values == null
                ? Collections.emptySet()
                : values;
    }
}
```

## 9.3 什么时候值得拆分

满足以下情况之一时，可以考虑拆分：

- 单个 `RedisUtils` 已经明显过长；
- 某种数据结构的方法很多；
- 不同数据结构的序列化或操作规则不同；
- 多个开发者频繁修改同一个工具类；
- 类名已经无法准确表达职责；
- 单元测试难以按功能组织。

如果项目实际只使用少量 Value 操作，就没有必要提前创建所有 Operations 类。

---

## 10. 业务 Service 与 Redis 工具类的边界

判断代码应该放在哪里，可以使用两个问题：

```text
如何操作 Redis？
        → utils.redis

为什么操作 Redis、何时操作、业务结果如何判断？
        → 业务 Service
```

## 10.1 应该放在 Redis 工具包中的方法

```java
set(key, value, ttl)
get(key, type)
delete(key)
hasKey(key)
addToSet(key, values)
incrementZSetScore(key, member, delta)
```

这些方法只关心 Redis 数据结构和命令。

## 10.2 不应该放在 Redis 工具包中的方法

```java
saveLoginCode(phone, code)
verifyLoginCode(phone, code)
likeVideo(videoId, userId)
cacheVideoDetail(videoId)
calculateHotVideoRanking()
removeUserLoginState(userId)
```

这些方法已经包含业务含义，应进入对应 Service。

## 10.3 验证码业务示例

```java
@Service
@RequiredArgsConstructor
public class VerificationCodeService {

    private final RedisUtils redisUtils;
    private final RedisKeyGenerator redisKeyGenerator;
    private final RedisProperties redisProperties;

    public void saveLoginCode(String phone, String code) {
        String key = redisKeyGenerator.loginCode(phone);

        redisUtils.set(
                key,
                code,
                redisProperties.auth().loginCodeTtl()
        );
    }

    public boolean verifyLoginCode(
            String phone,
            String submittedCode
    ) {
        String key = redisKeyGenerator.loginCode(phone);
        String storedCode = redisUtils.get(key, String.class);

        if (!Objects.equals(storedCode, submittedCode)) {
            return false;
        }

        redisUtils.delete(key);
        return true;
    }
}
```

职责关系为：

```text
VerificationCodeService
    负责验证码业务规则
            ↓
RedisUtils
    负责执行通用 Redis 操作
            ↓
RedisTemplate
    负责与 Redis 通信和序列化
```

---

## 11. 不要隐藏危险操作

## 11.1 避免使用 `KEYS` 删除前缀 Key

下面的方法看起来很方便：

```java
Set<String> keys = redisTemplate.keys(prefix + "*");
redisTemplate.delete(keys);
```

但它底层相当于执行：

```redis
KEYS prefix*
```

`KEYS` 会遍历当前数据库中的 Key。数据量较大时，可能阻塞 Redis，影响其他请求。

生产环境中更适合：

- 使用 `SCAN` 分批扫描；
- 根据业务 ID 精确删除；
- 维护相关 Key 的索引集合；
- 使用缓存版本号切换；
- 使用 Spring Cache 的 CacheManager；
- 重新评估是否真的需要按前缀批量删除。

通用工具类中不应该提供一个看起来毫无成本的 `deleteByPrefix`，却隐藏其可能遍历全库的事实。

## 11.2 不要吞掉 Redis 异常

不推荐：

```java
try {
    return redisTemplate.opsForHash().entries(key);
} catch (Exception e) {
    e.printStackTrace();
    return null;
}
```

这样会让调用方无法区分：

- Key 不存在；
- Hash 为空；
- Redis 连接失败；
- 反序列化失败；
- 程序内部错误。

推荐做法是：

- 让 Redis 基础设施异常继续抛出；
- 或转换为项目统一的基础设施异常；
- 在合适的全局层次记录日志；
- 不要用 `null` 掩盖 Redis 服务故障。

---

## 12. 原子性与并发问题

## 12.1 多条 Redis 命令默认不是一个原子操作

例如：

```java
redisTemplate.opsForSet().add(key, value);
redisTemplate.expire(key, ttl);
```

实际执行的是：

```text
SADD
EXPIRE
```

如果第一条成功后应用崩溃，第二条可能没有执行，导致 Key 永不过期。

再例如：

```text
ZADD
ZCARD
ZREMRANGEBYRANK
```

并发请求可能在三条命令之间交叉执行，最终结果不一定满足“添加后集合长度必须不超过限制”。

## 12.2 需要原子性时的选择

根据场景可以考虑：

- 使用 Redis 原生原子命令；
- 使用 `SET key value NX EX`；
- 使用 Lua 脚本；
- 使用 Redis 事务；
- 使用 Spring Data Redis 提供的原子 API；
- 明确接受最终一致性。

是否需要原子性不是由工具类决定的，而是由业务一致性要求决定的。

## 12.3 验证码校验并删除

下面的实现不是严格原子的：

```java
String code = redisUtils.get(key, String.class);

if (Objects.equals(code, submittedCode)) {
    redisUtils.delete(key);
    return true;
}
```

两个并发请求可能同时读取到正确验证码，然后都验证成功。

如果业务要求验证码只能成功使用一次，应考虑使用 Lua 脚本完成：

```text
读取验证码
        ↓
比较验证码
        ↓
匹配则删除
```

让整个过程在 Redis 中原子执行。

---

## 13. 类型安全与返回值设计

## 13.1 不使用原始类型

不推荐：

```java
private RedisTemplate redisTemplate;
```

推荐：

```java
private final RedisTemplate<String, Object> redisTemplate;
```

或者在只操作字符串时使用：

```java
private final StringRedisTemplate stringRedisTemplate;
```

## 13.2 谨慎处理 `Boolean` 自动拆箱

Spring Data Redis 的部分方法返回 `Boolean`，理论上可能为 `null`。

不推荐：

```java
public boolean hasKey(String key) {
    return redisTemplate.hasKey(key);
}
```

推荐：

```java
public boolean hasKey(String key) {
    return Boolean.TRUE.equals(redisTemplate.hasKey(key));
}
```

## 13.3 不随意改变 Redis 原始语义

例如 `reverseRank` 在成员不存在时原本返回 `null`。工具类不应该悄悄改成返回集合大小，否则调用方可能误以为成员真的具有该排名。

通用组件应尽量：

- 保留底层命令的核心语义；
- 对特殊行为使用清晰的方法名；
- 在 JavaDoc 中说明空值、边界和下标规则；
- 不用模糊的默认值隐藏“不存在”。

---

## 14. 配置与环境变量

Redis 连接信息不应该硬编码在 Java 类中。

可以在配置文件中使用环境变量：

```yaml
spring:
  data:
    redis:
      host: ${REDIS_HOST:127.0.0.1}
      port: ${REDIS_PORT:6379}
      password: ${REDIS_PASSWORD:}
      database: ${REDIS_DATABASE:0}
      connect-timeout: 3s
      timeout: 3s
```

含义是：

- 本地没有环境变量时，默认连接 `127.0.0.1:6379`；
- 测试、生产或 Docker 环境可以通过环境变量覆盖；
- 密码不直接写死在代码中；
- 同一份程序可以适配不同环境。

当 Spring Boot 运行在 Windows、Redis 运行在 Docker 中时：

```text
REDIS_HOST=127.0.0.1
REDIS_PORT=6379
```

当 Spring Boot 和 Redis 都运行在同一个 Compose 网络中时：

```text
REDIS_HOST=redis
REDIS_PORT=6379
```

---

## 15. 测试建议

Redis 基础设施代码不能只依靠编译通过，还应该验证真实读写行为。

## 15.1 应测试的内容

至少包括：

- String 或 JSON Value 能否正确写入和读取；
- 自定义对象序列化后能否正确恢复；
- TTL 是否正确设置；
- Key 过期后是否无法读取；
- `hasKey` 和 `delete` 返回值是否符合预期；
- Set 是否能够添加、判断成员和删除成员；
- ZSet 分数、排名和范围查询是否正确；
- 不存在的 Key 如何返回；
- 类型不匹配时如何处理；
- Lua 脚本是否满足原子性要求。

## 15.2 测试环境

实际项目可以考虑：

- 本地 Docker Compose Redis；
- 测试专用 Redis 数据库；
- Testcontainers 启动临时 Redis 容器；
- 每个测试使用独立 Key 前缀；
- 测试结束后精确清理测试 Key。

如果希望测试更接近 CI 和团队开发环境，Testcontainers 通常比依赖开发者本机已启动的 Redis 更稳定。

## 15.3 不使用生产 Redis 运行测试

自动化测试不应该连接生产 Redis，也不应该通过 `FLUSHDB` 清理共享环境。

推荐为测试 Key 增加独立前缀：

```text
test:redis-utils:value:001
test:redis-utils:set:001
```

并在测试结束后精确删除。

---

## 16. 对大型 `RedisUtil` 的评价

一个同时包含 String、Hash、List、Set、ZSet、JSON、TTL、每日过期和每周过期方法的 `RedisUtil`，确实具有较强的通用性。

它的优点包括：

- Redis 操作集中；
- 调用代码简短；
- 常用数据结构覆盖全面；
- 开发者不必重复编写底层 API；
- 中小项目初期开发速度较快。

但也可能存在以下问题：

- 单个类职责过大；
- 大量使用 `Object` 和原始类型；
- 序列化方式混乱；
- 使用 `KEYS` 等危险命令；
- 多命令复合操作缺乏原子性；
- 业务时间策略逐渐混入工具类；
- 吞掉异常并返回 `null`；
- 工具类不断增长成为基础设施“上帝类”。

因此，正确的态度不是完全拒绝 `RedisUtil`，而是：

> 保留通用封装的价值，同时通过包结构、类型系统、序列化配置和职责边界控制它的复杂度。

---

## 17. “菲比啾比”的推荐落地步骤

建议按照以下顺序实施：

### 第一阶段：环境和基础配置

1. 使用 Docker Compose 启动 Redis；
2. 引入 `spring-boot-starter-data-redis`；
3. 配置连接参数和环境变量；
4. 明确 Key、Value 和 Hash 的序列化方式；
5. 编写 Redis 连接测试。

### 第二阶段：公共组件

1. 创建 `utils.redis` package；
2. 创建基础 `RedisUtils`；
3. 创建 `RedisKeyUtils`；
4. 按业务模块创建 Redis 常量类；
5. 使用构造器注入和明确泛型；
6. 为公共操作编写集成测试。

### 第三阶段：接入第一个真实业务

优先选择边界清晰的业务，例如：

- 登录验证码；
- Token 黑名单；
- 防止重复提交；
- 简单视频详情缓存。

接入过程中验证：

- Key 格式；
- TTL；
- 空值行为；
- 数据一致性；
- 异常行为；
- 是否需要原子操作。

### 第四阶段：按需要扩展

当实际出现需求后，再增加：

- `RedisSetOperations`；
- `RedisHashOperations`；
- `RedisZSetOperations`；
- Lua 脚本；
- Spring Cache；
- 分布式锁；
- 缓存一致性策略；
- 热点数据和排行榜。

不要复制大量当前项目用不到的方法，也不要为了保持一个类而拒绝合理拆分。

---

## 18. 最终设计原则

“菲比啾比”项目的 Redis 代码遵循以下原则：

```text
Redis 连接与序列化
        → RedisConfig

通用 Redis 命令封装
        → utils.redis

Key 生成规则
        → RedisKeyUtils

Key 前缀和默认 TTL
        → 各业务 RedisConstants

验证码、点赞、缓存、排行榜等业务规则
        → 对应业务 Service

必须保证不可分割的复合操作
        → Redis 原子命令或 Lua 脚本

真实读写、TTL、序列化和原子性验证
        → Redis 集成测试
```

可以使用一个通用的 `RedisUtils`，但要遵守以下边界：

1. 使用构造器注入；
2. 不使用原始类型 `RedisTemplate`；
3. 明确并统一序列化方式；
4. 优先使用 `Duration` 表达 TTL；
5. 不把业务方法塞入工具类；
6. 不隐藏 `KEYS` 等高成本操作；
7. 不吞掉 Redis 异常；
8. 多命令组合要考虑原子性；
9. 操作增多后按数据结构或职责拆分；
10. 为关键 Redis 行为编写集成测试。

最终目标不是追求目录层级越多越好，而是让代码同时具备：

- 可读性；
- 可维护性；
- 类型安全；
- 业务边界清晰；
- 环境可复现；
- 行为可以验证；
- 能够随着项目规模持续演进。
