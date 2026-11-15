import { renderToStaticMarkup } from 'react-dom/server'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import { Color } from '@/types'
import { OtariaPlaytestContents, readOtariaResponse, type OtariaPlaytestOptions } from './OtariaPlaytestPage'

vi.mock('@/components/admin/DevPage', () => ({ DevPage: () => null }))
vi.mock('@/components/ui/PageShell', () => ({ pageStyles: {} }))

const options: OtariaPlaytestOptions = {
  aiEnabled: true,
  decks: [
    { id: 'J22:Elves (1)', setCode: 'J22', theme: 'Elves', color: Color.GREEN,
      cards: { Forest: 7, 'Llanowar Elves': 1 }, startingLands: ['Forest', 'Forest'], startingLandSource: 'DECK' },
    { id: "J22:Urza's", setCode: 'J22', theme: "Urza's", color: 'OTHER',
      cards: { 'Karn Liberated': 1 }, startingLands: ['Wastes', 'Wastes'], startingLandSource: 'SEPARATE_STOCK' },
    { id: 'J25:Chaos', setCode: 'J25', theme: 'Chaos', color: 'OTHER',
      cards: { 'Averna, the Chaos Bloom': 1 }, startingLands: ['Forest', 'Island'], startingLandSource: 'DECK' },
  ],
  limitations: ['Experimental playtest, not a complete campaign.'],
  missingCards: ['Missing creature'],
}

function render(overrides: Partial<Parameters<typeof OtariaPlaytestContents>[0]> = {}) {
  return renderToStaticMarkup(<MemoryRouter><OtariaPlaytestContents
    options={options} name="Explorer" onName={() => {}} opponents={2} onOpponents={() => {}}
    hostSeat={overrides.hostSeat ?? 0} onHostSeat={overrides.onHostSeat ?? (() => {})}
    color={Color.GREEN} onColor={() => {}} starting={false} onStart={() => {}} {...overrides}
    boosterId={overrides.boosterId ?? 'J22:Elves (1)'} onBoosterId={overrides.onBoosterId ?? (() => {})}
    fullAi={overrides.fullAi ?? false} onFullAi={overrides.onFullAi ?? (() => {})}
    aiThinkingDelayMs={overrides.aiThinkingDelayMs ?? 100}
    onAiThinkingDelayMs={overrides.onAiThinkingDelayMs ?? (() => {})}
    online={overrides.online ?? false} onOnline={overrides.onOnline ?? (() => {})}
    lobby={overrides.lobby ?? null} invitePreview={overrides.invitePreview ?? null}
    inviteUrl={overrides.inviteUrl ?? null} inviteSeat={overrides.inviteSeat ?? null}
    onInviteSeat={overrides.onInviteSeat ?? (() => {})} onJoinLobby={overrides.onJoinLobby ?? (() => {})}
    onReady={overrides.onReady ?? (() => {})} onStartLobby={overrides.onStartLobby ?? (() => {})}
  /></MemoryRouter>)
}

describe('Otaria playtest launch panel', () => {
  it('offers every supported AI table size, server decks and explicit limitations', () => {
    const html = render()
    expect(html).toContain('3 players total')
    expect(html).toContain('4 players total')
    expect(html).toContain('5 players total')
    expect(html).toContain('Start playtest vs 2 AI')
    expect(html).toContain('Experimental playtest, not a complete campaign.')
    expect(html).toContain('Missing creature')
    expect(html).toContain('Llanowar Elves')
    expect(html).toContain('Jumpstart 2022')
    expect(html).not.toContain('7 commons')
    expect(html).toContain('ms-g ms-cost')
    expect(html).toContain('Otaria map')
  })

  describe('Otaria API responses', () => {
    it('returns a valid success response', async () => {
      await expect(readOtariaResponse(new Response('{"decks":[]}', { status: 200 }))).resolves.toEqual({ decks: [] })
    })

    it('shows the server error on a valid JSON failure', async () => {
      await expect(readOtariaResponse(new Response('{"errors":["Card not found in registry: Example"]}', { status: 400 })))
        .rejects.toThrow('Card not found in registry: Example')
    })

    it.each(['', '<html>Server error</html>', '{'])('reports HTTP status for a non-JSON failure: %s', async (body) => {
      await expect(readOtariaResponse(new Response(body, { status: 500 })))
        .rejects.toThrow('HTTP 500')
    })

    it.each(['', '<html>Not the API</html>', '{'])('reports invalid JSON for a successful response: %s', async (body) => {
      await expect(readOtariaResponse(new Response(body, { status: 200 })))
        .rejects.toThrow('empty or invalid JSON response for Otaria (HTTP 200)')
    })

    it('preserves the disabled-dev-endpoint guidance for an empty 404', async () => {
      await expect(readOtariaResponse(new Response(null, { status: 404 })))
        .rejects.toThrow('game.dev-endpoints.enabled=true')
    })
  })

  it('disables launch when AI is unavailable or the name is blank', () => {
    expect(render({ options: { ...options, aiEnabled: false } })).toContain('game.ai.enabled=true')
    expect(render({ options: { ...options, aiEnabled: false } })).toMatch(/disabled=""[^>]*>Start playtest/)
    expect(render({ options: { ...options, aiEnabled: false }, online: true })).not.toMatch(/disabled=""[^>]*>Create invite lobby/)
    expect(render({ name: ' ' })).toMatch(/disabled=""[^>]*>Start playtest/)
  })

  it('shows the chosen size and prevents duplicate launch while preparing', () => {
    expect(render({ opponents: 4 })).toContain('Start playtest vs 4 AI')
    expect(render({ starting: true })).toMatch(/disabled=""[^>]*>Preparing the table/)
  })

  it('offers an invite lobby with human-selected seats and AI filling vacancies', () => {
    const html = render({ online: true })
    expect(html).toContain('Online lobby')
    expect(html).toContain('Total table size')
    expect(html).toContain('Your seat')
    expect(html).toContain('Create invite lobby')
    expect(html).toContain('Jumpstart 2022')

    const lobbyHtml = render({
      lobby: {
        lobbyId: 'room-12345678', tableSize: 4,
        players: [{ seatIndex: 0, name: 'Explorer', host: true, ready: true, you: true }],
        openSeats: [1, 2, 3], aiSeatsOnStart: 3, started: false, memberToken: 'member-token',
      },
      inviteUrl: 'http://localhost/otaria?lobby=room-12345678',
    })
    expect(lobbyHtml).toContain('Seat 1')
    expect(lobbyHtml).toContain('Open — AI fills at start')
    expect(lobbyHtml).toContain('Cancel ready')
    expect(lobbyHtml).toContain('Start game')
    expect(lobbyHtml).toContain('http://localhost/otaria?lobby=room-12345678')
  })

  it('offers fast autonomous tables without a human seat', () => {
    const html = render({ fullAi: true })
    expect(html).toContain('Full AI (fast)')
    expect(html).toContain('Watch 3 AI explorers')
    expect(html).toContain('First AI explorer name')
    expect(html).toContain('Every AI independently rolls a random color category')
    expect(html).not.toContain('Theme and booster variant')
    expect(html).toContain('no input is needed')
    expect(html).toContain('Delay between AI actions:')
    expect(html).toContain('value="100"')
    expect(html).toContain('max="5000"')
    expect(render({ fullAi: true, aiThinkingDelayMs: 800 })).toContain('value="800"')
    expect(render({ fullAi: true, opponents: 4 })).toContain('Watch 5 AI explorers')
    expect(render({ fullAi: true, starting: true })).toMatch(/disabled=""[^>]*>Preparing the table/)
  })

  it('offers fully random, color-random and exact booster selection', () => {
    const random = render({ color: null, boosterId: '' })
    expect(random).toContain('Any color (random)')
    expect(random).toContain('Foundations Jumpstart')
    expect(random).toContain('Chaos - Other')
    expect(random).toContain('A surprise Jumpstart booster')
    expect(random).not.toMatch(/disabled=""[^>]*>Start playtest/)
    const green = render({ boosterId: '' })
    expect(green).toContain('Random booster - Green')
    expect(green).not.toContain('Chaos - Other')
    expect(green).not.toContain('Exactly 20 cards')
  })

  it('previews Other packs with the explicit extra-Wastes rule', () => {
    const html = render({ color: 'OTHER', boosterId: "J22:Urza's" })
    expect(html).toContain('Karn Liberated')
    expect(html).toContain('two extra Wastes start in play')
    expect(html).toContain('all 20 booster cards remain available to draw')
    expect(html).not.toContain('Llanowar Elves')
    expect(render({ color: 'OTHER', boosterId: 'J25:Chaos' })).toContain('Forest + Island')
  })

  it('rejects a stale booster from another category', () => {
    expect(render({ color: Color.BLUE })).toMatch(/disabled=""[^>]*>Start playtest/)
  })
})
