package com.insight.insight_ai.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatClient chatClient;
    private final VectorStore vectorStore;

    // 🌟 1. 从 application.yml 读取你的 API Key
    @Value("${spring.ai.openai.api-key}")
    private String apiKey;

    // Spring Boot 内置的 JSON 处理工具
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ChatController(ChatClient.Builder chatClientBuilder, VectorStore vectorStore) {
        this.chatClient = chatClientBuilder.build();
        this.vectorStore = vectorStore;
    }

    @GetMapping(value = "/stream", produces = "text/event-stream;charset=UTF-8")
    public Flux<String> chatStream(@RequestParam String message) {

        System.out.println("接待当前请求的虚拟线程是: " + Thread.currentThread());

        // 1. 【粗排】从 PGVector 中快速召回 Top 10
        List<Document> rawDocs = vectorStore.similaritySearch(
                SearchRequest.defaults()
                        .withQuery(message)
                        .withTopK(10)
        );

        // 2. 【精排】调用真正的 Rerank 接口进行打分重排
        List<Document> top3Docs = rerankDocuments(message, rawDocs, 3);

        // 3. 【Prompt 组装】
        String context = top3Docs.stream()
                .map(Document::getContent)
                .collect(Collectors.joining("\n---\n"));

        String finalPrompt = String.format(
                "你是一个资深的企业知识库AI助手。请根据以下【参考知识】回答用户的问题。\n" +
                        "【参考知识】：\n%s\n\n【用户问题】：%s",
                context, message
        );

        // 4. 【流式生成】
// 4. 【流式生成】监控探针版
        System.out.println("🚀 [Chat] 最终拼装的 Prompt 长度为: " + finalPrompt.length() + " 字符，准备发送给大模型...");

        return chatClient.prompt()
                .user(finalPrompt)
                .stream()
                .content()
                // 👇 探针1：大模型每吐出一个字，都在控制台打印出来
                .doOnNext(chunk -> System.out.print(chunk))
                // 👇 探针2：如果大模型生成失败，立刻拦截并打印出鲜红的真实报错原因！
                .doOnError(e -> System.err.println("\n❌ [Chat] 大模型流式生成惨遭失败: " + e.getMessage()))
                // 👇 探针3：流式输出正常结束时的提示
                .doOnComplete(() -> System.out.println("\n✅ [Chat] 大模型回答流式推送完毕！"));
    }

    /**
     * 🌟 核心：真实调用 SiliconFlow 的 Rerank 接口
     */
    /**
     * 🌟 核心：真实调用 SiliconFlow 的 Rerank 接口
     */
    /**
     * 🌟 核心：真实调用 SiliconFlow 的 Rerank 接口 (带超时与防御机制)
     */
    private List<Document> rerankDocuments(String query, List<Document> candidates, int topN) {
        if (candidates.isEmpty()) return candidates;

        try {
            System.out.println("⏳ [Rerank] 开始组装请求数据...");

            // 1. 提取文本，并做一层保险：防止单段文本过长导致 API 卡死 (截取前1000字)
            List<String> texts = candidates.stream()
                    .map(doc -> {
                        String content = doc.getContent();
                        return content.length() > 1000 ? content.substring(0, 1000) : content;
                    })
                    .toList();

            Map<String, Object> requestBodyMap = Map.of(
                    "model", "BAAI/bge-reranker-v2-m3",
                    "query", query,
                    "documents", texts
            );
            String jsonBody = objectMapper.writeValueAsString(requestBodyMap);

            // 2. 🌟 核心修复 1：为 HttpRequest 设置整体读取超时时间 (10秒)
            System.out.println("⏳ [Rerank] 正在发起网络请求，最多等待 10 秒...");
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.siliconflow.cn/v1/rerank"))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    // 👇 如果 10 秒没收到数据，强行抛出 HttpTimeoutException 异常！
                    .timeout(java.time.Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            // 3. 🌟 核心修复 2：为 HttpClient 设置连接超时时间 (5秒)
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(5))
                    .build();

            // 4. 发送请求 (如果超时，这一行会直接跳到下面的 catch 里)
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            System.out.println("✅ [Rerank] 网络请求返回！HTTP状态码: " + response.statusCode());

            if (response.statusCode() != 200) {
                System.err.println("❌ Rerank API 调用失败: " + response.body());
                return candidates.stream().limit(topN).toList();
            }

            JsonNode rootNode = objectMapper.readTree(response.body());
            JsonNode resultsNode = rootNode.get("results");

            if (resultsNode == null || !resultsNode.isArray()) {
                System.err.println("❌ Rerank 返回格式异常: " + response.body());
                return candidates.stream().limit(topN).toList();
            }

            List<DocumentWithScore> scoredDocs = new ArrayList<>();
            for (JsonNode node : resultsNode) {
                int index = node.get("index").asInt();
                double score = node.get("relevance_score").asDouble();
                scoredDocs.add(new DocumentWithScore(candidates.get(index), score));
            }

            System.out.println("🎯 [Rerank] 重排打分完成，提取 Top " + topN);
            return scoredDocs.stream()
                    .sorted((d1, d2) -> Double.compare(d2.score, d1.score))
                    .limit(topN)
                    .map(DocumentWithScore::document)
                    .toList();

        } catch (java.net.http.HttpTimeoutException e) {
            // 🌟 专门捕获超时异常
            System.err.println("⏱️ ❌ Rerank 请求超时(超过10秒)！已掐断连接，触发降级策略直接返回。");
            return candidates.stream().limit(topN).toList();
        } catch (Exception e) {
            System.err.println("❌ Rerank 代码执行异常，触发降级策略: " + e.getMessage());
            e.printStackTrace(); // 打印详细堆栈方便排查
            return candidates.stream().limit(topN).toList();
        }
    }

    // 内部小工具类(Java 14 引入的 Record)，用来临时绑定文档和分数
    private record DocumentWithScore(Document document, double score) {}
}