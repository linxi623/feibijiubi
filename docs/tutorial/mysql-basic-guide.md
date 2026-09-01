# MySQL 基础语法与常用知识复习

这份笔记用于在编写项目建表 SQL 前快速复习 MySQL 的基础语法和常用知识，适合当前 mini-Bilibili 后端项目使用。

---

## 1. 基本概念

### 1.1 数据库 Database

一个项目通常对应一个数据库。

```sql
CREATE DATABASE feibijiubi DEFAULT CHARACTER SET utf8mb4;
```

使用数据库：

```sql
USE feibijiubi;
```

查看所有数据库：

```sql
SHOW DATABASES;
```

删除数据库：

```sql
DROP DATABASE feibijiubi;
```

> `DROP` 会删除数据库及其中的数据，执行前要确认清楚。

---

### 1.2 表 Table

表用于存放具体业务数据，例如用户表、视频表、评论表。

查看当前数据库中的表：

```sql
SHOW TABLES;
```

查看表结构：

```sql
DESC users;
```

查看完整建表语句：

```sql
SHOW CREATE TABLE users;
```

---

## 2. 建表语法 CREATE TABLE

基本语法：

```sql
CREATE TABLE 表名 (
    字段名 字段类型 约束,
    字段名 字段类型 约束,
    ...
);
```

示例：

```sql
CREATE TABLE users (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    username VARCHAR(50) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    nickname VARCHAR(50),
    status TINYINT DEFAULT 1,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
);
```

---

## 3. 常见字段类型

### 3.1 整数类型

常见整数类型：

```sql
TINYINT
INT
BIGINT
```

#### TINYINT

适合存状态值。

```sql
status TINYINT DEFAULT 1
```

例如：

- `1`：正常
- `0`：禁用

#### INT

普通整数。

```sql
age INT
```

#### BIGINT

大整数，常用于主键 ID。

```sql
id BIGINT PRIMARY KEY AUTO_INCREMENT
```

实际项目中，主键推荐使用 `BIGINT`。

---

### 3.2 字符串类型

常见字符串类型：

```sql
CHAR
VARCHAR
TEXT
```

#### VARCHAR

最常用，适合用户名、昵称、标题、URL 等。

```sql
username VARCHAR(50)
title VARCHAR(100)
avatar_url VARCHAR(500)
```

`VARCHAR(50)` 表示最多 50 个字符。

#### CHAR

固定长度，使用较少。

```sql
gender CHAR(1)
```

#### TEXT

适合长文本，例如简介、评论内容、文章正文。

```sql
description TEXT
content TEXT
```

---

### 3.3 时间类型

常见时间类型：

```sql
DATE
TIME
DATETIME
TIMESTAMP
```

开发中常用 `DATETIME`：

```sql
created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
```

含义：

- `created_at`：创建时间，插入数据时自动填当前时间。
- `updated_at`：更新时间，每次修改数据时自动更新。

---

### 3.4 小数类型

常见小数类型：

```sql
DECIMAL
FLOAT
DOUBLE
```

涉及金额时应使用 `DECIMAL`，不要使用 `FLOAT`。

```sql
price DECIMAL(10, 2)
```

表示总共 10 位数字，其中小数 2 位。

---

## 4. 常见约束

### 4.1 PRIMARY KEY 主键

每张表一般都要有主键。

```sql
id BIGINT PRIMARY KEY AUTO_INCREMENT
```

主键特点：

- 不能重复；
- 不能为空；
- 用来唯一标识一行数据。

---

### 4.2 AUTO_INCREMENT 自增

通常配合主键使用。

```sql
id BIGINT PRIMARY KEY AUTO_INCREMENT
```

插入数据时不用手动指定 ID，MySQL 会自动生成。

---

### 4.3 NOT NULL 非空

表示字段必须有值。

```sql
username VARCHAR(50) NOT NULL
```

---

### 4.4 DEFAULT 默认值

字段没有传值时使用默认值。

```sql
status TINYINT DEFAULT 1,
role VARCHAR(20) DEFAULT 'USER'
```

---

### 4.5 UNIQUE 唯一约束

表示字段不能重复。

```sql
username VARCHAR(50) NOT NULL UNIQUE
```

更推荐写成命名唯一索引：

```sql
UNIQUE KEY uk_users_username (username)
```

完整示例：

```sql
CREATE TABLE users (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    username VARCHAR(50) NOT NULL,
    UNIQUE KEY uk_users_username (username)
);
```

---

### 4.6 COMMENT 注释

给表和字段添加说明。

```sql
CREATE TABLE users (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '用户ID',
    username VARCHAR(50) NOT NULL COMMENT '用户名'
) COMMENT='用户表';
```

建议项目 SQL 中多写注释，方便学习和维护。

---

## 5. 增删改查 CRUD

CRUD 是数据库最常用的四类操作：

- Create：新增
- Read：查询
- Update：修改
- Delete：删除

---

## 6. INSERT 插入数据

### 6.1 插入一条数据

```sql
INSERT INTO users (username, password_hash, nickname)
VALUES ('linxi', '123456', '林夕');
```

如果字段有默认值，可以不写。

---

### 6.2 一次插入多条数据

```sql
INSERT INTO users (username, password_hash, nickname)
VALUES
('user1', '123456', '用户1'),
('user2', '123456', '用户2'),
('user3', '123456', '用户3');
```

---

## 7. SELECT 查询数据

### 7.1 查询所有字段

```sql
SELECT * FROM users;
```

实际开发中不建议大量使用 `*`，最好明确字段：

```sql
SELECT id, username, nickname FROM users;
```

---

### 7.2 WHERE 条件查询

```sql
SELECT * FROM users WHERE id = 1;
```

```sql
SELECT * FROM users WHERE username = 'linxi';
```

```sql
SELECT * FROM users WHERE status = 1;
```

---

### 7.3 多条件查询

#### AND：同时满足

```sql
SELECT * FROM users
WHERE role = 'USER' AND status = 1;
```

#### OR：满足任意一个

```sql
SELECT * FROM users
WHERE role = 'ADMIN' OR username = 'linxi';
```

---

### 7.4 LIKE 模糊查询

```sql
SELECT * FROM users
WHERE username LIKE '%lin%';
```

含义：

- `%lin%`：包含 `lin`
- `lin%`：以 `lin` 开头
- `%lin`：以 `lin` 结尾

搜索用户名或昵称：

```sql
SELECT * FROM users
WHERE username LIKE '%林%'
   OR nickname LIKE '%林%';
```

---

### 7.5 IN 查询

查询多个值：

```sql
SELECT * FROM users
WHERE role IN ('USER', 'ADMIN');
```

等价于：

```sql
WHERE role = 'USER' OR role = 'ADMIN'
```

---

### 7.6 BETWEEN 范围查询

```sql
SELECT * FROM users
WHERE created_at BETWEEN '2026-07-01 00:00:00'
                     AND '2026-07-31 23:59:59';
```

---

### 7.7 IS NULL / IS NOT NULL

判断空值不能使用 `= NULL`。

正确写法：

```sql
SELECT * FROM users WHERE avatar_url IS NULL;
```

```sql
SELECT * FROM users WHERE avatar_url IS NOT NULL;
```

错误写法：

```sql
SELECT * FROM users WHERE avatar_url = NULL;
```

---

## 8. ORDER BY 排序

升序：

```sql
SELECT * FROM users
ORDER BY id ASC;
```

降序：

```sql
SELECT * FROM users
ORDER BY created_at DESC;
```

`ASC` 是默认升序，可以省略。`DESC` 常用于“最新数据排前面”。

---

## 9. LIMIT 分页

查询前 10 条：

```sql
SELECT * FROM users
LIMIT 10;
```

从第 0 条开始，查 10 条：

```sql
SELECT * FROM users
LIMIT 0, 10;
```

从第 10 条开始，查 10 条：

```sql
SELECT * FROM users
LIMIT 10, 10;
```

分页公式：

```text
offset = (page - 1) * pageSize
```

例如：

```text
page = 1, pageSize = 10, offset = 0
page = 2, pageSize = 10, offset = 10
```

对应接口：

```http
GET /api/admin/users?page=1&pageSize=10
```

SQL 示例：

```sql
SELECT id, username, nickname, avatar_url, role, status, created_at
FROM users
ORDER BY created_at DESC
LIMIT 0, 10;
```

---

## 10. UPDATE 修改数据

基本语法：

```sql
UPDATE 表名
SET 字段名 = 新值
WHERE 条件;
```

修改昵称：

```sql
UPDATE users
SET nickname = '新的昵称'
WHERE id = 1;
```

修改用户状态：

```sql
UPDATE users
SET status = 0
WHERE id = 1;
```

修改角色：

```sql
UPDATE users
SET role = 'ADMIN'
WHERE id = 1;
```

> `UPDATE` 一定要小心 `WHERE`。没有 `WHERE` 会修改整张表。

危险写法：

```sql
UPDATE users
SET status = 0;
```

---

## 11. DELETE 删除数据

基本语法：

```sql
DELETE FROM 表名
WHERE 条件;
```

删除 ID 为 1 的用户：

```sql
DELETE FROM users
WHERE id = 1;
```

> `DELETE` 也一定要小心 `WHERE`。没有 `WHERE` 会删除整张表的数据。

危险写法：

```sql
DELETE FROM users;
```

---

## 12. 物理删除和逻辑删除

### 12.1 物理删除

真正从数据库中删除数据：

```sql
DELETE FROM users
WHERE id = 1;
```

### 12.2 逻辑删除

不真正删除数据，而是通过状态字段标记删除。

```sql
UPDATE users
SET status = -1
WHERE id = 1;
```

常见状态约定：

- `1`：正常
- `0`：禁用
- `-1`：已删除

实际项目中更推荐逻辑删除。

---

## 13. ALTER TABLE 修改表结构

### 13.1 添加字段

```sql
ALTER TABLE users
ADD COLUMN email VARCHAR(100) COMMENT '邮箱';
```

### 13.2 修改字段类型

```sql
ALTER TABLE users
MODIFY COLUMN nickname VARCHAR(100) COMMENT '用户昵称';
```

### 13.3 修改字段名

```sql
ALTER TABLE users
CHANGE COLUMN avatar avatar_url VARCHAR(500) COMMENT '头像地址';
```

### 13.4 删除字段

```sql
ALTER TABLE users
DROP COLUMN email;
```

### 13.5 添加普通索引

```sql
ALTER TABLE users
ADD INDEX idx_users_status (status);
```

### 13.6 添加唯一索引

```sql
ALTER TABLE users
ADD UNIQUE KEY uk_users_username (username);
```

---

## 14. DROP、DELETE、TRUNCATE 区别

### 14.1 DELETE

删除数据，保留表结构，可以带 `WHERE`。

```sql
DELETE FROM users WHERE id = 1;
```

### 14.2 TRUNCATE

清空整张表，保留表结构，不能带 `WHERE`。

```sql
TRUNCATE TABLE users;
```

### 14.3 DROP

删除整张表，包括表结构和数据。

```sql
DROP TABLE users;
```

| 语句 | 删除内容 | 是否保留表结构 | 是否可带 WHERE |
|---|---|---|---|
| DELETE | 数据 | 是 | 是 |
| TRUNCATE | 全部数据 | 是 | 否 |
| DROP | 表结构和数据 | 否 | 否 |

---

## 15. COUNT 统计数量

统计用户总数：

```sql
SELECT COUNT(*) FROM users;
```

统计正常用户数：

```sql
SELECT COUNT(*) FROM users
WHERE status = 1;
```

分页查询通常需要两条 SQL：

```sql
SELECT COUNT(*) FROM users;
```

```sql
SELECT id, username, nickname
FROM users
LIMIT 0, 10;
```

接口返回示例：

```json
{
  "total": 100,
  "list": []
}
```

---

## 16. GROUP BY 分组

按角色统计人数：

```sql
SELECT role, COUNT(*) AS count
FROM users
GROUP BY role;
```

按状态统计人数：

```sql
SELECT status, COUNT(*) AS count
FROM users
GROUP BY status;
```

---

## 17. 常用聚合函数

| 函数 | 作用 |
|---|---|
| COUNT | 统计数量 |
| SUM | 求和 |
| AVG | 平均值 |
| MAX | 最大值 |
| MIN | 最小值 |

示例：

```sql
SELECT SUM(view_count) FROM videos;
```

```sql
SELECT MAX(view_count) FROM videos;
```

```sql
SELECT AVG(view_count) FROM videos;
```

---

## 18. 表关联 JOIN

后续做 mini-Bilibili 项目时，经常会有关联表：

- `users`：用户表
- `videos`：视频表
- `comments`：评论表

例如视频表中通常会有：

```sql
user_id
```

表示视频作者 ID。

---

### 18.1 INNER JOIN 内连接

只查询两边都匹配的数据。

```sql
SELECT v.id, v.title, u.username
FROM videos v
INNER JOIN users u ON v.user_id = u.id;
```

---

### 18.2 LEFT JOIN 左连接

左表数据全部保留，右表匹配不到时显示 `NULL`。

```sql
SELECT v.id, v.title, u.username
FROM videos v
LEFT JOIN users u ON v.user_id = u.id;
```

实际开发中 `LEFT JOIN` 很常用。

---

### 18.3 表别名

```sql
users u
videos v
```

例如：

```sql
SELECT v.title, u.nickname
FROM videos v
LEFT JOIN users u ON v.user_id = u.id;
```

比完整表名更简洁。

---

## 19. 索引 Index

索引用于提高查询速度。

例如登录时按用户名查询：

```sql
SELECT * FROM users WHERE username = 'linxi';
```

如果 `username` 有索引，查询会更快。

---

### 19.1 普通索引

```sql
KEY idx_users_status (status)
```

完整示例：

```sql
CREATE TABLE users (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    status TINYINT DEFAULT 1,
    KEY idx_users_status (status)
);
```

---

### 19.2 唯一索引

既能加快查询，又能保证不重复。

```sql
UNIQUE KEY uk_users_username (username)
```

---

### 19.3 哪些字段适合加索引

经常出现在这些位置的字段适合加索引：

- `WHERE`
- `ORDER BY`
- `JOIN ON`

例如：

```sql
WHERE username = ?
WHERE status = ?
WHERE role = ?
ORDER BY created_at DESC
JOIN users ON videos.user_id = users.id
```

可以考虑给这些字段加索引：

- `username`
- `status`
- `role`
- `created_at`
- `user_id`

> 索引不是越多越好。索引会占空间，也会让新增、修改、删除变慢一点。

初学阶段原则：

> 经常查询、关联、排序的字段加索引，其他字段先不加。

---

## 20. 外键 Foreign Key

外键用于保证表之间的数据关系。

例如视频表：

```sql
CREATE TABLE videos (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    title VARCHAR(100) NOT NULL,

    CONSTRAINT fk_videos_user
        FOREIGN KEY (user_id) REFERENCES users(id)
);
```

含义：

```text
videos.user_id 必须来自 users.id
```

如果用户不存在，就不能插入对应视频。

### 初学项目要不要用外键？

建议当前项目初期可以先不用数据库外键，但字段要设计好。

```sql
user_id BIGINT NOT NULL COMMENT '作者ID'
```

原因：

- 外键会增加学习和调试成本；
- 删除数据时容易遇到约束问题；
- 很多实际项目也会选择在业务层控制关系。

---

## 21. 事务 Transaction

事务用于保证一组 SQL 要么全部成功，要么全部失败。

例如用户发布视频时：

1. 插入视频；
2. 插入视频标签关系；
3. 更新用户作品数量。

如果中间失败，应该全部回滚。

手动事务语法：

```sql
START TRANSACTION;

UPDATE users SET status = 0 WHERE id = 1;
INSERT INTO logs (content) VALUES ('禁用用户1');

COMMIT;
```

如果出错：

```sql
ROLLBACK;
```

在 Spring Boot 中，通常使用：

```java
@Transactional
```

来控制事务。

---

## 22. MySQL 常用命令总结

### 22.1 数据库相关

```sql
SHOW DATABASES;

CREATE DATABASE feibijiubi DEFAULT CHARACTER SET utf8mb4;

USE feibijiubi;

DROP DATABASE feibijiubi;
```

### 22.2 表相关

```sql
SHOW TABLES;

DESC users;

SHOW CREATE TABLE users;

DROP TABLE users;
```

### 22.3 数据相关

```sql
SELECT * FROM users;

INSERT INTO users (username, password_hash, nickname)
VALUES ('linxi', '123456', '林夕');

UPDATE users
SET nickname = '新昵称'
WHERE id = 1;

DELETE FROM users
WHERE id = 1;
```

---

## 23. 当前项目建表重点

当前项目是 mini-Bilibili 后端，优先掌握以下表的设计思路。

### 23.1 用户表 users

建议字段：

```sql
id
username
password_hash
nickname
avatar_url
role
status
created_at
updated_at
```

重点知识：

```sql
PRIMARY KEY
AUTO_INCREMENT
UNIQUE
DEFAULT
DATETIME
```

---

### 23.2 视频表 videos

后续可能需要字段：

```sql
id
user_id
title
description
video_url
cover_url
category_id
view_count
like_count
comment_count
status
created_at
updated_at
```

重点字段：

```sql
user_id
category_id
view_count
created_at
```

---

### 23.3 评论表 comments

后续可能需要字段：

```sql
id
video_id
user_id
content
status
created_at
updated_at
```

重点字段：

```sql
video_id
user_id
```

---

### 23.4 点赞表 likes

后续可能需要字段：

```sql
id
user_id
target_type
target_id
created_at
```

防止重复点赞：

```sql
UNIQUE KEY uk_user_target (user_id, target_type, target_id)
```

---

### 23.5 收藏表 favorites

后续可能需要字段：

```sql
id
user_id
video_id
created_at
```

防止重复收藏：

```sql
UNIQUE KEY uk_user_video (user_id, video_id)
```

---

## 24. 标准建表示例：用户表

```sql
DROP TABLE IF EXISTS `users`;

CREATE TABLE `users` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '用户ID',
    `username` VARCHAR(50) NOT NULL COMMENT '用户名',
    `password_hash` VARCHAR(255) NOT NULL COMMENT '加密后的密码',
    `nickname` VARCHAR(50) NOT NULL COMMENT '用户昵称',
    `avatar_url` VARCHAR(500) DEFAULT NULL COMMENT '头像地址',
    `role` VARCHAR(20) NOT NULL DEFAULT 'USER' COMMENT '用户角色：USER 普通用户，ADMIN 管理员',
    `status` TINYINT NOT NULL DEFAULT 1 COMMENT '用户状态：1 正常，0 禁用',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_users_username` (`username`),
    KEY `idx_users_role` (`role`),
    KEY `idx_users_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户表';
```

---

## 25. 最小必会知识清单

如果只是为了继续当前项目，先掌握这些就够了：

```sql
CREATE TABLE
DROP TABLE
INSERT INTO
SELECT FROM WHERE
UPDATE SET WHERE
DELETE FROM WHERE
ORDER BY
LIMIT
COUNT
LEFT JOIN
PRIMARY KEY
AUTO_INCREMENT
NOT NULL
DEFAULT
UNIQUE
INDEX
DATETIME
```

掌握这些后，就可以完成大部分后端项目的数据库设计和基础开发。