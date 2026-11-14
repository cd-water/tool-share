/**
 * 清洗查询参数。
 *
 * 表单常用空串表示「不筛选」，但把空串传给后端会被当成有效条件，
 * 因此发送前统一去掉空串、null 和 undefined。
 *
 * @param params 原始查询参数对象
 * @returns 仅保留有值字段的新对象；不会修改入参
 * 注意：0、false 等合法值会被保留，不做布尔化处理。
 */
export function cleanParams<T extends object>(params: T): Partial<T> {
  // 以值为准过滤，保留 0/false；返回新对象避免影响调用方状态。
  return Object.fromEntries(
    // 只保留有实际值的字段。
    Object.entries(params).filter(
      // 0 与 false 是合法筛选值，不能用真值判断。
      ([, value]) => value !== '' && value !== null && value !== undefined,
    ),
  // Object.fromEntries 的宽泛类型在此收窄为 Partial<T>。
  ) as Partial<T>
}
