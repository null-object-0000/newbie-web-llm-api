package site.newbie.web.llm.api.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 登录信息
 * 包含登录状态
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoginInfo {
    /**
     * 是否已登录
     */
    private boolean loggedIn;
    
    /**
     * 创建未登录的LoginInfo
     */
    public static LoginInfo notLoggedIn() {
        return LoginInfo.builder()
                .loggedIn(false)
                .build();
    }
    
    /**
     * 创建已登录的LoginInfo
     */
    public static LoginInfo loggedIn() {
        return LoginInfo.builder()
                .loggedIn(true)
                .build();
    }
}


