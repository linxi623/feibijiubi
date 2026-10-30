package com.feibijiubi.backend.integration.videosource.transport;

/**
 * E2-C2：一次受限 GET 的<b>协作式</b>总检查预算。
 *
 * <p>用 {@link System#nanoTime()} 的值（单调、只用于计算差值）记住起点，在固定检查点比较已用时间。
 * 检查点由 {@link RestrictedGetExecutor}（受限 GET 的唯一执行器，CMS 与媒体清单客户端共用）与
 * {@link BoundedBodyReader} 负责调用：连接前、收到响应头之后、
 * 每次读取前后、返回之前。</p>
 *
 * <h2>它不保证什么（这段是承诺边界）</h2>
 * <ul>
 *   <li><b>不是硬超时</b>：本类不中断 DNS 查询、不中断 TLS 握手、不关闭 socket、不打断一个阻塞中的
 *       {@code read}。它只在检查点上发现"已经超预算"并抛出固定失败码；一个卡住的阻塞读只能等到
 *       socket 超时（由 {@code RequestConfig#setSocketTimeout} 负责）。</li>
 *   <li><b>不检查就不知道</b>：两次 {@link #check()} 之间的空档可以任意长。所以"总预算 20s"的真实含义是
 *       "不会把明显已经超预算的响应继续交给调用方"，不是"SLA 20 秒"。</li>
 *   <li><b>不设后台任务</b>：没有线程、没有定时器、没有 watchdog；预算只在发起请求的那个线程里被检查。</li>
 * </ul>
 */
final class CmsRequestDeadline {

    private static final long NANOS_PER_MILLI = 1_000_000L;

    /** 时间源 seam：生产用 {@link System#nanoTime()}，单测注入受控时钟以离线验证超预算分支。 */
    @FunctionalInterface
    interface Clock {

        /** @return 单调递增的纳秒读数（只允许用于计算差值，不允许当成墙钟时间） */
        long nanoTime();
    }

    static final Clock SYSTEM_CLOCK = System::nanoTime;

    private final Clock clock;
    private final long startNanos;
    private final long budgetNanos;

    private CmsRequestDeadline(Clock clock, long startNanos, long budgetNanos) {
        this.clock = clock;
        this.startNanos = startNanos;
        this.budgetNanos = budgetNanos;
    }

    /**
     * 以"现在"为起点开启一次预算。
     *
     * @param clock       时间源，不允许为 {@code null}
     * @param budgetMillis 总预算毫秒数，必须是正数
     */
    static CmsRequestDeadline start(Clock clock, long budgetMillis) {
        if (clock == null) {
            throw new IllegalArgumentException("时间源不能为空");
        }
        if (budgetMillis <= 0) {
            throw new IllegalArgumentException("总检查预算必须是正数毫秒");
        }
        return new CmsRequestDeadline(clock, clock.nanoTime(), budgetMillis * NANOS_PER_MILLI);
    }

    /**
     * 在检查点判断是否已超出预算。
     *
     * @throws CmsHttpRequestException {@link CmsHttpRequestException.Reason#TOTAL_BUDGET_EXCEEDED}，
     *                                 当已用时间严格超过预算时；刚好等于预算仍然放行
     */
    void check() {
        if (clock.nanoTime() - startNanos > budgetNanos) {
            throw new CmsHttpRequestException(CmsHttpRequestException.Reason.TOTAL_BUDGET_EXCEEDED);
        }
    }
}
