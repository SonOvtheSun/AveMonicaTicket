package com.avemonica.ticket.service;
import com.avemonica.ticket.common.Result;

public interface SmsService {
    Result<String> sendCode(String phone);
    boolean verifyCode(String phone, String code);
    boolean checkCodeOnly(String phone, String code);
    void consumeCode(String phone);
}
