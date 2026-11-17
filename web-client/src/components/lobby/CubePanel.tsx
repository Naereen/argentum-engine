/**
 * CubePanel — the host's cube control, sitting where the set picker sits for a normal lobby (a cube is
 * a *pack source*, so the two are alternatives, not additions).
 *
 * Lets the host pick a cube from their library (local + account, merged by `useUnifiedCubes`), build or
 * edit one in the {@link CubeEditor}, and see the capacity constraint live — `players × packs × packSize
 * ≤ cube size` is the one cube-specific rule a host has to plan around, so it's shown as a sentence
 * rather than discovered when Start refuses.
 *
 * A cube in one of the booster styles prints its packs by rarity, with replacement, like a real set —
 * so it has no capacity to plan around, and the sentence says that instead.
 *
 * Server-authoritative: this component only sends `updateLobbySettings`. The server resolves the cube
 * against the card registry, re-checks capacity, and remains the authority on both.
 */
import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import type { SharedCube } from '@/api/account'
import { CubeEditor } from '@/components/cube/CubeEditor'
import { cubeLobbyUpdate, isBoosterStyle } from '@/components/cube/cubeModel'
import { type UnifiedCube, useUnifiedCubes } from '@/store/useUnifiedCubes'
import type { LobbySettings } from '@/types/messages'
import styles from './CubePanel.module.css'

interface CubePanelProps {
  readonly settings: LobbySettings
  readonly playerCount: number
  /**
   * The lobby's settings sender. Typed as the whole settings bag (rather than just the cube keys) so
   * this stays assignable from the store's action under `exactOptionalPropertyTypes`.
   */
  readonly updateLobbySettings: (settings: ReturnType<typeof cubeLobbyUpdate>) => void
}

/**
 * How the active format consumes packs. Winston and Grid deal one shared pile for the table; sealed and
 * booster draft deal per seat. Mirrors `TournamentLobby.cubeCapacityError`.
 */
function usesSharedPool(format: string): boolean {
  return format === 'WINSTON_DRAFT' || format === 'GRID_DRAFT'
}

export function CubePanel({ settings, playerCount, updateLobbySettings }: CubePanelProps) {
  const { t } = useTranslation()
  const { cubes, loading, removeCube } = useUnifiedCubes()
  const [showLibrary, setShowLibrary] = useState(false)
  const [editing, setEditing] = useState<{ cube: UnifiedCube | null } | null>(null)

  const activeCubeName = settings.cubeName ?? null
  const cubeCards = settings.cubeCardCount ?? 0
  const packSize = settings.packSize ?? 15
  const boosterStyle = settings.cubeBoosterStyle ?? 'SHUFFLED'
  const printsBoosters = isBoosterStyle(boosterStyle)

  /** The library entry backing the lobby's cube, when the host picked it from the library. */
  const activeCube = useMemo(
    () => cubes.find((c) => c.name.toLowerCase() === (activeCubeName ?? '').toLowerCase()) ?? null,
    [cubes, activeCubeName],
  )

  /**
   * Basic-land art sources. A cube has no basics of its own, so it names a set to take them from;
   * offer the regular (non-extension) sets the server already advertised.
   */
  const basicLandSets = useMemo(
    () =>
      settings.availableSets
        .filter((set) => !set.extensionSet)
        .map((set) => ({ code: set.code, name: set.name })),
    [settings.availableSets],
  )

  function useCube(cube: SharedCube) {
    updateLobbySettings(cubeLobbyUpdate(cube))
    setShowLibrary(false)
    setEditing(null)
  }

  const capacity = useMemo(() => {
    if (!activeCubeName) return null
    if (settings.format === 'PREMADE_DECKS') return null
    if (settings.cubePoolPlay && settings.format === 'SEALED') {
      return { ok: true, text: t('lobby.cubeCapacitySealed', { count: cubeCards }) }
    }
    if (printsBoosters) {
      return { ok: true, text: t('lobby.cubeCapacityBoosters') }
    }
    const shared = usesSharedPool(settings.format)
    const seats = Math.max(2, playerCount)
    const packsNeeded = shared ? settings.boosterCount : seats * settings.boosterCount
    const cardsNeeded = packsNeeded * packSize
    if (cardsNeeded > cubeCards) {
      return {
        ok: false,
        text: shared
          ? t('lobby.cubeCapacityShortShared', {
              packs: settings.boosterCount,
              packSize,
              needed: cardsNeeded,
              available: cubeCards,
            })
          : t('lobby.cubeCapacityShortPlayers', {
              players: seats,
              packs: settings.boosterCount,
              packSize,
              needed: cardsNeeded,
              available: cubeCards,
            }),
      }
    }
    if (shared) {
      return {
        ok: true,
        text: t('lobby.cubeCapacityEnoughShared', {
          count: cubeCards,
          packs: settings.boosterCount,
          packSize,
        }),
      }
    }
    const maxSeats = Math.floor(cubeCards / (settings.boosterCount * packSize))
    return {
      ok: true,
      text: t('lobby.cubeCapacityEnoughPlayers', {
        count: cubeCards,
        players: maxSeats,
        packs: settings.boosterCount,
        packSize,
      }),
    }
  }, [activeCubeName, cubeCards, packSize, printsBoosters, playerCount, settings.boosterCount, settings.format, settings.cubePoolPlay, t])

  const boosterLabel = boosterStyle === 'PLAY_BOOSTER'
    ? t('lobby.playBoosters')
    : boosterStyle === 'DRAFT_BOOSTER'
      ? t('lobby.draftBoosters')
      : t('lobby.shuffledCube')

  return (
    <div className={styles.panel}>
      <div className={styles.current}>
        {activeCubeName ? (
          <>
            <span className={styles.cubeName}>{activeCubeName}</span>
            <span className={styles.meta}>
              {cubeCards} cards ·{' '}
              {printsBoosters ? boosterLabel : t('lobby.cardsPerPack', { count: packSize })}
            </span>
          </>
        ) : (
          <span className={styles.meta}>{t('lobby.chooseOrCreateCube')}</span>
        )}
      </div>

      {capacity && (
        <span className={capacity.ok ? styles.capacityOk : styles.capacityBad}>{capacity.text}</span>
      )}

      <div className={styles.actions}>
        <button type="button" className={styles.button} onClick={() => setShowLibrary((v) => !v)}>
          {showLibrary ? t('lobby.hideCubes') : activeCubeName ? t('lobby.changeCube') : t('lobby.chooseCube')}
        </button>
        <button type="button" className={styles.button} onClick={() => setEditing({ cube: null })}>
          {t('lobby.newCube')}
        </button>
        {activeCube && (
          <button type="button" className={styles.button} onClick={() => setEditing({ cube: activeCube })}>
            {t('lobby.editCube', { name: activeCube.name })}
          </button>
        )}
      </div>

      {showLibrary && (
        <>
          {loading && cubes.length === 0 ? (
            <p className={styles.empty}>{t('lobby.loadingCubes')}</p>
          ) : cubes.length === 0 ? (
            <p className={styles.empty}>
              {t('lobby.noSavedCubes')}
            </p>
          ) : (
            <ul className={styles.library}>
              {cubes.map((cube) => (
                <li key={cube.id} className={styles.libraryItem}>
                  <button
                    type="button"
                    className={styles.libraryPick}
                    onClick={() => useCube(cube)}
                  >
                    <span className={styles.libraryName}>{cube.name}</span>
                    <span className={styles.libraryMeta}>
                      {cube.online && <span className={styles.onlineBadge}>{t('lobby.online')}</span>}
                      {t('lobby.cubeCardCount', { count: cube.cards.reduce((sum, e) => sum + e.count, 0) })}
                      {isBoosterStyle(cube.boosterStyle) && ` · ${cube.boosterStyle === 'PLAY_BOOSTER' ? t('lobby.playBoosters') : t('lobby.draftBoosters')}`}
                    </span>
                  </button>
                  <button
                    type="button"
                    className={styles.iconButton}
                    aria-label={t('lobby.editCube', { name: cube.name })}
                    title={t('lobby.editCube', { name: cube.name })}
                    onClick={() => setEditing({ cube })}
                  >
                    ✎
                  </button>
                  <button
                    type="button"
                    className={styles.iconButton}
                    aria-label={t('lobby.deleteCube', { name: cube.name })}
                    title={t('lobby.deleteCube', { name: cube.name })}
                    onClick={() => void removeCube(cube)}
                  >
                    ×
                  </button>
                </li>
              ))}
            </ul>
          )}
        </>
      )}

      {editing && (
        <CubeEditor
          cube={editing.cube}
          availableSets={basicLandSets}
          onClose={() => setEditing(null)}
          onUse={useCube}
        />
      )}
    </div>
  )
}
