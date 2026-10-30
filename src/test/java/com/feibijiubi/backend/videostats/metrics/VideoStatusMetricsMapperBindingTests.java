package com.feibijiubi.backend.videostats.metrics;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R4：只读指标 mapper 从 {@code com.feibijiubi.backend.metrics} 迁到
 * {@code com.feibijiubi.backend.videostats.metrics} 后的<b>离线装配</b>证明。
 *
 * <p>XML 物理路径不动（仍在 {@code com/feibijiubi/backend/mapper/}），用真实 MyBatis 解析器
 * 加载后断言 namespace 指向新 FQCN、五个采样语句齐全、{@code resultType} 仍是 Integer/Long。
 * 该 mapper 由 {@code @MapperScan(basePackageClasses = VideoStatusMetricsMapper.class)} 的隔离
 * 上下文显式注册，因此不依赖根包扫描。</p>
 *
 * <p>不连数据库、不启动 Spring 容器。</p>
 */
class VideoStatusMetricsMapperBindingTests {

    private static final Path XML = Path.of(
            "src/main/resources/com/feibijiubi/backend/mapper/VideoStatusMetricsMapper.xml");
    private static final String NAMESPACE =
            "com.feibijiubi.backend.videostats.metrics.VideoStatusMetricsMapper";

    @Test
    void legacyXmlPathBindsTheMigratedMetricsMapperInterface() throws IOException {
        Configuration configuration = parse();

        assertNotNull(VideoStatusMetricsMapper.class.getAnnotation(Mapper.class),
                "指标 mapper 必须保留 @Mapper");
        assertEquals(VideoStatusMetricsMapper.class.getName(), NAMESPACE,
                "namespace 必须等于迁移后的 FQCN");
        assertTrue(configuration.hasMapper(VideoStatusMetricsMapper.class),
                "XML namespace 必须解析到 " + NAMESPACE);
        for (Method method : VideoStatusMetricsMapper.class.getDeclaredMethods()) {
            assertTrue(configuration.hasStatement(NAMESPACE + "." + method.getName()),
                    "缺少 MappedStatement：" + method.getName());
        }
    }

    @Test
    void resultTypesStayPrimitiveWrappers() throws IOException {
        Configuration configuration = parse();

        assertEquals(Integer.class, resultType(configuration, "countOutboxStatusCapped"));
        assertEquals(Long.class, resultType(configuration, "selectOldestOutboxAgeMillis"));
        assertEquals(Integer.class, resultType(configuration, "countConsumedStatusCapped"));
        assertEquals(Long.class, resultType(configuration, "selectOldestConsumedAgeMillis"));
        assertEquals(Integer.class, resultType(configuration, "indexExists"));
    }

    private static Configuration parse() throws IOException {
        assertTrue(Files.isRegularFile(XML), "缺少 mapper 资源：" + XML.toAbsolutePath());
        Configuration configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        try (InputStream in = Files.newInputStream(XML)) {
            new XMLMapperBuilder(in, configuration, XML.toString(),
                    configuration.getSqlFragments()).parse();
        }
        return configuration;
    }

    private static Class<?> resultType(Configuration configuration, String statementId) {
        return configuration.getMappedStatement(NAMESPACE + "." + statementId)
                .getResultMaps().get(0).getType();
    }
}
