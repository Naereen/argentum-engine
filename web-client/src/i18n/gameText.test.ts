import { afterEach, describe, expect, it } from 'vitest'
import i18n from './index'
import { localizeNextStopPoint } from './gameText'

describe('localizeNextStopPoint', () => {
  afterEach(async () => {
    await i18n.changeLanguage('en')
  })

  it('translates server-provided step labels using the active locale', async () => {
    await i18n.changeLanguage('fr')

    expect(localizeNextStopPoint('To Attackers')).toContain('déclaration des attaquants')
    expect(localizeNextStopPoint('To My Turn')).not.toBe('To My Turn')
  })

  it('uses the localized pass label when no stop point is provided', async () => {
    await i18n.changeLanguage('fr')

    expect(localizeNextStopPoint(null)).toBe('Céder')
  })

  it('preserves unknown server labels rather than guessing', () => {
    expect(localizeNextStopPoint('A new server label')).toBe('A new server label')
  })
})
