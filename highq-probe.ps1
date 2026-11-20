# Read-only probe of the HighQ endpoints our migration design relies on.
# SAFETY: every call goes through Invoke-HighQ, which refuses anything except GET
# (plus POST folders/itemcount, which only counts). Nothing in HighQ is created, changed or deleted.
# Secrets are typed at prompts and never written to disk. Output goes to .\probe-output\
param(
    [string] $InstanceUrl = 'https://mishcondereya.highqsolutions.com/mishcondereya',   # host AND instance path
    [string] $RedirectUri = 'http://localhost:8080/callback',   # must match the HighQ API app registration exactly
    [string] $SiteId = '231',
    [string] $ApiVersion = '20',
    [int]    $MaxFolders = 30,                      # folders to walk; use -All for the whole site
    [switch] $All,
    [switch] $IncludeDownload                       # also test byte ranges on one file (body is not saved; shows in HighQ audit log)
)
$ErrorActionPreference = 'Stop'
$InstanceUrl = $InstanceUrl.TrimEnd('/')
$out = Join-Path $PSScriptRoot 'probe-output'
New-Item -ItemType Directory -Force $out | Out-Null
$summary = New-Object System.Collections.Generic.List[object]

# ---------- sign in (OAuth authorization code; your email/password go only into HighQ's own page) ----------
$clientId = Read-Host 'HighQ API client id'
$secure   = Read-Host 'HighQ API client secret' -AsSecureString
$bstr     = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
try { $clientSecret = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr) } finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }
$authUrl = "$InstanceUrl/authorize.action?response_type=code&client_id=$([uri]::EscapeDataString($clientId))&redirect_uri=$([uri]::EscapeDataString($RedirectUri))"
if ($RedirectUri -match '^http://localhost(:\d+)?/') {
    # Catch HighQ's redirect ourselves so the short-lived code is exchanged at once.
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
        $err  = $ctx.Request.QueryString['error']
        $html = if ($code) { 'Signed in. You can close this tab and go back to PowerShell.' } else { "HighQ returned no code ($err). Check PowerShell." }
        $bytes = [Text.Encoding]::UTF8.GetBytes("<html><body style='font-family:sans-serif'>$html</body></html>")
        $ctx.Response.ContentType = 'text/html'; $ctx.Response.OutputStream.Write($bytes, 0, $bytes.Length); $ctx.Response.Close()
        if (-not $code) { throw "HighQ redirect had no code. error=$err; query=$($ctx.Request.Url.Query -replace 'code=[^&]+','code=***')" }
    } finally { $listener.Stop() }
} else {
    Start-Process $authUrl
    $redirected = Read-Host 'Sign in in the browser, then paste the full redirected address'
    $code = if ($redirected -match '[?&]code=([^&]+)') { [uri]::UnescapeDataString($Matches[1]) } else { $redirected.Trim() }
}
$token = Invoke-RestMethod -Method Post -Uri "$InstanceUrl/api/oauth2/token" -Body @{
    grant_type = 'authorization_code'; client_id = $clientId; client_secret = $clientSecret; code = $code; redirect_uri = $RedirectUri }
$clientSecret = $null
$script:accessToken = $token.access_token
if (-not $script:accessToken) { throw 'No access_token returned.' }
"token: expires_in=$($token.expires_in) refresh_token_expires_in=$($token.refresh_token_expires_in)" | Out-File -Encoding utf8 "$out\token-lifetime.txt"
$token = $null

# ---------- the only door to HighQ ----------
function Invoke-HighQ([string]$Name, [string]$Path, [string]$Method = 'GET', $Body = $null, [switch]$Save) {
    $route = ($Path -split '\?')[0]
    if (-not ($Method -eq 'GET' -or ($Method -eq 'POST' -and $route -eq 'folders/itemcount'))) { throw "Blocked non-read call: $Method $Path" }
    $p = @{ UseBasicParsing = $true; Uri = "$InstanceUrl/api/$ApiVersion/$Path"; Method = $Method
            Headers = @{ 'Auth-Type' = 'OAUTH2'; Authorization = "Bearer $script:accessToken"; Accept = 'application/json' } }
    if ($Body) { $p.Body = ($Body | ConvertTo-Json -Depth 5); $p.ContentType = 'application/json' }
    Start-Sleep -Milliseconds 350   # stay under ~3 calls/s (HighQ: 2000 calls / 10 min per user)
    $status = 0; $content = ''; $rl = ''
    try {
        $r = Invoke-WebRequest @p
        $status = [int]$r.StatusCode; $content = $r.Content
        $rl = ($r.Headers.GetEnumerator() | Where-Object { $_.Key -match 'ratelimit' } | ForEach-Object { "$($_.Key)=$($_.Value)" }) -join '; '
    } catch {
        $resp = $_.Exception.Response
        if (-not $resp) { throw }
        $status = [int]$resp.StatusCode
        $content = (New-Object IO.StreamReader($resp.GetResponseStream())).ReadToEnd()
    }
    $json = $null; try { $json = $content | ConvertFrom-Json } catch {}
    if ($Save -or $status -ge 400) { $content | Out-File -Encoding utf8 (Join-Path $out "$Name.json") }
    $keys = if ($json) { ($json.PSObject.Properties.Name) -join ',' } else { '' }
    $summary.Add([pscustomobject]@{ name = $Name; method = $Method; path = $Path; status = $status; topLevelKeys = $keys; rateLimit = $rl })
    Write-Host ("{0,-28} {1}  {2}" -f $Name, $status, $Path)
    return $json
}

function Get-Prop($o, [string[]]$names) { foreach ($n in $names) { if ($o.PSObject.Properties.Name -contains $n -and $o.$n) { return $o.$n } } }

# Objects that sit under a property named subFolders (immediate children only)
function Find-SubFolders($node, [bool]$inSub = $false) {
    if ($null -eq $node) { return }
    if ($node -is [array]) { foreach ($n in $node) { Find-SubFolders $n $inSub }; return }
    if ($node -isnot [pscustomobject]) { return }
    if ($inSub -and (Get-Prop $node 'id','folderid')) { $node; return }
    foreach ($p in $node.PSObject.Properties) { Find-SubFolders $p.Value ($inSub -or $p.Name -match '^subfolders?$') }
}
# First object under a property whose name contains 'file' that has an id and a name
function Find-FirstFile($node, [bool]$inFile = $false) {
    if ($null -eq $node) { return }
    if ($node -is [array]) { foreach ($n in $node) { $f = Find-FirstFile $n $inFile; if ($f) { return $f } }; return }
    if ($node -isnot [pscustomobject]) { return }
    if ($inFile -and (Get-Prop $node 'id','docid') -and (Get-Prop $node 'name','title')) { return $node }
    foreach ($p in $node.PSObject.Properties) { $f = Find-FirstFile $p.Value ($inFile -or $p.Name -match 'file|document'); if ($f) { return $f } }
}

# ---------- 1. site and root folder ----------
# Site gates (passcode, terms & conditions, ...) return 403 with a link that trusts THIS token.
# Complete each one in the browser, then retry with the same token.
for ($gate = 1; $gate -le 5; $gate++) {
    $site = Invoke-HighQ "site-attempt-$gate" "sites/$SiteId" -Save
    $link = Get-Prop $site 'link'
    if (-not ($link -match 'checkSiteSecurityTrustEntries')) { break }
    Write-Host "`nHighQ gate: $(Get-Prop $site 'summary') (ref $(Get-Prop $site 'ref'))" -ForegroundColor Yellow
    Start-Process $link
    Read-Host 'Complete it in the browser that just opened, then press Enter here'
}
$root = Invoke-HighQ 'folders-details-site-root' "folders/details?siteId=$SiteId&include=subfolders&limit=-1" -Save
if (-not $root) { throw 'Could not read the site root folder; see probe-output\folders-details-site-root.json' }
$rootId = Get-Prop $root 'id','folderid'
if (-not $rootId) {
    $summary | Export-Csv -NoTypeInformation -Encoding UTF8 "$out\summary.csv"
    throw "No root folder for site $SiteId. HighQ said: $(Get-Prop $root 'summary','message'). See probe-output\folders-details-site-root.json"
}

# ---------- 2. walk folders (read-only) ----------
$folders = New-Object System.Collections.Generic.List[object]
$queue = New-Object System.Collections.Generic.Queue[object]
$queue.Enqueue($rootId); $seen = @{}
while ($queue.Count -gt 0 -and ($All -or $folders.Count -lt $MaxFolders)) {
    $id = $queue.Dequeue(); if ($seen[$id]) { continue }; $seen[$id] = $true
    $d = Invoke-HighQ "folder-$id" "folders/details?folderId=$id&include=path,subfolders&limit=-1" -Save:($folders.Count -lt 3)
    if (-not $d) { continue }
    $folders.Add([pscustomobject]@{ id = $id; name = (Get-Prop $d 'name'); parentFolderID = (Get-Prop $d 'parentFolderID')
        location = (Get-Prop $d 'location'); filecount = (Get-Prop $d 'filecount'); foldersize = (Get-Prop $d 'foldersize') })
    foreach ($c in (Find-SubFolders $d)) { $queue.Enqueue((Get-Prop $c 'id','folderid')) }
}
$folders | Export-Csv -NoTypeInformation -Encoding UTF8 "$out\folders.csv"
Write-Host "Folders recorded: $($folders.Count) (queue left: $($queue.Count))" -ForegroundColor Green

# ---------- 3. one small run of each read endpoint ----------
$sample = @($folders | Where-Object { $_.filecount -and [int]$_.filecount -gt 0 } | Select-Object -First 1)
if (-not $sample) { $sample = @($folders | Select-Object -First 1) }
$fid = $sample[0].id

Invoke-HighQ 'folders-items'      "folders/$fid/items?limit=5" -Save | Out-Null
Invoke-HighQ 'folders-path'       "folders/$fid/path" -Save | Out-Null
Invoke-HighQ 'folders-foldersize' "folders/$fid/foldersize" -Save | Out-Null
Invoke-HighQ 'folders-itemcount'  "folders/itemcount?includedeleteditems=false&includestructure=true" 'POST' @{ folderIdCsv = "$rootId" } -Save | Out-Null
$q = [uri]::EscapeDataString('"folderid=' + $fid + '"')
$files = Invoke-HighQ 'files-list' "files?q=$q&limit=5" -Save
Invoke-HighQ 'changes-current'    "changes?syncpositionid=" -Save | Out-Null

$file = Find-FirstFile $files
if ($file) {
    $fileId = Get-Prop $file 'id','docid'
    Invoke-HighQ 'file-info'          "files/$fileId" -Save | Out-Null
    Invoke-HighQ 'file-info-download' "files/$fileId`?include=downloadurl" -Save | Out-Null
    Invoke-HighQ 'file-versions'      "files/$fileId/versions?limit=5" -Save | Out-Null

    if ($IncludeDownload) {
        # Download tests: headers only, file content is never read or kept. Error bodies (small XML/JSON) are kept.
        $results = foreach ($t in @(@{ name = 'plain'; range = $false; accept = '*/*' },
                                    @{ name = 'range-0-99'; range = $true; accept = '*/*' },
                                    @{ name = 'plain-octet'; range = $false; accept = 'application/octet-stream' })) {
            Start-Sleep -Milliseconds 350
            $req = [Net.HttpWebRequest]::Create("$InstanceUrl/api/$ApiVersion/files/$fileId/content")
            $req.Headers['Authorization'] = "Bearer $script:accessToken"
            $req.Headers['Auth-Type'] = 'OAUTH2'
            $req.Accept = $t.accept
            if ($t.range) { $req.AddRange(0, 99) }
            $res = $null
            try { $res = $req.GetResponse() } catch [Net.WebException] { $res = $_.Exception.Response }
            if (-not $res) { [pscustomobject]@{ test = $t.name; status = 'no response' }; continue }
            $status = [int]$res.StatusCode
            $errBody = if ($status -ge 400) { (New-Object IO.StreamReader($res.GetResponseStream())).ReadToEnd() } else { $null }
            [pscustomobject]@{ test = $t.name; status = $status; contentLength = $res.ContentLength
                acceptRanges = $res.Headers['Accept-Ranges']; contentRange = $res.Headers['Content-Range']
                contentType = $res.ContentType; disposition = $res.Headers['Content-Disposition']; errorBody = $errBody }
            $res.Close()
        }
        $results | ConvertTo-Json | Out-File -Encoding utf8 "$out\download-tests.json"
        $results | Format-Table test, status, contentLength, acceptRanges, contentRange -AutoSize | Out-Host
    }
} else { Write-Host 'No file found in the sample folder; file endpoints skipped.' -ForegroundColor Yellow }

$script:accessToken = $null
$summary | Export-Csv -NoTypeInformation -Encoding UTF8 "$out\summary.csv"
Write-Host "`nDone. See $out" -ForegroundColor Green
