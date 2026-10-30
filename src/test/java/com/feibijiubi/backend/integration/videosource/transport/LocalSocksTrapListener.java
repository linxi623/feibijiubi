package com.feibijiubi.backend.integration.videosource.transport;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * E2-C3：本机 SOCKS 陷阱监听器 —— 只 bind {@code 127.0.0.1} 的随机回环端口，<b>只数连接、不读不写</b>。
 *
 * <p>用例把 JVM 级 {@code socksProxyHost}/{@code socksProxyPort} 临时指向这个端口：如果受限客户端的原始
 * socket 真的落回 JVM 默认 SOCKS 代理，连接就会打到这里被计数；而它被 {@code new Socket(Proxy.NO_PROXY)}
 * 绕过时，这里应当一个连接都收不到。</p>
 *
 * <h2>观测窗口：从 ready 到 {@link #close()}，而不是固定时长</h2>
 * <p>accept 的每次等待都受 {@value #ACCEPT_POLL_TIMEOUT_MILLIS} ms 的 socket 超时约束，但超时<b>不结束</b>
 * 监听：它只是让 accept 有界返回一次，循环继续。于是陷阱在整个请求期间都在监听，观测窗口的结束点由用例显式
 * 决定（{@link #close()}），而不是由“启动后 2 秒”这种与请求没有因果关系的固定时长决定 —— 那会在请求稍慢时
 * 提前停掉监听，把“漏看”变成假绿。</p>
 *
 * <p>{@link #start()} 返回时接受线程可能还没进 accept，所以用例必须先
 * {@link #awaitReady(long)} 成功再发请求：这就是“明确 ready 再请求”，不是 sleep 也不是无界轮询。</p>
 *
 * <p>本监听器不访问外网、不解析任何名字，只绑定回环地址。</p>
 */
final class LocalSocksTrapListener implements AutoCloseable {

    /** 单次 accept 的有界等待：超时只让循环再检查一次 {@code closed}，不结束监听。 */
    private static final int ACCEPT_POLL_TIMEOUT_MILLIS = 500;

    private static final long CLOSE_JOIN_MILLIS = 5_000L;

    private final ServerSocket serverSocket;
    private final Thread acceptThread;
    private final CountDownLatch ready = new CountDownLatch(1);
    private final Object connectionMonitor = new Object();
    private int acceptedConnections;
    private volatile boolean closed;
    /** {@link #close()} 是否已经成功收尾：成功后重复调用是空操作。 */
    private volatile boolean closedCleanly;

    private LocalSocksTrapListener(ServerSocket serverSocket) {
        this.serverSocket = serverSocket;
        this.acceptThread = new Thread(this::acceptLoop, "local-socks-trap");
        this.acceptThread.setDaemon(true);
    }

    /** 在 {@code 127.0.0.1} 的随机端口上启动陷阱。 */
    static LocalSocksTrapListener start() throws IOException {
        ServerSocket serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 4);
        serverSocket.setSoTimeout(ACCEPT_POLL_TIMEOUT_MILLIS);
        LocalSocksTrapListener listener = new LocalSocksTrapListener(serverSocket);
        listener.acceptThread.start();
        return listener;
    }

    /** 陷阱端口（写进 {@code socksProxyPort} 的那个值）。 */
    int port() {
        return serverSocket.getLocalPort();
    }

    /**
     * 有界等待接受线程真正进入 accept 循环。
     *
     * @return 到期限仍未就绪时返回 {@code false}（调用方必须据此失败，不能当成通过）
     */
    boolean awaitReady(long timeoutMillis) throws InterruptedException {
        return ready.await(timeoutMillis, TimeUnit.MILLISECONDS);
    }

    /** 打进来的连接数：正确行为下应当是 0。 */
    int acceptedConnections() {
        synchronized (connectionMonitor) {
            return acceptedConnections;
        }
    }

    @Override
    public void close() {
        if (closedCleanly) {
            return;
        }
        closed = true;
        try {
            serverSocket.close();
        } catch (IOException e) {
            // 关闭失败不改变结论：下面仍会有界 join，并检查接受线程是否结束。
        }
        try {
            acceptThread.join(CLOSE_JOIN_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("关闭 SOCKS 陷阱时被中断：接受线程未确认结束", e);
        }
        if (acceptThread.isAlive()) {
            throw new IllegalStateException("关闭 SOCKS 陷阱失败：接受线程在 " + CLOSE_JOIN_MILLIS
                    + " ms 内没有结束（interrupt 不能取消阻塞中的 accept）");
        }
        closedCleanly = true;
    }

    private void acceptLoop() {
        ready.countDown();
        while (!closed) {
            try (Socket connection = serverSocket.accept()) {
                // 只计数：本监听器只用来证明"没有连接打到这里"，不回任何协议数据。
                synchronized (connectionMonitor) {
                    acceptedConnections++;
                }
            } catch (SocketTimeoutException e) {
                // 单次 accept 的有界等待到期：只回去复查 closed，观测窗口不因此结束。
            } catch (IOException e) {
                // close() 关掉监听 socket 时 accept 抛 SocketException：正常退出路径。
                return;
            }
        }
    }
}
