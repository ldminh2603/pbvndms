#!/usr/bin/env bash
set -euo pipefail
# Run on master after namespace.yaml. Requires kubectl and interactive terminal.
read -r -p 'Database host [172.68.69.173]: ' DBHOST
DBHOST=${DBHOST:-172.68.69.173}
read -r -p 'Database name [web_core]: ' DBNAME
DBNAME=${DBNAME:-web_core}
read -r -p 'Database username [app_user]: ' DBUSER
DBUSER=${DBUSER:-app_user}
read -r -s -p 'Database password: ' DBPASS; echo
read -r -s -p 'LDAP reader password: ' LDAPPASS; echo
DBCONN="Host=${DBHOST};Port=5432;Database=${DBNAME};Username=${DBUSER};Password=${DBPASS};Search Path=core,public;Timeout=120;Command Timeout=120;Pooling=true;"
# stdin avoids storing passwords in command-line arguments or a YAML file.
# Temporary local files are created with restrictive permissions and deleted.
umask 077
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
printf '%s' "$DBCONN" > "$TMP/db"
printf '%s' "$LDAPPASS" > "$TMP/ldap"
kubectl -n webcore create secret generic webcore-backend-secret \
  --from-file=WEBCORE_DATABASE_CONNECTION_STRING="$TMP/db" \
  --from-file=WEBCORE_DOMAIN_SERVICE_PASSWORD="$TMP/ldap" \
  --dry-run=client -o yaml | kubectl apply -f -
unset DBPASS LDAPPASS DBCONN
