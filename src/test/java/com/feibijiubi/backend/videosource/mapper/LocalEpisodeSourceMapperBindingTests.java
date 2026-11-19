package com.feibijiubi.backend.videosource.mapper;

import com.feibijiubi.backend.video.entity.Video;
import com.feibijiubi.backend.video.local.entity.LocalVideoEpisode;
import com.feibijiubi.backend.videosource.entity.LocalEpisodeMediaCandidate;
import com.feibijiubi.backend.videosource.entity.LocalEpisodeSourceBinding;
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
 * R1：{@code LocalEpisodeSourceMapper} 从 {@code com.feibijiubi.backend.mapper} 迁到
 * {@code com.feibijiubi.backend.videosource.mapper} 后的<b>离线装配</b>证明。
 *
 * <p>XML 物理路径刻意不动（仍在 {@code com/feibijiubi/backend/mapper/}），所以这里读的是那个旧路径，
 * 用真实 MyBatis 解析器加载，然后断言：</p>
 * <ul>
 *   <li>mapper 接口仍是 {@link Mapper}（MyBatis 扫描能注册它）；</li>
 *   <li>XML 的 namespace 解析到<b>新</b> FQCN，即 {@code configuration.hasMapper(新接口)} 为真；</li>
 *   <li>接口上每个方法都有同名 {@code MappedStatement}；</li>
 *   <li>{@code resultType} 指向新位置的实体类。</li>
 * </ul>
 *
 * <p>不连数据库、不启动 Spring：只证明"迁移后 namespace/resultType 与接口对得上"。</p>
 */
class LocalEpisodeSourceMapperBindingTests {

    private static final Path XML = Path.of(
            "src/main/resources/com/feibijiubi/backend/mapper/LocalEpisodeSourceMapper.xml");
    private static final String NAMESPACE = "com.feibijiubi.backend.videosource.mapper.LocalEpisodeSourceMapper";

    @Test
    void legacyXmlPathBindsTheMigratedMapperInterface() throws IOException {
        Configuration configuration = parse();

        assertNotNull(LocalEpisodeSourceMapper.class.getAnnotation(Mapper.class),
                "mapper 接口必须保留 @Mapper，否则扫描不到");
        assertTrue(configuration.hasMapper(LocalEpisodeSourceMapper.class),
                "XML namespace 必须解析到 " + NAMESPACE);
        for (Method method : LocalEpisodeSourceMapper.class.getDeclaredMethods()) {
            assertTrue(configuration.hasStatement(NAMESPACE + "." + method.getName()),
                    "缺少 MappedStatement：" + method.getName());
        }
    }

    @Test
    void resultTypesPointAtTheMigratedEntities() throws IOException {
        Configuration configuration = parse();

        assertEquals(LocalVideoEpisode.class, resultType(configuration, "lockEpisode"));
        assertEquals(Video.class, resultType(configuration, "lockVideoForUpdate"));
        assertEquals(LocalEpisodeMediaCandidate.class, resultType(configuration, "selectCandidate"));
        assertEquals(LocalEpisodeMediaCandidate.class, resultType(configuration, "selectCandidates"));
        assertEquals(LocalEpisodeSourceBinding.class, resultType(configuration, "selectBindings"));
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
