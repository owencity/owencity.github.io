FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /build/server
COPY server/pom.xml ./pom.xml
COPY server/src ./src
COPY public/devdesk.html public/devdesk.css public/devdesk.js public/devdesk-core.mjs /build/public/
RUN mvn -B verify

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN groupadd --system devdesk && useradd --system --gid devdesk --home /app devdesk
COPY --from=build /build/server/target/devdesk-0.1.0.jar app.jar
USER devdesk
ENV BIND_ADDRESS=0.0.0.0
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=65", "-jar", "/app/app.jar"]
