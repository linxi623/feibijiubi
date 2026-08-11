package com.feibijiubi.backend.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.feibijiubi.backend.utils.rabbitmq.RabbitConstants;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.support.converter.DefaultJackson2JavaTypeMapper;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.cglib.core.Converter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.converter.JsonbMessageConverter;

@Configuration
public class VideoStatusRabbitConfig {

    /**
     * 主交换机，整个消息流转的主入口
     * 生产者的所有点赞、投币等事件首先流到在这里
     * @return
     */
    @Bean
    public DirectExchange videoStatusMainExchange() {
        return new DirectExchange(
                RabbitConstants.MAIN_EXCHANGE,
                true,
                false
        );
    }

    /**
     * 创建一个持久化队列（主队列）
     * 设置了死信交换机，消息被拒绝或超时时到这里
     * 设置了死信消息的路由键
     * @return
     */
    @Bean
    public Queue videoStatusMainQueue() {
        return QueueBuilder.durable(RabbitConstants.MAIN_QUEUE)
                .deadLetterExchange(RabbitConstants.DEAD_EXCHANGE)
                .deadLetterRoutingKey(RabbitConstants.DEAD_ROUTING_KEY)
                .build();
    }

    /**
     * 把主队列绑定到主交换机上
     * 匹配到指定路由键，消息从主交换机路由到主队列
     * @return
     */
    @Bean
    public Binding videoStatusMainBinding() {
        return BindingBuilder.bind(videoStatusMainQueue())
                .to(videoStatusMainExchange())
                .with(RabbitConstants.MAIN_ROUTING_KEY);
    }

    /**
     * 重试交换机
     * 是一个延迟缓冲池，主队列消费者处理失败后的去处
     * 将消息发布到重试队列
     * @return
     */
    @Bean
    public DirectExchange videoStatusRetryExchange() {
        return new DirectExchange(
                RabbitConstants.RETRY_EXCHANGE,
                true,
                false
        );
    }

    /**
     * 重试队列
     * 消息等待10s后才死亡，死亡后自动转发回主交换机
     * @return
     */
    @Bean
    public Queue videoStatusRetryQueue() {
        return QueueBuilder.durable(RabbitConstants.RETRY_QUEUE)
                .ttl(10_000)
                .deadLetterExchange(RabbitConstants.MAIN_EXCHANGE)
                .deadLetterRoutingKey(RabbitConstants.MAIN_ROUTING_KEY)
                .build();
    }

    /**
     * 重试绑定
     * @return
     */
    @Bean
    public Binding videoStatusRetryBinding() {
        return BindingBuilder.bind(videoStatusRetryQueue())
                .to(videoStatusRetryExchange())
                .with(RabbitConstants.RETRY_ROUTING_KEY);
    }

    /**
     * 死信交换机
     * 接受在重试队列反复失败的消息
     * @return
     */
    @Bean
    public DirectExchange videoStatusDeadExchange() {
        return new DirectExchange(
                RabbitConstants.DEAD_EXCHANGE,
                true,
                false
        );
    }

    /**
     * 死信队列
     * @return
     */
    @Bean
    public Queue videoStatusDeadQueue() {
        return QueueBuilder.durable(RabbitConstants.DEAD_QUEUE)
                .build();
    }

    /**
     * 死信绑定
     * @return
     */
    @Bean
    public Binding videoStatusDeadBinding() {
        return BindingBuilder.bind(videoStatusDeadQueue())
                .to(videoStatusDeadExchange())
                .with(RabbitConstants.DEAD_ROUTING_KEY);
    }

    /**
     * 消息转换器，将对象转为JSON字符串
     * @param objectMapper
     * @return
     */
    @Bean
    public MessageConverter rabbitMessageConverter(ObjectMapper objectMapper) {
        DefaultJackson2JavaTypeMapper typeMapper = new DefaultJackson2JavaTypeMapper();
        typeMapper.setTrustedPackages("*");

        Jackson2JsonMessageConverter converter = new Jackson2JsonMessageConverter(objectMapper);
        converter.setJavaTypeMapper(typeMapper);
        return converter;
    }


    /**
     * 创建监听容器工厂
     * @param connectionFactory
     * @param rabbitMessageConverter
     * @return
     */
    @Bean
    public SimpleRabbitListenerContainerFactory
    videoStatusListenerContainerFactory(
            ConnectionFactory connectionFactory,
            MessageConverter rabbitMessageConverter,
            @Value("${spring.rabbitmq.listener.simple.auto-startup:true}")
            boolean autoStartup
    ) {
        SimpleRabbitListenerContainerFactory factory =
                new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(rabbitMessageConverter);
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        factory.setPrefetchCount(20);
        factory.setConcurrentConsumers(1);
        factory.setMaxConcurrentConsumers(1);
        factory.setDefaultRequeueRejected(false);
        factory.setAutoStartup(autoStartup);
        return factory;
    }
}
