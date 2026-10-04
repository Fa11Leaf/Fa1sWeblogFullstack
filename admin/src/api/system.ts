import { http, unwrap } from './client'
import type { HealthPayload } from './types'

/** 三端连通状态。这个接口不需要登录。 */
export function fetchHealth(): Promise<HealthPayload> {
  return unwrap<HealthPayload>(http.get('/health'))
}
