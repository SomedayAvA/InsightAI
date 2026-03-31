package com.insight.insight_ai.listener;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.data.redis.connection.stream.ObjectRecord;
import org.springframework.data.redis.stream.StreamListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 后台异步消费者：负责监听 Redis，执行耗时的向量化入库
 */
@Component
public class DocumentStreamListener implements StreamListener<String, ObjectRecord<String, String>> {

    private final VectorStore vectorStore;

    public DocumentStreamListener(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    @Override
    public void onMessage(ObjectRecord<String, String> message) {
        System.out.println("🚀 [后台任务触发] 从 Redis 队列收到新文档，开始向量化处理...");

        // 1. 从消息中提取长文本
        String documentContent = message.getValue();

        try {
            // 2. 模拟耗时的切分和向量化过程（企业级通常会在这里用 TokenTextSplitter 切块）
            Document document = new Document(documentContent, Map.of("source", "async-queue"));

            // 3. 存入 PG 向量数据库（这里底层会发起 HTTP 请求调用大模型，极其耗时）
            vectorStore.add(List.of(document));

            System.out.println("✅ [后台任务完成] 文档已成功向量化并存入 PostgreSQL！");
        } catch (Exception e) {
            System.err.println("❌ [后台任务失败] 向量化异常: " + e.getMessage());
            // 在真实的生产环境中，这里应该把失败的消息放进死信队列 (DLQ) 方便补偿
        }
    }
}