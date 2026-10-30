package com.feibijiubi.backend.architecture;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ResultMap;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * R6 全局 Mapper 装配回归：证明 16 个 mapper XML 与迁移后的接口<b>在运行时真的绑得上</b>，而不是只看
 * 文本里有没有旧包名。
 *
 * <p>背景：R1–R5 把 mapper 接口按模块搬走（{@code user.mapper}、{@code video.mapper}、
 * {@code video.local.mapper}、{@code interaction.mapper}、{@code videosource.mapper}、
 * {@code videostats.mapper}、{@code videostats.metrics}），但父级裁定 R6 <b>保留全部 XML 的集中
 * 物理路径</b> {@code src/main/resources/com/feibijiubi/backend/mapper/}，以兼容部署期
 * {@code mybatis.mapper-locations=classpath:com/feibijiubi/backend/mapper/*.xml}。所以这条约定必须
 * 由测试守住：</p>
 * <ul>
 *   <li><b>classpath 资源发现</b>：用与本工程 mapper-locations 相同的
 *       {@link PathMatchingResourcePatternResolver} 模式解析，集合必须与源码目录一一对应；每个资源
 *       的 URL 必须仍落在 {@code com/feibijiubi/backend/mapper/} 下（打包成 jar 后也是这个路径），
 *       且内容与 {@code src/main/resources} 里的文件逐字节相同（没有过滤/替换偷偷改过 XML）；</li>
 *   <li><b>真实 MyBatis 解析</b>：16 个 XML 一起交给 {@link XMLMapperBuilder} 解析进同一个
 *       {@link Configuration}，每个 namespace 都注册成 mapper 接口，接口的每个方法都有同名
 *       {@code MappedStatement}；</li>
 *   <li><b>逐 statement 与 resultType</b>：每个 MappedStatement 的 namespace 属于已解析集合、SQL
 *       source 非空、resultMap 类型已解析成真实类，且不落在旧集中包 {@code com.feibijiubi.backend.mapper}
 *       下；</li>
 *   <li><b>扫描注册</b>：{@code @Mapper} 扫描根 {@code com.feibijiubi.backend} 找到的接口集合必须与
 *       XML namespace 集合一模一样（16 对 16），且全部落在上列模块包内。</li>
 *   <li><b>隔离测试的日志级别</b>：XML namespace 推导出的每个 mapper 包都必须在
 *       {@code src/test/resources/video-status-isolated.properties} 里有一条<b>显式</b>
 *       {@code logging.level.<包>=WARN}（日志级别按包层级继承，旧 mapper 包不覆盖其兄弟模块包）。</li>
 * </ul>
 *
 * <p>不连数据库、不启动 Spring 容器、不读 {@code application.yml}：只证明“资源位置 + namespace +
 * 接口注册 + statement/resultType 解析”这一串装配关系没有断。</p>
 *
 * <p><b>清单维护</b>：{@code APPROVED_MAPPER_PACKAGES} 与“16 对 16”记录的是本次 R1–R5 迁移后的
 * 当前结构，是防回退的护栏，不是“以后不许新增 mapper”的永久业务限制。新增/迁移 mapper 时，把新包
 * 加进本类清单、并在隔离测试配置里补一行对应 WARN 即可（两条改动都属于新 mapper 的一部分）；
 * 反过来，如果先看到这两处断言失败，先确认真实 mapper 是否已经新增。</p>
 */
class MyBatisMapperResourceTests {

    /** 与 {@code mybatis.mapper-locations} 相同的解析模式（父级 R6 裁定 XML 物理路径不变）。 */
    private static final String MAPPER_LOCATIONS_PATTERN =
            "classpath:com/feibijiubi/backend/mapper/*.xml";

    /** 源码侧的同一条路径，用于证明资源是被原样复制到 classpath 的。 */
    private static final Path MAPPER_SOURCE_DIR =
            Path.of("src/main/resources/com/feibijiubi/backend/mapper");

    private static final String RESOURCE_DIRECTORY = "com/feibijiubi/backend/mapper/";
    private static final String SCAN_ROOT = "com.feibijiubi.backend";
    private static final String LEGACY_MAPPER_PACKAGE = "com.feibijiubi.backend.mapper";

    private static final Pattern MAPPER_NAMESPACE =
            Pattern.compile("<mapper\\s+namespace=\"([^\"]+)\"");

    /** R1–R5 之后 mapper 接口允许出现的模块包。 */
    private static final Set<String> APPROVED_MAPPER_PACKAGES = Set.of(
            "com.feibijiubi.backend.user.mapper",
            "com.feibijiubi.backend.video.mapper",
            "com.feibijiubi.backend.video.local.mapper",
            "com.feibijiubi.backend.interaction.mapper",
            "com.feibijiubi.backend.videosource.mapper",
            "com.feibijiubi.backend.videostats.mapper",
            "com.feibijiubi.backend.videostats.metrics");

    @Test
    void allMapperXmlFilesAreDiscoverableAtTheConfiguredClasspathLocation() throws IOException {
        Set<String> sourceFiles = sourceXmlFileNames();

        Set<String> classpathFiles = new TreeSet<>();
        for (Resource resource : mapperResources()) {
            String fileName = resource.getFilename();
            assertNotNull(fileName, "mapper 资源必须有文件名：" + resource);
            assertTrue(classpathFiles.add(fileName), "同一 XML 在 classpath 上出现了多次：" + fileName);

            String url = resource.getURL().toExternalForm().replace('\\', '/');
            assertTrue(url.endsWith(RESOURCE_DIRECTORY + fileName),
                    "XML 必须仍位于 classpath:com/feibijiubi/backend/mapper/ 下，实际：" + url);

            Path source = MAPPER_SOURCE_DIR.resolve(fileName);
            assertTrue(Files.isRegularFile(source), "缺少 mapper 源文件：" + source.toAbsolutePath());
            try (InputStream in = resource.getInputStream()) {
                assertArrayEquals(Files.readAllBytes(source), in.readAllBytes(),
                        "classpath 上的 XML 必须与 src/main/resources 的同名文件逐字节一致：" + fileName);
            }
        }

        assertEquals(sourceFiles, classpathFiles,
                "src/main/resources 的 mapper XML 集合与 classpath 解析结果必须一致");
    }

    @Test
    void allMapperXmlFilesParseIntoOneMyBatisConfiguration() throws Exception {
        Map<String, String> namespacesByFile = xmlNamespacesByFile();
        Configuration configuration = parseAllMappers();

        assertEquals(namespacesByFile.size(), configuration.getMapperRegistry().getMappers().size(),
                "每个 XML 的 namespace 都必须注册成一个 mapper 接口：" + namespacesByFile.values());

        for (Map.Entry<String, String> entry : namespacesByFile.entrySet()) {
            String namespace = entry.getValue();
            assertTrue(isApprovedMapperPackage(namespace),
                    entry.getKey() + " 的 namespace 必须指向 R1–R5 迁移后的模块包，实际：" + namespace);
            assertFalse(namespace.startsWith(LEGACY_MAPPER_PACKAGE + "."),
                    entry.getKey() + " 的 namespace 不得回到旧集中包：" + namespace);

            Class<?> mapperInterface = Class.forName(namespace,
                    false, Thread.currentThread().getContextClassLoader());
            assertTrue(mapperInterface.isInterface(), "mapper 必须是接口：" + namespace);
            assertNotNull(mapperInterface.getAnnotation(Mapper.class),
                    "mapper 接口必须保留 @Mapper，否则 mybatis-spring 自动扫描注册不到：" + namespace);
            assertTrue(configuration.hasMapper(mapperInterface),
                    "XML namespace 必须已把接口注册进 Configuration：" + namespace);

            for (Method method : mapperInterface.getMethods()) {
                if (method.isSynthetic() || Modifier.isStatic(method.getModifiers())) {
                    continue;
                }
                assertTrue(configuration.hasStatement(namespace + "." + method.getName()),
                        "接口方法缺少同名 MappedStatement：" + namespace + "." + method.getName());
            }
        }
    }

    @Test
    void everyDeclaredStatementIsRegisteredWithResolvableTypes() throws Exception {
        Map<String, String> namespacesByFile = xmlNamespacesByFile();
        Set<String> namespaces = new LinkedHashSet<>(namespacesByFile.values());
        Configuration configuration = parseAllMappers();

        // MyBatis 的 StrictMap 除了 statement 全名还会存短名（selectByVid 之类），这里把两类分开：
        // 短名只允许是已登记全名的别名，避免“没匹配上的键”被静默跳过。
        Set<String> statementIds = new TreeSet<>();
        Set<String> shortNames = new TreeSet<>();
        for (String name : configuration.getMappedStatementNames()) {
            int dot = name.lastIndexOf('.');
            if (dot >= 0 && namespaces.contains(name.substring(0, dot))) {
                statementIds.add(name);
            } else {
                shortNames.add(name);
            }
        }
        assertFalse(statementIds.isEmpty(), "解析后必须有 MappedStatement");
        for (String shortName : shortNames) {
            assertTrue(statementIds.stream().anyMatch(id -> id.endsWith("." + shortName)),
                    "出现既不是已知 namespace 全名、也不是短名别名的 statement 键：" + shortName);
        }

        for (String statementId : statementIds) {
            MappedStatement statement = configuration.getMappedStatement(statementId);
            assertNotNull(statement.getSqlSource(), "MappedStatement 必须有 SQL source：" + statementId);
            assertFalse(statement.getResultMaps().isEmpty(),
                    "MappedStatement 必须有 resultMap：" + statementId);
            for (ResultMap resultMap : statement.getResultMaps()) {
                assertResolvedType(resultMap.getType(), statementId);
            }
            if (statement.getParameterMap().getType() != null) {
                assertResolvedType(statement.getParameterMap().getType(), statementId);
            }
        }
    }

    @Test
    void mapperInterfacesUnderTheScanRootMatchTheXmlNamespacesOneToOne() {
        Set<String> namespaces = new TreeSet<>(xmlNamespacesByFile().values());
        Set<String> scanned = mapperInterfacesUnder(SCAN_ROOT);

        assertEquals(namespaces, scanned,
                "扫描根 " + SCAN_ROOT + " 的 @Mapper 接口集合必须与 XML namespace 集合一一对应");

        for (String namespace : namespaces) {
            assertTrue(isApprovedMapperPackage(namespace),
                    "mapper 接口必须落在模块包内：" + namespace);
            String legacyFqcn = LEGACY_MAPPER_PACKAGE + namespace.substring(namespace.lastIndexOf('.'));
            assertThrows(ClassNotFoundException.class, () -> Class.forName(legacyFqcn),
                    "旧集中包不得残留同名接口（同一类只存在一份）：" + legacyFqcn);
        }
    }

    /**
     * XML namespace 推导出的每个 mapper 包，都必须在隔离测试配置里有一条显式的 WARN 级别。
     *
     * <p>为什么要求“显式”：{@code logging.level.<name>} 是包前缀匹配，
     * {@code com.feibijiubi.backend.video.mapper} <b>不</b>覆盖 {@code ...video.local.mapper}，
     * 所以 R1–R5 迁出的 7 个 mapper 包必须各自出现一行，不能依赖旧 {@code backend.mapper} 的兼容配置。</p>
     *
     * <p>只读仓库内的 {@code src/test/resources/video-status-isolated.properties}：不读
     * {@code application.yml}、不加载任何外部配置、不访问基础设施。</p>
     */
    @Test
    void everyDiscoveredMapperPackageHasAnExplicitWarnLoggerInTheIsolatedTestProperties() throws IOException {
        Set<String> mapperPackages = new TreeSet<>();
        for (String namespace : xmlNamespacesByFile().values()) {
            mapperPackages.add(namespace.substring(0, namespace.lastIndexOf('.')));
        }

        Properties isolatedConfig = isolatedTestProperties();
        List<String> notConfigured = new ArrayList<>();
        for (String mapperPackage : mapperPackages) {
            String key = "logging.level." + mapperPackage;
            String level = isolatedConfig.getProperty(key);
            if (!"WARN".equals(level)) {
                notConfigured.add(key + "（实际值： " + level + "）");
            }
        }

        assertEquals(List.of(), notConfigured,
                "隔离测试配置必须为每个 mapper 包显式配置 WARN（旧 mapper 包不覆盖新模块包），缺失或不符："
                        + notConfigured);
    }

    /** 仓库内的隔离 opt-in 测试资源；不读 application.yml、不加载外部配置。 */
    private static Properties isolatedTestProperties() throws IOException {
        Path propertiesFile = Path.of("src/test/resources/video-status-isolated.properties");
        assertTrue(Files.isRegularFile(propertiesFile),
                "缺少隔离测试资源（测试须从项目根目录运行）：" + propertiesFile.toAbsolutePath());
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(propertiesFile)) {
            properties.load(in);
        }
        return properties;
    }

    /** 解析结果里的类型必须是已加载的真实类，且不得来自旧集中 mapper 包。 */
    private static void assertResolvedType(Class<?> type, String statementId) {
        assertNotNull(type, "resultType/parameterType 必须解析成真实类：" + statementId);
        if (type.getName().startsWith(LEGACY_MAPPER_PACKAGE + ".")) {
            fail("statement " + statementId + " 的类型仍指向旧集中包：" + type.getName());
        }
    }

    private static boolean isApprovedMapperPackage(String namespace) {
        String packageName = namespace.substring(0, namespace.lastIndexOf('.'));
        return APPROVED_MAPPER_PACKAGES.contains(packageName);
    }

    /** 与 {@code mybatis.mapper-locations} 等价的 Spring 资源解析（mybatis-spring 也走这个解析器）。 */
    private static List<Resource> mapperResources() throws IOException {
        Resource[] resources = new PathMatchingResourcePatternResolver()
                .getResources(MAPPER_LOCATIONS_PATTERN);
        List<Resource> sorted = new ArrayList<>(List.of(resources));
        sorted.sort((left, right) -> left.getFilename().compareTo(right.getFilename()));
        assertFalse(sorted.isEmpty(),
                "按 " + MAPPER_LOCATIONS_PATTERN + " 找不到任何 mapper XML（测试须先编译资源）");
        return sorted;
    }

    private static Set<String> sourceXmlFileNames() {
        assertTrue(Files.isDirectory(MAPPER_SOURCE_DIR),
                "缺少 mapper 源码目录（测试须从项目根目录运行）：" + MAPPER_SOURCE_DIR.toAbsolutePath());
        try (Stream<Path> files = Files.list(MAPPER_SOURCE_DIR)) {
            Set<String> names = new TreeSet<>();
            files.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".xml"))
                    .forEach(names::add);
            assertFalse(names.isEmpty(), "没有找到任何 mapper XML：" + MAPPER_SOURCE_DIR.toAbsolutePath());
            return names;
        } catch (IOException e) {
            throw new IllegalStateException("无法列出 mapper 源码目录：" + MAPPER_SOURCE_DIR, e);
        }
    }

    /** XML 文件名 → namespace。只用于给 MyBatis 解析结果做对照，装配断言全部走 MyBatis 对象。 */
    private static Map<String, String> xmlNamespacesByFile() {
        Map<String, String> namespaces = new TreeMap<>();
        try {
            for (Resource resource : mapperResources()) {
                String fileName = resource.getFilename();
                String content;
                try (InputStream in = resource.getInputStream()) {
                    content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
                Matcher matcher = MAPPER_NAMESPACE.matcher(content);
                if (!matcher.find()) {
                    fail("mapper XML 缺少 namespace：" + fileName);
                }
                namespaces.put(fileName, matcher.group(1));
            }
        } catch (IOException e) {
            throw new IllegalStateException("无法读取 classpath 上的 mapper XML", e);
        }
        return namespaces;
    }

    /**
     * 把全部 mapper XML 解析进同一个 Configuration。
     *
     * <p>{@code mapUnderscoreToCamelCase=true} 与生产/隔离测试配置一致；解析顺序按文件名固定，避免
     * 失败信息随文件系统顺序漂移。</p>
     */
    private static Configuration parseAllMappers() throws IOException {
        Configuration configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        for (Resource resource : mapperResources()) {
            try (InputStream in = resource.getInputStream()) {
                new XMLMapperBuilder(in, configuration, resource.getFilename(),
                        configuration.getSqlFragments()).parse();
            }
        }
        return configuration;
    }

    /**
     * 与 {@code org.mybatis.spring.mapper.ClassPathMapperScanner} 相同的过滤规则：接口 + {@link Mapper}。
     *
     * <p>按注解过滤意味着测试源码里的嵌套 {@code @Configuration} 不会被算进来；这里断言的是“生产
     * mapper 接口集合”，不是“包内所有类”。</p>
     */
    private static Set<String> mapperInterfacesUnder(String basePackage) {
        ClassPathScanningCandidateComponentProvider provider =
                new ClassPathScanningCandidateComponentProvider(false) {
                    @Override
                    protected boolean isCandidateComponent(AnnotatedBeanDefinition beanDefinition) {
                        return beanDefinition.getMetadata().isInterface()
                                && beanDefinition.getMetadata().isIndependent();
                    }
                };
        provider.addIncludeFilter(new AnnotationTypeFilter(Mapper.class));

        Set<String> names = new TreeSet<>();
        for (BeanDefinition candidate : provider.findCandidateComponents(basePackage)) {
            names.add(candidate.getBeanClassName());
        }
        return names;
    }
}
