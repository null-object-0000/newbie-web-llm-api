# Antigravity Provider

基于 Antigravity-Manager 底层实现的提供器，直接调用 Google v1internal API。

## 功能特性

- **直接调用 Google API**：不依赖 Antigravity-Manager 应用，直接使用底层实现
- **账号管理**：支持多账号轮换和自动刷新
- **Project ID 自动获取**：自动从 Google API 获取或生成随机 project_id
- **流式响应**：完整支持 SSE 流式输出
- **请求转换**：自动将 OpenAI 格式转换为 Gemini v1internal 格式

## 配置

在 `application.properties` 中配置：

```properties
# Antigravity 账号目录（存放账号 JSON 文件）
antigravity.accounts-dir=./antigravity-accounts
```

## 登录命令

Antigravity Provider 提供了专用的登录命令：

- `/antigravity-login` 或 `/ag-login`：启动 Google OAuth 登录流程

使用步骤：
1. 在聊天中输入 `/antigravity-login`
2. 系统会自动打开浏览器（或显示授权链接）
3. 在浏览器中完成 Google 账号授权
4. 授权成功后，账号信息会自动保存
5. Provider 会自动重新加载账号

## 账号文件格式

账号文件应放在 `antigravity.accounts-dir` 目录下，文件名为 `*.json`，格式如下：

```json
{
  "id": "account-id",
  "email": "user@example.com",
  "token": {
    "access_token": "ya29.xxx",
    "refresh_token": "1//xxx",
    "expires_in": 3600,
    "expiry_timestamp": 1234567890,
    "project_id": "useful-fuze-abc12"
  }
}
```

## 使用方法

### 方式一：通过登录命令添加账号（推荐）

1. 启动应用
2. 在聊天中使用登录命令：`/antigravity-login` 或 `/ag-login`
3. 按照提示在浏览器中完成 Google OAuth 授权
4. 账号会自动保存到 `antigravity-accounts` 目录

### 方式二：手动添加账号文件

1. 将 Google OAuth 账号文件放入 `antigravity-accounts` 目录
2. 启动应用，Provider 会自动加载账号
3. 使用模型名称 `antigravity-chat` 或直接使用 Gemini 模型名称（如 `gemini-3-pro`、`gemini-3-flash`）

## 模型映射

- `antigravity-chat` → `gemini-3-flash`（默认）
- 支持直接使用 Gemini 模型名称：`gemini-3-pro`、`gemini-3-flash` 等

## 实现细节

### 核心组件

- **TokenManager**: 管理 Google OAuth tokens，支持账号轮换
- **ProjectResolver**: 通过 `loadCodeAssist` API 获取 project_id
- **UpstreamClient**: 调用 Google v1internal API
- **RequestMapper**: 将 OpenAI 请求转换为 Gemini 格式
- **ResponseMapper**: 将 Gemini SSE 流转换为 OpenAI SSE 格式

### API 端点

- **生成内容**: `https://cloudcode-pa.googleapis.com/v1internal:streamGenerateContent?alt=sse`
- **获取 Project ID**: `https://cloudcode-pa.googleapis.com/v1internal:loadCodeAssist`

### User-Agent

使用 `antigravity/1.11.9 windows/amd64` 作为 User-Agent，与 Antigravity-Manager 保持一致。

