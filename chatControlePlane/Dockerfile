# Build stage
FROM maven:3-eclipse-temurin-26 AS build
WORKDIR /app

# Cache dependency downloads — copy pom.xml first so Docker caches this
# layer and only re-downloads when pom.xml changes (not on every src change)
COPY pom.xml .
RUN mvn dependency:resolve dependency:resolve-plugins -B

# Build the application
COPY src ./src
RUN mvn package -DskipTests -B

# Runtime stage
FROM eclipse-temurin:26-jre-alpine
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar

EXPOSE 8080 9090

ENTRYPOINT ["java", "-jar", "app.jar"]
