# ==============================================================================
# Multi-Stage Dockerfile for BloodConnect
# Stage 1: Build WAR with Maven & Eclipse Temurin JDK 17
# Stage 2: Serve WAR with Apache Tomcat 10.1 on Eclipse Temurin JDK 17
# ==============================================================================

# ------------------------------------------------------------------------------
# 1. BUILD STAGE
# ------------------------------------------------------------------------------
FROM maven:3.9.9-eclipse-temurin-17 AS builder

WORKDIR /app

# Cache Maven dependencies layer
COPY pom.xml ./
RUN mvn dependency:go-offline -B

# Copy project source code and compile WAR
COPY src ./src
RUN mvn clean package -DskipTests -B

# ------------------------------------------------------------------------------
# 2. RUNTIME STAGE
# ------------------------------------------------------------------------------
FROM tomcat:10.1-jdk17-temurin

LABEL maintainer="BloodConnect Development Team"
LABEL description="BloodConnect - Web-Based Blood Donor & Emergency Finder System"

WORKDIR /usr/local/tomcat

# Remove default sample web applications for clean production environment
RUN rm -rf /usr/local/tomcat/webapps/*

# Deploy WAR to both ROOT (for https://domain/) and BloodConnect (for https://domain/BloodConnect/)
COPY --from=builder /app/target/BloodConnect.war /usr/local/tomcat/webapps/ROOT.war
COPY --from=builder /app/target/BloodConnect.war /usr/local/tomcat/webapps/BloodConnect.war

# Install dynamic startup entrypoint script
COPY docker-entrypoint.sh /usr/local/bin/docker-entrypoint.sh
RUN sed -i 's/\r$//' /usr/local/bin/docker-entrypoint.sh && chmod +x /usr/local/bin/docker-entrypoint.sh

# Render binds to dynamic $PORT (default 8080 for local docker runs)
ENV PORT=8080

EXPOSE 8080

ENTRYPOINT ["/usr/local/bin/docker-entrypoint.sh"]

