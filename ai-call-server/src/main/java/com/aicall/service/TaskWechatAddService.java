package com.aicall.service;

import com.aicall.entity.CallTask;
import com.aicall.entity.Customer;
import com.aicall.mapper.CallTaskMapper;
import com.aicall.mapper.CustomerMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 外呼任务接通后，按任务配置调用第三方「加微信好友」HTTP 接口。
 */
@Slf4j
@Service
public class TaskWechatAddService {

    private static final String DEDUP_KEY = "wechat:add:";
    private static final Duration DEDUP_TTL = Duration.ofHours(24);
    private static final String DEFAULT_MESSAGE = "你好，我是刚才和您通话的金融顾问，方便通过一下吗？";

    private final CallTaskMapper callTaskMapper;
    private final CustomerMapper customerMapper;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final RestTemplate wechatAddRestTemplate;

    private final ExecutorService executor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "task-wechat-add");
        t.setDaemon(true);
        return t;
    });

    public TaskWechatAddService(CallTaskMapper callTaskMapper,
                                CustomerMapper customerMapper,
                                StringRedisTemplate redisTemplate,
                                ObjectMapper objectMapper,
                                RestTemplateBuilder restTemplateBuilder) {
        this.callTaskMapper = callTaskMapper;
        this.customerMapper = customerMapper;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.wechatAddRestTemplate = restTemplateBuilder
                .setConnectTimeout(Duration.ofSeconds(5))
                .setReadTimeout(Duration.ofSeconds(15))
                .build();
    }

    /** 客户摘机接通后异步触发，不阻塞对话线程 */
    public void scheduleOnAnswered(Integer taskId, String customerPhone, Integer customerId) {
        if (taskId == null || !StringUtils.hasText(customerPhone)) {
            return;
        }
        executor.submit(() -> tryAddFriend(taskId, customerPhone.trim(), customerId));
    }

    private void tryAddFriend(Integer taskId, String phone, Integer customerId) {
        try {
            CallTask task = callTaskMapper.selectById(taskId);
            if (task == null || !isEnabled(task.getAutoAddWechat())) {
                return;
            }
            String apiUrl = task.getWechatAddApiUrl();
            if (!StringUtils.hasText(apiUrl)) {
                log.warn("[加V] 任务{}已开启自动加V但未配置接口地址", taskId);
                return;
            }
            String dedupKey = DEDUP_KEY + taskId + ":" + phone;
            if (Boolean.TRUE.equals(redisTemplate.hasKey(dedupKey))) {
                log.debug("[加V] 已请求过，跳过 taskId={} phone={}", taskId, phone);
                return;
            }
            Customer customer = customerId != null ? customerMapper.selectById(customerId) : null;
            String message = buildMessage(task, phone, customer);
            Map<String, String> body = new LinkedHashMap<>();
            body.put("phone", phone);
            body.put("message", message);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            String json = objectMapper.writeValueAsString(body);
            log.info("[加V] 请求 taskId={} url={} phone={} message={}",
                    taskId, apiUrl, phone,
                    message.length() > 40 ? message.substring(0, 40) + "..." : message);

            ResponseEntity<String> resp = wechatAddRestTemplate.postForEntity(
                    apiUrl.trim(), new HttpEntity<>(json, headers), String.class);
            redisTemplate.opsForValue().set(dedupKey, "1", DEDUP_TTL);
            log.info("[加V] 成功 taskId={} phone={} http={} body={}",
                    taskId, phone, resp.getStatusCode().value(),
                    resp.getBody() != null && resp.getBody().length() > 120
                            ? resp.getBody().substring(0, 120) + "..." : resp.getBody());
        } catch (Exception e) {
            log.warn("[加V] 失败 taskId={} phone={}: {}", taskId, phone, e.getMessage());
        }
    }

    private static boolean isEnabled(Integer flag) {
        return flag != null && flag == 1;
    }

    private String buildMessage(CallTask task, String phone, Customer customer) {
        String name = customer != null && StringUtils.hasText(customer.getName())
                ? customer.getName().trim() : "";
        String remarkTpl = StringUtils.hasText(task.getWechatAddRemark())
                ? task.getWechatAddRemark().trim() : "";
        String remark = applyTemplate(remarkTpl, phone, name, task.getTaskName(), remarkTpl);

        String msgTpl = StringUtils.hasText(task.getWechatAddMessage())
                ? task.getWechatAddMessage().trim() : DEFAULT_MESSAGE;
        String message = applyTemplate(msgTpl, phone, name, task.getTaskName(), remark);
        if (StringUtils.hasText(remark) && !message.contains(remark) && !msgTpl.contains("{remark}")) {
            message = message + " " + remark;
        }
        return message.trim();
    }

    private static String applyTemplate(String template, String phone, String name,
                                        String taskName, String remark) {
        if (!StringUtils.hasText(template)) {
            return "";
        }
        return template
                .replace("{phone}", phone != null ? phone : "")
                .replace("{name}", name != null ? name : "")
                .replace("{taskName}", taskName != null ? taskName : "")
                .replace("{remark}", remark != null ? remark : "");
    }
}
