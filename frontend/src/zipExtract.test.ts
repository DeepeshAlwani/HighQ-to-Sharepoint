import assert from 'node:assert/strict'
import { test } from 'node:test'
import { BlobReader, BlobWriter, TextReader, ZipWriter } from '@zip.js/zip.js'
import { readFileSync } from 'node:fs'
import { extractZip, newTally, unpackInPlace } from './zipExtract.ts'

// A folder in memory with just the parts of the File System Access API that the app uses.
class MemDir {
  dirs = new Map<string, MemDir>()
  files = new Map<string, Blob>()
  async getDirectoryHandle(name: string, o?: { create?: boolean }) {
    if (!this.dirs.has(name)) {
      if (!o?.create) throw new Error('NotFound')
      this.dirs.set(name, new MemDir())
    }
    return this.dirs.get(name)!
  }
  async getFileHandle(name: string, o?: { create?: boolean }) {
    if (!this.files.has(name) && !o?.create) throw new Error('NotFound')
    const files = this.files
    return {
      getFile: async () => files.get(name) ?? new Blob([]),
      createWritable: async () => {
        const parts: BlobPart[] = []
        const ws = new WritableStream<BlobPart>({ write: c => { parts.push(c) }, close: () => { files.set(name, new Blob(parts)) } })
        return Object.assign(ws, { write: async (c: BlobPart) => { parts.push(c) } })
      },
    }
  }
  async removeEntry(name: string) { this.files.delete(name) }
  list(prefix = ''): string[] {
    return [...[...this.files.keys()].map(f => prefix + f), ...[...this.dirs].flatMap(([d, h]) => h.list(`${prefix}${d}/`))].sort()
  }
}

async function zipOf(entries: Record<string, string | Blob>) {
  const w = new ZipWriter(new BlobWriter('application/zip'))
  for (const [name, data] of Object.entries(entries)) {
    await w.add(name, typeof data === 'string' ? new TextReader(data) : new BlobReader(data))
  }
  return w.close()
}

test('nested zips are unzipped into a folder named after them, one level deep', async () => {
  const deep = await zipOf({ 'deep.txt': 'deep' })
  const inner = await zipOf({ 'a.txt': 'hello', 'sub/b.txt': 'world', 'deeper.zip': deep, '../evil.txt': 'x' })
  const outer = await zipOf({ 'Folder/Contracts.zip': inner, 'Folder/readme.txt': 'top', 'Folder/broken.zip': 'not a zip' })
  const root = new MemDir()
  const rows: { path: string; status: string }[] = []
  const tally = newTally()

  await extractZip(outer, root as unknown as FileSystemDirectoryHandle, '', { log: r => rows.push(r), tally, unzipNested: true })

  assert.deepEqual(root.list(), [
    'Folder/Contracts.zip', 'Folder/Contracts/a.txt', 'Folder/Contracts/deeper.zip', 'Folder/Contracts/sub/b.txt', 'Folder/broken.zip', 'Folder/readme.txt',
  ])
  assert.ok(rows.some(r => r.path === 'Folder/Contracts.zip' && r.status === 'unzipped with errors'))   // evil.txt was blocked, so the zip is kept too
  assert.ok(rows.some(r => r.path === 'Folder/broken.zip' && r.status === 'failed'))
  assert.equal(tally.done, tally.total)
})

test('without the option zips stay as files; unzipInPlace removes the zip only when everything came out', async () => {
  const inner = await zipOf({ 'a.txt': 'hello' })
  const root = new MemDir()
  const tally = newTally()
  await extractZip(await zipOf({ 'X.zip': inner }), root as unknown as FileSystemDirectoryHandle, '', { log: () => {}, tally, unzipNested: false })
  assert.deepEqual(root.list(), ['X.zip'])

  const ok = await unpackInPlace(root as unknown as FileSystemDirectoryHandle, 'X.zip', { log: () => {}, tally: newTally(), unzipNested: false })
  assert.equal(ok, true)
  assert.deepEqual(root.list(), ['X/a.txt'])
})

test('.7z and .rar (RAR5) inside a zip are unpacked with 7-Zip; a broken .rar is kept as a file', async () => {
  const fixture = (name: string) => new Blob([readFileSync(new URL(`./fixtures/${name}`, import.meta.url))])
  const outer = await zipOf({ 'Seven.7z': fixture('test.7z'), 'Rar5.rar': fixture('test.rar'), 'bad.rar': 'not a rar' })
  const root = new MemDir()
  const rows: { path: string; status: string }[] = []
  const tally = newTally()

  await extractZip(outer, root as unknown as FileSystemDirectoryHandle, '', { log: r => rows.push(r), tally, unzipNested: true })

  assert.deepEqual(root.list(), ['Rar5/a.txt', 'Rar5/sub/b c.txt', 'Seven/a.txt', 'Seven/sub/b c.txt', 'bad.rar'])
  assert.equal(await root.dirs.get('Rar5')!.files.get('a.txt')!.text(), 'hello\n')
  assert.ok(rows.some(r => r.path === 'bad.rar' && r.status === 'failed'))
  process.exitCode = 0   // 7-Zip (WASM) sets the Node exit code for bad.rar; failed asserts still fail the run
  assert.equal(tally.done, tally.total)
})
