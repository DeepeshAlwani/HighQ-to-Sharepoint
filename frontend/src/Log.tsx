export type Row = { path: string; status: string; detail?: string }

const csvCell = (s = '') => `"${s.replace(/"/g, '""')}"`

export function toCsv(rows: Row[]) {
  return ['path,status,detail', ...rows.map(r => [r.path, r.status, r.detail].map(csvCell).join(','))].join('\r\n')
}

/** The last 200 results; the full list is saved as a CSV report in the destination folder. */
export function Log({ rows }: { rows: Row[] }) {
  if (!rows.length) return null
  return (
    <table>
      <thead><tr><th>Status</th><th>Path</th><th>Detail</th></tr></thead>
      <tbody>
        {rows.slice(-200).map((r, i) => (
          <tr key={i} className={r.status.startsWith('failed') || r.status === 'error' ? 'error' : undefined}>
            <td>{r.status}</td><td>{r.path}</td><td>{r.detail}</td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}
