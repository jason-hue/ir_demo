# IR Demo

一个用于本地验证的本地优先 RAG / 信息检索示例工程。

项目提供：
- 腾讯新闻文章抓取与索引
- 中文关键词检索
- Lucene + Qdrant 混合检索
- Spring AI 问答
- Vue 3 + Vite 演示前端

---

## 1. 技术栈

- Java 21
- Spring Boot 3.4.x
- Lucene 8.11.1
- HanLP / hanlp-lucene-plugin
- WebMagic 0.7.6
- Spring AI 1.0.3
- Qdrant
- Ollama
- Vue 3 + Vite

---

## 2. 仓库结构

- `src/main/java/`：后端代码
- `src/main/resources/`：配置与种子数据
- `frontend/`：前端演示页面
- `docker-compose.local.yml`：本地 Qdrant
- `workspace/`：运行期目录（索引、爬虫输出等，自动生成）

---

## 3. 新手完整部署教程（从克隆到可聊天）

下面按 **Ubuntu / Debian 风格命令** 写一套从 0 到可聊天的流程。如果你用的是其他系统，也只需要把“安装依赖”那一步换成对应平台的安装方式，其余仓库内命令不变。

### 第 0 步：克隆仓库

```bash
git clone <your-repo-url>
cd ir_demo
```

---

### 第 1 步：安装基础依赖

你至少需要：
- Git
- Java 21
- Maven 3.9+
- Node.js 20+
- npm
- Docker + Docker Compose
- Ollama

#### 1.1 Java / Maven / Node / Docker

如果你的系统还没有这些工具，请先安装。安装完成后确认：

```bash
java -version
mvn -version
node -v
npm -v
docker --version
docker compose version
```

---

### 第 2 步：安装并启动 Ollama

如果还没有安装 Ollama：

```bash
curl -fsSL https://ollama.com/install.sh | sh
```

如果脚本下载中断，可以改用“手动下载安装包”的方式；核心目标是最后能执行：

```bash
ollama --version
```

启动 Ollama 服务：

```bash
ollama serve
```

另开一个终端确认服务在线：

```bash
curl http://127.0.0.1:11434/api/tags
```

---

### 第 3 步：拉取本项目默认模型

本项目 demo 配置默认使用：
- 聊天模型：`llama3.2`
- 向量模型：`nomic-embed-text`

执行：

```bash
ollama pull llama3.2
ollama pull nomic-embed-text
ollama list
```

当 `ollama list` 里能看到这两个模型时，说明本地 AI 模型准备好了。

---

### 第 4 步：启动 Qdrant

项目已经带了本地 compose 文件：

```bash
sg docker -c 'docker compose -f docker-compose.local.yml up -d qdrant'
```

检查是否成功：

```bash
curl http://127.0.0.1:6333/collections
```

如果你当前 shell 没有 Docker socket 权限，可以先确认自己在 `docker` 组里，或者重新登录 shell 后再执行上面的 `sg docker -c ...` 命令。

---

### 第 5 步：启动后端

生产安全的默认启动方式是不带 profile 直接运行。它不会启用 demo 种子，也不会启用 `demo-happy` 假 Provider：

```bash
mvn spring-boot:run
```

如果你需要演示示例路径，再显式使用 `demo` profile。它会：
- 使用固定种子数据启动 Lucene
- 默认端口 `18082`
- 不依赖实时爬虫启动
- 在 Ollama / Qdrant 不可用时仍然能启动，并返回降级结果

启动命令：

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=demo
```

如果 `18082` 被占用：

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=demo -Dspring-boot.run.arguments="--server.port=18083"
```

启动成功后，你可以手动测一下：

```bash
curl "http://127.0.0.1:18082/query/kw?kw=新闻检索助手&pageNo=1&pageSize=10"
```

如果后端不在 `18082`，把上面 URL 换成你的实际端口。

---

### 第 6 步：启动前端

进入前端目录：

```bash
cd frontend
npm install
```

默认前端会访问：

```bash
http://127.0.0.1:8080
```

这来自：
- `frontend/.env.example`
- 以及 `frontend/src/App.vue` 的默认值

如果你的后端不是默认的 `8080`，先写 `.env`：

```bash
echo 'VITE_API_BASE_URL=http://127.0.0.1:18082' > .env
```

然后启动前端：

```bash
npm run dev
```

---

### 第 7 步：验证“真的能聊天”

前端页面中切到 **Chat**，输入问题后：

如果本地 AI 跑通，通常你会看到：
- 没有 degraded 错误条
- `Mode: hybrid`
- 有 `Answer` 面板
- 有 `Sources` 列表

后端 `/chat/ask` 成功时，一般会返回：
- `success: true`
- `answerAvailable: true`

你也可以直接测接口：

```bash
curl -X POST "http://127.0.0.1:18082/chat/ask" \
  -H "Content-Type: application/json" \
  -d '{"question":"请总结腾讯新闻中的AI相关新闻"}'
```

如果一切正常，返回里应该有：
- `success: true`
- `answerAvailable: true`
- `retrievalMode: "hybrid"`

---

## 4. 如果本地 AI 还没准备好：兜底演示模式

如果你暂时不想安装/拉取 Ollama 模型，也可以先把“聊天页面”跑通：

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=demo,demo-happy
```

这个模式会：
- 使用固定种子数据启动 Lucene
- 注入 demo 用的假 `ChatModel` / `VectorStore`
- 返回稳定的检索问答案例答案

它适合课堂展示或先验证前后端联通，不代表“真本地模型”已经准备完成。

---

## 5. 常用命令

### 后端测试

```bash
mvn test
```

### 后端打包

```bash
mvn -DskipTests package
```

### 仅运行关键 smoke 测试

```bash
mvn -Psmoke test
```

### 前端构建

```bash
npm --prefix frontend run build
```

---

## 6. 主要接口

- `GET /query/kw`：关键词检索
- `POST /query/hybrid`：混合检索
- `POST /chat/ask`：基于检索上下文问答

---

## 7. 常见问题

### 1) `mvn: command not found`

说明 Maven 没装好，或者没加到 `PATH`。先执行：

```bash
mvn -version
```

如果没有输出版本号，请先安装 Maven。

### 2) `Application run failed` 且日志里有 `BindException: 地址已在使用`

说明端口被占用，例如 `18082`。先查：

```bash
lsof -i :18082
```

然后结束旧进程，或者直接换端口启动。

### 3) Qdrant 启动失败 / Docker permission denied

先确认 Docker 可用：

```bash
docker --version
docker compose version
```

如果是 socket 权限问题，优先尝试：

```bash
sg docker -c 'docker compose -f docker-compose.local.yml up -d qdrant'
```

### 4) Ollama 服务在，但 chat 还是不能回答

最常见原因是：
- Ollama 服务启动了，但模型还没拉下来

检查：

```bash
ollama list
```

如果是空的，就执行：

```bash
ollama pull llama3.2
ollama pull nomic-embed-text
```

### 5) 前端能打开，但 chat 看起来“不工作”

先确认前端打到了正确的后端端口：

```bash
cat frontend/.env
```

如果没有 `.env`，前端默认使用：

```bash
http://127.0.0.1:8080
```

如果你在跑 `demo` profile，请把它改成对应的 demo 端口（默认 `18082`）。

另外还要确认后端是否真在那个端口启动成功。

---

## 8. 说明

- 默认测试不会跑 live-network 测试。
- `workspace/`、`target/` 等目录是运行期/构建产物，不应提交。
- 若本地 AI 或向量服务不可用，系统会返回降级结果，而不是直接启动失败。
