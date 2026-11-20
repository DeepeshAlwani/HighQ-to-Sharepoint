# HighQ Migration

Moves HighQ documents to a local folder (now) and SharePoint (later), keeping folder structure and HighQ numbering.
Design: see the "HighQ to SharePoint Migration – Solution Design" doc.

| Mode | Status |
| --- | --- |
| HighQ → local folder | built |
| Zip → local folder | built (uses zip paths; HighQ metadata file pending a sample) |
| HighQ → SharePoint | built, tested against the SharePoint mock |
| Zip → SharePoint | built, tested against the SharePoint mock |

## Real Microsoft sign-in (Entra ID app)

Users sign in with their own Microsoft work account; the app needs one Entra ID app registration.

1. Install the Azure CLI once: `winget install Microsoft.AzureCLI`
2. Run `.\infra\register-entra-app.ps1 -RenderUrl https://<app>.onrender.com` signed in as someone allowed to register apps.
   It creates the app (delegated Graph permissions offline_access, openid, profile, User.Read, Sites.ReadWrite.All),
   adds the local and Render callback URLs, creates a 1-year client secret, and writes the `GRAPH_*` values into `.env`.
3. If the users' organisation blocks user consent, its admin opens the admin-consent link the script prints.
4. `docker compose up --build` now uses real Microsoft sign-in and real SharePoint (values in `.env` override the mock).

## Host on Render

See [DEPLOY.md](DEPLOY.md): step-by-step setup, the values to enter, and what each connection (HighQ, Microsoft,
SharePoint) needs to go from mock to real.

## SharePoint mock

`docker compose up` also starts `sharepoint-mock`, a stand-in for Microsoft sign-in and the Graph calls we make
(user sign-in with refresh tokens, sites, libraries, folders, chunked upload sessions, dates, SharePoint's name rules).
"Sign in with Microsoft" opens a mock sign-in page on http://localhost:4000: enter any email.
Set `MOCK_TOKEN_SECONDS=125` to make tokens expire quickly and exercise the refresh.
Files it receives appear under `sharepoint-mock-data/`. Use any site URL, e.g. `https://contoso.sharepoint.com/sites/Migration`,
library `Documents`. Set `MOCK_THROTTLE_EVERY=7` before `docker compose up` to make every 7th call return 429.
To use real SharePoint, set the `GRAPH_*` values in `.env` (see `.env.example`).

## Run locally

Needs Docker Desktop, Node 22, and Chrome or Edge.

1. Copy `.env.example` to `.env` and fill in `HIGHQ_CLIENT_ID` and `HIGHQ_CLIENT_SECRET`. Never commit `.env`.
2. Backend (port 8080): `docker compose up --build`
3. Frontend (port 5173): `cd frontend`, `npm install`, `npm run dev`
4. Open http://localhost:5173

The HighQ API app's redirect URI must be `http://localhost:8080/callback`.

## Tests

- Backend: run as part of `docker compose up --build` (Maven `package`).
- Frontend: `cd frontend`, `npm test`, `npm run build`

## Layout

- `backend/`: Spring Boot. HighQ sign-in, site security gates, folder walk (manifest), file streaming. HighQ access is GET-only.
- `frontend/`: React. Writes into a folder the user picks (File System Access API); zip extraction runs in the browser.
- `highq-probe.ps1`, `highq-download.ps1`: PowerShell prototypes used to test the HighQ API.
