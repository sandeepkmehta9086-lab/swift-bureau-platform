package com.swiftbureau;

import com.swiftbureau.config.AppProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(AppProperties.class)
public class SwiftBureauApplication {

    public static void main(String[] args) {
        SpringApplication.run(SwiftBureauApplication.class, args);
    }
}
