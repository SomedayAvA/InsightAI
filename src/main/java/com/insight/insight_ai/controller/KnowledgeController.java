package com.insight.insight_ai.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.data.redis.connection.stream.ObjectRecord;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * @author someday
 */
@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {

    private final VectorStore vectorStore;
    private final StringRedisTemplate redisTemplate;
    // 用于处理 JSON 打包
    private final ObjectMapper objectMapper = new ObjectMapper();

    public KnowledgeController(VectorStore vectorStore, StringRedisTemplate redisTemplate) {
        this.vectorStore = vectorStore;
        this.redisTemplate = redisTemplate;
    }

    /**
     * 同步录入接口 (带权限标签版)
     */
    @PostMapping("/ingest")
    public String ingestText(@RequestBody String content,
                             @RequestParam(defaultValue = "public") String department) {
        //  手动指定 Metadata
        Document document = new Document(content, Map.of("department", department));
        vectorStore.add(List.of(document));
        return "知识同步录入成功！所属部门：" + department;
    }

    /**
     * 企业级异步录入接口 (带身份隔离打标版)
     */
    @PostMapping("/async-ingest")
    public String asyncIngest(@RequestBody String content,
                              @RequestParam(defaultValue = "public") String department) {
        try {
            // 1. 将“内容”和“部门”封装在一起
            Map<String, String> payload = Map.of(
                    "content", content,
                    "department", department
            );

            // 2. 转换成 JSON 字符串，确保后台 Listener 能解析出来
            String jsonMessage = objectMapper.writeValueAsString(payload);

            // 3. 投递到 Redis Stream
            ObjectRecord<String, String> record = StreamRecords.newRecord()
                    .ofObject(jsonMessage)
                    .withStreamKey("insight:document:stream");

            redisTemplate.opsForStream().add(record);

            System.out.println("📤 [Knowledge] 已投递带标签的消息: " + jsonMessage);
            return "文档已接收！归属部门: " + department;

        } catch (JsonProcessingException e) {
            return "消息打包失败: " + e.getMessage();
        }
    }
}