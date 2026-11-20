/** The "unzip zips found inside" checkbox, the same in all four modes. */
export default function UnzipOption(p: { checked: boolean; disabled: boolean; onChange: (v: boolean) => void }) {
  return (
    <label className="check">
      <input type="checkbox" checked={p.checked} disabled={p.disabled} onChange={e => p.onChange(e.target.checked)} />
      Unpack any .zip, .7z or .rar files found: each one's files go into a folder with the archive's name, instead of the archive
    </label>
  )
}
