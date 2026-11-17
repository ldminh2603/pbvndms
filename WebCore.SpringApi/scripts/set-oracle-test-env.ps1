<#!
Sets Oracle test variables for the current PowerShell session.

Run with dot-sourcing so variables remain available afterwards:
  . .\scripts\set-oracle-test-env.ps1
#>
param(
    [string]$HostName = '192.168.116.131',
    [int]$Port = 1521,
    [string]$Sid = 'cdb1',
    [string]$ServiceName = '',
    [string]$SchemaUser = 'WEBCORE_MESSAGING'
)

$schemaPassword = Read-Host 'Oracle schema password' -AsSecureString
$migrationPassword = Read-Host 'Oracle migration/SYS password (press Enter to reuse schema password)' -AsSecureString

function Convert-SecureStringToPlainText([Security.SecureString]$Value) {
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($Value)
    try { return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer) }
}

$env:WEBCORE_ORACLE_HOST = $HostName
$env:WEBCORE_ORACLE_PORT = "$Port"
$env:WEBCORE_ORACLE_SID = $Sid
$env:WEBCORE_ORACLE_SERVICE_NAME = $ServiceName
$env:WEBCORE_ORACLE_SCHEMA_PASSWORD = Convert-SecureStringToPlainText $schemaPassword
$env:WEBCORE_ORACLE_MIGRATION_PASSWORD = if ($migrationPassword.Length -eq 0) { $env:WEBCORE_ORACLE_SCHEMA_PASSWORD } else { Convert-SecureStringToPlainText $migrationPassword }

Write-Host 'Oracle test variables have been set for this PowerShell session only.'
Write-Host 'Password values are not printed or written to disk.'
