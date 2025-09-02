# Stage 1: build
FROM maven:3.9.4-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
# build jar (skip tests for faster builds; remove -DskipTests in CI if you have tests)
RUN mvn -B package -DskipTests

# Stage 2: runtime
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
# copy jar (artifactId-version from your pom)
COPY --from=build /app/target/*.jar app.jar

# optional: set timezone, locale, etc.
ENV JAVA_OPTS=""

EXPOSE 8080
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
