package com.feibijiubi.backend.utils.rabbitmq;

public final class RabbitConstants {

    //--------------------------------------------video-status------------------------------------
    public static final String MAIN_EXCHANGE = "video.status.exchange.v1";
    public static final String MAIN_ROUTING_KEY = "video.status.changed.v1";
    public static final String MAIN_QUEUE = "video.status.persist.queue.v1";

    public static final String RETRY_EXCHANGE = "video.status.retry.exchange.v1";
    public static final String RETRY_ROUTING_KEY = "video.status.retry.v1";
    public static final String RETRY_QUEUE = "video.status.retry.queue.v1";

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
