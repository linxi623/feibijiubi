package com.feibijiubi.backend.infrastructure.storage;

import com.feibijiubi.backend.BackendApplication;
import com.feibijiubi.backend.infrastructure.storage.config.TencentCosConfig;
import com.feibijiubi.backend.infrastructure.storage.config.TencentCosProperties;
import com.feibijiubi.backend.infrastructure.storage.service.FileStorageService;
import com.feibijiubi.backend.infrastructure.storage.service.impl.TencentCosStorageServiceImpl;
import com.feibijiubi.backend.video.mapper.UploadTempFileMapper;
import com.qcloud.cos.COSClient;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import javax.sql.DataSource;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * R5：验证 COS 存储能力搬进 {@code infrastructure.storage} 后，配置项、bean 名和存储签名都没变。
 *
 * <p>本类<b>不</b>访问 COS、不连数据库：{@link COSClient} 只用假配置构造（构造期不做 DNS/网络调用），
 * {@link UploadTempFileMapper} 是 mock，上下文只注册存储相关的三个类。
 * 它证明的是“{@code tencent.cos.*} 仍绑到 {@link TencentCosProperties}、
 * {@code COSClient} 与 {@code tencentCosStorageServiceImpl} 仍按原名注册、
 * {@link FileStorageService} 仍只有一个实现”，而不是对象内部的 SDK 行为。</p>
 */
class TencentCosStorageWiringTests {

    private static final String BASE_URL = "https://cdn.example.invalid";

    /** 按生产路径装配：配置类显式导入，存储实现由组件扫描注册（bean 名来自 @Service）。 */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(TencentCosProperties.class)
    @ComponentScan(basePackageClasses = TencentCosStorageServiceImpl.class)
    @Import(TencentCosConfig.class)
    static class CosStorageWiringConfiguration {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(CosStorageWiringConfiguration.class)
            .withBean(UploadTempFileMapper.class, () -> mock(UploadTempFileMapper.class));

    @Test
    void cosClientPropertiesAndStorageServiceAreWiredWithoutTouchingCosOrDatabase() {
        runner.withPropertyValues(
                        "tencent.cos.region=ap-guangzhou",
                        "tencent.cos.bucket=test-bucket-000",
                        "tencent.cos.base-url=" + BASE_URL,
                        "tencent.cos.app-id=1000000000",
                        "tencent.cos.secret-id=test-secret-id",
                        "tencent.cos.secret-key=test-secret-key")
                .run(context -> {
                    COSClient client = null;
                    try {
                        assertNull(context.getStartupFailure(), failureMessage(context));

                        client = context.getBean(COSClient.class);
                        TencentCosProperties properties = context.getBean(TencentCosProperties.class);
                        assertEquals("ap-guangzhou", properties.getRegion(), "tencent.cos.region 绑定");
                        assertEquals("test-bucket-000", properties.getBucket(), "tencent.cos.bucket 绑定");
                        assertEquals(BASE_URL, properties.getBaseUrl(), "tencent.cos.base-url 绑定");

                        assertEquals(1, context.getBeanNamesForType(COSClient.class).length,
                                "COSClient 必须唯一");
                        assertTrue(context.containsBean("COSClient"),
                                "COS 客户端 bean 名不得因搬包改变（来自 @Bean 方法名）");
                        assertEquals(1, context.getBeanNamesForType(FileStorageService.class).length,
                                "存储接口必须唯一实现");
                        assertTrue(context.containsBean("tencentCosStorageServiceImpl"),
                                "存储实现必须仍叫 tencentCosStorageServiceImpl");
                        assertInstanceOf(TencentCosStorageServiceImpl.class,
                                context.getBean(FileStorageService.class));

                        FileStorageService storage = context.getBean(FileStorageService.class);
                        String key = "temp/videos/7/20261030/abcdef.mp4";
                        assertEquals(BASE_URL + "/" + key, storage.Key2Url(key),
                                "存储签名与 URL 约定不变");
                        assertEquals(key, storage.Url2Key(storage.Key2Url(key)),
                                "Url2Key/Key2Url 仍互为逆操作");

                        assertNoInfrastructure(context);
                    } finally {
                        if (client != null) {
                            client.shutdown();
                        }
                    }
                });
    }

    @Test
    void productionRegistrationOfCosConfigurationIsStillDeclared() {
        EnableConfigurationProperties registration =
                BackendApplication.class.getAnnotation(EnableConfigurationProperties.class);
        assertTrue(Set.of(registration.value()).contains(TencentCosProperties.class),
                "BackendApplication 仍必须以 @EnableConfigurationProperties 注册搬包后的配置类");

        ConfigurationProperties prefix =
                TencentCosProperties.class.getAnnotation(ConfigurationProperties.class);
        assertEquals("tencent.cos", prefix.prefix(), "配置前缀不得改变");

        ClassPathScanningCandidateComponentProvider provider =
                new ClassPathScanningCandidateComponentProvider(false);
        provider.addIncludeFilter(new AnnotationTypeFilter(Configuration.class));
        Set<String> configs = new LinkedHashSet<>();
        provider.findCandidateComponents("com.feibijiubi.backend.infrastructure.storage")
                .forEach(candidate -> configs.add(candidate.getBeanClassName()));

        assertTrue(configs.contains(TencentCosConfig.class.getName()),
                "根包扫描必须能发现搬包后的 COS 配置：" + configs);
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName("com.feibijiubi.backend.config.TencentCosProperties"),
                "旧包不得残留配置类（同一类只存在一份）");
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName("com.feibijiubi.backend.service.storage.FileStorageService"),
                "旧包不得残留存储接口（同一类只存在一份）");
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName("com.feibijiubi.backend.service.impl.storage.TencentCosStorageServiceImpl"),
                "旧包不得残留存储实现（同一类只存在一份）");
    }

    private static void assertNoInfrastructure(AssertableApplicationContext context) {
        assertEquals(0, context.getBeanNamesForType(DataSource.class).length,
                "不得创建 DataSource");
        assertEquals(0, context.getBeanNamesForType(RedisConnectionFactory.class).length,
                "不得创建 Redis 连接工厂");
        assertEquals(0, context.getBeanNamesForType(ConnectionFactory.class).length,
                "不得创建 RabbitMQ 连接工厂");
    }

    private static String failureMessage(AssertableApplicationContext context) {
        Throwable failure = context.getStartupFailure();
        return failure == null ? "" : failure.toString();
    }
}
