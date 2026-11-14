import type { BadgeTone } from '@/components/ui/badge'
import type {
  DamageLevel,
  DamageReportStatus,
  MessageType,
  RentalStatus,
  ToolStatus,
} from '@/api/types'

export function toolStatusTone(status: ToolStatus): BadgeTone {
  switch (status) {
    case 'AVAILABLE':
      return 'success'
    case 'RENTED':
      return 'warning'
    case 'MAINTENANCE':
      return 'info'
    case 'SCRAPPED':
      return 'danger'
    default:
      return 'neutral'
  }
}

export function rentalStatusTone(status: RentalStatus): BadgeTone {
  switch (status) {
    case 'PENDING_PAYMENT':
      return 'warning'
    case 'RESERVED':
      return 'info'
    case 'PICKED_UP':
      return 'primary'
    case 'RETURNED':
      return 'success'
    case 'OVERDUE':
      return 'danger'
    case 'CANCELLED':
      return 'neutral'
    default:
      return 'neutral'
  }
}

export function damageStatusTone(status: DamageReportStatus): BadgeTone {
  switch (status) {
    case 'SUBMITTED':
      return 'info'
    case 'VERIFIED':
      return 'primary'
    case 'REPAIRING':
      return 'warning'
    case 'COMPLETED':
      return 'success'
    case 'REJECTED':
    case 'SCRAPPED':
      return 'danger'
    default:
      return 'neutral'
  }
}

export function messageTypeTone(type: MessageType): BadgeTone {
  switch (type) {
    case 'RESERVATION':
    case 'PICKUP':
      return 'info'
    case 'RENT_DUE':
      return 'warning'
    case 'OVERDUE':
      return 'danger'
    case 'REPAIR':
      return 'primary'
    case 'TOOL_AVAILABLE':
      return 'success'
    default:
      return 'neutral'
  }
}

export function damageLevelTone(level: DamageLevel): BadgeTone {
  switch (level) {
    case 'NEW':
      return 'success'
    case 'LIGHT':
      return 'info'
    case 'MEDIUM':
      return 'warning'
    case 'SEVERE':
      return 'danger'
    default:
      return 'neutral'
  }
}
