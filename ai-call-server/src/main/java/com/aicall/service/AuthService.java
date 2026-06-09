package com.aicall.service;

import com.aicall.common.BizException;
import com.aicall.entity.AdminUser;
import com.aicall.entity.Tenant;
import com.aicall.mapper.AdminUserMapper;
import com.aicall.mapper.TenantMapper;
import com.aicall.util.JwtUtil;
import com.aicall.util.Md5Util;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final AdminUserMapper adminUserMapper;
    private final TenantMapper tenantMapper;
    private final JwtUtil jwtUtil;

    public Map<String, Object> adminLogin(String username, String password) {
        if (username != null) {
            username = username.trim();
        }
        if (password != null) {
            password = password.trim();
        }
        AdminUser user = adminUserMapper.selectOne(new LambdaQueryWrapper<AdminUser>()
                .eq(AdminUser::getUsername, username));
        if (user == null || !user.getPassword().equalsIgnoreCase(Md5Util.encrypt(password))) {
            throw new BizException("账号或密码错误");
        }
        String token = jwtUtil.createToken("admin", user.getId(), user.getUsername());
        Map<String, Object> data = new HashMap<>();
        data.put("token", token);
        data.put("username", user.getUsername());
        return data;
    }

    public Map<String, Object> tenantLogin(String username, String password) {
        Tenant tenant = tenantMapper.selectOne(new LambdaQueryWrapper<Tenant>()
                .eq(Tenant::getUsername, username));
        if (tenant == null || !tenant.getPassword().equals(Md5Util.encrypt(password))) {
            throw new BizException("账号或密码错误");
        }
        if (tenant.getStatus() != 1) {
            throw new BizException("账号已被禁用，请联系管理员");
        }
        tenant.setLastLoginTime(LocalDateTime.now());
        tenantMapper.updateById(tenant);
        String token = jwtUtil.createToken("tenant", tenant.getId(), tenant.getUsername());
        Map<String, Object> data = new HashMap<>();
        data.put("token", token);
        data.put("username", tenant.getUsername());
        data.put("tenantId", tenant.getId());
        return data;
    }
}
