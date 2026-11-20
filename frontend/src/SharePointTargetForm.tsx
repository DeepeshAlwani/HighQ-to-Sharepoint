import { useEffect, useState } from 'react'
import { postJson } from './api'

export type SharePointTarget = { siteUrl: string; library: string; folderPath: string }

/**
 * Sign in with Microsoft, then site URL + library + folder, checked against SharePoint before a job may start.
 * Copies run with the signed-in user's own SharePoint permissions.
 */
export default function SharePointTargetForm({ step, disabled, onChange }: {
  step: number
  disabled: boolean
  onChange: (t: SharePointTarget | null) => void
}) {
  const [ms, setMs] = useState<{ connected: boolean; user?: string } | null>(null)
  const [target, setTarget] = useState<SharePointTarget>({ siteUrl: 'https://contoso.sharepoint.com/sites/Migration', library: 'Documents', folderPath: '' })
  const [checked, setChecked] = useState<{ siteName: string; library: string; folderPath: string } | null>(null)
  const [error, setError] = useState(new URLSearchParams(location.search).get('microsoftError') ?? '')

  useEffect(() => {
    fetch('/auth/microsoft/status').then(r => r.json()).then(setMs).catch(() => setMs({ connected: false }))
  }, [])

  function edit(patch: Partial<SharePointTarget>) {
    setTarget(t => ({ ...t, ...patch }))
    setChecked(null)
    onChange(null)
  }

  async function check() {
    setError('')
    try {
      const r = await postJson<{ siteName: string; library: string; folderPath: string }>('/api/sharepoint/check', target)
      setChecked(r)
      onChange(target)
    } catch (e) {
      if ((e as { status?: number }).status === 401) setMs({ connected: false })
      setError(e instanceof Error ? e.message : String(e))
    }
  }

  async function signOut() {
    await fetch('/auth/microsoft/logout', { method: 'POST' })
    setMs({ connected: false })
    setChecked(null)
    onChange(null)
  }

  return (
    <div className="step">
      <h3>{step}. Choose the SharePoint destination</h3>
      {ms === null ? <p>Checking…</p>
        : ms.connected
          ? <p>Signed in to Microsoft as <b>{ms.user}</b>. Files are copied with your SharePoint permissions.{' '}
              <button onClick={signOut} disabled={disabled}>Sign out</button></p>
          : <p><a className="button" href="/auth/microsoft/login">Sign in with Microsoft</a>{' '}
              <span className="note">Use the work account that can edit the target SharePoint site.</span></p>}
      <label className="wide">Site URL <input value={target.siteUrl} disabled={disabled} onChange={e => edit({ siteUrl: e.target.value })} /></label>
      <label>Library <input value={target.library} disabled={disabled} onChange={e => edit({ library: e.target.value })} /></label>
      <label>Folder (optional) <input value={target.folderPath} disabled={disabled} onChange={e => edit({ folderPath: e.target.value })} placeholder="library root" /></label>
      <button onClick={check} disabled={disabled || !ms?.connected || !target.siteUrl.trim()}>Check SharePoint</button>
      {checked && <p className="ok">Ready: {checked.siteName} › {checked.library}{checked.folderPath ? ` › ${checked.folderPath}` : ''}</p>}
      {error && <p className="error">{error}</p>}
    </div>
  )
}
