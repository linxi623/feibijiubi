package com.feibijiubi.backend.mq;

import com.feibijiubi.backend.config.VideoStatusRabbitConfig;
import com.feibijiubi.backend.utils.rabbitmq.RabbitConstants;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Queue;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * T08 队列拓扑的纯单元测试：只构造 Queue/Binding 对象，不连接 RabbitMQ。
 */
class VideoStatusRetryTopologyTests {

    private final VideoStatusRabbitConfig config = new VideoStatusRabbitConfig();

    @Test
    void originalTenSecondRetryQueueArgumentsStayUnchanged() {
        Queue queue = config.videoStatusRetryQueue();

        assertEquals(RabbitConstants.RETRY_QUEUE, queue.getName());
        assertRetryQueue(queue, 10_000);
    }

    @Test
    void thirtyAndOneHundredTwentySecondQueuesUseSeparateFixedTtl() {
        Queue thirty = config.videoStatusRetryQueue30s();
        Queue oneTwenty = config.videoStatusRetryQueue120s();

        assertEquals(RabbitConstants.RETRY_QUEUE_30S, thirty.getName());
        assertEquals(RabbitConstants.RETRY_QUEUE_120S, oneTwenty.getName());
        assertRetryQueue(thirty, 30_000);
        assertRetryQueue(oneTwenty, 120_000);
    }

    @Test
    void eachTierHasItsOwnBindingOnTheRetryExchange() {
        Binding ten = config.videoStatusRetryBinding();
        Binding thirty = config.videoStatusRetryBinding30s();
        Binding oneTwenty = config.videoStatusRetryBinding120s();

        assertEquals(RabbitConstants.RETRY_EXCHANGE, ten.getExchange());
        assertEquals(RabbitConstants.RETRY_ROUTING_KEY, ten.getRoutingKey());
        assertEquals(RabbitConstants.RETRY_QUEUE, ten.getDestination());
        assertEquals(RabbitConstants.RETRY_EXCHANGE, thirty.getExchange());
        assertEquals(RabbitConstants.RETRY_ROUTING_KEY_30S, thirty.getRoutingKey());
        assertEquals(RabbitConstants.RETRY_QUEUE_30S, thirty.getDestination());
        assertEquals(RabbitConstants.RETRY_EXCHANGE, oneTwenty.getExchange());
        assertEquals(RabbitConstants.RETRY_ROUTING_KEY_120S, oneTwenty.getRoutingKey());
        assertEquals(RabbitConstants.RETRY_QUEUE_120S, oneTwenty.getDestination());
    }

    private static void assertRetryQueue(Queue queue, long expectedTtlMillis) {
        assertEquals(expectedTtlMillis,
                ((Number) queue.getArguments().get("x-message-ttl")).longValue());
        assertEquals(RabbitConstants.MAIN_EXCHANGE,
                queue.getArguments().get("x-dead-letter-exchange"));
        assertEquals(RabbitConstants.MAIN_ROUTING_KEY,
                queue.getArguments().get("x-dead-letter-routing-key"));
    }
}
