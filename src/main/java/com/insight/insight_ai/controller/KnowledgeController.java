package com.insight.insight_ai.controller;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.web.bind.annotation.*;
import org.springframework.data.redis.connection.stream.ObjectRecord;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {

    // 1. 统一把所有需要的依赖声明在最顶部
    private final VectorStore vectorStore;
    private final StringRedisTemplate redisTemplate;

    // 2. 🌟 只保留这一个合并后的构造函数！Spring 会自动帮我们把这两个对象注入进来
    public KnowledgeController(VectorStore vectorStore, StringRedisTemplate redisTemplate) {
        this.vectorStore = vectorStore;
        this.redisTemplate = redisTemplate;
    }

    /**
     * 同步录入接口 (直接存入 PG，容易阻塞超时)
     */
    @PostMapping("/ingest")
    public String ingestText(@RequestBody String content) {
        Document document = new Document(content, Map.of("source", "manual-input"));
        vectorStore.add(List.of(document));
        return "知识同步录入成功！";
    }

    /**
     * 🌟 企业级异步录入接口 (扔进 Redis，瞬间返回)
     */
    @PostMapping("/async-ingest")
    public String asyncIngest(@RequestBody String content) {
        ObjectRecord<String, String> record = StreamRecords.newRecord()
                .ofObject(content)
                .withStreamKey("insight:document:stream");

        redisTemplate.opsForStream().add(record);

        return "文档已接收！正在后台排队进行向量化处理...";
    }
}