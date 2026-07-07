CREATE DATABASE IF NOT EXISTS `feibijiubi`
DEFAULT CHARACTER SET utf8mb4
COLLATE utf8mb4_unicode_ci;

USE `feibijiubi`;

DROP TABLE IF EXISTS `users`;

CREATE TABLE `users` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '用户ID',
    `username` VARCHAR(50) NOT NULL COMMENT '用户名',
    `password_hash` VARCHAR(255) NOT NULL COMMENT '加密后的密码',
    `nickname` VARCHAR(50) NOT NULL COMMENT '用户昵称',
    `avatar_url` VARCHAR(500)  COMMENT '头像地址',
    `background_url` VARCHAR(500)  COMMENT '主页背景图url',
    `gender` TINYINT NOT NULL DEFAULT 2 COMMENT '性别 0女 1男 2未知',
    `description` VARCHAR(100) COMMENT '个人简介',
    `experience` INT NOT NULL DEFAULT 0 COMMENT '经验值',
    `coin` INT NOT NULL DEFAULT 0 COMMENT '硬币数',
    `vip` TINYINT NOT NULL DEFAULT 0 COMMENT '会员类型 0普通用户 1月度大会员 2季度大会员 3年度大会员',
    `status` TINYINT NOT NULL DEFAULT 0 COMMENT '状态 0正常 1封禁 2注销',
    `role` TINYINT NOT NULL DEFAULT 0 COMMENT '角色类型 0普通用户 1管理员 2超级管理员',
    `auth` TINYINT NOT NULL DEFAULT 0 COMMENT '官方认证 0普通用户 1个人认证 2机构认证',
    `auth_msg` VARCHAR(30) DEFAULT NULL COMMENT '认证说明',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted_at` DATETIME DEFAULT NULL COMMENT '注销时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `username` (`username`)
)