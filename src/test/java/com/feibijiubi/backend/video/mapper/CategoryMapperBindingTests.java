package com.feibijiubi.backend.video.mapper;

import com.feibijiubi.backend.video.entity.Category;
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
 * R3：分类 mapper 从 {@code com.feibijiubi.backend.mapper} 迁到
 * {@code com.feibijiubi.backend.video.mapper}（分类归 video）后的<b>离线装配</b>证明。
 *
 * <p>XML 物理路径不动，用真实 MyBatis 解析器加载后断言 namespace 指向新 FQCN、语句齐全、
 * {@code resultType} 指向新位置的 {@link Category}。扫描根的发现与旧包残留由
 * {@link VideoMapperBindingTests} 覆盖，这里不重复。</p>
 *
 * <p>不连数据库、不启动 Spring 容器。</p>
 */
class CategoryMapperBindingTests {

    private static final Path XML = Path.of(
            "src/main/resources/com/feibijiubi/backend/mapper/CategoryMapper.xml");
    private static final String NAMESPACE = "com.feibijiubi.backend.video.mapper.CategoryMapper";

    @Test
    void legacyXmlPathBindsTheMigratedMapperInterface() throws IOException {
        Configuration configuration = parse();

        assertNotNull(CategoryMapper.class.getAnnotation(Mapper.class),
                "mapper 接口必须保留 @Mapper，否则扫描不到");
        assertEquals(CategoryMapper.class.getName(), NAMESPACE, "namespace 必须等于迁移后的 FQCN");
        assertTrue(configuration.hasMapper(CategoryMapper.class),
                "XML namespace 必须解析到 " + NAMESPACE);
        for (Method method : CategoryMapper.class.getDeclaredMethods()) {
            assertTrue(configuration.hasStatement(NAMESPACE + "." + method.getName()),
                    "缺少 MappedStatement：" + method.getName());
        }
    }

    @Test
    void resultTypePointsAtTheMigratedEntity() throws IOException {
        Configuration configuration = parse();

        assertEquals(Category.class, resultType(configuration, "categoryList"));
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
