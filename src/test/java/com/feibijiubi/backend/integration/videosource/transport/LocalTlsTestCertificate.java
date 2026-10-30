package com.feibijiubi.backend.integration.videosource.transport;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * E2-C3：本机临时 TLS 测试用的<b>一次性自签证书</b> —— 由 JDK 自带 {@code keytool} 在 JUnit
 * {@code @TempDir} 里生成 PKCS12，进程退出后随临时目录消失。
 *
 * <h2>只做三件事</h2>
 * <ol>
 *   <li>用 {@link ProcessBuilder} 直接调 {@code <java.home>/bin/keytool(.exe)} 生成一张
 *       {@code SAN=dns:api.example.invalid}、有效期 1 天的 RSA 自签证书（PKCS12 单别名）。</li>
 *   <li>把 keytool 的等待上限固定为 30 秒；超时就 {@code destroyForcibly()} 并再有界 {@code waitFor}，
 *       失败时只带<b>截断且去掉临时密钥库路径</b>的输出片段，不贴整段进程输出。有界等待没等到退出时会写进固定
 *       文案里，<b>不声称</b>操作系统一定回收了那个进程。</li>
 *   <li>从同一个密钥库派生两个 {@link SSLContext}：服务端只加载这张临时证书的私钥，客户端只信任
 *       <b>这一张</b>证书（{@code setCertificateEntry} 的单条信任锚）。没有 trust-all 策略，没有
 *       {@code NoopHostnameVerifier}，不改 JVM 默认信任库。</li>
 * </ol>
 *
 * <h2>不是生产凭据</h2>
 * <p>口令是测试常量，只保护这个临时文件；它既不是真实密钥，也不来自任何配置文件。证书域名
 * {@code api.example.invalid} 是 RFC 2606 保留域，永远不会解析到真实主机；本测试的真实连接只发生在
 * {@code 127.0.0.1} 的随机回环端口上。</p>
 *
 * <p>刻意不做的事：不下载任何证书、不写 ASN.1、不引入 bouncycastle，也不读取系统 truststore 内容。</p>
 */
final class LocalTlsTestCertificate {

    /** 临时证书的唯一 SAN（同时也是 CN）：与 URL 策略允许列表里的 host 全等。 */
    static final String HOST = "api.example.invalid";

    private static final String ALIAS = "cms-local-tls";
    private static final String STORE_TYPE = "PKCS12";
    private static final String TLS_PROTOCOL = "TLS";

    /** 测试临时口令：只保护临时密钥库文件，不是任何真实环境的 secret。 */
    private static final char[] PASSWORD = "local-tls-test-only".toCharArray();

    private static final int KEY_SIZE = 2_048;
    private static final int VALIDITY_DAYS = 1;
    private static final long KEYTOOL_TIMEOUT_SECONDS = 30L;
    private static final long KEYTOOL_DESTROY_WAIT_SECONDS = 5L;
    private static final long OUTPUT_READER_JOIN_MILLIS = 2_000L;
    private static final int OUTPUT_EXCERPT_LIMIT = 2_000;

    private final KeyStore keyStore;

    private LocalTlsTestCertificate(KeyStore keyStore) {
        this.keyStore = keyStore;
    }

    /**
     * 在给定临时目录里生成临时证书并加载成密钥库。
     *
     * @param tempDirectory JUnit {@code @TempDir} 给出的临时目录（调用方负责清理）
     * @throws IOException              keytool 启动失败或密钥库文件读写失败
     * @throws GeneralSecurityException 密钥库/上下文初始化失败
     * @throws IllegalStateException    keytool 超时或非 0 退出（文案只带截断后的输出片段）
     */
    static LocalTlsTestCertificate generate(Path tempDirectory) throws IOException, GeneralSecurityException {
        if (tempDirectory == null) {
            throw new IllegalArgumentException("临时目录不能为空");
        }
        Path keyStoreFile = tempDirectory.resolve(ALIAS + ".p12");
        runKeytool(keyStoreFile);
        if (!Files.isRegularFile(keyStoreFile)) {
            throw new IllegalStateException("keytool 报告成功但没有生成临时密钥库文件");
        }
        KeyStore keyStore = KeyStore.getInstance(STORE_TYPE);
        try (InputStream inputStream = Files.newInputStream(keyStoreFile)) {
            keyStore.load(inputStream, PASSWORD);
        }
        return new LocalTlsTestCertificate(keyStore);
    }

    /**
     * 服务端上下文：只加载这张临时证书的私钥/证书链，不要求客户端证书。
     */
    SSLContext newServerContext() throws GeneralSecurityException {
        KeyManagerFactory keyManagerFactory =
                KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagerFactory.init(keyStore, PASSWORD);
        SSLContext sslContext = SSLContext.getInstance(TLS_PROTOCOL);
        sslContext.init(keyManagerFactory.getKeyManagers(), null, null);
        return sslContext;
    }

    /**
     * 客户端上下文：信任锚只有这一张临时证书（从同一个密钥库复制出的单条证书条目）。
     *
     * <p>这样客户端只能接受本测试自己签发的证书，JVM 默认信任库里的任何 CA 都不参与；反过来，默认信任库
     * 里也没有这张自签证书，所以生产工厂（{@code SSLContexts.createDefault()}）必然拒绝它。</p>
     */
    SSLContext newClientTrustContext() throws GeneralSecurityException, IOException {
        KeyStore trustStore = KeyStore.getInstance(STORE_TYPE);
        trustStore.load(null, null);
        trustStore.setCertificateEntry(ALIAS, keyStore.getCertificate(ALIAS));
        TrustManagerFactory trustManagerFactory =
                TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagerFactory.init(trustStore);
        SSLContext sslContext = SSLContext.getInstance(TLS_PROTOCOL);
        sslContext.init(null, trustManagerFactory.getTrustManagers(), null);
        return sslContext;
    }

    private static void runKeytool(Path keyStoreFile) throws IOException {
        List<String> command = new ArrayList<>();
        command.add(keytoolExecutable().toString());
        command.add("-genkeypair");
        command.add("-alias");
        command.add(ALIAS);
        command.add("-keyalg");
        command.add("RSA");
        command.add("-keysize");
        command.add(Integer.toString(KEY_SIZE));
        command.add("-sigalg");
        command.add("SHA256withRSA");
        command.add("-validity");
        command.add(Integer.toString(VALIDITY_DAYS));
        command.add("-storetype");
        command.add(STORE_TYPE);
        command.add("-keystore");
        command.add(keyStoreFile.toString());
        command.add("-storepass");
        command.add(new String(PASSWORD));
        command.add("-keypass");
        command.add(new String(PASSWORD));
        command.add("-dname");
        command.add("CN=" + HOST);
        command.add("-ext");
        command.add("SAN=dns:" + HOST);
        command.add("-noprompt");

        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        // 关掉子进程 stdin：keytool 若因任何原因要交互确认，会立刻失败而不是把测试挂住。
        process.getOutputStream().close();
        BoundedOutputReader output = BoundedOutputReader.start(process.getInputStream());
        try {
            if (!process.waitFor(KEYTOOL_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                boolean exited = destroyBounded(process);
                output.awaitUninterruptibly(OUTPUT_READER_JOIN_MILLIS);
                throw new IllegalStateException("keytool 生成临时证书超时（" + KEYTOOL_TIMEOUT_SECONDS
                        + " 秒）" + (exited
                        ? "，已强制结束进程"
                        : "，强制结束后 " + KEYTOOL_DESTROY_WAIT_SECONDS
                        + " 秒内仍未退出（不声称操作系统一定回收该进程）")
                        + output.excerptSuffix(keyStoreFile));
            }
            output.awaitUninterruptibly(OUTPUT_READER_JOIN_MILLIS);
            int exitCode = process.exitValue();
            if (exitCode != 0) {
                throw new IllegalStateException("keytool 生成临时证书失败，退出码 " + exitCode
                        + output.excerptSuffix(keyStoreFile));
            }
        } catch (InterruptedException e) {
            // 中断清理路径：先做有界强制结束与有界 join，再恢复中断标志后抛出 —— 不吞中断，也不自旋重试。
            destroyBoundedAfterInterrupt(process);
            output.awaitUninterruptibly(OUTPUT_READER_JOIN_MILLIS);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待 keytool 生成临时证书时被中断", e);
        } finally {
            // 无论成功/超时/中断都要关掉子进程的流：否则输出读线程会一直挂在 read 上。
            closeQuietly(process.getInputStream());
            closeQuietly(process.getErrorStream());
            closeQuietly(process.getOutputStream());
        }
    }

    /**
     * 强制结束子进程并做<b>有界</b>等待。
     *
     * @return {@code true} 表示等待窗口内已经退出；{@code false} 表示窗口内仍未退出（本方法不声称操作系统一定
     *         回收了该进程，只把结果交给调用方写进固定文案）
     */
    private static boolean destroyBounded(Process process) {
        process.destroyForcibly();
        try {
            return process.waitFor(KEYTOOL_DESTROY_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 中断清理路径上的有界强制结束：为了真的等到期限，先清掉中断标志，再在 {@code finally} 里恢复。
     *
     * <p>不自旋：只做一次有界等待；等待期间再次被中断就按“未退出”处理，中断标志由调用方统一恢复。</p>
     */
    private static boolean destroyBoundedAfterInterrupt(Process process) {
        process.destroyForcibly();
        boolean interrupted = Thread.interrupted();
        try {
            return process.waitFor(KEYTOOL_DESTROY_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            return false;
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException e) {
            // 关闭失败不改变判定：真正的判定依据是退出码和密钥库文件是否存在。
        }
    }

    /**
     * 只用 JDK 自带的 keytool：路径从 {@code java.home} 推出，Windows 上优先 {@code keytool.exe}。
     */
    private static Path keytoolExecutable() {
        String javaHome = System.getProperty("java.home");
        if (javaHome == null || javaHome.isBlank()) {
            throw new IllegalStateException("系统属性 java.home 缺失：无法定位 JDK 自带 keytool");
        }
        Path binDirectory = Path.of(javaHome).resolve("bin");
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
        List<Path> candidates = windows
                ? List.of(binDirectory.resolve("keytool.exe"), binDirectory.resolve("keytool"))
                : List.of(binDirectory.resolve("keytool"), binDirectory.resolve("keytool.exe"));
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("在 JDK 的 bin 目录下找不到 keytool：本测试只用 JDK 自带工具生成临时证书");
    }

    /**
     * 有界读取 keytool 输出的守护线程：最多保留 {@value #OUTPUT_EXCERPT_LIMIT} 个字符，避免失败时把整段
     * 进程输出贴进断言消息。
     */
    private static final class BoundedOutputReader {

        private final StringBuilder collected = new StringBuilder();
        private Thread thread;

        private BoundedOutputReader() {
        }

        static BoundedOutputReader start(InputStream inputStream) {
            BoundedOutputReader reader = new BoundedOutputReader();
            reader.thread = new Thread(() -> reader.drain(inputStream), "keytool-output-reader");
            reader.thread.setDaemon(true);
            reader.thread.start();
            return reader;
        }

        void awaitUninterruptibly(long millis) {
            boolean interrupted = Thread.interrupted();
            try {
                thread.join(millis);
            } catch (InterruptedException e) {
                interrupted = true;
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        /** 输出片段：截断 + 去掉临时密钥库绝对路径，不含任何口令（keytool 从不回显口令）。 */
        String excerptSuffix(Path keyStoreFile) {
            String excerpt;
            synchronized (collected) {
                excerpt = collected.toString().trim();
            }
            if (excerpt.isEmpty()) {
                return "（keytool 没有输出内容）";
            }
            return "；keytool 输出（已截断、已去掉临时密钥库路径）："
                    + excerpt.replace(keyStoreFile.toString(), "<临时密钥库>");
        }

        private void drain(InputStream inputStream) {
            try (BufferedReader reader =
                         new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    synchronized (collected) {
                        if (collected.length() < OUTPUT_EXCERPT_LIMIT) {
                            collected.append(line).append('\n');
                        }
                    }
                }
            } catch (IOException e) {
                // 读输出失败不影响判定：真正的判定依据是退出码和密钥库文件是否存在。
            }
        }
    }
}
