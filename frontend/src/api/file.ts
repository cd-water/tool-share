import { upload } from './request'
import type { FileVO } from './types'

/** 通用文件上传（工具图片、报修材料等） */
export function uploadFile(form: FormData) {
  return upload<FileVO>('/file/upload', form)
}
