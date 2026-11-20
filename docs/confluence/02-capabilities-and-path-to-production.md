# HighQ Migration – Current Capabilities and Path to Production

Nov 20, 2026 · Deepesh Alwani

The migration app works end to end today: HighQ is the real source, local folders are a real destination, and SharePoint runs against a simulated stand-in until the firm's IT provides a Microsoft Entra ID app and a test site. The design rationale is on the page **HighQ to SharePoint Migration – Solution Design**.

| Field | Value |
| --- | --- |
| Status | Working prototype, hosted for testing |
| Hosted at | https://highq-migration.onrender.com (Render, free plan) |
| Real connections | HighQ (OAuth, tested on site 231), local folders (Chrome / Edge) |
| Simulated | Microsoft sign-in and SharePoint (stand-in service) |
| Code | Private GitHub repository `HighQ-to-Sharepoint` |

To reach production, three things are needed: an Entra ID app and SharePoint test site from IT, production hosting with persistent storage and access control, and sign-off on how redacted files and site security gates are handled.

## Current capabilities

All four migration modes are built and available as tabs on the website; two work against real systems today.

| Mode | Status | How it works | Main limits today |
| --- | --- | --- | --- |
| HighQ → local folder | **Live** (real HighQ, real folder) | Server reads HighQ; the browser saves each file into a folder the user picks | Chrome or Edge on desktop only; Windows 260-character path limit (long names are shortened and logged) |
| Zip → local folder | **Live** (no server involved) | The browser extracts a HighQ zip export into a chosen folder | Paths come from the zip's folders, not HighQ's metadata file yet |
| HighQ → SharePoint | **Built, simulated destination** | Server streams each file from HighQ straight to SharePoint as the signed-in user | Needs the Entra ID app and a real site |
| Zip → SharePoint | **Built, simulated destination** | Zip is uploaded to the server and extracted straight into SharePoint | Needs the Entra ID app; zip held on the server's temporary disk |

**Features common to all modes**

- Folder structure kept exactly, including empty folders, with HighQ's numbering in names ("1 Structure", "19 Policy.docx").
- Names SharePoint or Windows reject are cleaned (e.g. `bad:name?.txt` → `bad_name_.txt`) and every rename is reported.
- Resume: re-running skips files already copied with the same modified date.
- Original modified dates are kept on every file.
- A CSV report per run: copied, renamed, skipped and failed files with reasons.
- Redacted HighQ files: the redacted copy by default; originals only by an explicit, logged choice.
- Optional "unpack archives" checkbox: any .zip, .7z or .rar found (in HighQ or inside an uploaded zip) is unpacked into a folder with the archive's name instead of being copied as an archive; one level deep, and an archive that can't be fully unpacked (damaged, password-protected) is kept as a file. In the browser, .7z and .rar files over 1 GB are kept as files.

**HighQ specifics handled**

- Sign-in with each user's own HighQ account (OAuth); tokens refresh automatically.
- Site security steps (passcode, terms and conditions): the app shows HighQ's link, the user completes it once, and the copy continues.
- HighQ's limit of 2,000 calls per 10 minutes: calls are spaced to stay under it.
- Placeholder files (no content in HighQ) are created as empty files with the same name and listed.

**SharePoint specifics handled** (verified against the stand-in)

- Each user signs in with their own Microsoft account; copies run with that user's SharePoint permissions.
- Large files go up in resumable 10 MB chunks; Microsoft throttling (HTTP 429) is retried automatically.
- SharePoint's 400-character path limit: long names fall back to a short unique name.

## How it runs today vs production

The same Docker image runs in both places; production adds staff sign-on, durable storage and real Microsoft services around it.

| Part | Today (Render, testing) | Production (Azure) |
| --- | --- | --- |
| Who can open the site | Anyone with the address (HighQ and Microsoft logins still required) | Staff only, behind the firm's single sign-on |
| App | highq-migration: website + API in one Docker image, free plan, sleeps when idle | Same image on Azure Container Apps, always on |
| Sign-ins and jobs | Kept in server memory | Encrypted tokens and job progress in a database, key in Azure Key Vault |
| Zip uploads | Server's temporary disk | Azure Blob Storage with a deletion rule |
| HighQ | Real (OAuth, tested on site 231) | Real, with the production callback registered |
| Microsoft sign-in and SharePoint | sharepoint-mock stand-in | Microsoft Entra ID and real SharePoint, each user's own access |
| Monitoring | Render logs | Application Insights |

Today the app and the SharePoint stand-in are two free Render services, deployed automatically from the GitHub repository.

## What has been tested

Everything except real SharePoint has been exercised; the SharePoint side is tested against a stand-in that mimics Microsoft Graph.

| Area | Test | Result |
| --- | --- | --- |
| HighQ API | Read-only probe of every endpoint used, on site 231 | Works; file fields, rate-limit headers and version history confirmed |
| HighQ site gates | Passcode and terms-and-conditions steps | Pass once per sign-in via HighQ's link |
| HighQ downloads | Plain download vs byte-range request | Plain works; byte ranges return HTTP 500, so the app never requests them |
| HighQ → local | Full site 231 (21 folders, 34 files) in the app | Worked; structure, numbering and redacted copies as expected |
| Zip → SharePoint | Test zip with a 25 MB file, empty file, illegal names, a malicious path | All copied correctly in 3 chunks; illegal names renamed; malicious path blocked |
| Throttling | Stand-in returned HTTP 429 on every 7th call | All retried, no failures |
| Resume | Same job run twice | Second run skipped every file |
| Microsoft sign-in | Sign-in, forged sign-in response, token expiry | Forged response rejected; expired token refreshed automatically |
| Hosting image | Render-style container on port 10000 | Site, API and secure cookies work |
| Automated tests | Naming rules, zip-path safety, SharePoint path encoding | Run on every build; a failure stops deployment |

**Not yet tested:** real SharePoint permissions and Microsoft's own behaviour, very large sites (thousands of files), and large zip uploads through Render.

## Known limitations

The prototype takes deliberate shortcuts, each marked `shortcut:` in the code; none loses data, but several must be fixed before production.

| Limitation | Effect today | Production fix | Must fix before production? |
| --- | --- | --- | --- |
| HighQ and Microsoft sign-ins kept in server memory | A restart signs everyone out | Store tokens encrypted (key in Azure Key Vault) | Yes |
| Job progress kept in server memory | A restart loses the job list (re-running still resumes) | Spring Batch with a database job repository | Yes |
| Free hosting sleeps after 15 minutes idle | Restarts end running jobs | Always-on paid instance | Yes |
| No access control on the website itself | Anyone with the address can open it (they still need HighQ and Microsoft logins) | Firm single sign-on in front of the app | Yes |
| Uploaded zips stored on the server's temporary disk | Large zips limited by disk; lost on restart | Azure Blob Storage with a retention rule | Yes, for zip → SharePoint |
| Site scan runs while the browser waits | Fine for a few thousand items; slow on large sites | Run the scan as a background job | For large sites |
| An interrupted file restarts from byte 0 | Wasted time on very large files only | Save upload sessions to resume mid-file | No |
| Zip modes use the zip's own folder paths | Correct when HighQ exports folder structure | Use HighQ's metadata file (needs a sample export) | Recommended |
| SharePoint path length is approximated | Rare edge cases near 400 characters | Calculate from the library's real URL | No |

## Path to production

Three stages, each usable on its own: connect real SharePoint, harden the app, then move to production hosting. Stage 1 needs no code changes.

**Stage 1: connect real SharePoint (configuration only)**

1. **IT** registers an app in Microsoft Entra ID: Web platform, client secret, delegated Microsoft Graph permissions offline_access, openid, profile, User.Read and Sites.ReadWrite.All, with admin consent. The repo's `infra/register-entra-app.ps1` does this in one run for anyone allowed to.
2. **IT** adds the redirect URI `https://highq-migration.onrender.com/auth/microsoft/callback` (and the localhost one for development).
3. **IT** provides a SharePoint test site the migration user can edit.
4. **Developer** switches the Microsoft settings (the `GRAPH_` values) to Microsoft's addresses and the new client id and secret. The "SharePoint is simulated" note disappears by itself.
5. **Developer and a pilot user** copy site 231 to the test site and check structure, names, dates and permissions.

**Stage 2: harden the app (code changes)**

1. Store HighQ and Microsoft tokens encrypted, with the key in Azure Key Vault.
2. Move transfer jobs to Spring Batch with a database, so progress survives restarts.
3. Put the firm's single sign-on in front of the website so only staff can open it.
4. Store uploaded zips in Azure Blob Storage with an automatic deletion rule.
5. Run the HighQ site scan as a background job for large sites.
6. Switch zip modes to HighQ's metadata file once a sample export is available.

**Stage 3: production hosting (Azure, as in the design)**

1. Container: the same Docker image runs on Azure Container Apps (always on, scaled to the workload).
2. Database: Azure SQL or Azure Database for PostgreSQL for jobs and the file manifest.
3. Secrets: Azure Key Vault, read through a managed identity; no secrets in settings.
4. Storage: Azure Blob Storage for zip uploads.
5. Monitoring: Application Insights for logs, failures and HighQ / Microsoft throttling.
6. Callbacks: register the production HighQ callback on the HighQ API app and the production Microsoft callback on the Entra ID app.
7. Pilot on one client site, then open to wider use.

Render can stay for demos and testing: moving to Azure is a change of where the same image runs, not a rewrite.

## Decisions and inputs needed

The Entra ID app from IT is the only blocker for real SharePoint; the rest decide how fast the app reaches production.

**From IT**

- [ ] Register the Entra ID app (spec in Stage 1) and send the client id and secret securely.
- [ ] Provide a SharePoint test site the migration user can edit.
- [ ] Confirm the firm's single sign-on option for putting the app behind staff login.

**From the HighQ admin**

- [ ] Add the hosted callback `https://highq-migration.onrender.com/callback` (and later the production one) to the HighQ API app.
- [ ] A sample site export with "Include folder structure" and "Include file metadata", for the zip modes.
- [ ] Confirm compliance is aware that every download appears in HighQ's audit log under the migrating user.

**From management**

- [ ] Approve production hosting on Azure and its running costs (Container Apps, database, Key Vault, Blob Storage, monitoring).
- [ ] Confirm redacted files are migrated as the redacted copy, with originals only by explicit, logged choice.
- [ ] Choose a pilot client site for the first real SharePoint migration.
