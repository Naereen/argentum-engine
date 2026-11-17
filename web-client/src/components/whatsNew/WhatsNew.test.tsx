// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { I18nextProvider } from 'react-i18next'
import i18n from '@/i18n'
import { WhatsNew } from './WhatsNew'

describe('What’s new localization', () => {
  beforeEach(async () => {
    window.localStorage.clear()
    await i18n.changeLanguage('en')
  })

  afterEach(() => {
    cleanup()
  })

  it('localizes Otaria announcements in the feed and unread label', async () => {
    render(
      <I18nextProvider i18n={i18n}>
        <WhatsNew compact />
      </I18nextProvider>,
    )

    fireEvent.click(screen.getByTitle("What's new in Argentum"))
    expect(await screen.findByText('Invite friends to explore Otaria')).toBeTruthy()

    await act(async () => {
      await i18n.changeLanguage('fr')
    })

    expect(await screen.findByText('Explorez Otaria avec vos amis')).toBeTruthy()
    expect(screen.getByText(/Explorez Otaria sur une carte hexagonale/)).toBeTruthy()
  })
})
