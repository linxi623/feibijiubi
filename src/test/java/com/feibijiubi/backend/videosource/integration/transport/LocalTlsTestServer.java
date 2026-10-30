package com.feibijiubi.backend.videosource.integration.transport;

import javax.net.ssl.ExtendedSSLSession;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * E2-C3：本机临时 TLS 测试服务器 —— <b>只 bind 显式 {@code 127.0.0.1} 的随机端口（port 0）</b>，用
 * {@link SSLContext} 建 {@link SSLServerSocket}，然后自己读 HTTP/1.1 请求头、自己写响应。
 *
 * <h2>为什么不用 {@code HttpsServer}</h2>
 * <p>因为本切片要验证的是<b>客户端真实发出的东西</b>：请求行、{@code Host} 头，以及 TLS 层的 SNI。SNI 只能从
 * 服务端 {@link ExtendedSSLSession#getRequestedServerNames()} 读到，而 {@code com.sun.net.httpserver}
 * 不把 {@link SSLSession} 交给 handler。这里不手写 TLS 握手 —— 握手完全由 JDK 的
 * {@code SSLServerSocket}/{@link SSLContext} 完成，本类只做握手之后的应用层字节。</p>
 *
 * <h2>有界与清理</h2>
 * <ul>
 *   <li>accept 循环、每连接的读、写都有上界：连接 socket 读超时
 *       {@value #READ_TIMEOUT_MILLIS} ms，请求头上限 {@value #MAX_REQUEST_HEAD_BYTES} 字节，
 *       send buffer 压到 {@value #SEND_BUFFER_BYTES} 字节（这样“客户端已经关闭连接”能被服务端的写快速观测到）。</li>
 *   <li>{@link #close()} 同时关闭监听 socket（解除 accept 阻塞）和<b>当前已 accept 的连接</b>（解除阻塞在
 *       握手/读/写上的线程），然后有界 join 接受线程；线程没有结束就<b>抛异常让用例明确失败</b> ——
 *       {@code Thread#interrupt()} 不能取消阻塞中的 socket 读写，所以这里不拿它当收尾手段。</li>
 *   <li>连接是<b>顺序</b>处理的（一条处理完才 accept 下一条）：测试都是顺序发请求，没有并发语义要验证。</li>
 * </ul>
 *
 * <h2>握手完成是“可等待”的观测</h2>
 * <p>{@link #awaitHandshakeCompletions(int, long)} 用监视器 + 有界 {@code wait} 等待“服务端侧握手成功次数”
 * 达标，而不是读一个瞬时值：握手在 accept 线程里异步完成，TLS 1.3 下服务端发出自己的 Finished 后就认为握手
 * 完成（不必等到对端的 Finished），所以“用例拿到客户端异常”与“服务端计数已更新”没有先后保证。用例据此区分
 * <b>证书/主机名校验失败</b>（服务端握手确实完成过）与<b>握手根本没跑起来</b>。</p>
 *
 * <h2>它不是被测对象</h2>
 * <p>本类是测试脚手架：它证明"服务端确实收到了这个请求/收到了这个 SNI/确实观测到客户端关闭连接"，不构成对
 * 生产代码的任何断言。</p>
 */
final class LocalTlsTestServer implements AutoCloseable {

    /** 每个连接上应用层读取的上限：客户端连上来却什么都不发时，读不能永久阻塞。 */
    private static final int READ_TIMEOUT_MILLIS = 5_000;

    /** 请求头上限：真实的受控 GET 请求头只有几百字节。 */
    private static final int MAX_REQUEST_HEAD_BYTES = 16 * 1024;

    /** 刻意压小的 send buffer：让“客户端 abort 后服务端写失败”尽早发生，而不是被内核缓冲吞掉。 */
    private static final int SEND_BUFFER_BYTES = 8 * 1024;

    private static final long ACCEPT_THREAD_JOIN_MILLIS = 5_000L;

    /** 一个已接受的连接上要做的事。异常照常抛给 accept 循环（由它记录/关闭）。 */
    @FunctionalInterface
    interface RequestHandler {
        void handle(Connection connection) throws IOException;
    }

    private final SSLServerSocket serverSocket;
    private final RequestHandler handler;
    private final Thread acceptThread;
    private final List<RecordedRequest> requests = Collections.synchronizedList(new ArrayList<>());

    /**
     * 当前已 accept、可能仍阻塞在握手/请求头读/应答写上的连接。{@link #close()} 必须能关掉它，否则接受线程会
     * 一直阻塞到读超时（甚至更久）。用 {@link AtomicReference} 而不是普通字段：accept 线程发布、close() 线程
     * 取走，两者都要无锁地看到对方。
     */
    private final AtomicReference<SSLSocket> activeConnection = new AtomicReference<>();

    /** 连接计数共用一个监视器：读写都在它上面同步，等待者用有界 {@code wait}（不是 sleep 轮询）。 */
    private final Object connectionMonitor = new Object();
    private int acceptedConnections;
    private int handshakeCompletions;
    private int handshakeFailures;
    private int readsAbortedByClose;

    private volatile boolean closed;
    /** {@link #close()} 是否已经成功收尾：成功后重复调用是空操作。 */
    private volatile boolean closedCleanly;

    private LocalTlsTestServer(SSLServerSocket serverSocket, RequestHandler handler) {
        this.serverSocket = serverSocket;
        this.handler = handler;
        this.acceptThread = new Thread(this::acceptLoop, "local-tls-test-server");
        this.acceptThread.setDaemon(true);
    }

    /**
     * 在 {@code 127.0.0.1} 的随机端口上启动。
     *
     * @param serverContext 只含临时证书的服务端上下文
     * @param handler       每条连接的应答行为
     */
    static LocalTlsTestServer start(SSLContext serverContext, RequestHandler handler) throws IOException {
        if (serverContext == null) {
            throw new IllegalArgumentException("服务端 TLS 上下文不能为空");
        }
        if (handler == null) {
            throw new IllegalArgumentException("请求处理器不能为空");
        }
        // 显式绑定回环地址：绝不 bind 0.0.0.0 / ::，端口由内核分配（port 0）。
        SSLServerSocket serverSocket = (SSLServerSocket) serverContext.getServerSocketFactory()
                .createServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
        serverSocket.setReuseAddress(true);
        LocalTlsTestServer server = new LocalTlsTestServer(serverSocket, handler);
        server.acceptThread.start();
        return server;
    }

    /** 实际监听的端口（客户端 base URI 用 host:port 里的那个 port）。 */
    int port() {
        return serverSocket.getLocalPort();
    }

    /** 已记录的应用层请求（不含握手失败、也不含“连上但没发请求就断开”的连接）。 */
    List<RecordedRequest> requests() {
        synchronized (requests) {
            return List.copyOf(requests);
        }
    }

    /** 已接受的 TCP 连接数：用来证明"只发生了一次连接/没有第二次连接"。 */
    int acceptedConnections() {
        synchronized (connectionMonitor) {
            return acceptedConnections;
        }
    }

    /**
     * 服务端侧握手抛 {@link SSLException} 的次数。
     *
     * <p>只作诊断：TLS 1.3 下服务端可能在收到对端的 fatal alert 之前就已经完成自己的握手，因此这个计数
     * <b>不是</b>可靠的断言目标（读到 {@code 0} 的瞬时值什么都不能证明）。用例要断言"握手确实跑起来了"，
     * 应该用 {@link #awaitHandshakeCompletions(int, long)}。</p>
     */
    int handshakeFailures() {
        synchronized (connectionMonitor) {
            return handshakeFailures;
        }
    }

    /** 在 {@link #close()} 收尾期间结束的请求头读次数：被关连接唤醒，不是读超时。 */
    int readsAbortedByClose() {
        synchronized (connectionMonitor) {
            return readsAbortedByClose;
        }
    }

    /** 接受线程是否已经结束（用来断言"没有遗留监听线程"）。 */
    boolean acceptLoopFinished() {
        return !acceptThread.isAlive();
    }

    /** {@link #close()} 是否已经开始：用例的 handler 据此区分“客户端断开”与“服务端自己在收尾”。 */
    boolean isClosed() {
        return closed;
    }

    /**
     * 有界等待"已 accept 的连接数达到 {@code expected}"。
     *
     * @return 到期限仍未达到时返回 {@code false}（调用方必须据此失败，不能当成通过）
     */
    boolean awaitAcceptedConnections(int expected, long timeoutMillis) throws InterruptedException {
        return awaitCounterAtLeast(expected, timeoutMillis, true);
    }

    /**
     * 有界等待"服务端侧握手成功次数达到 {@code expected}"。
     *
     * <p>为什么需要等待而不是读瞬时值：服务端在独立 accept 线程里执行握手并在 startHandshake 正常返回后
     * 更新计数；客户端异常返回与服务端计数更新没有跨线程先后保证，不能用即时读数替代有界等待。</p>
     *
     * @return 到期限仍未达到时返回 {@code false}（调用方必须据此失败，不能当成通过）
     */
    boolean awaitHandshakeCompletions(int expected, long timeoutMillis) throws InterruptedException {
        return awaitCounterAtLeast(expected, timeoutMillis, false);
    }

    private boolean awaitCounterAtLeast(int expected, long timeoutMillis, boolean accepted) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        synchronized (connectionMonitor) {
            while (currentCounter(accepted) < expected) {
                long remainingMillis = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
                if (remainingMillis <= 0L) {
                    return false;
                }
                connectionMonitor.wait(remainingMillis);
            }
            return true;
        }
    }

    private int currentCounter(boolean accepted) {
        return accepted ? acceptedConnections : handshakeCompletions;
    }

    @Override
    public void close() {
        if (closedCleanly) {
            return;
        }
        closed = true;
        // 先关监听 socket（解除 accept 阻塞），再关当前已 accept 的连接（解除阻塞中的握手/读/写）。
        closeQuietly(serverSocket);
        closeQuietly(activeConnection.getAndSet(null));
        try {
            acceptThread.join(ACCEPT_THREAD_JOIN_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("关闭本机 TLS 测试服务器时被中断：接受线程未确认结束", e);
        }
        if (acceptThread.isAlive()) {
            throw new IllegalStateException("关闭本机 TLS 测试服务器失败：接受线程在 "
                    + ACCEPT_THREAD_JOIN_MILLIS + " ms 内没有结束（interrupt 不能取消阻塞中的 socket 读写）");
        }
        closedCleanly = true;
    }

    private void acceptLoop() {
        while (!closed) {
            SSLSocket socket;
            try {
                socket = (SSLSocket) serverSocket.accept();
            } catch (IOException e) {
                // close() 关掉监听 socket 时 accept 抛 SocketException：正常退出路径。其它 IO 失败也结束循环。
                return;
            }
            // 先发布再复查 closed：close() 可能在 accept 返回与发布之间把 closed 置位并 getAndSet 掉当时的值，
            // 那时它看不到这条刚 accept 的连接，所以发布之后必须自己再检查一次。
            activeConnection.set(socket);
            if (closed) {
                if (activeConnection.compareAndSet(socket, null)) {
                    closeQuietly(socket);
                }
                return;
            }
            synchronized (connectionMonitor) {
                acceptedConnections++;
                connectionMonitor.notifyAll();
            }
            try {
                handleConnection(socket);
            } finally {
                // 只清自己这条连接：compareAndSet 不会误清 close() 之后才发布的下一条连接。
                activeConnection.compareAndSet(socket, null);
            }
        }
    }

    private void handleConnection(SSLSocket socket) {
        try (SSLSocket connection = socket) {
            connection.setSoTimeout(READ_TIMEOUT_MILLIS);
            connection.setSendBufferSize(SEND_BUFFER_BYTES);
            try {
                connection.startHandshake();
            } catch (SSLException e) {
                recordHandshakeFailure();
                return;
            }
            // 服务端侧握手已完成：这是"错 host / 不信任证书"用例的正向证据（客户端拒绝的是证书/主机名，
            // 而不是握手根本没跑起来）。
            recordHandshakeCompletion();
            String sniServerName = requestedServerName(connection.getSession());
            String requestHead;
            try {
                requestHead = readRequestHead(connection.getInputStream());
            } catch (SocketTimeoutException e) {
                // 有界读超时：客户端连上来却不发请求，直接关闭连接。
                return;
            } catch (IOException e) {
                if (closed) {
                    // 被 close() 主动关掉连接时唤醒的读：证明 close() 真的关了已 accept 的连接，而不是靠超时。
                    recordReadEndedByClose();
                }
                return;
            }
            if (requestHead == null) {
                // 客户端在发请求前就断开了（例如主机名校验失败后 abort）：不算一条已完成请求。
                if (closed) {
                    // 若此刻服务端已经在收尾（close()），这次读就是被“关连接”唤醒的，而不是读超时。
                    recordReadEndedByClose();
                }
                return;
            }
            RecordedRequest request = RecordedRequest.parse(requestHead, sniServerName);
            requests.add(request);
            try {
                handler.handle(new Connection(request, connection.getOutputStream()));
            } catch (IOException e) {
                // 客户端提前 abort（含超限后 abort）会让服务端写失败：需要观测它的用例在 handler 里自己记录。
            }
        } catch (IOException e) {
            // 连接参数设置、握手期的非 SSL IO 失败、或关闭时的 IO 失败：不改变用例结论。
        }
    }

    private void recordHandshakeCompletion() {
        synchronized (connectionMonitor) {
            handshakeCompletions++;
            connectionMonitor.notifyAll();
        }
    }

    private void recordHandshakeFailure() {
        synchronized (connectionMonitor) {
            handshakeFailures++;
        }
    }

    private void recordReadEndedByClose() {
        synchronized (connectionMonitor) {
            readsAbortedByClose++;
        }
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException e) {
            // 关闭失败不改变结论：close() 之后仍会有界 join 并检查接受线程是否结束。
        }
    }

    /** 服务端视角的 SNI：JDK 没收到 SNI 时返回 {@code null}（用例据此失败，而不是改成"通过"）。 */
    private static String requestedServerName(SSLSession session) {
        if (!(session instanceof ExtendedSSLSession extendedSession)) {
            return null;
        }
        for (SNIServerName serverName : extendedSession.getRequestedServerNames()) {
            if (serverName instanceof SNIHostName hostName) {
                return hostName.getAsciiName();
            }
        }
        return null;
    }

    /** 读到 {@code \r\n\r\n} 为止（含），超过上限抛 {@link IOException}；EOF 前没读完返回 {@code null}。 */
    private static String readRequestHead(InputStream inputStream) throws IOException {
        byte[] terminator = {'\r', '\n', '\r', '\n'};
        ByteArrayOutputStream collected = new ByteArrayOutputStream(512);
        int matched = 0;
        int read;
        while ((read = inputStream.read()) >= 0) {
            collected.write(read);
            if ((byte) read == terminator[matched]) {
                matched++;
            } else {
                matched = (byte) read == terminator[0] ? 1 : 0;
            }
            if (matched == terminator.length) {
                return collected.toString(StandardCharsets.US_ASCII);
            }
            if (collected.size() > MAX_REQUEST_HEAD_BYTES) {
                throw new IOException("请求头超过测试服务器的读取上限");
            }
        }
        return null;
    }

    /**
     * 服务端记录到的一条请求：请求行、{@code Host} 头、该连接的 SNI 名，以及全部原始头行。
     */
    record RecordedRequest(String requestLine, String hostHeader, String sniServerName,
                           List<String> headerLines) {

        static RecordedRequest parse(String requestHead, String sniServerName) {
            String[] lines = requestHead.split("\r\n");
            String requestLine = lines.length > 0 ? lines[0] : "";
            String hostHeader = null;
            List<String> headerLines = new ArrayList<>();
            for (int i = 1; i < lines.length; i++) {
                String line = lines[i];
                if (line.isEmpty()) {
                    continue;
                }
                headerLines.add(line);
                int colon = line.indexOf(':');
                if (colon > 0 && "host".equalsIgnoreCase(line.substring(0, colon).trim())) {
                    hostHeader = line.substring(colon + 1).trim();
                }
            }
            return new RecordedRequest(requestLine, hostHeader, sniServerName, List.copyOf(headerLines));
        }

        /** 是否带有指定头（名字大小写无关，值是精确匹配）。 */
        boolean hasHeader(String name, String value) {
            String prefix = name.toLowerCase() + ":";
            for (String line : headerLines) {
                String lowerLine = line.toLowerCase();
                if (lowerLine.startsWith(prefix)
                        && line.substring(prefix.length()).trim().equals(value)) {
                    return true;
                }
            }
            return false;
        }
    }

    /** 一条已接受连接上的应答写入器：固定长度响应或 chunked 响应。 */
    static final class Connection {

        private final RecordedRequest request;
        private final OutputStream outputStream;
        private boolean chunked;

        private Connection(RecordedRequest request, OutputStream outputStream) {
            this.request = request;
            this.outputStream = outputStream;
        }

        RecordedRequest request() {
            return request;
        }

        /** 写一个 {@code Content-Length} 固定的响应并 flush。 */
        void sendFixed(int status, String reason, List<String> headerLines, byte[] body) throws IOException {
            StringBuilder head = new StringBuilder();
            head.append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n");
            for (String line : headerLines) {
                head.append(line).append("\r\n");
            }
            head.append("Content-Length: ").append(body.length).append("\r\n");
            head.append("Connection: close\r\n\r\n");
            outputStream.write(head.toString().getBytes(StandardCharsets.US_ASCII));
            outputStream.write(body);
            outputStream.flush();
        }

        /** 开始一个 {@code Transfer-Encoding: chunked} 的 200 响应（长度未知，客户端只能按字节数判上限）。 */
        void startChunkedResponse(List<String> headerLines) throws IOException {
            StringBuilder head = new StringBuilder();
            head.append("HTTP/1.1 200 OK\r\n");
            for (String line : headerLines) {
                head.append(line).append("\r\n");
            }
            head.append("Transfer-Encoding: chunked\r\n");
            head.append("Connection: close\r\n\r\n");
            outputStream.write(head.toString().getBytes(StandardCharsets.US_ASCII));
            outputStream.flush();
            chunked = true;
        }

        /** 写一个 chunk 并 flush；客户端已关闭连接时这里会抛 {@link IOException}（用例据此观测"断流"）。 */
        void writeChunk(byte[] data) throws IOException {
            if (!chunked) {
                throw new IllegalStateException("必须先调用 startChunkedResponse");
            }
            outputStream.write((Integer.toHexString(data.length) + "\r\n").getBytes(StandardCharsets.US_ASCII));
            outputStream.write(data);
            outputStream.write("\r\n".getBytes(StandardCharsets.US_ASCII));
            outputStream.flush();
        }
    }
}
