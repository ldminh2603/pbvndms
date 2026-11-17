# Build the Spring API with the JDK version required by pom.xml.
FROM maven:3.9.10-eclipse-temurin-21 AS build
WORKDIR /workspace

COPY pom.xml ./
RUN mvn --batch-mode --no-transfer-progress dependency:go-offline

COPY src ./src
RUN mvn --batch-mode --no-transfer-progress package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app

COPY --from=build /workspace/target/webcore-spring-api-*.jar app.jar
COPY docker-entrypoint.sh /usr/local/bin/webcore-spring-api-entrypoint
RUN chmod +x /usr/local/bin/webcore-spring-api-entrypoint

EXPOSE 8080

ENTRYPOINT ["/usr/local/bin/webcore-spring-api-entrypoint"]
