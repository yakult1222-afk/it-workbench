const TERMINAL_STATUSES = new Set(['SUCCEEDED', 'DEGRADED', 'FAILED', 'SUPERSEDED'])

function namedError(code, message) {
  const error = new Error(message)
  error.code = code
  return error
}

function wait(ms, signal) {
  if (signal?.aborted) {
    return Promise.reject(namedError('DAILY_SUMMARY_POLL_ABORTED', '已停止等待日报'))
  }
  return new Promise((resolve, reject) => {
    const timer = setTimeout(resolve, ms)
    const abort = () => {
      clearTimeout(timer)
      reject(namedError('DAILY_SUMMARY_POLL_ABORTED', '已停止等待日报'))
    }
    signal?.addEventListener('abort', abort, { once: true })
  })
}

export function dailySummaryStageCopy(status, elapsedMs = 0) {
  if (status === 'QUEUED') return '日报已进入整理队列，马上开始…'
  if (status === 'RETRYING') return 'AI 有点忙，正在重新整理…'
  if (status === 'SUCCEEDED') return '日报整理完成…'
  if (status === 'DEGRADED') return 'AI 暂时繁忙，已切换到模板汇总…'
  if (status === 'FAILED') return '日报整理没有完成…'
  if (status === 'SUPERSEDED') return '任务记录有更新，正在重新整理…'
  if (elapsedMs >= 45000) return '日报还在整理，可能还需要一点时间…'
  if (elapsedMs >= 25000) return '正在把零散的处理记录串成一份日报…'
  if (elapsedMs >= 10000) return '正在梳理完成、进行中和延期事项…'
  return '正在翻阅今天的任务记录…'
}

export async function pollDailySummary({
  jobId,
  load,
  onUpdate,
  intervalMs = 1000,
  timeoutMs = 180000,
  signal,
  now = Date.now
}) {
  const startedAt = now()
  while (true) {
    if (signal?.aborted) {
      throw namedError('DAILY_SUMMARY_POLL_ABORTED', '已停止等待日报')
    }
    if (now() - startedAt >= timeoutMs) {
      throw namedError(
        'DAILY_SUMMARY_POLL_TIMEOUT',
        '日报还在后台整理，可以关闭弹窗稍后再看'
      )
    }

    const job = await load(jobId)
    if (onUpdate) onUpdate(job)
    if (TERMINAL_STATUSES.has(job.status)) {
      return job
    }
    await wait(intervalMs, signal)
  }
}
