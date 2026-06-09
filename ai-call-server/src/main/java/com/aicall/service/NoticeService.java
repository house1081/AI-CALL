package com.aicall.service;

import com.aicall.entity.SystemNotice;
import com.aicall.entity.Tenant;
import com.aicall.mapper.SystemNoticeMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class NoticeService {

    private final SystemNoticeMapper systemNoticeMapper;

    public void notifyBalanceLow(Tenant tenant) {
        SystemNotice adminNotice = new SystemNotice();
        adminNotice.setTargetRole("admin");
        adminNotice.setTargetId(null);
        adminNotice.setTitle("商户余额不足");
        adminNotice.setContent("商户[" + tenant.getUsername() + "]余额不足，已暂停外呼任务");
        systemNoticeMapper.insert(adminNotice);

        SystemNotice tenantNotice = new SystemNotice();
        tenantNotice.setTargetRole("tenant");
        tenantNotice.setTargetId(tenant.getId());
        tenantNotice.setTitle("余额不足");
        tenantNotice.setContent("余额不足，请立即充值，外呼任务已暂停");
        systemNoticeMapper.insert(tenantNotice);
    }

    public List<SystemNotice> listForAdmin(boolean unreadOnly) {
        LambdaQueryWrapper<SystemNotice> q = new LambdaQueryWrapper<SystemNotice>()
                .eq(SystemNotice::getTargetRole, "admin")
                .orderByDesc(SystemNotice::getId)
                .last("LIMIT 50");
        if (unreadOnly) {
            q.eq(SystemNotice::getIsRead, 0);
        }
        return systemNoticeMapper.selectList(q);
    }

    public List<SystemNotice> listForTenant(Integer tenantId) {
        return systemNoticeMapper.selectList(new LambdaQueryWrapper<SystemNotice>()
                .eq(SystemNotice::getTargetRole, "tenant")
                .eq(SystemNotice::getTargetId, tenantId)
                .orderByDesc(SystemNotice::getId)
                .last("LIMIT 20"));
    }

    public void markRead(Integer id) {
        systemNoticeMapper.update(null, new LambdaUpdateWrapper<SystemNotice>()
                .eq(SystemNotice::getId, id)
                .set(SystemNotice::getIsRead, 1));
    }

    public void notifyRechargeResult(Integer tenantId, boolean success,
                                     java.math.BigDecimal amount, String failReason) {
        SystemNotice n = new SystemNotice();
        n.setTargetRole("tenant");
        n.setTargetId(tenantId);
        if (success) {
            n.setTitle("充值到账");
            n.setContent("您提交的充值 " + amount + " 元已到账");
        } else {
            n.setTitle("充值审核失败");
            n.setContent("充值 " + amount + " 元审核未通过：" + (failReason != null ? failReason : "请联系管理员"));
        }
        systemNoticeMapper.insert(n);
    }
}
