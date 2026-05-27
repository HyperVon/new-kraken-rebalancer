import {Route, Routes} from 'react-router-dom'
import Dashboard from '@/components/Dashboard'
import Settings from '@/components/Settings'

function App() {
  return (
    <Routes>
      <Route path="/" element={<Dashboard />} />
      <Route path="/settings" element={<Settings />} />
    </Routes>
  )
}

export default App
