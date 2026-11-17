// @vitest-environment jsdom
import { act, cleanup, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { I18nextProvider } from 'react-i18next'
import i18n from '@/i18n'
import { PublicLobbyList, type PublicLobbyEntry } from './PublicLobbyList'

vi.mock('@/components/profile/accountUi', () => ({
  Avatar: () => <span aria-hidden />,
}))

const lobby: PublicLobbyEntry = {
  kind: 'quickGame',
  lobbyId: 'public-lobby',
  playerCount: 1,
  maxPlayers: 2,
  setCode: null,
  hostName: 'Alice',
  format: 'PAUPER',
  hostAvatar: null,
  playerNames: ['Alice'],
  ranked: true,
  openSeconds: 30,
}

describe('Public lobby localization', () => {
  beforeEach(async () => {
    window.localStorage.clear()
    await i18n.changeLanguage('en')
  })

  afterEach(() => {
    cleanup()
  })

  it('localizes lobby controls and status while preserving player and format names', async () => {
    render(
      <I18nextProvider i18n={i18n}>
        <PublicLobbyList lobbies={[lobby]} error={null} onlinePlayers={3} onJoin={() => undefined} />
      </I18nextProvider>,
    )

    await act(async () => {
      await i18n.changeLanguage('fr')
    })

    expect(screen.getByRole('heading', { name: /Salons ouverts/ })).toBeTruthy()
    expect(screen.getByText('3 joueurs en ligne')).toBeTruthy()
    expect(screen.getByText('Classé')).toBeTruthy()
    expect(screen.getByText('Pauper')).toBeTruthy()
    expect(screen.getByText('Decks légaux en Pauper')).toBeTruthy()
    expect(screen.getByText('Organisé par')).toBeTruthy()
    expect(screen.getByText('Alice')).toBeTruthy()
    expect(screen.getByTitle('1 place libre')).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Rejoindre le salon Pauper de Alice' })).toBeTruthy()
  })
})
