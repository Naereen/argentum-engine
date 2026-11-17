// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import i18n from '@/i18n'
import { I18nextProvider, initReactI18next, useTranslation } from 'react-i18next'
import { LanguageSwitcher } from './LanguageSwitcher'
import en from '@/i18n/locales/en.json'
import fr from '@/i18n/locales/fr.json'

void i18n.use(initReactI18next).init({
  resources: { en: { translation: en }, fr: { translation: fr } },
  lng: 'en',
  fallbackLng: 'en',
  interpolation: { escapeValue: false },
})

function TranslatedHelp() {
  const { t } = useTranslation()
  return <p>{t('navigation.help')}</p>
}

function App() {
  return (
    <I18nextProvider i18n={i18n}>
      <LanguageSwitcher floating={false} />
      <TranslatedHelp />
    </I18nextProvider>
  )
}

describe('LanguageSwitcher', () => {
  beforeEach(async () => {
    window.localStorage.clear()
    await i18n.changeLanguage('en')
  })

  afterEach(() => {
    cleanup()
  })

  it('switches translated text to French and persists the selection', async () => {
    render(<App />)
    expect(screen.getByText('Help')).toBeTruthy()

    fireEvent.change(screen.getByRole('combobox', { name: 'Language' }), { target: { value: 'fr' } })

    expect(await screen.findByText('Aide')).toBeTruthy()
    expect(i18n.language).toBe('fr')
    expect(window.localStorage.getItem('argentum-language')).toBe('fr')
    expect((screen.getByRole('combobox', { name: 'Langue' }) as HTMLSelectElement).value).toBe('fr')
  })
})
