package com.aicall.config;

import com.aicall.entity.AdminUser;
import com.aicall.mapper.AdminUserMapper;
import com.aicall.util.Md5Util;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动时若无管理员则自动创建 admin / admin123
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminUserInitializer implements ApplicationRunner {

    private final AdminUserMapper adminUserMapper;

    private static final String DEFAULT_USER = "admin";
    private static final String DEFAULT_PASS_MD5 = Md5Util.encrypt("admin123");

    @Override
    public void run(ApplicationArguments args) {
        AdminUser admin = adminUserMapper.selectOne(new LambdaQueryWrapper<AdminUser>()
                .eq(AdminUser::getUsername, DEFAULT_USER));
        if (admin == null) {
            AdminUser user = new AdminUser();
            user.setUsername(DEFAULT_USER);
            user.setPassword(DEFAULT_PASS_MD5);
            adminUserMapper.insert(user);
            log.warn("已自动创建默认管理员 {} / admin123", DEFAULT_USER);
            return;
        }
        String pwd = admin.getPassword();
        boolean needFix = pwd == null
                || "admin123".equals(pwd)
                || pwd.length() != 32
                || !pwd.equalsIgnoreCase(DEFAULT_PASS_MD5);
        if (needFix) {
            admin.setPassword(DEFAULT_PASS_MD5);
            adminUserMapper.updateById(admin);
            log.warn("已自动修复管理员 {} 的密码哈希，请使用 admin / admin123 登录", DEFAULT_USER);
        }
    }
}
