// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { I18nextProvider, initReactI18next } from 'react-i18next'
import i18n from '@/i18n'
import en from '@/i18n/locales/en.json'
import fr from '@/i18n/locales/fr.json'
import { SetPickerModal } from './SetPickerModal'

void i18n.use(initReactI18next).init({
  resources: { en: { translation: en }, fr: { translation: fr } },
  lng: 'en',
  fallbackLng: 'en',
  interpolation: { escapeValue: false },
})

const sets = [{
  code: 'TST',
  name: 'Test Set',
  partial: true,
  implementedCount: 35,
  releaseDate: '2002-10-07',
}]

describe('SetPickerModal localization', () => {
  beforeEach(async () => {
    window.localStorage.clear()
    await i18n.changeLanguage('en')
  })

  afterEach(() => cleanup())

  it('updates picker controls and set metadata when the language changes', async () => {
    render(
      <I18nextProvider i18n={i18n}>
        <SetPickerModal
          sets={sets}
          selectedCodes={[]}
          onToggleSet={() => undefined}
          onClose={() => undefined}
        />
      </I18nextProvider>,
    )

    expect(screen.getByText('Choose sets')).toBeTruthy()
    expect(screen.getByPlaceholderText('Search sets by name or code…')).toBeTruthy()

    await act(async () => {
      await i18n.changeLanguage('fr')
    })

    expect(screen.getByText('Choisir des extensions')).toBeTruthy()
    expect(screen.getByPlaceholderText("Rechercher par nom ou code d'extension…")).toBeTruthy()
    fireEvent.click(screen.getByText('Afficher les extensions partielles'))
    expect(await screen.findByText('Test Set')).toBeTruthy()
    expect(screen.getByText('partielle')).toBeTruthy()
    expect(screen.getByText('35 cartes')).toBeTruthy()
  })
})
