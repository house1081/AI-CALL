package com.aicall.service.prerecord;

import com.aicall.dto.PrerecordMiningResultDto;
import com.aicall.entity.CallRecord;
import com.aicall.entity.PrerecordFaq;
import com.aicall.mapper.CallRecordMapper;
import com.aicall.mapper.PrerecordFaqMapper;
import com.aicall.util.TtsAudioCacheKeyUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class PrerecordMiningService {

    private static final int HIGH_FREQ_THRESHOLD = 3;

    private final CallRecordMapper callRecordMapper;
    private final PrerecordFaqMapper prerecordFaqMapper;

    public PrerecordMiningResultDto mineFromHistory(int days, int recordLimit) {
        PrerecordMiningResultDto dto = new PrerecordMiningResultDto();
        LocalDateTime since = LocalDateTime.now().minusDays(Math.max(1, days));
        List<CallRecord> records = callRecordMapper.selectList(new LambdaQueryWrapper<CallRecord>()
                .isNotNull(CallRecord::getDialogText)
                .ge(CallRecord::getCallTime, since)
                .orderByDesc(CallRecord::getId)
                .last("LIMIT " + Math.max(10, Math.min(recordLimit, 5000))));
        dto.setScannedRecords(records.size());

        Map<String, MutableAgg> agg = new HashMap<>();
        for (CallRecord record : records) {
            for (String line : record.getDialogText().split("\n")) {
                if (!line.startsWith("【客户】")) {
                    continue;
                }
                String utterance = line.substring("【客户】".length()).trim();
                if (!isValidQuestion(utterance)) {
                    continue;
                }
                dto.setExtractedUtterances(dto.getExtractedUtterances() + 1);
                String norm = TtsAudioCacheKeyUtil.normalizeUserQuestion(utterance);
                MutableAgg item = agg.computeIfAbsent(norm, k -> new MutableAgg(utterance));
                item.count++;
            }
        }

        for (Map.Entry<String, MutableAgg> e : agg.entrySet()) {
            String norm = e.getKey();
            MutableAgg item = e.getValue();
            String tier = item.count >= HIGH_FREQ_THRESHOLD ? "high_freq" : "cold";
            String category = classify(item.display);

            PrerecordFaq existing = prerecordFaqMapper.selectOne(new LambdaQueryWrapper<PrerecordFaq>()
                    .eq(PrerecordFaq::getQuestionNorm, norm)
                    .last("LIMIT 1"));
            if (existing == null) {
                PrerecordFaq faq = new PrerecordFaq();
                faq.setQuestionDisplay(item.display);
                faq.setQuestionNorm(norm);
                faq.setCategory(category);
                faq.setKeywords(extractKeywords(item.display));
                faq.setAnswerText(null);
                faq.setTier(tier);
                faq.setHitCount(item.count);
                faq.setMatchCount(0);
                faq.setTransferCount(0);
                faq.setEnabled("high_freq".equals(tier) ? 1 : 0);
                faq.setCreateTime(LocalDateTime.now());
                faq.setUpdateTime(LocalDateTime.now());
                prerecordFaqMapper.insert(faq);
                dto.setNewFaqs(dto.getNewFaqs() + 1);
            } else {
                PrerecordFaq upd = new PrerecordFaq();
                upd.setId(existing.getId());
                upd.setHitCount((existing.getHitCount() != null ? existing.getHitCount() : 0) + item.count);
                if (upd.getHitCount() >= HIGH_FREQ_THRESHOLD) {
                    upd.setTier("high_freq");
                    upd.setEnabled(1);
                }
                upd.setCategory(category);
                prerecordFaqMapper.updateById(upd);
                dto.setUpdatedFaqs(dto.getUpdatedFaqs() + 1);
            }
        }

        dto.setHighFreqCount(prerecordFaqMapper.selectCount(new LambdaQueryWrapper<PrerecordFaq>()
                .eq(PrerecordFaq::getTier, "high_freq")).intValue());
        dto.setColdCount(prerecordFaqMapper.selectCount(new LambdaQueryWrapper<PrerecordFaq>()
                .eq(PrerecordFaq::getTier, "cold")).intValue());
        log.info("[预录挖掘] 完成 scanned={} utterances={} new={} updated={}",
                dto.getScannedRecords(), dto.getExtractedUtterances(), dto.getNewFaqs(), dto.getUpdatedFaqs());
        return dto;
    }

    private boolean isValidQuestion(String text) {
        if (!StringUtils.hasText(text) || text.length() < 3) {
            return false;
        }
        String n = TtsAudioCacheKeyUtil.normalizeUserQuestion(text);
        if (n.length() < 2) {
            return false;
        }
        return !n.equals("嗯") && !n.equals("啊") && !n.equals("喂") && !n.equals("不需要")
                && !n.equals("不用") && !n.equals("挂了");
    }

    private String classify(String text) {
        String t = text.toLowerCase(Locale.ROOT);
        if (t.contains("利率") || t.contains("利息") || t.contains("年化")) {
            return "利率";
        }
        if (t.contains("额度") || t.contains("多少万") || t.contains("多少钱")) {
            return "额度";
        }
        if (t.contains("征信")) {
            return "征信";
        }
        if (t.contains("手续费") || t.contains("收费") || t.contains("杂费")) {
            return "手续费";
        }
        if (t.contains("放款") || t.contains("到账") || t.contains("多久")) {
            return "放款时效";
        }
        if (t.contains("材料") || t.contains("办理") || t.contains("流程") || t.contains("还款")) {
            return "办理流程";
        }
        return "其他";
    }

    private String extractKeywords(String text) {
        String[] kws = {"利率", "利息", "额度", "征信", "手续费", "放款", "到账", "材料", "办理", "还款", "公积金"};
        StringBuilder sb = new StringBuilder();
        for (String kw : kws) {
            if (text.contains(kw)) {
                if (sb.length() > 0) {
                    sb.append(',');
                }
                sb.append(kw);
            }
        }
        return sb.length() > 0 ? sb.toString() : text.length() > 12 ? text.substring(0, 12) : text;
    }

    private static class MutableAgg {
        final String display;
        int count;

        MutableAgg(String display) {
            this.display = display;
        }
    }
}
