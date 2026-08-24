import { afterEach, describe, expect, it, vi } from 'vitest'
import { http, skillApi } from '@/api/index'

describe('skill proposal API', () => {
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('preserves snowflake ids across routine and proposal review endpoints', () => {
    const get = vi.spyOn(http, 'get').mockResolvedValue({} as never)
    const post = vi.spyOn(http, 'post').mockResolvedValue({} as never)
    const candidateId = '9007199254740993'
    const proposalId = '9007199254740995'

    skillApi.routinePromote(candidateId)
    skillApi.skillProposal(proposalId)
    skillApi.skillProposalApply(proposalId)
    skillApi.skillProposalRollback(proposalId, { reviewer: 'admin', note: 'restore' })

    expect(post).toHaveBeenNthCalledWith(1, `/skills/routines/${candidateId}/promote`)
    expect(get).toHaveBeenCalledWith(`/skills/proposals/${proposalId}`)
    expect(post).toHaveBeenNthCalledWith(2, `/skills/proposals/${proposalId}/apply`)
    expect(post).toHaveBeenNthCalledWith(3, `/skills/proposals/${proposalId}/rollback`, {
      reviewer: 'admin',
      note: 'restore',
      applyNow: false,
    })
  })

  it('keeps approval and application as separate requests', () => {
    const get = vi.spyOn(http, 'get').mockResolvedValue({} as never)
    const post = vi.spyOn(http, 'post').mockResolvedValue({} as never)
    const proposalId = '9007199254740995'

    skillApi.skillProposals({ page: 2, size: 20, status: 'PENDING' })
    skillApi.skillProposalApprove(proposalId, { reviewer: 'admin', note: 'diff reviewed' })

    expect(get).toHaveBeenCalledWith('/skills/proposals', {
      params: { page: 2, size: 20, status: 'PENDING' },
    })
    expect(post).toHaveBeenCalledWith(`/skills/proposals/${proposalId}/approve`, {
      reviewer: 'admin',
      note: 'diff reviewed',
      applyNow: false,
    })
    expect(post).not.toHaveBeenCalledWith(`/skills/proposals/${proposalId}/apply`)
  })
})
