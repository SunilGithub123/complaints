package com.example.complaints.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;

import java.util.TimeZone;

/**
 * Forces the JVM default TimeZone to Asia/Kolkata at startup, defending against env-misconfiguration.
 * See TECHNICAL_DESIGN.md §16.1.
 */
@Configuration
public class TimezoneConfig {

    private static final Logger log = LoggerFactory.getLogger(TimezoneConfig.class);

    @PostConstruct
    public void setDefaultTimezone() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"));
        log.info("JVM default TimeZone set to {}", TimeZone.getDefault().getID());
    }
}

