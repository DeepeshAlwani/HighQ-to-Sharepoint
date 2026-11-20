import { useState } from 'react'
import { postJson } from './api'
import HighQSource, { type HighQSelection } from './HighQSource'
import JobProgress from './JobProgress'
import UnzipOption from './UnzipOption'
import SharePointTargetForm, { type SharePointTarget } from './SharePointTargetForm'

export default function HighQToSharePoint() {
  const [source, setSource] = useState<HighQSelection | null>(null)
  const [target, setTarget] = useState<SharePointTarget | null>(null)
  const [originals, setOriginals] = useState(false)
  const [unzip, setUnzip] = useState(false)
  const [jobId, setJobId] = useState<string | null>(null)
  const [running, setRunning] = useState(false)
  const [resetKey, setResetKey] = useState(0)
  const [error, setError] = useState('')

  async function start() {
    if (!source || !target) return
    setError('')
    try {
      const { id } = await postJson<{ id: string }>('/api/jobs/highq-to-sharepoint', {
        siteId: Number(source.siteId),
        folderId: source.folderId ? Number(source.folderId) : null,
        target,
        originals,
        unzip,
      })
      setJobId(id)
      setRunning(true)
    } catch (e) {
      if ((e as { status?: number }).status === 409) setResetKey(k => k + 1)   // HighQ wants the security step again
      setError(e instanceof Error ? e.message : String(e))
    }
  }

  return (
    <section>
      <h2>HighQ → SharePoint</h2>
      <p className="note">The copy runs on the server: files go straight from HighQ to SharePoint, nothing is saved on this PC.</p>
      <HighQSource disabled={running} resetKey={resetKey} onChange={setSource} />
      <SharePointTargetForm step={3} disabled={running} onChange={setTarget} />
      <div className="step">
        <label className="check">
          <input type="checkbox" checked={originals} disabled={running} onChange={e => setOriginals(e.target.checked)} />
          Copy the unredacted original of redacted files (needs "download original" rights; each one is logged)
        </label>
        <UnzipOption checked={unzip} disabled={running} onChange={setUnzip} />
      </div>
      <button className="primary" onClick={start} disabled={!source || !target || running}>Start copy</button>
      {error && <p className="error">{error}</p>}
      {jobId && <JobProgress key={jobId} jobId={jobId} onFinished={() => setRunning(false)} />}
    </section>
  )
}
