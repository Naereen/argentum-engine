import { renderToStaticMarkup } from 'react-dom/server'
import { describe, expect, it } from 'vitest'
import { InitiativeResultNoticeContent } from './InitiativeResultNotice'

describe('InitiativeResultNotice', () => {
  it('announces the explorer win, legal next step and rolls', () => {
    const markup = renderToStaticMarkup(<InitiativeResultNoticeContent
      notice={{ key: 1, explorerName: 'Ari', explorerIsYou: true, outcome: 'explorerWon', rolls: '5 vs 3' }}
    />)
    expect(markup).toContain('You won initiative')
    expect(markup).toContain('You may fight or flee freely.')
    expect(markup).toContain('Rolls: 5 vs 3')
  })

  it('announces an encounter win and the explorer whose turn ends', () => {
    const markup = renderToStaticMarkup(<InitiativeResultNoticeContent
      notice={{ key: 2, explorerName: 'Ari', explorerIsYou: false, outcome: 'encounterWon', rolls: null }}
    />)
    expect(markup).toContain('Encounter won initiative')
    expect(markup).toContain('Ari’s turn ends.')
    expect(markup).not.toContain('Rolls:')
  })
})
