// @vitest-environment jsdom
import type { ReactNode } from 'react'
import { act, cleanup, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { I18nextProvider } from 'react-i18next'
import { MemoryRouter } from 'react-router-dom'
import i18n from '@/i18n'
import { SetCompletionPage } from './SetCompletionPage'

vi.mock('../ui/PageShell', () => ({
  PageShell: ({ children }: { children: ReactNode }) => <>{children}</>,
  pageStyles: { h1: '', lede: '', tabs: '', tab: '' },
}))

describe('SetCompletionPage localization', () => {
  beforeEach(async () => {
    await i18n.changeLanguage('en')
  })

  afterEach(() => {
    cleanup()
  })

  it('updates page heading, tabs, and help label when the active language changes', async () => {
    render(
      <I18nextProvider i18n={i18n}>
        <MemoryRouter>
          <SetCompletionPage />
        </MemoryRouter>
      </I18nextProvider>,
    )

    expect(screen.getByRole('heading', { name: 'Set completion' })).toBeTruthy()
    expect(screen.getByRole('tab', { name: 'Sets' })).toBeTruthy()
    expect(screen.getByRole('button', { name: 'What is the Assay Explorer?' })).toBeTruthy()

    await act(async () => {
      await i18n.changeLanguage('fr')
    })

    expect(screen.getByRole('heading', { name: 'Avancement des extensions' })).toBeTruthy()
    expect(screen.getByRole('tab', { name: 'Extensions' })).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Qu’est-ce que l’explorateur Assay ?' })).toBeTruthy()
  })
})
