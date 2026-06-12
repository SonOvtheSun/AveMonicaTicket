package com.avemonica.ticket.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class UserRegisterDTO {

    @NotBlank(message = "手机号不能为空")
    // 中国大陆手机号正则：1开头，第二位是3-9，后面9位数字
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String phone;

    @NotBlank(message = "密码不能为空")
    private String password;

    @NotBlank(message = "昵称不能为空")
    private String username;

    @NotBlank(message = "验证码不能为空")
    private String code;

    private String registerTicket;
}
