# SQL Server and Oracle test sources

Spring keeps only one Core Base connection in `WEBCORE_DATABASE_CONNECTION_STRING`.
Do not add SQL Server or Oracle connection strings to `application.yml`.

For testing a business source such as notifications, update the existing
`core.def_source` row named `db_messaging`. Java reads this row exactly as .NET
does. Use a secret manager or an environment-substituted deployment script for
the password; do not commit it.

## SQL Server

```json
{
  "data_source_type": "MSSQLSERVER",
  "data_source_connection": "Server=<host>,1433;Database=<database>;Username=<user>;Password=<password>;Encrypt=False;TrustServerCertificate=True;"
}
```

`SQLSERVER`, `MSSQLSERVER`, and `MSSQL` are accepted by Spring. Notification
tables are read from `[app].[notification_inbox]` and `[app].[notification_history]`.

## Oracle

```json
{
  "data_source_type": "ORACLE",
  "data_source_connection": "Host=<host>;Port=1521;Service Name=<service>;Username=<user>;Password=<password>;"
}
```

Notification tables are read from `WEBCORE_MESSAGING.NOTIFICATION_INBOX` and
`WEBCORE_MESSAGING.NOTIFICATION_HISTORY`, matching the .NET provisioner schema map.

After changing `def_source`, invoke `POST /api/cluster/reload-system` on the
running Java API (or restart it) to clear the cached datasource pool.
