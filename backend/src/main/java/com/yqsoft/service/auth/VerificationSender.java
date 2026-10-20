package com.yqsoft.service.auth;

public interface VerificationSender {
    void send(String destination, String code, String purpose);
}
