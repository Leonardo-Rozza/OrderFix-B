# ============================
#       BUILD STAGE
# ============================
FROM maven:3.9.9-eclipse-temurin-21 AS build

WORKDIR /app

# Copiar solo el pom.xml primero → permite usar la cache
COPY pom.xml .

# Descargar dependencias sin compilar
RUN mvn -q -DskipTests dependency:go-offline

# Copiar el código fuente
COPY src ./src

# Compilar (los tests corren en CI/local, no en el build de la imagen)
RUN mvn -q -DskipTests -Dmaven.test.skip=true package

# ============================
#        RUN STAGE
# ============================
FROM eclipse-temurin:21-jre

WORKDIR /app

COPY --from=build /app/target/*.jar app.jar

# Correr sin privilegios
RUN useradd --system --no-create-home appuser
USER appuser

EXPOSE 8080

# MaxRAMPercentage=75: en una instancia de 512 MB el default de la JVM (25%)
# deja un heap de ~128 MB, demasiado chico para Spring Boot.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
