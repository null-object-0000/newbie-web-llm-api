package site.newbie.web.llm.api.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * 管理后台访问拦截器
 * 只允许内网 IP 访问管理后台
 */
@Slf4j
@Component
public class AdminAccessInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String clientIp = getClientIpAddress(request);
        
        if (!isInternalIp(clientIp)) {
            log.warn("拒绝外部 IP 访问管理后台: {}", clientIp);
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"error\":\"管理后台仅允许内网访问\"}");
            return false;
        }
        
        log.debug("允许内网 IP 访问管理后台: {}", clientIp);
        return true;
    }

    /**
     * 获取客户端真实 IP 地址
     */
    private String getClientIpAddress(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("Proxy-Client-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("WL-Proxy-Client-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("HTTP_CLIENT_IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("HTTP_X_FORWARDED_FOR");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        
        // 处理多个 IP 的情况（X-Forwarded-For 可能包含多个 IP）
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        
        // 如果是 localhost，转换为 127.0.0.1
        if ("0:0:0:0:0:0:0:1".equals(ip) || "::1".equals(ip)) {
            ip = "127.0.0.1";
        }
        
        return ip;
    }

    /**
     * 检查是否为内网 IP
     * 内网 IP 范围：
     * - 127.0.0.0/8 (127.0.0.1 - 127.255.255.254) - 本地回环
     * - 10.0.0.0/8 (10.0.0.1 - 10.255.255.254) - 私有网络
     * - 172.16.0.0/12 (172.16.0.1 - 172.31.255.254) - 私有网络
     * - 192.168.0.0/16 (192.168.0.1 - 192.168.255.254) - 私有网络
     */
    private boolean isInternalIp(String ip) {
        if (ip == null || ip.isEmpty()) {
            return false;
        }
        
        try {
            InetAddress address = InetAddress.getByName(ip);
            
            // 检查是否为回环地址
            if (address.isLoopbackAddress()) {
                return true;
            }
            
            // 检查是否为链路本地地址
            if (address.isLinkLocalAddress()) {
                return true;
            }
            
            // 检查是否为站点本地地址（IPv6）
            if (address.isSiteLocalAddress()) {
                return true;
            }
            
            // 检查 IPv4 私有地址范围
            byte[] addr = address.getAddress();
            if (addr.length == 4) { // IPv4
                int firstOctet = addr[0] & 0xFF;
                int secondOctet = addr[1] & 0xFF;
                
                // 127.0.0.0/8
                if (firstOctet == 127) {
                    return true;
                }
                
                // 10.0.0.0/8
                if (firstOctet == 10) {
                    return true;
                }
                
                // 172.16.0.0/12
                if (firstOctet == 172 && secondOctet >= 16 && secondOctet <= 31) {
                    return true;
                }
                
                // 192.168.0.0/16
                if (firstOctet == 192 && secondOctet == 168) {
                    return true;
                }
            }
            
            return false;
        } catch (UnknownHostException e) {
            log.warn("无法解析 IP 地址: {}", ip, e);
            return false;
        }
    }
}


