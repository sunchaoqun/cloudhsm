package com.example.hsmdemo;

import com.example.hsmdemo.config.HsmProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(HsmProperties.class)
public class AppConfig {
}
