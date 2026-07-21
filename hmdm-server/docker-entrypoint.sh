#!/bin/bash
set -e

# ================================================================
#  Headwind MDM - Docker Entrypoint
#  Reads env vars and configures Tomcat context XML at startup
# ================================================================

TOMCAT_DIR=/usr/local/tomcat
WORK_DIR=$TOMCAT_DIR/work
CONTEXT_FILE=$TOMCAT_DIR/conf/Catalina/localhost/ROOT.xml

echo "=== Headwind MDM Docker Entrypoint ==="

# ── Admin Password (Headwind MDM uses MD5, not BCrypt!) ───────
ADMIN_PASSWORD='Sravan@123'

# ── Parse DB_URL (Spring Boot format) ─────────────────────────
# Expected: jdbc:postgresql://HOST:PORT/DATABASE?params
# For Aiven PostgreSQL, SSL is required (sslmode=require)
DB_SSLMODE="require"
if [ -n "$DB_URL" ]; then
    echo "Parsing DB_URL using bash parameter expansion..."
    DB_REMAINDER="${DB_URL#jdbc:postgresql://}"
    DB_HOST_PORT="${DB_REMAINDER%%/*}"
    DB_HOST="${DB_HOST_PORT%:*}"
    DB_PORT="${DB_HOST_PORT#*:}"
    DB_QUERY="${DB_REMAINDER#*/}"
    DB_NAME="${DB_QUERY%%\?*}"

    # Preserve sslmode from URL if present
    if echo "$DB_URL" | grep -q "sslmode="; then
        DB_SSLMODE=$(echo "$DB_URL" | sed 's/.*sslmode=//' | sed 's/&.*//')
    fi

    # Fallbacks if extraction fails
    [ -z "$DB_HOST" ] && DB_HOST="${SQL_HOST:-localhost}"
    [ -z "$DB_PORT" ] && DB_PORT="${SQL_PORT:-5432}"
    [ -z "$DB_NAME" ] && DB_NAME="${SQL_BASE:-hmdm}"

    echo "  Host: $DB_HOST"
    echo "  Port: $DB_PORT"
    echo "  Database: $DB_NAME"
    echo "  SSL mode: $DB_SSLMODE"
else
    echo "DB_URL not set, using individual SQL_* vars..."
    DB_HOST="${SQL_HOST:-localhost}"
    DB_PORT="${SQL_PORT:-5432}"
    DB_NAME="${SQL_BASE:-hmdm}"
fi

DB_USER="${DB_USERNAME:-${SQL_USER:-hmdm}}"
DB_PASS="${DB_PASSWORD:-${SQL_PASS:-changeme}}"

# ── Domain / URL ──────────────────────────────────────────────
BASE_DOMAIN="${BASE_DOMAIN:-localhost}"
PROTOCOL="${PROTOCOL:-http}"
BASE_URL="${PROTOCOL}://${BASE_DOMAIN}"

# ── Shared Secret ────────────────────────────────────────────
SHARED_SECRET="${SHARED_SECRET:-changeme-C3z9vi54}"

# ── Force Reconﬁgure ────────────────────────────────────────
FORCE="${FORCE_RECONFIGURE:-false}"

echo "Base URL: $BASE_URL"
echo "Force reconfigure: $FORCE"

# ── Create Context XML ───────────────────────────────────────
create_context() {
    echo "Creating Tomcat context XML..."
    cat > "$CONTEXT_FILE" << EOF
<?xml version="1.0" encoding="UTF-8"?>
<Context>
    <Parameter name="JDBC.driver"   value="org.postgresql.Driver"/>
    <Parameter name="JDBC.url"      value="jdbc:postgresql://${DB_HOST}:${DB_PORT}/${DB_NAME}?sslmode=${DB_SSLMODE}"/>
    <Parameter name="JDBC.username" value="${DB_USER}"/>
    <Parameter name="JDBC.password" value="${DB_PASS}"/>

    <Parameter name="base.directory" value="${WORK_DIR}"/>
    <Parameter name="files.directory" value="${WORK_DIR}/files"/>
    <Parameter name="plugins.files.directory" value="${WORK_DIR}/plugins"/>

    <Parameter name="base.url" value="${BASE_URL}"/>
    <Parameter name="usage.scenario" value="private"/>
    <Parameter name="secure.enrollment" value="0"/>
    <Parameter name="hash.secret" value="${SHARED_SECRET}"/>

    <Parameter name="role.orgadmin.id" value="2"/>
    <Parameter name="initialization.completion.signal.file" value="${WORK_DIR}/init-complete.flag"/>
    <Parameter name="plugin.devicelog.persistence.config.class" value="com.hmdm.plugins.devicelog.persistence.postgres.DeviceLogPostgresPersistenceConfiguration"/>
    <Parameter name="aapt.command" value="aapt"/>
    <Parameter name="log4j.config" value="file://${WORK_DIR}/log4j-hmdm.xml"/>
    <Parameter name="plugin.photo.enable.places" value="false"/>
    <Parameter name="mqtt.server.uri" value="0.0.0.0:31000"/>
    <Parameter name="device.fast.search.chars" value="5"/>
    <Parameter name="mqtt.auth" value="1"/>
    <Parameter name="mqtt.external" value="0"/>
    <Parameter name="sql.init.script.path" value="${WORK_DIR}/init.sql"/>

    <!-- SMTP (optional, configure for password recovery) -->
    <Parameter name="smtp.host" value=""/>
    <Parameter name="smtp.port" value="587"/>
    <Parameter name="smtp.ssl" value="0"/>
    <Parameter name="smtp.starttls" value="0"/>
    <Parameter name="smtp.username" value=""/>
    <Parameter name="smtp.password" value=""/>
    <Parameter name="smtp.from" value=""/>
</Context>
EOF
    echo "Context XML created at $CONTEXT_FILE"
}

# ── Create log4j config if missing ───────────────────────────
create_log4j() {
    if [ ! -f "$WORK_DIR/log4j-hmdm.xml" ]; then
        cat > "$WORK_DIR/log4j-hmdm.xml" << 'EOF'
<?xml version="1.0" encoding="UTF-8" ?>
<!DOCTYPE log4j:configuration SYSTEM "log4j.dtd">
<log4j:configuration xmlns:log4j="http://jakarta.apache.org/log4j/">
    <appender name="FILE" class="org.apache.log4j.RollingFileAppender">
        <param name="File" value="/usr/local/tomcat/work/logs/hmdm.log"/>
        <param name="MaxFileSize" value="10MB"/>
        <param name="MaxBackupIndex" value="5"/>
        <layout class="org.apache.log4j.PatternLayout">
            <param name="ConversionPattern" value="%d{yyyy-MM-dd HH:mm:ss} %-5p %c{1}:%L - %m%n"/>
        </layout>
    </appender>
    <root>
        <priority value="INFO"/>
        <appender-ref ref="FILE"/>
    </root>
</log4j:configuration>
EOF
    fi
}

# ── Create work directories ─────────────────────────────────
for DIR in cache files plugins logs; do
    mkdir -p "$WORK_DIR/$DIR"
done

# ── Check if we need to (re)create config ───────────────────
if [ ! -f "$CONTEXT_FILE" ] || [ "$FORCE" = "true" ]; then
    create_context
else
    echo "Context XML already exists (set FORCE_RECONFIGURE=true to recreate)"
fi

create_log4j

# ── Wait for PostgreSQL (include port and SSL for Aiven) ────
echo "Waiting for PostgreSQL at ${DB_HOST}:${DB_PORT} (SSL mode: ${DB_SSLMODE})..."
until PGPASSWORD="$DB_PASS" PGSSLMODE="$DB_SSLMODE" psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -c '\q' 2>/dev/null; do
    echo "  Database not ready yet, retrying in 5s..."
    sleep 5
done
echo "Database is ready!"

# ── Start Tomcat in background ──────────────────────────────
echo "Starting Tomcat in background..."
catalina.sh start

# ── Wait for users table, then set admin password ───────────
echo "Waiting for admin user table to be initialized (this may take a minute on first run)..."
PASSWORD_SET=false
for i in $(seq 1 60); do
  if PGPASSWORD="$DB_PASS" PGSSLMODE="$DB_SSLMODE" psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -t -c "SELECT 1 FROM users WHERE id=1;" 2>/dev/null | grep -q "1"; then
    echo "Setting admin password (using MD5 for Headwind MDM compatibility)..."
    RESULT=$(PGPASSWORD="$DB_PASS" PGSSLMODE="$DB_SSLMODE" psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -c "UPDATE users SET password=MD5('$ADMIN_PASSWORD'), passwordReset=false WHERE id=1;" 2>&1) || true
    echo "Password update result: $RESULT"
    echo "Admin password set successfully. Login with your admin email / $ADMIN_PASSWORD"
    PASSWORD_SET=true
    break
  fi
  sleep 3
done

if [ "$PASSWORD_SET" = "false" ]; then
  echo "WARNING: Could not set admin password - users table was not found within timeout." >&2
  echo "The application may still be initializing. Check the logs above." >&2
fi

# ── Follow Tomcat logs to keep container running ────────────
tail -F $TOMCAT_DIR/logs/catalina.out
