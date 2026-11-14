import type { Color } from './enums'
import type { EntityId } from './entities'

export type OtariaLocation =
  | 'KROSIAN_FOREST'
  | 'ORDER_ENCAMPMENT'
  | 'SOUTHERN_PIT_ARENA'
  | 'BALSHAN_BAY'
  | 'PARDIC_MOUNTAINS'
  | 'ORDER_SOUTHERN_ENCAMPMENT'
  | 'CABAL_CITY'
  | 'CEPHALID_SOUTHERN_PALACE'

export interface OtariaPosition {
  readonly column: number
  readonly row: number
}

export interface ClientOtariaTile {
  readonly position: OtariaPosition
  readonly color: Color
  readonly location?: OtariaLocation | null
}

export interface ClientOtariaReserveCard {
  readonly id: EntityId
  readonly name: string
  readonly manaCost: string
  readonly typeLine: string
  readonly oracleText: string
  readonly imageUri?: string | null
}

export interface ClientOtariaPlayer {
  readonly playerId: EntityId
  readonly startingLocation: OtariaLocation
  readonly position: OtariaPosition
  readonly reserveCount: number
  readonly privateData?: {
    readonly reserve: readonly ClientOtariaReserveCard[]
    readonly locationCards: readonly OtariaLocation[]
    readonly treasureTokens: Readonly<Partial<Record<'SCRAP' | 'ROCK', number>>>
  } | null
}

export interface ClientOtariaPile {
  readonly remaining: number
  readonly discarded: number
}

export interface ClientOtariaTurn {
  readonly action: 'MOVE' | 'EXPLORE' | 'PREPARE' | 'COMBAT'
  readonly movementAllowed: boolean
  readonly explorationFinished: boolean
  readonly encounterInitiative?: 'EXPLORER' | 'ENCOUNTER' | null
  readonly encounterAction?: 'MOVE' | 'COMBAT' | null
}

export interface ClientOtariaEncounter {
  readonly actorId: EntityId
  readonly explorerId: EntityId
  readonly managerId: EntityId
  readonly position: OtariaPosition
  readonly participants: readonly EntityId[]
  readonly initiative?: 'EXPLORER' | 'ENCOUNTER' | null
}

export interface ClientOtariaState {
  readonly tiles: readonly ClientOtariaTile[]
  readonly players: readonly ClientOtariaPlayer[]
  readonly locations: readonly {
    readonly location: OtariaLocation
    readonly used: number
    readonly limit: number
  }[]
  readonly starPile: ClientOtariaPile
  readonly colorPiles: Readonly<Record<Color, ClientOtariaPile>>
  readonly treasureCount: number
  readonly locationCount: number
  readonly encounters?: readonly ClientOtariaEncounter[]
  readonly turn?: ClientOtariaTurn | null
  readonly pendingExploration?: {
    readonly explorerId: EntityId
    readonly managerId: EntityId
    readonly source: { readonly type: 'Star'; readonly location: OtariaLocation }
      | { readonly type: 'Terrain'; readonly color: Color }
    readonly stage: 'DRAWN' | 'CHOOSING_LOCATION'
    readonly faceDown: boolean
    readonly card?: {
      readonly name: string
      readonly kind: 'CREATURE' | 'NONCREATURE' | 'EVENT' | 'DISCOVERY' | 'BOSS'
      readonly imageUri?: string | null
    } | null
  } | null
}
