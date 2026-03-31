# Build stage
FROM gradle:8-jdk17 AS build
WORKDIR /project
COPY build.gradle settings.gradle ./
COPY gradle ./gradle
COPY src ./src
RUN gradle shadowJar --no-daemon

# Runtime stage
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=build /project/build/libs/cwfgw-*-all.jar app.jar

EXPOSE 8080

ENV JAVA_OPTS="-Xmx512m -Xms256m"
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
