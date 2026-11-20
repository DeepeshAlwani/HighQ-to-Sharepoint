# HighQ to SharePoint Migration – Solution Design

Nov 19, 2026 · Deepesh Alwani

We are building a web app that copies client documents straight from HighQ into SharePoint, keeping every relative path. It runs on Java 21 with Spring Boot, can resume where it stopped, and leaves room for a bulk "Option C" engine later. Current capabilities and the steps to production are on the page **HighQ Migration – Current Capabilities and Path to Production**.

| Field | Value |
| --- | --- |
| Status | Draft for review |
| Chosen option | Option B: API-to-API streaming web app |
| Stack | Java 21, Spring Boot 3, Spring Batch 5 (production), React |
| Hosting | Microsoft Azure in production (Container Apps, Azure SQL / PostgreSQL, Key Vault); Render for testing today |
| Future path | Option C: SharePoint Migration API engine for very large clients |

## TL;DR

- **Today:** staff download a zip from HighQ, unzip it and re-upload each file to SharePoint by hand.
- **Proposed:** the user connects HighQ and SharePoint once, picks a source folder and a destination library, and a background job copies everything with folders intact.
- **Key features:** resume after any failure, a report per job, and an incremental catch-up before cutover. Users who already exported a HighQ zip can upload it to SharePoint or extract it to a local folder.
- **Main constraint:** HighQ allows 2,000 API calls per 10 minutes per user, which caps one job at roughly 10,000 to 12,000 files per hour.

## Problem statement

Clients with documents in HighQ need them moved to SharePoint with the folder structure unchanged, and today that is done by hand.

The current process:

1. Download a zip of the site or folder from HighQ.
2. Unzip it on a local machine.
3. Recreate each folder in SharePoint and upload files one by one.

Why this does not scale:

- It takes hours of staff time per client, and grows with file count.
- Large zips fail and must be downloaded again from the start.
- There is no record of what was copied, so missed or renamed files go unnoticed.
- Files whose names SharePoint rejects fail silently or are renamed ad hoc.
- Copies of client documents sit on local machines, which is a confidentiality risk.

## Goals and non-goals

**Goals**

- Copy files and folders from a HighQ site or folder to a SharePoint document library, keeping relative paths, either through the HighQ API or from a HighQ zip export. Also save HighQ content or a HighQ zip to a local folder with the same paths.
- Self-service: users connect both systems and run jobs from a web page.
- Never store user passwords; keep tokens encrypted.
- Resume an interrupted job without copying finished files again.
- Show a preview before copying and a verification report after.
- Support a catch-up pass that copies only what changed since the first run.
- Design so a bulk engine (Option C) can be added without rewriting the job pipeline.

**Non-goals for the first release**

- Migrating HighQ permissions to SharePoint permissions.
- Keeping the original author ("Created by") on files. Graph uploads show the migrating user; Option C fixes this.
- Migrating full version history. Only the latest version is copied in release 1.
- Two-way sync. This is a one-way migration tool.
- Other HighQ modules (iSheets, wikis, blogs, tasks).

## As built (20 Nov 2026)

The sections after this one describe the target design. The working build follows it, with a few deliberate differences that let us test end to end without paid infrastructure or access we don't have yet.

| Area | Target design | As built today | Why |
| --- | --- | --- | --- |
| Hosting | Azure Container Apps | Render (free plan): one Docker image serving website and API, plus a SharePoint stand-in service | No Azure access yet; the same image moves to Azure unchanged |
| Microsoft sign-in | App-only access (Sites.Selected) | Each user signs in with their own Microsoft account (delegated) | Manager decision: copies run with the user's own SharePoint permissions and are attributed to them |
| SharePoint | Microsoft Graph | Graph-compatible stand-in, until IT provides an Entra ID app | No app registration or SharePoint tenant available yet; switching is configuration only |
| Job engine | Spring Batch, database, Service Bus | In-process background jobs; resume by skipping files already at the destination with the same modified date | No database needed to prove the flow; Spring Batch comes with production hosting |
| Graph access | Microsoft Graph SDK | Direct HTTP calls (five operations) | Small surface, and easy to point at the stand-in |
| Secrets | Azure Key Vault | Environment settings (Render dashboard, local .env file) | Key Vault arrives with Azure hosting |
| Zip uploads | Azure Blob Storage | Server's temporary disk, deleted after the copy by default | Same reason as hosting |
| Local destinations | Browser writes the files | As designed (File System Access API) | No change |

**How it is set up**

- **Code:** private GitHub repository `HighQ-to-Sharepoint`.
- `backend/`: Java 21, Spring Boot. HighQ sign-in and site gates, folder walk, file streaming, Microsoft sign-in, SharePoint uploads, transfer jobs.
- `frontend/`: React. The four migration tabs; saving to local folders and zip extraction run in the browser.
- `sharepoint-mock/`: the SharePoint and Microsoft sign-in stand-in (no dependencies).
- `infra/register-entra-app.ps1`: registers the Entra ID app once someone with rights runs it.
- `Dockerfile` and `render.yaml`: hosting. `docker-compose.yml`: local development. `DEPLOY.md`: setup steps.
- **Hosted:** https://highq-migration.onrender.com, rebuilt automatically on every push to `main`. Every build runs the automated tests; a failing test stops the deploy.
- **Local development:** `docker compose up` (backend and stand-in) plus the React dev server.
- **Configuration:** HighQ settings (`HIGHQ_` values), Microsoft and SharePoint settings (`GRAPH_` values) and app settings (`APP_` values) are environment values. Switching SharePoint from the stand-in to real changes only the `GRAPH_` values.

## Solution overview and architecture

In the target design, a web app takes the job request, a queue hands it to a Spring Batch worker, and the worker streams each file from HighQ straight into SharePoint.

> Diagram (online version of this design): browser → web app (Spring Boot) → Service Bus queue → Spring Batch worker, which reads from HighQ and writes to SharePoint through Microsoft Graph. The worker and web app share a database (job repository and file manifest); Key Vault holds secrets, Application Insights collects logs, and Blob Storage holds uploaded zips.

The web app reads job progress from the same database the worker writes to. Key Vault and Application Insights serve both. Blob Storage holds uploaded zips now, and Option C staging later.

**How a user runs a migration**

1. Connects HighQ and SharePoint once. If a HighQ site has a passcode or terms and conditions, the app shows HighQ's link once so the user can complete it.
2. Picks a HighQ site or folder and a SharePoint site, library and folder.
3. Reviews the preview: file count, size, renamed files, estimated time.
4. Starts the job and watches progress live; can cancel and re-run to resume.
5. Downloads the report when it finishes, and runs a catch-up pass before cutover.

## Technology choices

We chose Java 21 with Spring Batch because Spring Batch already provides restartable, chunked jobs, which is most of the resume feature for free.

**Why Spring Batch**

- Its job repository records every job, step and chunk in the database. A failed or stopped job restarts from the last committed chunk.
- Built-in retry and skip policies handle transient API errors and bad files without custom code.
- Partitioned steps run folders or file ranges in parallel with a fixed thread count, which suits the HighQ rate limit.
- Mature, widely known, and well supported on Azure.

**Alternatives considered**

| Option | Strength | Why not chosen |
| --- | --- | --- |
| .NET 8 | Most polished Microsoft Graph and Azure libraries | No equivalent of Spring Batch; restart and chunking would be hand-built |
| Python (FastAPI) | Fastest to prototype | Less mature Graph SDK; job machinery would be hand-built |
| Power Automate / Logic Apps | Low-code | Per-action cost, poor fit for deep folder trees and large files, weak resume |

**Stack**

| Layer | Choice | Reason |
| --- | --- | --- |
| Backend | Java 21, Spring Boot 3, Spring Batch 5 | Restartable jobs, retry/skip, partitioning |
| SharePoint access | Microsoft Graph | Official API, with resumable large-file upload sessions |
| HighQ access | Java HTTP client against the HighQ REST API | Streams downloads without loading files into memory |
| Front end | React | Job setup, live progress |
| Database | Azure SQL or Azure Database for PostgreSQL | Spring Batch job repository plus our file manifest |
| Hosting | Azure Container Apps | Long-running jobs (Azure Functions time out), scale workers by queue depth |
| Queue | Azure Service Bus | Hands jobs from the web app to workers |
| Secrets | Azure Key Vault with Managed Identity | No secrets in configuration |
| Monitoring | Azure Application Insights | Logs, rate-limit and throttling metrics |

## Migration job flow

In the target design each job is one Spring Batch job with six steps; nothing is copied until the user approves the preview.

> Diagram (online version of this design): Discover → Dry run → user approves (if not, adjust renames or scope and dry-run again) → Bookmark → Transfer → Verify → Catch-up, which feeds new or changed files back into Transfer until cutover.

The bookmark is saved before copying starts, so files changed during a long transfer are picked up by catch-up rather than missed.

| Step | What happens | Spring Batch construct |
| --- | --- | --- |
| 1. Discover | Walk the HighQ folder tree; one manifest row per file with path, size, dates, author, version | Tasklet step |
| 2. Dry run | Fix names SharePoint rejects, check path lengths, total size and estimated time; user approves | Tasklet step, then the job waits for approval |
| 3. Bookmark | Call the HighQ changes API with an empty `syncpositionid` and store the position it returns | Tasklet step |
| 4. Transfer | Create folders, stream each file from HighQ into a Graph upload session, set timestamps | Partitioned chunk step: reader = manifest, writer = destination engine |
| 5. Verify | Compare hashes and per-folder counts and sizes; build the CSV report | Chunk step + tasklet |
| 6. Catch-up | Read changes since the bookmark, 500 per call; copy new files and versions, apply moves and renames, and report deletions | Separate job, run on demand until cutover |

## Zip-based modes

Users who already exported a zip from the HighQ web interface get two more modes: upload it to SharePoint, or extract it to a local folder. Both rebuild relative paths from the zip.

> Diagram (online version of this design): two modes run on the server (HighQ REST API and uploaded HighQ zip, both feeding the same pipeline into SharePoint); the local extract mode runs entirely in the user's browser, from a zip on the PC to a local folder.

Both server modes share one pipeline and differ only in where files are read from; the local mode applies the same rules in the browser.

**Shared rules for both zip modes**

- The zip is the HighQ bundle export with "Include folder structure" and "Include file metadata" selected.
- The metadata file is meant to be the source of truth for each file's relative path, name and dates. Until we have a sample export, zip entry paths are used.
- Names and paths go through the same cleanup rules as the API mode, so results match across modes.
- Reject unsafe entries: paths containing `..` or starting with `/` ("zip slip"), and archives whose unpacked size is far larger than the zip ("zip bomb").
- The exact metadata file name and layout are not publicly documented. We need a sample export before building the parser.

### Zip upload to SharePoint

The user uploads the zip, and a background job extracts it and copies each file to SharePoint with its relative path.

1. **Upload:** in production, the browser sends the zip straight to Azure Blob Storage in blocks, so large uploads resume after a dropped connection.
2. **Validate:** the worker checks the zip is readable and safe.
3. **Build manifest:** one row per file, with its relative path.
4. **Transfer and verify:** the same steps as the API mode. Each file is read straight out of the zip and streamed to SharePoint.
5. **Clean up:** the zip is deleted when the job finishes, unless the user chooses to keep it. A storage rule deletes any leftover zips after 7 days.

This mode needs no HighQ API access, so HighQ's rate limit does not apply. It is usually faster than the API mode, and works before a HighQ admin registers our API application.

Later option: our HighQ API can build the zip itself (`POST /files/zipdownload`, up to 100 files or folders per request), so a future version could fetch the zip for the user.

### Zip extract to a local folder

The user picks a zip and a folder on their PC; the browser extracts the zip into that folder with relative paths. Nothing is uploaded.

A hosted web app cannot write to a user's disk, so this mode runs entirely in the browser:

1. The user selects the zip and a destination folder. The browser asks permission for that folder once.
2. The browser reads the zip with the zip.js library.
3. Each file is written into the chosen folder at its relative path through the browser's File System Access API.
4. **Resume:** on a re-run, files already present with the right size are skipped.

**Why in the browser**

- Client documents never leave the user's machine, which suits confidential legal content.
- No upload wait and no hosting storage or compute cost.
- The browser can only write inside the folder the user picked, so unsafe zip paths cannot escape it.

**Limits**

- The File System Access API works in Chrome and Edge on desktop, not in Firefox or Safari. Most corporate Windows users have Edge.
- Windows limits full paths to 260 characters by default. Longer names are shortened and the change is logged.

### HighQ to a local folder

The user picks a HighQ site or folder and a folder on their PC; files arrive with the same structure and numbering. Proven first with a PowerShell prototype on site 231 (20 Nov 2026), then in the app.

Decided 20 Nov 2026: the browser does the writing, as in the zip extract mode.

1. The backend signs in to HighQ, clears site gates, walks the folders and builds the manifest, exactly as for SharePoint.
2. The browser asks for a destination folder once (File System Access API).
3. For each file, the browser fetches it through our backend, which streams it from HighQ, and writes it to its relative path.
4. Resume: files already downloaded at the same HighQ version are skipped; files are only written once complete.

The four modes are built from two halves: one backend (HighQ sign-in, gates, folder walk, SharePoint uploads) and one browser writer (local folders).

## Resume, retry and verification

Every file has a row in a manifest, so a stopped job resumes at the right file.

**Manifest (one row per file)**

| Field | Purpose |
| --- | --- |
| HighQ file id, version id | Source identity |
| Relative path (original and cleaned) | Where it lands in SharePoint |
| Size, created date, modified date | Planning, verification, timestamps |
| Created-by email | Not usable by Graph; kept for Option C |
| Status | Pending, in progress, done, skipped, failed |
| Upload session URL, bytes committed | Mid-file resume |
| SharePoint item id, hash | Verification |
| Error message, attempt count | Failure report |

**Resume**

- **Between files:** in production, Spring Batch restarts the job from the last committed chunk. Today, re-running skips files already in SharePoint with the same modified date.
- **Mid-file:** a Graph upload session survives a restart and reports which bytes it still needs. HighQ rejects byte-range requests (tested: a Range header returns HTTP 500), so the worker downloads the file again and skips the bytes SharePoint already has.
- **Expired session:** that one file restarts from zero.

**Retry and rate limits**

- Read the rate-limit headers on every HighQ response and slow down before reaching zero.
- On HTTP 429 from HighQ or Microsoft Graph, wait as told (`Retry-After`), then retry.
- Refresh the access token before it expires; HighQ may return 403 or 401 for an expired token.
- 3 files in parallel per job today, configurable.
- After the retry limit, the file is marked failed and the job continues.

**Verification**

- Every file's size is checked against what the source sent (`Content-Length`).
- HighQ provides no file hash. Production adds Microsoft's QuickXorHash, computed while streaming and compared with the hash SharePoint returns.
- A CSV report per job: copied, renamed, skipped, failed, with reasons.

## Security and credentials

Users sign in to HighQ and Microsoft through OAuth, so the app never sees or stores a password.

**Why not SHA-256**

SHA-256 is a one-way hash. A hashed secret can never be turned back into the original, so the app could not use it to call HighQ or SharePoint. Credentials the app must reuse have to be encrypted, not hashed.

**Signing in**

- **Connect HighQ** and **Sign in with Microsoft** buttons, each opening the vendor's own sign-in page.
- Each user signs in with their own accounts; copies run with that user's HighQ and SharePoint permissions.
- Status shown per connection, with sign-out.

**What is stored and where (production)**

| Item | Stored where | Protection |
| --- | --- | --- |
| User passwords | Nowhere | OAuth sign-in on the vendor's page |
| HighQ and Microsoft app client ids and secrets | Azure Key Vault | Read by the app through Managed Identity |
| HighQ and Microsoft refresh tokens (per user) | Database | AES-256-GCM, key held in Key Vault |
| Access tokens | Memory only | Short-lived, never written to disk |

**Permissions**

- Microsoft: one Entra ID app registration with delegated Microsoft Graph permissions offline_access, openid, profile, User.Read and Sites.ReadWrite.All (decided 20 Nov 2026 by management, instead of app-only Sites.Selected). Every copy runs with the signed-in user's own SharePoint access and is attributed to them.
- HighQ: an API application registered on the HighQ instance. Our test client: access token 30 days, refresh token 1 year.

**Other controls**

- Files stream through memory to their destination.
- HTTPS everywhere; secure, HTTP-only session cookies.
- Every download appears in HighQ's own audit log under the migrating user. Tell each client's compliance team before a job runs.
- Unredacted originals of redacted files are only downloaded on an explicit choice, and each one is logged.

## API constraints and rate limits

HighQ's API rate limit is the main throughput limit: 2,000 calls per 10 minutes per user token, or roughly 10,000 to 12,000 small files per hour per job.

**HighQ endpoints we rely on**

Checked against our instance's swagger.json (Collaborate 5.12.4, OpenAPI 3.0, 375 endpoints, API versions up to at least 20). Sign-in is OAuth2 authorization code (`authorize.action` and `/api/oauth2/token`), and every endpoint we use returns JSON or XML.

| Purpose | Endpoint | Confirmed in our swagger |
| --- | --- | --- |
| List subfolders | `GET /folders/{id}/items` | limit=-1 returns all. Items the caller cannot see are silently left out |
| Folder path and details | `GET /folders/details` | API v20+. Folder plus its parent path and direct subfolders |
| List files | `GET /files?q="folderid=N"` | limit=-1 returns all; offset is an item ID, not a position |
| Download | `GET /files/{id}/content` | Options: version, original. **No byte-range support.** Users without "download original" rights get the encrypted copy |
| Preview counts | `POST /folders/itemcount` | API v17+. File and subfolder counts for the whole tree in one call |
| Version history | `GET /files/{id}/versions` | Number, date, author, notes, extension |
| Incremental changes | `GET /changes` | Empty syncpositionid returns the current position; 500 changes per call; needs content-manager access |
| Zip bundle | `POST /files/zipdownload` | API v17+. Up to 100 items per request; refused for gated sites |

**Live test results (20 Nov 2026, site 231, read-only calls)**

- **Sign-in works.** Every call sends `Auth-Type: OAUTH2` and a bearer token.
- **Token lifetimes:** access token 30 days, refresh token 1 year (fixed by HighQ). The sign-in code is valid for 30 minutes.
- **Site security gates can be passed per token.** A gated site answers HTTP 403 with a link to HighQ's trust page. The user completes it once and the same token then works. Tested for site passcode and terms and conditions.
- **Passcode trust is per token; terms acceptance is per user.**
- **Rate limits confirmed:** 2,000 calls per 10-minute window per user, reported in response headers.
- **File-list fields confirmed:** name, id, folderID, siteid, versionid, created and modified dates, type, originaltype, size, location, created-by and modified-by with email, permission, auto-numbering.
- **Downloads work, byte ranges do not.** A plain download returns the full file with its size; a Range header returns HTTP 500.
- **Counts match.** itemcount reported 20 subfolders and 34 files, matching the folder walk.

**Path and naming rules learned from the test**

- File names have no extension in the API: the file name is name + "." + type.
- Folder and file names keep HighQ's numbering (decided 20 Nov 2026): auto-numbering + space + name, e.g. "1 Structure", nested "4.1 Test", "19 HR_PerformanceImprovementPolicy.docx". This also prevents clashes: site 231 has two folders named "Folder 1" (numbers 9 and 13). The number only appears in list responses, not on a folder's own details call.
- location is the parent path; dates look like "06 Nov 2026 09:32" with no time zone and are treated as UTC.

**Redacted files**

- Files marked as redacted are served as the redacted copy, whose size differs from the listing (site 231: 4 files). Integrity is checked against the size actually sent.
- Default (decided 20 Nov 2026): migrate the redacted copy and mark each one in the report.
- Originals only on an explicit, logged choice, with an account that has "download original" rights. Client or manager sign-off needed.

**Other file cases found in the test**

- Placeholder files have no content (downloading one returns 403): each is created as an empty file with its HighQ name, so the folder keeps the slot, and is listed in the report.
- Names with square or curly brackets are handled as literal text everywhere.
- Local downloads hit Windows' 260-character path limit (one site 231 path was 335): the name is shortened, keeping the number and adding a unique id, and the change is logged.

**Rate limits**

| System | Limit | When exceeded |
| --- | --- | --- |
| HighQ, per user token | 2,000 calls / 10 min | HTTP 429 until the window resets |
| HighQ, per application | 8,000 calls / 2 min, shared by all users | HTTP 429 |
| Microsoft Graph / SharePoint | Dynamic, per app and per tenant | HTTP 429 or 503 with Retry-After |

**SharePoint naming rules handled**

- Characters not allowed: `" * : < > ? / \ |`
- No leading or trailing spaces or trailing dots.
- Reserved names such as CON, PRN, AUX, NUL.
- Full path no longer than 400 characters; longer names fall back to a short unique name.

## Future path to Option C

Option C swaps only the upload engine; discovery, the manifest, verification and catch-up stay the same.

**What Option C is**

Instead of uploading file by file through Microsoft Graph, the worker stages files and a manifest in Azure Blob Storage. SharePoint then imports the whole package server-side through the SharePoint Migration API.

**When to use it**

- A client has more than about 1 TB or several hundred thousand files.
- The client needs original authors ("Created by", "Modified by") kept.
- The client needs full version history migrated.

**What we keep in place for it**

- **One destination interface,** so a Migration API writer can sit beside the Graph writer.
- **Capture everything at discovery:** author email, created date and version ids are already read from HighQ.
- **Engine chosen per job:** a setting on each job, Graph by default.

## Risks and open questions

The top risk is HighQ's site security gates: they block the API until the user completes a browser step for each sign-in, so the app builds that step in.

**Top risk: site security gates**

Tested on 20 Nov 2026: site 231's passcode and terms-and-conditions gates both returned 403 with a link. Completing the link in the browser unlocked the API for that same token. How the app handles this:

1. **Check the source site before a job.** Any 403 with a link is a gate.
2. **Show the user HighQ's link** in the job setup. They complete it (enter the emailed passcode, or accept the terms), then check again, and the app continues with the same token.
3. **Keep the token alive with refresh tokens, not new sign-ins.** Whether passcode trust survives a token refresh is still untested.
4. **Fallback for gates the link cannot clear** (site password, IP restriction): the zip upload mode, or a site admin relaxes the gate during the migration window with approval.

**Risks**

| Risk | Impact | Mitigation |
| --- | --- | --- |
| HighQ rate limit caps speed | Large clients take days | Show an estimate up front; offer Option C for the largest |
| Changes API needs content-admin access | No incremental catch-up | Request the role up front, or re-scan and compare |
| No byte-range downloads | Large files re-download on resume | Skip already-uploaded bytes; acceptable cost |
| Files arrive encrypted without "download original" rights | Unreadable copies | Use a migration account with those rights; flag remaining files in the report |
| Placeholder files with no content | Download fails | Create them as empty files and list them in the report |
| Mass download shows in HighQ audit log | Compliance questions | Notify the client before each job |
| Microsoft Graph throttling | Slower uploads | Honour Retry-After; cap parallel uploads |

**Open questions**

- [ ] Who provides the dedicated migration account with content-admin and "download original" rights on each site?
- [ ] Typical size per client, to decide when Option C is needed.
- [ ] Is each client's SharePoint in their own Microsoft tenant, or ours?
- [ ] Who in each client firm grants Microsoft admin consent and HighQ API registration?
- [ ] A sample HighQ bundle export with file metadata, to confirm the metadata file's name and format.
- [ ] Maximum zip size to accept for upload.

**Decisions needed from management**

- [ ] Approve Option B with Java 21 and Spring Batch on Azure.
- [ ] Approve Azure resource costs: Container Apps, database, Key Vault, Blob Storage, Application Insights.
- [ ] Agree that release 1 excludes permissions, original authors and version history.

## Delivery phases

Phase 1 delivers a usable migration tool; later phases start only after their gate is met.

| Phase | Scope | Gate to start |
| --- | --- | --- |
| Phase 1 · MVP | Connect HighQ and SharePoint, streamed copy with resume, CSV report, zip upload to SharePoint | (built; real SharePoint pending the Entra ID app) |
| Phase 2 · Hardening | Catch-up via HighQ changes API, encrypted token store, Spring Batch with a database, staff single sign-on | Pilot on one client site |
| Phase 3 · Option C | Migration API bulk engine, authors and timestamps, version history, optional permission mapping | A client needs scale or original authors |

## Sources

- HighQ developer portal: API rate limit, Get folder list v2, Get files list v2, Download file, Changes list v2, Managing token expiration, API access using OAuth2 (developerportal.thomsonreuters.com, pages last updated between June 2023 and November 2024).
- HighQ help: Download or export a bundle of files; Download files and folders in bulk (thomsonreuters.com/en-gb/help/highq).
- Our HighQ instance's swagger.json (Collaborate 5.12.4), reviewed on 19 Nov 2026.
