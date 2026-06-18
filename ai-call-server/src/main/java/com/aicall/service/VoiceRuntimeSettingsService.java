package com.aicall.service;

import com.aicall.common.BizException;
import com.aicall.common.CosyVoiceModelRules;
import com.aicall.common.CosyVoiceSystemVoiceCatalog;
import com.aicall.common.CosyVoiceVoiceMode;
import com.aicall.common.OutboundDialogMode;
import com.aicall.common.SilenceProfile;
import com.aicall.config.AiVoiceProperties;
import com.aicall.dto.CosyVoiceSystemVoiceOptionDto;
import com.aicall.dto.VoiceRuntimeConfigDto;
import com.aicall.entity.VoiceRuntimeConfig;
import com.aicall.entity.CallTask;
import com.aicall.mapper.CallTaskMapper;
import com.aicall.mapper.VoiceRuntimeConfigMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 外呼语音运行时配置（分段 ASR → LLM → CosyVoice TTS；后台可改，每通外呼从库读取）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VoiceRuntimeSettingsService {

    private static final int CONFIG_ID = 1;

    private final VoiceRuntimeConfigMapper voiceRuntimeConfigMapper;
    private final CallTaskMapper callTaskMapper;
    private final AiVoiceProperties aiVoiceProperties;
    private final DashScopeApiKeyResolver dashScopeApiKeyResolver;
    private final ObjectProvider<OpeningVoiceCacheService> openingVoiceCacheProvider;
    private final ObjectProvider<EndingVoiceCacheService> endingVoiceCacheProvider;

    private volatile VoiceRuntimeConfig cachedRow;

    private final ConcurrentHashMap<String, SilenceProfile.Params> callSilenceProfiles = new ConcurrentHashMap<>();

    public String resolveSilenceProfile(Integer taskId) {
        if (taskId != null) {
            CallTask task = callTaskMapper.selectById(taskId);
            if (task != null && SilenceProfile.isValid(task.getSilenceProfile())) {
                return SilenceProfile.normalize(task.getSilenceProfile());
            }
        }
        VoiceRuntimeConfig row = loadRow();
        if (row != null && SilenceProfile.isValid(row.getSilenceProfile())) {
            return SilenceProfile.normalize(row.getSilenceProfile());
        }
        return SilenceProfile.normalize(aiVoiceProperties.getSilenceProfile());
    }

    public SilenceProfile.Params resolveSilenceParams(Integer taskId) {
        return SilenceProfile.resolve(resolveSilenceProfile(taskId));
    }

    public void bindCallSilenceProfile(String fsUuid, Integer taskId) {
        if (!StringUtils.hasText(fsUuid)) {
            return;
        }
        SilenceProfile.Params params = resolveSilenceParams(taskId);
        callSilenceProfiles.put(fsUuid.trim(), params);
        log.info("[语音配置] 句末档位 uuid={} profile={} silenceMs={} vadThreshold={}",
                fsUuid.trim(), params.profile(), params.userSilenceMs(), params.vadThreshold());
    }

    public void unbindCallSilenceProfile(String fsUuid) {
        if (StringUtils.hasText(fsUuid)) {
            callSilenceProfiles.remove(fsUuid.trim());
        }
    }

    public SilenceProfile.Params silenceParamsForCall(String fsUuid) {
        if (!StringUtils.hasText(fsUuid)) {
            return SilenceProfile.resolve(aiVoiceProperties.getSilenceProfile());
        }
        SilenceProfile.Params cached = callSilenceProfiles.get(fsUuid.trim());
        return cached != null ? cached : SilenceProfile.resolve(aiVoiceProperties.getSilenceProfile());
    }

    public int resolveUserSilenceMs(String fsUuid) {
        return silenceParamsForCall(fsUuid).userSilenceMs();
    }

    public double resolveVadThreshold(String fsUuid) {
        return silenceParamsForCall(fsUuid).vadThreshold();
    }

    public int resolveAsrVadSilenceMs(String fsUuid) {
        return resolveUserSilenceMs(fsUuid);
    }

    public String getCosyvoiceCloneVoiceId() {
        VoiceRuntimeConfig row = loadRow();
        if (row != null && StringUtils.hasText(row.getCosyvoiceCloneVoiceId())) {
            return row.getCosyvoiceCloneVoiceId().trim();
        }
        return StringUtils.hasText(aiVoiceProperties.getTtsCloneVoiceId())
                ? aiVoiceProperties.getTtsCloneVoiceId().trim()
                : "";
    }

    public String getTtsVoiceMode() {
        VoiceRuntimeConfig row = loadRow();
        if (row != null && StringUtils.hasText(row.getTtsVoiceMode())) {
            return CosyVoiceVoiceMode.normalize(row.getTtsVoiceMode());
        }
        return CosyVoiceVoiceMode.CLONE;
    }

    public boolean isSystemVoiceMode() {
        return CosyVoiceVoiceMode.isSystem(getTtsVoiceMode());
    }

    public String getCosyvoiceSystemVoice() {
        VoiceRuntimeConfig row = loadRow();
        if (row != null && StringUtils.hasText(row.getCosyvoiceSystemVoice())) {
            return CosyVoiceSystemVoiceCatalog.normalizeVoiceId(row.getCosyvoiceSystemVoice());
        }
        if (StringUtils.hasText(aiVoiceProperties.getTtsVoice())) {
            return CosyVoiceSystemVoiceCatalog.normalizeVoiceId(aiVoiceProperties.getTtsVoice());
        }
        return CosyVoiceSystemVoiceCatalog.defaultVoiceId();
    }

    /** 外呼 TTS 实际使用的 voice 参数 */
    public String getEffectiveTtsVoice() {
        if (isSystemVoiceMode()) {
            return getCosyvoiceSystemVoice();
        }
        return getCosyvoiceCloneVoiceId();
    }

    /** 外呼 TTS 实际使用的 CosyVoice 模型 */
    public String getEffectiveTtsModel() {
        if (isSystemVoiceMode()) {
            return CosyVoiceSystemVoiceCatalog.resolveModel(getCosyvoiceSystemVoice());
        }
        return CosyVoiceModelRules.resolveCloneTtsModel(
                getCosyvoiceCloneVoiceId(), aiVoiceProperties.getTtsModel());
    }

    public List<CosyVoiceSystemVoiceOptionDto> listSystemVoiceOptions() {
        return CosyVoiceSystemVoiceCatalog.listOptions().stream().map(o -> {
            CosyVoiceSystemVoiceOptionDto dto = new CosyVoiceSystemVoiceOptionDto();
            dto.setVoiceId(o.voiceId());
            dto.setLabel(o.label());
            dto.setModel(o.model());
            return dto;
        }).toList();
    }

    public boolean isPlayOpeningOnAnswer() {
        VoiceRuntimeConfig row = loadRow();
        if (row != null && row.getPlayOpeningOnAnswer() != null) {
            return row.getPlayOpeningOnAnswer() == 1;
        }
        return aiVoiceProperties.isPlayOpeningOnAnswer();
    }

    public String getOutboundDialogMode() {
        VoiceRuntimeConfig row = loadRow();
        if (row != null && StringUtils.hasText(row.getOutboundDialogMode())) {
            return OutboundDialogMode.normalize(row.getOutboundDialogMode());
        }
        return OutboundDialogMode.AI_REALTIME;
    }

    public boolean isSmartPrerecordMode() {
        return OutboundDialogMode.isSmartPrerecord(getOutboundDialogMode());
    }

    public void logEffectiveVoiceProfile(String context) {
        logEffectiveVoiceProfile(context, null);
    }

    public void logEffectiveVoiceProfile(String context, Integer taskId) {
        String ctx = StringUtils.hasText(context) ? context : "外呼";
        SilenceProfile.Params sp = resolveSilenceParams(taskId);
        log.info("[语音配置] {} 生效 taskId={} voiceMode={} cosyVoice={} ttsModel={} silenceProfile={} silenceMs={} playOpening={}",
                ctx, taskId, getTtsVoiceMode(), maskVoiceId(getEffectiveTtsVoice()), getEffectiveTtsModel(),
                sp.profile(), sp.userSilenceMs(), isPlayOpeningOnAnswer());
    }

    public VoiceRuntimeConfigDto getForAdmin() {
        VoiceRuntimeConfigDto dto = new VoiceRuntimeConfigDto();
        SilenceProfile.Params sp = resolveSilenceParams(null);
        dto.setSilenceProfile(sp.profile());
        dto.setEffectiveUserSilenceMs(sp.userSilenceMs());
        dto.setEffectiveVadThreshold(sp.vadThreshold());
        dto.setCosyvoiceCloneVoiceId(getCosyvoiceCloneVoiceId());
        dto.setTtsVoiceMode(getTtsVoiceMode());
        dto.setCosyvoiceSystemVoice(getCosyvoiceSystemVoice());
        dto.setEffectiveTtsVoice(getEffectiveTtsVoice());
        dto.setEffectiveTtsModel(getEffectiveTtsModel());
        dto.setSystemVoiceOptions(listSystemVoiceOptions());
        dto.setDefaultCosyvoiceCloneVoiceId(
                StringUtils.hasText(aiVoiceProperties.getTtsCloneVoiceId())
                        ? aiVoiceProperties.getTtsCloneVoiceId().trim()
                        : "");
        dto.setPlayOpeningOnAnswer(isPlayOpeningOnAnswer());
        dto.setOutboundDialogMode(getOutboundDialogMode());
        dto.setOutboundDialogModeLabel(isSmartPrerecordMode() ? "智能预录外呼" : "AI实时对话");
        dto.setDashScopeConfigured(isDashScopeConfigured());
        dto.setCosyvoiceTtsModel(getEffectiveTtsModel());
        dto.setUserSilenceBeforeResponseMs(aiVoiceProperties.getUserSilenceBeforeResponseMs());
        dto.setTurnBasedPlaybackAsrTailMs(aiVoiceProperties.getTurnBasedPlaybackAsrTailMs());
        dto.setPlaybackBargeInEnergyThreshold(aiVoiceProperties.getPlaybackBargeInEnergyThreshold());
        return dto;
    }

    public void validateVoiceReady(Integer taskId) {
        if (isSystemVoiceMode()) {
            if (!StringUtils.hasText(getCosyvoiceSystemVoice())) {
                throw new BizException("请先在总后台选择 CosyVoice 系统音色");
            }
            return;
        }
        if (!StringUtils.hasText(getCosyvoiceCloneVoiceId())) {
            throw new BizException("请先在总后台配置 CosyVoice 复刻 voice_id");
        }
    }

    public void saveFromAdmin(VoiceRuntimeConfigDto req) {
        if (req == null) {
            throw new BizException("配置不能为空");
        }
        String silenceProfile = SilenceProfile.normalize(
                StringUtils.hasText(req.getSilenceProfile())
                        ? req.getSilenceProfile().trim()
                        : resolveSilenceProfile(null));
        String voiceMode = CosyVoiceVoiceMode.normalize(req.getTtsVoiceMode());
        String cosyVoice = StringUtils.hasText(req.getCosyvoiceCloneVoiceId())
                ? req.getCosyvoiceCloneVoiceId().trim()
                : getCosyvoiceCloneVoiceId();
        String systemVoice = CosyVoiceSystemVoiceCatalog.normalizeVoiceId(
                StringUtils.hasText(req.getCosyvoiceSystemVoice())
                        ? req.getCosyvoiceSystemVoice().trim()
                        : getCosyvoiceSystemVoice());
        if (CosyVoiceVoiceMode.isSystem(voiceMode)) {
            if (!StringUtils.hasText(systemVoice)) {
                throw new BizException("请选择系统预置音色");
            }
        } else if (!StringUtils.hasText(cosyVoice)) {
            throw new BizException("需配置 CosyVoice 复刻 voice_id");
        }
        boolean playOpening = req.getPlayOpeningOnAnswer() != null
                ? Boolean.TRUE.equals(req.getPlayOpeningOnAnswer())
                : isPlayOpeningOnAnswer();
        int playOpeningFlag = playOpening ? 1 : 0;
        String outboundMode = OutboundDialogMode.normalize(req.getOutboundDialogMode());

        VoiceRuntimeConfig row = loadRow();
        String oldEffectiveVoice = getEffectiveTtsVoice();
        String oldEffectiveModel = getEffectiveTtsModel();

        if (row == null) {
            row = new VoiceRuntimeConfig();
            row.setId(CONFIG_ID);
            row.setSilenceProfile(silenceProfile);
            row.setCosyvoiceCloneVoiceId(cosyVoice);
            row.setTtsVoiceMode(voiceMode);
            row.setCosyvoiceSystemVoice(systemVoice);
            row.setPlayOpeningOnAnswer(playOpeningFlag);
            row.setOutboundDialogMode(outboundMode);
            row.setUpdateTime(LocalDateTime.now());
            voiceRuntimeConfigMapper.insert(row);
        } else {
            row.setSilenceProfile(silenceProfile);
            row.setCosyvoiceCloneVoiceId(cosyVoice);
            row.setTtsVoiceMode(voiceMode);
            row.setCosyvoiceSystemVoice(systemVoice);
            row.setPlayOpeningOnAnswer(playOpeningFlag);
            row.setOutboundDialogMode(outboundMode);
            row.setUpdateTime(LocalDateTime.now());
            voiceRuntimeConfigMapper.updateById(row);
        }
        log.info("[语音配置] 已保存（下一通外呼生效，无需重启）silenceProfile={} voiceMode={} outboundMode={} cloneVoice={} systemVoice={} playOpening={}",
                silenceProfile, voiceMode, outboundMode, maskVoiceId(cosyVoice), systemVoice, playOpeningFlag);

        String newEffectiveVoice = getEffectiveTtsVoice();
        String newEffectiveModel = getEffectiveTtsModel();
        if (!newEffectiveVoice.equals(oldEffectiveVoice) || !newEffectiveModel.equals(oldEffectiveModel)) {
            log.info("[语音配置] CosyVoice 音色已变更，请在「固定话术预生成」手动同步（避免自动预合成触发 428）");
        }
        cachedRow = voiceRuntimeConfigMapper.selectById(CONFIG_ID);
    }

    private VoiceRuntimeConfig loadRow() {
        VoiceRuntimeConfig row = cachedRow;
        if (row != null) {
            return row;
        }
        row = voiceRuntimeConfigMapper.selectById(CONFIG_ID);
        cachedRow = row;
        return row;
    }

    private boolean isDashScopeConfigured() {
        return dashScopeApiKeyResolver.isConfigured();
    }

    private static String maskVoiceId(String id) {
        if (!StringUtils.hasText(id)) {
            return "(未配置)";
        }
        if (id.length() <= 12) {
            return id;
        }
        return id.substring(0, 8) + "…" + id.substring(id.length() - 4);
    }
}
