import { afterEach, describe, expect, it, vi } from 'vitest'
import { aiNewsApi, http } from '@/api/index'

describe('aiNewsApi candidate pipeline', () => {
  afterEach(() => vi.restoreAllMocks())

  it('keeps scan and candidate snowflake ids as strings', () => {
    const get = vi.spyOn(http, 'get').mockResolvedValue({} as never)
    const post = vi.spyOn(http, 'post').mockResolvedValue({} as never)
    const scanRunId = '9007199254740993'
    const candidateId = '9007199254740995'

    aiNewsApi.listCandidateRuns({ page: 1, size: 20 })
    aiNewsApi.getCandidateRun(scanRunId)
    aiNewsApi.listCandidates({ page: 2, size: 20, scanRunId, reviewStatus: 'PENDING' })
    aiNewsApi.reviewCandidate(candidateId, 'ACCEPTED', '重要产品发布')

    expect(get).toHaveBeenNthCalledWith(1, '/ai-news/candidate-pipeline/scans', {
      params: { page: 1, size: 20 },
    })
    expect(get).toHaveBeenNthCalledWith(2, `/ai-news/candidate-pipeline/scans/${scanRunId}`)
    expect(get).toHaveBeenNthCalledWith(3, '/ai-news/candidate-pipeline/candidates', {
      params: { page: 2, size: 20, scanRunId, reviewStatus: 'PENDING' },
    })
    expect(post).toHaveBeenCalledWith(
      `/ai-news/candidate-pipeline/candidates/${candidateId}/review`,
      { decision: 'ACCEPTED', reason: '重要产品发布' },
    )
  })
})
