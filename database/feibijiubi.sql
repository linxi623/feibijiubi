CREATE DATABASE IF NOT EXISTS `feibijiubi`
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

USE `feibijiubi`;

DROP TABLE IF EXISTS `user_follow`;
DROP TABLE IF EXISTS `upload_temp_file`;
DROP TABLE IF EXISTS `user_video`;
DROP TABLE IF EXISTS `video_status`;
DROP TABLE IF EXISTS `video`;
DROP TABLE IF EXISTS `users`;


CREATE TABLE `users` (
    `id` INT NOT NULL AUTO_INCREMENT COMMENT '用户ID',
    `username` VARCHAR(50) NOT NULL COMMENT '用户名',
    `password_hash` VARCHAR(255) NOT NULL COMMENT '加密后的密码',
    `nickname` VARCHAR(50) NOT NULL COMMENT '用户昵称',
    `avatar_url` VARCHAR(500) DEFAULT NULL COMMENT '头像地址',
    `background_url` VARCHAR(500) DEFAULT NULL COMMENT '主页背景图URL',
    `gender` TINYINT NOT NULL DEFAULT 2 COMMENT '性别 0女 1男 2未知',
    `description` VARCHAR(100) DEFAULT NULL COMMENT '个人简介',
    `experience` INT NOT NULL DEFAULT 0 COMMENT '经验值',
    `coin` INT NOT NULL DEFAULT 0 COMMENT '硬币数',
    `vip` TINYINT NOT NULL DEFAULT 0 COMMENT '会员类型 0普通用户 1月度大会员 2季度大会员 3年度大会员',
    `status` TINYINT NOT NULL DEFAULT 0 COMMENT '状态 0正常 1封禁 2注销',
    `role` TINYINT NOT NULL DEFAULT 0 COMMENT '角色类型 0普通用户 1管理员 2超级管理员',
    `auth` TINYINT NOT NULL DEFAULT 0 COMMENT '官方认证 0普通用户 1个人认证 2机构认证',
    `auth_msg` VARCHAR(30) DEFAULT NULL COMMENT '认证说明',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted_at` DATETIME DEFAULT NULL COMMENT '注销时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_users_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户表';

CREATE TABLE `video` (
    `vid` INT NOT NULL AUTO_INCREMENT COMMENT '视频ID',
    `uid` INT NOT NULL COMMENT '投稿用户ID',
    `title` VARCHAR(80) NOT NULL COMMENT '视频标题',
    `source_type` TINYINT NOT NULL DEFAULT 1 COMMENT '来源类型 1自制 2转载',
    `visibility` TINYINT NOT NULL DEFAULT 0 COMMENT '可见性 0公开 1私密',
    `duration` DOUBLE NOT NULL DEFAULT 0 COMMENT '视频时长，单位秒',
    `mc_id` VARCHAR(20) NOT NULL COMMENT '主分区ID',
    `sc_id` VARCHAR(20) NOT NULL COMMENT '子分区ID',
    `tags` VARCHAR(500) DEFAULT NULL COMMENT '标签，建议用逗号或换行分隔',
    `description` VARCHAR(2000) DEFAULT NULL COMMENT '视频简介',
    `cover_url` VARCHAR(500) NOT NULL COMMENT '封面URL',
    `cover_key` VARCHAR(500) NOT NULL COMMENT '后端管理封面对象',
    `video_url` VARCHAR(500) NOT NULL COMMENT '视频URL',
    `video_key` VARCHAR(500) NOT NULL COMMENT '后端复制、删除、校验 COS 对象',
    `status` TINYINT NOT NULL DEFAULT 0 COMMENT '状态 0审核中 1通过审核 2打回整改 3违规删除',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建/上传时间',
    `deleted_at` DATETIME DEFAULT NULL COMMENT '删除时间',
    PRIMARY KEY (`vid`),
    KEY `idx_video_uid` (`uid`),
    KEY `idx_video_status_created_at` (`status`, `created_at`),
    KEY `idx_video_category` (`mc_id`, `sc_id`),
    CONSTRAINT `fk_video_uid` FOREIGN KEY (`uid`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='视频表';

CREATE TABLE `video_status` (
    `vid` INT NOT NULL COMMENT '视频ID',
    `play_times` INT NOT NULL DEFAULT 0 COMMENT '播放次数',
    `like_times` INT NOT NULL DEFAULT 0 COMMENT '点赞次数',
    `unlike_times` INT NOT NULL DEFAULT 0 COMMENT '点踩次数',
    `comment_times` INT NOT NULL DEFAULT 0 COMMENT '评论次数',
    `coin_times` INT NOT NULL DEFAULT 0 COMMENT '投币次数',
    `share_times` INT NOT NULL DEFAULT 0 COMMENT '分享次数',
    `collect_times` INT NOT NULL DEFAULT 0 COMMENT '收藏次数',
    `danmu_times` INT NOT NULL DEFAULT 0 COMMENT '弹幕次数',
    PRIMARY KEY (`vid`),
    CONSTRAINT `fk_video_status_vid` FOREIGN KEY (`vid`) REFERENCES `video` (`vid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='视频数据统计表';

CREATE TABLE `user_video` (
    `id` INT NOT NULL AUTO_INCREMENT COMMENT '唯一标识',
    `vid` INT NOT NULL COMMENT '视频ID',
    `uid` INT NOT NULL COMMENT '用户ID',
    `play_time` DOUBLE NOT NULL DEFAULT 0 COMMENT '观看时长，单位秒',
    `liked` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否点赞 0否 1是',
    `unliked` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否点踩踏 0否 1是',
    `coin` TINYINT NOT NULL DEFAULT 0 COMMENT '投币数 0-2',
    `collect` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否收藏 0否 1是',
    `played_at` DATETIME DEFAULT NULL COMMENT '最近观看时间',
    `liked_at` DATETIME DEFAULT NULL COMMENT '最近点赞时间',
    `coined_at` DATETIME DEFAULT NULL COMMENT '最近投币时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_video_uid_vid` (`uid`, `vid`),
    KEY `idx_user_video_vid` (`vid`),
    CONSTRAINT `fk_user_video_uid` FOREIGN KEY (`uid`) REFERENCES `users` (`id`),
    CONSTRAINT `fk_user_video_vid` FOREIGN KEY (`vid`) REFERENCES `video` (`vid`),
    CONSTRAINT `ck_user_video_coin` CHECK (`coin` >= 0 AND `coin` <= 2)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户视频关系表';

CREATE TABLE `upload_temp_file` (
    `id` INT NOT NULL AUTO_INCREMENT COMMENT '临时文件ID',
    `uid` INT NOT NULL COMMENT '上传用户ID',
    `file_type` TINYINT NOT NULL COMMENT '文件类型 1视频 2封面',
    `object_key` VARCHAR(500) NOT NULL COMMENT 'COS临时对象key',
    `original_filename` VARCHAR(255) DEFAULT NULL COMMENT '原始文件名',
    `content_type` VARCHAR(100) DEFAULT NULL COMMENT '文件类型',
    `file_size` BIGINT DEFAULT NULL COMMENT '文件大小',
    `status` TINYINT NOT NULL DEFAULT 0 COMMENT '状态 0临时 1已提交 2已清理',
    `expire_at` DATETIME NOT NULL COMMENT '过期时间',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_upload_temp_file_object_key` (`object_key`),
    KEY `idx_upload_temp_file_uid_status_expire` (`uid`, `status`, `expire_at`),
    CONSTRAINT `fk_upload_temp_file_uid` FOREIGN KEY (`uid`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='上传临时文件表';

CREATE TABLE `user_follow` (
    `id` INT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '唯一标识',
    `follower_id` INT NOT NULL COMMENT '关注者用户id',
    `followed_id` INT NOT NULL COMMENT '被关注者id',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '关注时间',

    UNIQUE KEY uk_follower_followed (follower_id, followed_id),
    KEY idx_followed_id (followed_id),
    CONSTRAINT `fk_user_follow_follower_id` FOREIGN KEY (`follower_id`) REFERENCES `users` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_user_follow_followed_id` FOREIGN KEY (`followed_id`) REFERENCES `users` (`id`) ON DELETE CASCADE,
    CONSTRAINT `ck_user_follow_not_self`
        CHECK (`follower_id` <> `followed_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户关系表';
