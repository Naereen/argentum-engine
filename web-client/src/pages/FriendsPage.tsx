/**
 * Friends page: your shareable friend code, an add-by-code box, incoming/outgoing requests, and your
 * friends list with live online status — plus the "hide my online status" toggle. Presence updates
 * arrive live over the WebSocket (see friendsStore); a slow poll covers the passive side of
 * accept/unfriend. Prompts sign-in when anonymous, and reports gracefully when accounts are disabled.
 */
import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router-dom'
import { setPresenceHidden } from '@/api/friends'
import { type BlockedPlayer, fetchBlocked, unblock } from '@/api/blocks'
import { LoginModal } from '@/components/auth/LoginModal'
import { AccountPage, Avatar, MessageCard, accountStyles as a } from '@/components/profile/accountUi'
import { pageStyles as p } from '@/components/ui/PageShell'
import { useAuthStore } from '@/store/authStore'
import { useFriendsStore } from '@/store/friendsStore'

const POLL_INTERVAL_MS = 25_000

export function FriendsPage() {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const user = useAuthStore((s) => s.user)
  const status = useAuthStore((s) => s.status)
  const accountsEnabled = useAuthStore((s) => s.accountsEnabled)
  const init = useAuthStore((s) => s.init)
  const patchUser = useAuthStore((s) => s.patchUser)

  const friends = useFriendsStore((s) => s.friends)
  const onlineFriendCount = friends.filter((f) => f.online).length
  const incoming = useFriendsStore((s) => s.incoming)
  const outgoing = useFriendsStore((s) => s.outgoing)
  const loading = useFriendsStore((s) => s.loading)
  const storeError = useFriendsStore((s) => s.error)
  const load = useFriendsStore((s) => s.load)
  const sendRequest = useFriendsStore((s) => s.sendRequest)
  const accept = useFriendsStore((s) => s.accept)
  const removeRequest = useFriendsStore((s) => s.removeRequest)
  const unfriend = useFriendsStore((s) => s.unfriend)

  const [loginOpen, setLoginOpen] = useState(false)
  const [codeInput, setCodeInput] = useState('')
  const [addError, setAddError] = useState<string | null>(null)
  const [addNotice, setAddNotice] = useState(false)
  const [sending, setSending] = useState(false)
  const [copied, setCopied] = useState(false)
  const [hideBusy, setHideBusy] = useState(false)

  useEffect(() => {
    if (status === 'idle') void init()
  }, [status, init])

  // Initial load + slow poll while the page is open (presence also arrives live via WS push).
  useEffect(() => {
    if (status !== 'authenticated') return
    void load()
    const timer = window.setInterval(() => void load(), POLL_INTERVAL_MS)
    return () => window.clearInterval(timer)
  }, [status, load])

  const submitCode = async () => {
    const code = codeInput.trim()
    if (!code) return
    setSending(true)
    setAddError(null)
    setAddNotice(false)
    try {
      await sendRequest(code)
      setCodeInput('')
      setAddNotice(true)
    } catch (e) {
      setAddError(e instanceof Error ? e.message : 'Could not send the request.')
    } finally {
      setSending(false)
    }
  }

  const copyCode = async () => {
    if (!user) return
    try {
      await navigator.clipboard.writeText(user.id)
      setCopied(true)
      window.setTimeout(() => setCopied(false), 1500)
    } catch {
      /* clipboard blocked — the code is shown for manual copy */
    }
  }

  const toggleHidden = async () => {
    if (!user) return
    const next = !user.hidePresence
    setHideBusy(true)
    try {
      await setPresenceHidden(next)
      patchUser({ hidePresence: next })
    } catch {
      /* leave the toggle as-is on failure */
    } finally {
      setHideBusy(false)
    }
  }

  if (status === 'authenticated' && user) {
    return (
      <AccountPage title={t('friends.title')}>
        <div>
          <h1 className={p.h1}>{t('friends.title')}</h1>
          <p className={p.lede}>
            {friends.length === 0
              ? t('friends.emptyLede')
              : `${t(friends.length === 1 ? 'friends.friendCountOne' : 'friends.friendCountMany', { count: friends.length })}${onlineFriendCount > 0 ? ` · ${t('friends.onlineNowCount', { count: onlineFriendCount })}` : ''}`}
          </p>
        </div>

        <div className={a.friendsGrid}>
          {/* Your friend code + presence visibility */}
          <section className={`${p.panel} ${a.stack}`}>
            <h2 className={p.panelTitle}>{t('friends.friendCode')}</h2>
            <p className={a.muted}>{t('friends.shareCodeHint')}</p>
            <div className={a.codeRow}>
              <code className={a.code}>{user.id}</code>
              <button type="button" className={p.button} onClick={() => void copyCode()}>
                {copied ? t('friends.copied') : t('friends.copy')}
              </button>
            </div>
            <label className={a.toggle}>
              <input type="checkbox" checked={user.hidePresence} disabled={hideBusy} onChange={() => void toggleHidden()} />
              <span>
                {t('friends.hideOnlineStatus')}
                <span className={a.dim}>{t('friends.offlineHint')}</span>
              </span>
            </label>
          </section>

          {/* Add a friend */}
          <section className={`${p.panel} ${a.stack}`}>
            <h2 className={p.panelTitle}>{t('friends.addFriend')}</h2>
            <p className={a.muted}>{t('friends.pasteCodeHint')}</p>
            <div className={a.addRow}>
              <input
                className={p.input}
                placeholder={t('friends.friendCodePlaceholder')}
                aria-label={t('friends.friendCodePlaceholder')}
                value={codeInput}
                onChange={(e) => {
                  setCodeInput(e.target.value)
                  setAddError(null)
                  setAddNotice(false)
                }}
                onKeyDown={(e) => {
                  if (e.key === 'Enter') void submitCode()
                }}
              />
              <button
                type="button"
                className={p.buttonPrimary}
                disabled={sending || !codeInput.trim()}
                onClick={() => void submitCode()}
              >
                {sending ? t('friends.sending') : t('friends.sendRequest')}
              </button>
            </div>
            {addError && <p className={a.error}>{addError}</p>}
            {addNotice && <p className={a.notice}>{t('friends.requestSent')}</p>}
          </section>

          {/* Incoming requests */}
          {incoming.length > 0 && (
            <section className={`${p.panel} ${a.full}`}>
              <div className={a.sectionHead}>
                <h2 className={a.sectionTitle}>{t('friends.requests')}</h2>
                <span className={a.count}>{incoming.length}</span>
              </div>
              {incoming.map((r) => (
                <div key={r.requestId} className={a.personRow}>
                  <span className={a.person}>
                    <Avatar name={r.displayName} avatar={r.avatar} small />
                    <span className={a.personName}>{r.displayName}</span>
                  </span>
                  <span className={a.rowActions}>
                    <button type="button" className={p.buttonPrimary} onClick={() => void accept(r.requestId)}>
                      {t('friends.accept')}
                    </button>
                    <button type="button" className={p.buttonGhost} onClick={() => void removeRequest(r.requestId)}>
                      {t('friends.decline')}
                    </button>
                  </span>
                </div>
              ))}
            </section>
          )}

          {/* Friends list */}
          <section className={`${p.panel} ${a.full}`}>
            <div className={a.sectionHead}>
              <h2 className={a.sectionTitle}>{t('friends.yourFriends')}</h2>
              {friends.length > 0 && (
                <span className={a.count}>
                  {friends.length}
                  {onlineFriendCount > 0 && <span className={a.win}> · {t('friends.onlineCount', { count: onlineFriendCount })}</span>}
                </span>
              )}
            </div>
            {storeError && <p className={a.error}>{storeError}</p>}
            {friends.length === 0 ? (
              <p className={a.muted}>{loading ? t('friends.loading') : t('friends.emptyFriends')}</p>
            ) : (
              friends.map((f) => (
                <div key={f.accountId} className={a.personRow}>
                  <span className={a.person}>
                    <Avatar name={f.displayName} avatar={f.avatar} small online={f.online} />
                    <span className={a.cellStack}>
                      <span className={a.personName}>{f.displayName}</span>
                      <span className={a.personStatus} data-online={f.online}>{f.online ? t('friends.online') : t('friends.offline')}</span>
                    </span>
                  </span>
                  <span className={a.rowActions}>
                    <button type="button" className={a.miniButton} onClick={() => navigate(`/messages/${f.accountId}`)}>
                      {t('friends.message')}
                    </button>
                    <button type="button" className={a.miniButton} onClick={() => navigate(`/u/${f.accountId}`)}>
                      {t('friends.profile')}
                    </button>
                    <button type="button" className={a.miniButton} onClick={() => void unfriend(f.accountId)}>
                      {t('friends.unfriend')}
                    </button>
                  </span>
                </div>
              ))
            )}
          </section>

          {/* Outgoing requests */}
          {outgoing.length > 0 && (
            <section className={`${p.panel} ${a.full}`}>
              <div className={a.sectionHead}>
                <h2 className={a.sectionTitle}>{t('friends.sentRequests')}</h2>
                <span className={a.count}>{outgoing.length}</span>
              </div>
              {outgoing.map((r) => (
                <div key={r.requestId} className={a.personRow}>
                  <span className={a.person}>
                    <Avatar name={r.displayName} avatar={r.avatar} small />
                    <span className={a.cellStack}>
                      <span className={a.personName}>{r.displayName}</span>
                      <span className={a.pendingTag}>{t('friends.awaitingReply')}</span>
                    </span>
                  </span>
                  <button type="button" className={a.miniButton} onClick={() => void removeRequest(r.requestId)}>
                    {t('friends.cancel')}
                  </button>
                </div>
              ))}
            </section>
          )}

          <BlockedPlayersSection />
        </div>
      </AccountPage>
    )
  }

  const resolving = status === 'idle' || status === 'loading'

  return (
    <AccountPage title={t('friends.title')}>
      <MessageCard>
        <h1 className={p.h1}>{t('friends.title')}</h1>
        {accountsEnabled ? (
          <>
            <p className={p.lede}>{t('friends.signInToUse')}</p>
            <button type="button" className={`${p.buttonPrimary} ${a.fullButton}`} onClick={() => setLoginOpen(true)}>
              {t('friends.signIn')}
            </button>
            <LoginModal open={loginOpen} onClose={() => setLoginOpen(false)} />
          </>
        ) : resolving ? (
          <p className={a.muted}>{t('friends.loading')}</p>
        ) : (
          <p className={a.muted}>{t('friends.accountsUnavailable')}</p>
        )}
      </MessageCard>
    </AccountPage>
  )
}

/**
 * Players you blocked from a result screen. Absent until there is one — most people never block
 * anyone, and an empty "Blocked" heading would only suggest they should.
 */
function BlockedPlayersSection() {
  const { t } = useTranslation()
  const [blocked, setBlocked] = useState<BlockedPlayer[]>([])
  useEffect(() => {
    let cancelled = false
    fetchBlocked().then((list) => { if (!cancelled) setBlocked(list) }).catch(() => { /* not essential */ })
    return () => { cancelled = true }
  }, [])
  if (blocked.length === 0) return null

  const doUnblock = (accountId: string) => {
    setBlocked((list) => list.filter((b) => b.accountId !== accountId))
    void unblock(accountId).catch(() => fetchBlocked().then(setBlocked).catch(() => {}))
  }

  return (
    <section className={`${p.panel} ${a.full}`}>
      <div className={a.sectionHead}>
        <h2 className={a.sectionTitle}>{t('friends.blocked')}</h2>
        <span className={a.count}>{blocked.length}</span>
      </div>
      <p className={a.muted}>{t('friends.blockedDescription')}</p>
      {blocked.map((b) => (
        <div key={b.accountId} className={a.personRow}>
          <span className={a.person}>
            <Avatar name={b.displayName} avatar={b.avatar} small />
            <span className={a.personName}>{b.displayName}</span>
          </span>
          <button type="button" className={a.miniButton} onClick={() => doUnblock(b.accountId)}>
            {t('friends.unblock')}
          </button>
        </div>
      ))}
    </section>
  )
}
