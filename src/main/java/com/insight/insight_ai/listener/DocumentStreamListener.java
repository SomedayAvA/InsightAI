package com.insight.insight_ai.listener;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.data.redis.connection.stream.ObjectRecord;
import org.springframework.data.redis.stream.StreamListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 进阶版异步消费者：支持多租户元数据解析与防毒药机制
 * @author someday
 */
@Component
public class DocumentStreamListener implements StreamListener<String, ObjectRecord<String, String>> {

    private final VectorStore vectorStore;
    // 1. 引入 JSON 解析工具
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DocumentStreamListener(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    @Override
    public void onMessage(ObjectRecord<String, String> message) {
        // 拿到 Redis 里的原始字符串（现在期望它是一个 JSON）
        String rawValue = message.getValue();
        System.out.println("🚀 [后台任务触发] 从 Redis 收到消息: " + rawValue);

        try {
            // 2. 解析 JSON
            JsonNode rootNode = objectMapper.readTree(rawValue);

            // 防御性校验：如果收到的 JSON 里没有 content，说明数据格式不对，直接跳过
            if (!rootNode.has("content")) {
                System.err.println("⚠️ [跳过] 收到无效格式的消息，缺少 content 字段");
                return;
            }

            // 提取内容和部门标签（如果没有传部门，默认给 public）
            String content = rootNode.get("content").asText();
            String department = rootNode.has("department") ? rootNode.get("department").asText() : "public";

            // 3. 核心隔离逻辑：把解析出来的部门标签，塞进 Document 的 Metadata 里！
            // 只有这里塞进去了，ChatController 里才能用 withFilterExpression 过滤出来。
            Map<String, Object> metadata = Map.of(
                    "source", "async-queue",
                    "department", department
            );

            Document document = new Document(content, metadata);

            System.out.println("⏳ 正在为部门 [" + department + "] 执行向量化入库...");
            vectorStore.add(List.of(document));

            System.out.println("✅ [后台任务完成] 文档已成功打上 [" + department + "] 标签并存入 PostgreSQL！");

        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // 4. 毒药消息防御：专门捕获 JSON 解析异常。
            // 就算队列里还卡着你之前发的“今天中午吃红烧肉”纯文本，这里也会优雅地把它丢弃，而不会导致线程死循环崩溃。
            System.err.println("🛡️ [安全拦截] 收到非 JSON 格式的脏数据，强行丢弃防止阻塞: " + rawValue);
        } catch (Exception e) {
            System.err.println("❌ [后台任务失败] 向量化异常: " + e.getMessage());
            e.printStackTrace();
        }
    }
}