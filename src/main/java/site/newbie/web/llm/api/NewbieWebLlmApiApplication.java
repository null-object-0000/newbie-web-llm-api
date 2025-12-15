package site.newbie.web.llm.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class NewbieWebLlmApiApplication {

	public static void main(String[] args) {
		SpringApplication.run(NewbieWebLlmApiApplication.class, args);

		// 简单验证 Playwright 是否能加载驱动
		try (var playwright = com.microsoft.playwright.Playwright.create()) {
			System.out.println("✅ Playwright initialized successfully!");
		}
	}

}
