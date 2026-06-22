package com.example.complaints.auth.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Dev-only {@link SmsService}: writes the OTP to stdout with a clear {@code [DEV-SMS]} banner so
 * the developer can copy it into the verify call. Deliberately bypasses SLF4J — the hard rule
 * forbids logging secrets / OTPs through structured logging (see
 * {@code .github/copilot-instructions.md} §"Code style" #8).
 *
 * <p>Active when {@code app.sms.provider=console} (default in dev). Test / prod override with
 * {@code app.sms.provider=msg91} once the MSG91 adapter ships.</p>
 */
@Service
@ConditionalOnProperty(prefix = "app.sms", name = "provider", havingValue = "console", matchIfMissing = true)
public class ConsoleSmsService implements SmsService {

    @Override
    public void sendOtp(String mobile, String otp) {
        // System.out is intentional — see Javadoc above.
        System.out.println("[DEV-SMS] mobile=" + mobile + " otp=" + otp);
    }
}

