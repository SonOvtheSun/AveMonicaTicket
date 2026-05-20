package com.avemonica.ticket.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class UserLoginDTO {
    @NotBlank(message = "手机号不能为空")
    private String account;

    @NotBlank(message = "密码不能为空")
    private String password;
}