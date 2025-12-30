package site.newbie.web.llm.api.controller;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理后台视图控制器
 * 处理前端路由回退，确保 Vue Router 的 History 模式正常工作
 * 
 * 注意：此控制器只处理前端路由，API 路径（/admin/api/**）由 AdminController 处理
 * 前端路由路径：/admin/, /admin/dashboard, /admin/accounts, /admin/api-keys
 */
@RestController
@RequestMapping("/admin")
public class AdminViewController {
    
    /**
     * 处理前端路由，返回 index.html
     * 这样 Vue Router 可以处理客户端路由
     * 
     * 匹配所有 /admin 下的前端路由路径（不包括 /admin/api/**）
     */
    @GetMapping(value = {"", "/", "/dashboard", "/accounts", "/api-keys"})
    public ResponseEntity<Resource> index() {
        try {
            Resource resource = new ClassPathResource("static/admin/index.html");
            if (resource.exists()) {
                return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_HTML)
                    .body(resource);
            } else {
                return ResponseEntity.notFound().build();
            }
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }
}

