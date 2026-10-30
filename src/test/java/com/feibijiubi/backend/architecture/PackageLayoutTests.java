package com.feibijiubi.backend.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R6 目录收口的<b>结构回归</b>：只读源码树，不连数据库、不启动 Spring 容器。
 *
 * <p>R1–R5 已把按技术分层（controller/service/mapper/dto/vo/entity…）的顶层目录拆成按业务模块的
 * 包，这一步最容易漏的是“文件搬了但 package 没改”或“旧顶层目录被重新建出来”。所以这里直接读
 * 源码文件：</p>
 * <ul>
 *   <li>每个 {@code .java} 的 {@code package} 声明必须等于它所在的目录路径；</li>
 *   <li>包根（{@code com.feibijiubi.backend}）下只允许批准的业务/基础模块、启动类，以及两个已知例外
 *       {@code event}（类名就是 MQ 类型头的一部分，不能迁）与 {@code config}（全应用装配）；</li>
 *   <li>重构前的顶层技术分层目录不得回到包根（它们只允许作为模块内部子包存在，例如
 *       {@code video/controller}）。</li>
 * </ul>
 *
 * <p>不引入 ArchUnit 等新依赖，也<b>不</b>断言模块之间无环：{@code shared ↔ infrastructure}、
 * {@code video ↔ interaction} 等既有双向依赖在重构前就存在，本批只搬包名，不做服务边界治理。</p>
 *
 * <p><b>清单维护</b>：{@code APPROVED_MODULES}、{@code APPROVED_ROOT_EXCEPTIONS} 与
 * {@code LEGACY_ROOT_DIRECTORIES} 记录的是这次重构收口后的结构，是防回退的护栏，<b>不是</b>“以后不许
 * 新增业务模块/顶层目录”的永久限制。真要新增模块时，把名字加进 {@code APPROVED_MODULES}（或作为新的
 * 显式例外并写明理由）即可；反过来，旧技术分层目录名只有作为模块内部子包才合法，不要加回去。</p>
 */
class PackageLayoutTests {

    private static final Path MAIN_SOURCES = Path.of("src/main/java");
    private static final Path TEST_SOURCES = Path.of("src/test/java");
    private static final Path PRODUCTION_PACKAGE_ROOT = MAIN_SOURCES.resolve("com/feibijiubi/backend");
    private static final Path TEST_PACKAGE_ROOT = TEST_SOURCES.resolve("com/feibijiubi/backend");

    /** 启动类之外的批准模块。 */
    private static final Set<String> APPROVED_MODULES = Set.of(
            "auth", "user", "video", "interaction", "videostats", "videosource",
            "shared", "infrastructure");

    /** 已知例外：{@code event} 的消息类 FQCN 即 RabbitMQ 类型头；{@code config} 是全应用装配。 */
    private static final Set<String> APPROVED_ROOT_EXCEPTIONS = Set.of("config", "event");

    private static final String ENTRY_POINT = "BackendApplication.java";

    /** 重构前的顶层技术分层目录：只能作为模块内部子包，不能回到包根。 */
    private static final Set<String> LEGACY_ROOT_DIRECTORIES = Set.of(
            "annotation", "common", "controller", "converter", "dto", "entity", "enums",
            "integration", "interceptor", "mapper", "mq", "service", "utils", "vo");

    private static final Pattern PACKAGE_DECLARATION =
            Pattern.compile("^\\s*package\\s+([A-Za-z0-9_.]+)\\s*;", Pattern.MULTILINE);

    @Test
    void productionSourcesDeclareThePackageMatchingTheirDirectory() {
        List<Path> sources = assertPackagesMatchDirectories(MAIN_SOURCES);
        assertTrue(sources.contains(MAIN_SOURCES.resolve("com/feibijiubi/backend/BackendApplication.java")),
                "生产源码根必须包含启动类（测试须从项目根目录运行）");
    }

    @Test
    void testSourcesDeclareThePackageMatchingTheirDirectory() {
        List<Path> sources = assertPackagesMatchDirectories(TEST_SOURCES);
        assertTrue(sources.contains(TEST_SOURCES.resolve("com/feibijiubi/backend/BackendApplicationTests.java")),
                "测试源码根必须包含启动类测试（测试须从项目根目录运行）");
    }

    @Test
    void rootPackageHoldsOnlyTheApprovedModulesPlusTheEventAndConfigExceptions() {
        Set<String> expected = new TreeSet<>(APPROVED_MODULES);
        expected.addAll(APPROVED_ROOT_EXCEPTIONS);
        expected.add(ENTRY_POINT);

        assertEquals(expected, childrenOf(PRODUCTION_PACKAGE_ROOT),
                "包根只允许批准的业务/基础模块、启动类，以及 event/config 两个已知例外");
    }

    @Test
    void legacyTopLevelDirectoriesAreGoneFromBothSourceRoots() {
        for (Path packageRoot : List.of(PRODUCTION_PACKAGE_ROOT, TEST_PACKAGE_ROOT)) {
            for (String legacy : LEGACY_ROOT_DIRECTORIES) {
                assertFalse(Files.exists(packageRoot.resolve(legacy)),
                        "旧顶层技术分层目录不得回到包根：" + packageRoot.resolve(legacy));
            }
        }
    }

    /**
     * 逐个源码文件核对 package 声明与目录路径，返回参与核对的源文件（供调用方做锚点断言）。
     */
    private static List<Path> assertPackagesMatchDirectories(Path sourceRoot) {
        assertTrue(Files.isDirectory(sourceRoot),
                "缺少源码根目录（测试须从项目根目录运行）：" + sourceRoot.toAbsolutePath());
        List<Path> sources = javaSourcesUnder(sourceRoot);
        assertFalse(sources.isEmpty(), "没有找到任何 .java：" + sourceRoot.toAbsolutePath());

        List<String> mismatches = new ArrayList<>();
        for (Path source : sources) {
            String expected = packageForDirectory(sourceRoot, source);
            String declared = declaredPackage(source);
            if (!expected.equals(declared)) {
                mismatches.add(sourceRoot.relativize(source) + " 声明 package " + declared
                        + "，按路径应为 " + expected);
            }
        }
        assertEquals(List.of(), mismatches, "package 声明必须与源码路径一致");
        return sources;
    }

    /** 目录相对源码根的路径就是应有包名：{@code .../backend/video/local/entity} → {@code com.feibijiubi.backend.video.local.entity}。 */
    private static String packageForDirectory(Path sourceRoot, Path source) {
        Path directory = sourceRoot.relativize(source).getParent();
        List<String> segments = new ArrayList<>();
        if (directory != null) {
            for (Path segment : directory) {
                segments.add(segment.toString());
            }
        }
        return String.join(".", segments);
    }

    private static String declaredPackage(Path source) {
        String text;
        try {
            text = Files.readString(source, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("无法读取源码：" + source, e);
        }
        Matcher matcher = PACKAGE_DECLARATION.matcher(text);
        return matcher.find() ? matcher.group(1) : "<缺少 package 声明>";
    }

    private static List<Path> javaSourcesUnder(Path sourceRoot) {
        try (Stream<Path> walk = Files.walk(sourceRoot)) {
            return walk.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("无法遍历源码根：" + sourceRoot, e);
        }
    }

    private static Set<String> childrenOf(Path directory) {
        assertTrue(Files.isDirectory(directory), "缺少目录：" + directory.toAbsolutePath());
        try (Stream<Path> children = Files.list(directory)) {
            Set<String> names = new TreeSet<>();
            children.forEach(child -> names.add(child.getFileName().toString()));
            return names;
        } catch (IOException e) {
            throw new UncheckedIOException("无法列出目录：" + directory, e);
        }
    }
}
