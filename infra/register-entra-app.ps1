# Registers the "HighQ Migration" app in Microsoft Entra ID so users can "Sign in with Microsoft" for real.
# Run it yourself, signed in with an account allowed to register apps in the tenant (an Azure / Entra admin).
# Writes the client id and secret into ..\.env (GRAPH_* lines); the secret is never printed.
#
#   .\infra\register-entra-app.ps1 -RenderUrl https://highq-migration.onrender.com
#
param(
    [string] $DisplayName = 'HighQ Migration',
    [string] $RenderUrl,                                  # optional: adds the hosted callback too
    [ValidateSet('AzureADMultipleOrgs', 'AzureADMyOrg')]
    [string] $Audience = 'AzureADMultipleOrgs'            # MultipleOrgs: users of other organisations' tenants can sign in (after their admin consents)
)
$ErrorActionPreference = 'Stop'
if (-not (Get-Command az -ErrorAction SilentlyContinue)) {
    throw 'Azure CLI not found. Install it with:  winget install Microsoft.AzureCLI   then open a new terminal.'
}

# Sign in if needed (works for tenants without an Azure subscription too)
az account show *> $null
if ($LASTEXITCODE -ne 0) { az login --allow-no-subscriptions | Out-Null }
$tenant = az account show --query tenantId -o tsv

$redirects = @('http://localhost:8080/auth/microsoft/callback')
if ($RenderUrl) { $redirects += ($RenderUrl.TrimEnd('/') + '/auth/microsoft/callback') }

# Delegated Microsoft Graph permissions: offline_access, openid, profile, User.Read, Sites.ReadWrite.All
$graphPermissions = @'
[{ "resourceAppId": "00000003-0000-0000-c000-000000000000",
   "resourceAccess": [
     { "id": "7427e0e9-2fba-42fe-b0c0-848c9e6a8182", "type": "Scope" },
     { "id": "37f7f235-527c-4136-accd-4a02d197296e", "type": "Scope" },
     { "id": "14dad69e-099b-42c9-810b-d002981feec1", "type": "Scope" },
     { "id": "e1fe6dd8-ba31-4d61-89e7-88639da4683d", "type": "Scope" },
     { "id": "89fe6a52-be36-487e-b7d8-d061c450a026", "type": "Scope" } ] }]
'@
$permFile = New-TemporaryFile
Set-Content -Path $permFile -Value $graphPermissions -Encoding utf8

try {
    $existing = az ad app list --display-name $DisplayName --query '[0].appId' -o tsv
    if ($existing) {
        Write-Host "Updating existing app $DisplayName ($existing)"
        $appId = $existing
        az ad app update --id $appId --web-redirect-uris @redirects --required-resource-accesses "@$permFile" | Out-Null
    } else {
        Write-Host "Creating app $DisplayName"
        $appId = az ad app create --display-name $DisplayName --sign-in-audience $Audience `
            --web-redirect-uris @redirects --required-resource-accesses "@$permFile" --query appId -o tsv
    }
} finally {
    Remove-Item $permFile -Force
}

# New client secret (valid 1 year). Kept in a variable and written to .env only.
$secret = az ad app credential reset --id $appId --append --display-name 'migration-app' --years 1 --query password -o tsv

$envFile = Join-Path (Split-Path $PSScriptRoot -Parent) '.env'
$lines = if (Test-Path $envFile) { Get-Content $envFile | Where-Object { $_ -notmatch '^GRAPH_(BASE_URL|AUTHORIZE_URL|TOKEN_URL|CLIENT_ID|CLIENT_SECRET)=' } } else { @() }
$authority = if ($Audience -eq 'AzureADMyOrg') { $tenant } else { 'organizations' }
$lines += @(
    'GRAPH_BASE_URL=https://graph.microsoft.com/v1.0',
    "GRAPH_AUTHORIZE_URL=https://login.microsoftonline.com/$authority/oauth2/v2.0/authorize",
    "GRAPH_TOKEN_URL=https://login.microsoftonline.com/$authority/oauth2/v2.0/token",
    "GRAPH_CLIENT_ID=$appId",
    "GRAPH_CLIENT_SECRET=$secret"
)
[IO.File]::WriteAllLines($envFile, [string[]]$lines)   # UTF-8 without BOM: a BOM would corrupt the first key for Docker
$secret = $null

Write-Host ""
Write-Host "Done. Client id: $appId (tenant $tenant). GRAPH_* settings written to .env; the secret is only there." -ForegroundColor Green
Write-Host "Redirect URIs: $($redirects -join ', ')"
Write-Host ""
Write-Host "If users' organisation does not let users approve apps themselves, an admin of THAT organisation opens:"
Write-Host "  https://login.microsoftonline.com/organizations/adminconsent?client_id=$appId" -ForegroundColor Cyan
Write-Host "For Render, copy the GRAPH_* values from .env into the service's environment settings."
