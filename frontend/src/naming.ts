// Same Windows-safety rules as backend Naming.java, for names that come from zip entries.
const INVALID = /[<>:"/\\|?*\x00-\x1F]/g
const RESERVED = /^(CON|PRN|AUX|NUL|COM\d|LPT\d)$/i

export function safeSegment(s: string): string {
  let n = s.replace(INVALID, '_').trim().replace(/[. ]+$/, '')
  if (RESERVED.test(n)) n = '_' + n
  return n || '_'
}

/** A zip entry's path made safe, or null if it tries to leave the destination folder ("zip slip"). */
export function safeZipPath(entryName: string): string | null {
  const p = entryName.replace(/\\/g, '/')
  if (p.startsWith('/') || /^[a-zA-Z]:/.test(p)) return null
  const segs = p.split('/').filter(s => s !== '' && s !== '.')
  if (segs.length === 0 || segs.includes('..')) return null
  return segs.map(safeSegment).join('/')
}
