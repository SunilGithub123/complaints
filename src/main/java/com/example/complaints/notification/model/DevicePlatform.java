package com.example.complaints.notification.model;

/**
 * Push notification platform per Stage 21 contract §3.1.
 * {@code WEB} is reserved in the enum for forward-compat per FE sign-off §9.2 but
 * no web push path is implemented in v1 — mobile (ANDROID + IOS) only.
 */
public enum DevicePlatform {
    ANDROID,
    IOS,
    WEB
}

