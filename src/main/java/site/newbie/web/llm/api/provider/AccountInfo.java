package site.newbie.web.llm.api.provider;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 当前登录账号信息
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AccountInfo {
    /**
     * 账号名称（显示名称，如 "Changen Ni"）
     */
    private String accountName;
    
    /**
     * 账号标识（通常是邮箱，如 "thien01657008216@gmail.com"）
     */
    private String accountId;
    
    /**
     * 是否成功获取到账号信息
     */
    private boolean success;
    
    /**
     * 错误信息（如果获取失败）
     */
    private String errorMessage;
    
    public static AccountInfo success(String accountName, String accountId) {
        return new AccountInfo(accountName, accountId, true, null);
    }
    
    public static AccountInfo failed(String errorMessage) {
        return new AccountInfo(null, null, false, errorMessage);
    }
}

