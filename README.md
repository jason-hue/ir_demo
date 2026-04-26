# 运行步骤

## 1. 启动 Qdrant

```bash
docker compose -f docker-compose.local.yml up -d qdrant
curl http://127.0.0.1:6333/collections
```

## 2. 启动 Ollama

```bash
ollama serve
```

## 3. 拉取模型

```bash
ollama pull llama3.2:1b
ollama pull nomic-embed-text
ollama list
```

## 4. 选择 AI Provider

### 4.1 使用 GLM（推荐国内环境，无需代理）

```bash
export GLM_API_KEY="你的智谱AI API Key"

env GLM_API_KEY="$GLM_API_KEY" \
mvn spring-boot:run \
  -Dspring-boot.run.arguments="--server.port=18082 --irdemo.dir.home=workspace-real-crawl-clean --irdemo.dir.startCrawler=true --app.seed.lucene.enabled=false --irdemo.ai.chat-provider=glm --irdemo.ai.glm.enabled=true --irdemo.ai.glm.model=glm-4-flash --irdemo.ai.glm.api-key=$GLM_API_KEY --irdemo.ai.qdrant.collection-name=news_article_chunks_real_18091 --irdemo.ai.provider-status.connect-timeout=5s --irdemo.ai.provider-status.read-timeout=30s"
```

### 4.2 使用 Gemini（需要代理）

```bash
export GEMINI_API_KEY="你的 Gemini API Key"

env GEMINI_API_KEY="$GEMINI_API_KEY" \
mvn spring-boot:run \
  -Dspring-boot.run.jvmArguments="-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=7897 -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7897" \
  -Dspring-boot.run.arguments="--server.port=18082 --irdemo.dir.home=workspace-real-crawl-clean --irdemo.dir.startCrawler=true --app.seed.lucene.enabled=false --irdemo.ai.chat-provider=gemini --irdemo.ai.gemini.enabled=true --irdemo.ai.gemini.model=gemini-2.5-flash --irdemo.ai.qdrant.collection-name=news_article_chunks_real_18091 --irdemo.ai.provider-status.connect-timeout=5s --irdemo.ai.provider-status.read-timeout=30s"
```

### 4.3 使用 Ollama（本地模型）

```bash
mvn spring-boot:run \
  -Dspring-boot.run.arguments="--server.port=18082 --irdemo.dir.home=workspace-real-crawl-clean --irdemo.dir.startCrawler=true --app.seed.lucene.enabled=false --irdemo.ai.chat-provider=ollama --irdemo.ai.ollama.enabled=true --irdemo.ai.ollama.chat-model=llama3.2:1b --irdemo.ai.ollama.embedding-model=nomic-embed-text --irdemo.ai.qdrant.collection-name=news_article_chunks_real_18091 --irdemo.ai.provider-status.connect-timeout=5s --irdemo.ai.provider-status.read-timeout=30s"
```

## 5. 检查后端状态

```bash
curl http://127.0.0.1:18082/status/production
```

## 6. 启动前端

```bash
cd frontend
cp .env.example .env
sed -i 's#http://127.0.0.1:8080#http://127.0.0.1:18082#' .env
npm install
npm run dev
```

## 7. 关键词检索

```bash
curl "http://127.0.0.1:18082/query/kw?kw=%E8%85%BE%E8%AE%AF&pageNo=1&pageSize=10"
```

## 8. 混合检索

```bash
curl -X POST "http://127.0.0.1:18082/query/hybrid" \
  -H "Content-Type: application/json" \
  -d '{"question":"腾讯新闻 科技 教育 财经","pageNo":1,"pageSize":5}'
```

## 9. 问答

```bash
curl -X POST "http://127.0.0.1:18082/chat/ask" \
  -H "Content-Type: application/json" \
  -d '{"question":"财经新闻中信用卡业务趋势是什么？"}'
```

---

# 项目报告 - RAG系统向量检索效果验证

## 项目概述
本项目用于验证基于真实新闻数据的 RAG 问答系统效果。系统通过"新闻爬取 + 向量检索 + 大模型生成"的链路，提升回答相关性与可解释性，降低纯模型直答带来的幻觉风险。

## 测试方法
测试数据包括 356 个新闻 chunks，覆盖财经、科技、教育、政治等主题。测试问题分为三类：简单事实问题、复杂推理问题、库外问题，用于验证检索准确性、回答质量与边界处理能力。

## 核心成果
- 检索准确率达到 100%。
- Hybrid 检索链路稳定工作。
- 答案质量整体较好，能够基于检索内容生成带引用回答。
- 系统已具备内测和小范围试运行基础。

## 发现的问题
### P0 - 必须修复
1. HNSW 索引未建立，检索性能仍有优化空间。
2. API 字段存在问题，库外问题响应语义需修正。

### P1 - 重要优化
1. 存在实体混淆风险，库外问题个别情况下会误匹配。
2. 响应时间偏长，用户体验仍需优化。

### P2 - 功能增强
1. 建议继续扩展新闻数据规模。
2. 建议持续提升库外识别准确率。

## 技术价值
- 验证了 RAG 核心链路已经跑通。
- 验证了库内问题可以基于真实数据回答。
- 验证了跨领域新闻检索与引用式回答能力。

## 改进路线图
### 立即行动
1. 优化 Qdrant 索引。
2. 修正库外问题 API 响应语义。

### 短期优化
1. 实施实体消歧机制。
2. 优化响应时间。
3. 扩展新闻数据量。

### 长期增强
1. 建立自动化测试体系。
2. 建立性能监控和告警机制。
3. 持续提升边界问题识别能力。

## 项目验证结论
- RAG 核心链路已经跑通。
- 库内问题检索效果较好，相关新闻召回准确率高。
- 系统可进入内测阶段。
- 暂不建议直接对外生产放量，建议优先修复索引、库外拒答、实体消歧与响应时延问题。
