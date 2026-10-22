package com.feibijiubi.backend.mq;

/**
 * Outbox 投递失败的类型化异常（T07）。
 *
 * <p>旧实现只有一个 {@code throws Exception}，relay 无法区分“根本没发出去”、
 * “Broker 明确拒绝”和“已经发出但 confirm 结果不确定”。这三类都需要重试，
 * 但日志/诊断（以及 T10 的处置判断）必须能看出是哪一种，因此这里用有限枚举表达。</p>
 *
 * <p><b>日志安全</b>：异常的 {@code message} 只放 {@link Reason} 名称，绝不拼接
 * payload、JSON 片段或 broker 返回的原文。relay 侧也只记录 reason code 与异常类名，
 * 避免把消息体或凭据写进日志。</p>
 */
public class VideoStatusPublishException extends RuntimeException {

    /** 有限、可枚举的投递失败原因。 */
    public enum Reason {
        /** 调用 RabbitTemplate 发送时直接抛异常（连接/通道/序列化等），消息可能未发出。 */
        SEND_FAILED,
        /** Broker 明确 NACK。 */
        NACK,
        /** Broker ACK 但消息无法路由到队列（returned message）。 */
        UNROUTABLE,
        /** 等待 confirm 超时：消息可能已经到达，结果不确定。 */
        CONFIRM_TIMEOUT,
        /** 等待 confirm 被中断：结果不确定。 */
        CONFIRM_INTERRUPTED,
        /** confirm future 以异常完成：结果不确定。 */
        CONFIRM_FAILED
    }

    private final Reason reason;

    public VideoStatusPublishException(Reason reason, Throwable cause) {
        // message 只保留 reason code；原始异常作为 cause 保留栈，但 relay 不打印 cause 的 message。
        super(reason == null ? "UNKNOWN" : reason.name(), cause);
        this.reason = reason == null ? Reason.SEND_FAILED : reason;
    }

    public Reason reason() {
        return reason;
    }

    /** confirm 阶段的结果不确定（超时/中断/异常完成），消息可能已被 Broker 接受。 */
    public boolean confirmUncertain() {
        return reason == Reason.CONFIRM_TIMEOUT
                || reason == Reason.CONFIRM_INTERRUPTED
                || reason == Reason.CONFIRM_FAILED;
    }
}
