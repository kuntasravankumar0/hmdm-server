#!/bin/sh
HMDM_DIR=/opt/hmdm
TEMPLATE_DIR=$HMDM_DIR/templates
TOMCAT_DIR=/usr/local/tomcat
BASE_DIR=$TOMCAT_DIR/work
CACHE_DIR=$BASE_DIR/cache
PASSWORD=123456
ADMIN_PASSWORD='Sravan@123'

for DIR in cache files plugins logs; do
   [ -d "$BASE_DIR/$DIR" ] || mkdir "$BASE_DIR/$DIR"
done

if [ ! -z "$LOCAL_IP" ]; then
    EXISTS=`grep $BASE_DOMAIN /etc/hosts`
    if [ -z "$EXISTS" ] || [ "$FORCE_RECONFIGURE" = "true" ]; then
        grep -v $BASE_DOMAIN /etc/hosts > /etc/hosts~
	cp /etc/hosts~ /etc/hosts
	echo "$LOCAL_IP $BASE_DOMAIN" >> /etc/hosts
	rm -f /etc/hosts~
    fi
fi

HMDM_WAR="$(basename -- $HMDM_URL)"

if [ -f "$CACHE_DIR/$HMDM_WAR" ] && [ "$FORCE_RECONFIGURE" = "true" ]; then
    rm -f $CACHE_DIR/$HMDM_WAR
fi

if [ ! -f "$CACHE_DIR/$HMDM_WAR" ]; then
    if ! wget $DOWNLOAD_CREDENTIALS $HMDM_URL -O $CACHE_DIR/$HMDM_WAR; then
        echo "Failed to retrieve $HMDM_URL!"
        exit 1
    fi
fi

if [ ! -f "$TOMCAT_DIR/webapps/ROOT.war" ] || [ "$FORCE_RECONFIGURE" = "true" ]; then
    cp $CACHE_DIR/$HMDM_WAR $TOMCAT_DIR/webapps/ROOT.war
fi

$HMDM_DIR/update-web-app-docker.sh

if [ ! -f "$BASE_DIR/log4j.xml" ] || [ "$FORCE_RECONFIGURE" = "true" ]; then
    cp $TEMPLATE_DIR/conf/log4j_template.xml $BASE_DIR/log4j-hmdm.xml
fi

if [ ! -d "$BASE_DIR/emails" ] || [ "$FORCE_RECONFIGURE" = "true" ]; then
    cp -r $TEMPLATE_DIR/emails $BASE_DIR/emails
fi

if [ ! -d $TOMCAT_DIR/conf/Catalina/localhost ]; then
    mkdir -p $TOMCAT_DIR/conf/Catalina/localhost
fi

if [ ! -f "$TOMCAT_DIR/conf/Catalina/localhost/ROOT.xml" ] || [ "$FORCE_RECONFIGURE" = "true" ]; then
    cat $TEMPLATE_DIR/conf/context_template.xml | sed "s|_SQL_HOST_|$SQL_HOST|g; s|_SQL_PORT_|$SQL_PORT|g; s|_SQL_BASE_|$SQL_BASE|g; s|_SQL_USER_|$SQL_USER|g; s|_SQL_PASS_|$SQL_PASS|g; s|_PROTOCOL_|$PROTOCOL|g; s|_BASE_DOMAIN_|$BASE_DOMAIN|g; s|_SHARED_SECRET_|$SHARED_SECRET|g;" > $TOMCAT_DIR/conf/Catalina/localhost/ROOT.xml 
fi

for DIR in cache files plugins logs; do
   [ -d "$BASE_DIR/$DIR" ] || mkdir "$BASE_DIR/$DIR"
done

if [ "$INSTALL_LANGUAGE" != "ru" ]; then
    INSTALL_LANGUAGE=en
fi

if [ ! -f "$BASE_DIR/init.sql" ] || [ "$FORCE_RECONFIGURE" = "true" ]; then
    cat $TEMPLATE_DIR/sql/hmdm_init.$INSTALL_LANGUAGE.sql | sed "s|_ADMIN_EMAIL_|$ADMIN_EMAIL|g; s|_HMDM_VERSION_|$CLIENT_VERSION|g; s|_HMDM_VARIANT_|$HMDM_VARIANT|g" > $BASE_DIR/init1.sql
fi

# Extract all plugin/APK download URLs from the init SQL
FILES_TO_DOWNLOAD=$(grep https://h-mdm.com $BASE_DIR/init1.sql | awk '{ print $4 }' | sed "s/'//g; s/)//g; s/,//g" | sort -u)

# Download files and build a sed expression that replaces h-mdm.com with local URL
# only for files that were SUCCESSFULLY downloaded.
# Files that fail keep the original h-mdm.com URL so devices download directly from there.
REPLACE_CMD="s|https://h-mdm.com|$PROTOCOL://$BASE_DOMAIN|g"
SKIPPED_FILES=""

cd $BASE_DIR/files
for FILE in $FILES_TO_DOWNLOAD; do
    FILENAME=$(basename $FILE)
    if [ ! -f "$BASE_DIR/files/$FILENAME" ]; then
        echo "Downloading $FILENAME..."
        if wget --timeout=30 --tries=3 --wait=5 "$FILE"; then
            echo "Downloaded $FILENAME successfully"
        else
            echo "WARNING: Failed to download $FILE from h-mdm.com"
            echo "Devices will download $FILENAME directly from h-mdm.com"
            SKIPPED_FILES="$SKIPPED_FILES $FILE"
        fi
    fi
done

# Generate init.sql: replace h-mdm.com with local URL, UNLESS the file failed to download
if [ -n "$SKIPPED_FILES" ]; then
    # For failed files, keep the h-mdm.com URL in the SQL
    # Build a sed command that replaces all h-mdm.com URLs EXCEPT the failed ones
    cp $BASE_DIR/init1.sql $BASE_DIR/init.sql
    MARKER_COUNTER=0
    for FILE_PATH in $SKIPPED_FILES; do
        # Temporarily mark failed URLs so they don't get replaced
        FAILED_MARKER="__HMDM_FALLBACK_${MARKER_COUNTER}__"
        sed -i "s|$FILE_PATH|$FAILED_MARKER|g" $BASE_DIR/init.sql
        MARKER_COUNTER=$((MARKER_COUNTER + 1))
    done
    # Replace remaining h-mdm.com URLs with local
    sed -i "s|https://h-mdm.com|$PROTOCOL://$BASE_DOMAIN|g" $BASE_DIR/init.sql
    # Restore the fallback markers to original h-mdm.com URLs
    MARKER_COUNTER=0
    for FILE_PATH in $SKIPPED_FILES; do
        FAILED_MARKER="__HMDM_FALLBACK_${MARKER_COUNTER}__"
        sed -i "s|$FAILED_MARKER|$FILE_PATH|g" $BASE_DIR/init.sql
        MARKER_COUNTER=$((MARKER_COUNTER + 1))
    done
else
    # All files downloaded successfully - simple replacement
    cat $BASE_DIR/init1.sql | sed "s|https://h-mdm.com|$PROTOCOL://$BASE_DOMAIN|g" > $BASE_DIR/init.sql
fi

rm $BASE_DIR/init1.sql

# jks is always created from the certificates
if [ "$PROTOCOL" = "https" ]; then
    if [ "$HTTPS_LETSENCRYPT" = "true" ]; then
	HTTPS_CERT_PATH=/etc/letsencrypt/live/$BASE_DOMAIN
        echo "Looking for SSL keys in $HTTPS_CERT_PATH..."
	# If started by docker-compose, let's wait until certbot completes
	until [ -f $HTTPS_CERT_PATH/$HTTPS_PRIVKEY ]; do
            echo "Keys not found, waiting..."
	    sleep 5
        done
    fi

    openssl pkcs12 -export -out $TOMCAT_DIR/ssl/hmdm.p12 -inkey $HTTPS_CERT_PATH/$HTTPS_PRIVKEY -in $HTTPS_CERT_PATH/$HTTPS_CERT -certfile $HTTPS_CERT_PATH/$HTTPS_FULLCHAIN -password pass:$PASSWORD
    keytool -importkeystore -destkeystore $TOMCAT_DIR/ssl/hmdm.jks -srckeystore $TOMCAT_DIR/ssl/hmdm.p12 -srcstoretype PKCS12 -srcstorepass $PASSWORD -deststorepass $PASSWORD -noprompt    
fi

# Waiting for the database (SSL required for Aiven PostgreSQL)
until PGPASSWORD=$SQL_PASS PGSSLMODE=require psql -h "$SQL_HOST" -p "$SQL_PORT" -U "$SQL_USER" -d "$SQL_BASE" -c '\q'; do
  echo "Waiting for the PostgreSQL database..."
  sleep 5
done
echo "Database is ready!"

# Avoid delays due to an issue with a random number
cp /opt/java/openjdk/conf/security/java.security /tmp/java.security
cat /tmp/java.security | sed "s|securerandom.source=file:/dev/random|securerandom.source=file:/dev/urandom|g" > /opt/java/openjdk/conf/security/java.security
rm /tmp/java.security

# Start Tomcat in background (app will run init.sql to create tables)
echo "Starting Tomcat in background..."
catalina.sh start

# Wait for the users table to exist, then set the admin password
echo "Waiting for admin user table to be initialized (this may take a minute on first run)..."
PASSWORD_SET=false
for i in $(seq 1 60); do
  if PGPASSWORD=$SQL_PASS PGSSLMODE=require psql -h "$SQL_HOST" -p "$SQL_PORT" -U "$SQL_USER" -d "$SQL_BASE" -t -c "SELECT 1 FROM users WHERE id=1;" 2>/dev/null | grep -q "1"; then
    echo "Setting admin password..."
    PGPASSWORD=$SQL_PASS PGSSLMODE=require psql -h "$SQL_HOST" -p "$SQL_PORT" -U "$SQL_USER" -d "$SQL_BASE" -c "UPDATE users SET password=MD5('$ADMIN_PASSWORD'), passwordReset=false WHERE id=1;" 2>&1
    echo "Admin password configured successfully."
    PASSWORD_SET=true
    break
  fi
  sleep 3
done

if [ "$PASSWORD_SET" = "false" ]; then
  echo "WARNING: Could not set admin password - users table was not found within timeout." >&2
  echo "The application may still be initializing. Check the logs above." >&2
fi

# Follow Tomcat logs to keep container running
tail -F $TOMCAT_DIR/logs/catalina.out
