package com.avemonica.ticket.exception;

import lombok.Getter;

@Getter
public class BusinessException extends RuntimeException {

    private Integer code;

    // 默认 500 错误码的构造函数
    public BusinessException(String message) {
        super(message);
        this.code = 500;
    }

    // 自定义错误码的构造函数
    public BusinessException(Integer code, String message) {
        super(message);
        this.code = code;
    }
}
