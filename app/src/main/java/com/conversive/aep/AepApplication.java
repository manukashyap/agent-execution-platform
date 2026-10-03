package com.conversive.aep;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class AepApplication {

    public static void main(String[] args) {
        SpringApplication.run(AepApplication.class, args);
    }
}
