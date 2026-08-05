# Reseed the local database with demo accounts (see seed-demo.sql).
# Loads DB credentials from the root .env and pipes the SQL into the running
# Postgres container. Idempotent — safe to run repeatedly. Local helper only.

$ErrorActionPreference = "Stop"

$envPath  = Join-Path $PSScriptRoot "..\..\.env"
$sqlPath  = Join-Path $PSScriptRoot "seed-demo.sql"

# Load the root .env into this session.
Get-Content $envPath | Where-Object { $_ -match '^\s*[^#]' -and $_ -match '=' } | ForEach-Object {
    $name, $value = $_ -split '=', 2
    Set-Item -Path "Env:$($name.Trim())" -Value $value.Trim()
}

$dbUser      = if ($env:POSTGRES_USER) { $env:POSTGRES_USER } else { "picsou" }
$dbName      = if ($env:POSTGRES_DB)   { $env:POSTGRES_DB }   else { "picsou" }
$dbPassword  = $env:POSTGRES_PASSWORD

# Find the Postgres container (compose service name contains "db").
$container = (docker ps --filter "name=db" --format "{{.Names}}" | Select-Object -First 1)
if (-not $container) {
    throw "No running Postgres container found (looked for a container name containing 'db'). Is 'docker compose up' running?"
}

Write-Host "Seeding '$dbName' via container '$container'..." -ForegroundColor Cyan

Get-Content $sqlPath -Raw | docker exec -i -e "PGPASSWORD=$dbPassword" $container `
    psql -U $dbUser -d $dbName -v ON_ERROR_STOP=1

if ($LASTEXITCODE -ne 0) { throw "Seed failed (psql exit $LASTEXITCODE)." }
Write-Host "Seed complete." -ForegroundColor Green
