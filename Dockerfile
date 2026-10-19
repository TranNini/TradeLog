FROM eclipse-temurin:21-jre

WORKDIR /app

COPY target/TradeLog-1.0-SNAPSHOT.jar app.jar

# The database, the pictures and the access code, all in one volume; the nightly backups in another one
ENV SPRING_DATASOURCE_URL=jdbc:sqlite:/app/data/tradelog.db \
    TRADELOG_IMAGES_DIR=/app/data/images \
    TRADELOG_ACCESS_CODE_FILE=/app/data/access-code.txt \
    TRADELOG_BACKUP_DIR=/app/backups
RUN mkdir -p /app/data /app/backups
VOLUME /app/data /app/backups

EXPOSE 8081

ENTRYPOINT ["java", "-jar", "app.jar"]
