package com.aicall.controller.admin;

import com.aicall.common.Result;
import com.aicall.service.AuthService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminAuthController {

    private final AuthService authService;

    @PostMapping("/login")
    public Result<Map<String, Object>> login(@RequestBody LoginReq req) {
        return Result.ok(authService.adminLogin(req.getUsername(), req.getPassword()));
    }

    @Data
    public static class LoginReq {
        private String username;
        private String password;
    }
}
