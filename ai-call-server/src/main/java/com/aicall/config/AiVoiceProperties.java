package com.aicall.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "ai-voice")
public class AiVoiceProperties {
    private boolean enabled = true;
    private boolean playOpeningOnAnswer = true;
    private boolean autoPlayAfterOriginate = true;
    private int answerWatchSeconds = 45;
    /** 默认最大振铃次数（任务未配置时使用） */
    private int answerMaxRingCount = 10;
    /** 振铃计数周期（秒），约每周期计 1 次振铃 */
    private int answerRingCycleSec = 4;
    /** 摘机后等待 RTP 稳定的毫秒数（过大会拖慢开场白） */
    private int answerPlayDelayMs = 80;
    /** 接通后等待预合成开场白 wav 的最长时间（毫秒）；有缓存则立即播 */
    private int openingPrewarmWaitMs = 800;
    /** 监听摘机轮询间隔（毫秒） */
    private int answerWatchPollMs = 500;
    private String speakVoice = "zh|default";
    private String speakFallbackVoices = "en|flite,eng|callie";
    private int maxSpeakChars = 60;
    /** 外呼每轮 LLM max_tokens 上限 */
    private int dialogLlmMaxTokens = 80;
    /** 外呼 LLM temperature 覆盖（null=沿用模型配置） */
    private Double dialogLlmTemperature;
    /** 外呼 LLM top_p 覆盖 */
    private Double dialogLlmTopP = 1.0;

    /** sentence=整句识别；stream 由 asrStreamEnabled 控制 */
    private String asrMode = "sentence";

    /** auto | http | speak | cosyvoice（DashScope 仿真人声，推荐） */
    private String ttsMode = "cosyvoice";
    private String ttsHttpUrl = "";
    private String playbackBaseUrl = "";

    /** CosyVoice 音色；v3-plus/flash 用 longanyang 等；v2 用 longxiaochun_v2 */
    private String ttsVoice = "longanyang";
    private String ttsModel = "cosyvoice-v3-plus";
    /** v3.5-plus/flash 专用：百炼声音复刻或声音设计返回的 voice ID（无系统音色） */
    private String ttsCloneVoiceId = "";
    /** 语速倍率 0.5~2.0，推荐 0.95 */
    private double ttsSpeechRate = 0.95;
    /** 语调倍率 0.5~2.0，略高更自然 */
    private double ttsPitchRate = 1.02;
    private int ttsVolume = 80;
    /** CosyVoice 风格提示（空则不下发） */
    /** CosyVoice Instruct 情感指令（关闭可减轻 428；固定话术预合成始终不带 instruction） */
    private boolean ttsInstructionEnabled = false;
    private String ttsInstruction = "用自然亲切的客服语气，语速适中，像真人打电话。";
    /** 电话播报 wav 峰值归一化比例（0.5~1.0，越大越响） */
    private double playbackPeakRatio = 0.95;
    /** TTS 播完估算额外缓冲（毫秒） */
    private int playbackTailBufferMs = 50;
    private int playbackPostStopMs = 40;

    private String fsBuiltinSound = "";
    private boolean fsFetchViaEsl = true;
    private boolean fsPushWavViaEsl = false;
    private String fsHostOs = "linux";
    private String fsTempWavDir = "/tmp/aicall-voice";
    private String fsSharedWavDir = "";
    /** 用户语音 ASR 录音目录（FS uuid_execute record / uuid_record 落盘路径） */
    private String fsRecordDir = "";
    /** 接通后整通 uuid_record，挂断后复制到 call-record-publish-dir */
    private boolean callRecordEnabled = true;
    /** 相对项目根，对应 WebConfig /uploads/record/ */
    private String callRecordPublishDir = "./uploads/record/";
    private String broadcastLegs = "aleg";
    private int fsCurlWaitMs = 2000;
    private boolean preferHttpPlayback = false;
    /** park 外呼：优先 uuid_execute/broadcast，比 uuid_displace 更易让对端听到 */
    private boolean preferExecutePlayback = false;

    /** 关闭蜂鸣/内置音效/speak 机械音回退 */
    private boolean enableToneFallback = false;
    private boolean enableBuiltinSoundFallback = false;
    private boolean enableSpeakFallback = false;

    /** RTP 分片毫秒（20=电话常用小包，降低堆积卡顿） */
    private int rtpPacketizationMs = 20;
    /** 关闭 FS 侧冗余静音检测/舒适噪声 */
    private boolean disableComfortNoise = true;

    private boolean fsSocketEnabled = true;
    private String fsSocketHost = "0.0.0.0";
    private int fsSocketPort = 8888;

    private String asrHttpUrl = "";
    private int asrConnectTimeoutMs = 5000;
    private int asrReadTimeoutMs = 30000;
    /** 中文 zh-CN */
    private String asrLanguage = "zh-CN";
    /** 电话场景：phone_call（映射 DashScope paraformer-8k / HTTP model 参数） */
    private String asrModel = "phone_call";
    /** DashScope 实际模型名；电话 8k 推荐 paraformer-8k-v2 */
    private String asrDashscopeModel = "paraformer-8k-v2";
    /** 本机无公网 URL 时用 Paraformer WebSocket 实时识别（映射 paraformer-realtime-8k-v2） */
    private boolean asrParaformerRealtimeEnabled = true;
    private int asrRealtimeTimeoutSec = 30;
    /** 启动后 dummy 识别，吃掉 ASR 冷启动 */
    private boolean asrWarmOnStartup = true;
    /** 外呼任务开始前等待 RAG+ASR+TTS 预热 */
    private boolean outboundReadyGateEnabled = true;
    private long outboundReadyMaxWaitMs = 180_000L;
    private String asrAudioFormat = "pcm";
    /** VAD：连续静音达到该毫秒数才结束录音并送 ASR（与 user-silence-before-response-ms 对齐） */
    private boolean asrVadEnabled = true;
    private int asrVadSilenceMs = 800;
    private int asrVadMinSpeechMs = 300;
    private int asrVadPollMs = 40;
    private int asrVadEnergyThreshold = 220;
    /** ASR 送识别前 PCM 增益（电话小声场景，1.0=不放大） */
    private double asrInputGain = 1.8;
    /** ASR 数字/金额逆文本归一化（ITN），电话场景建议开启 */
    private boolean asrEnableItn = true;
    /** ASR 领域提示，帮助识别贷款/额度等行业词汇 */
    private String asrContextHint = "额度,万,利率,周转,征信,抵押,听不清";
    /** 单轮最长录音（毫秒），超时仍未句末静音则整段送 ASR */
    private int asrRecordMaxMs = 12000;
    /** 从全程录音切给 ASR 的最大音频秒数（过长会拖慢识别） */
    private int asrMaxSliceSec = 6;
    /** 旧逻辑：固定 2 秒切段（VAD 开启时默认关闭） */
    private int dialogRecordChunkSec = 2;
    private int dialogMaxCallSec = 300;
    private int dialogMaxRounds = 12;
    /** 播报后等待再收音（毫秒），避免 AI 尾音触发 VAD */
    private int dialogPostPlayWaitMs = 250;
    /** 分段模式播后尾音消回声（毫秒），与 dialogPostPlayWaitMs 取较大值生效 */
    private int turnBasedPlaybackAsrTailMs = 600;
    /** 句间停顿 100~200ms */
    private int sentencePauseMs = 150;
    /** 分句顺序播报：每句单独 TTS，句间留空白（金融外呼真人感） */
    private boolean ttsSentenceSequentialEnabled = true;
    /** 智能断句停顿：逗号/句号对应不同静音时长 */
    private boolean ttsSmartPauseEnabled = true;
    /** 逗号后停顿（毫秒） */
    private int ttsClausePauseMs = 250;
    /** 句号后停顿（毫秒） */
    private int ttsSentenceEndPauseMs = 450;
    /** 两句 TTS 之间额外换气空白（毫秒） */
    private int ttsInterSentenceGapMs = 400;
    /** 根据客户话术动态切换 instruction/语速 */
    private boolean ttsDynamicProsodyEnabled = true;
    /** FS 背景办公音参考音量 0~1（需在 FS 拨号方案配置，此处仅文档/日志） */
    private double backgroundAudioVolume = 0.22;
    private boolean dialogEnabled = true;
    /**
     * true=每轮客户话术后都调大模型（带历史上下文），规则只做槽位摘要/挂断，不替模型生成回复。
     * false=旧逻辑，槽位规则可跳过 LLM（易出现「说了80还说没听清」）。
     */
    private boolean dialogLlmPrimary = true;
    /** 外呼对话传给大模型的最少历史轮数下限（与模型配置 maxHistoryRounds 取较大值） */
    private int dialogMaxHistoryRounds = 10;
    /** 大模型调用放到独立线程池（dialog-llm），不占用 dialog-loop */
    private boolean dialogLlmAsync = false;
    /** 大模型 SSE 流式输出（与 TTS 是否边生成边播无关） */
    private boolean dialogLlmStream = true;
    /** true=LLM 流式过程中按句 TTS；false=等 LLM 全文后再 cosyvoice 播报 */
    /** false=每轮整段 TTS 一次，避免多句流式互相 stop 只听到半句 */
    private boolean dialogLlmStreamTts = false;
    /** 流式首句 TTS 触发字数（小于 max-speak-chars，更快开口） */
    private int streamTtsFirstChunkChars = 14;
    /** 独立线程池调用大模型最长等待（秒） */
    private int dialogLlmTimeoutSec = 55;
    /** 分段模式：客户长时间无有效 ASR 时主动 gentle 询问 */
    private boolean dialogSilenceProbeEnabled = true;
    private int dialogSilenceProbeMs = 6000;
    private int dialogSilenceProbeMinMs = 5000;
    private int dialogSilenceProbeMaxMs = 8000;
    private int dialogSilenceProbeMax = 3;
    /** 话术保存时预合成开场白 wav，接通后拷贝即播 */
    private boolean openingVoicePrecacheEnabled = true;
    /** 服务启动时是否自动预合成（建议关，避免启动即触发 428） */
    private boolean openingVoicePrecacheOnStartup = false;
    /** 预合成开场白本地目录 */
    private String openingVoiceCacheDir = "./uploads/tts/opening";
    /** 预合成结束语本地目录 */
    private String endingVoiceCacheDir = "./uploads/tts/ending";
    /** 对话话术 TTS 短语缓存目录 */
    private String ttsPhraseCacheDir = "./uploads/tts/phrases";
    /** 是否启用话术 TTS 磁盘缓存（相同回答+音色复用 wav） */
    private boolean ttsPhraseCacheEnabled = true;
    /** 话术缓存内存索引上限（磁盘文件不受限） */
    private int ttsPhraseCacheMaxEntries = 2048;
    /** 问答级音频缓存：相同用户问题+音色直接复用 wav，跳过 LLM/TTS */
    private boolean dialogReplyAudioCacheEnabled = true;
    private String dialogReplyAudioCacheDir = "./uploads/tts/reply-cache";
    private int dialogReplyAudioCacheMaxEntries = 1024;
    private int dialogReplyAudioCacheMinUserChars = 2;
    private int dialogReplyAudioCacheMaxUserChars = 80;
    /** CosyVoice 两次请求最小间隔（毫秒），缓解 428 */
    private int ttsMinIntervalMs = 1200;
    /** 触发 428 后全局冷却（毫秒）；0=关闭 */
    private int ttsRateLimitCooldownMs = 3000;
    /** 遇到 428 时最多重试次数（仅网络异常；限流不重试） */
    private int ttsRateLimitRetries = 0;
    /** 启动后延迟预合成开场白（毫秒），避免与首通外呼抢 TTS */
    private int openingVoicePrecacheDelayMs = 8000;
    /** 启动时是否预热常用短语 TTS（易与开场/结束语缓存叠加触发 428） */
    private boolean ttsPhraseWarmOnStartup = false;
    /** 流式 TTS：按句切分边合成边播 */
    private boolean ttsStreamEnabled = false;
    /** 流式 ASR：固定短段（不推荐；请用 asr-vad-enabled） */
    private boolean asrStreamEnabled = false;
    /** 启动时少打日志、不生成测试蜂鸣 */
    private boolean startupQuiet = true;

    /** 全局默认句末档位：stable=稳健 | fast=极速（任务/DB 可覆盖） */
    private String silenceProfile = "stable";

    /** 用户句末静默门槛（毫秒） */
    private int userSilenceBeforeResponseMs = 800;
    /** AI 播报期间本地 PCM 能量超过此阈值视为用户插嘴（8k RMS） */
    private int playbackBargeInEnergyThreshold = 320;
    /** 播报结束后对齐 ASR 基线，避免回声误打断 */
    private boolean syncAsrBaselineAfterPlayback = true;
    /** 是否开启播报期间用户插嘴检测 */
    private boolean bargeInEnabled = true;
    /** 分段轮次对话是否允许插嘴（全程录音混 TTS 时易误打断，默认关） */
    private boolean bargeInDuringTurnBased = false;
    /** 插嘴灵敏度（1~100，越大越不敏感） */
    private int bargeInThreshold = 7;
    /** 插嘴判定防抖：检测录音尾部毫秒数 */
    private int bargeInHoldMs = 150;
    /**
     * 播报开始后允许插嘴检测的最短等待（毫秒）。
     * 全程录音会混入 TTS 本端声音，过早检测会把 AI 自己的播报当成用户插嘴。
     */
    private int playbackBargeInGraceMs = 2000;
    /** 至少播完该比例后才允许插嘴（0.85=播完约 85%） */
    private double playbackBargeInMinRatio = 0.85;
    /** TTS 合成采样率（Hz） */
    private int ttsSampleRate = 16000;

    /** 插嘴检测能量阈值：bargeInThreshold>0 时按灵敏度换算，否则用 playbackBargeInEnergyThreshold */
    public int resolveBargeInEnergyThreshold() {
        if (bargeInThreshold > 0) {
            return Math.max(asrVadEnergyThreshold, bargeInThreshold * 45);
        }
        return Math.max(asrVadEnergyThreshold, playbackBargeInEnergyThreshold);
    }

    /** 插嘴检测尾部窗口毫秒 */
    public int resolveBargeInHoldMs() {
        return Math.max(80, bargeInHoldMs);
    }

    /** 分段模式播后实际等待毫秒（post + tail 取大） */
    public int resolveTurnBasedPlaybackTailMs() {
        return Math.max(dialogPostPlayWaitMs, turnBasedPlaybackAsrTailMs);
    }

    /** ASR/VAD 句末静音：本地 VAD 与 HTTP ASR 统一门槛 */
    public int resolveAsrVadSilenceMs() {
        return Math.max(asrVadSilenceMs, userSilenceBeforeResponseMs);
    }

    /** 分段：第 N 次静默追问阈值（5s → 6s → 8s） */
    public int resolveDialogSilenceProbeMs(int attempt) {
        return resolveProbeMs(attempt, dialogSilenceProbeMinMs, dialogSilenceProbeMs,
                dialogSilenceProbeMaxMs, dialogSilenceProbeMax);
    }

    private static int resolveProbeMs(int attempt, int minMs, int midMs, int maxMs, int maxAttempt) {
        int min = Math.max(3000, minMs);
        int mid = Math.max(min, midMs);
        int max = Math.max(mid, maxMs);
        int n = Math.max(1, attempt);
        if (n <= 1) {
            return min;
        }
        if (n >= Math.max(1, maxAttempt)) {
            return max;
        }
        return mid;
    }
}
