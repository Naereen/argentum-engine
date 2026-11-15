import type { EntityId } from '@/types'

export type OtariaOverviewMapPosition = 'center' | 'right' | null

export function otariaOverviewMapPosition(
  enabled: boolean,
  topCount: number,
  bottomCount: number,
): OtariaOverviewMapPosition {
  if (!enabled) return null
  if (topCount === 2 && bottomCount === 2) return 'center'
  if (
    (topCount === 1 && bottomCount === 1) ||
    (topCount === 2 && bottomCount === 1) ||
    (topCount === 3 && bottomCount === 2)
  ) return 'right'
  return null
}

export function otariaBottomOrder(
  ids: readonly EntityId[],
  anchorId: EntityId | null,
  mapPosition: OtariaOverviewMapPosition,
): readonly EntityId[] {
  if (mapPosition !== 'right' || !anchorId || !ids.includes(anchorId)) return ids
  return [anchorId, ...ids.filter((id) => id !== anchorId)]
}
