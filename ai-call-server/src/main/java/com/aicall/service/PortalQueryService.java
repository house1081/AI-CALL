package com.aicall.service;

import com.aicall.dto.CallRecordVO;
import com.aicall.dto.CallTaskVO;
import com.aicall.dto.IntentCustomerVO;
import com.aicall.entity.*;
import com.aicall.mapper.*;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PortalQueryService {

    private final CallRecordMapper callRecordMapper;
    private final CustomerMapper customerMapper;
    private final CustomerGroupMapper customerGroupMapper;
    private final DialogCallContextService dialogCallContextService;
    private final AiPromptMapper aiPromptMapper;
    private final DialogKnowledgeBaseMapper dialogKnowledgeBaseMapper;

    public List<CallRecordVO> enrichCallRecords(List<CallRecord> records) {
        if (records.isEmpty()) {
            return List.of();
        }
        Set<Integer> customerIds = records.stream()
                .map(CallRecord::getCustomerId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Integer, Customer> customerMap = customerIds.isEmpty() ? Map.of()
                : customerMapper.selectBatchIds(customerIds).stream()
                .collect(Collectors.toMap(Customer::getId, c -> c));

        return records.stream().map(r -> {
            CallRecordVO vo = new CallRecordVO();
            copyCallRecord(r, vo);
            if (r.getCustomerId() != null && customerMap.containsKey(r.getCustomerId())) {
                vo.setCustomerName(customerMap.get(r.getCustomerId()).getName());
            }
            return vo;
        }).toList();
    }

    public List<CallTaskVO> enrichTasks(List<CallTask> tasks) {
        if (tasks.isEmpty()) {
            return List.of();
        }
        Set<Integer> groupIds = tasks.stream().map(CallTask::getGroupId).collect(Collectors.toSet());
        Map<Integer, CustomerGroup> groupMap = customerGroupMapper.selectBatchIds(groupIds).stream()
                .collect(Collectors.toMap(CustomerGroup::getId, g -> g));
        return tasks.stream().map(t -> {
            CallTaskVO vo = new CallTaskVO();
            copyTask(t, vo);
            CustomerGroup g = groupMap.get(t.getGroupId());
            vo.setGroupName(g != null ? g.getGroupName() : "未分组");
            var ctx = dialogCallContextService.resolveForTenantTask(t.getTenantId(), t.getId());
            if (ctx.getPromptId() != null) {
                AiPrompt prompt = aiPromptMapper.selectById(ctx.getPromptId());
                if (prompt != null) {
                    vo.setPromptName(StringUtils.hasText(prompt.getPromptName())
                            ? prompt.getPromptName() : ("模板#" + prompt.getId()));
                }
            }
            if (ctx.hasKb()) {
                vo.setKbId(ctx.getKbId());
                DialogKnowledgeBase kb = dialogKnowledgeBaseMapper.selectById(ctx.getKbId());
                if (kb != null) {
                    vo.setKbName(kb.getKbName());
                }
            }
            return vo;
        }).toList();
    }

    /** 数据库分页 + 批量取最新通话，避免 N+1 */
    public List<IntentCustomerVO> listIntentCustomers(Integer tenantId, String level, int page, int pageSize) {
        LambdaQueryWrapper<Customer> q = new LambdaQueryWrapper<Customer>()
                .eq(Customer::getTenantId, tenantId)
                .isNotNull(Customer::getLevel)
                .ne(Customer::getLevel, "");
        if (StringUtils.hasText(level)) {
            q.eq(Customer::getLevel, level);
        }
        q.last("ORDER BY FIELD(level,'A','B','C','D'), last_call_time DESC");

        Page<Customer> p = customerMapper.selectPage(new Page<>(page, pageSize), q);
        List<Customer> customers = p.getRecords();
        if (customers.isEmpty()) {
            return List.of();
        }

        List<String> phones = customers.stream().map(Customer::getPhone).toList();
        Map<String, CallRecord> latestByPhone = phones.isEmpty()
                ? Map.of()
                : callRecordMapper.selectLatestByPhones(tenantId, phones).stream()
                        .collect(Collectors.toMap(CallRecord::getCustomerPhone, r -> r, (a, b) -> a));

        return customers.stream().map(c -> {
            IntentCustomerVO vo = new IntentCustomerVO();
            vo.setId(c.getId());
            vo.setPhone(c.getPhone());
            vo.setName(c.getName());
            vo.setLevel(c.getLevel());
            vo.setLastCallTime(c.getLastCallTime());
            CallRecord latest = latestByPhone.get(c.getPhone());
            if (latest != null) {
                vo.setCustomerNeed(latest.getCustomerNeed());
                vo.setCustomerPain(latest.getCustomerPain());
                vo.setBudget(latest.getBudget());
                vo.setNextTime(latest.getNextTime());
            }
            return vo;
        }).toList();
    }

    public long countIntentCustomers(Integer tenantId, String level) {
        LambdaQueryWrapper<Customer> q = new LambdaQueryWrapper<Customer>()
                .eq(Customer::getTenantId, tenantId)
                .isNotNull(Customer::getLevel)
                .ne(Customer::getLevel, "");
        if (StringUtils.hasText(level)) {
            q.eq(Customer::getLevel, level);
        }
        return customerMapper.selectCount(q);
    }

    private void copyCallRecord(CallRecord src, CallRecordVO dest) {
        dest.setId(src.getId());
        dest.setTenantId(src.getTenantId());
        dest.setLineId(src.getLineId());
        dest.setCustomerId(src.getCustomerId());
        dest.setCustomerPhone(src.getCustomerPhone());
        dest.setCallDuration(src.getCallDuration());
        dest.setBilledMinutes(src.getBilledMinutes());
        dest.setDeductAmount(src.getDeductAmount());
        dest.setCostAmount(src.getCostAmount());
        dest.setProfit(src.getProfit());
        dest.setProfitAbnormal(src.getProfitAbnormal());
        dest.setRecordUrl(src.getRecordUrl());
        dest.setDialogText(src.getDialogText());
        dest.setCustomerNeed(src.getCustomerNeed());
        dest.setCustomerPain(src.getCustomerPain());
        dest.setBudget(src.getBudget());
        dest.setNextTime(src.getNextTime());
        dest.setLevel(src.getLevel());
        dest.setCallStatus(src.getCallStatus());
        dest.setCallTime(src.getCallTime());
        dest.setTaskId(src.getTaskId());
    }

    private void copyTask(CallTask src, CallTaskVO dest) {
        BeanUtils.copyProperties(src, dest);
    }
}
