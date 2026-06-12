package com.avemonica.ticket.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "aliyun.dypns")
public class AliyunDypnsProperties {

    private String accessKeyId;

    private String accessKeySecret;

    private String signName;

    private String templateCode;
}