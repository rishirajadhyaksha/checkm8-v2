WORKDIR /app
COPY --from=build /app/target/chess-server-1.0.0.jar app.jar

EXPOSE 8080

CMD java -Dserver.port=$PORT -jar app.jar