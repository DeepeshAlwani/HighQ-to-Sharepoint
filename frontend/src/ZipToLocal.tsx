import { useState } from 'react'
import { BlobReader, ZipReader } from '@zip.js/zip.js'
import { dirFor, existingSize, folderPickerSupported, pickFolder, splitPath, writeText } from './fs'
import { Log, type Row, toCsv } from './Log'
import { safeZipPath } from './naming'

const REPORT_FILE = '_zip-report.csv'

/**
 * Extracts a HighQ zip export into a folder on this PC, entirely in the browser: nothing is uploaded.
 * shortcut: paths come from the zip's own folder structure; switch to HighQ's metadata file once we have a sample export.
 */
export default function ZipToLocal() {
  const [zip, setZip] = useState<File | null>(null)
  const [root, setRoot] = useState<FileSystemDirectoryHandle | null>(null)
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
    const reader = new ZipReader(new BlobReader(zip))
    try {
      const entries = await reader.getEntries()
      const total = entries.reduce((sum, e) => sum + (e.uncompressedSize || 0), 0)
      if (total > 1e9 && total > 100 * zip.size) {
        throw new Error('This zip would unpack to over 100 times its own size, which is a sign of a malicious zip. Stopped.')
      }

      let extracted = 0, skipped = 0, failed = 0, done = 0
      const files = entries.filter(e => !e.directory)
      for (const e of entries) {
        const rel = safeZipPath(e.filename)
        if (!rel) {
          if (!e.directory) failed++
          log({ path: e.filename, status: 'failed', detail: 'unsafe path in zip (points outside the destination), not extracted' })
          continue
        }
        try {
          if (e.directory) {
            await dirFor(root, rel)
            continue
          }
          const [relDir, name] = splitPath(rel)
          const dir = await dirFor(root, relDir)
          if ((await existingSize(dir, name)) === e.uncompressedSize) {
            skipped++
            continue
          }
          const out = await (await dir.getFileHandle(name, { create: true })).createWritable()
          await e.getData!(out)   // zip.js closes the stream; the file only appears once complete
          const size = await existingSize(dir, name)
          if (size !== e.uncompressedSize) throw new Error(`incomplete: ${size} of ${e.uncompressedSize} bytes`)
          extracted++
          const normalised = e.filename.replace(/\\/g, '/').replace(/^\.\//, '')
          log({ path: rel, status: 'extracted', detail: rel !== normalised ? `renamed from "${e.filename}"` : undefined })
        } catch (err) {
          failed++
          log({ path: rel, status: 'failed', detail: err instanceof Error ? err.message : String(err) })
        } finally {
          if (!e.directory) setProgress(`${++done} of ${files.length} files`)
        }
      }
      await writeText(root, REPORT_FILE, toCsv(report))
      setProgress(`Done: ${extracted} extracted, ${skipped} already there, ${failed} failed. Report saved as ${REPORT_FILE}.`)
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
      setProgress('')
    } finally {
      await reader.close()
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
      </div>

      <button className="primary" onClick={start} disabled={!zip || !root || running}>Extract</button>
      {progress && <p>{progress}</p>}
      {error && <p className="error">{error}</p>}
      <Log rows={rows} />
    </section>
  )
}
