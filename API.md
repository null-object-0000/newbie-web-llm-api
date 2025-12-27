# API 文档

## 基础路径

所有 API 的基础路径为：`/v1`（除了图片相关 API 使用 `/api/images`）

## 端点

### 1. 获取模型列表

```
GET /v1/models
```

获取所有可用的模型列表。

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
    },
    {
      "id": "gemini-web-chat",
      "object": "model",
      "created": 1234567890,
      "owned_by": "gemini"
    },
    {
      "id": "gemini-web-imagegen",
      "object": "model",
      "created": 1234567890,
      "owned_by": "gemini"
    }
  ]
}
```

**使用示例**：
```bash
curl http://localhost:24753/v1/models
```

### 2. 获取提供者列表

```
GET /v1/providers
```

获取所有可用的提供者及其支持的模型列表。

**响应示例**：
```json
{
  "deepseek": {
    "name": "deepseek",
    "models": ["deepseek-web"]
  },
  "gemini": {
    "name": "gemini",
    "models": ["gemini-web-chat", "gemini-web-imagegen"]
  },
  "openai": {
    "name": "openai",
    "models": ["gpt-4o", "gpt-4o-mini", "o1-preview", "o1-mini"]
  }
}
```

**使用示例**：
```bash
curl http://localhost:24753/v1/providers
```

### 3. 聊天补全

```
POST /v1/chat/completions
```

发送聊天请求，支持流式响应。

**请求头**：
- `Content-Type`: `application/json`（必需）
- `X-New-Conversation` (可选): `true` 或 `false`，是否新开对话，默认为 `false`
- `X-Thinking` (可选): `true` 或 `false`，是否启用深度思考模式，默认为 `false`
- `X-Conversation-URL` (可选): 对话 URL，用于继续特定对话
- `X-Conversation-ID` (可选): 对话 ID，用于继续特定对话

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

**请求参数说明**：
- `model` (必需): 模型名称，例如 `deepseek-web`、`gemini-web-chat`、`gemini-web-imagegen` 等
- `messages` (必需): 消息数组，每个消息包含：
  - `role`: 角色，`user` 或 `assistant`
  - `content`: 消息内容
- `stream` (可选): 是否使用流式响应，默认为 `false`

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
- `nwla-conversation-id`：对话 ID 标记（用于保存对话 ID）

**使用示例**：

发送聊天请求（流式）：
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

生成图片（Gemini 图片生成模型）：
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

**使用 OpenAI SDK**：

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

### 4. 获取图片资源

```
GET /api/images/{filename}
```

获取本地存储的图片资源。

**路径参数**：
- `filename`：图片文件名（例如：`gemini_76248a96acfd3db4_20251228_011010_6e65de0c.jpg`）

**响应**：图片文件（Content-Type 根据文件扩展名自动确定）

**支持的图片格式**：
- JPEG/JPG
- PNG
- GIF
- WebP

**使用示例**：
```bash
curl http://localhost:24753/api/images/gemini_76248a96acfd3db4_20251228_011010_6e65de0c.jpg
```

**响应头**：
- `Content-Type`: 根据文件扩展名自动设置（例如：`image/jpeg`）
- `Content-Length`: 文件大小（字节）
- `Access-Control-Allow-Origin`: `*`（允许跨域访问）

### 5. 下载原始尺寸图片

```
GET /api/images/download-original/{filename}
```

从 Gemini 下载原始尺寸的图片。此接口会重新访问 Gemini 对话页面并触发下载，获取原始分辨率的图片。

**路径参数**：
- `filename`：图片文件名（例如：`gemini_76248a96acfd3db4_20251228_011010_6e65de0c.jpg`）

**响应**：原始尺寸的图片文件（作为附件下载）

**响应头**：
- `Content-Type`: 根据文件扩展名自动设置
- `Content-Disposition`: `attachment; filename="{filename}"`（触发浏览器下载）

**使用示例**：
```bash
curl -O http://localhost:24753/api/images/download-original/gemini_76248a96acfd3db4_20251228_011010_6e65de0c.jpg
```

**注意事项**：
- 此接口需要访问 Gemini 对话页面，可能需要一些时间
- 确保已登录 Gemini 账号
- 文件名格式：`{provider}_{conversationId}_{timestamp}_{uuid}.{ext}`

## 错误处理

### 错误响应格式

当请求失败时，API 会返回相应的 HTTP 状态码和错误信息：

```json
{
  "error": {
    "message": "错误描述",
    "type": "error_type",
    "code": "error_code"
  }
}
```

### 常见错误码

- `400 Bad Request`: 请求参数错误
- `401 Unauthorized`: 未登录或登录已过期
- `404 Not Found`: 资源不存在（例如图片文件）
- `500 Internal Server Error`: 服务器内部错误
- `503 Service Unavailable`: 服务不可用（例如提供器正忙）

## 速率限制

目前没有实现速率限制，但建议：
- 避免过于频繁的请求
- 使用流式响应时，确保客户端能够及时处理数据
- 对于图片生成请求，请耐心等待生成完成

## 认证

目前 API 不需要 API Key，但需要：
1. 在浏览器中登录对应的服务（DeepSeek/Gemini/OpenAI）
2. 登录状态会保存在 `user-data` 目录中
3. 如果登录过期，需要重新登录

## 模型说明

### DeepSeek

- **deepseek-web**: 标准聊天模型

### Gemini

- **gemini-web-chat**: 聊天模型，支持深度思考模式
- **gemini-web-imagegen**: 图片生成模型，支持根据文本描述生成图片

### OpenAI

- **gpt-4o**: GPT-4o 模型
- **gpt-4o-mini**: GPT-4o Mini 模型
- **o1-preview**: O1 Preview 模型（支持深度思考）
- **o1-mini**: O1 Mini 模型（支持深度思考）

## 最佳实践

1. **使用流式响应**：对于长文本生成，建议使用 `stream: true` 以获得更好的用户体验
2. **管理对话上下文**：使用 `X-Conversation-ID` 或 `X-Conversation-URL` 来继续之前的对话
3. **错误处理**：始终检查响应状态码并处理错误情况
4. **图片生成**：图片生成可能需要较长时间，请设置合适的超时时间
5. **资源清理**：对于不再需要的图片，可以手动删除 `user-data/gemini-images/` 目录中的文件

