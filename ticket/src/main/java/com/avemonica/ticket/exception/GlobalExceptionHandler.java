package com.avemonica.ticket.exception;

import com.avemonica.ticket.common.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j // 启用 Lombok 的日志功能
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 1. 拦截我们刚刚自定义的业务异常 (BusinessException)
     */
    @ExceptionHandler(BusinessException.class)
    public Result<String> handleBusinessException(BusinessException e) {
        // 打印黄色的警告日志，方便后端排查，但不需要打印出吓人的堆栈
        log.warn("业务异常拦截: code={}, message={}", e.getCode(), e.getMessage());

        Result<String> result = Result.error(e.getMessage());
        result.setCode(e.getCode()); // 将自定义的 code 塞进返回体
        return result;
    }

    /**
     * 2. 拦截前端参数校验异常 (MethodArgumentNotValidException)
     * 比如前端传的手机号格式不对，@Pattern 注解没通过，就会进这里
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<String> handleValidationException(MethodArgumentNotValidException e) {
        BindingResult bindingResult = e.getBindingResult();
        StringBuilder errorMsg = new StringBuilder();

        // 把所有的校验错误信息拼接起来 (比如 "手机号不能为空; 密码长度不够;")
        for (FieldError fieldError : bindingResult.getFieldErrors()) {
            errorMsg.append(fieldError.getDefaultMessage()).append("; ");
        }

        log.warn("参数校验异常: {}", errorMsg);
        return Result.error(errorMsg.toString());
    }

    /**
     * 3. 终极兜底：拦截所有未知的运行时异常 (Exception)
     * 比如空指针异常(NullPointerException)、数据库连接断开等
     */
    @ExceptionHandler(Exception.class)
    public Result<String> handleException(Exception e) {
        // 打印红色的错误日志，包含完整的堆栈信息，必须修 bug 了！
        log.error("系统未知异常: ", e);

        // 绝对不能把包含代码逻辑的 e.getMessage() 直接返回给前端，用友好的提示兜底
        return Result.error("系统繁忙，请稍后再试");
    }
}