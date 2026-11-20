import { useState } from 'react'
import HighQToLocal from './HighQToLocal'
import HighQToSharePoint from './HighQToSharePoint'
import ZipToLocal from './ZipToLocal'
import ZipToSharePoint from './ZipToSharePoint'

const modes = [
  { id: 'highq-local', label: 'HighQ → local folder', view: <HighQToLocal /> },
  { id: 'zip-local', label: 'Zip → local folder', view: <ZipToLocal /> },
  { id: 'highq-sharepoint', label: 'HighQ → SharePoint', view: <HighQToSharePoint /> },
  { id: 'zip-sharepoint', label: 'Zip → SharePoint', view: <ZipToSharePoint /> },
]

// Remember the tab: signing in to HighQ or Microsoft reloads the page.
function savedMode() {
  try { return localStorage.getItem('mode') ?? modes[0].id } catch { return modes[0].id }
}

export default function App() {
  const [mode, setModeState] = useState(savedMode)
  const setMode = (m: string) => {
    setModeState(m)
    try { localStorage.setItem('mode', m) } catch { /* private window: just don't remember */ }
  }
  return (
    <main>
      <h1>HighQ Migration</h1>
      <nav>
        {modes.map(m => (
          <button key={m.id} className={m.id === mode ? 'tab active' : 'tab'} onClick={() => setMode(m.id)}>{m.label}</button>
        ))}
      </nav>
      {(modes.find(m => m.id === mode) ?? modes[0]).view}
    </main>
  )
}
