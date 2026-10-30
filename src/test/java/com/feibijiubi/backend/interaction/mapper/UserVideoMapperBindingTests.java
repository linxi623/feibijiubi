package com.feibijiubi.backend.interaction.mapper;

import com.feibijiubi.backend.interaction.entity.UserVideo;
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
 * R3：{@code UserVideoMapper} 从 {@code com.feibijiubi.backend.mapper} 迁到
 * {@code com.feibijiubi.backend.interaction.mapper}（用户-视频关系属互动）后的<b>离线装配</b>证明。
 *
 * <p>XML 物理路径刻意不动（仍在 {@code com/feibijiubi/backend/mapper/}），所以这里读的是那个旧路径，
 * 用真实 MyBatis 解析器加载，然后断言 namespace 指向新 FQCN、语句齐全、
 * {@code resultType} 指向新位置的 {@link UserVideo}。</p>
 *
 * <p>另外证明扫描注册：从 {@code com.feibijiubi.backend} 出发按 {@link Mapper} 过滤必须能找到两个
 * interaction mapper，且旧包不再残留同名接口（同一类只存在一份）。</p>
 *
 * <p>不连数据库、不启动 Spring 容器。</p>
 */
class UserVideoMapperBindingTests {

    private static final Path XML = Path.of(
            "src/main/resources/com/feibijiubi/backend/mapper/UserVideoMapper.xml");
    private static final String NAMESPACE = "com.feibijiubi.backend.interaction.mapper.UserVideoMapper";
    private static final String SCAN_ROOT = "com.feibijiubi.backend";

    @Test
    void legacyXmlPathBindsTheMigratedMapperInterface() throws IOException {
        Configuration configuration = parse();

        assertNotNull(UserVideoMapper.class.getAnnotation(Mapper.class),
                "mapper 接口必须保留 @Mapper，否则扫描不到");
        assertEquals(UserVideoMapper.class.getName(), NAMESPACE, "namespace 必须等于迁移后的 FQCN");
        assertTrue(configuration.hasMapper(UserVideoMapper.class),
                "XML namespace 必须解析到 " + NAMESPACE);
        for (Method method : UserVideoMapper.class.getDeclaredMethods()) {
            assertTrue(configuration.hasStatement(NAMESPACE + "." + method.getName()),
                    "缺少 MappedStatement：" + method.getName());
        }
    }

    @Test
    void resultTypePointsAtTheMigratedEntity() throws IOException {
        Configuration configuration = parse();

        assertEquals(UserVideo.class, resultType(configuration, "selectByUidAndVid"));
    }

    @Test
    void interactionMappersAreDiscoveredUnderTheApplicationMapperScanRoot() {
        Set<String> mapperInterfaces = mapperInterfacesUnder(SCAN_ROOT);

        assertTrue(mapperInterfaces.contains(UserVideoMapper.class.getName()),
                SCAN_ROOT + " 的 @Mapper 扫描必须包含 " + UserVideoMapper.class.getName());
        assertTrue(mapperInterfaces.contains("com.feibijiubi.backend.interaction.mapper.VideoInteractionMapper"),
                "交互幂等/窗口 mapper 必须随 interaction 模块一起被扫描到");

        assertFalse(mapperInterfaces.contains("com.feibijiubi.backend.mapper.UserVideoMapper"),
                "旧包不得再注册 UserVideoMapper");
        assertFalse(mapperInterfaces.contains("com.feibijiubi.backend.mapper.VideoInteractionMapper"),
                "旧包不得再注册 VideoInteractionMapper");

        assertThrows(ClassNotFoundException.class,
                () -> Class.forName("com.feibijiubi.backend.mapper.UserVideoMapper"),
                "旧包不得残留同名类（同一类只存在一份）");
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName("com.feibijiubi.backend.mapper.VideoInteractionMapper"),
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
