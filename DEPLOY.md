# Deploying to Render

## What it connects to right now

| System | Status today | What it needs to go live |
| --- | --- | --- |
| **HighQ** (source) | **Real.** Uses your own HighQ API app with OAuth sign-in. Tested on site 231. | Add the Render callback `https://<app>.onrender.com/callback` as a redirect URI on that HighQ API app. Site passcode / terms gates are handled in the app. |
| **Microsoft sign-in** | **Mock.** The `sharepoint-mock` service shows a stand-in sign-in page (any email works). | An Entra ID app registration from the firm's IT (spec below), then swap the `GRAPH_*` values. No code change. |
| **SharePoint** (destination) | **Mock.** Files land in the `sharepoint-mock` service's temporary disk and are lost when it restarts. | The same Entra app, plus a SharePoint site the signed-in user can edit (ideally a test site first). |
| **Local folder** (destination) | **Real.** The browser writes into a folder the user picks. Chrome or Edge on desktop only. | Nothing. |
| **Zip upload** (source) | **Real.** Paths come from the zip's own folders. | A sample HighQ export with "Include file metadata", to switch to the metadata file. |

**Entra ID app spec for IT** (or run `infra/register-entra-app.ps1` if you are allowed to register apps):
- Platform: Web, with a client secret.
- Redirect URIs: `https://<app>.onrender.com/auth/microsoft/callback` and `http://localhost:8080/auth/microsoft/callback`.
- Delegated Microsoft Graph permissions: `offline_access`, `openid`, `profile`, `User.Read`, `Sites.ReadWrite.All`, with admin consent.
- Send back: client id and client secret (securely).

## Steps

### 1. Put the code on GitHub
Render deploys from a Git repository. Use a **private** repository: the code names the HighQ instance.
`.env`, downloads and mock data are git-ignored, so no secrets or client files are pushed.

```powershell
git add -A
git commit -m "HighQ migration app"
git branch -M main
git remote add origin https://github.com/<you>/highq-migration.git
git push -u origin main
```

### 2. Create the services from the Blueprint
1. Sign in at https://dashboard.render.com and connect your GitHub account.
2. **New > Blueprint**, pick the repository. Render reads `render.yaml` and proposes two free web services:
   - `highq-migration`: the website and API (root `Dockerfile`).
   - `sharepoint-mock`: the SharePoint stand-in.
3. Render asks for every value marked `sync: false`. You can fill them now or after step 4. The first build takes
   about 5 to 10 minutes (it also runs all tests; a failing test stops the deploy).

### 3. Note the two addresses
Each service page shows its address, e.g. `https://highq-migration.onrender.com` and
`https://sharepoint-mock-xyz.onrender.com`. Render adds a suffix if the name is taken, so use what it shows.
Below, `<app>` and `<mock>` stand for these.

### 4. Set the environment values on `highq-migration`
(Service > Environment. Save triggers a redeploy.)

| Key | Value |
| --- | --- |
| `APP_FRONTEND_URL` | `https://<app>` |
| `HIGHQ_INSTANCE_URL` | `https://mishcondereya.highqsolutions.com/mishcondereya` |
| `HIGHQ_CLIENT_ID` | your HighQ client id |
| `HIGHQ_CLIENT_SECRET` | your HighQ client secret |
| `HIGHQ_REDIRECT_URI` | `https://<app>/callback` |
| `GRAPH_BASE_URL` | `https://<mock>/v1.0` |
| `GRAPH_AUTHORIZE_URL` | `https://<mock>/organizations/oauth2/v2.0/authorize` |
| `GRAPH_TOKEN_URL` | `https://<mock>/organizations/oauth2/v2.0/token` |
| `GRAPH_CLIENT_ID` | `mock-client` |
| `GRAPH_CLIENT_SECRET` | `mock-secret` |
| `GRAPH_REDIRECT_URI` | `https://<app>/auth/microsoft/callback` |

`COOKIE_SECURE`, `APP_UPLOAD_DIR` and `HIGHQ_API_VERSION` are already set by the Blueprint.

### 5. Register the HighQ callback
In HighQ, on your API app (the one you added the localhost URI to), add the redirect URI `https://<app>/callback`.
Without it, "Connect HighQ" fails on Render.

### 6. Try it
1. Open `https://<mock>` once to wake it (free services sleep; the first request can take about a minute).
2. Open `https://<app>` in Chrome or Edge.
3. HighQ tabs: Connect HighQ, check site 231, complete the passcode step.
4. SharePoint tabs: Sign in with Microsoft (mock page, any email), keep the example site URL, Check SharePoint, start.

### 7. Switch to real Microsoft and SharePoint (when IT has the Entra app)
Replace the `GRAPH_*` values with:

| Key | Value |
| --- | --- |
| `GRAPH_BASE_URL` | `https://graph.microsoft.com/v1.0` |
| `GRAPH_AUTHORIZE_URL` | `https://login.microsoftonline.com/organizations/oauth2/v2.0/authorize` |
| `GRAPH_TOKEN_URL` | `https://login.microsoftonline.com/organizations/oauth2/v2.0/token` |
| `GRAPH_CLIENT_ID` / `GRAPH_CLIENT_SECRET` | from IT |

Then delete the `sharepoint-mock` service. Use the firm's tenant id instead of `organizations` to allow only the firm's accounts.

## Limits of the free plan
- Services sleep after 15 minutes without traffic and restart on the next visit. A restart ends running jobs and
  sign-ins; re-running a job resumes (files already copied are skipped). Use a paid instance for real migrations.
- 512 MB memory and a temporary disk: large zip uploads are stored on that disk while they are copied.
  Large uploads through Render have not been tested yet.
- The site is public on the internet. Using it still needs a HighQ login and a Microsoft login, but anyone can use
  the mock (it is fake data only).
