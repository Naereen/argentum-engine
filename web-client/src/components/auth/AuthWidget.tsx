/**
 * The account menu in the landing screen's top bar.
 *
 * One button — your initial and display name — that opens a menu of every account-scoped page:
 * Profile, Stats, Messages (with unread count), Friends (with who's online and pending requests), Admin
 * for admins, and Log out.
 * It used to be a row of five frosted pills beside the navigation, which crowded the top bar and
 * wrapped onto a second line on anything narrower than a desktop; a menu is one control at every
 * width, and the pages it opens are "things about *your* account", so they belong together rather
 * than in the main navigation.
 *
 * Friends online and pending friend requests show on the trigger itself — spelled out on a quiet line
 * under your name, or as count badges on the avatar on narrow screens — so they're visible without
 * opening the menu. Unread messages don't: the chat button beside this menu carries that count, and
 * repeating it here read as two different notifications. The Messages item in the menu still has it.
 *
 * Anonymous visitors see a single Log in button that opens the magic-link modal. Renders nothing
 * when the server has accounts disabled — a login form there could only fail.
 */
import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router-dom'
import { LoginModal } from '@/components/auth/LoginModal'
import { AvatarArt, hasAvatarArt } from '@/components/profile/AvatarArt'
import { initialOf } from '@/components/profile/avatars'
import { useAuthStore } from '@/store/authStore'
import { useFriendsStore } from '@/store/friendsStore'
import { unreadBadgeCount, useMessagesStore } from '@/store/messagesStore'
import styles from './AuthWidget.module.css'

export function AuthWidget() {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const accountsEnabled = useAuthStore((s) => s.accountsEnabled)
  const status = useAuthStore((s) => s.status)
  const user = useAuthStore((s) => s.user)
  const logout = useAuthStore((s) => s.logout)
  const incomingCount = useFriendsStore((s) => s.incoming.length)
  const onlineCount = useFriendsStore((s) => s.friends.filter((f) => f.online).length)
  const loadFriends = useFriendsStore((s) => s.load)
  const resetFriends = useFriendsStore((s) => s.reset)
  const unreadMessages = useMessagesStore((s) => unreadBadgeCount(s.threads))
  const loadThreads = useMessagesStore((s) => s.loadThreads)
  const resetMessages = useMessagesStore((s) => s.reset)
  const [loginOpen, setLoginOpen] = useState(false)
  const [menuOpen, setMenuOpen] = useState(false)
  const rootRef = useRef<HTMLDivElement | null>(null)

  // Keep the friends and messages data (and their badges) populated app-wide once signed in; clear
  // them on sign-out. Live updates then arrive via the WebSocket push (see friendsStore, messagesStore).
  useEffect(() => {
    if (status === 'authenticated') {
      void loadFriends()
      void loadThreads()
    } else if (status === 'anonymous') {
      resetFriends()
      resetMessages()
    }
  }, [status, loadFriends, resetFriends, loadThreads, resetMessages])

  useEffect(() => {
    if (!menuOpen) return
    const closeWhenOutside = (event: PointerEvent) => {
      if (!rootRef.current?.contains(event.target as Node)) setMenuOpen(false)
    }
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setMenuOpen(false)
    }
    document.addEventListener('pointerdown', closeWhenOutside)
    document.addEventListener('keydown', closeOnEscape)
    return () => {
      document.removeEventListener('pointerdown', closeWhenOutside)
      document.removeEventListener('keydown', closeOnEscape)
    }
  }, [menuOpen])

  if (!accountsEnabled) return null

  if (status !== 'authenticated' || !user) {
    return (
      <>
        <button type="button" className={styles.login} onClick={() => setLoginOpen(true)}>
          {t('auth.logIn')}
        </button>
        <LoginModal open={loginOpen} onClose={() => setLoginOpen(false)} />
      </>
    )
  }

  const go = (path: string) => {
    setMenuOpen(false)
    navigate(path)
  }
  const signOut = () => {
    setMenuOpen(false)
    resetFriends()
    resetMessages()
    logout()
  }
  const statusParts = [
    ...(onlineCount > 0 ? [t(onlineCount === 1 ? 'auth.onlineFriendOne' : 'auth.onlineFriendMany', { count: onlineCount })] : []),
    ...(incomingCount > 0 ? [t(incomingCount === 1 ? 'auth.requestOne' : 'auth.requestMany', { count: incomingCount })] : []),
  ]
  const portrait = hasAvatarArt(user.avatar)

  return (
    <div className={styles.root} ref={rootRef}>
      <button
        type="button"
        className={styles.trigger}
        aria-haspopup="menu"
        aria-expanded={menuOpen}
        onClick={() => setMenuOpen((open) => !open)}
        data-testid="account-menu"
        aria-label={[user.displayName, ...statusParts].join(', ')}
        title={statusParts.length > 0 ? statusParts.join(' · ') : undefined}
      >
        <span className={styles.avatar} data-portrait={portrait} aria-hidden>
          {portrait ? <AvatarArt avatar={user.avatar} /> : initialOf(user.displayName)}
          {incomingCount > 0 && <span className={styles.avatarRequests}>{incomingCount}</span>}
          {onlineCount > 0 && <span className={styles.avatarOnline}>{onlineCount}</span>}
        </span>
        <span className={styles.identity}>
          <span className={styles.name}>{user.displayName}</span>
          {(onlineCount > 0 || incomingCount > 0) && (
            <span className={styles.status} aria-hidden>
              {onlineCount > 0 && (
                <span className={styles.presence} data-testid="account-menu-online-count">
                  <span className={styles.onlineDot} />
                  {t(onlineCount === 1 ? 'auth.onlineFriendOne' : 'auth.onlineFriendMany', { count: onlineCount })}
                </span>
              )}
              {incomingCount > 0 && (
                <span className={styles.pending} data-testid="account-menu-request-count">
                  {t(incomingCount === 1 ? 'auth.requestOne' : 'auth.requestMany', { count: incomingCount })}
                </span>
              )}
            </span>
          )}
        </span>
        <svg className={styles.chevron} viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
          <path d="M6 9l6 6 6-6" />
        </svg>
      </button>

      {menuOpen && (
        <div className={styles.menu} role="menu" aria-label={t('auth.account')}>
          <div className={styles.menuHeader}>
            <span className={styles.menuMuted}>{t('auth.signedInAs')}</span>
            <span className={styles.menuName}>{user.displayName}</span>
          </div>
          <button type="button" role="menuitem" className={styles.item} onClick={() => go('/profile')}>
            {t('auth.profile')}
          </button>
          <button
            type="button"
            role="menuitem"
            className={styles.item}
            onClick={() => go('/preferences')}
            title={t('auth.preferencesDescription')}
          >
            {t('auth.preferences')}
          </button>
          <button
            type="button"
            role="menuitem"
            className={styles.item}
            onClick={() => go('/stats')}
            title={t('auth.statsDescription')}
          >
            {t('auth.stats')}
          </button>
          <button type="button" role="menuitem" className={styles.item} onClick={() => go('/messages')}>
            {t('auth.messages')}
            {unreadMessages > 0 && (
              <span className={styles.itemMeta}>
                <span className={styles.unreadCount} aria-label={t('auth.unreadCount', { count: unreadMessages })}>
                  {unreadMessages > 99 ? '99+' : unreadMessages}
                </span>
              </span>
            )}
          </button>
          <button type="button" role="menuitem" className={styles.item} onClick={() => go('/friends')}>
            {t('auth.friends')}
            <span className={styles.itemMeta}>
              {onlineCount > 0 && (
                <span className={styles.online} aria-label={t('auth.friendsOnlineCount', { count: onlineCount })}>
                  <span className={styles.onlineDot} aria-hidden />
                  {onlineCount}
                </span>
              )}
              {incomingCount > 0 && (
                <span className={styles.requests} aria-label={t('auth.pendingRequestCount', { count: incomingCount })}>
                  {incomingCount}
                </span>
              )}
            </span>
          </button>
          {user.isAdmin && (
            <button type="button" role="menuitem" className={styles.item} onClick={() => go('/admin')}>
              {t('auth.admin')}
            </button>
          )}
          <div className={styles.separator} role="separator" />
          <button type="button" role="menuitem" className={`${styles.item} ${styles.logout}`} onClick={signOut}>
            {t('auth.logOut')}
          </button>
        </div>
      )}
    </div>
  )
}
