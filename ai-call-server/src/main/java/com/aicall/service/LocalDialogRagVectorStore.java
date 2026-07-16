package com.aicall.service;

import com.aicall.common.DialogTrainingDataType;
import com.aicall.dto.DialogRagHitDto;
import com.aicall.util.VectorCosineUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/** 内存向量库：按知识库 kbId 隔离索引 */
@Slf4j
@Component
public class LocalDialogRagVectorStore implements DialogRagVectorStore {

    private volatile ConcurrentHashMap<Integer, Entry> indexRef = new ConcurrentHashMap<>();

    @Override
    public void upsert(int qaId, int kbId, String question, String standardAnswer, float[] embedding,
                       int dataType, double weight) {
        if (embedding == null || embedding.length == 0) {
            indexRef.remove(qaId);
            return;
        }
        indexRef.put(qaId, new Entry(qaId, kbId, question, standardAnswer, embedding, dataType, weight));
    }

    @Override
    public void remove(int qaId) {
        indexRef.remove(qaId);
    }

    @Override
    public List<DialogRagHitDto> search(float[] queryEmbedding, int kbId, int topK) {
        if (queryEmbedding == null || queryEmbedding.length == 0) {
            return List.of();
        }
        ConcurrentHashMap<Integer, Entry> snapshot = indexRef;
        if (snapshot.isEmpty()) {
            return List.of();
        }
        List<DialogRagHitDto> scored = new ArrayList<>();
        for (Entry e : snapshot.values()) {
            if (e.kbId != kbId) {
                continue;
            }
            double raw = VectorCosineUtil.cosine(queryEmbedding, e.embedding);
            if (raw <= 0.01) {
                continue;
            }
            DialogRagHitDto hit = new DialogRagHitDto();
            hit.setQaId(e.qaId);
            hit.setQuestion(e.question);
            hit.setStandardAnswer(e.standardAnswer);
            hit.setDataType(e.dataType);
            hit.setDataTypeLabel(DialogTrainingDataType.label(e.dataType));
            hit.setRawScore(raw);
            hit.setWeightedScore(raw * e.weight);
            scored.add(hit);
        }
        scored.sort(Comparator.comparingDouble(DialogRagHitDto::getWeightedScore).reversed());
        if (scored.size() <= topK) {
            return scored;
        }
        return new ArrayList<>(scored.subList(0, topK));
    }

    @Override
    public int size() {
        return indexRef.size();
    }

    @Override
    public int sizeByKb(int kbId) {
        int n = 0;
        for (Entry e : indexRef.values()) {
            if (e.kbId == kbId) {
                n++;
            }
        }
        return n;
    }

    @Override
    public void clear() {
        indexRef = new ConcurrentHashMap<>();
    }

    private record Entry(int qaId, int kbId, String question, String standardAnswer,
                         float[] embedding, int dataType, double weight) {}
}
