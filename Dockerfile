# ============================
#         BUILD STAGE
# ============================
FROM maven:3.9.9-eclipse-temurin-21 AS build

WORKDIR /app

# Copia únicamente el pom.xml primero
COPY pom.xml .

# Descarga dependencias (cachea en Docker)
RUN mvn -q -DskipTests dependency:go-offline

# Copia el código fuente
COPY src ./src

# Compila el proyecto
RUN mvn -q -DskipTests package


# ============================
#         RUN STAGE
# ============================
FROM eclipse-temurin:21-jre

WORKDIR /app

# Copia el .jar generado (cualquiera que esté dentro de target/)
COPY --from=build /app/target/*.jar /app/app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
