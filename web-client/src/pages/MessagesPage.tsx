/**
 * Direct messages, at /messages and /messages/:accountId.
 *
 * Two panes: your conversations (with a separate Requests tab for messages from players who aren't
 * your friends) and the open conversation. On a phone only one pane shows at a time — the list at
 * /messages, the conversation at /messages/:id with a back button. With nothing open, the second pane
 * is the friend picker that starts a conversation (also `/messages?new`, which the list's compose
 * button opens so a phone shows it too).
 *
 * A conversation from a non-friend arrives as a request: until you accept it there's no composer,
 * just Accept / Delete / Block. A conversation you start with a non-friend is a request on their
 * side, and the composer says how many messages you have left until they answer. New messages
 * arrive over the WebSocket (see messagesStore); a slow poll is the catch-all, and a quick one stands
 * in for the socket when this page was opened directly and no game connection exists.
 *
 * Everywhere else the same conversations open in the chat dock (`components/messages/ChatDock`),
 * which this page hides while it's showing.
 */
import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { LoginModal } from '@/components/auth/LoginModal'
import { BackButton, ComposeIcon, ConversationPane, NewMessagePicker, ThreadRow, messagesStyles as m } from '@/components/messages/conversation'
import { MessageCard, accountStyles as a } from '@/components/profile/accountUi'
import { PageShell, pageStyles as p } from '@/components/ui/PageShell'
import { useAuthStore } from '@/store/authStore'
import { useGameStore } from '@/store/gameStore'
import { useMessagesStore } from '@/store/messagesStore'

const POLL_INTERVAL_MS = 30_000
/** Without a socket there's no push, so poll quickly enough to feel like a conversation. */
const OFFLINE_POLL_INTERVAL_MS = 6_000

export function MessagesPage() {
  const { t } = useTranslation()
  const { accountId } = useParams<{ accountId: string }>()
  const [search] = useSearchParams()
  const navigate = useNavigate()
  const composing = !accountId && search.has('new')
  const user = useAuthStore((s) => s.user)
  const status = useAuthStore((s) => s.status)
  const accountsEnabled = useAuthStore((s) => s.accountsEnabled)
  const loadThreads = useMessagesStore((s) => s.loadThreads)
  const openConversation = useMessagesStore((s) => s.openConversation)
  const live = useGameStore((s) => s.connectionStatus === 'connected')
  const [loginOpen, setLoginOpen] = useState(false)

  useEffect(() => {
    if (status !== 'authenticated') return
    void loadThreads()
    const timer = window.setInterval(() => {
      if (document.visibilityState !== 'visible') return
      void loadThreads()
      if (!live && accountId) void openConversation(accountId)
    }, live ? POLL_INTERVAL_MS : OFFLINE_POLL_INTERVAL_MS)
    return () => window.clearInterval(timer)
  }, [status, live, accountId, loadThreads, openConversation])

  if (status === 'authenticated' && user) {
    const toList = () => navigate('/messages')
    return (
      <PageShell title={t('library.messagesUI.pageTitle')} width="wide" fit>
        <div className={m.layout} data-open={!!accountId || composing}>
          <ThreadList selectedId={accountId} composing={composing} myId={user.id} />
          {accountId && accountId !== user.id ? (
            <ConversationPane
              key={accountId}
              accountId={accountId}
              myId={user.id}
              className={p.panel}
              onBack={toList}
              onLeave={toList}
              menuItems={[{ label: t('library.messagesUI.viewProfile'), onSelect: () => navigate(`/u/${accountId}`) }]}
            />
          ) : (
            <NewMessagePane composing={composing} />
          )}
        </div>
      </PageShell>
    )
  }

  const resolving = status === 'idle' || status === 'loading'
  return (
    <PageShell title={t('library.messagesUI.pageTitle')}>
      <MessageCard>
      <h1 className={p.h1}>{t('library.messagesUI.pageTitle')}</h1>
        {accountsEnabled ? (
          <>
          <p className={p.lede}>{t('library.messagesUI.signInPrompt')}</p>
            <button type="button" className={`${p.buttonPrimary} ${a.fullButton}`} onClick={() => setLoginOpen(true)}>
            {t('library.messagesUI.signIn')}
            </button>
            <LoginModal open={loginOpen} onClose={() => setLoginOpen(false)} />
          </>
        ) : resolving ? (
          <p className={a.muted}>{t('library.messagesUI.loading')}</p>
        ) : (
          <p className={a.muted}>{t('library.messagesUI.accountsUnavailable')}</p>
        )}
      </MessageCard>
    </PageShell>
  )
}

function ThreadList({ selectedId, composing, myId }: { selectedId: string | undefined; composing: boolean; myId: string }) {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const threads = useMessagesStore((s) => s.threads)
  const loaded = useMessagesStore((s) => s.threadsLoaded)
  const chats = threads.filter((t) => t.state !== 'INCOMING_REQUEST')
  const requests = threads.filter((t) => t.state === 'INCOMING_REQUEST')
  const selectedTab = requests.some((t) => t.other.accountId === selectedId)
    ? 'requests'
    : chats.some((t) => t.other.accountId === selectedId) ? 'chats' : null
  const [tab, setTab] = useState<'chats' | 'requests'>(selectedTab ?? 'chats')

  // The open conversation's tab follows it — a request opened from a link, or one just accepted.
  useEffect(() => {
    if (selectedTab) setTab(selectedTab)
  }, [selectedTab])

  const shown = tab === 'chats' ? chats : requests
  const unreadRequests = requests.filter((t) => t.unread > 0).length

  return (
    <aside className={`${p.panel} ${m.listPane}`} aria-label={t('library.messagesUI.conversations')}>
      <div className={m.listHead}>
        <h1 className={m.listTitle}>{t('library.messagesUI.pageTitle')}</h1>
        <button
          type="button"
          className={m.iconButton}
          data-active={composing}
          onClick={() => navigate('/messages?new')}
          aria-label={t('library.messagesUI.newMessage')}
          title={t('library.messagesUI.newMessage')}
        >
          <ComposeIcon />
        </button>
      </div>
      <div className={m.tabs} role="tablist">
        <button type="button" role="tab" aria-selected={tab === 'chats'} className={m.tab} onClick={() => setTab('chats')}>
          {t('library.messagesUI.chats')}
        </button>
        <button type="button" role="tab" aria-selected={tab === 'requests'} className={m.tab} onClick={() => setTab('requests')}>
          {t('library.messagesUI.requests')}
          {requests.length > 0 && (
            <span className={m.tabCount} data-unread={unreadRequests > 0}>{requests.length}</span>
          )}
        </button>
      </div>
      <div className={m.threadScroll}>
        {!loaded ? (
          <div className={m.listEmpty}><span className={a.spinner} aria-hidden /></div>
        ) : shown.length === 0 ? (
          <div className={m.listEmpty}>
            {tab === 'chats' ? (
              <>
                <p className={m.emptyTitle}>{t('library.messagesUI.noConversations')}</p>
                <p className={a.muted}>
                  <button type="button" className={a.link} onClick={() => navigate('/messages?new')}>{t('library.messagesUI.messageFriend')}</button>
                  {' '}{t('library.messagesUI.orMessageAnyPlayer')}
                </p>
                {requests.length > 0 && (
                  <button type="button" className={a.link} onClick={() => setTab('requests')}>
                    {t('library.messagesUI.messageRequestCount', { count: requests.length })}
                  </button>
                )}
              </>
            ) : (
              <>
                <p className={m.emptyTitle}>{t('library.messagesUI.noRequests')}</p>
                <p className={a.muted}>{t('library.messagesUI.requestWaiting')}</p>
              </>
            )}
          </div>
        ) : (
          <ul className={m.threadList}>
            {shown.map((t) => (
              <li key={t.other.accountId}>
                <ThreadRow thread={t} myId={myId} selected={t.other.accountId === selectedId} onOpen={() => navigate(`/messages/${t.other.accountId}`)} />
              </li>
            ))}
          </ul>
        )}
      </div>
    </aside>
  )
}

/** Nothing open: start a conversation with any friend, including ones you've never written to. */
function NewMessagePane({ composing }: { composing: boolean }) {
  const { t } = useTranslation()
  const navigate = useNavigate()
  return (
    <section className={`${p.panel} ${m.convPane}`} aria-label={t('library.messagesUI.newMessage')}>
      <header className={m.convHead}>
        <BackButton onClick={() => navigate('/messages')} />
        <h2 className={m.paneTitle}>{t('library.messagesUI.newMessage')}</h2>
      </header>
      <NewMessagePicker key={String(composing)} autoFocus={composing} onPick={(id) => navigate(`/messages/${id}`)} />
    </section>
  )
}
