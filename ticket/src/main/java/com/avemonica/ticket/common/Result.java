package com.avemonica.ticket.common;
import lombok.Data;

@Data
public class Result<T> {
    private Integer code;
    private String message;
    private T data;

    public static <T> Result<T> success(String msg, T data) {
        Result<T> r = new Result<>();
        r.code = 200; r.message = msg; r.data = data;
        return r;
    }
    public static <T> Result<T> error(String msg) {
        Result<T> r = new Result<>();
        r.code = 500; r.message = msg;
        return r;
    }
}