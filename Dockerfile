# ================================================================
#  STAGE 1: Build the WAR with Maven + JDK 11
# ================================================================
FROM maven:3.8-eclipse-temurin-11 AS builder

WORKDIR /build

# Install Node.js 18 (required by frontend-maven-plugin)
RUN apt-get update \
    && apt-get install -y curl \
    && curl -fsSL https://deb.nodesource.com/setup_18.x | bash - \
    && apt-get install -y nodejs \
    && npm install -g grunt-cli \
    && rm -rf /var/lib/apt/lists/*

# Copy all project files
COPY common ./common
COPY jwt ./jwt
COPY notification ./notification
COPY plugins ./plugins
COPY swagger ./swagger
COPY server ./server
COPY pom.xml .

# Create empty build.properties for Maven resource filtering
RUN touch server/build.properties

# Build the server module and its dependencies (skip tests for speed)
RUN mvn clean install -DskipTests -pl server -am -q

# ================================================================
#  STAGE 2: Run in Tomcat 9 with JDK 11
# ================================================================
FROM tomcat:9-jdk11-temurin-jammy

RUN apt-get update \
    && apt-get install -y \
        aapt \
        wget \
        sed \
        postgresql-client \
    && rm -rf /var/lib/apt/lists/*

# Copy the built WAR from builder stage
COPY --from=builder /build/server/target/launcher.war /usr/local/tomcat/webapps/ROOT.war

# Remove default webapps
RUN rm -rf /usr/local/tomcat/webapps/docs \
           /usr/local/tomcat/webapps/examples \
           /usr/local/tomcat/webapps/host-manager \
           /usr/local/tomcat/webapps/manager

# Create work directories
RUN mkdir -p /usr/local/tomcat/work/cache \
             /usr/local/tomcat/work/files \
             /usr/local/tomcat/work/plugins \
             /usr/local/tomcat/work/logs \
             /usr/local/tomcat/conf/Catalina/localhost

# Copy the Docker entrypoint
COPY docker-entrypoint.sh /
RUN chmod +x /docker-entrypoint.sh

EXPOSE 8080

ENTRYPOINT ["/docker-entrypoint.sh"]
