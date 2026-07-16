package com.aicall.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public Result<?> handleBiz(BizException e) {
        return Result.fail(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public Result<?> handleBadRequest(IllegalArgumentException e) {
        return Result.fail(400, e.getMessage() != null ? e.getMessage() : "请求参数错误");
    }

    @ExceptionHandler(Exception.class)
    public Result<?> handle(Exception e) {
        log.error("未处理异常", e);
        return Result.fail(e.getMessage() != null ? e.getMessage() : "系统异常");
    }
}
