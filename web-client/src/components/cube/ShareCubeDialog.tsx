/**
 * Share a cube with friends.
 *
 * A link has to open on someone else's machine, so only a cube saved to an account can have one; the
 * link always shows the owner's latest *saved* version, which is why unsaved edits are saved first
 * rather than silently left out. Anyone can still copy the cube as a text list — the format the
 * editor's Paste tab reads back — so a guest is never stuck.
 */
import { useState } from 'react'
import { createPortal } from 'react-dom'
import { useTranslation } from 'react-i18next'
import { sharedCubePath } from '@/api/account'
import { LoginModal } from '@/components/auth/LoginModal'
import { useAuthStore } from '@/store/authStore'
import type { UnifiedCube } from '@/store/useUnifiedCubes'
import styles from './CubeEditor.module.css'

interface ShareCubeDialogProps {
  /** The cube as last saved, or null when it has never been saved. */
  readonly saved: UnifiedCube | null
  /** The editor holds changes the saved copy doesn't have. */
  readonly dirty: boolean
  readonly isLoggedIn: boolean
  /** Plain-text list of the cube as it stands. */
  readonly text: string
  /** Save the editor's current state; resolves to the saved cube, or null if saving failed. */
  readonly onSave?: () => Promise<UnifiedCube | null>
  readonly share: (cube: UnifiedCube) => Promise<string>
  readonly unshare: (cube: UnifiedCube) => Promise<void>
  readonly onClose: () => void
}

export function ShareCubeDialog({
  saved,
  dirty,
  isLoggedIn,
  text,
  onSave,
  share,
  unshare,
  onClose,
}: ShareCubeDialogProps) {
  const { t } = useTranslation()
  const accountsEnabled = useAuthStore((s) => s.accountsEnabled)
  const [code, setCode] = useState<string | null>(saved?.shareCode ?? null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [copied, setCopied] = useState<'link' | 'text' | null>(null)
  const [loginOpen, setLoginOpen] = useState(false)

  const url = code ? `${window.location.origin}${sharedCubePath(code)}` : null
  const inCloud = saved?.cloudId != null

  async function copy(value: string, what: 'link' | 'text') {
    try {
      await navigator.clipboard.writeText(value)
      setCopied(what)
      window.setTimeout(() => setCopied(null), 1800)
    } catch {
      setError(t('cubeUi.clipboardBlocked'))
    }
  }

  async function run(action: () => Promise<void>) {
    setBusy(true)
    setError(null)
    try {
      await action()
    } catch (err) {
      setError(err instanceof Error ? err.message : t('cubeUi.somethingWentWrong'))
    } finally {
      setBusy(false)
    }
  }

  /** Save first when the link would otherwise show an older version, then create the link. */
  const createLink = () =>
    run(async () => {
      let target = saved
      if ((dirty || !inCloud) && onSave) target = await onSave()
      if (!target || target.cloudId == null) throw new Error(t('cubeUi.saveCubeToAccountFirst'))
      const shareCode = await share(target)
      setCode(shareCode)
      await copy(`${window.location.origin}${sharedCubePath(shareCode)}`, 'link')
    })

  const revoke = () =>
    run(async () => {
      if (saved) await unshare(saved)
      setCode(null)
    })

  return createPortal(
    <>
      <div className={styles.shareBackdrop} onClick={onClose} />
      <div className={styles.shareDialog} role="dialog" aria-modal="true" aria-labelledby="share-cube-title">
        <h2 id="share-cube-title" className={styles.shareTitle}>{t('cubeUi.shareThisCube')}</h2>

        {!accountsEnabled ? (
          <p className={styles.shareBody}>
            {t('cubeUi.accountsDisabledShareHint')}
          </p>
        ) : !isLoggedIn ? (
          <>
            <p className={styles.shareBody}>
              {t('cubeUi.signInShareHint')}
            </p>
            <button type="button" className={styles.primaryButton} onClick={() => setLoginOpen(true)}>
              {t('cubeUi.signInToShare')}
            </button>
          </>
        ) : url ? (
          <>
            <p className={styles.shareBody}>
              {t(dirty ? 'cubeUi.sharedLatestDirty' : 'cubeUi.sharedLatest')}
            </p>
            <div className={styles.shareLinkRow}>
              <input className={styles.input} value={url} readOnly aria-label={t('cubeUi.shareLink')} onFocus={(e) => e.target.select()} />
              <button type="button" className={styles.primaryButton} onClick={() => void copy(url, 'link')}>
                {copied === 'link' ? t('cubeUi.copied') : t('cubeUi.copyLink')}
              </button>
            </div>
            <button type="button" className={styles.linkButton} disabled={busy} onClick={() => void revoke()}>
              {t('cubeUi.stopSharing')}
            </button>
          </>
        ) : (
          <>
            <p className={styles.shareBody}>
              {t('cubeUi.createShareHint')}
            </p>
            <button type="button" className={styles.primaryButton} disabled={busy} onClick={() => void createLink()}>
              {busy ? t('cubeUi.creatingLink') : dirty || !inCloud ? t('cubeUi.saveAndCreateLink') : t('cubeUi.createLink')}
            </button>
          </>
        )}

        {error && <p className={styles.error}>{error}</p>}

        <div className={styles.shareFooter}>
          <button type="button" className={styles.secondaryButton} onClick={() => void copy(text, 'text')}>
            {copied === 'text' ? t('cubeUi.listCopied') : t('cubeUi.copyAsTextList')}
          </button>
          <button type="button" className={styles.secondaryButton} onClick={onClose}>
            {t('cubeUi.done')}
          </button>
        </div>
      </div>
      <LoginModal open={loginOpen} onClose={() => setLoginOpen(false)} />
    </>,
    document.body,
  )
}
