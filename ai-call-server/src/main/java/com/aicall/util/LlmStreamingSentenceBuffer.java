package com.aicall.util;

import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 将大模型流式 token 增量切分为可播报的完整短句 */
public final class LlmStreamingSentenceBuffer {

    private static final Pattern SENTENCE_END = Pattern.compile(".*?[。！？；.!?;,，]");

    private final StringBuilder pending = new StringBuilder();
    private final int maxChars;
    private final int firstChunkMinChars;
    private boolean firstChunkEmitted;

    public LlmStreamingSentenceBuffer(int maxChars) {
        this(maxChars, maxChars);
    }

    public LlmStreamingSentenceBuffer(int maxChars, int firstChunkMinChars) {
        this.maxChars = Math.max(16, maxChars);
        this.firstChunkMinChars = Math.max(6, Math.min(this.maxChars, firstChunkMinChars));
    }

    public List<String> feed(String delta) {
        List<String> out = new ArrayList<>();
        if (!StringUtils.hasText(delta)) {
            return out;
        }
        pending.append(delta);
        drainCompleteSentences(out);
        return out;
    }

    public String flushRemainder() {
        String rest = pending.toString().trim();
        pending.setLength(0);
        if (!StringUtils.hasText(rest)) {
            return "";
        }
        return SpeakTextLimiter.limit(rest, maxChars);
    }

    private void drainCompleteSentences(List<String> out) {
        if (!firstChunkEmitted && pending.length() >= firstChunkMinChars) {
            String chunk = SpeakTextLimiter.limit(pending.toString(), maxChars);
            if (StringUtils.hasText(chunk)) {
                out.add(chunk);
                pending.delete(0, chunk.length());
                firstChunkEmitted = true;
                return;
            }
        }
        // 电话场景：凑够 maxChars 即出句，不等到句号，便于边生成边 TTS
        if (pending.length() >= maxChars) {
            String chunk = SpeakTextLimiter.limit(pending.toString(), maxChars);
            if (StringUtils.hasText(chunk)) {
                out.add(chunk);
                pending.delete(0, chunk.length());
                firstChunkEmitted = true;
                return;
            }
        }
        while (true) {
            Matcher m = SENTENCE_END.matcher(pending);
            if (!m.find()) {
                break;
            }
            String sentence = m.group().trim();
            pending.delete(0, m.end());
            if (!StringUtils.hasText(sentence)) {
                continue;
            }
            emitWithinLimit(out, sentence);
            firstChunkEmitted = true;
        }
        if (pending.length() > maxChars * 2) {
            String chunk = SpeakTextLimiter.limit(pending.toString(), maxChars);
            if (StringUtils.hasText(chunk)) {
                out.add(chunk);
                pending.delete(0, chunk.length());
                firstChunkEmitted = true;
            }
        }
    }

    private void emitWithinLimit(List<String> out, String sentence) {
        for (String part : SpeakTextLimiter.splitWithinLimit(sentence, maxChars)) {
            if (StringUtils.hasText(part)) {
                out.add(part);
            }
        }
    }
}
