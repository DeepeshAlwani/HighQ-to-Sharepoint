// Unpacking archives (.zip, .7z, .rar) into a local folder; shared by "Zip to local folder" and "HighQ to local folder".
import { BlobReader, ZipReader } from '@zip.js/zip.js'
import { dirFor, existingSize, splitPath, writeFile } from './fs.ts'
import type { Row } from './Log.tsx'
import { safeZipPath } from './naming.ts'

/** "x.zip", "x.7z" or "x.rar". shortcut: multi-part RARs (.part1.rar, .r00) are unpacked per part. */
export const isArchive = (name: string) => /.\.(zip|7z|rar)$/i.test(name)

/** "A/B/Contracts.zip" -> "A/B/Contracts": the folder an archive is unpacked into. */
export const unpackFolder = (relPath: string) => relPath.replace(/\.(zip|7z|rar)$/i, '')

export type Tally = { extracted: number; skipped: number; failed: number; done: number; total: number }
export const newTally = (): Tally => ({ extracted: 0, skipped: 0, failed: 0, done: 0, total: 0 })

type Options = { log: (r: Row) => void; tally: Tally; unzipNested: boolean; onFile?: () => void }

/**
 * Extracts `zip` into root/prefix. Files already there with the same size are skipped (resume).
 * With unzipNested, each .zip inside is unzipped into a folder named after it and the zip itself is
 * removed once all its files are out (one level: zips inside those stay as files).
 */
export async function extractZip(zip: Blob, root: FileSystemDirectoryHandle, prefix: string, opts: Options) {
  const { log, tally } = opts
  // 'tolerant': safeZipPath below blocks unsafe entries one by one instead of zip.js rejecting the whole zip
  const reader = new ZipReader(new BlobReader(zip), { filenameValidation: 'tolerant' })
  try {
    const entries = await reader.getEntries()
    const unpacked = entries.reduce((sum, e) => sum + (e.uncompressedSize || 0), 0)
    if (unpacked > 1e9 && unpacked > 100 * zip.size) {
      throw new Error('this zip would unpack to over 100 times its own size, a sign of a malicious zip; not extracted')
    }
    tally.total += entries.filter(e => !e.directory).length
    const under = (rel: string) => (prefix ? `${prefix}/${rel}` : rel)

    for (const e of entries) {
      const safe = safeZipPath(e.filename)
      if (!safe) {
        if (!e.directory) { tally.failed++; tally.done++; opts.onFile?.() }
        log({ path: under(e.filename), status: 'failed', detail: 'unsafe path in zip (points outside the destination), not extracted' })
        continue
      }
      const rel = under(safe)
      if (e.directory) {
        await dirFor(root, rel)
        continue
      }
      try {
        const [relDir, name] = splitPath(rel)
        const dir = await dirFor(root, relDir)
        if ((await existingSize(dir, name)) === e.uncompressedSize) {
          tally.skipped++
          continue
        }
        const out = await (await dir.getFileHandle(name, { create: true })).createWritable()
        await e.getData!(out)   // zip.js closes the stream; the file only appears once complete
        const size = await existingSize(dir, name)
        if (size !== e.uncompressedSize) throw new Error(`incomplete: ${size} of ${e.uncompressedSize} bytes`)

        if (opts.unzipNested && isArchive(name)) {
          await unpackInPlace(root, rel, { ...opts, unzipNested: false })
          continue
        }
        tally.extracted++
        const normalised = e.filename.replace(/\\/g, '/').replace(/^\.\//, '')
        log({ path: rel, status: 'extracted', detail: safe !== normalised ? `renamed from "${e.filename}"` : undefined })
      } catch (err) {
        tally.failed++
        log({ path: rel, status: 'failed', detail: err instanceof Error ? err.message : String(err) })
      } finally {
        tally.done++
        opts.onFile?.()
      }
    }
  } finally {
    await reader.close()
  }
}

/**
 * Unpacks an archive already saved at root/zipRel into the folder next to it with the archive's name, then
 * removes the archive. If anything fails the archive is kept, so nothing is lost. Returns true when fully unpacked.
 */
export async function unpackInPlace(root: FileSystemDirectoryHandle, zipRel: string, opts: Options) {
  const [relDir, name] = splitPath(zipRel)
  const dir = await dirFor(root, relDir)
  const failedBefore = opts.tally.failed
  try {
    const file = await (await dir.getFileHandle(name)).getFile()
    if (/\.zip$/i.test(name)) await extractZip(file, root, unpackFolder(zipRel), opts)
    else await extractWith7Zip(file, root, unpackFolder(zipRel), opts)
  } catch (err) {
    opts.tally.failed++
    opts.log({ path: zipRel, status: 'failed', detail: `could not unpack (${err instanceof Error ? err.message : err}); the archive is kept as a file` })
    return false
  }
  if (opts.tally.failed > failedBefore) {
    opts.log({ path: zipRel, status: 'unzipped with errors', detail: 'some files failed; the archive is kept as a file' })
    return false
  }
  await dir.removeEntry(name)
  opts.log({ path: zipRel, status: 'unzipped', detail: `into folder "${unpackFolder(name)}"` })
  return true
}

// shortcut: 7-Zip (WASM) holds the archive and its unpacked files in browser memory, so archives over 1 GB are kept
// as files; stream entry by entry if larger ones turn up.
const MAX_IN_BROWSER = 1e9

/** .7z and .rar, with 7-Zip compiled to WASM (loaded only when needed). 7-Zip itself refuses paths leaving the folder. */
async function extractWith7Zip(archive: Blob, root: FileSystemDirectoryHandle, prefix: string, opts: Options) {
  if (archive.size > MAX_IN_BROWSER) throw new Error('over 1 GB, too large to unpack in the browser')
  const { default: SevenZip } = await import('7z-wasm')
  const output: string[] = []
  const sz = await SevenZip({ print: s => output.push(s), printErr: s => output.push(s) })
  sz.FS.mkdir('/in')
  sz.FS.mkdir('/out')
  sz.FS.writeFile('/in/archive', new Uint8Array(await archive.arrayBuffer()))
  // -p with no password: a password-protected archive fails instead of waiting for input
  const code = sz.callMain(['x', '-y', '-bd', '-p', '-o/out', '/in/archive']) as unknown as number
  sz.FS.unlink('/in/archive')
  if (code !== 0) {
    throw new Error(output.find(l => /Cannot open|Wrong password|Data Error|Unsupported/i.test(l)) ?? `7-Zip exit code ${code}`)
  }

  const files: string[] = []
  const walk = (dir: string) => {
    for (const name of sz.FS.readdir(dir)) {
      if (name === '.' || name === '..') continue
      const path = `${dir}/${name}`
      const mode = sz.FS.lstat(path).mode
      if (sz.FS.isDir(mode)) walk(path)
      else if (sz.FS.isFile(mode)) files.push(path)   // links inside archives are left out
    }
  }
  walk('/out')
  const { log, tally } = opts
  tally.total += files.length
  for (const path of files) {
    const raw = path.slice('/out/'.length)
    const safe = safeZipPath(raw)
    const rel = `${prefix}/${safe ?? raw}`
    try {
      if (!safe) throw new Error('unsafe path in archive, not extracted')
      const [relDir, name] = splitPath(rel)
      const dir = await dirFor(root, relDir)
      const data = sz.FS.readFile(path)
      sz.FS.unlink(path)   // free the memory as we go
      if ((await existingSize(dir, name)) === data.length) {
        tally.skipped++
        continue
      }
      const size = await writeFile(dir, name, new Blob([data as Uint8Array<ArrayBuffer>]))
      if (size !== data.length) throw new Error(`incomplete: ${size} of ${data.length} bytes`)
      tally.extracted++
      log({ path: rel, status: 'extracted', detail: safe !== raw ? `renamed from "${raw}"` : undefined })
    } catch (err) {
      tally.failed++
      log({ path: rel, status: 'failed', detail: err instanceof Error ? err.message : String(err) })
    } finally {
      tally.done++
      opts.onFile?.()
    }
  }
}
