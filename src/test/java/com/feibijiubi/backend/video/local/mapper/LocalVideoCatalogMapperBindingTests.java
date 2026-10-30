package com.feibijiubi.backend.video.local.mapper;

import com.feibijiubi.backend.video.local.entity.LocalVideoEpisode;
import com.feibijiubi.backend.video.local.entity.LocalVideoSeries;
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
 * R1：{@code LocalVideoCatalogMapper} 迁到 {@code com.feibijiubi.backend.video.local.mapper} 后的
 * <b>离线装配</b>证明。
 *
 * <p>XML 物理路径刻意不动（仍在 {@code com/feibijiubi/backend/mapper/}），这里直接读旧路径并用真实
 * MyBatis 解析器加载，断言 mapper 接口仍带 {@link Mapper}、XML namespace 解析到新 FQCN、接口上每个方法
 * 都有同名 {@code MappedStatement}、{@code resultType} 指向新位置的实体类。</p>
 *
 * <p>不连数据库、不启动 Spring。</p>
 */
class LocalVideoCatalogMapperBindingTests {

    private static final Path XML = Path.of(
            "src/main/resources/com/feibijiubi/backend/mapper/LocalVideoCatalogMapper.xml");
    private static final String NAMESPACE = "com.feibijiubi.backend.video.local.mapper.LocalVideoCatalogMapper";

    @Test
    void legacyXmlPathBindsTheMigratedMapperInterface() throws IOException {
        Configuration configuration = parse();

        assertNotNull(LocalVideoCatalogMapper.class.getAnnotation(Mapper.class),
                "mapper 接口必须保留 @Mapper，否则扫描不到");
        assertTrue(configuration.hasMapper(LocalVideoCatalogMapper.class),
                "XML namespace 必须解析到 " + NAMESPACE);
        for (Method method : LocalVideoCatalogMapper.class.getDeclaredMethods()) {
            assertTrue(configuration.hasStatement(NAMESPACE + "." + method.getName()),
                    "缺少 MappedStatement：" + method.getName());
        }
    }

    @Test
    void resultTypesPointAtTheMigratedEntities() throws IOException {
        Configuration configuration = parse();

        assertEquals(LocalVideoSeries.class, resultType(configuration, "selectSeriesById"));
        assertEquals(LocalVideoSeries.class, resultType(configuration, "selectSeriesAfter"));
        assertEquals(LocalVideoEpisode.class, resultType(configuration, "selectEpisodeById"));
        assertEquals(LocalVideoEpisode.class, resultType(configuration, "selectEpisodesAfter"));
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
