package com.aicall.service;

import com.aicall.dto.DialogRagHitDto;

import java.util.List;

/** 对话训练向量索引（本地热加载 / 可扩展 Milvus） */
public interface DialogRagVectorStore {

    void upsert(int qaId, int kbId, String question, String standardAnswer, float[] embedding, int dataType, double weight);

    void remove(int qaId);

    List<DialogRagHitDto> search(float[] queryEmbedding, int kbId, int topK);

    int size();

    int sizeByKb(int kbId);

    void clear();
}
