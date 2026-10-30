import { createBrowserRouter, Navigate } from 'react-router'
import { RequireAuth } from '@/components/RequireAuth'
import AppLayout from '@/layouts/AppLayout'
import LoginPage from '@/pages/LoginPage'
import PlaceholderPage from '@/pages/PlaceholderPage'

export const router = createBrowserRouter([
  { path: '/login', element: <LoginPage /> },
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
