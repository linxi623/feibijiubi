package com.feibijiubi.backend.video.mapper;

import com.feibijiubi.backend.video.entity.Video;
import com.feibijiubi.backend.video.vo.AdminVideoListItemVO;
import com.feibijiubi.backend.video.vo.VideoListItemVO;
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
 * R3：{@code VideoMapper} 从 {@code com.feibijiubi.backend.mapper} 迁到
 * {@code com.feibijiubi.backend.video.mapper} 后的<b>离线装配</b>证明。
 *
 * <p>XML 物理路径刻意不动（仍在 {@code com/feibijiubi/backend/mapper/}），所以这里读的是那个旧路径，
 * 用真实 MyBatis 解析器加载，然后断言：</p>
 * <ul>
 *   <li>mapper 接口仍是 {@link Mapper}（{@code mybatis-spring-boot} 的自动扫描按注解注册）；</li>
 *   <li>XML 的 namespace 解析到<b>新</b> FQCN，即 {@code configuration.hasMapper(新接口)} 为真；</li>
 *   <li>接口上每个方法都有同名 {@code MappedStatement}；</li>
 *   <li>{@code resultType}/{@code parameterType} 指向新位置的 {@link Video} 实体与 video VO。</li>
 * </ul>
 *
 * <p>另外证明扫描注册：{@code @SpringBootApplication}（{@code com.feibijiubi.backend.BackendApplication}）
 * 所在包就是 MyBatis 自动装配的扫描根，从该包出发按 {@link Mapper} 过滤必须能找到三个 video mapper，
 * 且旧包 {@code com.feibijiubi.backend.mapper} 不再残留同名接口（同一类只存在一份）。</p>
 *
 * <p>不连数据库、不启动 Spring 容器：只证明“迁移后 namespace/resultType/扫描都能对上”。</p>
 */
class VideoMapperBindingTests {

    private static final Path XML = Path.of(
            "src/main/resources/com/feibijiubi/backend/mapper/VideoMapper.xml");
    private static final String NAMESPACE = "com.feibijiubi.backend.video.mapper.VideoMapper";
    private static final String SCAN_ROOT = "com.feibijiubi.backend";

    @Test
    void legacyXmlPathBindsTheMigratedMapperInterface() throws IOException {
        Configuration configuration = parse();

        assertNotNull(VideoMapper.class.getAnnotation(Mapper.class),
                "mapper 接口必须保留 @Mapper，否则扫描不到");
        assertEquals(VideoMapper.class.getName(), NAMESPACE, "namespace 必须等于迁移后的 FQCN");
        assertTrue(configuration.hasMapper(VideoMapper.class),
                "XML namespace 必须解析到 " + NAMESPACE);
        for (Method method : VideoMapper.class.getDeclaredMethods()) {
            assertTrue(configuration.hasStatement(NAMESPACE + "." + method.getName()),
                    "缺少 MappedStatement：" + method.getName());
        }
    }

    @Test
    void resultTypesAndParameterTypesPointAtTheMigratedTypes() throws IOException {
        Configuration configuration = parse();

        assertEquals(Video.class, resultType(configuration, "selectByVid"));
        assertEquals(Video.class, resultType(configuration, "selectPublishedByVid"));
        assertEquals(Video.class, resultType(configuration, "selectPublishedList"));
        assertEquals(VideoListItemVO.class, resultType(configuration, "selectFeed"));
        assertEquals(AdminVideoListItemVO.class, resultType(configuration, "selectByStatus"));
        assertEquals(Integer.class, resultType(configuration, "countVideoByUid"));

        assertEquals(Video.class,
                configuration.getMappedStatement(NAMESPACE + ".insert").getParameterMap().getType(),
                "insert 的 parameterType 必须指向 " + Video.class.getName());
    }

    @Test
    void videoMappersAreDiscoveredUnderTheApplicationMapperScanRoot() {
        Set<String> mapperInterfaces = mapperInterfacesUnder(SCAN_ROOT);

        assertTrue(mapperInterfaces.contains(VideoMapper.class.getName()),
                SCAN_ROOT + " 的 @Mapper 扫描必须包含 " + VideoMapper.class.getName());
        assertTrue(mapperInterfaces.contains("com.feibijiubi.backend.video.mapper.CategoryMapper"),
                "分类 mapper 必须随 video 模块一起被扫描到");
        assertTrue(mapperInterfaces.contains("com.feibijiubi.backend.video.mapper.UploadTempFileMapper"),
                "临时文件 mapper 必须随 video 模块一起被扫描到");

        assertFalse(mapperInterfaces.contains("com.feibijiubi.backend.mapper.VideoMapper"),
                "旧包不得再注册 VideoMapper");
        assertFalse(mapperInterfaces.contains("com.feibijiubi.backend.mapper.CategoryMapper"),
                "旧包不得再注册 CategoryMapper");
        assertFalse(mapperInterfaces.contains("com.feibijiubi.backend.mapper.UploadTempFileMapper"),
                "旧包不得再注册 UploadTempFileMapper");

        assertThrows(ClassNotFoundException.class,
                () -> Class.forName("com.feibijiubi.backend.mapper.VideoMapper"),
                "旧包不得残留同名类（同一类只存在一份）");
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName("com.feibijiubi.backend.mapper.CategoryMapper"),
                "旧包不得残留同名类（同一类只存在一份）");
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName("com.feibijiubi.backend.mapper.UploadTempFileMapper"),
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
