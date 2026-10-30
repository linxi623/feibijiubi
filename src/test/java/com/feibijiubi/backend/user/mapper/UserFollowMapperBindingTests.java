package com.feibijiubi.backend.user.mapper;

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
 * R2：{@code UserFollowMapper} 从 {@code com.feibijiubi.backend.mapper} 迁到
 * {@code com.feibijiubi.backend.user.mapper} 后的<b>离线装配</b>证明。
 *
 * <p>XML 物理路径刻意不动（仍在 {@code com/feibijiubi/backend/mapper/}）：用真实 MyBatis 解析器加载
 * 那个旧路径，断言 namespace 解析到新接口、接口上每个方法都有同名 {@code MappedStatement}、
 * 计数查询的 {@code resultType} 仍是 {@code Integer}。{@link Mapper} 注解仍在，自动扫描才会注册。</p>
 *
 * <p>不连数据库、不启动 Spring 容器。</p>
 */
class UserFollowMapperBindingTests {

    private static final Path XML = Path.of(
            "src/main/resources/com/feibijiubi/backend/mapper/UserFollowMapper.xml");
    private static final String NAMESPACE = "com.feibijiubi.backend.user.mapper.UserFollowMapper";

    @Test
    void legacyXmlPathBindsTheMigratedMapperInterface() throws IOException {
        Configuration configuration = parse();

        assertNotNull(UserFollowMapper.class.getAnnotation(Mapper.class),
                "mapper 接口必须保留 @Mapper，否则扫描不到");
        assertTrue(configuration.hasMapper(UserFollowMapper.class),
                "XML namespace 必须解析到 " + NAMESPACE);
        for (Method method : UserFollowMapper.class.getDeclaredMethods()) {
            assertTrue(configuration.hasStatement(NAMESPACE + "." + method.getName()),
                    "缺少 MappedStatement：" + method.getName());
        }
    }

    @Test
    void resultTypesStayUnchanged() throws IOException {
        Configuration configuration = parse();

        assertEquals(Boolean.class, resultType(configuration, "checkExist"));
        assertEquals(Integer.class, resultType(configuration, "countFans"));
        assertEquals(Integer.class, resultType(configuration, "countStar"));
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
