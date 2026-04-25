# 部署步骤

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
curl http://127.0.0.1:11434/api/tags
```

## 4. 设置 Gemini Key

```bash
export GEMINI_API_KEY="你的 Gemini API Key"
```

## 5. 启动后端

```bash
env GEMINI_API_KEY="$GEMINI_API_KEY" \
mvn spring-boot:run \
  -Dspring-boot.run.jvmArguments="-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=7897 -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7897" \
  -Dspring-boot.run.arguments="--server.port=18082 --irdemo.dir.home=workspace-real-crawl-clean --irdemo.dir.startCrawler=true --app.seed.lucene.enabled=false --irdemo.ai.chat-provider=gemini --irdemo.ai.gemini.enabled=true --irdemo.ai.gemini.model=gemini-2.5-flash --irdemo.ai.qdrant.collection-name=news_article_chunks_real_18091 --irdemo.ai.provider-status.connect-timeout=5s --irdemo.ai.provider-status.read-timeout=30s"
```

## 6. 检查后端状态

```bash
curl http://127.0.0.1:18082/status/production
```

## 7. 向量回填

```bash
curl -X POST http://127.0.0.1:18082/status/production/backfill-vectors
```

## 8. 启动前端

```bash
cd frontend
cp .env.example .env
sed -i 's#http://127.0.0.1:8080#http://127.0.0.1:18082#' .env
npm install
npm run dev
```

## 9. 关键词检索

```bash
curl "http://127.0.0.1:18082/query/kw?kw=腾讯新闻&pageNo=1&pageSize=10"
```

## 10. 混合检索

```bash
curl -X POST "http://127.0.0.1:18082/query/hybrid" \
  -H "Content-Type: application/json" \
  -d '{"question":"腾讯新闻 科技 教育 财经","pageNo":1,"pageSize":5}'
```

## 11. 问答

```bash
curl -X POST "http://127.0.0.1:18082/chat/ask" \
  -H "Content-Type: application/json" \
  -d '{"question":"真实抓取到的财经新闻里，信用卡业务整体趋势是什么？"}'
```
