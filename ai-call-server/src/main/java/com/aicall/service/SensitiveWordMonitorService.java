package com.aicall.service;

import com.aicall.dto.SensitiveWordMatch;
import com.aicall.entity.SensitiveWord;
import com.aicall.mapper.SensitiveWordMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 客户 ASR 文本敏感词/违规词匹配（内存词库，管理端变更后刷新）。
 */
@Slf4j
@Service
@DependsOn("sensitiveWordSchemaInitializer")
@RequiredArgsConstructor
public class SensitiveWordMonitorService {

    public static final int TYPE_VIOLATION = 1;
    public static final int TYPE_SENSITIVE = 2;

    private final SensitiveWordMapper sensitiveWordMapper;
    private final RiskControlService riskControlService;

    private volatile List<WordEntry> entries = new CopyOnWriteArrayList<>();
    private volatile boolean loaded;

    @EventListener(ContextRefreshedEvent.class)
    public void loadOnStartup() {
        if (loaded) {
            return;
        }
        reload();
        loaded = true;
    }

    public void reload() {
        try {
            List<SensitiveWord> rows = sensitiveWordMapper.selectList(
                new LambdaQueryWrapper<SensitiveWord>()
                        .eq(SensitiveWord::getStatus, 1)
                        .orderByDesc(SensitiveWord::getId));
        List<WordEntry> loaded = new ArrayList<>();
        for (SensitiveWord row : rows) {
            if (!StringUtils.hasText(row.getWord())) {
                continue;
            }
            loaded.add(new WordEntry(row.getWord().trim(), row.getWordType()));
        }
        loaded.sort(Comparator.comparingInt((WordEntry e) -> e.word.length()).reversed());
        entries = new CopyOnWriteArrayList<>(loaded);
        log.info("[敏感词] 词库已加载 {} 条", loaded.size());
        } catch (Exception e) {
            entries = new CopyOnWriteArrayList<>();
            log.warn("[敏感词] 词库加载失败: {}", e.getMessage());
        }
    }

    public boolean isMonitorEnabled() {
        var cfg = riskControlService.config();
        return cfg.getSensitiveMonitorEnabled() == null || cfg.getSensitiveMonitorEnabled() == 1;
    }

    public boolean isTransferEnabled() {
        var cfg = riskControlService.config();
        return cfg.getHumanTransferEnabled() != null && cfg.getHumanTransferEnabled() == 1
                && StringUtils.hasText(cfg.getHumanTransferDest());
    }

    /** 在客户 ASR 文本中匹配词库，不影响通话流程本身 */
    public SensitiveWordMatch match(String text) {
        if (!isMonitorEnabled() || !StringUtils.hasText(text) || entries.isEmpty()) {
            return null;
        }
        String normalized = normalize(text);
        if (normalized.isEmpty()) {
            return null;
        }
        for (WordEntry entry : entries) {
            if (containsNormalized(normalized, entry.normalized)) {
                SensitiveWordMatch m = new SensitiveWordMatch();
                m.setWord(entry.word);
                m.setWordType(entry.type == TYPE_VIOLATION ? "violation" : "sensitive");
                m.setWordTypeLabel(entry.type == TYPE_VIOLATION ? "违规词" : "敏感词");
                return m;
            }
        }
        return null;
    }

    private static boolean containsNormalized(String text, String keyword) {
        if (!StringUtils.hasText(keyword)) {
            return false;
        }
        if (keyword.length() <= 2) {
            return text.equals(keyword) || text.contains(keyword);
        }
        return text.contains(keyword);
    }

    private static String normalize(String text) {
        return text.trim()
                .replaceAll("[\\s，,。.!！?？~～、]+", "")
                .toLowerCase(Locale.ROOT);
    }

    private record WordEntry(String word, int type, String normalized) {
        WordEntry(String word, Integer type) {
            this(word, type != null && type == TYPE_VIOLATION ? TYPE_VIOLATION : TYPE_SENSITIVE, normalize(word));
        }
    }
}
