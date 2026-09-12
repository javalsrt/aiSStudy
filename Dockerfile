# ===== 阶段1: 使用 Maven 编译后端 =====
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app

# 复制 pom.xml 和源码
COPY backend/pom.xml .
COPY backend/src ./src

# 配置阿里云 Maven 镜像（国内服务器依赖下载加速）
RUN mkdir -p /root/.m2 && printf '<?xml version="1.0" encoding="UTF-8"?>\n<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0">\n  <mirrors>\n    <mirror>\n      <id>aliyun</id>\n      <mirrorOf>central</mirrorOf>\n      <url>https://maven.aliyun.com/repository/public</url>\n    </mirror>\n  </mirrors>\n</settings>\n' > /root/.m2/settings.xml

# 编译打包 (跳过测试)
RUN mvn clean package -DskipTests -q

# ===== 阶段2: 运行 Spring Boot =====
FROM eclipse-temurin:17-jre
WORKDIR /app

# 从编译阶段复制 JAR 包
COPY --from=build /app/target/*.jar app.jar

# 暴露后端端口
EXPOSE 8080

# 启动命令
ENTRYPOINT ["java", "-jar", "app.jar"]
