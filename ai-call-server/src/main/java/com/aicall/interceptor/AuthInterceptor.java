package com.aicall.interceptor;

import com.aicall.common.BizException;
import com.aicall.context.LoginUser;
import com.aicall.context.UserContext;
import com.aicall.util.JwtUtil;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    private final JwtUtil jwtUtil;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String auth = request.getHeader("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            throw new BizException(401, "未登录");
        }
        try {
            Claims claims = jwtUtil.parse(auth.substring(7));
            LoginUser user = new LoginUser();
            user.setUserId(((Number) claims.get("userId")).intValue());
            user.setUsername(claims.get("username", String.class));
            user.setRole(claims.get("role", String.class));
            if ("tenant".equals(user.getRole())) {
                user.setTenantId(user.getUserId());
            }
            UserContext.set(user);
            return true;
        } catch (Exception e) {
            throw new BizException(401, "登录已过期");
        }
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        UserContext.clear();
    }
}
