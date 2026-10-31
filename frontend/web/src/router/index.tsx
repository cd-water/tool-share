import { createBrowserRouter, Navigate } from 'react-router'
import { RequireAuth } from '@/components/RequireAuth'
import AppLayout from '@/layouts/AppLayout'
import AiPage from '@/pages/AiPage'
import DamagePage from '@/pages/DamagePage'
import LoginPage from '@/pages/LoginPage'
import MessagesPage from '@/pages/MessagesPage'
import ProfilePage from '@/pages/ProfilePage'
import RentalsPage from '@/pages/RentalsPage'
import ToolsPage from '@/pages/ToolsPage'

export const router = createBrowserRouter([
  { path: '/login', element: <LoginPage /> },
  {
    element: <RequireAuth />,
    children: [
      {
        element: <AppLayout />,
        children: [
          { index: true, element: <Navigate to="/tools" replace /> },
          { path: 'tools', element: <ToolsPage /> },
          { path: 'rentals', element: <RentalsPage /> },
          { path: 'damage', element: <DamagePage /> },
          { path: 'messages', element: <MessagesPage /> },
          { path: 'ai', element: <AiPage /> },
          { path: 'profile', element: <ProfilePage /> },
        ],
      },
    ],
  },
  { path: '*', element: <Navigate to="/tools" replace /> },
])
