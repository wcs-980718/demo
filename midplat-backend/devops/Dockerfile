FROM eclipse-temurin:17-jre

ENV TZ=Asia/Shanghai
WORKDIR /app

COPY app.jar /app/app.jar
COPY entrypoint.sh /app/entrypoint.sh

ENV JAVA_OPTS="-Xms512m -Xmx1024m"

EXPOSE 8090

ENTRYPOINT ["sh", "/app/entrypoint.sh"]
