import type { MsgItem, MsgQuery, PageResult } from './types'
import { httpGet } from '../utils/request'

export function myMessages(params: MsgQuery) {
  return httpGet<PageResult<MsgItem>>('/message/records/my', params)
}

export function listMessages(params: MsgQuery) {
  return httpGet<PageResult<MsgItem>>('/message/records', params)
}
