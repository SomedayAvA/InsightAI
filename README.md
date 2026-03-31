# 🧠 InsightAI - 企业级多租户 RAG 智能知识库平台

![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2+-brightgreen.svg)
![Spring AI](https://img.shields.io/badge/Spring%20AI-0.8.1-blue.svg)
![PGVector](https://img.shields.io/badge/PGVector-PostgreSQL-336791.svg)
![Redis Stream](https://img.shields.io/badge/Redis-Stream-DC382D.svg)
![SiliconFlow](https://img.shields.io/badge/SiliconFlow-BGE%20Rerank-blueviolet.svg)

InsightAI 是一个基于 **Spring AI** 与 **PGVector** 构建的企业级 RAG（检索增强生成）智能知识库系统。本项目不仅实现了基础的文档向量化与大模型问答，更针对真实企业场景解决了**数据越权泄露**、**海量文档入库阻塞**以及**分布式脏数据宕机**等核心架构痛点。

---

## 🌟 核心架构亮点 (Interview Highlights)

### 1. 🛡️ 物理级多租户数据隔离 (Multi-Tenant Isolation)
* **痛点**：传统 Prompt 约束极易遭受 Prompt Injection 攻击，导致机密数据（如财务、高管薪酬）泄露给普通员工。
* **破局**：在向量入库源头为 Document 注入 `department` Metadata 权限指纹。在检索阶段，利用 PGVector 的底层 SQL 表达式 (`withFilterExpression`) 进行物理级数据截断，确保大模型从根本上无法接触越权数据，实现 100% 数据安全。

### 2. 🚀 异步非阻塞向量化链路 (Async Ingestion Pipeline)
* **痛点**：大文本的 Embedding 向量化极为耗时，同步接口会导致 Tomcat 线程池枯竭。
* **破局**：引入 **Redis Stream** 作为消息中间件，削峰填谷。前端录入瞬间响应，后台 `DocumentStreamListener` 消费者异步执行 JSON 解析、文本切块与 PGVector 入库。

### 3. 🦠 分布式毒药消息容错 (Poison Pill Resilience)
* **痛点**：消息队列中若混入历史残留或格式错误的脏数据（Poison Pill），会导致消费者线程陷入“解析失败 -> 未确认 -> 无限重试”的死循环崩溃。
* **破局**：在 Listener 层面实现了防御性编程，对非 JSON 格式进行主动嗅探，并精确捕获 `JsonProcessingException`。将脏数据做安全拦截与丢弃（或转入死信队列 DLQ），保障消费者组稳定运行。

### 4. 🎯 双阶段检索与高可用降级 (Two-Stage Retrieval & Fallback)
* **粗排**：基于 PGVector 进行高维空间相似度快速召回（Top 10）。
* **精排**：接入 SiliconFlow 的 `BAAI/bge-reranker-v2-m3` 模型进行语义重排打分（Top 3），大幅提升回答精准度。
* **降级容错**：为 Rerank 网络请求配置严格的 Http Timeout（连接 5s/读取 10s），一旦外部 API 超时或宕机，系统自动降级使用粗排结果，确保问答服务的高可用性 (HA)。

---

## 🏗️ 系统架构图

```text
[前端交互] --> (HTTP POST) --> [KnowledgeController]
                                      |
                                  (JSON 封装)
                                      |
                                [Redis Stream] 队列削峰
                                      |
                           [DocumentStreamListener] 异步消费
                                      |
                         (防毒药拦截 + 注入权限 Metadata)
                                      |
                           [PostgreSQL + PGVector] 向量数据库
                                      ^
                                      | (SQL 权限过滤拦截 + 向量粗排)
                                      v
[前端问答] --> (SSE Stream) --> [ChatController] 
                                      |
                               [BGE Reranker] 语义精排 (带超时降级)
                                      |
                                [LLM 大模型] 组装严格约束 Prompt 并流式输出
```

---

## 🛠️ 技术栈 (Tech Stack)

* **后端框架**: Spring Boot 3.x, Java 21 (启用虚拟线程 Virtual Threads)
* **AI 编排**: Spring AI
* **向量数据库**: PostgreSQL + PGVector 插件
* **消息中间件**: Redis Stream
* **大语言模型**: OpenAI API 规范 / SiliconFlow
* **精排模型**: BGE-Reranker-v2-m3

---

## 🚀 快速启动 (Quick Start)

### 1. 环境准备
确保本机已安装 Docker，使用以下命令启动基础设施：
```bash
# 启动带 PGVector 插件的 PostgreSQL
docker run -d --name pgvector -p 5432:5432 -e POSTGRES_USER=postgres -e POSTGRES_PASSWORD=postgres pgvector/pgvector:pg16

# 启动 Redis
docker run -d --name redis -p 6379:6379 redis
```

### 2. 配置修改
在 `src/main/resources/application-local.yml` 中配置你的大模型 API 密钥（本项目已配置 `.gitignore` 防止密钥泄露）：
```yaml
spring:
  ai:
    openai:
      api-key: "sk-your-siliconflow-api-key"
      base-url: "[https://api.siliconflow.cn/v1](https://api.siliconflow.cn/v1)"
```

### 3. 启动与验证
* 启动 Spring Boot 项目。
* 访问 `http://localhost:8080` (确保将项目内的 `index.html` 放置于 `src/main/resources/static` 目录下)。
* 体验带权限隔离的知识录入与智能问答！

---

## 👨‍💻 关于作者
* 致力于探索 AI 与企业级后端架构的深度结合。欢迎 Star ⭐ 与交流！
