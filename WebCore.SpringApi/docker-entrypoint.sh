#!/bin/sh
set -eu

certificate_file="${WEBCORE_LDAPS_CERT_FILE:-}"
if [ -n "$certificate_file" ] && [ -f "$certificate_file" ]; then
    certificate_alias="${WEBCORE_LDAPS_CERT_ALIAS:-dc01-ad-pbb-local}"
    keytool -delete -alias "$certificate_alias" -keystore "$JAVA_HOME/lib/security/cacerts" -storepass changeit >/dev/null 2>&1 || true
    keytool -importcert -noprompt -trustcacerts -alias "$certificate_alias" -file "$certificate_file" -keystore "$JAVA_HOME/lib/security/cacerts" -storepass changeit
fi

exec java -jar /app/app.jar
