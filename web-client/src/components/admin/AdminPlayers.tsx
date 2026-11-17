/**
 * Admin Players: the roster of registered accounts and a per-account drill-down (lifetime record,
 * colors, modes, head-to-head, recent games, tournaments) — plus the control to grant/revoke admin
 * access. This is the page from which the first admin is bootstrapped: sign in once with the admin
 * password, open a player here, and promote their account; from then on they reach the dashboard with
 * their normal sign-in. Auth is the dashboard's shared {@link AdminAuth}.
 */
import { useEffect, useState } from 'react'
import type React from 'react'
import { useTranslation } from 'react-i18next'
import {
  type AdminGamesPage,
  type AdminUserDetail,
  type AdminUserSummary,
  fetchUserDetail,
  fetchUserGames,
  fetchUsers,
  setUserAdmin,
} from '@/api/adminUsers'
import type { AdminAuth } from '@/api/adminAuth'
import { TournamentDetailModal } from '@/components/profile/TournamentDetailModal'
import { Avatar, PlayedRecord } from '@/components/profile/accountUi'
import { describeFilter, isAllGames } from '@/api/statsFilter'
import { NotCountedNote, OpponentSplit, StatsFilterBar, useStatsFilterParam } from '@/components/stats/StatsFilterBar'
import { colorForIdentity, colorLabel, mergeModeBuckets } from './statFormat'
import { AdminScreen, Panel, StatCard, Table, adminTheme, cellStyle } from './adminUi'
import { AdminDeckModal, AdminSavedDecks } from './AdminPlayerDecks'
import { useAdminTournamentParam } from './adminNav'

const GAMES_PAGE_SIZE = 10

export function AdminPlayers({
  auth,
  selectedId,
  onSelect,
  onCloseDetail,
  onBack,
}: {
  auth: AdminAuth
  /** The player whose detail is open (`/admin/players/<id>`), or null for the roster. */
  selectedId: string | null
  onSelect: (id: string) => void
  onCloseDetail: () => void
  onBack: () => void
}) {
  const { t } = useTranslation()
  const [users, setUsers] = useState<AdminUserSummary[]>([])
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const [filter, setFilter] = useStatsFilterParam()

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    fetchUsers(auth, filter)
      .then((u) => !cancelled && setUsers(u))
      .catch((e) => !cancelled && setError(e instanceof Error ? e.message : t('adminPlayersUi.loadPlayersFailed')))
      .finally(() => !cancelled && setLoading(false))
    return () => {
      cancelled = true
    }
  }, [auth, filter, t])

  /** Reflect an admin-flag change from the detail view back into the roster row. */
  const applyAdminChange = (id: string, isAdmin: boolean) =>
    setUsers((prev) => prev.map((u) => (u.id === id ? { ...u, isAdmin } : u)))

  if (selectedId != null) {
    return (
      <PlayerDetail
        key={selectedId}
        auth={auth}
        id={selectedId}
        onBack={onCloseDetail}
        onAdminChange={applyAdminChange}
      />
    )
  }

  return (
    <AdminScreen
      title={t('adminUi.playersTitle')}
      subtitle={t(users.length === 1 ? 'adminPlayersUi.registeredOne' : 'adminPlayersUi.registeredMany', { count: users.length })}
      onBack={onBack}
      backLabel={t('adminStats.back')}
    >
      {error && <p style={styles.error}>{error}</p>}
      <Panel
        title={isAllGames(filter) ? t('adminPlayersUi.everyGame') : t('adminPlayersUi.gamesFilter', { filter: describeFilter(filter) })}
        subtitle={t('adminPlayersUi.filterSubtitle')}
        action={<StatsFilterBar filter={filter} onChange={setFilter} />}
      >
        {loading && users.length === 0 ? (
          <p style={cellStyle.muted}>{t('adminPlayersUi.loadingPlayers')}</p>
        ) : users.length === 0 ? (
          <p style={cellStyle.muted}>{t('adminPlayersUi.noAccounts')}</p>
        ) : (
          <Table head={[t('adminPlayersUi.player'), t('adminPlayersUi.games'), t('adminPlayersUi.wins'), t('adminPlayersUi.winPercent'), t('adminPlayersUi.lastPlayed'), '']}>
            {users.map((u) => {
              const winRate = u.games > 0 ? Math.round((u.wins / u.games) * 100) : 0
              return (
                <tr key={u.id} style={styles.row} onClick={() => onSelect(u.id)}>
                  <td style={cellStyle.td}>
                    <div style={styles.playerCell}>
                      <Avatar name={u.displayName} avatar={u.avatar} small />
                      <div>
                        <div style={styles.playerNameRow}>
                          <span style={styles.playerName}>{u.displayName}</span>
                          {u.isAdmin && <span style={styles.adminBadge}>{t('adminPlayersUi.adminBadge')}</span>}
                        </div>
                        <div style={styles.playerEmail}>{u.email}</div>
                      </div>
                    </div>
                  </td>
                  <td style={cellStyle.tdNum}>{u.games}</td>
                  <td style={cellStyle.tdNum}>{u.wins}</td>
                  <td style={cellStyle.tdNum}>{u.games > 0 ? `${winRate}%` : '—'}</td>
                  <td style={cellStyle.tdNum}>{u.lastPlayed ? u.lastPlayed.slice(0, 10) : '—'}</td>
                  <td style={cellStyle.tdNum}>
                    <span style={styles.viewLink}>{t('adminPlayersUi.view')}</span>
                  </td>
                </tr>
              )
            })}
          </Table>
        )}
      </Panel>
    </AdminScreen>
  )
}

function PlayerDetail({
  auth,
  id,
  onBack,
  onAdminChange,
}: {
  auth: AdminAuth
  id: string
  onBack: () => void
  onAdminChange: (id: string, isAdmin: boolean) => void
}) {
  const { t } = useTranslation()
  const [detail, setDetail] = useState<AdminUserDetail | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [updating, setUpdating] = useState(false)
  const [games, setGames] = useState<AdminGamesPage>({ entries: [], total: 0 })
  const [gamesPage, setGamesPage] = useState(0)
  const [deckModal, setDeckModal] = useState<{ gameId: string; opponent: string } | null>(null)
  const [openTournament, setOpenTournament, closeTournament] = useAdminTournamentParam()
  const [filter, setFilterParam] = useStatsFilterParam()
  const setFilter = (next: typeof filter) => {
    setFilterParam(next)
    setGamesPage(0)
  }

  useEffect(() => {
    let cancelled = false
    fetchUserDetail(auth, id, filter)
      .then((d) => !cancelled && setDetail(d))
      .catch((e) => !cancelled && setError(e instanceof Error ? e.message : t('adminPlayersUi.loadPlayerFailed')))
    return () => {
      cancelled = true
    }
  }, [auth, id, filter, t])

  useEffect(() => {
    let cancelled = false
    fetchUserGames(auth, id, GAMES_PAGE_SIZE, gamesPage * GAMES_PAGE_SIZE, filter)
      .then((p) => !cancelled && setGames(p))
      .catch(() => !cancelled && setGames({ entries: [], total: 0 }))
    return () => {
      cancelled = true
    }
  }, [auth, id, gamesPage, filter])

  /** Open a finished game's replay in a new tab so the admin keeps their place on this screen. */
  const watchReplay = (gameId: string) => window.open(`/replay/${gameId}`, '_blank', 'noopener')

  const toggleAdmin = async () => {
    if (!detail) return
    const next = !detail.isAdmin
    setUpdating(true)
    setError(null)
    try {
      const isAdmin = await setUserAdmin(auth, id, next)
      setDetail({ ...detail, isAdmin })
      onAdminChange(id, isAdmin)
    } catch (e) {
      setError(e instanceof Error ? e.message : t('adminPlayersUi.updateAdminFailed'))
    } finally {
      setUpdating(false)
    }
  }

  const adminButton = detail && (
    <button
      type="button"
      style={detail.isAdmin ? styles.demoteBtn : styles.promoteBtn}
      disabled={updating}
      onClick={() => void toggleAdmin()}
    >
      {updating ? t('adminPlayersUi.saving') : detail.isAdmin ? t('adminPlayersUi.revokeAdmin') : t('adminPlayersUi.makeAdmin')}
    </button>
  )

  return (
    <AdminScreen
      title={detail?.displayName ?? t('adminPlayersUi.playerDefault')}
      subtitle={detail?.email}
      onBack={onBack}
      backLabel={t('adminPlayersUi.backToPlayers')}
      right={adminButton}
    >
      {error && <p style={styles.error}>{error}</p>}
      {!detail ? (
        <p style={cellStyle.muted}>{t('adminPlayersUi.loading')}</p>
      ) : (
        <>
          <div style={styles.identity}>
            <Avatar name={detail.displayName} avatar={detail.avatar} />
            <div style={styles.meta}>{t('adminPlayersUi.joined', { date: detail.createdAt.slice(0, 10) })}</div>
          </div>

          {detail.isAdmin && (
            <div style={styles.adminNotice}>
              <span style={styles.adminBadge}>{t('adminPlayersUi.adminBadge')}</span>
              <span style={cellStyle.muted}>{t('adminPlayersUi.adminAccessNotice')}</span>
            </div>
          )}

          <div style={styles.filterRow}>
            <StatsFilterBar filter={filter} onChange={setFilter} />
          </div>

          <div style={styles.cardRow}>
            <StatCard label={t('adminPlayersUi.games')} value={detail.stats.games} />
            <StatCard label={t('adminPlayersUi.wins')} value={detail.stats.wins} />
            <StatCard label={t('adminPlayersUi.losses')} value={detail.stats.losses} />
            <StatCard
              label={t('adminPlayersUi.winRate')}
              value={detail.stats.games > 0 ? `${Math.round(detail.stats.winRate * 100)}%` : '—'}
              accent
            />
          </div>
          <div style={styles.splitRow}>
            <OpponentSplit vsHumans={detail.stats.vsHumans} vsAi={detail.stats.vsAi} filter={filter} onChange={setFilter} />
            <NotCountedNote count={detail.stats.notCounted} />
          </div>

          {detail.colors.length > 0 && (
            <Panel title={t('adminPlayersUi.colorsPlayed')}>
              <ChipList items={detail.colors.map((c) => `${colorLabel(c.label)} · ${c.count}`)} />
            </Panel>
          )}

          {detail.modes.length > 0 && (
            <Panel title={t('adminPlayersUi.gameModes')}>
              <ChipList items={mergeModeBuckets(detail.modes).map((m) => `${m.label} · ${m.count}`)} />
            </Panel>
          )}

          {detail.opponents.length > 0 && (
            <Panel title={t('adminPlayersUi.headToHead')}>
              <Table head={[t('adminPlayersUi.opponent'), t('adminPlayersUi.winsShort'), t('adminPlayersUi.lossesShort')]}>
                {detail.opponents.map((o, i) => (
                  <tr key={`${o.opponent}-${i}`}>
                    <td style={cellStyle.td}>
                      {o.opponent}
                      {o.isAi && <span style={styles.aiTag}> {t('adminActivity.ai')}</span>}
                    </td>
                    <td style={cellStyle.tdNum}>{o.wins}</td>
                    <td style={cellStyle.tdNum}>{o.losses}</td>
                  </tr>
                ))}
              </Table>
            </Panel>
          )}

          {detail.topCards.length > 0 && (
            <Panel title={t('adminPlayersUi.mostPlayedCards')}>
              <ChipList items={detail.topCards.map((c) => `${c.cardName} · ${c.copies}`)} />
            </Panel>
          )}

          {detail.tournaments.length > 0 && (
            <Panel title={t('adminPlayersUi.tournaments')}>
              <Table head={[t('adminPlayersUi.date'), t('adminPlayersUi.tournaments'), t('adminPlayersUi.record'), t('adminPlayersUi.place')]}>
                {detail.tournaments.map((t) => (
                  <tr key={t.id} style={styles.row} onClick={() => setOpenTournament(t.id)}>
                    <td style={cellStyle.td}>{t.endedAt.slice(0, 10)}</td>
                    <td style={cellStyle.td}>{t.name ?? '—'}</td>
                    <td style={cellStyle.tdNum}>
                      <PlayedRecord
                        wins={t.wins}
                        losses={t.losses}
                        draws={t.draws}
                        forfeitWins={t.forfeitWins}
                        forfeitLosses={t.forfeitLosses}
                      />
                    </td>
                    <td style={cellStyle.tdNum}>
                      {t.placement}/{t.playerCount}
                    </td>
                  </tr>
                ))}
              </Table>
            </Panel>
          )}

          {(games.total > 0 || !isAllGames(filter)) && (
            <Panel title={t('adminPlayersUi.gamesCount', { count: games.total })}>
              {games.total === 0 && <p style={cellStyle.muted}>{t('adminPlayersUi.noGamesFilter', { filter: describeFilter(filter) })}</p>}
              <Table head={[t('adminPlayersUi.date'), t('adminPlayersUi.mode'), t('adminPlayersUi.colors'), t('adminPlayersUi.opponent'), t('adminPlayersUi.result'), t('adminPlayersUi.deck'), t('adminPlayersUi.replay')]}>
                {games.entries.map((g, i) => (
                  <tr key={`${g.gameId}-${i}`}>
                    <td style={cellStyle.td}>{g.endedAt.slice(0, 10)}</td>
                    <td style={cellStyle.td}>{prettyMode(g.gameMode)}</td>
                    <td style={cellStyle.td}>
                      {g.colors ? (
                        <span style={styles.colorsCell}>
                          <span style={{ ...styles.colorDot, backgroundColor: colorForIdentity(g.colors) }} />
                          {colorLabel(g.colors)}
                        </span>
                      ) : (
                        '—'
                      )}
                    </td>
                    <td style={cellStyle.td}>{g.opponents ?? '—'}</td>
                    <td
                      style={{ ...cellStyle.tdNum, color: g.notCounted ? adminTheme.textMuted : g.won ? adminTheme.good : adminTheme.bad }}
                      title={
                        g.notCounted === 'NO_ACTION'
                          ? t('adminPlayersUi.notCountedNoAction')
                          : g.notCounted
                            ? t('adminPlayersUi.notCountedNoOpponent')
                            : undefined
                      }
                    >
                      {g.won ? t('accountHistory.win') : t('accountHistory.loss')}
                      {g.notCounted && <span style={styles.notCounted}>{t('adminPlayersUi.notCounted')}</span>}
                    </td>
                    <td style={cellStyle.tdNum}>
                      <button
                        type="button"
                        style={styles.rowLink}
                        onClick={() => setDeckModal({ gameId: g.gameId, opponent: g.opponents ?? t('adminPlayersUi.opponent') })}
                      >
                        {t('adminPlayersUi.viewDeck')}
                      </button>
                    </td>
                    <td style={cellStyle.tdNum}>
                      {g.hasReplay ? (
                        <button type="button" style={styles.rowLink} onClick={() => watchReplay(g.gameId)}>
                          {t('adminPlayersUi.watch')}
                        </button>
                      ) : (
                        '—'
                      )}
                    </td>
                  </tr>
                ))}
              </Table>
              {games.total > GAMES_PAGE_SIZE && (
                <div style={styles.pager}>
                  <button
                    type="button"
                    style={{ ...styles.pagerBtn, ...(gamesPage === 0 ? styles.pagerBtnDisabled : {}) }}
                    disabled={gamesPage === 0}
                    onClick={() => setGamesPage((p) => Math.max(0, p - 1))}
                  >
                    {t('adminPlayersUi.newer')}
                  </button>
                  <span style={cellStyle.muted}>
                    {t('adminPlayersUi.page', { current: gamesPage + 1, total: Math.max(1, Math.ceil(games.total / GAMES_PAGE_SIZE)) })}
                  </span>
                  <button
                    type="button"
                    style={{
                      ...styles.pagerBtn,
                      ...((gamesPage + 1) * GAMES_PAGE_SIZE >= games.total ? styles.pagerBtnDisabled : {}),
                    }}
                    disabled={(gamesPage + 1) * GAMES_PAGE_SIZE >= games.total}
                    onClick={() => setGamesPage((p) => p + 1)}
                  >
                    {t('adminPlayersUi.older')}
                  </button>
                </div>
              )}
            </Panel>
          )}

          <AdminSavedDecks auth={auth} userId={id} />
        </>
      )}

      {deckModal && (
        <AdminDeckModal
          auth={auth}
          userId={id}
          gameId={deckModal.gameId}
          opponentLabel={deckModal.opponent}
          onClose={() => setDeckModal(null)}
        />
      )}

      {openTournament != null && (
        <TournamentDetailModal tournamentId={openTournament} onClose={closeTournament} />
      )}
    </AdminScreen>
  )
}

function ChipList({ items }: { items: string[] }) {
  return (
    <div style={styles.chipRow}>
      {items.map((it) => (
        <span key={it} style={styles.chip}>
          {it}
        </span>
      ))}
    </div>
  )
}

/** Turn a raw game-mode token (TOURNAMENT / QUICK_GAME / ...) into a readable label. */
function prettyMode(mode: string | null): string {
  if (!mode) return '—'
  return mode
    .toLowerCase()
    .split('_')
    .map((w) => w.charAt(0).toUpperCase() + w.slice(1))
    .join(' ')
}

const styles: Record<string, React.CSSProperties> = {
  error: { color: adminTheme.bad, fontSize: 13, margin: 0 },
  filterRow: { marginBottom: 14 },
  splitRow: { display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: '8px 16px', margin: '-4px 0 18px' },
  notCounted: { display: 'block', fontSize: 11, color: adminTheme.textMuted, fontWeight: 400 },
  row: { cursor: 'pointer' },
  playerCell: { display: 'flex', alignItems: 'center', gap: 12 },
  playerNameRow: { display: 'flex', alignItems: 'center', gap: 8 },
  playerName: { color: adminTheme.text, fontWeight: 600 },
  playerEmail: { color: adminTheme.textMuted, fontSize: 12, marginTop: 2 },
  adminBadge: {
    fontSize: 10,
    fontWeight: 700,
    letterSpacing: 0.6,
    color: adminTheme.accent,
    backgroundColor: 'rgba(139,155,255,0.12)',
    border: `1px solid ${adminTheme.accent}55`,
    borderRadius: 999,
    padding: '2px 7px',
  },
  viewLink: { color: adminTheme.accent, fontSize: 13, whiteSpace: 'nowrap' },
  cardRow: { display: 'flex', gap: 12, flexWrap: 'wrap' },
  meta: { color: adminTheme.textMuted, fontSize: 13 },
  identity: { display: 'flex', alignItems: 'center', gap: 16 },
  adminNotice: {
    display: 'flex',
    alignItems: 'center',
    gap: 10,
    backgroundColor: 'rgba(139,155,255,0.06)',
    border: `1px solid ${adminTheme.border}`,
    borderRadius: 12,
    padding: '10px 14px',
  },
  aiTag: { color: adminTheme.textMuted, fontSize: 11 },
  chipRow: { display: 'flex', flexWrap: 'wrap', gap: 8 },
  chip: {
    backgroundColor: adminTheme.panelAlt,
    border: `1px solid ${adminTheme.border}`,
    borderRadius: 999,
    padding: '4px 12px',
    color: adminTheme.textSecondary,
    fontSize: 13,
  },
  promoteBtn: {
    padding: '8px 14px',
    borderRadius: 9,
    border: 'none',
    backgroundColor: adminTheme.accentSolid,
    color: adminTheme.onAccent,
    fontWeight: 600,
    fontSize: 13,
    cursor: 'pointer',
  },
  demoteBtn: {
    padding: '8px 14px',
    borderRadius: 9,
    border: `1px solid ${adminTheme.bad}66`,
    backgroundColor: 'transparent',
    color: adminTheme.bad,
    fontWeight: 600,
    fontSize: 13,
    cursor: 'pointer',
  },
  colorsCell: { display: 'inline-flex', alignItems: 'center', gap: 6 },
  colorDot: { width: 9, height: 9, borderRadius: 999, display: 'inline-block' },
  rowLink: { background: 'none', border: 'none', color: adminTheme.accent, cursor: 'pointer', fontSize: 13, padding: 0 },
  pager: { display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 14, marginTop: 12 },
  pagerBtn: {
    background: 'none',
    border: `1px solid ${adminTheme.border}`,
    borderRadius: 8,
    color: adminTheme.textSecondary,
    cursor: 'pointer',
    fontSize: 13,
    padding: '6px 12px',
  },
  pagerBtnDisabled: { color: adminTheme.textMuted, cursor: 'default', borderColor: adminTheme.borderSoft },
}
