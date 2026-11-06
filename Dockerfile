# ==============================================================================
# Multi-Stage Dockerfile for BloodConnect
# Stage 1: Fast Build with Maven & Eclipse Temurin JDK 17
# Stage 2: Serve directly in Apache Tomcat 10.1 on Eclipse Temurin JDK 17
# ==============================================================================

# ------------------------------------------------------------------------------
# 1. BUILD STAGE
# ------------------------------------------------------------------------------
FROM maven:3.9.9-eclipse-temurin-17 AS builder

WORKDIR /app

# Copy pom.xml and source code
COPY pom.xml ./
COPY src ./src

# Compile and package WAR (fast build, no bloat)
RUN mvn clean package -DskipTests -B

# ------------------------------------------------------------------------------
# 2. RUNTIME STAGE
# ------------------------------------------------------------------------------
FROM tomcat:10.1-jdk17-temurin

LABEL maintainer="BloodConnect Development Team"
LABEL description="BloodConnect - Web-Based Blood Donor & Emergency Finder System"

WORKDIR /usr/local/tomcat

# Remove default sample web applications
RUN rm -rf /usr/local/tomcat/webapps/*

# Explode WAR directly into ROOT webapp directory for instant startup
RUN mkdir -p /usr/local/tomcat/webapps/ROOT
COPY --from=builder /app/target/BloodConnect.war /tmp/BloodConnect.war
RUN cd /usr/local/tomcat/webapps/ROOT && jar -xf /tmp/BloodConnect.war && rm /tmp/BloodConnect.war

# Also support /BloodConnect path seamlessly
RUN cp -r /usr/local/tomcat/webapps/ROOT /usr/local/tomcat/webapps/BloodConnect

# Install dynamic startup entrypoint script
COPY docker-entrypoint.sh /usr/local/bin/docker-entrypoint.sh
RUN sed -i 's/\r$//' /usr/local/bin/docker-entrypoint.sh && chmod +x /usr/local/bin/docker-entrypoint.sh

# Render dynamic $PORT
ENV PORT=8080

EXPOSE 8080

ENTRYPOINT ["/usr/local/bin/docker-entrypoint.sh"]
