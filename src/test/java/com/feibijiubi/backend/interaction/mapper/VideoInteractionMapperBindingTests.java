package com.feibijiubi.backend.interaction.mapper;

import com.feibijiubi.backend.interaction.entity.VideoInteractionRequest;
import com.feibijiubi.backend.interaction.entity.VideoInteractionWindow;
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
 * R3：交互幂等/窗口 mapper 从 {@code com.feibijiubi.backend.mapper} 迁到
 * {@code com.feibijiubi.backend.interaction.mapper} 后的<b>离线装配</b>证明。
 *
 * <p>XML 物理路径不动，用真实 MyBatis 解析器加载后断言 namespace 指向新 FQCN、每个语句（含 T15 清理
 * 语句）都有 {@code MappedStatement}、{@code resultType} 指向新位置的 {@link VideoInteractionRequest} /
 * {@link VideoInteractionWindow}。扫描根的发现与旧包残留由 {@link UserVideoMapperBindingTests} 覆盖。</p>
 *
 * <p>不连数据库、不启动 Spring 容器。</p>
 */
class VideoInteractionMapperBindingTests {

    private static final Path XML = Path.of(
            "src/main/resources/com/feibijiubi/backend/mapper/VideoInteractionMapper.xml");
    private static final String NAMESPACE = "com.feibijiubi.backend.interaction.mapper.VideoInteractionMapper";

    @Test
    void legacyXmlPathBindsTheMigratedMapperInterface() throws IOException {
        Configuration configuration = parse();

        assertNotNull(VideoInteractionMapper.class.getAnnotation(Mapper.class),
                "mapper 接口必须保留 @Mapper，否则扫描不到");
        assertEquals(VideoInteractionMapper.class.getName(), NAMESPACE,
                "namespace 必须等于迁移后的 FQCN");
        assertTrue(configuration.hasMapper(VideoInteractionMapper.class),
                "XML namespace 必须解析到 " + NAMESPACE);
        for (Method method : VideoInteractionMapper.class.getDeclaredMethods()) {
            assertTrue(configuration.hasStatement(NAMESPACE + "." + method.getName()),
                    "缺少 MappedStatement：" + method.getName());
        }
    }

    @Test
    void resultTypesPointAtTheMigratedEntities() throws IOException {
        Configuration configuration = parse();

        assertEquals(VideoInteractionRequest.class, resultType(configuration, "selectRequestByKey"));
        assertEquals(VideoInteractionWindow.class, resultType(configuration, "selectWindowForUpdate"));
        assertEquals(Long.class, resultType(configuration, "selectExpiredRequestIds"));
        assertEquals(Long.class, resultType(configuration, "selectIdleWindowIds"));
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
