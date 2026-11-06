#!/bin/sh
set -e

# Default to 8080 if Render or Docker does not set PORT
APP_PORT="${PORT:-8080}"

echo "=========================================================="
echo " Starting BloodConnect on Apache Tomcat 10.1"
echo " Target HTTP Port: ${APP_PORT}"
echo " Contexts: / (Root) and /BloodConnect"
echo "=========================================================="

# Dynamically configure Tomcat HTTP Connector port in server.xml
if [ -f /usr/local/tomcat/conf/server.xml ]; then
    sed -i "s/port=\"8080\"/port=\"${APP_PORT}\"/g" /usr/local/tomcat/conf/server.xml
fi

# Launch Tomcat in foreground
exec catalina.sh run

