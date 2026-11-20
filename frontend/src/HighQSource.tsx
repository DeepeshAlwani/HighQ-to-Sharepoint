import { useEffect, useState } from 'react'
import { errorText } from './api'

export type HighQSelection = { siteId: string; folderId: string; siteName: string }

type Gate =
  | { open: true; siteName: string; rootFolderId: string }
  | { open: false; summary: string; ref: string; link: string }

/**
 * Steps shared by every "from HighQ" mode: connect, choose a site, and clear its security step.
 * Calls onChange with the selection once the site is readable, or null when it isn't (yet).
 * Bump `resetKey` to force a fresh site check, e.g. after HighQ asks for the security step again.
 */
export default function HighQSource({ disabled, resetKey, onChange }: {
  disabled: boolean
  resetKey: number
  onChange: (s: HighQSelection | null) => void
}) {
  const [auth, setAuth] = useState<{ connected: boolean; email?: string } | null>(null)
  const [siteId, setSiteId] = useState('231')
  const [folderId, setFolderId] = useState('')
  const [gate, setGate] = useState<Gate | null>(null)
  const [error, setError] = useState(new URLSearchParams(location.search).get('highqError') ?? '')

  useEffect(() => {
    fetch('/auth/highq/status').then(r => r.json()).then(setAuth).catch(() => setAuth({ connected: false }))
  }, [])

  useEffect(() => { setGate(null) }, [resetKey])

  useEffect(() => {
    onChange(gate?.open ? { siteId: siteId.trim(), folderId: folderId.trim(), siteName: gate.siteName } : null)
  }, [gate, siteId, folderId])

  async function checkSite() {
    setError('')
    const r = await fetch(`/api/highq/sites/${encodeURIComponent(siteId.trim())}/gate`)
    if (!r.ok) return setError(await errorText(r))
    setGate(await r.json())
  }

  return (
    <>
      <div className="step">
        <h3>1. Connect HighQ</h3>
        {auth === null ? <p>Checking…</p>
          : auth.connected ? <p>Connected as <b>{auth.email ?? 'HighQ user'}</b>.</p>
          : <a className="button" href="/auth/highq/login">Connect HighQ</a>}
      </div>

      <div className="step">
        <h3>2. Choose the HighQ site</h3>
        <label>Site ID <input value={siteId} disabled={disabled} onChange={e => { setSiteId(e.target.value); setGate(null) }} /></label>
        <label>Folder ID (optional) <input value={folderId} disabled={disabled} onChange={e => setFolderId(e.target.value)} placeholder="whole site" /></label>
        <button onClick={checkSite} disabled={!auth?.connected || disabled}>Check site</button>
        {gate?.open === true && <p className="ok">Ready: {gate.siteName}</p>}
        {gate?.open === false && (
          <div className="warn">
            <p>HighQ says: {gate.summary}</p>
            <p>
              <a href={gate.link} target="_blank" rel="noreferrer">Open HighQ's security step</a>, complete it, then{' '}
              <button onClick={checkSite}>check again</button>
            </p>
          </div>
        )}
        {error && <p className="error">{error}</p>}
      </div>
    </>
  )
}
