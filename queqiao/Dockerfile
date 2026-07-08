# Stage 1: 构建
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app

COPY pom.xml .
RUN mvn dependency:resolve

COPY src ./src
COPY templates ./templates
RUN mvn package -DskipTests

# Stage 2: 运行
FROM eclipse-temurin:17-jre
WORKDIR /app

COPY --from=build /app/target/queqiao-*.jar app.jar

# 健康检查
HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
  CMD curl -s http://localhost:8081/actuator/health | grep UP || exit 1

EXPOSE 8081

ENTRYPOINT ["java", "-jar", "app.jar", "--spring.profiles.active=prod"]
