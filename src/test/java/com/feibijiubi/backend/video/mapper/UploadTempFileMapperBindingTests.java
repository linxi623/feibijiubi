package com.feibijiubi.backend.video.mapper;

import com.feibijiubi.backend.video.entity.UploadTempFile;
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
 * R3：临时上传文件台账的 mapper 从 {@code com.feibijiubi.backend.mapper} 迁到
 * {@code com.feibijiubi.backend.video.mapper} 后的<b>离线装配</b>证明。
 *
 * <p>这一类按父级裁定归 video（属视频上传/发布流程），存储实现 {@code TencentCosStorageServiceImpl}
 * 仍留在原处等 R5 收口，只改 import。这里用真实 MyBatis 解析器加载<b>原物理路径</b>的 XML，断言 namespace
 * 指向新 FQCN、语句齐全、{@code resultType}/{@code parameterType} 指向新位置的 {@link UploadTempFile}。</p>
 *
 * <p>不连数据库、不启动 Spring 容器。</p>
 */
class UploadTempFileMapperBindingTests {

    private static final Path XML = Path.of(
            "src/main/resources/com/feibijiubi/backend/mapper/UploadTempFileMapper.xml");
    private static final String NAMESPACE = "com.feibijiubi.backend.video.mapper.UploadTempFileMapper";

    @Test
    void legacyXmlPathBindsTheMigratedMapperInterface() throws IOException {
        Configuration configuration = parse();

        assertNotNull(UploadTempFileMapper.class.getAnnotation(Mapper.class),
                "mapper 接口必须保留 @Mapper，否则扫描不到");
        assertEquals(UploadTempFileMapper.class.getName(), NAMESPACE,
                "namespace 必须等于迁移后的 FQCN");
        assertTrue(configuration.hasMapper(UploadTempFileMapper.class),
                "XML namespace 必须解析到 " + NAMESPACE);
        for (Method method : UploadTempFileMapper.class.getDeclaredMethods()) {
            assertTrue(configuration.hasStatement(NAMESPACE + "." + method.getName()),
                    "缺少 MappedStatement：" + method.getName());
        }
    }

    @Test
    void resultTypesAndParameterTypesPointAtTheMigratedEntity() throws IOException {
        Configuration configuration = parse();

        assertEquals(UploadTempFile.class, resultType(configuration, "selectByObjectKey"));
        assertEquals(UploadTempFile.class, resultType(configuration, "selectExpiredTempFiles"));
        assertEquals(UploadTempFile.class,
                configuration.getMappedStatement(NAMESPACE + ".insert").getParameterMap().getType(),
                "insert 的 parameterType 必须指向 " + UploadTempFile.class.getName());
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
