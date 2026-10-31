import { Navigate, Outlet, useLocation } from 'react-router'
import { useAuthStore } from '@/stores/auth'

export function RequireAuth() {
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
