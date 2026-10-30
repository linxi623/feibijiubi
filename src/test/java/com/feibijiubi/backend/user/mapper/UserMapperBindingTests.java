package com.feibijiubi.backend.user.mapper;

import com.feibijiubi.backend.user.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R2：{@code UserMapper} 从 {@code com.feibijiubi.backend.mapper} 迁到
 * {@code com.feibijiubi.backend.user.mapper} 后的<b>离线装配</b>证明。
 *
 * <p>XML 物理路径刻意不动（仍在 {@code com/feibijiubi/backend/mapper/}），所以这里读的是那个旧路径，
 * 用真实 MyBatis 解析器加载，然后断言：</p>
 * <ul>
 *   <li>mapper 接口仍是 {@link Mapper}（{@code mybatis-spring-boot} 的自动扫描按注解注册）；</li>
 *   <li>XML 的 namespace 解析到<b>新</b> FQCN，即 {@code configuration.hasMapper(新接口)} 为真；</li>
 *   <li>接口上每个方法都有同名 {@code MappedStatement}；</li>
 *   <li>{@code resultType}/{@code parameterType} 指向新位置的 {@link User} 实体。</li>
 * </ul>
 *
 * <p>另外证明扫描注册：{@code @SpringBootApplication}（{@code com.feibijiubi.backend.BackendApplication}）
 * 所在包就是 MyBatis 自动装配的扫描根，从该包出发按 {@link Mapper} 过滤必须能找到两个用户 mapper，
 * 且旧包 {@code com.feibijiubi.backend.mapper} 不再残留同名接口（同一类只存在一份）。</p>
 *
 * <p>不连数据库、不启动 Spring 容器：只证明“迁移后 namespace/resultType/扫描都能对上”。</p>
 */
class UserMapperBindingTests {

    private static final Path XML = Path.of(
            "src/main/resources/com/feibijiubi/backend/mapper/UserMapper.xml");
    private static final String NAMESPACE = "com.feibijiubi.backend.user.mapper.UserMapper";
    private static final String SCAN_ROOT = "com.feibijiubi.backend";
    private static final String OLD_USER_MAPPER = "com.feibijiubi.backend.mapper.UserMapper";
    private static final String OLD_USER_FOLLOW_MAPPER = "com.feibijiubi.backend.mapper.UserFollowMapper";

    @Test
    void legacyXmlPathBindsTheMigratedMapperInterface() throws IOException {
        Configuration configuration = parse();

        assertNotNull(UserMapper.class.getAnnotation(Mapper.class),
                "mapper 接口必须保留 @Mapper，否则扫描不到");
        assertTrue(configuration.hasMapper(UserMapper.class),
                "XML namespace 必须解析到 " + NAMESPACE);
        for (Method method : UserMapper.class.getDeclaredMethods()) {
            assertTrue(configuration.hasStatement(NAMESPACE + "." + method.getName()),
                    "缺少 MappedStatement：" + method.getName());
        }
    }

    @Test
    void resultTypesAndParameterTypesPointAtTheMigratedEntity() throws IOException {
        Configuration configuration = parse();

        assertEquals(User.class, resultType(configuration, "selectByUsernameForLogin"));
        assertEquals(User.class, resultType(configuration, "selectById"));
        assertEquals(User.class, resultType(configuration, "selectAuthStateById"));
        assertEquals(Integer.class, resultType(configuration, "countByUsername"));

        assertEquals(User.class,
                configuration.getMappedStatement(NAMESPACE + ".createUser").getParameterMap().getType(),
                "createUser 的 parameterType 必须指向 " + User.class.getName());
    }

    @Test
    void userMappersAreDiscoveredUnderTheApplicationMapperScanRoot() {
        Set<String> mapperInterfaces = mapperInterfacesUnder(SCAN_ROOT);

        assertTrue(mapperInterfaces.contains(UserMapper.class.getName()),
                SCAN_ROOT + " 的 @Mapper 扫描必须包含 " + UserMapper.class.getName());
        assertTrue(mapperInterfaces.contains(UserFollowMapper.class.getName()),
                SCAN_ROOT + " 的 @Mapper 扫描必须包含 " + UserFollowMapper.class.getName());
        assertFalse(mapperInterfaces.contains(OLD_USER_MAPPER), "旧包不得再注册 UserMapper");
        assertFalse(mapperInterfaces.contains(OLD_USER_FOLLOW_MAPPER), "旧包不得再注册 UserFollowMapper");

        assertThrows(ClassNotFoundException.class, () -> Class.forName(OLD_USER_MAPPER),
                "旧包不得残留同名类（同一类只存在一份）");
        assertThrows(ClassNotFoundException.class, () -> Class.forName(OLD_USER_FOLLOW_MAPPER),
                "旧包不得残留同名类（同一类只存在一份）");
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

    /**
     * 与 {@code org.mybatis.spring.mapper.ClassPathMapperScanner} 相同的过滤规则：接口 + {@link Mapper}。
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

        Set<String> names = new LinkedHashSet<>();
        for (BeanDefinition candidate : provider.findCandidateComponents(basePackage)) {
            names.add(candidate.getBeanClassName());
        }
        return names;
    }
}
