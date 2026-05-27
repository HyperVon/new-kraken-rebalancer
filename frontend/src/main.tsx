import {StrictMode} from 'react'
import {createRoot} from 'react-dom/client'
import {QueryClient, QueryClientProvider} from '@tanstack/react-query'
import {BrowserRouter} from 'react-router-dom'
import {Toaster} from 'react-hot-toast'
import './index.css'
import App from './App.tsx'

const queryClient = new QueryClient();

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <App />
        <Toaster position="bottom-right" toastOptions={{
          style: {
            background: '#1e293b',
            color: '#f8fafc',
            border: '1px solid #334155',
          }
        }} />
      </BrowserRouter>
    </QueryClientProvider>
  </StrictMode>,
)
