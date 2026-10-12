FROM eclipse-temurin:21-jre

WORKDIR /app

COPY target/TradeLog-1.0-SNAPSHOT.jar app.jar

# The database, the pictures and the access code, all in one volume
ENV SPRING_DATASOURCE_URL=jdbc:sqlite:/app/data/tradelog.db \
    TRADELOG_IMAGES_DIR=/app/data/images \
    TRADELOG_ACCESS_CODE_FILE=/app/data/access-code.txt
RUN mkdir -p /app/data
VOLUME /app/data

EXPOSE 8081

ENTRYPOINT ["java", "-jar", "app.jar"]
