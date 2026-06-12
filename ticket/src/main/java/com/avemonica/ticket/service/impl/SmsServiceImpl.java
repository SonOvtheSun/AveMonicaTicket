package com.avemonica.ticket.service.impl;

import com.aliyun.dypnsapi20170525.Client;
import com.aliyun.dypnsapi20170525.models.CheckSmsVerifyCodeRequest;
import com.aliyun.dypnsapi20170525.models.CheckSmsVerifyCodeResponse;
import com.aliyun.dypnsapi20170525.models.SendSmsVerifyCodeRequest;
import com.aliyun.dypnsapi20170525.models.SendSmsVerifyCodeResponse;
import com.aliyun.teaopenapi.models.Config;
import com.aliyun.teautil.models.RuntimeOptions;
import com.avemonica.ticket.common.Result;
import com.avemonica.ticket.config.AliyunDypnsProperties;
import com.avemonica.ticket.service.SmsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class SmsServiceImpl implements SmsService {

    @Autowired
    private AliyunDypnsProperties properties;

    private Client createClient() throws Exception {
        Config config = new Config()
                .setAccessKeyId(properties.getAccessKeyId())
                .setAccessKeySecret(properties.getAccessKeySecret());

        config.endpoint = "dypnsapi.aliyuncs.com";

        return new Client(config);
    }

    @Override
    public Result<String> sendCode(String phone) {
        if (!StringUtils.hasText(phone) || !phone.matches("^1[3-9]\\d{9}$")) {
            return Result.error("手机号格式不正确");
        }

        try {
            Client client = createClient();

            SendSmsVerifyCodeRequest request = new SendSmsVerifyCodeRequest()
                    .setPhoneNumber(phone)
                    .setCountryCode("86")
                    .setSignName(properties.getSignName())
                    .setTemplateCode(properties.getTemplateCode())
                    // 使用阿里云生成验证码，后面才能用 CheckSmsVerifyCode 核验
                    .setTemplateParam("{\"code\":\"##code##\",\"min\":\"5\"}")
                    .setCodeLength(6L)
                    .setValidTime(300L)
                    .setInterval(60L)
                    .setCodeType(1L)
                    .setReturnVerifyCode(false);

            RuntimeOptions runtime = new RuntimeOptions();
            SendSmsVerifyCodeResponse response = client.sendSmsVerifyCodeWithOptions(request, runtime);

            if (response == null || response.getBody() == null) {
                return Result.error("验证码发送失败：阿里云无响应");
            }

            if (!Boolean.TRUE.equals(response.getBody().getSuccess())) {
                String message = response.getBody().getMessage();
                return Result.error("验证码发送失败：" + message);
            }

            return Result.success("验证码已发送", null);
        } catch (Exception e) {
            return Result.error("验证码发送异常：" + e.getMessage());
        }
    }

    @Override
    public boolean verifyCode(String phone, String code) {
        return checkCodeOnly(phone, code);
    }

    @Override
    public boolean checkCodeOnly(String phone, String code) {
        if (!StringUtils.hasText(phone) || !phone.matches("^1[3-9]\\d{9}$")) {
            return false;
        }

        if (!StringUtils.hasText(code)) {
            return false;
        }

        try {
            Client client = createClient();

            CheckSmsVerifyCodeRequest request = new CheckSmsVerifyCodeRequest()
                    .setPhoneNumber(phone)
                    .setCountryCode("86")
                    .setVerifyCode(code)
                    .setCaseAuthPolicy(1L);

            RuntimeOptions runtime = new RuntimeOptions();
            CheckSmsVerifyCodeResponse response = client.checkSmsVerifyCodeWithOptions(request, runtime);

            if (response == null || response.getBody() == null || response.getBody().getModel() == null) {
                return false;
            }

            return "PASS".equals(response.getBody().getModel().getVerifyResult());
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void consumeCode(String phone) {
        // 使用阿里云短信认证服务后，不再需要手动删除 Redis 验证码。
        // 验证码状态由阿里云 CheckSmsVerifyCode 负责。
    }
}