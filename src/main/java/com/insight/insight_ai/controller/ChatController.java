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

/**
 * @author someday
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatClient chatClient;
    private final VectorStore vectorStore;


    @Value("${spring.ai.openai.api-key}")
    private String apiKey;

    // Spring Boot 内置的 JSON 处理工具
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ChatController(ChatClient.Builder chatClientBuilder, VectorStore vectorStore) {
        this.chatClient = chatClientBuilder.build();
        this.vectorStore = vectorStore;
    }

    @GetMapping(value = "/stream", produces = "text/event-stream;charset=UTF-8")
    public Flux<String> chatStream(
            @RequestParam String message,
            // 接收前端传来的身份标识。如果不传，默认当做只能看公开资料的实习生
            @RequestParam(defaultValue = "public") String userDept) {

        System.out.println("接待当前请求的虚拟线程是: " + Thread.currentThread());
        System.out.println("当前提问者的模拟身份是: " + userDept);

        // 组装底层物理隔离的表达式
        // 逻辑：数据库里的内容，要么是公开的 (public)，要么是属于你这个部门的。其他的你休想拿到！
        String filterExpr = String.format("department == 'public' || department == '%s'", userDept);

        // 3. 【粗排】带着安全锁，从 PGVector 中快速召回 Top 10
        List<Document> rawDocs = vectorStore.similaritySearch(
                SearchRequest.defaults()
                        .withQuery(message)
                        .withTopK(10)
                        // 新增：把安全锁挂到查询请求上
                        .withFilterExpression(filterExpr)
        );

        // 4. 【精排】调用真正的 Rerank 接口进行打分重排
        List<Document> top3Docs = rerankDocuments(message, rawDocs);

        // 5. 【Prompt 组装】
        String context = top3Docs.stream()
                .map(Document::getContent)
                .collect(Collectors.joining("\n---\n"));

        String finalPrompt = String.format(
                """
                        你是一个极其严谨的企业知识库AI助手。请你【严格】遵循以下规则进行回答：
                        1. 绝对忠实于原意：你的回答必须100%%基于提供的【参考知识】，严禁使用你自身的预训练知识，严禁进行任何主观臆断或多余的逻辑推演。
                        2. 直接提取答案：如果【参考知识】中明确包含了答案，请直接输出事实，不需要解释你的推演过程（例如：直接回答“吃番茄炒蛋”）。
                        3. 拒绝回答界限：如果【参考知识】中没有完全匹配的信息，请立刻回复“抱歉，当前部门知识库中未找到相关准确信息”，绝不允许胡编乱造。
                        
                        【参考知识】：
                        %s
                        
                        【用户问题】：%s""",
                context, message
        );

        // 6. 【流式生成】监控探针版
        System.out.println("🚀 [Chat] 最终拼装的 Prompt 长度为: " + finalPrompt.length() + " 字符，准备发送给大模型...");

        return chatClient.prompt()
                .user(finalPrompt)
                .stream()
                .content()
                .doOnNext(System.out::print)
                .doOnError(e -> System.err.println("\n❌ [Chat] 大模型流式生成惨遭失败: " + e.getMessage()))
                .doOnComplete(() -> System.out.println("\n✅ [Chat] 大模型回答流式推送完毕！"));
    }


    /**
     * 调用 SiliconFlow 的 Rerank 接口 (带超时与防御机制)
     */
    private List<Document> rerankDocuments(String query, List<Document> candidates) {
        if (candidates.isEmpty()) {
            return candidates;
        }

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

            // 2.核心修复 1：为 HttpRequest 设置整体读取超时时间 (10秒)
            System.out.println("⏳ [Rerank] 正在发起网络请求，最多等待 10 秒...");
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.siliconflow.cn/v1/rerank"))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    // 如果 10 秒没收到数据，强行抛出 HttpTimeoutException 异常！
                    .timeout(java.time.Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            // 3. 核心修复 2：为 HttpClient 设置连接超时时间 (5秒)
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(5))
                    .build();

            // 4. 发送请求 (如果超时，这一行会直接跳到下面的 catch 里)
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            System.out.println("✅ [Rerank] 网络请求返回！HTTP状态码: " + response.statusCode());

            if (response.statusCode() != 200) {
                System.err.println("❌ Rerank API 调用失败: " + response.body());
                return candidates.stream().limit(3).toList();
            }

            JsonNode rootNode = objectMapper.readTree(response.body());
            JsonNode resultsNode = rootNode.get("results");

            if (resultsNode == null || !resultsNode.isArray()) {
                System.err.println("❌ Rerank 返回格式异常: " + response.body());
                return candidates.stream().limit(3).toList();
            }

            List<DocumentWithScore> scoredDocs = new ArrayList<>();
            for (JsonNode node : resultsNode) {
                int index = node.get("index").asInt();
                double score = node.get("relevance_score").asDouble();
                scoredDocs.add(new DocumentWithScore(candidates.get(index), score));
            }

            System.out.println("🎯 [Rerank] 重排打分完成，提取 Top " + 3);
            return scoredDocs.stream()
                    .sorted((d1, d2) -> Double.compare(d2.score, d1.score))
                    .limit(3)
                    .map(DocumentWithScore::document)
                    .toList();

        } catch (java.net.http.HttpTimeoutException e) {
            // 专门捕获超时异常
            System.err.println("⏱️ ❌ Rerank 请求超时(超过10秒)！已掐断连接，触发降级策略直接返回。");
            return candidates.stream().limit(3).toList();
        } catch (Exception e) {
            System.err.println("❌ Rerank 代码执行异常，触发降级策略: " + e.getMessage());
            e.printStackTrace(); // 打印详细堆栈方便排查
            return candidates.stream().limit(3).toList();
        }
    }

    // 内部小工具类(Java 14 引入的 Record)，用来临时绑定文档和分数
    private record DocumentWithScore(Document document, double score) {}
}