package com.feibijiubi.backend.videostats.mapper;

import com.feibijiubi.backend.videostats.entity.VideoStatus;
import com.feibijiubi.backend.videostats.entity.VideoStatusConsumedEvent;
import com.feibijiubi.backend.videostats.entity.VideoStatusConsumptionRepairLog;
import com.feibijiubi.backend.videostats.entity.VideoStatusOutbox;
import com.feibijiubi.backend.videostats.entity.VideoStatusReconciliationEventRow;
import com.feibijiubi.backend.videostats.entity.VideoStatusReconciliationRelationAggregate;
import com.feibijiubi.backend.videostats.service.admin.VideoStatusFailureAdminRow;
import com.feibijiubi.backend.videostats.service.admin.VideoStatusStatus3ReplayRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.ResultMap;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R4：6 个统计 mapper 从 {@code com.feibijiubi.backend.mapper} 迁到
 * {@code com.feibijiubi.backend.videostats.mapper} 后的<b>离线装配</b>证明。
 *
 * <p>XML 物理路径刻意不动（仍在 {@code com/feibijiubi/backend/mapper/}，与
 * {@code mybatis.mapper-locations} 的既有约定一致），这里读那个旧路径，用真实 MyBatis
 * 解析器加载，断言：</p>
 * <ul>
 *   <li>mapper 接口仍带 {@link Mapper}，XML namespace 解析到<b>新</b> FQCN
 *       （{@code configuration.hasMapper(新接口)} 为真）；</li>
 *   <li>每个接口方法都有同名 {@code MappedStatement}（不是只看 XML 文本）；</li>
 *   <li>{@code resultType}/{@code resultMap} 指向迁移后的实体、admin 行与 VO 类型。</li>
 * </ul>
 *
 * <p>同时证明扫描注册：{@code @SpringBootApplication} 所在包
 * （{@code com.feibijiubi.backend}）就是 MyBatis 自动装配的扫描根，按 {@link Mapper} 过滤必须
 * 能找到 6 个统计 mapper，且旧包 {@code com.feibijiubi.backend.mapper} 不再残留同名接口
 * （同一类只存在一份）。</p>
 *
 * <p>不连数据库、不启动 Spring 容器。{@code VideoStatusMetricsMapper} 的 XML 由
 * {@code videostats.metrics.VideoStatusMetricsMapperBindingTests} 覆盖。</p>
 */
class VideoStatusMapperBindingTests {

    private static final Path XML_DIR =
            Path.of("src/main/resources/com/feibijiubi/backend/mapper");
    private static final String SCAN_ROOT = "com.feibijiubi.backend";
    private static final String OLD_MAPPER_PACKAGE = "com.feibijiubi.backend.mapper.";

    /** mapper 接口 → 保持原物理路径的 XML 文件名。 */
    private static final Map<Class<?>, String> MAPPERS = new LinkedHashMap<>();

    static {
        MAPPERS.put(VideoStatusMapper.class, "VideoStatusMapper.xml");
        MAPPERS.put(VideoStatusConsumedEventMapper.class, "VideoStatusConsumedEventMapper.xml");
        MAPPERS.put(VideoStatusOutboxMapper.class, "VideoStatusOutboxMapper.xml");
        MAPPERS.put(VideoStatusOutboxRepairMapper.class, "VideoStatusOutboxRepairMapper.xml");
        MAPPERS.put(VideoStatusReconciliationMapper.class, "VideoStatusReconciliationMapper.xml");
        MAPPERS.put(VideoStatusFailureAdminMapper.class, "VideoStatusFailureAdminMapper.xml");
    }

    @Test
    void legacyXmlPathsBindTheMigratedMapperInterfaces() throws IOException {
        for (Map.Entry<Class<?>, String> entry : MAPPERS.entrySet()) {
            Class<?> mapper = entry.getKey();
            String namespace = mapper.getName();
            Configuration configuration = parse(entry.getValue());

            assertNotNull(mapper.getAnnotation(Mapper.class),
                    "mapper 接口必须保留 @Mapper，否则扫描不到：" + namespace);
            assertTrue(namespace.startsWith("com.feibijiubi.backend.videostats.mapper."),
                    "统计 mapper 必须落在 videostats.mapper 下：" + namespace);
            assertTrue(configuration.hasMapper(mapper),
                    "XML namespace 必须解析到 " + namespace);
            for (Method method : mapper.getDeclaredMethods()) {
                assertTrue(configuration.hasStatement(namespace + "." + method.getName()),
                        "缺少 MappedStatement：" + namespace + "." + method.getName());
            }
        }
    }

    @Test
    void resultTypesPointAtTheMigratedStatisticsTypes() throws IOException {
        Configuration status = parse("VideoStatusMapper.xml");
        assertEquals(VideoStatus.class,
                resultType(status, VideoStatusMapper.class, "selectByVid"));
        assertEquals(Long.class,
                resultType(status, VideoStatusMapper.class, "countLikeByUid"));

        Configuration consumed = parse("VideoStatusConsumedEventMapper.xml");
        assertEquals(VideoStatusConsumedEvent.class,
                resultMapType(consumed, VideoStatusConsumedEventMapper.class, "selectByEventId"));

        Configuration outbox = parse("VideoStatusOutboxMapper.xml");
        assertEquals(VideoStatusOutbox.class,
                resultType(outbox, VideoStatusOutboxMapper.class, "selectPendingForUpdate"));

        Configuration repair = parse("VideoStatusOutboxRepairMapper.xml");
        assertEquals(VideoStatusConsumptionRepairLog.class,
                resultType(repair, VideoStatusOutboxRepairMapper.class,
                        "selectRepairLogByOperationId"));
        assertEquals(VideoStatusOutbox.class,
                resultType(repair, VideoStatusOutboxRepairMapper.class, "selectSafeByEventId"));

        Configuration reconciliation = parse("VideoStatusReconciliationMapper.xml");
        assertEquals(VideoStatusReconciliationEventRow.class,
                resultMapType(reconciliation, VideoStatusReconciliationMapper.class,
                        "selectEventProjection"));
        assertEquals(VideoStatusReconciliationRelationAggregate.class,
                resultMapType(reconciliation, VideoStatusReconciliationMapper.class,
                        "selectRelationAggregate"));
        assertEquals(VideoStatus.class,
                resultMapType(reconciliation, VideoStatusReconciliationMapper.class,
                        "selectDbCounts"));

        Configuration admin = parse("VideoStatusFailureAdminMapper.xml");
        assertEquals(VideoStatusFailureAdminRow.OutboxRow.class,
                resultType(admin, VideoStatusFailureAdminMapper.class, "selectFailedOutbox"));
        assertEquals(VideoStatusStatus3ReplayRow.ConsumedTarget.class,
                resultType(admin, VideoStatusFailureAdminMapper.class,
                        "selectStatus3ReplayTarget"));
        assertEquals(VideoStatusStatus3ReplayRow.OutboxSource.class,
                resultType(admin, VideoStatusFailureAdminMapper.class,
                        "selectOutboxReplaySource"));
    }

    @Test
    void statisticsMappersAreDiscoveredUnderTheApplicationMapperScanRoot() {
        Set<String> mapperInterfaces = mapperInterfacesUnder(SCAN_ROOT);

        for (Class<?> mapper : MAPPERS.keySet()) {
            assertTrue(mapperInterfaces.contains(mapper.getName()),
                    SCAN_ROOT + " 的 @Mapper 扫描必须包含 " + mapper.getName());
            String oldFqcn = OLD_MAPPER_PACKAGE + mapper.getSimpleName();
            assertFalse(mapperInterfaces.contains(oldFqcn),
                    "旧包不得再注册 " + oldFqcn);
            assertThrows(ClassNotFoundException.class, () -> Class.forName(oldFqcn),
                    "旧包不得残留同名类（同一类只存在一份）：" + oldFqcn);
        }
    }

    private static Configuration parse(String fileName) throws IOException {
        Path xml = XML_DIR.resolve(fileName);
        assertTrue(Files.isRegularFile(xml), "缺少 mapper 资源：" + xml.toAbsolutePath());
        Configuration configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        try (InputStream in = Files.newInputStream(xml)) {
            new XMLMapperBuilder(in, configuration, xml.toString(),
                    configuration.getSqlFragments()).parse();
        }
        return configuration;
    }

    private static Class<?> resultType(Configuration configuration, Class<?> mapper, String statementId) {
        return configuration.getMappedStatement(mapper.getName() + "." + statementId)
                .getResultMaps().get(0).getType();
    }

    private static Class<?> resultMapType(Configuration configuration, Class<?> mapper, String statementId) {
        List<ResultMap> resultMaps = configuration
                .getMappedStatement(mapper.getName() + "." + statementId)
                .getResultMaps();
        return resultMaps.get(0).getType();
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
