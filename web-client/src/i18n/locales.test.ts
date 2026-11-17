import { describe, expect, it } from 'vitest'
import en from './locales/en.json'
import fr from './locales/fr.json'

function leafPaths(value: unknown, prefix = ''): string[] {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) return [prefix]
  return Object.entries(value).flatMap(([key, child]) => leafPaths(child, prefix ? `${prefix}.${key}` : key))
}

function getValue(value: unknown, path: string): unknown {
  return path.split('.').reduce<unknown>((current, key) => {
    if (current === null || typeof current !== 'object') return undefined
    return (current as Record<string, unknown>)[key]
  }, value)
}

function placeholders(value: unknown): string[] {
  if (typeof value !== 'string') return []
  return [...value.matchAll(/{{\s*([^{}\s]+)\s*}}/g)].map((match) => match[1]!).sort()
}

describe('locale dictionaries', () => {
  it('have identical translation keys', () => {
    expect(leafPaths(fr).sort()).toEqual(leafPaths(en).sort())
  })

  it('contain non-empty strings for every locale key', () => {
    for (const locale of [en, fr]) {
      for (const path of leafPaths(locale)) {
        expect(getValue(locale, path), path).toEqual(expect.any(String))
        expect((getValue(locale, path) as string).trim(), path).not.toBe('')
      }
    }
  })

  it('use matching interpolation placeholders for every translation', () => {
    for (const path of leafPaths(en)) {
      expect(placeholders(getValue(fr, path)), path).toEqual(placeholders(getValue(en, path)))
    }
  })
})
