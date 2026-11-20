/** The backend's error message for a failed response, falling back to the HTTP status. */
export async function errorText(r: Response) {
  try {
    const j = await r.json()
    return j.message || j.error || `HTTP ${r.status}`
  } catch {
    return `HTTP ${r.status}`
  }
}

export async function postJson<T>(url: string, body: unknown): Promise<T> {
  const r = await fetch(url, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) })
  if (!r.ok) throw Object.assign(new Error(await errorText(r)), { status: r.status })
  return r.json()
}
