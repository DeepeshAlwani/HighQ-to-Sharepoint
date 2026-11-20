import { useState } from 'react'
import JobProgress from './JobProgress'
import SharePointTargetForm, { type SharePointTarget } from './SharePointTargetForm'

/** Sends a file with upload progress (fetch can't report upload progress). */
function upload(url: string, form: FormData, onProgress: (pct: number) => void) {
  return new Promise<{ id: string; keptZipAt: string }>((resolve, reject) => {
    const xhr = new XMLHttpRequest()
    xhr.open('POST', url)
    xhr.upload.onprogress = e => e.lengthComputable && onProgress(Math.round((e.loaded / e.total) * 100))
    xhr.onload = () => {
      let body: { id?: string; keptZipAt?: string; message?: string } = {}
      try { body = JSON.parse(xhr.responseText) } catch { /* not JSON */ }
      if (xhr.status >= 200 && xhr.status < 300 && body.id) resolve({ id: body.id, keptZipAt: body.keptZipAt ?? '' })
      else reject(new Error(body.message || `HTTP ${xhr.status}`))
    }
    xhr.onerror = () => reject(new Error('Upload failed (network)'))
    xhr.send(form)
  })
}

export default function ZipToSharePoint() {
  const [zip, setZip] = useState<File | null>(null)
  const [target, setTarget] = useState<SharePointTarget | null>(null)
  const [deleteAfter, setDeleteAfter] = useState(true)
  const [uploadPct, setUploadPct] = useState<number | null>(null)
  const [jobId, setJobId] = useState<string | null>(null)
  const [keptAt, setKeptAt] = useState('')
  const [running, setRunning] = useState(false)
  const [error, setError] = useState('')

  async function start() {
    if (!zip || !target) return
    setError('')
    setRunning(true)
    setJobId(null)
    const form = new FormData()
    form.append('file', zip)
    form.append('siteUrl', target.siteUrl)
    form.append('library', target.library)
    form.append('folderPath', target.folderPath)
    form.append('deleteAfter', String(deleteAfter))
    try {
      const r = await upload('/api/jobs/zip-to-sharepoint', form, setUploadPct)
      setKeptAt(r.keptZipAt)
      setJobId(r.id)
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
      setRunning(false)
    } finally {
      setUploadPct(null)
    }
  }

  return (
    <section>
      <h2>Zip → SharePoint</h2>
      <p className="note">The zip is uploaded to the migration server, which extracts it straight into SharePoint.</p>

      <div className="step">
        <h3>1. Choose the HighQ zip</h3>
        <input type="file" accept=".zip" disabled={running} onChange={e => setZip(e.target.files?.[0] ?? null)} />
        <label className="check">
          <input type="checkbox" checked={deleteAfter} disabled={running} onChange={e => setDeleteAfter(e.target.checked)} />
          Delete the uploaded zip from the server when the copy finishes
        </label>
      </div>

      <SharePointTargetForm step={2} disabled={running} onChange={setTarget} />

      <button className="primary" onClick={start} disabled={!zip || !target || running}>Upload and copy</button>
      {uploadPct !== null && <p>Uploading zip… {uploadPct}%</p>}
      {error && <p className="error">{error}</p>}
      {keptAt && <p className="note">The zip is kept on the server at {keptAt}.</p>}
      {jobId && <JobProgress key={jobId} jobId={jobId} onFinished={() => setRunning(false)} />}
    </section>
  )
}
