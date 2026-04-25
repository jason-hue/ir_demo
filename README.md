# IR Demo 启动步骤

## 1. 启动 Qdrant

在项目根目录执行：

```bash
docker compose -f docker-compose.local.yml up -d qdrant
```

检查是否启动成功：

```bash
curl http://127.0.0.1:6333/collections
```

---

## 2. 启动 Ollama

先启动 Ollama 服务：

```bash
ollama serve
```

另开一个终端拉取项目需要的模型：

```bash
ollama pull llama3.2:1b
ollama pull nomic-embed-text
```

检查模型是否存在：

```bash
ollama list
curl http://127.0.0.1:11434/api/tags
```

---

## 3. 启动后端（本地基础模式）

在项目根目录执行：

```bash
mvn spring-boot:run
```

后端默认地址：

```text
http://127.0.0.1:8080
```

启动后检查状态：

```bash
curl http://127.0.0.1:8080/status/production
```

---

## 4. 启动后端（真实爬虫 + Gemini + 向量检索）

先设置 Gemini Key：

```bash
export GEMINI_API_KEY="你的 Gemini API Key"
```

如果当前机器访问 Gemini 需要代理，用下面这条命令启动：

```bash
env GEMINI_API_KEY="$GEMINI_API_KEY" \
mvn spring-boot:run \
  -Dspring-boot.run.jvmArguments="-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=7897 -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7897" \
  -Dspring-boot.run.arguments="--server.port=18093 --irdemo.dir.home=workspace-real-crawl-clean --irdemo.dir.startCrawler=true --app.seed.lucene.enabled=false --irdemo.ai.chat-provider=gemini --irdemo.ai.gemini.enabled=true --irdemo.ai.gemini.model=gemini-2.5-flash --irdemo.ai.qdrant.collection-name=news_article_chunks_real_18091 --irdemo.ai.provider-status.connect-timeout=5s --irdemo.ai.provider-status.read-timeout=30s"
```

如果你只想复用已经抓好的真实数据、不重新爬虫，把 `--irdemo.dir.startCrawler=true` 改成：

```text
--irdemo.dir.startCrawler=false
```

启动后检查状态：

```bash
curl http://127.0.0.1:18093/status/production
```

---

## 5. 向量回填

如果 Lucene 已经有文档，但 Qdrant 还没有对应向量，可以执行：

```bash
curl -X POST http://127.0.0.1:18093/status/production/backfill-vectors
```

---

## 6. 启动前端

先进入前端目录：

```bash
cd frontend
```

复制环境变量文件：

```bash
cp .env.example .env
```

默认后端地址配置为：

```text
VITE_API_BASE_URL=http://127.0.0.1:8080
```

如果你要连真实爬虫 + Gemini 的后端，把 `.env` 改成：

```text
VITE_API_BASE_URL=http://127.0.0.1:18093
```

安装依赖并启动前端：

```bash
npm install
npm run dev
```

---

## 7. 启动后常用接口命令

### 7.1 关键词检索

```bash
curl "http://127.0.0.1:18093/query/kw?kw=腾讯新闻&pageNo=1&pageSize=10"
```

### 7.2 混合检索

```bash
curl -X POST "http://127.0.0.1:18093/query/hybrid" \
  -H "Content-Type: application/json" \
  -d '{"question":"腾讯新闻 科技 教育 财经","pageNo":1,"pageSize":5}'
```

### 7.3 问答

```bash
curl -X POST "http://127.0.0.1:18093/chat/ask" \
  -H "Content-Type: application/json" \
  -d '{"question":"真实抓取到的财经新闻里，信用卡业务整体趋势是什么？"}'
```
