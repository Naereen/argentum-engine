/**
 * Custom cards — paste a Scryfall(-style) card object, have Argentum Assay read it, and play it.
 *
 * The panel is deliberately two things at once, because they are the same act: it *verifies* a
 * card's text against the parser (each printed line with the verdict the gates use, and a caret on
 * the token a decline died on) and it *compiles* that text into a real card the scenario can use.
 * A card only becomes addable once Assay reads every one of its lines — a partially-read card
 * would sit on the board looking right and be missing an ability.
 *
 * The panel stays visible when the server has the sandbox switched off; the check just answers with
 * what to turn on. Hiding it would leave the tester guessing whether the feature exists.
 */
import { useState } from 'react'
import type { AssayCompileResponse } from './types'
import type { CustomCard } from './builderState'
import { customCardImageUri } from './customCardJson'
import styles from './ScenarioBuilder.module.css'
import { useTranslation } from 'react-i18next'

const PLACEHOLDER = `{
  "name": "Argentum Sentinel",
  "mana_cost": "{2}{W}",
  "type_line": "Creature — Bird Soldier",
  "oracle_text": "Flying, vigilance\\nWhen this creature enters, draw a card.",
  "power": "2",
  "toughness": "3"
}`

/** The verdict vocabulary is the touchstone's; these are only how each one reads on screen. */
const VERDICT_KEY: Record<string, 'read' | 'respelled' | 'notRead' | 'ambiguous' | 'mismatch'> = {
  ROUND_TRIP: 'read',
  VARIANT: 'respelled',
  DECLINED: 'notRead',
  AMBIGUOUS: 'ambiguous',
  MISMATCH: 'mismatch',
}

function verdictClass(verdict: string): string {
  if (verdict === 'ROUND_TRIP') return styles.verdictOk!
  if (verdict === 'VARIANT') return styles.verdictVariant!
  return styles.verdictBad!
}

export function CustomCardPanel({
  cards,
  onAdd,
  onRemove,
  onPlace,
  placeHint,
}: {
  cards: CustomCard[]
  onAdd: (card: CustomCard) => void
  onRemove: (name: string) => void
  /** Put a compiled card into the builder's currently targeted seat + zone. */
  onPlace: (name: string) => void
  /** Where `onPlace` will put it, e.g. "Player 1's battlefield" — the button is otherwise mute. */
  placeHint: string
}) {
  const { t } = useTranslation()
  const [json, setJson] = useState('')
  const [checking, setChecking] = useState(false)
  const [result, setResult] = useState<AssayCompileResponse | null>(null)
  const [error, setError] = useState<string | null>(null)

  const check = async () => {
    setChecking(true)
    setError(null)
    setResult(null)
    try {
      const res = await fetch('/api/dev/scenarios/assay', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ json }),
      })
      if (res.status === 404) {
        setError(
          t('scenario.custom.serverDisabled'),
        )
        return
      }
      if (!res.ok) {
        setError(t('scenario.custom.requestFailed', { status: res.status }))
        return
      }
      setResult((await res.json()) as AssayCompileResponse)
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : t('scenario.custom.couldNotReachServer'))
    } finally {
      setChecking(false)
    }
  }

  const previewArt = result?.compiled ? customCardImageUri(json) : null

  const addCompiled = () => {
    if (!result?.compiled || !result.cardName) return
    onAdd({ name: result.cardName, json })
    setJson('')
    setResult(null)
  }

  return (
    <div className={styles.jsonDrawer}>
      <span className={styles.hint}>
        {t('scenario.custom.intro')}
      </span>

      <textarea
        className={styles.jsonArea}
        value={json}
        placeholder={PLACEHOLDER}
        spellCheck={false}
        onChange={(e) => setJson(e.target.value)}
      />

      <div className={styles.jsonActions}>
        <button
          type="button"
          className={styles.ghostBtn}
          disabled={!json.trim() || checking}
          onClick={() => void check()}
        >
          {checking ? t('scenario.custom.reading') : t('scenario.custom.check')}
        </button>
        <button
          type="button"
          className={styles.ghostBtn}
          disabled={!result?.compiled}
          onClick={addCompiled}
          title={result?.compiled ? t('scenario.custom.makeAvailable') : t('scenario.custom.checkFirst')}
        >
          {t('scenario.custom.addToScenario')}
        </button>
      </div>

      {error && <div className={styles.assayError}>{error}</div>}

      {result && (
        <div className={styles.assayResult}>
          <div className={result.compiled ? styles.assayHeadOk : styles.assayHeadBad}>
            {result.compiled
              ? t('scenario.custom.compiledReady', { name: result.cardName })
              : t('scenario.custom.didNotCompile', { name: result.cardName ?? t('scenario.custom.thisCard') })}
          </div>

          {/* The card's own art, when the pasted object carries `image_uris` — it is what the
              board and the game will show, so seeing it here is the confirmation. */}
          {previewArt && (
            <img className={styles.assayArt} src={previewArt} alt={result.cardName ?? 'Custom card'} />
          )}

          {result.lines.length > 0 && (
            <ul className={styles.assayLines}>
              {result.lines.map((line) => (
                <li key={line.index}>
                  <span className={verdictClass(line.verdict)}>
                    {(() => {
                      const verdictKey = VERDICT_KEY[line.verdict]
                      return verdictKey
                        ? t(`scenario.custom.verdict.${verdictKey}`)
                        : line.verdict.toLowerCase()
                    })()}
                  </span>
                  <span className={styles.assayLineText}>{line.text || t('scenario.custom.blank')}</span>
                  {line.printed && (
                    <span className={styles.assayPrinted}>{t('scenario.custom.canonical', { text: line.printed })}</span>
                  )}
                  {line.explanation && <pre className={styles.assayCaret}>{line.explanation}</pre>}
                </li>
              ))}
            </ul>
          )}

          {result.declines.length > 0 && (
            <ul className={styles.errorList}>
              {result.declines.map((d, i) => (
                <li key={i}>
                  {d.detail}
                  {d.line ? ` — “${d.line}”` : ''}
                </li>
              ))}
            </ul>
          )}

          {result.warnings.map((w, i) => (
            <div key={i} className={styles.assayWarning}>
              {w}
            </div>
          ))}
        </div>
      )}

      {cards.length > 0 && (
        <div className={styles.customCardList}>
          <span className={styles.smallLabel}>{t('scenario.custom.inScenario')}</span>
          {cards.map((card) => (
            <div key={card.name} className={styles.customCardRow}>
              {customCardImageUri(card.json) && (
                <img
                  className={styles.customCardThumb}
                  src={customCardImageUri(card.json)!}
                  alt=""
                />
              )}
              <span className={styles.customCardName}>{card.name}</span>
              <button
                type="button"
                className={styles.ghostBtn}
                onClick={() => onPlace(card.name)}
                title={t('scenario.custom.addToPlace', { place: placeHint })}
              >
                {t('scenario.custom.addToPlace', { place: placeHint })}
              </button>
              <button
                type="button"
                className={styles.dangerBtn}
                onClick={() => onRemove(card.name)}
                title={t('scenario.custom.removeAllCopies')}
              >
                {t('scenario.remove')}
              </button>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}
