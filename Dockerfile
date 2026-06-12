FROM eclipse-temurin:17-jdk AS build
WORKDIR /workspace
COPY . .
RUN ./gradlew :app:bootJar --no-daemon -x test

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /workspace/app/build/libs/*.jar app.jar
ENV SOFTHSM2_CONF=/etc/softhsm2.conf
EXPOSE 8080
ENTRYPOINT ["java", "--add-exports=jdk.crypto.cryptoki/sun.security.pkcs11=ALL-UNNAMED", "--add-exports=java.base/sun.security.x509=ALL-UNNAMED", "-jar", "app.jar"]
