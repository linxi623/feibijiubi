#!/bin/bash
# Works whether the official entrypoint sources or executes this bind mount.
# Explicit charset prevents UTF-8 seeds being interpreted as latin1.
MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --protocol=socket --default-character-set=utf8mb4 -uroot < /test-schema.sql
