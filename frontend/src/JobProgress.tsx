import { useEffect, useState } from 'react'
import { Log, type Row } from './Log'

type JobView = {
  id: string; state: 'RUNNING' | 'DONE' | 'FAILED' | 'CANCELLED'; phase: string; message: string | null
  total: number; finished: number; transferred: number; skipped: number; failed: number; bytes: number
  rows: Row[]
}

const mb = (n: number) => `${(n / 1024 / 1024).toFixed(1)} MB`

/** Follows a backend job until it ends. The job keeps running if this page is closed. */
export default function JobProgress({ jobId, onFinished }: { jobId: string; onFinished: () => void }) {
  const [job, setJob] = useState<JobView | null>(null)
  const [error, setError] = useState('')

  useEffect(() => {
    let stop = false
    async function poll() {
      try {
        const r = await fetch(`/api/jobs/${jobId}`)
        if (!r.ok) throw new Error(`HTTP ${r.status}`)
        const j: JobView = await r.json()
        if (stop) return
        setJob(j)
        if (j.state === 'RUNNING') setTimeout(poll, 1500)
        else onFinished()
      } catch (e) {
        if (!stop) {
          setError(`Lost track of the job: ${e instanceof Error ? e.message : e}`)
          onFinished()
        }
      }
    }
    poll()
    return () => { stop = true }
  }, [jobId])

  if (error) return <p className="error">{error}</p>
  if (!job) return <p>Starting…</p>

  const running = job.state === 'RUNNING'
  return (
    <div className="step">
      <h3>{running ? job.phase : `Finished: ${job.state.toLowerCase()}`}</h3>
      <p>
        {job.finished} of {job.total || '?'} files · {job.transferred} copied ({mb(job.bytes)}) · {job.skipped} skipped · {job.failed} failed
      </p>
      {job.message && <p className="error">{job.message}</p>}
      <p>
        {running && <button onClick={() => fetch(`/api/jobs/${jobId}/cancel`, { method: 'POST' })}>Cancel</button>}{' '}
        <a className="button" href={`/api/jobs/${jobId}/report.csv`}>Download report (CSV)</a>
      </p>
      <Log rows={job.rows} />
    </div>
  )
}
