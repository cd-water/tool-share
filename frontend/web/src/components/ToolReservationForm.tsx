import { useMemo } from 'react'
import { useMutation } from '@tanstack/react-query'
import { Controller, useForm, useWatch } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { Loader2 } from 'lucide-react'
import { rentalsApi } from '@/api/rentals'
import type { Rental, Tool } from '@/api/types'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { FieldError, Label } from '@/components/ui/label'
import { Select } from '@/components/ui/select'
import { formatMoney } from '@/lib/format'

const REMIND_OPTIONS = [
  { value: 0, label: '不提醒' },
  { value: 30, label: '提前 30 分钟' },
  { value: 60, label: '提前 1 小时' },
  { value: 180, label: '提前 3 小时' },
  { value: 360, label: '提前 6 小时' },
  { value: 720, label: '提前 12 小时' },
  { value: 1440, label: '提前 24 小时' },
  { value: 2880, label: '提前 48 小时' },
]

const reservationSchema = z
  .object({
    startTime: z.string().min(1, '请选择开始时间'),
    endTime: z.string().min(1, '请选择结束时间'),
    pickupPerson: z
      .string()
      .trim()
      .min(1, '请填写取件人')
      .max(20, '取件人姓名过长'),
    remindBeforeMinutes: z.coerce
      .number()
      .int()
      .min(0, '提醒时间不能为负数')
      .max(10080, '最多提前 7 天提醒'),
  })
  .refine((values) => new Date(values.endTime) > new Date(values.startTime), {
    path: ['endTime'],
    message: '结束时间需晚于开始时间',
  })

type ReservationValues = z.infer<typeof reservationSchema>
type ReservationInput = z.input<typeof reservationSchema>

/** 租赁预约表单：工具广场与 AI 助手共用 */
export function ToolReservationForm({
  tool,
  onCancel,
  onSuccess,
}: {
  tool: Tool
  onCancel: () => void
  onSuccess: (rental: Rental) => void
}) {
  const {
    control,
    handleSubmit,
    formState: { errors },
  } = useForm<ReservationInput, unknown, ReservationValues>({
    resolver: zodResolver(reservationSchema),
    defaultValues: {
      startTime: '',
      endTime: '',
      pickupPerson: '',
      remindBeforeMinutes: 1440,
    },
  })

  const endTimeValue = useWatch({ control, name: 'endTime' })
  const remindBefore = useWatch({ control, name: 'remindBeforeMinutes' })

  const remindHint = useMemo(() => {
    if (remindBefore === 0) {
      return '不发送到期提醒，请自行留意归还时间。'
    }
    if (!endTimeValue) {
      return '选择结束时间后可预览提醒时刻。'
    }
    const end = new Date(endTimeValue)
    if (Number.isNaN(end.getTime())) {
      return ''
    }
    const at = new Date(end.getTime() - Number(remindBefore) * 60_000)
    if (at.getTime() <= Date.now()) {
      return '预约即将到期，系统会在下单后约 1 分钟补发提醒。'
    }
    const text = at.toLocaleString('zh-CN', {
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      hour12: false,
    })
    return `系统将在 ${text} 发送到期提醒。`
  }, [endTimeValue, remindBefore])

  const mutation = useMutation({
    mutationFn: (values: ReservationValues) =>
      rentalsApi.create({ toolId: tool.id, ...values }),
    onSuccess,
  })

  return (
    <form
      className="space-y-3"
      onSubmit={handleSubmit((values) => mutation.mutate(values))}
      noValidate
    >
      <div className="rounded-xl border border-border bg-muted/30 p-3 text-sm">
        <div className="flex items-center justify-between gap-3">
          <span className="text-muted-foreground">工具押金</span>
          <span className="font-semibold text-foreground">
            {formatMoney(tool.deposit)}
          </span>
        </div>
        <p className="mt-1 text-xs text-muted-foreground">
          提交预约后请在「我的租赁」中完成押金支付，取件前 24 小时外可取消并退款。
        </p>
      </div>

      <div className="grid gap-3 sm:grid-cols-2">
        <div>
          <Label htmlFor="startTime">开始时间</Label>
          <Controller
            control={control}
            name="startTime"
            render={({ field, fieldState }) => (
              <Input
                id="startTime"
                type="datetime-local"
                className="h-10 bg-card"
                aria-invalid={fieldState.invalid}
                {...field}
              />
            )}
          />
          <FieldError>{errors.startTime?.message}</FieldError>
        </div>
        <div>
          <Label htmlFor="endTime">结束时间</Label>
          <Controller
            control={control}
            name="endTime"
            render={({ field, fieldState }) => (
              <Input
                id="endTime"
                type="datetime-local"
                className="h-10 bg-card"
                aria-invalid={fieldState.invalid}
                {...field}
              />
            )}
          />
          <FieldError>{errors.endTime?.message}</FieldError>
        </div>
      </div>

      <div>
        <Label htmlFor="pickupPerson">取件人</Label>
        <Controller
          control={control}
          name="pickupPerson"
          render={({ field, fieldState }) => (
            <Input
              id="pickupPerson"
              placeholder="请填写实际取件人姓名"
              className="h-10 bg-card"
              aria-invalid={fieldState.invalid}
              {...field}
            />
          )}
        />
        <FieldError>{errors.pickupPerson?.message}</FieldError>
      </div>

      <div>
        <Label htmlFor="remindBeforeMinutes">到期提醒</Label>
        <Controller
          control={control}
          name="remindBeforeMinutes"
          render={({ field, fieldState }) => (
            <Select
              id="remindBeforeMinutes"
              className="h-10 bg-card"
              aria-invalid={fieldState.invalid}
              value={String(field.value)}
              onChange={(event) => field.onChange(Number(event.target.value))}
            >
              {REMIND_OPTIONS.map((option) => (
                <option key={option.value} value={option.value}>
                  {option.label}
                </option>
              ))}
            </Select>
          )}
        />
        <p className="mt-1 text-xs text-muted-foreground">{remindHint}</p>
        <FieldError>{errors.remindBeforeMinutes?.message}</FieldError>
      </div>

      <div className="flex justify-end gap-2 pt-2">
        <Button type="button" variant="outline" onClick={onCancel}>
          返回
        </Button>
        <Button type="submit" disabled={mutation.isPending}>
          {mutation.isPending && <Loader2 className="size-4 animate-spin" />}
          提交预约
        </Button>
      </div>
    </form>
  )
}
