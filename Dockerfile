WORKDIR /app
COPY --from=build /app/target/chess-server-1.0.0.jar app.jar

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java -Dserver.port=$PORT -jar app.jar"]