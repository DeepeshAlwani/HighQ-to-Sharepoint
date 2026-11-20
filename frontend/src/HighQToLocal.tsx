import { useState } from 'react'
import { errorText } from './api'
import { dirFor, folderPickerSupported, pickFolder, readJson, splitPath, writeFile, writeText } from './fs'
import HighQSource, { type HighQSelection } from './HighQSource'
import { Log, type Row, toCsv } from './Log'
import UnzipOption from './UnzipOption'
import { isArchive, newTally, unpackInPlace } from './zipExtract'

type ManifestFile = {
  id: number; versionId: string; relativePath: string; shortName: string
  size: number | null; modified: string | null; redacted: boolean; placeholder: boolean
}
type Manifest = { siteName: string; rootFolderId: number; folders: string[]; files: ManifestFile[]; errors: string[] }

const STATE_FILE = '_highq-transfer.json'   // file id -> version already downloaded, for resume
const REPORT_FILE = '_highq-report.csv'
const TRANSIENT = [502, 503, 504, 520, 521, 522, 523, 524]
const PARALLEL = 3                          // the backend also spaces calls to stay under HighQ's rate limit

export default function HighQToLocal() {
  const [source, setSource] = useState<HighQSelection | null>(null)
  const [resetKey, setResetKey] = useState(0)
  const [root, setRoot] = useState<FileSystemDirectoryHandle | null>(null)
  const [originals, setOriginals] = useState(false)
  const [unzip, setUnzip] = useState(false)
  const [running, setRunning] = useState(false)
  const [rows, setRows] = useState<Row[]>([])
  const [progress, setProgress] = useState('')
  const [error, setError] = useState('')

  async function start() {
    if (!root || !source) return
    setRunning(true)
    setRows([])
    setError('')
    const report: Row[] = []
    const log = (row: Row) => {
      report.push(row)
      setRows(rs => [...rs, row])
    }
    try {
      setProgress('Scanning HighQ folders…')
      const q = source.folderId ? `?folderId=${encodeURIComponent(source.folderId)}` : ''
      const res = await fetch(`/api/highq/sites/${encodeURIComponent(source.siteId)}/manifest${q}`)
      if (res.status === 409) {
        setResetKey(k => k + 1)
        throw new Error('HighQ needs the site security step again. Click "Check site".')
      }
      if (!res.ok) throw new Error(await errorText(res))
      const m: Manifest = await res.json()
      m.errors.forEach(e => log({ path: '', status: 'error', detail: e }))

      for (const f of m.folders) await dirFor(root, f)   // keeps empty folders too
      const done: Record<string, string> = (await readJson(root, STATE_FILE)) ?? {}

      let finished = 0, downloaded = 0, skipped = 0, failed = 0
      const queue = [...m.files]
      // Retries transient gateway errors (Render's proxy answers 502/520-524 when a request is dropped);
      // safe because it is a read-only GET.
      const fetchContent = async (f: ManifestFile, useOriginal: boolean) => {
        for (let attempt = 1; ; attempt++) {
          let res: Response | null = null
          try {
            res = await fetch(`/api/highq/files/${f.id}/content?original=${useOriginal}`)
            if (!TRANSIENT.includes(res.status) || attempt === 3) return res
          } catch (e) {
            if (attempt === 3) throw e
          }
          await res?.body?.cancel().catch(() => {})
          await new Promise(r => setTimeout(r, 2000 * attempt))
        }
      }

      const worker = async () => {
        for (let f = queue.shift(); f; f = queue.shift()) {
          try {
            if (done[f.id] === f.versionId) {
              skipped++
              continue
            }
            const useOriginal = originals && f.redacted
            const [relDir, name] = splitPath(f.relativePath)
            const dir = await dirFor(root, relDir)
            if (f.placeholder) {   // HighQ has no content for placeholders: keep the slot as an empty file
              await writeFile(dir, name, new Blob([]))
              done[f.id] = f.versionId
              downloaded++
              log({ path: f.relativePath, status: 'placeholder', detail: 'empty file; HighQ has no content for placeholders' })
              continue
            }

            let res = await fetchContent(f, useOriginal)
            if (!res.ok || !res.body) throw new Error(`HighQ returned ${res.status}`)
            let expected = Number(res.headers.get('Content-Length') ?? NaN)
            let savedAs = name
            let written: number
            try {
              written = await writeFile(dir, name, res.body)
            } catch (e) {
              // Usually a path too long for Windows: retry once with the short name (number ~id.ext).
              await res.body.cancel().catch(() => {})
              res = await fetchContent(f, useOriginal)
              if (!res.ok || !res.body) throw e
              expected = Number(res.headers.get('Content-Length') ?? NaN)
              savedAs = f.shortName
              written = await writeFile(dir, savedAs, res.body)
            }
            if (!Number.isNaN(expected) && written !== expected) throw new Error(`incomplete: ${written} of ${expected} bytes`)

            if (unzip && isArchive(savedAs)) {
              const t = newTally()
              const ok = await unpackInPlace(root, relDir ? `${relDir}/${savedAs}` : savedAs, { log, tally: t, unzipNested: false })
              downloaded += t.extracted
              skipped += t.skipped
              failed += t.failed
              if (ok) done[f.id] = f.versionId   // otherwise a re-run tries this zip again
              continue
            }

            done[f.id] = f.versionId
            downloaded++
            log({
              path: relDir ? `${relDir}/${savedAs}` : savedAs,
              status: useOriginal ? 'downloaded (ORIGINAL of redacted file)' : f.redacted ? 'downloaded (redacted copy)' : 'downloaded',
              detail: savedAs !== name ? `renamed from "${name}" (path too long for Windows)` : undefined,
            })
          } catch (e) {
            failed++
            log({ path: f.relativePath, status: 'failed', detail: e instanceof Error ? e.message : String(e) })
          } finally {
            finished++
            setProgress(`${finished} of ${m.files.length} files`)
            if (finished % 10 === 0) await writeText(root, STATE_FILE, JSON.stringify(done))
          }
        }
      }
      await Promise.all(Array.from({ length: PARALLEL }, worker))

      await writeText(root, STATE_FILE, JSON.stringify(done))
      await writeText(root, REPORT_FILE, toCsv(report))
      setProgress(`Done: ${downloaded} downloaded, ${skipped} skipped, ${failed} failed, ${m.folders.length} folders. Report saved as ${REPORT_FILE}.`)
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
      setProgress('')
    } finally {
      setRunning(false)
    }
  }

  return (
    <section>
      <h2>HighQ → local folder</h2>
      <HighQSource disabled={running} resetKey={resetKey} onChange={setSource} />

      <div className="step">
        <h3>3. Choose where to save</h3>
        {folderPickerSupported()
          ? <button onClick={async () => setRoot(await pickFolder())} disabled={running}>Choose folder</button>
          : <p className="warn">This browser can't save into folders. Use Chrome or Edge on a desktop.</p>}
        {root && <p>Saving into <b>{root.name}</b></p>}
        <label className="check">
          <input type="checkbox" checked={originals} onChange={e => setOriginals(e.target.checked)} />
          Download the unredacted original of redacted files (needs "download original" rights; each one is logged)
        </label>
        <UnzipOption checked={unzip} disabled={running} onChange={setUnzip} />
      </div>

      <button className="primary" onClick={start} disabled={!source || !root || running}>Start download</button>
      {progress && <p>{progress}</p>}
      {error && <p className="error">{error}</p>}
      <Log rows={rows} />
    </section>
  )
}
