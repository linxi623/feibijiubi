package com.feibijiubi.backend.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class VideoStatusOutbox {
    private Long id;
    private String eventId; // 全局标识事件，保证消息幂等
    private Integer aggregateId; // 视频id
    private Long aggregateSequence; // 表示同一个视频的第几个事件，保证顺序
    private String eventType; // 描述事件类型，点赞、收藏、投币等
    private String payload; // 消息体，包含发送消息的所有内容
    private Integer status; // 消息的发送状态，0=PENDING 待发送、1=SENDING 发送中、2=SENT 已发送、3=FAILED 发送失败
    private Integer retryCount; // 消息重试的次数
    private LocalDateTime nextRetryAt; // 到达这个时间后，失败消息才能被继续发送
    private LocalDateTime sendingAt; // 记录消息什么时候进入SENDING的状态
    private String leaseToken; // 租约令牌，持有该令牌的才能改变状态
    private String lastError;
    private LocalDateTime createdAt;
    private LocalDateTime sentAt; // 记录消息成功发送的时间
}
