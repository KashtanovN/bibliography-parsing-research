FROM maven:3.9.11-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B dependency:go-offline -DexcludeScope=test
COPY src ./src
RUN mvn -B -Dmaven.test.skip=true package

FROM eclipse-temurin:17-jre-alpine
RUN apk add --no-cache curl && addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build --chown=app:app /build/target/bibliography-1.0.0.jar app.jar
USER app
ENV SERVER_ADDRESS=0.0.0.0
EXPOSE 8000
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
