/**
 * Magic-link sign-in modal: enter an email, receive a one-time sign-in link. No passwords.
 * Uses the account area's glass dialog (`account.module.css`).
 */
import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import type React from 'react'
import { requestLogin } from '@/api/account'
import a from '@/components/profile/account.module.css'
import p from '@/components/ui/PageShell.module.css'

interface LoginModalProps {
  open: boolean
  onClose: () => void
}

export function LoginModal({ open, onClose }: LoginModalProps) {
  const { t } = useTranslation()
  const [email, setEmail] = useState('')
  const [status, setStatus] = useState<'idle' | 'sending' | 'sent'>('idle')
  const [error, setError] = useState<string | null>(null)
  // Only ever set by a local dev server that has no mail to send — see `requestLogin`.
  const [devLoginPath, setDevLoginPath] = useState<string | null>(null)

  useEffect(() => {
    if (!open) return
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [open, onClose])

  // Reset transient state whenever the modal is (re)opened.
  useEffect(() => {
    if (open) {
      setStatus('idle')
      setError(null)
      setDevLoginPath(null)
    }
  }, [open])

  if (!open) return null

  const submit = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!email.trim()) return
    setStatus('sending')
    setError(null)
    try {
      const result = await requestLogin(email.trim())
      setDevLoginPath(result.devLoginPath ?? null)
      setStatus('sent')
    } catch (err) {
      setStatus('idle')
      setError(err instanceof Error ? err.message : t('auth.genericError'))
    }
  }

  return (
    <div className={a.backdrop} onClick={onClose} role="presentation">
      <div className={a.dialog} data-size="small" onClick={(e) => e.stopPropagation()} role="dialog" aria-modal="true" aria-labelledby="login-title">
        <div className={a.dialogHead}>
          <h2 id="login-title" className={a.dialogTitle}>
            {status === 'sent' && devLoginPath ? t('auth.signInDev') : status === 'sent' ? t('auth.checkEmail') : t('auth.signIn')}
          </h2>
          <button type="button" onClick={onClose} className={a.close} aria-label={t('auth.close')}>
            ×
          </button>
        </div>
        {status === 'sent' && devLoginPath ? (
          <div className={a.form}>
            <p className={a.muted}>
              {t('auth.devMailUnavailable', { email })}
            </p>
            <button
              type="button"
              onClick={() => window.location.assign(devLoginPath)}
              className={p.buttonPrimary}
              data-testid="dev-sign-in"
            >
              {t('auth.signInNow')}
            </button>
          </div>
        ) : status === 'sent' ? (
          <div className={a.form}>
            <p className={a.muted}>
              {t('auth.linkSent', { email })}
            </p>
            <button type="button" onClick={onClose} className={p.buttonPrimary}>
              {t('auth.done')}
            </button>
          </div>
        ) : (
          <>
            <p className={a.muted} style={{ marginTop: 6 }}>
              {t('auth.emailPrompt')}
            </p>
            <form onSubmit={submit} className={a.form}>
              <input
                type="email"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                placeholder={t('auth.emailAddress')}
                aria-label={t('auth.email')}
                autoFocus
                required
                className={p.input}
              />
              {error && <p className={a.error}>{error}</p>}
              <button type="submit" disabled={status === 'sending'} className={p.buttonPrimary}>
                {status === 'sending' ? t('auth.sending') : t('auth.sendSignInLink')}
              </button>
            </form>
          </>
        )}
      </div>
    </div>
  )
}
