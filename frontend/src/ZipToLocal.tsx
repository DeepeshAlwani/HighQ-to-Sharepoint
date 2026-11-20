import { useState } from 'react'
import { folderPickerSupported, pickFolder, writeText } from './fs'
import { Log, type Row, toCsv } from './Log'
import UnzipOption from './UnzipOption'
import { extractZip, newTally } from './zipExtract'

const REPORT_FILE = '_zip-report.csv'

/**
 * Extracts a HighQ zip export into a folder on this PC, entirely in the browser: nothing is uploaded.
 * shortcut: paths come from the zip's own folder structure; switch to HighQ's metadata file once we have a sample export.
 */
export default function ZipToLocal() {
  const [zip, setZip] = useState<File | null>(null)
  const [root, setRoot] = useState<FileSystemDirectoryHandle | null>(null)
  const [unzipNested, setUnzipNested] = useState(false)
  const [running, setRunning] = useState(false)
  const [rows, setRows] = useState<Row[]>([])
  const [progress, setProgress] = useState('')
  const [error, setError] = useState('')

  async function start() {
    if (!zip || !root) return
    setRunning(true)
    setRows([])
    setError('')
    const report: Row[] = []
    const log = (row: Row) => {
      report.push(row)
      setRows(rs => [...rs, row])
    }
    try {
      const tally = newTally()
      await extractZip(zip, root, '', { log, tally, unzipNested, onFile: () => setProgress(`${tally.done} of ${tally.total} files`) })
      await writeText(root, REPORT_FILE, toCsv(report))
      setProgress(`Done: ${tally.extracted} extracted, ${tally.skipped} already there, ${tally.failed} failed. Report saved as ${REPORT_FILE}.`)
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
      setProgress('')
    } finally {
      setRunning(false)
    }
  }

  return (
    <section>
      <h2>Zip → local folder</h2>
      <p className="note">The zip is read in this browser. Nothing is uploaded.</p>

      <div className="step">
        <h3>1. Choose the HighQ zip</h3>
        <input type="file" accept=".zip" disabled={running} onChange={e => setZip(e.target.files?.[0] ?? null)} />
      </div>

      <div className="step">
        <h3>2. Choose where to save</h3>
        {folderPickerSupported()
          ? <button onClick={async () => setRoot(await pickFolder())} disabled={running}>Choose folder</button>
          : <p className="warn">This browser can't save into folders. Use Chrome or Edge on a desktop.</p>}
        {root && <p>Saving into <b>{root.name}</b></p>}
        <UnzipOption checked={unzipNested} disabled={running} onChange={setUnzipNested} />
      </div>

      <button className="primary" onClick={start} disabled={!zip || !root || running}>Extract</button>
      {progress && <p>{progress}</p>}
      {error && <p className="error">{error}</p>}
      <Log rows={rows} />
    </section>
  )
}
