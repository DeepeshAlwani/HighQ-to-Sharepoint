// Local stand-in for the slice of Microsoft Graph (SharePoint) the migration uses. For testing only.
// Files land on disk under DATA_DIR/<host>/<site path>/<library>/..., so the result can be inspected directly.
// Mimics: Microsoft user sign-in (authorization code + refresh token, expiring access tokens), /me,
// site lookup, libraries, items by path, folder creation, upload sessions with Content-Range chunks,
// fileSystemInfo dates, SharePoint's name rules, and optional 429 throttling.
import http from 'node:http'
import fs from 'node:fs'
import path from 'node:path'
import crypto from 'node:crypto'

const PORT = Number(process.env.PORT || 4000)
const DATA = process.env.DATA_DIR || '/data'
const THROTTLE_EVERY = Number(process.env.MOCK_THROTTLE_EVERY || 0)   // e.g. 25: every 25th Graph call gets a 429
const TOKEN_SECONDS = Number(process.env.MOCK_TOKEN_SECONDS || 3600)  // lower it to exercise token refresh
const INVALID_NAME = /["*:<>?/\\|]/                                    // characters SharePoint rejects in a name
const sessions = new Map()
const codes = new Map()   // sign-in code -> { user, redirectUri }
let calls = 0

const escapeHtml = s => String(s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c])

function issueTokens(user) {
  const u = Buffer.from(user).toString('base64url')
  return { token_type: 'Bearer', expires_in: TOKEN_SECONDS, scope: 'Sites.ReadWrite.All User.Read',
    access_token: `mock-at.${u}.${Date.now() + TOKEN_SECONDS * 1000}`, refresh_token: `mock-rt.${u}` }
}

/** The signed-in user for a Graph request, or a reason it was refused. */
function userFor(req) {
  const m = /^Bearer mock-at\.([\w-]+)\.(\d+)$/.exec(req.headers.authorization || '')
  if (!m) return { error: 'Access token is missing or invalid' }
  if (Number(m[2]) < Date.now()) return { error: 'Lifetime validation failed, the token is expired.' }
  return { user: Buffer.from(m[1], 'base64url').toString() }
}

/** Microsoft's sign-in page, reduced to an email box and a button. */
function authorizePage(res, url) {
  const q = url.searchParams
  const redirectUri = q.get('redirect_uri')
  if (!redirectUri || !q.get('client_id') || q.get('response_type') !== 'code') {
    return fail(res, 400, 'invalid_request', 'client_id, redirect_uri and response_type=code are required')
  }
  if (q.get('approve') === '1') {
    const user = (q.get('login') || '').trim()
    if (!user.includes('@')) return fail(res, 400, 'invalid_request', 'Enter an email address')
    const code = crypto.randomUUID()
    codes.set(code, { user, redirectUri })
    const back = new URL(redirectUri)
    back.searchParams.set('code', code)
    if (q.get('state')) back.searchParams.set('state', q.get('state'))
    res.writeHead(302, { Location: back.toString() })
    return res.end()
  }
  const hidden = [...q.entries()].map(([k, v]) => `<input type="hidden" name="${escapeHtml(k)}" value="${escapeHtml(v)}">`).join('')
  res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' })
  res.end(`<!doctype html><title>Mock Microsoft sign-in</title>
<body style="font-family:system-ui;max-width:420px;margin:60px auto">
<h2>Mock Microsoft sign-in</h2><p>Local test only. A real deployment shows Microsoft's own sign-in page here.</p>
<form method="get">${hidden}<input type="hidden" name="approve" value="1">
<label>Email <input name="login" value="tester@contoso.com" style="width:100%;padding:6px" autofocus></label>
<p><button style="padding:6px 16px">Sign in</button></p></form></body>`)
}

async function tokenEndpoint(req, res) {
  const form = new URLSearchParams((await readBody(req)).toString())
  if (!form.get('client_id') || !form.get('client_secret')) return send(res, 401, { error: 'invalid_client', error_description: 'Client id and secret are required' })
  const grant = form.get('grant_type')
  if (grant === 'authorization_code') {
    const c = codes.get(form.get('code'))
    codes.delete(form.get('code'))   // codes are single use
    if (!c) return send(res, 400, { error: 'invalid_grant', error_description: 'The code is invalid or was already used' })
    if (c.redirectUri !== form.get('redirect_uri')) return send(res, 400, { error: 'invalid_grant', error_description: 'redirect_uri does not match' })
    return send(res, 200, issueTokens(c.user))
  }
  if (grant === 'refresh_token') {
    const m = /^mock-rt\.([\w-]+)$/.exec(form.get('refresh_token') || '')
    if (!m) return send(res, 400, { error: 'invalid_grant', error_description: 'Invalid refresh token' })
    return send(res, 200, issueTokens(Buffer.from(m[1], 'base64url').toString()))
  }
  return send(res, 400, { error: 'unsupported_grant_type', error_description: 'Use authorization_code or refresh_token' })
}

const b64 = s => Buffer.from(s).toString('base64url')
const unb64 = s => Buffer.from(s, 'base64url').toString()

function send(res, status, body, headers = {}) {
  res.writeHead(status, { 'Content-Type': 'application/json', ...headers })
  res.end(body === undefined ? '' : JSON.stringify(body))
}
const fail = (res, status, code, message) => send(res, status, { error: { code, message } })

function readBody(req) {
  return new Promise((resolve, reject) => {
    const parts = []
    req.on('data', c => parts.push(c))
    req.on('end', () => resolve(Buffer.concat(parts)))
    req.on('error', reject)
  })
}

/** Decoded, validated path segments; throws on anything that could escape the data folder. */
function segments(encoded) {
  const segs = encoded.split('/').filter(Boolean).map(s => decodeURIComponent(s))
  if (segs.some(s => s === '.' || s === '..')) throw Object.assign(new Error('Path may not contain . or ..'), { status: 400 })
  return segs
}

const driveDir = driveId => path.join(DATA, ...segments(unb64(driveId)))
const fsPath = (driveId, segs) => path.join(driveDir(driveId), ...segs)

function item(driveId, segs) {
  const st = fs.statSync(fsPath(driveId, segs))
  const when = st.mtime.toISOString()
  return {
    id: b64(driveId + '|' + segs.join('/')),
    name: segs.at(-1) ?? 'root',
    size: st.isFile() ? st.size : 0,
    ...(st.isDirectory() ? { folder: { childCount: fs.readdirSync(fsPath(driveId, segs)).length } } : { file: {} }),
    fileSystemInfo: { lastModifiedDateTime: when },
    lastModifiedDateTime: when,
    parentReference: { driveId, path: '/drive/root:/' + segs.slice(0, -1).join('/') },
  }
}

function checkName(name) {
  if (!name || INVALID_NAME.test(name) || name.startsWith(' ') || name.endsWith(' ') || name.endsWith('.')) {
    throw Object.assign(new Error(`The name "${name}" is not allowed in SharePoint`), { status: 400, code: 'invalidRequest' })
  }
}

function setModified(file, iso) {
  if (!iso) return
  const d = new Date(iso)
  if (!Number.isNaN(d.getTime())) fs.utimesSync(file, d, d)
}

async function graph(req, res, url) {
  const who = userFor(req)
  if (who.error) return fail(res, 401, 'InvalidAuthenticationToken', who.error)
  if (THROTTLE_EVERY && ++calls % THROTTLE_EVERY === 0) {
    return send(res, 429, { error: { code: 'TooManyRequests', message: 'Throttled (mock)' } }, { 'Retry-After': '1' })
  }
  const p = url.pathname.slice('/v1.0'.length)
  if (p === '/me' && req.method === 'GET') {
    return send(res, 200, { displayName: who.user.split('@')[0], userPrincipalName: who.user, mail: who.user })
  }

  // GET /sites/{host}  or  /sites/{host}:/{site path}
  let m = p.match(/^\/sites\/([^/]+)\/drives$/)
  if (m && req.method === 'GET') {
    const siteKey = unb64(m[1])
    segments(siteKey)
    return send(res, 200, { value: [{ id: b64(siteKey + '/Documents'), name: 'Documents', driveType: 'documentLibrary' }] })
  }
  if (p.startsWith('/sites/') && req.method === 'GET') {
    const rest = p.slice('/sites/'.length)
    const colon = rest.indexOf(':')
    const host = colon < 0 ? rest : rest.slice(0, colon)
    const sitePath = colon < 0 ? '' : segments(rest.slice(colon + 1)).join('/')
    const key = sitePath ? `${host}/${sitePath}` : host
    return send(res, 200, { id: b64(key), name: sitePath.split('/').at(-1) || host, displayName: sitePath.split('/').at(-1) || host, webUrl: `https://${host}/${sitePath}` })
  }

  // /drives/{id}/root[:/{path}:][/children|/content|/createUploadSession]
  m = p.match(/^\/drives\/([^/]+)\/root(.*)$/)
  if (!m) return fail(res, 404, 'itemNotFound', 'Unknown route (mock)')
  const driveId = m[1]
  let rest = m[2], encodedPath = '', action = ''
  if (rest.startsWith(':')) {
    const parts = rest.split(':')            // ['', '/a/b', '/children']; raw ':' cannot appear inside encoded names
    encodedPath = parts[1]
    action = parts[2] || ''
  } else action = rest
  const segs = segments(encodedPath)
  const target = fsPath(driveId, segs)
  fs.mkdirSync(driveDir(driveId), { recursive: true })

  if (action === '' && req.method === 'GET') {
    return fs.existsSync(target) ? send(res, 200, item(driveId, segs)) : fail(res, 404, 'itemNotFound', 'The resource could not be found.')
  }
  if (action === '' && req.method === 'PATCH') {
    if (!fs.existsSync(target)) return fail(res, 404, 'itemNotFound', 'The resource could not be found.')
    const body = JSON.parse((await readBody(req)).toString() || '{}')
    setModified(target, body.fileSystemInfo?.lastModifiedDateTime)
    return send(res, 200, item(driveId, segs))
  }
  if (action === '/children' && req.method === 'POST') {
    const body = JSON.parse((await readBody(req)).toString() || '{}')
    checkName(body.name)
    if (!fs.existsSync(target)) return fail(res, 404, 'itemNotFound', 'Parent folder not found.')
    const child = path.join(target, body.name)
    if (fs.existsSync(child)) {
      if (body['@microsoft.graph.conflictBehavior'] === 'fail') return fail(res, 409, 'nameAlreadyExists', 'An item with the same name already exists.')
    } else fs.mkdirSync(child)
    return send(res, 201, item(driveId, [...segs, body.name]))
  }
  if (action === '/content' && req.method === 'PUT') {
    segs.forEach(checkName)
    const body = await readBody(req)
    fs.mkdirSync(path.dirname(target), { recursive: true })
    fs.writeFileSync(target, body)
    return send(res, 201, item(driveId, segs))
  }
  if (action === '/createUploadSession' && req.method === 'POST') {
    segs.forEach(checkName)
    const body = JSON.parse((await readBody(req)).toString() || '{}')
    const id = crypto.randomUUID()
    const tmp = path.join(DATA, '.uploads', id)
    fs.mkdirSync(path.dirname(tmp), { recursive: true })
    fs.writeFileSync(tmp, '')
    sessions.set(id, { driveId, segs, tmp, received: 0, modified: body.item?.fileSystemInfo?.lastModifiedDateTime })
    return send(res, 200, {
      uploadUrl: `${req.headers['x-forwarded-proto'] || 'http'}://${req.headers.host}/upload/${id}`,   // https when hosted
      expirationDateTime: new Date(Date.now() + 3600_000).toISOString(),
      nextExpectedRanges: ['0-'],
    })
  }
  return fail(res, 400, 'invalidRequest', `Unsupported ${req.method} ${action || 'item'} (mock)`)
}

async function uploadChunk(req, res, id) {
  const s = sessions.get(id)
  if (!s) return fail(res, 404, 'itemNotFound', 'Upload session not found or expired')
  // Real upload URLs are pre-authorised; sending a token to them is a client bug.
  if (req.headers.authorization) return fail(res, 401, 'unauthenticated', 'Do not send Authorization to an upload URL')
  const m = /^bytes (\d+)-(\d+)\/(\d+)$/.exec(req.headers['content-range'] || '')
  if (!m) return fail(res, 400, 'invalidRequest', 'Content-Range header missing or malformed')
  const [start, end, total] = m.slice(1).map(Number)
  const body = await readBody(req)
  if (start !== s.received) return fail(res, 416, 'invalidRange', `Expected range starting at ${s.received}`)
  if (body.length !== end - start + 1) return fail(res, 400, 'invalidRequest', 'Body length does not match Content-Range')
  fs.appendFileSync(s.tmp, body)
  s.received = end + 1
  if (s.received < total) return send(res, 202, { nextExpectedRanges: [`${s.received}-`] })

  const target = fsPath(s.driveId, s.segs)
  fs.mkdirSync(path.dirname(target), { recursive: true })
  fs.renameSync(s.tmp, target)
  setModified(target, s.modified)
  sessions.delete(id)
  return send(res, 201, item(s.driveId, s.segs))
}

http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://mock')
  res.on('finish', () => console.log(`${req.method} ${url.pathname} -> ${res.statusCode}`))
  try {
    if (req.method === 'GET' && /^\/[^/]+\/oauth2\/v2\.0\/authorize$/.test(url.pathname)) return authorizePage(res, url)
    if (req.method === 'POST' && /^\/[^/]+\/oauth2\/v2\.0\/token$/.test(url.pathname)) return await tokenEndpoint(req, res)
    const up = url.pathname.match(/^\/upload\/([\w-]+)$/)
    if (up && req.method === 'PUT') return await uploadChunk(req, res, up[1])
    if (url.pathname.startsWith('/v1.0/')) return await graph(req, res, url)
    return fail(res, 404, 'itemNotFound', 'Unknown route (mock)')
  } catch (e) {
    return fail(res, e.status || 500, e.code || 'generalException', e.message)
  }
}).listen(PORT, () => console.log(`SharePoint mock listening on ${PORT}, data in ${DATA}`))
