package com.feibijiubi.backend.utils.rabbitmq;

public final class RabbitConstants {

    //--------------------------------------------video-status------------------------------------
    public static final String MAIN_EXCHANGE = "video.status.exchange.v1";
    public static final String MAIN_ROUTING_KEY = "video.status.changed.v1";
    public static final String MAIN_QUEUE = "video.status.persist.queue.v1";

    public static final String RETRY_EXCHANGE = "video.status.retry.exchange.v1";
    // 第一级 10 秒队列：名称、路由键与 TTL 均保持原样，兼容已部署队列。
    public static final String RETRY_ROUTING_KEY = "video.status.retry.v1";
    public static final String RETRY_QUEUE = "video.status.retry.queue.v1";
    // T08：新增 30 秒 / 120 秒两级固定 TTL 队列，避免在同一队列混用任意过期时间。
    public static final String RETRY_ROUTING_KEY_30S = "video.status.retry.30s.v1";
    public static final String RETRY_QUEUE_30S = "video.status.retry.queue.30s.v1";
    public static final String RETRY_ROUTING_KEY_120S = "video.status.retry.120s.v1";
    public static final String RETRY_QUEUE_120S = "video.status.retry.queue.120s.v1";

    /** 视频统计消费者监听器 id；恢复控制器按此 id 精确停止/启动容器。 */
    public static final String CONSUMER_LISTENER_ID = "videoStatusEventConsumer";

    public static final String DEAD_EXCHANGE = "video.status.dlx.v1";
    public static final String DEAD_ROUTING_KEY = "video.status.dead.v1";
    public static final String DEAD_QUEUE = "video.status.dlq.v1";

    public static final String ATTEMPT_HEADER = "x-video-status-attempt";
    public static final String RECOVERY_HEADER = "x-video-status-recovery";
    public static final String EVENT_ID_HEADER = "x-video-status-event-id";
    public static final String FAILURE_REASON_HEADER =
            "x-video-status-failure-reason";
    //-----------------------------------------video-status----------------------------------------




    private RabbitConstants() {
    }
}
