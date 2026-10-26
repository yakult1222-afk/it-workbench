import { describe, expect, it, vi } from 'vitest'
import { dailySummaryStageCopy, pollDailySummary } from './dailySummaryPolling'

describe('pollDailySummary', () => {
  it('continues through RETRYING and resolves on DEGRADED', async () => {
    const load = vi.fn()
      .mockResolvedValueOnce({ status: 'RETRYING' })
      .mockResolvedValueOnce({ status: 'DEGRADED', content: '模板日报' })
    const onUpdate = vi.fn()

    const result = await pollDailySummary({
      jobId: 1,
      load,
      onUpdate,
      intervalMs: 0,
      timeoutMs: 1000
    })

    expect(result.status).toBe('DEGRADED')
    expect(onUpdate).toHaveBeenCalledWith({ status: 'RETRYING' })
  })

  it('returns extended-wait copy after 45 seconds', () => {
    expect(dailySummaryStageCopy('RUNNING', 46000)).toContain('还需要一点时间')
  })

  it('times out without losing the background job contract', async () => {
    let tick = 0
    const now = () => [0, 1, 2001][tick++] ?? 2001

    await expect(pollDailySummary({
      jobId: 1,
      load: vi.fn().mockResolvedValue({ status: 'RUNNING' }),
      intervalMs: 0,
      timeoutMs: 2000,
      now
    })).rejects.toMatchObject({
      code: 'DAILY_SUMMARY_POLL_TIMEOUT',
      message: '日报还在后台整理，可以关闭弹窗稍后再看'
    })
  })

  it('stops polling when the caller closes the modal', async () => {
    const controller = new AbortController()
    controller.abort()

    await expect(pollDailySummary({
      jobId: 1,
      load: vi.fn(),
      signal: controller.signal,
      intervalMs: 0,
      timeoutMs: 1000
    })).rejects.toMatchObject({ code: 'DAILY_SUMMARY_POLL_ABORTED' })
  })
})
