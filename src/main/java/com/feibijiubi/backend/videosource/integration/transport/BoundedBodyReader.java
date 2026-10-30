package com.feibijiubi.backend.videosource.integration.transport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * E2-C2：<b>有上界</b>的响应体读取 —— 把一次流读取限制在 {@value #MAX_BODY_BYTES} 字节以内，
 * 直接给离线解析器（{@code AppleCmsJsonAdapter}）当 {@code byte[]} 用。
 *
 * <h2>读多少</h2>
 * <p>最多从流里读 {@code MAX_BODY_BYTES + 1} 字节：正好 {@value #MAX_BODY_BYTES} 字节通过，
 * 出现第 {@code MAX_BODY_BYTES + 1} 个字节就判定超限并抛出
 * {@link CmsHttpRequestException.Reason#RESPONSE_TOO_LARGE}。每次 {@code read} 的请求长度都按
 * "剩余额度 + 1"收窄，因此无论上游如何分块，实际从流里取走的字节数都不会超过上界 + 1。</p>
 *
 * <h2>刻意不用的写法</h2>
 * <ul>
 *   <li>不用 {@code EntityUtils.toByteArray}：它按实体声明的长度（或无限累积）把整个响应读进内存。</li>
 *   <li>不用 {@code InputStream#readAllBytes} / {@code readNBytes(Integer.MAX_VALUE)}：无上界。</li>
 *   <li>不信 {@code Content-Length}：声明长度只用于提前拒绝（由调用方判断），本类仍按<b>实际读到</b>的
 *       字节数计数，"声明 10 字节、实际无限"的响应一样会被上界拦住。</li>
 * </ul>
 *
 * <h2>预算检查</h2>
 * <p>每次 {@code read} 前后各检查一次 {@link CmsRequestDeadline}：中途超预算时抛
 * {@link CmsHttpRequestException.Reason#TOTAL_BUDGET_EXCEEDED}，<b>不会</b>把已经读到的、但整体已过期的
 * 部分字节返回给调用方。仍要说明：本类不打断一个正在阻塞的 {@code read}。</p>
 */
final class BoundedBodyReader {

    /** 响应体上限：512 KiB，与离线解析器的 {@code AppleCmsJsonAdapter.MAX_BODY_BYTES} 同一量级。 */
    static final int MAX_BODY_BYTES = 512 * 1024;

    /** 单次读取的缓冲大小；只是吞吐调优，与上界无关。 */
    private static final int READ_BUFFER_BYTES = 8 * 1024;

    private BoundedBodyReader() {
    }

    /**
     * 读一个已打开的响应体流。
     *
     * @param content  响应体流（调用方负责关闭；本方法不关闭入参）
     * @param deadline 本次请求的总检查预算
     * @return 长度在 {@code 1..MAX_BODY_BYTES} 之间的响应体副本
     * @throws CmsHttpRequestException 超限、空 body、或中途超出总预算
     * @throws IOException            流读取失败；由调用方折叠成固定失败码
     */
    static byte[] read(InputStream content, CmsRequestDeadline deadline) throws IOException {
        ByteArrayOutputStream collected = new ByteArrayOutputStream(READ_BUFFER_BYTES);
        byte[] buffer = new byte[READ_BUFFER_BYTES];
        int total = 0;
        while (true) {
            deadline.check();
            // "剩余额度 + 1"：多读一个字节就足以判定超限，同时保证不会把整个超大响应读进内存。
            int requested = Math.min(buffer.length, MAX_BODY_BYTES - total + 1);
            int read = content.read(buffer, 0, requested);
            deadline.check();
            if (read < 0) {
                break;
            }
            total += read;
            if (total > MAX_BODY_BYTES) {
                throw new CmsHttpRequestException(CmsHttpRequestException.Reason.RESPONSE_TOO_LARGE);
            }
            collected.write(buffer, 0, read);
        }
        if (total == 0) {
            throw new CmsHttpRequestException(CmsHttpRequestException.Reason.EMPTY_BODY);
        }
        return collected.toByteArray();
    }
}
