
FROM maven:3.9-eclipse-temurin-17 AS build

WORKDIR /app
COPY pom.xml .
COPY src ./src

RUN mvn -B -DskipTests package

FROM eclipse-temurin:17-jre-jammy

RUN apt-get update && apt-get install -y --no-install-recommends \
    xvfb \
    x11vnc \
    openbox \
    novnc \
    websockify \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY --from=build /app/target/qr-shield-swing-1.0.0.jar ./app.jar
COPY start.sh ./start.sh

RUN chmod +x ./start.sh

ENV DISPLAY=:99
ENV PORT=10000

EXPOSE 10000

CMD ["./start.sh"]
