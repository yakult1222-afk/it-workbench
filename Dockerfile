# ============ 阶段 1：构建前端 ============
FROM node:20-alpine AS frontend-build
WORKDIR /app/frontend
COPY frontend/package*.json ./
RUN npm ci --no-fund --no-audit
COPY frontend/ ./
RUN npm run build

# ============ 阶段 2：构建后端（内嵌前端产物，单端口运行） ============
FROM maven:3.9-eclipse-temurin-17 AS backend-build
WORKDIR /app/backend
COPY backend/pom.xml ./
RUN mvn -B -q dependency:go-offline
COPY backend/src ./src
COPY --from=frontend-build /app/frontend/dist ./src/main/resources/static
RUN mvn -B -q clean package -DskipTests

# ============ 阶段 3：运行 ============
FROM eclipse-temurin:17-jre
WORKDIR /app
ENV TZ=Asia/Shanghai
COPY --from=backend-build /app/backend/target/it-workbench-1.0.0.jar app.jar
EXPOSE 8080
# 数据库连接通过环境变量覆盖：MYSQL_HOST / MYSQL_PORT / MYSQL_DB / MYSQL_USER / MYSQL_PASSWORD
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
