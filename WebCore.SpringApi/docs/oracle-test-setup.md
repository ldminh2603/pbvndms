# Oracle notification test setup

From `Runtime/WebCore.SpringApi`, run the script by dot-sourcing it. It prompts
for secrets and sets them only in the current PowerShell session:

```powershell
. .\scripts\set-oracle-test-env.ps1
```

The script sets `WEBCORE_ORACLE_HOST`, `WEBCORE_ORACLE_PORT`,
`WEBCORE_ORACLE_SID`, `WEBCORE_ORACLE_SERVICE_NAME`,
`WEBCORE_ORACLE_SCHEMA_PASSWORD`, and `WEBCORE_ORACLE_MIGRATION_PASSWORD`.

Use the existing .NET provisioner to inspect/provision the Oracle schemas, then
update `def_source.db_messaging` to provider `ORACLE`. Do not commit or echo a
password. After the source change, restart Spring or reload Core before testing
notification endpoints.
