import React from 'react'
import ReactDOM from 'react-dom/client'
import { RouterProvider } from 'react-router-dom'
import { router } from './router'
import { useAuthStore } from './store/auth'
import { initTheme } from './store/theme'
import { TargetCursor } from '@/components/ui/target-cursor'
import { AuthPixelTransition } from '@/components/transition/auth-pixel-transition'
import '@/styles/globals.css'

function App() {
  return (
    <>
      <RouterProvider
        router={router}
        future={{
          v7_startTransition: true,
        }}
      />
      <TargetCursor
        targetSelector=".cursor-target, button, a[href], [role='button'], [role='tab']"
        spinDuration={5}
      />
      <AuthPixelTransition />
    </>
  )
}

// 初始化 auth store
useAuthStore.getState().init()
// 首帧前应用明暗主题，避免闪烁
initTheme()

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>,
)
