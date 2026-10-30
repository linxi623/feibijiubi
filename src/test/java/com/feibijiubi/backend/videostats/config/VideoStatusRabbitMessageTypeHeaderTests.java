package com.feibijiubi.backend.videostats.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.feibijiubi.backend.event.VideoStatusChangedEvent;
import com.feibijiubi.backend.event.VideoStatusEventType;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.support.converter.DefaultJackson2JavaTypeMapper;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.amqp.support.converter.MessageConversionException;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R4 消息协议闸门的<b>离线回归</b>：固定统计事件的 Rabbit 序列化契约。
 *
 * <p>审计结论（详见 {@code docs/refactoring/r4-package-map.json} 的 {@code messageCompatibility}）：
 * 生产唯一 converter 是 {@link VideoStatusRabbitConfig#rabbitMessageConverter} →
 * {@link Jackson2JsonMessageConverter} + {@link DefaultJackson2JavaTypeMapper}，Spring Boot 的
 * {@code RabbitTemplateConfigurer} 把它装到自动配置的 {@code RabbitTemplate} 上，因此
 * {@code VideoStatusEventPublisher} 的 {@code convertAndSend} 会写入
 * {@code __TypeId__=<payload 类 FQCN>}；消费端 {@code @RabbitListener} 的
 * {@code (org.springframework.amqp.core.Message, Channel)} 参数不是 payload 参数，converter 因此回落到
 * {@code __TypeId__} 解析类。搬运 payload 类会同时改变出站类型头并让在途旧头消息无法解析，
 * 所以 {@code com.feibijiubi.backend.event} 的三个类按父级裁定<b>保持原路径</b>。</p>
 *
 * <p>本类锁住的就是这份契约：出站类型头仍是原 FQCN、原 FQCN 仍可解析回事件对象、converter 没有新增
 * id→class 映射（没有兼容别名层）、消息体字段仍是按名序列化、不存在的类名仍然失败（说明“类必须存在”
 * 不是可以忽略的细节）。不依赖真实 broker、不启动 Spring 容器、不连数据库。</p>
 */
class VideoStatusRabbitMessageTypeHeaderTests {

    /** 消息协议里的类型头名，不是可随手改的常量：已发出的消息带着它。 */
    private static final String TYPE_ID_HEADER = "__TypeId__";
    /** 已部署消息里的类型头值，必须保持可解析。 */
    private static final String LEGACY_EVENT_FQCN =
            "com.feibijiubi.backend.event.VideoStatusChangedEvent";

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void publishedEventCarriesTheLegacyEventFqcnAsTypeId() {
        Message message = converter().toMessage(event(), new MessageProperties());

        assertEquals(LEGACY_EVENT_FQCN, VideoStatusChangedEvent.class.getName(),
                "统计事件的 wire 类必须留在原包，否则 __TypeId__ 会变");
        assertEquals(LEGACY_EVENT_FQCN,
                message.getMessageProperties().getHeaders().get(TYPE_ID_HEADER),
                "出站消息的 __TypeId__ 必须是原 FQCN");
        assertEquals(MessageProperties.CONTENT_TYPE_JSON,
                message.getMessageProperties().getContentType(),
                "内容类型必须仍是 application/json");
    }

    @Test
    void messageWithTheLegacyTypeIdStillResolvesToTheEventClass() {
        Message message = converter().toMessage(event(), new MessageProperties());
        message.getMessageProperties().setHeader(TYPE_ID_HEADER, LEGACY_EVENT_FQCN);

        Object payload = converter().fromMessage(message);

        VideoStatusChangedEvent restored = assertInstanceOf(VideoStatusChangedEvent.class, payload,
                "携带原 FQCN 的在途消息必须仍能反序列化");
        assertEquals(event(), restored, "反序列化结果必须与发布前一致");
    }

    @Test
    void unknownTypeIdFailsClassResolution() {
        Message message = converter().toMessage(event(), new MessageProperties());
        message.getMessageProperties().setHeader(TYPE_ID_HEADER,
                "com.feibijiubi.backend.legacy.VideoStatusChangedEvent");

        MessageConversionException error = assertThrows(MessageConversionException.class,
                () -> converter().fromMessage(message),
                "类型头指向不存在的类时必须失败，不能静默丢字段或退化成 Map");
        assertTrue(String.valueOf(error.getMessage()).contains("Class not found"),
                "失败原因必须点名类不存在：" + error.getMessage());
    }

    @Test
    void converterAddsNoClassIdMappingAndKeepsBodyFieldBased() {
        Jackson2JsonMessageConverter converter =
                assertInstanceOf(Jackson2JsonMessageConverter.class, converter(),
                        "生产 converter 必须仍是 Jackson2JsonMessageConverter");
        DefaultJackson2JavaTypeMapper typeMapper =
                assertInstanceOf(DefaultJackson2JavaTypeMapper.class,
                        converter.getJavaTypeMapper(),
                        "生产 converter 必须仍使用 DefaultJackson2JavaTypeMapper");

        assertTrue(typeMapper.getIdClassMapping().isEmpty(),
                "不得新增 id→class 兼容映射：本轮不做协议兼容层");

        String body = new String(converter.toMessage(event(), new MessageProperties()).getBody(),
                StandardCharsets.UTF_8);
        assertTrue(body.contains("\"eventId\""), "消息体仍按字段名序列化：" + body);
        assertFalse(body.contains("com.feibijiubi"),
                "消息体不得包含包名，包归属只能通过 __TypeId__ 影响协议：" + body);
    }

    private MessageConverter converter() {
        return new VideoStatusRabbitConfig().rabbitMessageConverter(objectMapper);
    }

    private static VideoStatusChangedEvent event() {
        VideoStatusEventType type = VideoStatusEventType.LIKE;
        return new VideoStatusChangedEvent(
                "evt-r4-protocol",
                42,
                type,
                1L,
                type.calculateHotScore(1L),
                LocalDateTime.of(2026, 1, 2, 3, 4, 5),
                2,
                "trace-r4"
        );
    }
}
