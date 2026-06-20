package com.example.complaints;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

import java.util.TimeZone;

/**
 * Application entry point. Pins the JVM TimeZone to Asia/Kolkata at the earliest
 * possible point so any static initializers running during bean construction
 * already see IST. See TECHNICAL_DESIGN.md §16.1.
 */
@SpringBootApplication
@ConfigurationPropertiesScan(basePackages = {
        "com.example.complaints.config",
        "com.example.complaints.auth.security"
})
public class ComplaintsApplication {

    public static void main(String[] args) {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"));
        SpringApplication.run(ComplaintsApplication.class, args);
    }
}

