package com.aicall.service.prerecord;

import com.aicall.common.PrerecordClipType;
import com.aicall.entity.PrerecordAudioClip;
import com.aicall.entity.PrerecordFaq;
import com.aicall.mapper.PrerecordAudioClipMapper;
import com.aicall.mapper.PrerecordFaqMapper;
import com.aicall.util.TtsAudioCacheKeyUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 金融获客高频问题种子库 + 全局缓冲/收尾/转顾问话术。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PrerecordFaqSeedService {

    private final PrerecordFaqMapper prerecordFaqMapper;
    private final PrerecordAudioClipMapper prerecordAudioClipMapper;
    private final PrerecordClipMemoryService prerecordClipMemoryService;

    @PostConstruct
    public void seedIfEmpty() {
        Long count = prerecordFaqMapper.selectCount(null);
        if (count != null && count > 0) {
            return;
        }
        seedDefaults();
        prerecordClipMemoryService.reload();
        log.info("[预录外呼] 已初始化默认高频问题库");
    }

    public void seedDefaults() {
        insertFaq("利率多少", "利率", "利率,利息,年化,几个点,费率高", "high_freq",
                "咱们综合年化最低4.2%，根据个人资质略有浮动，没有前置费用。");
        insertFaq("额度怎么算", "额度", "额度,多少钱,多少万,能贷多少,批多少", "high_freq",
                "额度根据您的收入和征信情况核定，一般五万到五十万，具体可以帮您测算。");
        insertFaq("上不上征信", "征信", "征信,查征信,上征信,信用记录", "high_freq",
                "会查征信，正常按时还款对征信是正面记录，逾期才会产生不良影响。");
        insertFaq("有没有手续费", "手续费", "手续费,杂费,前期费用,收费,服务费", "high_freq",
                "没有前置手续费和杂费，具体以签约合同为准。");
        insertFaq("多久能放款", "放款时效", "多久,放款,到账,几天,什么时候到账", "high_freq",
                "资料齐全的话，一般一到三个工作日可以到账，最快当天。");
        insertFaq("需要什么材料", "办理流程", "材料,资料,需要什么,怎么办理,怎么办", "high_freq",
                "身份证、银行卡就行，有公积金或社保通过率更高，线上提交很方便。");
        insertFaq("公积金能不能提额", "额度", "公积金,提额,社保", "high_freq",
                "有公积金或社保可以提额，具体额度顾问可以帮您测算。");
        insertFaq("还款方式", "办理流程", "还款,月供,等额,先息后本", "high_freq",
                "支持等额本息和先息后本，您可以根据资金周转选合适的方式。");

        insertGlobalClip(PrerecordClipType.BUFFER, 1, "我跟您说下这块。");
        insertGlobalClip(PrerecordClipType.BUFFER, 2, "您问的这个我清楚。");
        insertGlobalClip(PrerecordClipType.BUFFER, 3, "简单给您讲一下。");
        insertGlobalClip(PrerecordClipType.CLOSING, 1, "除了这个，您还有其他想了解的吗？");
        insertGlobalClip(PrerecordClipType.CLOSING, 2, "您看还有什么要咨询的？");
        insertGlobalClip(PrerecordClipType.TRANSFER, 1,
                "您这个情况比较特殊，细节我这边没办法给您准确答复。"
                        + "我马上帮您对接专业信贷顾问，一对一给您测算讲解，您稍等片刻。");
        insertGlobalClip(PrerecordClipType.REFUSE, 1,
                "好的，理解您的想法，那就不多打扰了，祝您生活愉快。");
        insertGlobalClip(PrerecordClipType.END, 1, "好的，那不打扰您了，祝您生活愉快，再见。");
        prerecordClipMemoryService.reload();
    }

    private void insertFaq(String display, String category, String keywords, String tier, String answer) {
        String norm = TtsAudioCacheKeyUtil.normalizeUserQuestion(display);
        PrerecordFaq faq = new PrerecordFaq();
        faq.setQuestionDisplay(display);
        faq.setQuestionNorm(norm);
        faq.setCategory(category);
        faq.setKeywords(keywords);
        faq.setAnswerText(answer);
        faq.setTier(tier);
        faq.setHitCount(0);
        faq.setMatchCount(0);
        faq.setTransferCount(0);
        faq.setEnabled(1);
        faq.setCreateTime(LocalDateTime.now());
        faq.setUpdateTime(LocalDateTime.now());
        prerecordFaqMapper.insert(faq);

        PrerecordAudioClip answerClip = new PrerecordAudioClip();
        answerClip.setFaqId(faq.getId());
        answerClip.setClipType(PrerecordClipType.ANSWER);
        answerClip.setTextContent(answer);
        answerClip.setVariantNo(1);
        answerClip.setEnabled(1);
        answerClip.setCreateTime(LocalDateTime.now());
        answerClip.setUpdateTime(LocalDateTime.now());
        prerecordAudioClipMapper.insert(answerClip);
    }

    private void insertGlobalClip(String type, int variant, String text) {
        if (prerecordAudioClipMapper.selectCount(new LambdaQueryWrapper<PrerecordAudioClip>()
                .isNull(PrerecordAudioClip::getFaqId)
                .eq(PrerecordAudioClip::getClipType, type)
                .eq(PrerecordAudioClip::getVariantNo, variant)) > 0) {
            return;
        }
        PrerecordAudioClip clip = new PrerecordAudioClip();
        clip.setFaqId(null);
        clip.setClipType(type);
        clip.setTextContent(text);
        clip.setVariantNo(variant);
        clip.setEnabled(1);
        clip.setCreateTime(LocalDateTime.now());
        clip.setUpdateTime(LocalDateTime.now());
        prerecordAudioClipMapper.insert(clip);
    }

    public List<PrerecordFaq> listEnabledHighFreq() {
        return prerecordFaqMapper.selectList(new LambdaQueryWrapper<PrerecordFaq>()
                .eq(PrerecordFaq::getEnabled, 1)
                .eq(PrerecordFaq::getTier, "high_freq")
                .orderByDesc(PrerecordFaq::getHitCount)
                .orderByAsc(PrerecordFaq::getId));
    }
}
