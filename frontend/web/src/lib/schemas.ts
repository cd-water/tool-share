import { z } from 'zod'

export type LoginMode = 'code' | 'pwd'

const phoneField = z
  .string()
  .regex(/^1[3-9]\d{9}$/, '请输入正确的手机号')

export const loginSchema = z
  .object({
    mode: z.enum(['code', 'pwd']),
    phone: phoneField,
    code: z.string(),
    password: z.string(),
  })
  .superRefine((values, ctx) => {
    if (values.mode === 'code' && !/^\d{6}$/.test(values.code)) {
      ctx.addIssue({
        code: 'custom',
        path: ['code'],
        message: '请输入 6 位验证码',
      })
    }
    if (values.mode === 'pwd' && values.password.length === 0) {
      ctx.addIssue({
        code: 'custom',
        path: ['password'],
        message: '请输入密码',
      })
    }
  })

export type LoginFormValues = z.infer<typeof loginSchema>
