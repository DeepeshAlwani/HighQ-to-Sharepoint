# Prototype: download a HighQ site (or one folder) to a local folder, keeping the folder structure
# and HighQ's numbering ("1 Structure\19 HR_Policy.docx").
# SAFETY: read-only. Every HighQ call is a GET; nothing in HighQ is created, changed or deleted.
# Secrets are typed at prompts and never written to disk. Re-running skips files already downloaded.
param(
    [string] $InstanceUrl = 'https://mishcondereya.highqsolutions.com/mishcondereya',
    [string] $RedirectUri = 'http://localhost:8080/callback',
    [string] $SiteId = '231',
    [string] $FolderId,                       # optional: start here instead of the site root
    [string] $Destination,                    # default: .\highq-download\<site name>
    [string] $ApiVersion = '20',
    [int]    $MaxFiles = 50,                  # safety cap for a test run; use -All for everything
    [switch] $All,
    # Redacted files: by default the redacted copy (what HighQ normally serves) is downloaded.
    # -Originals fetches the unredacted original instead; needs "download original" rights. Logged per file.
    [switch] $Originals
)
$ErrorActionPreference = 'Stop'
$InstanceUrl = $InstanceUrl.TrimEnd('/')

# ---------- naming rules ----------
# Windows-safe name: HighQ numbering + name (+ extension). Invalid characters become '_'.
function Get-LocalName([string]$Number, [string]$Name, [string]$Ext) {
    $n = if ($Number) { "$Number $Name" } else { $Name }
    $n = ($n -replace '[<>:"/\\|?*\x00-\x1F]', '_').Trim().TrimEnd('.')
    if ($n -match '^(CON|PRN|AUX|NUL|COM\d|LPT\d)$') { $n = "_$n" }
    if (-not $n) { $n = '_' }
    if ($Ext) { $n = "$n.$($Ext.TrimStart('.'))" }
    return $n
}
# HighQ dates look like "06 Nov 2026 09:32" and are UTC
function ConvertFrom-HighQDate([string]$s) {
    $d = [datetime]::MinValue
    if ([datetime]::TryParseExact($s, 'dd MMM yyyy HH:mm', [Globalization.CultureInfo]::InvariantCulture,
        [Globalization.DateTimeStyles]'AssumeUniversal,AdjustToUniversal', [ref]$d)) { return $d }
    return $null
}
if ($env:HIGHQ_DOWNLOAD_SELFTEST) { return }   # lets a test load the functions above without running

# ---------- sign in (same flow as highq-probe.ps1) ----------
$clientId = Read-Host 'HighQ API client id'
$secure   = Read-Host 'HighQ API client secret' -AsSecureString
$bstr     = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
try { $clientSecret = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr) } finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }
$authUrl = "$InstanceUrl/authorize.action?response_type=code&client_id=$([uri]::EscapeDataString($clientId))&redirect_uri=$([uri]::EscapeDataString($RedirectUri))"
$listener = New-Object Net.HttpListener
$listener.Prefixes.Add(([uri]$RedirectUri).GetLeftPart('Authority') + '/')
$listener.Start()
try {
    Start-Process $authUrl
    Write-Host 'Sign in to HighQ in the browser window that just opened (waiting up to 5 minutes)...' -ForegroundColor Cyan
    $task = $listener.GetContextAsync()
    if (-not $task.Wait(300000)) { throw 'Timed out waiting for the HighQ sign-in redirect.' }
    $ctx = $task.Result
    $code = $ctx.Request.QueryString['code']
    $bytes = [Text.Encoding]::UTF8.GetBytes("<html><body style='font-family:sans-serif'>Signed in. You can close this tab and go back to PowerShell.</body></html>")
    $ctx.Response.ContentType = 'text/html'; $ctx.Response.OutputStream.Write($bytes, 0, $bytes.Length); $ctx.Response.Close()
    if (-not $code) { throw "HighQ redirect had no code: $($ctx.Request.QueryString['error'])" }
} finally { $listener.Stop() }
$token = Invoke-RestMethod -Method Post -Uri "$InstanceUrl/api/oauth2/token" -Body @{
    grant_type = 'authorization_code'; client_id = $clientId; client_secret = $clientSecret; code = $code; redirect_uri = $RedirectUri }
$clientSecret = $null
$script:headers = @{ 'Auth-Type' = 'OAUTH2'; Authorization = "Bearer $($token.access_token)"; Accept = 'application/json' }
$token = $null

# ---------- the only door to HighQ: GET only ----------
function Get-HighQ([string]$Path) {
    Start-Sleep -Milliseconds 350   # ~3 calls/s, under 2000 calls / 10 min
    try { return Invoke-RestMethod -Method Get -Uri "$InstanceUrl/api/$ApiVersion/$Path" -Headers $script:headers }
    catch {
        $resp = $_.Exception.Response
        if (-not $resp) { throw }
        $body = (New-Object IO.StreamReader($resp.GetResponseStream())).ReadToEnd()
        try { return ($body | ConvertFrom-Json) | Add-Member -PassThru -NotePropertyName httpStatus -NotePropertyValue ([int]$resp.StatusCode) }
        catch { throw "HTTP $([int]$resp.StatusCode) on $Path : $body" }
    }
}
function As-Array($x) { if ($null -eq $x) { @() } elseif ($x -is [array]) { $x } else { @($x) } }

# ---------- site gates (passcode, terms): complete the link, then retry with the same token ----------
for ($gate = 1; $gate -le 5; $gate++) {
    $site = Get-HighQ "sites/$SiteId"
    if (-not ($site.link -match 'checkSiteSecurityTrustEntries')) { break }
    Write-Host "`nHighQ gate: $($site.summary) (ref $($site.ref))" -ForegroundColor Yellow
    Start-Process $site.link
    Read-Host 'Complete it in the browser that just opened, then press Enter here'
}
if (-not $site.sitename) { throw "Cannot read site $SiteId : $($site.summary)" }

$startId = if ($FolderId) { $FolderId } else { $site.sitefolderID }
if (-not $Destination) { $Destination = Join-Path $PSScriptRoot ("highq-download\" + (Get-LocalName '' $site.sitename '')) }
New-Item -ItemType Directory -Force $Destination | Out-Null
$manifestPath = Join-Path $Destination '_manifest.csv'
Write-Host "`nSite: $($site.sitename)  ->  $Destination" -ForegroundColor Green

# ---------- walk folders and download ----------
$manifest = New-Object System.Collections.Generic.List[object]
$queue = New-Object System.Collections.Generic.Queue[object]
$queue.Enqueue(@{ id = $startId; rel = '' })
$downloaded = 0; $stop = $false
while ($queue.Count -gt 0 -and -not $stop) {
    $f = $queue.Dequeue()
    $localDir = if ($f.rel) { Join-Path $Destination $f.rel } else { $Destination }
    New-Item -ItemType Directory -Force $localDir | Out-Null

    # subfolders (auto-numbering only appears in list responses)
    $items = Get-HighQ "folders/$($f.id)/items?limit=-1"
    foreach ($sub in (As-Array $items.folder)) {
        $name = Get-LocalName $sub.'auto-numbering' $sub.name ''
        $queue.Enqueue(@{ id = $sub.id; rel = $(if ($f.rel) { Join-Path $f.rel $name } else { $name }) })
    }

    # files in this folder
    $q = [uri]::EscapeDataString('"folderid=' + $f.id + '"')
    $list = Get-HighQ "files?q=$q&limit=-1"
    $used = @{}
    foreach ($file in (As-Array $list.file)) {
        if (-not $All -and $downloaded -ge $MaxFiles) { $stop = $true; break }
        $name = Get-LocalName $file.'auto-numbering' $file.name $file.type
        if ($used[$name.ToLower()]) { $name = Get-LocalName $file.'auto-numbering' "$($file.name) ($($file.id))" $file.type }
        $used[$name.ToLower()] = $true
        $rel = if ($f.rel) { Join-Path $f.rel $name } else { $name }
        $target = Join-Path $Destination $rel
        $row = [pscustomobject]@{ highqId = $file.id; version = $file.versionid; relativePath = $rel; size = $file.size; redacted = $file.redactionstatus; status = ''; error = '' }

        # Windows path limit: shorten the name, keeping number, a ~id suffix for uniqueness, and the extension
        if ($target.Length -ge 260) {
            $room = 259 - $localDir.Length - 1 - (" ~$($file.id)").Length - $(if ($file.type) { $file.type.Length + 1 } else { 0 })
            $base = Get-LocalName $file.'auto-numbering' $file.name ''
            if ($room -ge 10) {
                $name = Get-LocalName '' ($base.Substring(0, [Math]::Min($base.Length, $room)).TrimEnd(' ', '.') + " ~$($file.id)") $file.type
                $rel = if ($f.rel) { Join-Path $f.rel $name } else { $name }
                $target = Join-Path $Destination $rel
                $row.relativePath = $rel; $row.error = "name shortened from $($base.Length) characters (Windows path limit)"
            }
        }

        if ($file.type -eq 'placeholder') { $row.status = 'skipped (placeholder, no content)' }
        elseif ($target.Length -ge 260) { $row.status = 'failed'; $row.error = "folder path alone is too long for Windows ($($localDir.Length) characters)" }
        # Files are only ever moved into place complete, so an existing file with HighQ's modified date is current.
        elseif ((Test-Path -LiteralPath $target) -and (Get-Item -LiteralPath $target).LastWriteTimeUtc -eq (ConvertFrom-HighQDate $file.modifieddate)) { $row.status = 'skipped (already downloaded)' }
        else {
            # Download to a temp file: -OutFile treats [ ] in a path as wildcards, temp names have none.
            function Receive-HighQContent([string]$query) {
                $tmp = [IO.Path]::GetTempFileName()
                Start-Sleep -Milliseconds 350
                # No Range header: HighQ answers a byte-range request with HTTP 500.
                $r = Invoke-WebRequest -UseBasicParsing -PassThru -OutFile $tmp `
                    -Uri "$InstanceUrl/api/$ApiVersion/files/$($file.id)/content$query" `
                    -Headers @{ 'Auth-Type' = 'OAUTH2'; Authorization = $script:headers.Authorization }
                $len = (Get-Item -LiteralPath $tmp).Length
                $cl = $r.Headers['Content-Length']
                if ($cl -and $len -ne [long]$cl) { Remove-Item -LiteralPath $tmp -Force; throw "truncated download: Content-Length $cl, got $len" }
                return @{ path = $tmp; length = $len }
            }
            $got = $null
            $redacted = [bool]$file.redactionstatus
            try {
                if ($redacted -and $Originals) {
                    $got = Receive-HighQContent '?original=true'
                    $row.status = 'downloaded (ORIGINAL of redacted file)'
                } else {
                    $got = Receive-HighQContent ''
                    $row.status = if ($redacted) { 'downloaded (redacted copy)' } else { 'downloaded' }
                }
                # A redacted copy is a different file from the one HighQ's listing size describes.
                if (-not $redacted -and $file.size -and $got.length -ne [long]$file.size) {
                    $row.status = 'downloaded (size differs)'
                    $row.error = "HighQ lists $($file.size) bytes; served $($got.length)"
                }
                if ($redacted) { $row.error = "listing size $($file.size), downloaded $($got.length)" }
                Move-Item -LiteralPath $got.path -Destination $target -Force
                $when = ConvertFrom-HighQDate $file.modifieddate
                if ($when) { (Get-Item -LiteralPath $target).LastWriteTimeUtc = $when }
                $downloaded++
            } catch {
                $row.status = 'failed'; $row.error = $_.Exception.Message
            } finally {
                if ($got -and (Test-Path -LiteralPath $got.path)) { Remove-Item -LiteralPath $got.path -Force }
            }
        }
        Write-Host ("{0,-30} {1}" -f $row.status, $rel)
        $manifest.Add($row)
    }
}
$script:headers = $null
$manifest | Export-Csv -NoTypeInformation -Encoding UTF8 $manifestPath
$counts = $manifest | Group-Object status | ForEach-Object { "$($_.Name): $($_.Count)" }
Write-Host "`nDone. $($counts -join ', ')" -ForegroundColor Green
if ($stop) { Write-Host "Stopped at the -MaxFiles cap ($MaxFiles). Run with -All for everything; finished files are skipped." -ForegroundColor Yellow }
Write-Host "Manifest: $manifestPath"
