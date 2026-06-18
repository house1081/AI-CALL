package com.aicall.service.prerecord;

import com.aicall.common.PrerecordClipType;
import com.aicall.entity.PrerecordAudioClip;
import com.aicall.entity.PrerecordFaq;
import com.aicall.mapper.PrerecordAudioClipMapper;
import com.aicall.mapper.PrerecordFaqMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/** 预录片段内存索引，避免通话中反复查库 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PrerecordClipMemoryService {

    private final PrerecordAudioClipMapper prerecordAudioClipMapper;
    private final PrerecordFaqMapper prerecordFaqMapper;

    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>(Snapshot.EMPTY);

    @PostConstruct
    public void reload() {
        List<PrerecordAudioClip> clips = prerecordAudioClipMapper.selectList(
                new LambdaQueryWrapper<PrerecordAudioClip>().eq(PrerecordAudioClip::getEnabled, 1));
        Map<String, List<PrerecordAudioClip>> global = clips.stream()
                .filter(c -> c.getFaqId() == null)
                .collect(Collectors.groupingBy(PrerecordAudioClip::getClipType));
        Map<Long, List<PrerecordAudioClip>> answers = clips.stream()
                .filter(c -> c.getFaqId() != null && PrerecordClipType.ANSWER.equals(c.getClipType()))
                .collect(Collectors.groupingBy(PrerecordAudioClip::getFaqId));
        List<PrerecordFaq> faqs = prerecordFaqMapper.selectList(
                new LambdaQueryWrapper<PrerecordFaq>()
                        .eq(PrerecordFaq::getEnabled, 1)
                        .eq(PrerecordFaq::getTier, "high_freq")
                        .orderByDesc(PrerecordFaq::getHitCount));
        snapshot.set(new Snapshot(global, answers, faqs));
        log.info("[预录外呼] 内存索引已加载 faq={} clips={}", faqs.size(), clips.size());
    }

    public List<PrerecordFaq> listHighFreqFaqs() {
        return snapshot.get().highFreqFaqs;
    }

    public PrerecordAudioClip pickGlobal(String clipType) {
        List<PrerecordAudioClip> list = snapshot.get().globalByType.get(clipType);
        if (list == null || list.isEmpty()) {
            return null;
        }
        return list.get(ThreadLocalRandom.current().nextInt(list.size()));
    }

    public PrerecordAudioClip pickAnswer(Long faqId) {
        List<PrerecordAudioClip> list = snapshot.get().answersByFaq.get(faqId);
        if (list == null || list.isEmpty()) {
            return null;
        }
        return list.get(ThreadLocalRandom.current().nextInt(list.size()));
    }

    private record Snapshot(
            Map<String, List<PrerecordAudioClip>> globalByType,
            Map<Long, List<PrerecordAudioClip>> answersByFaq,
            List<PrerecordFaq> highFreqFaqs) {
        static final Snapshot EMPTY = new Snapshot(Map.of(), Map.of(), List.of());
    }
}
