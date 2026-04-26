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
