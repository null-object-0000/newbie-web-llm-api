# Web-LLM-API

将网页版 LLM（如 DeepSeek）转换为标准的 OpenAI 兼容 API，支持流式响应、多模型管理和对话历史管理。

## ✨ 功能特性

- 🔄 **OpenAI 兼容 API**：完全兼容 OpenAI API 规范，可直接使用 OpenAI SDK
- 🌊 **流式响应**：支持 Server-Sent Events (SSE) 流式输出，实现打字机效果
- 💬 **对话管理**：支持新对话和继续对话，自动管理对话上下文
- 🧠 **深度思考模式**：支持 DeepSeek 的深度思考模式，可区分思考过程和最终回复
- 📝 **对话历史**：前端自动保存对话历史到浏览器本地存储
- 🔗 **URL 管理**：自动保存和恢复对话 URL，切换对话时自动导航到对应页面
- 🎯 **多模型支持**：基于 Provider 架构，易于扩展支持更多 LLM 提供商
- 🎨 **Web 测试界面**：内置美观的 Web 测试界面，支持对话列表管理

## 🛠️ 技术栈

- **后端**：
  - Spring Boot 4.0.0
  - Java 21
  - Playwright（浏览器自动化）
  - Server-Sent Events (SSE)

- **前端**：
  - OpenAI JavaScript SDK 6.10.0
  - 原生 JavaScript (ES6 Modules)
  - LocalStorage（本地存储）

## 📦 安装和运行

### 前置要求

- Java 21 或更高版本
- Maven 3.6+
- 已登录 DeepSeek 账号（浏览器中）

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

4. **访问测试界面**
```
http://localhost:24753/test.html
```

## 🚀 使用方法

### Web 界面使用

1. 打开 `http://localhost:24753/test.html`
2. 选择提供者和模型
3. 可选择启用"深度思考"模式
4. 输入消息并发送
5. 在侧边栏管理对话列表：
   - 点击"新对话"创建新对话
   - 点击对话项切换对话
   - 点击标题可编辑对话标题
   - 点击"删除"删除对话

### API 使用

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

### 基础路径

所有 API 的基础路径为：`/v1`

### 端点

#### 1. 获取模型列表

```
GET /v1/models
```

**响应示例**：
```json
{
  "object": "list",
  "data": [
    {
      "id": "deepseek-web",
      "object": "model",
      "created": 1234567890,
      "owned_by": "deepseek"
    }
  ]
}
```

#### 2. 获取提供者列表

```
GET /v1/providers
```

**响应示例**：
```json
{
  "deepseek": {
    "name": "deepseek",
    "models": ["deepseek-web"]
  }
}
```

#### 3. 聊天补全

```
POST /v1/chat/completions
```

**请求头**：
- `X-New-Conversation` (可选): `true` 或 `false`，是否新开对话
- `X-Thinking` (可选): `true` 或 `false`，是否启用深度思考模式
- `X-Conversation-URL` (可选): 对话 URL，用于继续特定对话

**请求体**：
```json
{
  "model": "deepseek-web",
  "messages": [
    {"role": "user", "content": "你好"}
  ],
  "stream": true
}
```

**响应**：Server-Sent Events (SSE) 流

**响应格式**（兼容 OpenAI）：
```
data: {"id":"...","object":"chat.completion.chunk","created":1234567890,"model":"deepseek-web","choices":[{"index":0,"delta":{"content":"你好"},"finish_reason":null}]}

data: {"id":"...","object":"chat.completion.chunk","created":1234567890,"model":"deepseek-web","choices":[{"index":0,"delta":{"content":"！"},"finish_reason":null}]}

data: [DONE]
```

**特殊标记**：
- `__THINKING__`：思考内容标记（深度思考模式）
- `__REPLACE__`：整体替换标记（用于内容修正）
- `__URL__`：对话 URL 标记（用于保存对话 URL）

## ⚙️ 配置

### 应用配置

编辑 `src/main/resources/application.properties`：

```properties
# 服务器端口
server.port=24753

# Playwright 浏览器配置
# 浏览器数据目录（可选，用于保持登录状态）
playwright.browser-data-dir=./my-browser-data
```

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

1. **页面管理**：使用 Playwright 自动化浏览器，管理 DeepSeek 对话页面
2. **内容提取**：采用混合方式提取 AI 回复：
   - DOM 解析：实时流式提取内容
   - SSE 拦截：通过 JavaScript 注入拦截 SSE 数据，用于最终修正
3. **流式传输**：将提取的内容转换为 OpenAI 兼容的 SSE 格式
4. **URL 管理**：自动保存和恢复对话 URL，确保切换对话时导航到正确页面

## 📝 注意事项

1. **登录状态**：首次使用需要手动登录 DeepSeek（在浏览器中）
2. **浏览器资源**：Playwright 会启动浏览器实例，占用一定系统资源
3. **网络要求**：需要能够访问 `chat.deepseek.com`
4. **对话 URL**：切换对话时会自动导航到保存的 URL，确保对话上下文正确

## 🐛 故障排除

### 问题：前端无法连接后端

- 检查后端是否正常运行
- 确认端口号是否正确（默认 24753）
- 检查浏览器控制台的错误信息

### 问题：无法获取 AI 回复

- 检查是否已登录 DeepSeek
- 查看后端日志，确认 Playwright 是否正常工作
- 检查网络连接

### 问题：对话 URL 未保存

- 检查浏览器控制台是否有错误
- 确认 LocalStorage 是否可用
- 查看后端日志，确认 URL 是否成功发送

## 📄 许可证

查看 [LICENSE](LICENSE) 文件了解详情。

## 🤝 贡献

欢迎提交 Issue 和 Pull Request！

## 📧 联系方式

如有问题或建议，请通过 Issue 联系。

---

**注意**：本项目仅供学习和研究使用，请遵守相关服务的使用条款。

