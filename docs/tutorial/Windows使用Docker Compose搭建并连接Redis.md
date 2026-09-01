# Windows 使用 Docker Compose 搭建并连接 Redis

## 1. 学习目标

这一阶段先不要直接修改登录、点赞、视频等核心业务，而是完成以下目标：

- 能使用 Docker Compose 启动 Redis；
- 能从 Windows 连接 Redis；
- 会执行常用 Redis 命令；
- 理解 Key、Value 和 TTL；
- 能在 Spring Boot 中使用 `StringRedisTemplate` 读写 Redis。

整体连接关系如下：

```text
Spring Boot / RedisInsight / redis-cli（Windows）
                    │
                    │ localhost:6379
                    ▼
           Docker 中的 Redis 容器
```

---

## 2. 为什么推荐 Docker Compose

推荐使用 Docker Compose 管理 Redis，主要有以下原因：

- 开发环境容易复现；
- 不会把 Redis 的运行环境直接安装到 Windows 中；
- Redis 的启动、停止和删除方式比较统一；
- 后续可以把 RabbitMQ、MySQL 等中间件放入同一个 Compose 文件；
- 其他人拿到“菲比啾比”项目后，更容易快速启动所需环境。

如果暂时不会 Docker，也可以先在 Windows 或 WSL 中安装 Redis，但后续仍建议学习 Docker Compose。

---

## 3. 在 Windows 中安装 Docker Desktop

Docker Desktop 下载地址：

<https://www.docker.com/products/docker-desktop/>

安装时建议使用：

- WSL 2 后端；
- Linux Containers；
- Docker Compose。

安装完成后，打开 Docker Desktop，等待 Docker Engine 正常运行。

然后在 PowerShell 或 Git Bash 中执行：

```bash
docker --version
docker compose version
```

如果都能显示版本号，说明 Docker 和 Docker Compose 已经可以正常使用。

示例：

```text
Docker version 27.x.x
Docker Compose version v2.x.x
```

---

## 4. 创建 Redis Compose 文件

建议在“菲比啾比”后端项目根目录创建 `compose.yaml`：

```text
backend/
├── compose.yaml
├── pom.xml
├── src/
└── docs/
```

在 `compose.yaml` 中写入：

```yaml
services:
  redis:
    image: redis:7.4-alpine
    container_name: feibijiubi-redis
    restart: unless-stopped
    ports:
      - "127.0.0.1:6379:6379"
    volumes:
      - redis-data:/data
    command:
      - redis-server
      - --appendonly
      - "yes"
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 5s
      timeout: 3s
      retries: 5

volumes:
  redis-data:
```

### 4.1 `image`

```yaml
image: redis:7.4-alpine
```

表示使用 Redis 7.4 的 Alpine Linux 轻量镜像。

### 4.2 `ports`

```yaml
ports:
  - "127.0.0.1:6379:6379"
```

端口映射格式可以理解为：

```text
Windows 地址和端口:容器端口
```

这里表示：

```text
Windows 的 127.0.0.1:6379
              ↓
Redis 容器的 6379
```

指定 `127.0.0.1` 后，Redis 端口只绑定到本机，不会直接暴露给局域网中的其他设备。

### 4.3 `volumes`

```yaml
volumes:
  - redis-data:/data
```

Redis 数据会保存在 Docker Volume 中。重新创建容器时，只要没有删除数据卷，原来的数据就可以继续保留。

### 4.4 `appendonly`

```yaml
command:
  - redis-server
  - --appendonly
  - "yes"
```

这会启用 Redis 的 AOF 持久化。

当前配置用于本地学习，因此暂时不设置密码。生产环境不能直接照搬这份配置。

### 4.5 `healthcheck`

```yaml
healthcheck:
  test: ["CMD", "redis-cli", "ping"]
  interval: 5s
  timeout: 3s
  retries: 5
```

Docker 会定期在容器中执行 `redis-cli ping`，用于判断 Redis 是否已经能够正常响应。

---

## 5. 启动 Redis

在 `compose.yaml` 所在目录执行：

```bash
docker compose up -d
```

参数说明：

- `up`：创建并启动 Compose 中定义的服务；
- `-d`：让服务在后台运行。

查看容器状态：

```bash
docker compose ps
```

正常情况下可以看到类似结果：

```text
NAME                 IMAGE                STATUS
feibijiubi-redis     redis:7.4-alpine     Up (healthy)
```

查看 Redis 日志：

```bash
docker compose logs redis
```

持续查看日志：

```bash
docker compose logs -f redis
```

按 `Ctrl + C` 可以退出持续日志查看，不会停止 Redis 容器。

---

## 6. 从 Windows 连接 Redis

### 6.1 方法一：使用容器中的 `redis-cli`

这是最简单的方法，不需要在 Windows 中额外安装 Redis 客户端。

在 `compose.yaml` 所在目录执行：

```bash
docker compose exec redis redis-cli
```

进入 Redis 命令行后执行：

```redis
PING
```

正常结果：

```text
PONG
```

测试写入数据：

```redis
SET project:name "feibijiubi"
```

读取数据：

```redis
GET project:name
```

结果：

```text
"feibijiubi"
```

退出客户端：

```redis
exit
```

也可以不进入交互模式，直接执行一条命令：

```bash
docker compose exec redis redis-cli PING
```

结果应为：

```text
PONG
```

### 6.2 方法二：使用 RedisInsight

RedisInsight 是 Redis 官方提供的图形化客户端，适合初学者观察 Key、Value 和 TTL。

下载地址：

<https://redis.io/insight/>

安装完成后，创建 Redis 连接：

| 配置项 | 填写内容 |
| --- | --- |
| Host | `127.0.0.1` 或 `localhost` |
| Port | `6379` |
| Username | 留空 |
| Password | 留空 |
| Database Alias | `菲比啾比本地Redis` |

连接成功后，可以使用 RedisInsight：

- 查看 Redis 中的 Key；
- 查看 Key 的数据类型；
- 查看剩余 TTL；
- 执行 Redis 命令；
- 修改或删除测试数据。

### 6.3 方法三：在 WSL 中安装 `redis-cli`

如果已经安装 Ubuntu WSL，可以进入 WSL 后执行：

```bash
sudo apt update
sudo apt install redis-tools
```

连接 Docker 中的 Redis：

```bash
redis-cli -h 127.0.0.1 -p 6379
```

测试连接：

```redis
PING
```

正常结果：

```text
PONG
```

---

## 7. Redis 常用命令练习

## 7.1 String 类型

写入：

```redis
SET user:1:name "张三"
```

读取：

```redis
GET user:1:name
```

检查 Key 是否存在：

```redis
EXISTS user:1:name
```

删除：

```redis
DEL user:1:name
```

### Key 命名建议

建议使用冒号划分业务层级：

```text
业务:资源:标识:属性
```

例如：

```text
user:1:name
video:1001:title
login:code:13800138000
```

这种命名方式比 `user1name` 更容易阅读和管理。

## 7.2 TTL 和过期时间

写入一个 60 秒后过期的 Key：

```redis
SET login:code:13800138000 "123456" EX 60
```

查看剩余时间：

```redis
TTL login:code:13800138000
```

可能得到：

```text
(integer) 53
```

表示该 Key 还剩 53 秒过期。

等待 Key 过期后执行：

```redis
GET login:code:13800138000
```

结果：

```text
(nil)
```

为已经存在的 Key 单独设置过期时间：

```redis
EXPIRE user:1:name 300
```

表示该 Key 将在 300 秒后过期。

取消过期时间：

```redis
PERSIST user:1:name
```

`TTL` 的常见返回值：

| 返回值 | 含义 |
| --- | --- |
| 正整数 | 剩余过期秒数 |
| `-1` | Key 存在，但是没有设置过期时间 |
| `-2` | Key 不存在或已经过期 |

## 7.3 查看 Key

在本地学习环境中可以使用：

```redis
KEYS *
```

但是要注意：`KEYS *` 会遍历当前数据库中的所有 Key。数据量较大时可能阻塞 Redis，因此生产环境中不应该随意使用。

生产环境更推荐使用渐进式扫描：

```redis
SCAN 0 MATCH user:* COUNT 10
```

## 7.4 Hash 类型

Hash 可以保存一个对象的多个字段：

```redis
HSET user:1 nickname "张三" avatar "/avatar/1.jpg"
```

获取一个字段：

```redis
HGET user:1 nickname
```

获取所有字段：

```redis
HGETALL user:1
```

## 7.5 List 类型

向列表左侧添加数据：

```redis
LPUSH video:recent 101 102 103
```

查看整个列表：

```redis
LRANGE video:recent 0 -1
```

## 7.6 Set 类型

向集合中添加用户 ID：

```redis
SADD video:1:likes 1001 1002 1003
```

查看集合中的所有成员：

```redis
SMEMBERS video:1:likes
```

判断某个用户是否在集合中：

```redis
SISMEMBER video:1:likes 1001
```

---

## 8. Spring Boot 引入 Redis

## 8.1 添加依赖

在 `pom.xml` 的 `<dependencies>` 中添加：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
```

添加完成后，可以执行编译检查：

```bash
./mvnw -DskipTests compile
```

## 8.2 配置连接信息

在 `src/main/resources/application.properties` 中添加：

```properties
spring.data.redis.host=127.0.0.1
spring.data.redis.port=6379
spring.data.redis.database=0
spring.data.redis.connect-timeout=3s
spring.data.redis.timeout=3s
```

这里使用：

```properties
spring.data.redis.host=127.0.0.1
```

是因为当前 Spring Boot 应用运行在 Windows 本机，而 Redis 容器的端口已经映射到了 Windows 的 `6379` 端口。

连接过程是：

```text
Windows 上的 Spring Boot
        ↓ 127.0.0.1:6379
Windows 的 Docker 端口映射
        ↓
Redis 容器的 6379
```

---

## 9. 使用 StringRedisTemplate

第一轮学习推荐使用 `StringRedisTemplate`，因为：

- Key 和 Value 都以字符串形式保存；
- 在 `redis-cli` 或 RedisInsight 中容易直接观察；
- 暂时不需要处理复杂的对象序列化配置；
- 更适合理解 Redis 的基础操作。

示例学习服务：

```java
package com.feibijiubi.backend.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
@RequiredArgsConstructor
public class RedisLearningService {

    private final StringRedisTemplate stringRedisTemplate;

    public void setValue(String key, String value) {
        stringRedisTemplate.opsForValue().set(key, value);
    }

    public void setValue(String key, String value, Duration ttl) {
        stringRedisTemplate.opsForValue().set(key, value, ttl);
    }

    public String getValue(String key) {
        return stringRedisTemplate.opsForValue().get(key);
    }

    public Boolean deleteValue(String key) {
        return stringRedisTemplate.delete(key);
    }
}
```

写入普通 Key：

```java
redisLearningService.setValue("project:name", "菲比啾比");
```

写入一个 10 分钟后过期的 Key：

```java
redisLearningService.setValue(
        "login:code:13800138000",
        "123456",
        Duration.ofMinutes(10)
);
```

然后可以通过 `redis-cli` 或 RedisInsight 检查：

```redis
GET project:name
GET login:code:13800138000
TTL login:code:13800138000
```

---

## 10. 常用 Docker Compose 管理命令

### 10.1 停止 Redis，但保留容器和数据

```bash
docker compose stop redis
```

重新启动：

```bash
docker compose start redis
```

### 10.2 删除容器，但保留数据卷

```bash
docker compose down
```

再次启动：

```bash
docker compose up -d
```

因为 `redis-data` 数据卷没有被删除，原有 Redis 数据通常仍然存在。

### 10.3 删除容器和 Redis 数据

```bash
docker compose down -v
```

`-v` 会同时删除 Compose 创建的数据卷，Redis 数据也会被清除。执行前应确认其中的数据已经不再需要。

### 10.4 查看数据卷

```bash
docker volume ls
```

---

## 11. 常见问题

## 11.1 `Connection refused`

先查看 Redis 容器状态：

```bash
docker compose ps
```

再测试容器内部的 Redis：

```bash
docker compose exec redis redis-cli PING
```

如果容器没有运行，执行：

```bash
docker compose up -d
```

如果容器内部能够返回 `PONG`，但 Windows 客户端仍然不能连接，需要继续检查端口映射和客户端填写的 Host、Port。

## 11.2 6379 端口被占用

如果启动时出现类似错误：

```text
port is already allocated
```

说明 Windows 的 `6379` 端口已经被其他程序占用。

可以将 Compose 配置改为：

```yaml
ports:
  - "127.0.0.1:6380:6379"
```

此时连接端口也要改成 `6380`：

```bash
redis-cli -h 127.0.0.1 -p 6380
```

Spring Boot 配置也要改为：

```properties
spring.data.redis.port=6380
```

需要注意：Redis 容器内部仍然监听 `6379`，只是 Windows 对外暴露的端口改为了 `6380`。

## 11.3 Spring Boot 以后也运行在 Docker 中

如果以后将 Spring Boot 和 Redis 都放入同一个 Compose 文件，就不能继续使用：

```properties
spring.data.redis.host=127.0.0.1
```

在容器中，`127.0.0.1` 指向当前容器自己。Spring Boot 容器应该通过 Compose 服务名访问 Redis：

```properties
spring.data.redis.host=redis
spring.data.redis.port=6379
```

可以记住下面的规则：

| Spring Boot 运行位置 | Redis Host | Redis Port |
| --- | --- | --- |
| Spring Boot 运行在 Windows | `127.0.0.1` | Windows 映射端口，默认 `6379` |
| Spring Boot 和 Redis 都运行在同一个 Compose | `redis` | 容器端口 `6379` |
| 其他电脑访问 Windows | Windows 的局域网 IP | Windows 映射端口 |

如果需要允许局域网中的其他电脑连接，还需要调整 Compose 端口绑定和 Windows 防火墙。学习阶段不建议开放 Redis 到局域网。

## 11.4 Docker Desktop 没有启动

如果执行 Docker 命令时提示无法连接 Docker Engine，应先打开 Docker Desktop，等待引擎启动完成后再执行：

```bash
docker compose up -d
```

---

## 12. 推荐学习顺序

建议按照以下顺序练习：

1. 安装并启动 Docker Desktop；
2. 使用 `docker compose up -d` 启动 Redis；
3. 使用 `docker compose ps` 确认 Redis 健康状态；
4. 使用 `docker compose exec redis redis-cli` 连接 Redis；
5. 熟悉 `SET`、`GET`、`DEL` 和 `EXISTS`；
6. 熟悉 `EXPIRE`、`TTL` 和带 `EX` 的 `SET`；
7. 安装 RedisInsight，观察 Key、Value 和 TTL；
8. 在 Spring Boot 中引入 Redis 依赖；
9. 使用 `StringRedisTemplate` 写入测试 Key；
10. 在 RedisInsight 或 `redis-cli` 中确认 Spring Boot 写入的数据；
11. 最后再将 Redis 应用到具体业务中。

第一阶段最重要的是理解：

```text
Key   = 数据的名字
Value = 数据的内容
TTL   = 数据还能存活多久
Redis = 主要在内存中工作的高性能键值数据库
```

---

## 13. “菲比啾比”后续适合使用 Redis 的功能

后续可以考虑在以下场景中使用 Redis：

- 登录验证码；
- 登录状态或 Token 黑名单；
- 热门视频排行榜；
- 视频信息缓存；
- 用户点赞状态；
- 防止重复提交；
- 接口访问频率限制；
- 临时播放量、点赞量等计数聚合。

但是第一轮建议只创建独立的 Redis 学习代码，不要立即改造现有登录、视频、审核或点赞等核心业务。先把 Redis 环境、常用命令、TTL 和 `StringRedisTemplate` 的基本使用掌握清楚，再逐步接入实际业务。
