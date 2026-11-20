// Writing into a folder the user picks (File System Access API: Chrome and Edge on desktop).
// Shared by "HighQ to local folder" and "Zip to local folder".

declare global {
  interface Window {
    showDirectoryPicker(options?: { mode?: 'read' | 'readwrite' }): Promise<FileSystemDirectoryHandle>
  }
}

export const folderPickerSupported = () => typeof window !== 'undefined' && 'showDirectoryPicker' in window

export function pickFolder() {
  return window.showDirectoryPicker({ mode: 'readwrite' })
}

/** The directory for a '/'-separated relative path, creating any missing folders. */
export async function dirFor(root: FileSystemDirectoryHandle, relDir: string) {
  let dir = root
  for (const seg of relDir.split('/').filter(Boolean)) dir = await dir.getDirectoryHandle(seg, { create: true })
  return dir
}

export function splitPath(rel: string): [string, string] {
  const i = rel.lastIndexOf('/')
  return i < 0 ? ['', rel] : [rel.slice(0, i), rel.slice(i + 1)]
}

export async function existingSize(dir: FileSystemDirectoryHandle, name: string): Promise<number | null> {
  try {
    return (await (await dir.getFileHandle(name)).getFile()).size
  } catch {
    return null
  }
}

/**
 * Writes a file and returns its size on disk. The browser writes to a temporary swap file and only
 * replaces the target on close, so a failed or interrupted write never leaves a half-written file.
 */
export async function writeFile(dir: FileSystemDirectoryHandle, name: string, data: ReadableStream<Uint8Array> | Blob) {
  const handle = await dir.getFileHandle(name, { create: true })
  const out = await handle.createWritable()
  if (data instanceof Blob) {
    await out.write(data)
    await out.close()
  } else {
    await data.pipeTo(out)
  }
  return (await handle.getFile()).size
}

export async function readJson<T>(dir: FileSystemDirectoryHandle, name: string): Promise<T | null> {
  try {
    return JSON.parse(await (await (await dir.getFileHandle(name)).getFile()).text()) as T
  } catch {
    return null
  }
}

export async function writeText(dir: FileSystemDirectoryHandle, name: string, text: string) {
  await writeFile(dir, name, new Blob([text]))
}
