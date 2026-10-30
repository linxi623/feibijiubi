package com.feibijiubi.backend;

import com.feibijiubi.backend.auth.config.JwtProperties;
import com.feibijiubi.backend.config.TencentCosProperties;
import com.feibijiubi.backend.interaction.config.VideoInteractionProperties;
import com.feibijiubi.backend.videostats.config.VideoStatusProperties;
import com.feibijiubi.backend.auth.interceptor.LoginInterceptor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@EnableConfigurationProperties({JwtProperties.class, TencentCosProperties.class,
        VideoStatusProperties.class, VideoInteractionProperties.class})
@SpringBootApplication
public class BackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(BackendApplication.class, args);
    }

}
