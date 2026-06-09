package com.aicall.controller.tenant;

import com.aicall.common.Result;
import com.aicall.service.AuthService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/tenant")
@RequiredArgsConstructor
public class TenantAuthController {

    private final AuthService authService;

    @PostMapping("/login")
    public Result<Map<String, Object>> login(@RequestBody LoginReq req) {
        return Result.ok(authService.tenantLogin(req.getUsername(), req.getPassword()));
    }

    @Data
    public static class LoginReq {
        private String username;
        private String password;
    }
}
