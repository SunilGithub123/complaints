package com.example.complaints.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bootstrap admin env-var holder. Read by {@code AuthBootstrapRunner} on startup.
 * See TECHNICAL_DESIGN.md §6 "Bootstrap Admin".
 */
@ConfigurationProperties(prefix = "app.bootstrap-admin")
public record BootstrapAdminProperties(
        String employeeId,
        String password,
        String subdivisionCode
) {
    public boolean isComplete() {
        return employeeId != null && !employeeId.isBlank()
                && password != null && !password.isBlank()
                && subdivisionCode != null && !subdivisionCode.isBlank();
    }
}

