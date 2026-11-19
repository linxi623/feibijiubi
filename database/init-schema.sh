#!/bin/bash
# 导入 /schema/feibijiubi.sql（compose.yaml 以只读方式挂载到该路径）。
#
# 为什么需要这个包装脚本：MySQL 官方镜像的 docker-entrypoint 直接执行 .sql 时，容器内没有
# locale，mysql 客户端默认字符集是 latin1，UTF-8 的中文种子数据会被按字节当字符计数，
# 在 `sc_name varchar(20)` 这类窄列上直接报 "Data too long for column ... at row N"，
# 初始化在中途中断，只建出部分表。
#
# 显式指定 utf8mb4 后与 src/test/docker/init-schema.sh 的行为一致。
# 本文件可能被 entrypoint 直接执行，也可能被 source，因此不依赖 shebang 生效。
MYSQL_PWD="${MYSQL_ROOT_PASSWORD}" mysql \
    --protocol=socket \
    --default-character-set=utf8mb4 \
    -uroot < /schema/feibijiubi.sql
