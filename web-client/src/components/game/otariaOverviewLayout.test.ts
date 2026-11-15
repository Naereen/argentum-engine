import { describe, expect, it } from 'vitest'
import { entityId } from '@/types'
import { otariaBottomOrder, otariaOverviewMapPosition } from './otariaOverviewLayout'

describe('Otaria table overview layout', () => {
  it('reserves the right or center for the map according to the living seats', () => {
    expect(otariaOverviewMapPosition(true, 1, 1)).toBe('right')
    expect(otariaOverviewMapPosition(true, 2, 1)).toBe('right')
    expect(otariaOverviewMapPosition(true, 2, 2)).toBe('center')
    expect(otariaOverviewMapPosition(true, 3, 2)).toBe('right')
    expect(otariaOverviewMapPosition(false, 2, 2)).toBeNull()
    expect(otariaOverviewMapPosition(true, 1, 1)).toBeNull()
    expect(otariaOverviewMapPosition(true, 3, 3)).toBeNull()
  })

  it('moves the anchor to the left only when the map sits on the right', () => {
    const other = entityId('other')
    const anchor = entityId('anchor')
    const ids = [other, anchor]
    expect(otariaBottomOrder(ids, anchor, 'right')).toEqual([anchor, other])
    expect(otariaBottomOrder(ids, anchor, 'center')).toEqual(ids)
    expect(otariaBottomOrder(ids, anchor, null)).toEqual(ids)
    expect(otariaBottomOrder(ids, entityId('missing'), 'right')).toEqual(ids)
  })
})
