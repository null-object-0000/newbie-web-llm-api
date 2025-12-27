# Web-LLM-API

将网页版 LLM（如 DeepSeek、Gemini、OpenAI）转换为标准的 OpenAI 兼容 API，支持流式响应、多模型管理、对话历史管理和图片生成功能。

## ✨ 功能特性

- 🔄 **OpenAI 兼容 API**：完全兼容 OpenAI API 规范，可直接使用 OpenAI SDK
- 🌊 **流式响应**：支持 Server-Sent Events (SSE) 流式输出，实现打字机效果
- 💬 **对话管理**：支持新对话和继续对话，自动管理对话上下文
- 🧠 **深度思考模式**：支持 DeepSeek 和 Gemini 的深度思考模式，可区分思考过程和最终回复
- 📝 **对话历史**：前端自动保存对话历史到浏览器本地存储
- 🔗 **URL 管理**：自动保存和恢复对话 URL，切换对话时自动导航到对应页面
- 🎯 **多模型支持**：基于 Provider 架构，易于扩展支持更多 LLM 提供商
- 🖼️ **图片生成**：支持 Gemini 图片生成模型，自动下载并本地存储生成的图片
- 📥 **图片下载**：支持下载原始尺寸的图片，提供完整的图片访问 API

## 🛠️ 技术栈

- **后端**：
  - Spring Boot 4.0.0
  - Java 21
  - Playwright（浏览器自动化）
  - Server-Sent Events (SSE)

- **客户端兼容**：
  - 完全兼容 OpenAI API 规范
  - 支持所有兼容 OpenAI 的客户端和 SDK

## 📦 安装和运行

### 前置要求

- Java 21 或更高版本
- Maven 3.6+
- 已登录对应提供商的账号（浏览器中）：
  - DeepSeek：需要登录 `chat.deepseek.com`
  - Gemini：需要登录 `gemini.google.com`
  - OpenAI：需要登录 `chatgpt.com` 或 `chat.openai.com`

### 快速开始

1. **克隆项目**
```bash
git clone <repository-url>
cd newbie-web-llm-api
```

2. **编译项目**
```bash
mvn clean package
```

3. **运行项目**
```bash
mvn spring-boot:run
```

或者运行编译后的 JAR：
```bash
java -jar target/newbie-web-llm-api-0.0.1-SNAPSHOT.jar
```

4. **使用兼容 OpenAI 的客户端**

本项目完全兼容 OpenAI API 规范，可以使用任何兼容 OpenAI 的客户端或 SDK。推荐使用 [Open WebUI](https://github.com/open-webui/open-webui) 作为 Web 界面。

### Docker 部署

#### 前置要求

- Docker 20.10+ 和 Docker Compose 2.0+
- 或仅 Docker（不使用 docker-compose）

#### 使用 Docker Compose（推荐）

1. **构建并启动容器**
```bash
docker-compose up -d
```

2. **查看日志**
```bash
docker-compose logs -f
```

3. **停止容器**
```bash
docker-compose down
```

4. **重新构建镜像**
```bash
docker-compose build --no-cache
docker-compose up -d
```

#### 使用基础镜像加速构建（推荐）

为了加速构建，项目支持使用预构建的基础镜像（包含 Node.js 和 Chromium）。基础镜像只需要构建一次，之后每次构建应用时都可以复用。

**首次构建基础镜像**（只需要执行一次）：
```bash
# 构建基础镜像（包含 Node.js 和 Chromium）
docker build --target base -t newbie-web-llm-api-base:latest .

# 或者使用 docker-compose
docker-compose -f docker-compose.build.yml build base-image
```

**之后构建应用时，Docker 会自动复用基础镜像**，大大加快构建速度：
```bash
# 正常构建，会自动使用已存在的基础镜像
docker-compose build
docker-compose up -d
```

**推送到镜像仓库（可选）**：
如果使用 Docker Hub 或其他镜像仓库，可以推送基础镜像供团队共享：
```bash
# 标记镜像
docker tag newbie-web-llm-api-base:latest your-registry/newbie-web-llm-api-base:latest

# 推送镜像
docker push your-registry/newbie-web-llm-api-base:latest

# 然后在 Dockerfile 中修改 FROM 语句使用远程镜像
# FROM your-registry/newbie-web-llm-api-base:latest
```

#### 使用 Docker 命令

1. **构建镜像**
```bash
docker build -t newbie-web-llm-api:latest .
```

2. **运行容器**
```bash
docker run -d \
  --name newbie-web-llm-api \
  -p 24753:24753 \
  -v $(pwd)/user-data:/app/user-data \
  -v $(pwd)/logs:/app/logs \
  newbie-web-llm-api:latest
```

3. **查看日志**
```bash
docker logs -f newbie-web-llm-api
```

4. **停止容器**
```bash
docker stop newbie-web-llm-api
docker rm newbie-web-llm-api
```

#### Docker 部署注意事项

- **数据持久化**：`user-data` 目录会被挂载到容器中，用于保存浏览器数据和登录会话
- **首次登录**：首次运行需要在浏览器中登录 DeepSeek 账号，登录状态会保存在 `user-data` 目录
- **端口映射**：默认端口为 24753，可通过修改 `docker-compose.yml` 或 Docker 命令中的端口映射来更改
- **资源限制**：建议为容器分配至少 512MB 内存，Playwright 浏览器需要一定资源

## 🚀 使用方法

### 推荐：使用 Open WebUI

[Open WebUI](https://github.com/open-webui/open-webui) 是一个功能强大的开源 Web UI，完全兼容 OpenAI API。

#### 配置 Open WebUI

1. 访问 `http://localhost:3000`
2. 创建管理员账号
3. 在设置中添加自定义 API：
   - **API Base URL**: `http://localhost:24753/v1`
   - **API Key**: `not-needed`（任意值即可）
4. 选择模型并开始使用

#### 支持的模型

- **DeepSeek**：`deepseek-web` - 聊天模型
- **Gemini**：
  - `gemini-web-chat` - 聊天模型（支持深度思考）
  - `gemini-web-imagegen` - 图片生成模型
- **OpenAI**：`gpt-4o`, `gpt-4o-mini`, `o1-preview`, `o1-mini` 等

### 使用其他兼容 OpenAI 的客户端

本项目完全兼容 OpenAI API 规范，可以使用任何兼容 OpenAI 的客户端，例如：

- **命令行工具**：`curl`, `httpie` 等
- **Python SDK**：`openai` Python 包
- **JavaScript SDK**：`openai` npm 包
- **其他客户端**：任何支持 OpenAI API 的客户端

详细 API 文档请查看 [API.md](API.md)。

### 图片生成示例

使用 Gemini 图片生成模型（`gemini-web-imagegen`）：

```bash
curl -X POST http://localhost:24753/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model": "gemini-web-imagegen",
    "messages": [
      {"role": "user", "content": "画一只可爱的小猫"}
    ],
    "stream": true
  }'
```

生成的图片会：
1. 自动下载到本地（存储在 `user-data/gemini-images/` 目录）
2. 以 Markdown 图片格式返回完整 URL
3. 支持通过 `/api/images/{filename}` 访问
4. 支持通过 `/api/images/download-original/{filename}` 下载原始尺寸

### 使用 curl 命令

#### 1. 获取模型列表

```bash
curl http://localhost:24753/v1/models
```

#### 2. 获取提供者列表

```bash
curl http://localhost:24753/v1/providers
```

#### 3. 发送聊天请求（流式）

```bash
curl -X POST http://localhost:24753/v1/chat/completions \
  -H "Content-Type: application/json" \
  -H "X-New-Conversation: true" \
  -H "X-Thinking: false" \
  -d '{
    "model": "deepseek-web",
    "messages": [
      {"role": "user", "content": "你好"}
    ],
    "stream": true
  }'
```

#### 4. 生成图片（Gemini 图片生成模型）

```bash
curl -X POST http://localhost:24753/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model": "gemini-web-imagegen",
    "messages": [
      {"role": "user", "content": "画一只可爱的小猫"}
    ],
    "stream": true
  }'
```

响应中会包含图片的完整 URL，例如：
```
![生成的图片](http://localhost:24753/api/images/gemini_76248a96acfd3db4_20251228_011010_6e65de0c.jpg)
```

#### 4. 使用 OpenAI SDK

```javascript
import OpenAI from 'openai';

const openai = new OpenAI({
  baseURL: 'http://localhost:24753/v1',
  apiKey: 'not-needed',
  dangerouslyAllowBrowser: true
});

const stream = await openai.chat.completions.create({
  model: 'deepseek-web',
  messages: [
    { role: 'user', content: '你好' }
  ],
  stream: true
});

for await (const chunk of stream) {
  console.log(chunk.choices[0]?.delta?.content || '');
}
```

## 📡 API 文档

详细的 API 文档请查看 [API.md](API.md) 文件。

API 文档包含：
- 所有端点的详细说明
- 请求和响应格式
- 使用示例
- 错误处理
- 最佳实践

### 快速参考

- **获取模型列表**: `GET /v1/models`
- **获取提供者列表**: `GET /v1/providers`
- **聊天补全**: `POST /v1/chat/completions`
- **获取图片**: `GET /api/images/{filename}`
- **下载原始图片**: `GET /api/images/download-original/{filename}`

## ⚙️ 配置

### 应用配置

编辑 `src/main/resources/application.properties`：

```properties
# 服务器端口
server.port=24753

# 服务器基础 URL（用于构建完整的图片 URL）
app.server.base-url=http://localhost:24753

# Playwright 浏览器配置
# 浏览器模式：true 为无头模式，false 为有界面模式（首次登录建议使用 false）
app.browser.headless=false

# 浏览器数据目录（用于保持登录状态和存储图片）
app.browser.user-data-dir=./user-data

# 监控模式（可选）
openai.monitor.mode=sse

# 调试模式（可选）
debug=false
```

### 配置说明

- **`app.server.base-url`**：服务器的基础 URL，用于构建完整的图片访问 URL。如果部署到其他服务器，请修改为对应的地址（例如：`https://your-domain.com`）
- **`app.browser.headless`**：浏览器模式。首次登录时建议设置为 `false`，登录成功后可以设置为 `true` 以节省资源
- **`app.browser.user-data-dir`**：浏览器数据目录，用于保存登录状态和生成的图片。图片会存储在 `{user-data-dir}/gemini-images/` 目录下

### 浏览器数据目录

项目支持使用浏览器数据目录来保持登录状态。首次运行时，Playwright 会自动创建浏览器实例。如果需要保持登录状态：

1. 将已登录的浏览器数据目录复制到项目根目录
2. 在配置中指定路径（如 `./my-browser-data`）

## 🏗️ 架构设计

### Provider 架构

项目采用 Provider 模式，易于扩展支持更多 LLM 提供商：

```
LLMProvider (接口)
    ├── BaseProvider (抽象基类)
    │   ├── sendSseChunk() - 发送 SSE 数据块
    │   ├── sendSseReplace() - 发送整体替换消息
    │   ├── sendThinkingContent() - 发送思考内容
    │   └── sendConversationId() - 发送对话 ID
    │
    └── DeepSeekProvider (DeepSeek 实现)
        ├── streamChat() - 流式聊天
        ├── monitorResponseHybrid() - 混合监听响应
        └── setupSseInterceptor() - SSE 拦截器
```

### 添加新的 Provider

1. 实现 `LLMProvider` 接口
2. 继承 `BaseProvider` 基类
3. 在 `ProviderRegistry` 中注册

示例：
```java
@Component
public class MyProvider extends BaseProvider implements LLMProvider {
    // 实现接口方法
}
```

## 🔍 工作原理

1. **页面管理**：使用 Playwright 自动化浏览器，管理各提供商的对话页面
2. **内容提取**：采用混合方式提取 AI 回复：
   - DOM 解析：实时流式提取内容
   - SSE 拦截：通过 JavaScript 注入拦截 SSE 数据，用于最终修正
3. **流式传输**：将提取的内容转换为 OpenAI 兼容的 SSE 格式
4. **URL 管理**：自动保存和恢复对话 URL，确保切换对话时导航到正确页面
5. **图片生成**（Gemini）：
   - 自动启用图片生成工具
   - 监控思考过程和图片生成进度
   - 使用 Playwright 的请求 API 下载图片（自动携带浏览器上下文）
   - 本地存储图片并生成完整访问 URL
   - 支持下载原始尺寸图片

## 📝 注意事项

1. **登录状态**：首次使用需要手动登录对应的服务（在浏览器中）：
   - DeepSeek：访问 `chat.deepseek.com` 并登录
   - Gemini：访问 `gemini.google.com` 并登录
   - OpenAI：访问 `chatgpt.com` 或 `chat.openai.com` 并登录
2. **浏览器资源**：Playwright 会启动浏览器实例，占用一定系统资源。建议首次登录后设置 `app.browser.headless=true` 以节省资源
3. **网络要求**：需要能够访问对应的服务域名
4. **对话 URL**：切换对话时会自动导航到保存的 URL，确保对话上下文正确
5. **图片存储**：生成的图片会存储在 `user-data/gemini-images/` 目录，请确保有足够的磁盘空间
6. **图片 URL**：图片 URL 基于 `app.server.base-url` 配置生成，部署到其他服务器时请修改此配置

## 📄 许可证

查看 [LICENSE](LICENSE) 文件了解详情。

## 🤝 贡献

欢迎提交 Issue 和 Pull Request！

## 📧 联系方式

如有问题或建议，请通过 Issue 联系。

---

**注意**：本项目仅供学习和研究使用，请遵守相关服务的使用条款。

