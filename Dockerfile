# ---------- Build stage ----------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN ./mvnw -q -B dependency:go-offline
COPY src src
RUN ./mvnw -q -B -DskipTests package && \
    cp target/complaints-*.jar /workspace/app.jar

# ---------- Run stage ----------
FROM eclipse-temurin:21-jre
ENV TZ=Asia/Kolkata \
    JAVA_OPTS="-XX:+UseG1GC -XX:MaxRAMPercentage=75 -Duser.timezone=Asia/Kolkata"
WORKDIR /app
COPY --from=build /workspace/app.jar /app/app.jar
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
  CMD wget -q --spider http://localhost:8080/actuator/health || exit 1
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]

