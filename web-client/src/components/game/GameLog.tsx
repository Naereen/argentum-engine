import { useGameStore, type LogEntry } from '@/store/gameStore.ts'
import { identitySeatColor, selectTeamMap } from '@/store/selectors.ts'
import type { EntityId } from '@/types'
import { markLearnSignal } from '@/learn/signals'
import React, { useState, useRef, useEffect } from 'react'
import { useTranslation } from 'react-i18next'
import type { TFunction } from 'i18next'

/**
 * Collapsible game log panel showing accumulated events.
 */
export function GameLog() {
  const { t } = useTranslation()
  const eventLog = useGameStore((state) => state.eventLog)
  const playerId = useGameStore((state) => state.playerId)
  const isOtaria = useGameStore((state) => !!state.gameState?.otaria)
  // Multiplayer: log entries take the actor's seat color ("who did that?" is the
  // dominant question in a pod). 2-player keeps the classic cyan/orange split.
  const players = useGameStore((state) => state.gameState?.players)
  const teamMap = useGameStore(selectTeamMap)
  const isMulti = (players?.length ?? 0) > 2
  // Identity colour, not raw seat colour: in a Two-Headed Giant game the rail, orbs and plates
  // paint team hues, and the log has to agree with them or "who did that" reads wrong.
  const seatColorFor = (entryPlayerId: EntityId | null): string | null => {
    if (!isMulti || !players || !entryPlayerId || entryPlayerId === playerId) return null
    const idx = players.findIndex((p) => p.playerId === entryPlayerId)
    return idx >= 0 ? identitySeatColor(teamMap, entryPlayerId, idx).base : null
  }
  const [expanded, setExpanded] = useState(false)
  const scrollRef = useRef<HTMLDivElement>(null)

  // Auto-scroll to bottom when new entries arrive
  useEffect(() => {
    if (expanded && scrollRef.current) {
      scrollRef.current.scrollTop = scrollRef.current.scrollHeight
    }
  }, [eventLog.length, expanded])

  if (!expanded) {
    return (
      <button
        data-learn="log"
        onClick={() => {
          setExpanded(true)
          markLearnSignal('logOpened')
        }}
        style={styles.toggleButton}
      >
        {t('logs.toggle', { count: eventLog.length })}
      </button>
    )
  }

  return (
    <div style={{ ...styles.panel, ...(isOtaria ? { bottom: 48, maxHeight: 'min(640px, calc(100dvh - 80px))' } : {}) }}>
      <div style={styles.header}>
        <span style={styles.headerTitle}>{t('logs.title')}</span>
        <button onClick={() => setExpanded(false)} style={styles.closeButton}>
          &times;
        </button>
      </div>
      <div ref={scrollRef} style={{ ...styles.entries, ...(isOtaria ? { maxHeight: 'min(600px, calc(100dvh - 120px))' } : {}) }}>
        {eventLog.length === 0 && (
          <div style={styles.empty}>{t('logs.empty')}</div>
        )}
        {eventLog.map((entry, i) => (
          <LogEntryRow
            key={i}
            entry={entry}
            isPlayer={entry.playerId === playerId}
            seatColor={seatColorFor(entry.playerId)}
            playerName={players?.find((player) => player.playerId === entry.playerId)?.name}
            resolvePlayerName={(id) => players?.find((player) => player.playerId === id)?.name}
          />
        ))}
      </div>
    </div>
  )
}

export function LogEntryRow({
  entry,
  isPlayer,
  seatColor,
  playerName,
  resolvePlayerName,
}: {
  entry: LogEntry
  isPlayer: boolean
  seatColor: string | null
  playerName?: string | undefined
  resolvePlayerName?: (id: EntityId) => string | undefined
}) {
  const { t } = useTranslation()
  if (entry.type === 'turn') {
    return (
      <div style={styles.turnSeparator}>
        {formatLogEntry(entry, t, playerName, resolvePlayerName)}
      </div>
    )
  }

  // A system line *about* a seat ("Bob has been eliminated") keeps that seat's colour in a pod —
  // it is the one log line where "who" matters most, and it was the only one that couldn't have it.
  const color = entry.type === 'system'
    ? seatColor ?? '#999'
    : entry.playerId === null
      ? '#888'
      : isPlayer
        ? '#5bc0de'
        : seatColor ?? '#e07050'

  return (
    <div style={{ ...styles.entry, color }}>
      {formatLogEntry(entry, t, playerName, resolvePlayerName)}
      {entry.cardName && entry.imageUri && (
        <details style={styles.eventCard}>
          <summary style={styles.eventSummary}>{t('logs.viewCard', { cardName: entry.cardName })}</summary>
          <img src={entry.imageUri} alt={entry.cardName} style={styles.eventImage} />
        </details>
      )}
    </div>
  )
}

function formatLogEntry(
  entry: LogEntry,
  t: TFunction,
  playerName?: string,
  resolvePlayerName: (id: EntityId) => string | undefined = () => undefined,
): string {
  const event = entry.event
  if (!event) return entry.description
  const actor = playerName ?? t('gameLog.aPlayer')
  const playerNameFor = resolvePlayerName ?? (() => undefined)
  switch (event.type) {
    case 'lifeChanged':
      if (event.change >= 0) {
        return t('gameLog.lifeGained', { playerName: actor, amount: event.change })
      }
      return t('gameLog.lifeLost', { playerName: actor, amount: Math.abs(event.change) })
    case 'cardDrawn':
      return event.cardName
        ? t('gameLog.cardDrawnNamed', { playerName: actor, cardName: event.cardName })
        : t('gameLog.cardDrawn', { playerName: actor })
    case 'cardDiscarded':
      return t('gameLog.cardDiscarded', { playerName: actor, cardName: event.cardName })
    case 'permanentEntered':
      return t('gameLog.permanentEntered', { playerName: actor, cardName: event.cardName })
    case 'permanentLeft':
      return t('gameLog.permanentLeft', { cardName: event.cardName, destination: t(`gameLog.zones.${event.destination}`) })
    case 'creatureAttacked':
      return t('gameLog.creatureAttacked', {
        cardName: event.creatureName,
        playerName: playerNameFor(event.defendingPlayerId) ?? t('gameLog.aPlayer'),
      })
    case 'creatureBlocked':
      return t('gameLog.creatureBlocked', { blockerName: event.blockerName, attackerName: event.attackerName })
    case 'creatureDied':
      return t('gameLog.creatureDied', { cardName: event.creatureName })
    case 'spellCast':
      return t('gameLog.spellCast', { playerName: actor, cardName: event.spellName })
    case 'spellResolved':
      return t('gameLog.spellResolved', { cardName: event.spellName })
    case 'spellCountered':
      return t('gameLog.spellCountered', { cardName: event.spellName })
    case 'permanentTapped':
      return t('gameLog.permanentTapped', { cardName: event.permanentName })
    case 'permanentUntapped':
      return t('gameLog.permanentUntapped', { cardName: event.permanentName })
    case 'counterAdded':
      return t('gameLog.counterAdded', { count: event.count, counterType: event.counterType, cardName: event.permanentName })
    case 'counterRemoved':
      return t('gameLog.counterRemoved', { count: event.count, counterType: event.counterType, cardName: event.permanentName })
    case 'manaAdded':
      return t('gameLog.manaAdded', { playerName: actor, mana: event.manaString })
    case 'turnChanged':
      return t('gameLog.turnChanged', { playerName: playerNameFor(event.activePlayerId) ?? t('gameLog.aPlayer'), turn: event.turnNumber })
    case 'gameRestarted':
      return t('gameLog.gameRestarted', { playerName: playerNameFor(event.startingPlayerId) ?? t('gameLog.aPlayer') })
    case 'gameEnded':
      return event.winnerId
        ? t('gameLog.gameWon', { playerName: playerNameFor(event.winnerId) ?? t('gameLog.aPlayer') })
        : t('gameLog.gameDrawn')
    case 'cardCycled':
      return t('gameLog.cardCycled', { playerName: actor, cardName: event.cardName })
    case 'libraryShuffled':
      return t('gameLog.libraryShuffled', { playerName: actor })
    default:
      return entry.description
  }
}

const styles: Record<string, React.CSSProperties> = {
  toggleButton: {
    position: 'fixed',
    bottom: 12,
    left: 12,
    zIndex: 500,
    padding: '6px 12px',
    fontSize: 12,
    backgroundColor: 'var(--chrome-bg)',
    backdropFilter: 'var(--chrome-blur)',
    WebkitBackdropFilter: 'var(--chrome-blur)',
    color: 'var(--chrome-text)',
    border: '1px solid var(--chrome-border)',
    borderRadius: 'var(--chrome-radius)',
    cursor: 'pointer',
  },
  panel: {
    position: 'fixed',
    bottom: 12,
    left: 12,
    zIndex: 500,
    width: 'min(320px, calc(100vw - 24px))',
    maxHeight: 300,
    display: 'flex',
    flexDirection: 'column',
    backgroundColor: 'var(--chrome-panel-bg)',
    border: '1px solid var(--chrome-panel-border)',
    borderRadius: 12,
    backdropFilter: 'var(--chrome-blur)',
    WebkitBackdropFilter: 'var(--chrome-blur)',
    boxShadow: '0 12px 36px rgba(0, 0, 0, 0.45)',
    overflow: 'hidden',
  },
  header: {
    display: 'flex',
    justifyContent: 'space-between',
    alignItems: 'center',
    padding: '6px 10px',
    borderBottom: '1px solid rgba(255, 255, 255, 0.08)',
  },
  headerTitle: {
    color: '#aaa',
    fontSize: 12,
    fontWeight: 600,
    textTransform: 'uppercase',
    letterSpacing: 1,
  },
  closeButton: {
    background: 'none',
    border: 'none',
    color: '#888',
    fontSize: 18,
    cursor: 'pointer',
    padding: '0 4px',
    lineHeight: 1,
  },
  entries: {
    flex: 1,
    overflowY: 'auto',
    padding: '4px 8px',
    maxHeight: 260,
  },
  empty: {
    color: '#555',
    fontSize: 12,
    padding: 8,
    textAlign: 'center',
  },
  entry: {
    fontSize: 12,
    padding: '2px 0',
    lineHeight: 1.4,
    borderBottom: '1px solid rgba(255,255,255,0.04)',
  },
  eventCard: {
    margin: '4px 0',
  },
  eventSummary: {
    cursor: 'pointer',
    color: 'var(--chrome-text)',
  },
  eventImage: {
    display: 'block',
    width: '100%',
    height: 'auto',
    marginTop: 6,
    borderRadius: 6,
  },
  turnSeparator: {
    fontSize: 11,
    padding: '6px 0 4px',
    lineHeight: 1.4,
    textAlign: 'center',
    color: 'rgba(255,255,255,0.5)',
    fontWeight: 600,
    borderBottom: '1px solid rgba(255,255,255,0.1)',
    borderTop: '1px solid rgba(255,255,255,0.1)',
    backgroundColor: 'rgba(255,255,255,0.04)',
    marginTop: 2,
    marginBottom: 2,
  },
}
