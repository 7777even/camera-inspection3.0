# Stage 1: 构建
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app

# 先下载依赖（利用 Docker 缓存层）
COPY pom.xml .
RUN mvn dependency:resolve

# 编译打包
COPY src ./src
COPY templates ./templates
RUN mvn package -DskipTests

# Stage 2: 运行
FROM eclipse-temurin:17-jre
WORKDIR /app

# 创建数据目录
RUN mkdir -p /data/screenshots /data/ledger

# 复制构建产物和模板
COPY --from=build /app/target/enviro-brain-*.jar app.jar
COPY --from=build /app/templates ./templates

# 健康检查
HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
  CMD curl -s http://localhost:8080/actuator/health | grep UP || exit 1

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar", "--spring.profiles.active=prod"]
