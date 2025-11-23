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

# Compilar
RUN mvn -q -DskipTests package

# ============================
#        RUN STAGE
# ============================
FROM eclipse-temurin:21-jre

WORKDIR /app

COPY --from=build /app/target/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
