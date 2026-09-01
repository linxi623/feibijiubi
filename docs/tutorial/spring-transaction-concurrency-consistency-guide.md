# Spring Boot 并发与数据一致性学习笔记

> 适用项目：菲比啾比后端  
> 适合读者：正在学习 Java、Spring Boot、MyBatis 和 MySQL 的后端初学者

## 1. 学习目标

在“菲比啾比”中，下面这些操作都涉及多个数据之间的一致性：

- 用户点赞或取消点赞视频；
- 用户点踩或取消点踩视频；
- 用户收藏或取消收藏视频；
- 用户给视频投币，同时扣减自己的硬币余额；
- 视频统计表中的点赞数、点踩数、收藏数和投币数随之变化。

以投币为例，一次请求可能需要修改三份数据：

1. `users.coin`：扣减用户余额；
2. `user_video.coin`：记录该用户给该视频投了多少币；
3. `video_status.coin_times`：增加视频总投币数。

这些操作既要避免“执行一半”，也要应对重复请求和并发请求。本笔记按照以下顺序整理相关知识：

1. `@Transactional` 的提交和回滚；
2. SQL 条件更新和 affected rows；
3. MySQL 行锁与 `SELECT ... FOR UPDATE`；
4. 事务隔离级别；
5. 乐观锁 `version` 字段；
6. Redis 分布式锁；
7. 消息队列和最终一致性。

建议先掌握 MySQL 事务和原子 SQL，再学习 Redis 和消息队列。后两者不是数据库事务的替代品。

---

# 2. `@Transactional` 的提交和回滚

## 2.1 什么是事务

事务是一组不可分割的数据库操作。这组操作要么全部成功，要么全部失败。

事务通常具有 ACID 四个特性：

| 特性 | 英文 | 含义 |
|---|---|---|
| 原子性 | Atomicity | 一组操作要么全部成功，要么全部回滚 |
| 一致性 | Consistency | 事务执行前后，数据都满足业务规则和数据库约束 |
| 隔离性 | Isolation | 并发事务之间尽量互不干扰 |
| 持久性 | Durability | 事务提交后，结果会被持久保存 |

## 2.2 为什么投币需要事务

假设投币执行以下 SQL：

```text
1. video_status.coin_times + 1
2. users.coin - 1
3. user_video.coin = 1
```

如果第 1、2 步成功，第 3 步失败，而前面的修改没有回滚，就会出现：

- 用户的硬币已经被扣除；
- 视频总投币数已经增加；
- 但用户视频关系表没有记录这次投币。

这就是数据不一致。

## 2.3 Spring Boot 中使用 `@Transactional`

```java
@Transactional(rollbackFor = Exception.class)
public void increaseCoin(Integer currentUserId, Integer vid, Byte coin) {
    // 查询和校验
    // 增加视频投币统计
    // 扣减用户硬币
    // 保存用户投币记录
}
```

正常执行结束后，Spring 提交事务；执行期间抛出需要回滚的异常时，Spring 回滚事务。

## 2.4 默认回滚规则

Spring 默认会对以下异常回滚：

- `RuntimeException`；
- `Error`。

默认不会对普通受检异常自动回滚。初学阶段可以明确声明：

```java
@Transactional(rollbackFor = Exception.class)
```

项目中的 `BusinessException` 如果继承 `RuntimeException`，即使不写 `rollbackFor`，通常也会触发回滚；显式写出可以让意图更清楚。

## 2.5 常见失效场景

### 场景一：同类内部调用

```java
@Service
public class VideoServiceImpl {

    public void methodA() {
        methodB();
    }

    @Transactional
    public void methodB() {
        // 数据库操作
    }
}
```

如果 `methodA()` 直接调用同一个对象中的 `methodB()`，调用可能没有经过 Spring 代理，事务可能不生效。

更规范的做法是：

- 将事务边界放到外部真正调用的 Service 公共方法上；
- 或者根据职责拆分到另一个 Service Bean；
- 不要为了强行调用代理而滥用自注入。

### 场景二：异常被吃掉

```java
@Transactional
public void increaseCoin(...) {
    try {
        userMapper.decreaseCoin(...);
    } catch (Exception e) {
        log.error("扣币失败", e);
        // 没有继续抛出异常
    }
}
```

如果异常被捕获后没有继续抛出，Spring 可能认为方法正常结束，从而提交事务。

应该让异常继续传播：

```java
catch (Exception e) {
    log.error("扣币失败", e);
    throw e;
}
```

### 场景三：方法不是可代理的公共方法

通常应把事务标注在 Spring Bean 的 `public` Service 方法上。不要把关键事务只放在 `private` 方法上。

### 场景四：修改了非事务资源

数据库事务只能直接回滚数据库操作，不能自动撤销：

- 已经上传到对象存储的文件；
- 已经发送出去的 HTTP 请求；
- 已经发出的短信；
- 已经发布到外部系统的消息。

这类资源需要补偿操作或更完整的一致性方案。

## 2.6 `@Transactional` 能解决什么，不能解决什么

它能解决：

- 同一次请求内执行一半的问题；
- 后续 SQL 失败时，回滚前面的数据库修改。

它不能单独解决：

- 两个并发请求同时读取旧数据；
- 重复请求造成重复计数；
- 跨多个独立数据库的分布式事务；
- 数据库与 Redis、MQ、对象存储之间的自动一致性。

例如两个事务可能同时读到 `liked=false`，然后都把点赞数加一。两个事务内部都完整提交，但整体结果仍然错误。

---

# 3. SQL 条件更新和 affected rows

## 3.1 什么是 affected rows

执行 `INSERT`、`UPDATE` 或 `DELETE` 后，数据库会返回受影响的行数。

MyBatis Mapper 可以使用 `int` 接收：

```java
int decreaseCoin(@Param("currentUserId") Integer currentUserId,
                 @Param("coin") Byte coin);
```

调用后：

```java
int rows = userMapper.decreaseCoin(currentUserId, coin);
```

常见含义：

- `rows == 1`：符合条件的一行被更新；
- `rows == 0`：没有记录满足条件；
- `rows > 1`：在本应只更新一行的业务中，通常说明 SQL 条件或数据约束有问题。

## 3.2 错误的“先查询再更新”

```java
User user = userMapper.selectById(currentUserId);

if (user.getCoin() < coin) {
    throw new BusinessException(400, "硬币不足");
}

userMapper.decreaseCoin(currentUserId, coin);
```

顺序执行时没有明显问题，但并发时可能发生：

```text
用户余额 = 1

请求 A 查询到 1
请求 B 查询到 1
请求 A 判断足够
请求 B 判断也足够
请求 A 扣减 1
请求 B 再扣减 1
最终余额 = -1
```

问题在于“判断”和“更新”是两条 SQL，不是一个原子操作。

## 3.3 使用条件 UPDATE

```xml
<update id="decreaseCoin">
    UPDATE users
    SET coin = coin - #{coin}
    WHERE id = #{currentUserId}
      AND coin >= #{coin}
      AND deleted_at IS NULL
</update>
```

Service 根据受影响行数判断：

```java
int userRows = userMapper.decreaseCoin(currentUserId, coin);
if (userRows != 1) {
    throw new BusinessException(400, "硬币数量不足");
}
```

这条 SQL 会在数据库内部完成：

```text
检查 coin >= 投币数 + 扣减余额
```

并发请求执行时，其中一个请求扣减成功后，另一个请求会重新判断条件。如果余额不足，第二次更新影响 0 行。

## 3.4 条件更新实现状态切换

例如只有当前未收藏时才收藏：

```sql
UPDATE user_video
SET collect = 1
WHERE uid = ?
  AND vid = ?
  AND collect = 0;
```

只有当前已收藏时才能取消：

```sql
UPDATE user_video
SET collect = 0
WHERE uid = ?
  AND vid = ?
  AND collect = 1;
```

Java 判断：

```java
int rows = userVideoMapper.setCollect(currentUserId, vid, isCollect);
if (rows == 0) {
    // 状态本来就是目标值，可以按幂等成功处理
    return;
}
```

但要注意：如果随后还要更新 `video_status.collect_times`，需要在同一事务中保证两者一致。并发场景下，还要设计好“关系状态改变”和“统计数改变”的顺序。

## 3.5 affected rows 的注意事项

受影响行数的具体表现可能受数据库和驱动配置影响。例如某些场景会区分：

- 匹配到多少行；
- 实际值发生变化的有多少行。

因此最好让 SQL 的 `WHERE` 明确表达业务条件，而不是仅依赖“更新为相同值时数据库返回几行”。

例如：

```sql
WHERE collect <> #{isCollect}
```

比无条件更新后猜测 affected rows 的含义更清楚。

---

# 4. MySQL 行锁与 `SELECT ... FOR UPDATE`

## 4.1 为什么普通 SELECT 不够

当前点赞逻辑大致是：

```text
1. 查询 user_video.liked
2. 判断状态是否变化
3. 更新 user_video
4. 更新 video_status.like_times
```

并发时：

```text
请求 A 查询 liked=false
请求 B 查询 liked=false
请求 A 判断需要增加点赞
请求 B 判断也需要增加点赞
```

普通 `SELECT` 不会阻止另一个事务同时读取旧状态。

## 4.2 `SELECT ... FOR UPDATE`

```sql
SELECT
    id,
    uid,
    vid,
    liked,
    unliked,
    coin,
    collect
FROM user_video
WHERE uid = ?
  AND vid = ?
FOR UPDATE;
```

这叫锁定读。它会对查询到的记录加排他锁，锁通常持续到当前事务提交或回滚。

## 4.3 MyBatis 示例

Mapper：

```java
UserVideo selectByUidAndVidForUpdate(
        @Param("uid") Integer uid,
        @Param("vid") Integer vid
);
```

XML：

```xml
<select id="selectByUidAndVidForUpdate"
        resultType="com.feibijiubi.backend.entity.UserVideo">
    SELECT
        id,
        vid,
        uid,
        play_time,
        liked,
        unliked,
        coin,
        collect,
        played_at,
        liked_at,
        coined_at
    FROM user_video
    WHERE uid = #{uid}
      AND vid = #{vid}
    FOR UPDATE
</select>
```

Service：

```java
@Transactional(rollbackFor = Exception.class)
public void recordLike(Integer uid,
                       Integer vid,
                       Boolean isLike,
                       Boolean isSet) {
    userVideoMapper.ensureExists(uid, vid);

    UserVideo userVideo =
            userVideoMapper.selectByUidAndVidForUpdate(uid, vid);

    Boolean currentState = isLike
            ? userVideo.getLiked()
            : userVideo.getUnliked();

    if (isSet.equals(currentState)) {
        return;
    }

    // 更新用户状态和统计数
}
```

## 4.4 两个并发点赞请求如何执行

```text
请求 A 开启事务并锁住 (uid=7, vid=42)
请求 B 尝试锁同一行，进入等待
请求 A 把 liked 改成 true，点赞数 +1，然后提交
请求 B 获得锁并读取最新状态 liked=true
请求 B 发现已经是目标状态，直接返回
```

最终只增加一次点赞数。

## 4.5 `FOR UPDATE` 必须放在事务中

如果没有显式事务，查询执行后连接可能很快提交，锁也会立即释放，无法覆盖后续业务操作。

因此它通常需要和下面的代码配合：

```java
@Transactional
```

## 4.6 索引非常重要

当前 `user_video` 应有唯一索引：

```sql
UNIQUE KEY uk_user_video_uid_vid (uid, vid)
```

查询条件：

```sql
WHERE uid = ? AND vid = ?
```

可以精确定位一行。

如果锁定查询没有合适索引，MySQL 可能扫描并锁定更多记录或范围，降低并发性能，甚至增加死锁风险。

## 4.7 死锁

如果两个事务以不同顺序获取多把锁，可能发生死锁：

```text
事务 A 已锁用户，等待视频关系
事务 B 已锁视频关系，等待用户
```

MySQL 会检测死锁并回滚其中一个事务。

减少死锁的方法：

1. 所有业务按一致顺序加锁；
2. 事务尽量短；
3. 不要在持锁期间执行网络请求或耗时任务；
4. 使用合适索引，减少锁范围；
5. 对可重试的死锁异常设计有限次数重试。

## 4.8 悲观锁适合什么场景

适合：

- 数据冲突概率较高；
- 核心余额或库存不能出错；
- 一次事务执行时间较短；
- 可以接受竞争请求等待。

不适合：

- 冲突很少，却有大量请求；
- 事务中存在长时间操作；
- 业务跨越多个独立系统，数据库锁覆盖不到全部资源。

---

# 5. 事务隔离级别

## 5.1 为什么需要隔离级别

多个事务并发执行时，数据库需要决定：

- 一个事务能否看到另一个事务未提交的数据；
- 同一事务重复查询时，结果是否必须保持一致；
- 新插入的数据是否会突然出现在查询结果中。

隔离越强，异常现象越少，但并发性能和等待成本可能越高。

## 5.2 四种标准隔离级别

| 隔离级别 | 脏读 | 不可重复读 | 幻读 |
|---|---:|---:|---:|
| READ UNCOMMITTED | 可能 | 可能 | 可能 |
| READ COMMITTED | 避免 | 可能 | 可能 |
| REPEATABLE READ | 避免 | 避免 | 标准定义下仍需关注；InnoDB 有额外机制处理许多场景 |
| SERIALIZABLE | 避免 | 避免 | 避免，但并发代价最高 |

## 5.3 三种常见并发异常

### 脏读

事务 A 修改余额但尚未提交，事务 B 读取到了这个值。随后 A 回滚，B 读到的是从未真正生效的数据。

### 不可重复读

事务 A 第一次查询余额是 10；事务 B 修改并提交后，事务 A 第二次查询变成 8。

### 幻读

事务 A 查询“所有收藏了视频 42 的用户”得到 10 行；事务 B 新增一条收藏并提交；事务 A 再次查询得到 11 行。

## 5.4 MySQL InnoDB 的默认级别

MySQL InnoDB 通常默认使用：

```text
REPEATABLE READ
```

普通快照读常通过 MVCC 提供一致性视图；`SELECT ... FOR UPDATE` 属于锁定读，行为与普通快照读不同，通常会读取当前可锁定的最新记录。

## 5.5 隔离级别不能代替业务锁

即使使用 `REPEATABLE READ`，两个事务仍可能分别读取到相同的旧业务状态，然后执行冲突更新。

所以不要认为：

```text
使用了事务 + 默认隔离级别 = 所有并发问题自动消失
```

对于点赞、收藏、投币，要根据业务选择：

- 条件更新；
- `FOR UPDATE`；
- 乐观锁；
- 唯一约束；
- 或它们的组合。

## 5.6 Spring 中指定隔离级别

```java
@Transactional(
        isolation = Isolation.READ_COMMITTED,
        rollbackFor = Exception.class
)
public void someBusinessMethod() {
}
```

除非理解数据库行为和业务要求，否则不要随意把所有事务改成 `SERIALIZABLE`。更强的隔离级别可能带来：

- 更多等待；
- 更低吞吐量；
- 更多锁冲突；
- 更高的死锁概率。

初学阶段通常先使用数据库默认值，再针对具体问题使用条件更新或锁定读。

---

# 6. 乐观锁 `version` 字段

## 6.1 什么是乐观锁

悲观锁认为冲突经常发生，因此先锁住记录再修改。

乐观锁认为冲突不常发生，因此允许多个请求先读取；更新时检查记录是否仍是自己读取时的版本。

它通常不是真的“加锁”，而是使用条件更新检测并发冲突。

## 6.2 表结构增加 `version`

```sql
ALTER TABLE user_video
ADD COLUMN version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号';
```

实体类增加：

```java
private Integer version;
```

## 6.3 更新 SQL

```sql
UPDATE user_video
SET liked = ?,
    version = version + 1
WHERE uid = ?
  AND vid = ?
  AND version = ?;
```

假设两个请求都读取到：

```text
liked=false, version=3
```

请求 A 更新：

```text
WHERE version=3
```

成功后版本变成 4。

请求 B 仍然使用：

```text
WHERE version=3
```

数据库中已经是 4，因此 B 影响 0 行。程序据此知道发生了并发冲突。

## 6.4 MyBatis 示例

```xml
<update id="updateLikeWithVersion">
    UPDATE user_video
    SET liked = #{liked},
        unliked = #{unliked},
        liked_at = #{likedAt},
        version = version + 1
    WHERE uid = #{uid}
      AND vid = #{vid}
      AND version = #{version}
</update>
```

Java：

```java
int rows = userVideoMapper.updateLikeWithVersion(userVideo);
if (rows == 0) {
    throw new BusinessException(409, "操作冲突，请重试");
}
```

## 6.5 冲突后怎么办

常见策略：

### 策略一：返回冲突

返回 409，让客户端重新获取状态后再操作。

适合不能自动判断用户最终意图的业务。

### 策略二：有限重试

重新查询最新数据，再判断是否仍需修改：

```text
最多重试 2～3 次
```

不要无限重试，否则高冲突时会形成重试风暴。

### 策略三：发现已经达到目标状态，按成功返回

例如用户要求 `liked=true`，重试时发现另一个并发请求已经将其改为 `true`，可以将本次请求视为幂等成功。

## 6.6 乐观锁和悲观锁如何选择

| 比较项 | 悲观锁 | 乐观锁 |
|---|---|---|
| 冲突处理 | 先等待锁 | 更新时检测版本冲突 |
| 冲突频率 | 适合较高冲突 | 适合较低冲突 |
| 数据库连接占用 | 等待期间占用事务与连接 | 通常等待较少，但可能重试 |
| 实现难度 | 事务和锁顺序要谨慎 | 要维护 version 和冲突策略 |
| 用户体验 | 请求可能等待 | 请求可能收到冲突或自动重试 |

对“同一用户对同一视频点赞”来说，冲突通常不高，乐观锁是可选方案；对余额、库存等严格且高价值的数据，条件更新或悲观锁往往更直接。

---

# 7. Redis 分布式锁

## 7.1 为什么叫分布式锁

如果应用只有一个实例，可以使用 JVM 内部锁：

```java
synchronized
```

但生产环境通常会部署多个后端实例：

```text
请求 A → 后端实例 1
请求 B → 后端实例 2
```

实例 1 的 JVM 锁无法阻止实例 2。此时需要所有实例都能访问的协调系统，例如 Redis。

## 7.2 基本思想

为业务资源设计锁 key：

```text
lock:user-video:{uid}:{vid}
```

例如：

```text
lock:user-video:7:42
```

只有成功获取锁的请求才能修改用户 7 与视频 42 的关系。

## 7.3 不要用简单的 `SETNX` 加 `DEL`

不完整示例：

```text
SETNX lock:key 1
执行业务
DEL lock:key
```

它存在很多问题：

- 业务进程崩溃后锁不会释放；
- 锁过期后，旧请求可能删除新请求的锁；
- 业务执行时间超过锁有效期；
- 获取锁和设置过期时间不是一个原子操作。

## 7.4 正确获取锁的基本要求

Redis 命令应具有类似语义：

```text
SET lock:key unique-request-id NX PX 10000
```

含义：

- `NX`：只有 key 不存在时才设置；
- `PX 10000`：锁 10 秒后自动过期；
- value 使用当前持有者唯一标识。

释放锁时，必须先确认锁仍属于自己，再删除。检查和删除需要通过 Lua 脚本原子完成。

## 7.5 Java 中优先使用成熟客户端

实际项目中更推荐使用 Redisson：

```java
RLock lock = redissonClient.getLock(
        "lock:user-video:" + uid + ":" + vid
);

boolean locked = lock.tryLock(1, 10, TimeUnit.SECONDS);
if (!locked) {
    throw new BusinessException(429, "操作过于频繁，请稍后重试");
}

try {
    // 数据库事务业务
} finally {
    if (lock.isHeldByCurrentThread()) {
        lock.unlock();
    }
}
```

即使使用成熟组件，也要理解：

- 获取锁超时；
- 锁租约；
- 看门狗续期；
- Redis 故障；
- 网络分区；
- 锁与数据库事务之间的边界。

## 7.6 Redis 锁不能代替数据库约束

即使使用 Redis 锁，数据库仍应该保留最后一道防线：

- `UNIQUE(uid, vid)`；
- `coin >= 0`；
- 条件更新 `coin >= #{coin}`；
- 合理的非空和外键约束。

原因包括：

- 代码可能遗漏加锁；
- 锁可能过期；
- 运维脚本可能绕过应用；
- Redis 可能发生故障；
- 其他服务可能直接访问数据库。

正确思路是：

```text
Redis 锁减少并发冲突
数据库约束和原子 SQL 保证最终底线
```

## 7.7 当前项目是否需要 Redis 锁

菲比啾比当前学习阶段不必马上引入 Redis 分布式锁。

优先使用：

1. `@Transactional`；
2. 条件更新；
3. affected rows；
4. 唯一约束；
5. 必要时 `SELECT ... FOR UPDATE`。

当项目部署多个实例、热点竞争明显，或者业务锁需要覆盖数据库之外的资源时，再认真评估 Redis 分布式锁。

---

# 8. 消息队列和最终一致性

## 8.1 什么是消息队列

消息队列（MQ）用于在系统之间异步传递事件：

```text
生产者 → 消息队列 → 消费者
```

常见产品包括：

- RabbitMQ；
- RocketMQ；
- Kafka。

例如播放量统计：

```text
用户播放视频
    ↓
后端发送 VideoPlayedEvent
    ↓
消费者异步增加播放量
```

HTTP 请求不必等待所有后续工作完成。

## 8.2 为什么需要异步处理

异步处理可以用于：

- 削峰：大量请求先进入队列，消费者按能力处理；
- 解耦：投稿服务不必直接调用所有通知服务；
- 提高响应速度：非核心操作放到后台；
- 广播业务事件：多个消费者分别处理统计、通知、推荐等任务。

## 8.3 什么是最终一致性

强一致性要求操作完成后，所有相关数据立即一致。

最终一致性允许短时间不一致，但系统经过重试后最终达到正确状态。

例如：

```text
用户点赞成功
user_video.liked 立即更新
点赞统计通过 MQ 稍后更新
```

此时用户可能短暂看到点赞状态已变，但总点赞数尚未变化。只要消息最终被消费，统计会达到一致。

## 8.4 为什么“数据库提交后再发消息”仍有问题

```java
@Transactional
public void likeVideo(...) {
    updateDatabase();
}

messageProducer.send(event);
```

可能发生：

```text
数据库提交成功
应用在发送消息前宕机
```

结果：业务数据成功，但消息永远没有发送。

反过来，先发消息再提交数据库也有问题：

```text
消息发送成功
数据库事务回滚
消费者却认为业务成功
```

这就是数据库与消息队列的双写一致性问题。

## 8.5 本地消息表 / Outbox Pattern

一种常见方案是在同一个数据库事务中写入：

1. 业务数据；
2. 待发送事件表。

示例：

```sql
CREATE TABLE outbox_event (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    event_id VARCHAR(64) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    payload JSON NOT NULL,
    status TINYINT NOT NULL DEFAULT 0,
    retry_count INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL,
    sent_at DATETIME NULL,
    UNIQUE KEY uk_outbox_event_id (event_id)
);
```

事务中：

```text
更新用户点赞状态
插入一条 VideoLikedEvent
提交事务
```

后台任务扫描未发送事件并发送到 MQ。发送成功后，将事件标记为已发送。

这样业务更新和“必须发送的事件记录”由同一个数据库事务保证。

## 8.6 消息一定只消费一次吗

工程上通常很难保证绝对的 exactly once。更常见的是：

```text
at least once：消息至少投递一次，可能重复
```

因此消费者必须具有幂等性。

## 8.7 消费者幂等

每条消息带唯一 `eventId`：

```json
{
  "eventId": "f7d2...",
  "eventType": "VIDEO_LIKED",
  "uid": 7,
  "vid": 42
}
```

消费者处理前检查：

```text
该 eventId 是否已经处理？
```

可以创建消费记录表：

```sql
CREATE TABLE consumed_event (
    event_id VARCHAR(64) PRIMARY KEY,
    consumed_at DATETIME NOT NULL
);
```

在同一个本地事务中：

```text
插入 consumed_event
执行统计更新
提交
```

如果消息重复到达，插入唯一键冲突，消费者知道该消息已经处理过。

## 8.8 重试、死信和补偿

消息消费失败后不能无限快速重试，否则可能压垮系统。

通常需要：

- 有限次数重试；
- 指数退避；
- 死信队列；
- 监控和告警；
- 人工补偿工具；
- 定期数据对账。

例如视频点赞统计可以通过定时对账修复：

```sql
SELECT vid, COUNT(*)
FROM user_video
WHERE liked = 1
GROUP BY vid;
```

再与 `video_status.like_times` 比较。

## 8.9 哪些操作适合 MQ

比较适合：

- 播放量等高频统计；
- 点赞后的通知；
- 投稿后的审核任务；
- 视频处理、转码；
- 推荐系统事件；
- 操作日志和数据分析。

不建议在初学阶段只靠异步消息处理：

- 用户余额扣减；
- 同一视频只能投一次；
- 立即需要返回成功或失败的强一致性操作。

投币主流程更适合先由 MySQL 事务保证强一致性，事务成功后再异步发送“用户已投币”事件，用于通知、推荐或数据分析。

---

# 9. 菲比啾比中的推荐实践

## 9.1 点赞和点踩

项目规则：点赞和点踩是两个独立状态，可以同时为 `true`。

当前阶段建议：

1. `user_video` 分别保存 `liked`、`unliked`；
2. 删除禁止二者同时为真的数据库约束；
3. 状态没有变化时按幂等成功处理；
4. 用户状态和统计数在同一个事务中更新；
5. 学习并发后，为关系行使用条件更新、乐观锁或 `FOR UPDATE`；
6. 后期可增加定时对账，重新计算统计数。

不要把“允许同时点赞和点踩”误解为“两个统计一起增加”。每次请求仍然只修改目标状态：

```text
isLike=true  → 操作 liked / like_times
isLike=false → 操作 unliked / unlike_times
```

## 9.2 收藏

当前阶段建议：

1. 确保 `user_video(uid, vid)` 唯一；
2. 状态未变化时直接返回；
3. 状态变化时更新 `user_video.collect`；
4. 同一事务内更新 `video_status.collect_times`；
5. 并发阶段可使用 `FOR UPDATE` 或条件更新。

## 9.3 投币

投币比点赞和收藏更严格，因为它涉及用户资产。

推荐顺序：

1. 校验投币数只能是 1 或 2；
2. 开启数据库事务；
3. 创建并锁定 `user_video` 关系行；
4. 判断是否已经投币；
5. 使用带 `coin >= #{coin}` 的条件 SQL 扣减余额；
6. 根据 affected rows 判断余额是否足够；
7. 保存用户投币记录；
8. 增加视频投币统计；
9. 任一步失败，整个事务回滚。

Redis 和 MQ 不应该成为防止余额变负数的唯一措施。

## 9.4 播放量

播放量通常允许一定延迟，不像余额那样要求立即强一致，因此未来可以考虑：

```text
请求先写 Redis 计数
定时批量同步 MySQL
```

或者：

```text
发送播放事件到 MQ
消费者批量统计
```

但需要考虑：

- 消息重复；
- 消息丢失；
- 幂等消费；
- Redis 数据持久化；
- 定期对账。

---

# 10. 方案选择速查表

| 问题 | 优先方案 | 原因 |
|---|---|---|
| 一次请求执行一半 | `@Transactional` | 保证同一数据库内一起提交或回滚 |
| 余额不能扣成负数 | 条件 UPDATE + affected rows | 判断和扣减在一条 SQL 内原子完成 |
| 同一用户不能重复投币 | 唯一关系 + 行锁/条件更新 | 避免并发请求同时通过旧状态检查 |
| 点赞重复请求不重复计数 | 幂等状态判断 | 已经是目标状态时直接返回 |
| 并发点赞仍重复计数 | `FOR UPDATE` 或乐观锁 | 串行化冲突操作或检测版本冲突 |
| 多实例需要跨进程协调 | Redis 分布式锁 | JVM 锁无法跨实例生效 |
| 高频播放统计 | Redis/MQ + 批量落库 | 降低数据库即时写压力 |
| 数据库与 MQ 保持一致 | Outbox + 幂等消费 | 避免数据库成功但消息丢失 |
| 统计长期漂移 | 定期对账与修复 | 为统计数据提供最终兜底 |

---

# 11. 推荐学习与实践顺序

## 第一阶段：事务基础

需要掌握：

- `@Transactional` 的提交和回滚；
- RuntimeException 与受检异常；
- 同类调用导致事务代理失效；
- 不要吞掉异常；
- 数据库事务不能自动回滚外部资源。

练习：故意让投币流程最后一步失败，确认余额和视频统计都回滚。

## 第二阶段：原子 SQL

需要掌握：

- 条件 `UPDATE`；
- affected rows；
- 唯一约束；
- 幂等接口。

练习：将扣减余额改成：

```sql
AND coin >= #{coin}
```

确认硬币不足时 affected rows 为 0。

## 第三阶段：数据库并发控制

需要掌握：

- `SELECT ... FOR UPDATE`；
- 行锁和索引；
- 死锁；
- 锁顺序；
- 事务尽量短。

练习：使用两个数据库连接或并发测试，同时操作同一个 `user_video` 记录，观察第二个事务等待。

## 第四阶段：隔离级别与 MVCC

需要掌握：

- 四种隔离级别；
- 脏读、不可重复读、幻读；
- 快照读与当前读；
- InnoDB 默认隔离级别。

练习：在两个 MySQL 会话中手动开启事务，观察不同隔离级别下重复查询结果。

## 第五阶段：乐观锁

需要掌握：

- `version` 字段；
- `WHERE version = ?`；
- affected rows 为 0 代表冲突；
- 有限重试与 409 响应。

练习：让两个请求使用同一个 version 更新记录，确认只能有一个成功。

## 第六阶段：Redis 分布式锁

需要掌握：

- JVM 锁为什么不能跨实例；
- 锁 key 粒度；
- 唯一持有者标识；
- 锁过期、续期和安全释放；
- Redisson；
- Redis 锁不能替代数据库约束。

练习：先在测试项目中实现锁，不要直接用核心余额业务作为第一个练习。

## 第七阶段：消息队列与最终一致性

需要掌握：

- 生产者和消费者；
- 至少一次投递；
- 消费幂等；
- 重试和死信队列；
- Outbox Pattern；
- 定期对账和人工补偿。

练习：将“点赞后发送通知”做成异步事件，但点赞主事务仍由 MySQL 保证。

---

# 12. 核心结论

1. `@Transactional` 保证一次请求内的数据库操作一起提交或回滚，但不自动解决所有并发问题。
2. 余额、库存等数值扣减，优先使用条件 SQL 和 affected rows，而不是单纯“先查再改”。
3. `SELECT ... FOR UPDATE` 可以让冲突事务按顺序处理，但必须配合事务和合适索引。
4. 事务隔离级别描述并发事务的可见性，不等于自动满足所有业务规则。
5. 乐观锁通过 `version` 检测并发冲突，适合冲突概率较低的业务。
6. Redis 分布式锁用于多实例协调，但数据库仍要保留原子 SQL、唯一约束等最终防线。
7. 消息队列适合异步、削峰和解耦；消费者必须幂等，数据库与 MQ 的一致性需要 Outbox 等方案。
8. 对菲比啾比当前阶段而言，优先学好 MySQL 事务、条件更新、affected rows 和行锁，再引入 Redis 与 MQ。
