import { renderToStaticMarkup } from 'react-dom/server'
import { readdirSync } from 'node:fs'
import { describe, expect, it } from 'vitest'
import { entityId } from '@/types'
import { LogEntryRow } from './GameLog'

describe('illustrated game log', () => {
  it('ships every mapped event and merchant proxy with the client', () => {
    expect(readdirSync(new URL('../../../public/images/otaria/events/', import.meta.url)).sort()).toEqual([
      'blocage-cerebral.png', 'flux-du-jusant.png', 'frappe-concertee.png', 'infestation.png',
      'merchant-b3.png', 'merchant-b5.png', 'merchant-g3.png', 'merchant-g5.png',
      'merchant-r3.png', 'merchant-r5.png', 'merchant-u3.png', 'merchant-u5.png',
      'merchant-w3.png', 'merchant-w5.png', 'naturalisation.png', 'pyroclasme.png',
    ])
  })
  it('keeps event artwork inspectable without looking up a French proxy on Scryfall', () => {
    const html = renderToStaticMarkup(<LogEntryRow entry={{
      description: 'Ev. Naturalisation finished resolving and was discarded.',
      playerId: entityId('explorer'),
      timestamp: 0,
      type: 'action',
      cardName: 'Ev. Naturalisation',
      imageUri: '/images/otaria/events/naturalisation.png',
    }} isPlayer seatColor={null} />)
    expect(html).toContain('finished resolving and was discarded')
    expect(html).toContain('<details')
    expect(html).toContain('View card: Ev. Naturalisation')
    expect(html).toContain('src="/images/otaria/events/naturalisation.png"')
    expect(html).not.toContain('scryfall')
  })

  it('keeps ordinary log entries unchanged and unknown events readable without bogus artwork', () => {
    const html = renderToStaticMarkup(<LogEntryRow entry={{
      description: 'Unknown event was discarded without resolving.',
      playerId: null,
      timestamp: 0,
      type: 'action',
      cardName: 'Unknown event',
      imageUri: null,
    }} isPlayer={false} seatColor={null} />)
    expect(html).toContain('discarded without resolving')
    expect(html).not.toContain('<img')
    expect(html).not.toContain('<details')
  })
})
