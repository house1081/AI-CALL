package com.aicall.interceptor;

import com.aicall.config.FreeSwitchProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class CallbackSecretInterceptor implements HandlerInterceptor {

    private final FreeSwitchProperties props;
    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String secret = props.getCallbackSecret();
        if (!StringUtils.hasText(secret)) {
            return true;
        }
        String header = request.getHeader("X-Callback-Secret");
        if (secret.equals(header)) {
            return true;
        }
        response.setStatus(401);
        response.setContentType("application/json;charset=UTF-8");
        objectMapper.writeValue(response.getWriter(), Map.of("code", 401, "message", "回调密钥无效"));
        return false;
    }
}
