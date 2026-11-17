import { useState, useEffect, useRef } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { useGameStore } from '@/store/gameStore.ts'
import { useConnectName } from '@/store/useConnectName'
import { EntryCard } from '@/components/lobby/EntryCard'
import { useTranslation } from 'react-i18next'

/**
 * Entry point for the /tournament/:lobbyId route.
 *
 * Handles two cases:
 * 1. No name yet: show name entry, connect, then auto-join lobby
 * 2. A name already known (stored, or a signed-in account's display name): connect and auto-join
 *    without asking
 *
 * Once lobby state is received, navigates to "/" where the normal lobby/tournament UI takes over.
 */
export function TournamentEntryPage() {
  const { t } = useTranslation()
  const { lobbyId } = useParams<{ lobbyId: string }>()
  const navigate = useNavigate()

  const connectionStatus = useGameStore((state) => state.connectionStatus)
  const connect = useGameStore((state) => state.connect)
  const joinLobby = useGameStore((state) => state.joinLobby)
  const lobbyState = useGameStore((state) => state.lobbyState)
  const tournamentState = useGameStore((state) => state.tournamentState)
  const lastError = useGameStore((state) => state.lastError)
  const setPendingTournamentId = useGameStore((state) => state.setPendingTournamentId)
  const sessionReplaced = useGameStore((state) => state.sessionReplaced)

  const { name: connectName, resolving: nameResolving } = useConnectName()
  const [playerName, setPlayerName] = useState(() => localStorage.getItem('argentum-player-name') || '')
  const [joining, setJoining] = useState(false)
  const [tournamentInfo, setTournamentInfo] = useState<{ exists: boolean; state: string; playerCount: number; format: string } | null>(null)
  const [fetchError, setFetchError] = useState<'notFound' | 'notActive' | null>(null)
  const hasJoinedRef = useRef(false)
  const hasConnectedRef = useRef(false)

  // Fetch tournament info from REST endpoint for display
  useEffect(() => {
    if (!lobbyId) return
    fetch(`/api/tournaments/${lobbyId}/status`)
      .then((res) => {
        if (!res.ok) throw new Error('notFound')
        return res.json()
      })
      .then((data) => setTournamentInfo(data))
      .catch(() => setFetchError('notActive'))
  }, [lobbyId])

  // Auto-connect anyone who already has a name — stored, or a signed-in account's display name.
  // Never while another tab/device owns the session — reconnecting would steal it back.
  useEffect(() => {
    if (sessionReplaced) return
    if (connectName && connectionStatus === 'disconnected' && !hasConnectedRef.current) {
      hasConnectedRef.current = true
      connect(connectName)
    }
  }, [connectionStatus, connect, connectName, sessionReplaced])

  // Already seated in this tournament: this route was reached by going Back from another page (a
  // replay), because App stamps `/tournament/<id>` into the history entry it sits on. The store
  // already holds the live tournament, so there is nothing to join. Re-sending JoinLobby makes the
  // server replay the whole rejoin snapshot, which resets the standings screen mid-round.
  const alreadyHere =
    lobbyId != null && (tournamentState?.lobbyId === lobbyId || lobbyState?.lobbyId === lobbyId)

  // Auto-join lobby once connected
  useEffect(() => {
    if (alreadyHere) return
    if (connectionStatus === 'connected' && lobbyId && !hasJoinedRef.current) {
      hasJoinedRef.current = true
      setJoining(true)
      joinLobby(lobbyId)
    }
  }, [alreadyHere, connectionStatus, lobbyId, joinLobby])

  // Navigate to "/" once lobby or tournament state is received
  useEffect(() => {
    if (lobbyState || tournamentState) {
      navigate('/', { replace: true })
    }
  }, [lobbyState, tournamentState, navigate])

  const handleConnect = () => {
    if (playerName.trim() && lobbyId) {
      localStorage.setItem('argentum-player-name', playerName.trim())
      setPendingTournamentId(lobbyId)
      connect(playerName.trim())
    }
  }

  // Show error if lobby not found via REST or WebSocket error
  const errorMessage = fetchError
    ? t(fetchError === 'notFound' ? 'tournamentEntry.notFound' : 'tournamentEntry.notActive')
    : lastError?.message?.toLowerCase().includes('lobby') || lastError?.message?.toLowerCase().includes('not found')
      ? lastError.message
      : null

  // Only a visitor with no name at all is asked for one; everyone else is mid-connect above.
  const showNameEntry = !errorMessage && connectionStatus === 'disconnected' && !connectName && !nameResolving

  return (
    <EntryCard
      kicker={t('tournamentEntry.kicker')}
      title={t('tournamentEntry.title')}
      code={lobbyId}
      facts={tournamentInfo ? [
        { label: t('tournamentEntry.format'), value: tournamentInfo.format },
        { label: t('tournamentEntry.status'), value: tournamentInfo.state.replace(/_/g, ' ').toLowerCase() },
        { label: t('tournamentEntry.players'), value: tournamentInfo.playerCount },
      ] : undefined}
      error={errorMessage}
      nameEntry={showNameEntry ? { value: playerName, onChange: setPlayerName, onSubmit: handleConnect, submitLabel: t('tournamentEntry.join') } : undefined}
      progressText={joining ? t('tournamentEntry.joining') : t('tournamentEntry.connecting')}
    />
  )
}
