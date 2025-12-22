# 使用多阶段构建
# 阶段1: 构建阶段
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /app

# 配置 Maven 使用国内镜像（阿里云）
RUN mkdir -p /root/.m2 && \
    echo '<?xml version="1.0" encoding="UTF-8"?>' > /root/.m2/settings.xml && \
    echo '<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0"' >> /root/.m2/settings.xml && \
    echo '          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"' >> /root/.m2/settings.xml && \
    echo '          xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.0.0 http://maven.apache.org/xsd/settings-1.0.0.xsd">' >> /root/.m2/settings.xml && \
    echo '  <mirrors>' >> /root/.m2/settings.xml && \
    echo '    <mirror>' >> /root/.m2/settings.xml && \
    echo '      <id>aliyunmaven</id>' >> /root/.m2/settings.xml && \
    echo '      <mirrorOf>central</mirrorOf>' >> /root/.m2/settings.xml && \
    echo '      <name>阿里云公共仓库</name>' >> /root/.m2/settings.xml && \
    echo '      <url>https://maven.aliyun.com/repository/public</url>' >> /root/.m2/settings.xml && \
    echo '    </mirror>' >> /root/.m2/settings.xml && \
    echo '  </mirrors>' >> /root/.m2/settings.xml && \
    echo '</settings>' >> /root/.m2/settings.xml

# 复制 pom.xml 和源代码
COPY pom.xml .
COPY src ./src

# 构建应用
RUN mvn clean package -DskipTests

# 阶段2: 基础镜像阶段（包含 Node.js 和 Chromium）
# 这个阶段可以单独构建并推送到镜像仓库，实现复用
FROM eclipse-temurin:21-jre-jammy AS base

WORKDIR /app

# 配置 apt 使用国内镜像（阿里云 Ubuntu 22.04 Jammy）
RUN sed -i 's|http://archive.ubuntu.com/ubuntu|http://mirrors.aliyun.com/ubuntu|g' /etc/apt/sources.list.d/*.list 2>/dev/null || \
    sed -i 's|http://archive.ubuntu.com/ubuntu|http://mirrors.aliyun.com/ubuntu|g' /etc/apt/sources.list 2>/dev/null || true && \
    sed -i 's|http://security.ubuntu.com/ubuntu|http://mirrors.aliyun.com/ubuntu|g' /etc/apt/sources.list.d/*.list 2>/dev/null || \
    sed -i 's|http://security.ubuntu.com/ubuntu|http://mirrors.aliyun.com/ubuntu|g' /etc/apt/sources.list 2>/dev/null || true

# 安装 Playwright 浏览器依赖
RUN apt-get update && \
    apt-get install -y \
    # Playwright 所需的基础依赖
    libnss3 \
    libnspr4 \
    libatk1.0-0 \
    libatk-bridge2.0-0 \
    libcups2 \
    libdrm2 \
    libdbus-1-3 \
    libxkbcommon0 \
    libxcomposite1 \
    libxdamage1 \
    libxfixes3 \
    libxrandr2 \
    libgbm1 \
    libasound2 \
    libpango-1.0-0 \
    libcairo2 \
    libatspi2.0-0 \
    # 其他可能需要的依赖
    fonts-liberation \
    libappindicator3-1 \
    xdg-utils \
    wget \
    ca-certificates \
    curl \
    && rm -rf /var/lib/apt/lists/*

# 安装 Node.js（用于 Playwright CLI）
RUN curl -fsSL https://deb.nodesource.com/setup_20.x | bash - && \
    apt-get install -y nodejs && \
    rm -rf /var/lib/apt/lists/*

# 安装 Playwright CLI 并只安装 Chromium
RUN npm install -g playwright@1.57.0 && \
    playwright install chromium && \
    playwright install-deps chromium && \
    # 清理：删除 Playwright CLI（保留已安装的 Chromium）
    npm uninstall -g playwright && \
    rm -rf /root/.npm /tmp/*

# 设置 Playwright 浏览器路径环境变量
# 注意：Playwright Java 和 Playwright Node.js 使用相同的浏览器缓存路径
ENV PLAYWRIGHT_BROWSERS_PATH=/root/.cache/ms-playwright

# 验证 Chromium 是否已安装（用于调试）
RUN ls -la /root/.cache/ms-playwright/ 2>/dev/null | head -5 || echo "浏览器目录检查完成"

# 阶段3: 运行阶段（使用基础镜像）
FROM base

WORKDIR /app

# 删除 Node.js（Chromium 已安装，不再需要 Node.js）
RUN apt-get remove -y nodejs npm && \
    apt-get autoremove -y && \
    rm -rf /var/lib/apt/lists/*

# 从构建阶段复制 JAR 文件
COPY --from=build /app/target/*.jar app.jar

# 创建用户数据目录
RUN mkdir -p /app/user-data

# 暴露端口
EXPOSE 24753

# 设置环境变量
ENV JAVA_OPTS="-Xmx512m -Xms256m"
# 阻止 Playwright Java 自动下载浏览器（我们已经手动安装了 Chromium）
ENV PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1
ENV PLAYWRIGHT_BROWSERS_PATH=/root/.cache/ms-playwright

# 运行应用
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]

