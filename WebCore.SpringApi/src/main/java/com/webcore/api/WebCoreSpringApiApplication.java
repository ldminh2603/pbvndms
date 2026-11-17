package com.webcore.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class WebCoreSpringApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(WebCoreSpringApiApplication.class, args);
    }
}
