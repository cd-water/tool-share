import { createBrowserRouter, Navigate, Outlet, useLocation } from 'react-router'
import AppLayout from '@/layouts/AppLayout'
import LoginView from '@/views/LoginView'
import PlaceholderPage from '@/views/PlaceholderPage'
import { useAuthStore } from '@/stores/auth'

function RequireAuth() {
  const tokenValue = useAuthStore((s) => s.tokenValue)
  const location = useLocation()

  if (!tokenValue) {
    return (
      <Navigate
        to="/login"
        state={{ from: location.pathname + location.search }}
        replace
      />
    )
  }

  return <Outlet />
}

export const router = createBrowserRouter([
  { path: '/login', element: <LoginView /> },
  {
    element: <RequireAuth />,
    children: [
      {
        element: <AppLayout />,
        children: [
          { index: true, element: <Navigate to="/page-1" replace /> },
          { path: 'page-1', element: <PlaceholderPage title="页面1" /> },
          { path: 'page-2', element: <PlaceholderPage title="页面2" /> },
          { path: 'page-3', element: <PlaceholderPage title="页面3" /> },
        ],
      },
    ],
  },
  { path: '*', element: <Navigate to="/page-1" replace /> },
])
