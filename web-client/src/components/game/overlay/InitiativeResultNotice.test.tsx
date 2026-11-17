import { renderToStaticMarkup } from 'react-dom/server'
import type React from 'react'
import { beforeEach, describe, expect, it } from 'vitest'
import { I18nextProvider } from 'react-i18next'
import i18n from '@/i18n'
import { InitiativeResultNoticeContent } from './InitiativeResultNotice'

const render = (node: React.ReactNode) => renderToStaticMarkup(<I18nextProvider i18n={i18n}>{node}</I18nextProvider>)

describe('InitiativeResultNotice', () => {
  beforeEach(async () => {
    await i18n.changeLanguage('en')
  })

  it('announces the explorer win, legal next step and rolls', () => {
    const markup = render(<InitiativeResultNoticeContent
      notice={{ key: 1, explorerName: 'Ari', explorerIsYou: true, outcome: 'explorerWon', rolls: '5 vs 3' }}
    />)
    expect(markup).toContain('You won initiative')
    expect(markup).toContain('You may fight or flee freely.')
    expect(markup).toContain('Rolls: 5 vs 3')
  })

  it('announces an encounter win and the explorer whose turn ends', () => {
    const markup = render(<InitiativeResultNoticeContent
      notice={{ key: 2, explorerName: 'Ari', explorerIsYou: false, outcome: 'encounterWon', rolls: null }}
    />)
    expect(markup).toContain('Encounter won initiative')
    expect(markup).toContain('Ari’s turn ends.')
    expect(markup).not.toContain('Rolls:')
  })
})
