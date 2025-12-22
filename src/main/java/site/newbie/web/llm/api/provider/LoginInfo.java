package site.newbie.web.llm.api.provider;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 登录信息
 * 包含登录状态和账号昵称
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
     * 账号昵称（如果已登录）
     */
    private String nickname;
    
    /**
     * 创建未登录的LoginInfo
     */
    public static LoginInfo notLoggedIn() {
        return LoginInfo.builder()
                .loggedIn(false)
                .nickname(null)
                .build();
    }
    
    /**
     * 创建已登录的LoginInfo
     */
    public static LoginInfo loggedIn(String nickname) {
        return LoginInfo.builder()
                .loggedIn(true)
                .nickname(nickname)
                .build();
    }
}


