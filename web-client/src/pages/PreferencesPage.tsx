/**
 * /preferences — the player's table setup (see `store/preferencesStore.ts`). Open to guests too:
 * their preferences live in this browser, and a signed-in account carries them across devices.
 */
import { useEffect } from 'react'
import { PreferencesPanel } from '@/components/preferences/PreferencesPanel'
import { AccountPage } from '@/components/profile/accountUi'
import { pageStyles as p } from '@/components/ui/PageShell'
import { useAuthStore } from '@/store/authStore'
import { syncPreferences } from '@/store/preferencesStore'
import { useTranslation } from 'react-i18next'

export function PreferencesPage() {
  const { t } = useTranslation()
  const status = useAuthStore((st) => st.status)
  const accountsEnabled = useAuthStore((st) => st.accountsEnabled)
  const init = useAuthStore((st) => st.init)

  useEffect(() => {
    if (status === 'idle') void init()
  }, [status, init])

  // Pick up a change made on another device before the player starts editing here.
  useEffect(() => {
    if (status === 'authenticated') void syncPreferences()
  }, [status])

  return (
    <AccountPage title={t('preferencesPage.title')} width="narrow">
      <div>
        <h1 className={p.h1}>{t('preferencesPage.title')}</h1>
        <p className={p.lede}>
          {status === 'authenticated'
            ? t('preferencesPage.savedToAccount')
            : accountsEnabled
              ? t('preferencesPage.savedInBrowserWithSignIn')
              : t('preferencesPage.savedInBrowser')}
        </p>
      </div>
      <PreferencesPanel />
    </AccountPage>
  )
}
