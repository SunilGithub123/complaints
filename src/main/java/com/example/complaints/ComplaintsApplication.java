package com.example.complaints;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.TimeZone;

/**
 * Application entry point. Pins the JVM TimeZone to Asia/Kolkata at the earliest
 * possible point so any static initializers running during bean construction
 * already see IST. See TECHNICAL_DESIGN.md §16.1.
 */
@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan(basePackages = {
        "com.example.complaints.config",
        "com.example.complaints.auth.security",
        "com.example.complaints.auth.service",
        "com.example.complaints.complaint",
        "com.example.complaints.storage"
})
public class ComplaintsApplication {

    public static void main(String[] args) {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"));
        SpringApplication.run(ComplaintsApplication.class, args);
    }
}

