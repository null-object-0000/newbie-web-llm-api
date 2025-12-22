package site.newbie.web.llm.api.provider;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import site.newbie.web.llm.api.model.ChatCompletionRequest;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * SSE 数据记录工具类
 * 用于记录完整的原始 SSE 响应数据到单独的日志文件，便于调试
 */
@Slf4j
public class SseDataLogger {
    
    // 使用单独的 Logger 实例，写入专门的 SSE 日志文件
    private static final Logger SSE_LOGGER = LoggerFactory.getLogger(SseDataLogger.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    
    private final StringBuilder allRawSseData = new StringBuilder();
    private int sseDataChunkCount = 0;
    private final String modelName;
    private final String sessionId;
    private final ChatCompletionRequest request;
    private final LocalDateTime startTime;
    private boolean requestLogged = false;
    
    public SseDataLogger(String modelName, ChatCompletionRequest request) {
        this.modelName = modelName;
        this.request = request;
        this.sessionId = UUID.randomUUID().toString();
        this.startTime = LocalDateTime.now();
    }
    
    /**
     * 记录完整的原始请求信息（仅在第一次调用时记录）
     */
    public void logRequest() {
        if (requestLogged || request == null) {
            return;
        }
        
        try {
            String requestJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(request);
            String requestLog = String.format(
                    """
                            
                            
                            ================================================================================
                            SSE 会话开始 - Session ID: %s
                            时间: %s
                            模型: %s
                            ================================================================================
                            完整原始请求:
                            %s
                            ================================================================================
                            """,
                sessionId,
                startTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")),
                modelName,
                requestJson
            );
            
            SSE_LOGGER.info(requestLog);
            requestLogged = true;
        } catch (Exception e) {
            log.warn("记录请求信息时出错: {}", e.getMessage());
        }
    }
    
    /**
     * 记录 SSE 数据块
     * 
     * @param sseData SSE 数据内容
     * @param chunkType 数据块类型描述（可选，用于标识数据块的来源）
     */
    public void logSseChunk(String sseData, String chunkType) {
        if (sseData == null || sseData.isEmpty()) {
            return;
        }
        
        // 确保请求信息已记录
        logRequest();
        
        sseDataChunkCount++;
        String typeLabel = chunkType != null && !chunkType.isEmpty() 
                ? " (" + chunkType + ")" 
                : "";
        
        allRawSseData.append("\n\n=== SSE 数据块 #")
                .append(sseDataChunkCount)
                .append(typeLabel)
                .append(" (长度: ")
                .append(sseData.length())
                .append(") ===\n")
                .append(sseData)
                .append("\n=== SSE 数据块 #")
                .append(sseDataChunkCount)
                .append(" 结束 ===\n");
        
        // 同时记录到控制台（DEBUG 级别）和 SSE 日志文件（INFO 级别）
        log.debug("=== [{}] 原始 SSE 响应数据块 #{} (长度: {}) ===\n{}\n=== SSE 数据块 #{} 结束 ===",
                modelName, sseDataChunkCount, sseData.length(), sseData, sseDataChunkCount);
        
        // 写入 SSE 日志文件
        String chunkLog = String.format(
            "\n--- SSE 数据块 #%d%s (Session: %s) ---\n长度: %d 字符\n内容:\n%s\n--- SSE 数据块 #%d 结束 ---\n",
            sseDataChunkCount, typeLabel, sessionId, sseData.length(), sseData, sseDataChunkCount
        );
        SSE_LOGGER.info(chunkLog);
    }
    
    /**
     * 记录 SSE 数据块（无类型描述）
     */
    public void logSseChunk(String sseData) {
        logSseChunk(sseData, null);
    }
    
    /**
     * 输出完整的 SSE 数据汇总日志
     * 
     * @param extractedTextLength 最终提取的文本长度
     */
    public void logSummary(int extractedTextLength) {
        // 确保请求信息已记录
        logRequest();
        
        LocalDateTime endTime = LocalDateTime.now();
        long duration = java.time.Duration.between(startTime, endTime).toMillis();
        
        if (allRawSseData.length() > 0) {
            String summaryLog = String.format(
                    """
                            
                            
                            ================================================================================
                            SSE 会话结束 - Session ID: %s
                            开始时间: %s
                            结束时间: %s
                            持续时间: %d ms
                            模型: %s
                            ================================================================================
                            统计信息:
                              - 总共接收数据块数: %d
                              - 累计原始数据长度: %d 字符
                              - 最终提取文本长度: %d 字符
                            ================================================================================
                            完整原始 SSE 响应数据:
                            %s
                            ================================================================================
                            SSE 会话结束 - Session ID: %s
                            ================================================================================
                            
                            """,
                sessionId,
                startTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")),
                endTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")),
                duration,
                modelName,
                sseDataChunkCount,
                allRawSseData.length(),
                extractedTextLength,
                    allRawSseData,
                sessionId
            );
            
            // 写入 SSE 日志文件
            SSE_LOGGER.info(summaryLog);
            
            // 同时记录到控制台（INFO 级别）
            log.info("=== [{}] SSE 流完整原始响应数据汇总 ===\n总共接收 {} 个数据块，累计原始数据长度: {} 字符，最终提取文本长度: {} 字符\n完整原始数据已写入 SSE 日志文件",
                    modelName, sseDataChunkCount, allRawSseData.length(), extractedTextLength);
        } else {
            String emptySummaryLog = String.format(
                    """
                            
                            
                            ================================================================================
                            SSE 会话结束 - Session ID: %s
                            开始时间: %s
                            结束时间: %s
                            持续时间: %d ms
                            模型: %s
                            ================================================================================
                            警告: 未接收到任何 SSE 数据
                            ================================================================================
                            SSE 会话结束 - Session ID: %s
                            ================================================================================
                            
                            """,
                sessionId,
                startTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")),
                endTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")),
                duration,
                modelName,
                sessionId
            );
            
            SSE_LOGGER.warn(emptySummaryLog);
        }
    }
    
    /**
     * 获取数据块数量
     */
    public int getChunkCount() {
        return sseDataChunkCount;
    }
    
    /**
     * 获取累计原始数据长度
     */
    public int getTotalRawDataLength() {
        return allRawSseData.length();
    }
    
    /**
     * 清空已记录的数据（用于重置）
     */
    public void clear() {
        allRawSseData.setLength(0);
        sseDataChunkCount = 0;
    }
}

