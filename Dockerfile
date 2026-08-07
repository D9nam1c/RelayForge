FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY . .
ARG MODULE
RUN MAVEN_USER_HOME=/tmp/relayforge-maven ./mvnw -q -DskipTests -pl "${MODULE}" -am package \
    && cp "${MODULE}"/target/*.jar /relayforge.jar

FROM eclipse-temurin:25-jre
RUN useradd --system --uid 10001 --create-home relayforge
USER 10001
COPY --from=build /relayforge.jar /app/relayforge.jar
EXPOSE 8080
ENTRYPOINT ["java","-XX:MaxRAMPercentage=75","-XX:+ExitOnOutOfMemoryError","-jar","/app/relayforge.jar"]
