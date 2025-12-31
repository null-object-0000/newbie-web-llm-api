# Gemini 内置指令功能

## 概述

Gemini Provider 现在支持内置指令，允许用户在对话中通过特殊指令来执行操作，例如添加云盘文件或本地文件作为附件。

**重要：指令对话和普通对话的区别**
- **指令对话**：消息只包含指令（如 `/attach-drive:文件名`），会独立处理，不发送给 Gemini，通过流式消息返回执行进度和结果
- **普通对话**：消息包含指令+实际内容（如 `/attach-drive:文件名 请分析`），指令会作为附件添加，然后发送清理后的消息给 Gemini

## 支持的指令

### 1. 添加 Google Drive 文件

**语法：**
```
/attach-drive:文件名
或
/attach-drive 文件名
```

**指令对话示例：**
```
/attach-drive:隋坡-糖醋排骨-202409.mp4
```
- 只执行指令，不发送给 Gemini
- 通过流式消息返回执行进度和结果

**普通对话示例：**
```
/attach-drive:隋坡-糖醋排骨-202409.mp4 请帮我分析这个视频
```
- 先执行指令添加附件
- 然后发送"请帮我分析这个视频"给 Gemini（包含附件）

**说明：**
- 会自动打开 Google Drive 文件选择器
- 在搜索框中输入文件名并选择第一个结果
- 指令对话会实时反馈操作进度

### 2. 添加本地文件

**语法：**
```
/attach-local:文件路径
或
/attach-local 文件路径
```

**指令对话示例：**
```
/attach-local:/path/to/file.pdf
```

**普通对话示例：**
```
/attach-local:/path/to/file.pdf 请帮我总结这个文档
```

**说明：**
- 会自动打开文件选择器并选择指定文件
- 等待文件上传完成
- 指令对话会实时反馈操作进度

### 3. 查询帮助

**语法：**
```
/help
或
/help:指令名
```

**指令对话示例：**
```
/help
```
- 显示所有支持的指令列表及其基本用法

```
/help:attach-drive
```
- 显示特定指令的详细帮助信息

**说明：**
- `/help` 会列出所有支持的指令及其基本用法
- `/help:指令名` 会显示指定指令的详细说明、用法、示例和注意事项
- 支持的指令名：`attach-drive`、`attach-local`、`help`

## 工作原理

### 指令对话流程

1. **指令检测**：`CommandParser.isCommandOnly()` 判断是否只包含指令
2. **指令执行**：如果是指令对话，执行所有指令
3. **进度反馈**：通过流式消息实时发送操作进度
   - "开始添加 Google Drive 文件: 文件名"
   - "正在打开上传菜单..."
   - "正在打开 Google Drive 文件选择器..."
   - "正在搜索文件: 文件名"
   - "✅ 已选择文件: 文件名"
   - "✅ 文件添加完成"
4. **结果返回**：发送最终执行结果
   ```
   执行指令结果：
   1. 添加 Google Drive 文件: 文件名
      ✅ 执行成功
   
   ✅ 所有指令执行完成
   ```
5. **完成**：不发送消息给 Gemini，直接完成

### 普通对话流程

1. **指令解析**：`CommandParser` 解析用户消息，识别内置指令
2. **指令执行**：在发送消息前，按顺序执行所有指令（作为附件添加）
3. **消息清理**：从消息中移除指令部分，只保留实际内容
4. **消息发送**：发送清理后的消息给 Gemini（包含附件）

## 实现细节

### 指令格式

指令遵循以下格式：
- `/command:参数` - 带冒号的参数
- `/command 参数` - 空格分隔的参数

### 指令执行顺序

多个指令会按在消息中出现的顺序执行。

### 指令对话 vs 普通对话判断

- **指令对话**：消息解析后，清理后的消息为空或只有空白
- **普通对话**：消息解析后，清理后的消息还有实际内容

### 错误处理

**指令对话：**
- 如果指令执行失败，会在结果中标记为失败
- 通过流式消息返回错误信息
- 不会发送消息给 Gemini

**普通对话：**
- 如果指令执行失败，会记录警告日志，但不会阻止消息发送
- 如果消息中只有指令没有实际内容，会使用默认消息 "Hello"

## 扩展指令

要添加新指令，需要：

1. 实现 `Command` 接口
2. 在 `CommandParser.createCommand()` 方法中添加指令处理逻辑

示例：
```java
public class MyCustomCommand implements Command {
    @Override
    public String getName() {
        return "my-command";
    }
    
    @Override
    public String getDescription() {
        return "我的自定义指令: " + param;
    }
    
    @Override
    public boolean execute(Page page, ProgressCallback progressCallback) {
        // 发送进度（如果是指令对话）
        if (progressCallback != null) {
            progressCallback.onProgress("开始执行我的指令...");
        }
        
        // 实现指令逻辑
        try {
            // ... 执行操作 ...
            
            if (progressCallback != null) {
                progressCallback.onProgress("✅ 执行完成");
            }
            return true;
        } catch (Exception e) {
            if (progressCallback != null) {
                progressCallback.onProgress("❌ 执行失败: " + e.getMessage());
            }
            return false;
        }
    }
}
```

**注意事项：**
- `progressCallback` 可能为 `null`（普通对话中的指令），需要检查
- 如果是指令对话，通过 `progressCallback` 发送进度消息
- 进度消息会自动包装为系统消息格式

## 注意事项

1. 指令必须在消息开头或独立出现
2. 指令参数不能包含空格（除非使用冒号格式）
3. Google Drive 文件选择需要网络连接和已登录的 Google 账号
4. 本地文件路径必须是绝对路径
5. **指令对话**会独立处理，不发送给 Gemini，只返回执行结果
6. **普通对话**中的指令会作为附件添加，然后发送消息给 Gemini
7. `help` 指令只能用于指令对话，不支持在普通对话中使用

## 使用场景

### 场景 1：纯指令操作（指令对话）
当你只想执行操作，不需要 AI 回复时：
```
/attach-drive:文件.pdf
```
- ✅ 执行指令
- ✅ 返回执行结果
- ❌ 不发送给 Gemini

### 场景 2：带附件的对话（普通对话）
当你需要 AI 处理附件时：
```
/attach-drive:文件.pdf 请帮我总结这个文档
```
- ✅ 执行指令添加附件
- ✅ 发送"请帮我总结这个文档"给 Gemini（包含附件）
- ✅ Gemini 回复（可以访问附件）

### 场景 3：查询指令帮助
当你需要了解支持的指令时：
```
/help
```
- ✅ 显示所有支持的指令列表

```
/help:attach-drive
```
- ✅ 显示 `attach-drive` 指令的详细帮助

