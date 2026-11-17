/**
 * The preferences editor — every setting in `store/preferencesStore.ts`, grouped into Gameplay,
 * Battlefield, Display and (signed in) Messages. Shared by the /preferences page and the in-game
 * settings dialog, so the two can never drift. Each control writes straight to the store (which
 * saves to localStorage and, when signed in, to the account); there is no Save button.
 */
import type React from 'react'
import {
  DEFAULT_PREFERENCES,
  OWN_STACK_STOPS,
  STACK_LIMITS,
  STOPPABLE_STEPS,
  usePreferences,
  type ForcedChoiceMode,
  type MotionPreference,
  type StackingRule,
} from '@/store/preferencesStore'
import { useAuthStore } from '@/store/authStore'
import { Step, StepDisplayNames } from '@/types/enums'
import type { OwnStackStop, PriorityModeValue } from '@/types/messages'
import s from './Preferences.module.css'
import { LanguageSwitcher } from '@/components/ui/LanguageSwitcher'
import { useTranslation } from 'react-i18next'

const MODE_OPTIONS: { value: PriorityModeValue; labelKey: 'preferences.auto' | 'preferences.stops' | 'preferences.fullControl'; hintKey: 'preferences.startingPriorityHint' | 'preferences.startingPriorityStopsHint' | 'preferences.startingPriorityFullHint' }[] = [
  { value: 'auto', labelKey: 'preferences.auto', hintKey: 'preferences.startingPriorityHint' },
  { value: 'stops', labelKey: 'preferences.stops', hintKey: 'preferences.startingPriorityStopsHint' },
  { value: 'fullControl', labelKey: 'preferences.fullControl', hintKey: 'preferences.startingPriorityFullHint' },
]

/**
 * `gameplay.forcedChoices`. Each hint names its trade-off: the faster the forced answer, the more
 * its timing can tell an opponent the question was forced. All three answer at once a question
 * everyone can see is forced, since a pause there hides nothing.
 */
const FORCED_CHOICE_OPTIONS: { value: ForcedChoiceMode; labelKey: 'preferences.answerInstantly' | 'preferences.answerForMe' | 'preferences.askEveryTime'; hintKey: 'preferences.forcedInstantHint' | 'preferences.forcedDelayedHint' | 'preferences.forcedAskHint' }[] = [
  { value: 'instant', labelKey: 'preferences.answerInstantly', hintKey: 'preferences.forcedInstantHint' },
  { value: 'delayed', labelKey: 'preferences.answerForMe', hintKey: 'preferences.forcedDelayedHint' },
  { value: 'ask', labelKey: 'preferences.askEveryTime', hintKey: 'preferences.forcedAskHint' },
]

const MOTION_OPTIONS: { value: MotionPreference; labelKey: 'preferences.followSystem' | 'preferences.alwaysReduce' }[] = [
  { value: 'system', labelKey: 'preferences.followSystem' },
  { value: 'reduce', labelKey: 'preferences.alwaysReduce' },
]

const OWN_STACK_STOP_LABELS: Record<OwnStackStop, {
  labelKey: 'preferences.myTriggeredAbilities' | 'preferences.myActivatedAbilities' | 'preferences.mySpells'
  hintKey: 'preferences.myTriggeredAbilitiesHint' | 'preferences.myActivatedAbilitiesHint' | 'preferences.mySpellsHint'
}> = {
  TRIGGERED_ABILITIES: {
    labelKey: 'preferences.myTriggeredAbilities',
    hintKey: 'preferences.myTriggeredAbilitiesHint',
  },
  ACTIVATED_ABILITIES: {
    labelKey: 'preferences.myActivatedAbilities',
    hintKey: 'preferences.myActivatedAbilitiesHint',
  },
  SPELLS: {
    labelKey: 'preferences.mySpells',
    hintKey: 'preferences.mySpellsHint',
  },
}

/** On your own turn the engine always stops in your main phases — shown checked and locked. */
const ALWAYS_STOP_MY_TURN = new Set<Step>([Step.PRECOMBAT_MAIN, Step.POSTCOMBAT_MAIN])

const ROWS: { key: 'lands' | 'creatures' | 'other'; labelKey: 'preferences.lands' | 'preferences.creatures' | 'preferences.otherPermanents'; hintKey: 'preferences.landExamples' | 'preferences.creatureTokens' | 'preferences.otherPermanentTypes' }[] = [
  { key: 'lands', labelKey: 'preferences.lands', hintKey: 'preferences.landExamples' },
  { key: 'creatures', labelKey: 'preferences.creatures', hintKey: 'preferences.creatureTokens' },
  { key: 'other', labelKey: 'preferences.otherPermanents', hintKey: 'preferences.otherPermanentTypes' },
]

export function PreferencesPanel({ compact = false }: { compact?: boolean }) {
  const { t } = useTranslation()
  const prefs = usePreferences((st) => st.prefs)
  const update = usePreferences((st) => st.update)
  const reset = usePreferences((st) => st.reset)
  const { gameplay, battlefield, display } = prefs
  // Messages need an account; a guest has nothing to be alerted to.
  const signedIn = useAuthStore((st) => st.status === 'authenticated')

  const toggleStop = (step: Step, mine: boolean) => {
    const key = mine ? 'myTurnStops' : 'opponentTurnStops'
    const current = gameplay[key]
    update('gameplay', { [key]: current.includes(step) ? current.filter((x) => x !== step) : [...current, step] })
  }
  const setRule = (key: 'lands' | 'creatures' | 'other', patch: Partial<StackingRule>) =>
    update('battlefield', { [key]: { ...battlefield[key], ...patch } })

  return (
    <div className={s.root} data-compact={compact}>
      <section className={s.section}>
        <header className={s.sectionHead}>
          <h2 className={s.sectionTitle}>{t('settings.language')}</h2>
        </header>
        <LanguageSwitcher floating={false} />
      </section>
      <section className={s.section}>
        <header className={s.sectionHead}>
          <h2 className={s.sectionTitle}>{t('settings.gameplay')}</h2>
          <p className={s.sectionLede}>{t('settings.gameplayDescription')}</p>
        </header>

        <Field label={t('settings.startingPriorityMode')} hint={t(MODE_OPTIONS.find((m) => m.value === gameplay.priorityMode)!.hintKey)}>
          <Segmented
            value={gameplay.priorityMode}
            options={MODE_OPTIONS.map((option) => ({ value: option.value, label: t(option.labelKey) }))}
            onChange={(priorityMode) => update('gameplay', { priorityMode })}
            label={t('settings.startingPriorityMode')}
          />
        </Field>
        <p className={s.note}>{t('preferences.startingPriorityNote')}</p>

        <div className={s.field}>
          <div className={s.fieldText}>
            <span className={s.label}>{t('settings.alwaysStopAt')}</span>
            <span className={s.hint}>
              {t('preferences.alwaysStopHint')}
            </span>
          </div>
        </div>
        <table className={s.stopGrid}>
          <thead>
            <tr>
              <th scope="col">{t('settings.step')}</th>
              <th scope="col">{t('settings.myTurn')}</th>
              <th scope="col">{t('settings.opponentTurn')}</th>
            </tr>
          </thead>
          <tbody>
            {STOPPABLE_STEPS.map((step) => {
              const locked = ALWAYS_STOP_MY_TURN.has(step)
              return (
                <tr key={step}>
                  <th scope="row">{t(`phases.steps.${step}`, { defaultValue: StepDisplayNames[step] })}</th>
                  <td>
                    <input
                      type="checkbox"
                      className={s.check}
                      checked={locked || gameplay.myTurnStops.includes(step)}
                      disabled={locked}
                      title={locked ? t('preferences.lockedPriority') : undefined}
                      aria-label={t('preferences.stopAtOnMyTurn', { step: t(`phases.steps.${step}`, { defaultValue: StepDisplayNames[step] }) })}
                      onChange={() => toggleStop(step, true)}
                    />
                  </td>
                  <td>
                    <input
                      type="checkbox"
                      className={s.check}
                      checked={gameplay.opponentTurnStops.includes(step)}
                      aria-label={t('preferences.stopAtOnOpponentTurn', { step: t(`phases.steps.${step}`, { defaultValue: StepDisplayNames[step] }) })}
                      onChange={() => toggleStop(step, false)}
                    />
                  </td>
                </tr>
              )
            })}
          </tbody>
        </table>
        {(gameplay.myTurnStops.length > 0 || gameplay.opponentTurnStops.length > 0) && (
          <button
            type="button"
            className={s.linkButton}
            onClick={() => update('gameplay', { myTurnStops: [], opponentTurnStops: [] })}
          >
            {t('settings.clearStops')}
          </button>
        )}

        <div className={s.field}>
          <div className={s.fieldText}>
            <span className={s.label}>{t('settings.holdPriority')}</span>
            <span className={s.hint}>
              {t('preferences.holdPriorityHint')}
            </span>
          </div>
        </div>
        {OWN_STACK_STOPS.map((kind) => (
          <Toggle
            key={kind}
            label={t(OWN_STACK_STOP_LABELS[kind].labelKey)}
            hint={t(OWN_STACK_STOP_LABELS[kind].hintKey)}
            checked={gameplay.ownStackStops.includes(kind)}
            onChange={(on) =>
              update('gameplay', {
                ownStackStops: on
                  ? [...gameplay.ownStackStops, kind]
                  : gameplay.ownStackStops.filter((k) => k !== kind),
              })
            }
          />
        ))}

        <Toggle
          label={t('settings.autoTap')}
          hint={t('preferences.autoTapHint')}
          checked={gameplay.autoTap}
          onChange={(autoTap) => update('gameplay', { autoTap })}
        />
        <Toggle
          label={t('settings.autoOrderTriggers')}
          hint={t('preferences.autoOrderTriggersHint')}
          checked={gameplay.autoOrderTriggers}
          onChange={(autoOrderTriggers) => update('gameplay', { autoOrderTriggers })}
        />
        <Field
          label={t('settings.singleAnswerChoices')}
          hint={t(FORCED_CHOICE_OPTIONS.find((o) => o.value === gameplay.forcedChoices)!.hintKey)}
        >
          <Segmented
            value={gameplay.forcedChoices}
            options={FORCED_CHOICE_OPTIONS.map((option) => ({ value: option.value, label: t(option.labelKey) }))}
            onChange={(forcedChoices) => update('gameplay', { forcedChoices })}
label={t('settings.singleAnswerChoices')}
          />
        </Field>
        <Toggle
          label={t('settings.followAction')}
          hint={t('preferences.followActionHint')}
          checked={gameplay.followAction}
          onChange={(followAction) => update('gameplay', { followAction })}
        />
      </section>

      <section className={s.section}>
        <header className={s.sectionHead}>
          <h2 className={s.sectionTitle}>{t('settings.battlefield')}</h2>
          <p className={s.sectionLede}>
            {t('preferences.battlefieldDescription')}
          </p>
        </header>

        <div className={s.stackRows}>
          {ROWS.map(({ key, labelKey, hintKey }) => (
            <div key={key} className={s.stackRow}>
              <div className={s.fieldText}>
<span className={s.label}>{t(labelKey)}</span>
                <span className={s.hint}>{t(hintKey)}</span>
              </div>
              <label className={s.selectLabel}>
                <span>{t('preferences.stackFrom')}</span>
                <select
                  className={s.select}
                  value={battlefield[key].groupFrom}
                  onChange={(e) => setRule(key, { groupFrom: Number(e.target.value) })}
                >
                  <option value={0}>{t('preferences.neverStack')}</option>
                  {range(2, STACK_LIMITS.groupFromMax).map((n) => (
                    <option key={n} value={n}>{t('preferences.identical', { count: n })}</option>
                  ))}
                </select>
              </label>
              <label className={s.selectLabel}>
                <span>{t('preferences.stacksOf')}</span>
                <select
                  className={s.select}
                  value={battlefield[key].stackSize}
                  disabled={battlefield[key].groupFrom === 0}
                  onChange={(e) => setRule(key, { stackSize: Number(e.target.value) })}
                >
                  <option value={0}>{t('preferences.anySize')}</option>
                  {range(2, STACK_LIMITS.stackSizeMax).map((n) => (
                    <option key={n} value={n}>{t('preferences.atMost', { count: n })}</option>
                  ))}
                </select>
              </label>
              <StackPreview rule={battlefield[key]} layers={battlefield.maxVisibleLayers} />
            </div>
          ))}
        </div>

        <Field
          label={t('settings.visibleStackLayers')}
          hint={t('preferences.visibleStackLayersHint')}
        >
          <select
            className={s.select}
            value={battlefield.maxVisibleLayers}
            onChange={(e) => update('battlefield', { maxVisibleLayers: Number(e.target.value) })}
            aria-label={t('settings.visibleStackLayers')}
          >
            {range(STACK_LIMITS.layersMin, STACK_LIMITS.layersMax).map((n) => (
              <option key={n} value={n}>{n}</option>
            ))}
          </select>
        </Field>
      </section>

      <section className={s.section}>
        <header className={s.sectionHead}>
          <h2 className={s.sectionTitle}>{t('settings.display')}</h2>
        </header>
        <Field label={t('settings.reduceMotion')} hint={t('preferences.reduceMotionHint')}>
          <Segmented
            value={display.motion}
            options={MOTION_OPTIONS.map((option) => ({ value: option.value, label: t(option.labelKey) }))}
            onChange={(motion) => update('display', { motion })}
            label={t('settings.reduceMotion')}
          />
        </Field>
        <Toggle
          label={t('settings.cardPreview')}
          hint={t('preferences.cardPreviewHint')}
          checked={display.hoverPreview}
          onChange={(hoverPreview) => update('display', { hoverPreview })}
        />
      </section>

      {signedIn && (
        <section className={s.section}>
          <header className={s.sectionHead}>
            <h2 className={s.sectionTitle}>{t('settings.messages')}</h2>
          </header>
          <Toggle
            label={t('settings.messageAlerts')}
            hint={t('preferences.messageAlertsHint')}
            checked={prefs.messages.alertsAway}
            onChange={(alertsAway) => update('messages', { alertsAway })}
          />
        </section>
      )}

      <footer className={s.footer}>
        <span className={s.hint}>{t('settings.changesSave')}</span>
        <button
          type="button"
          className={s.resetButton}
          disabled={sameAsDefaults(prefs)}
          onClick={reset}
        >
          {t('settings.resetDefaults')}
        </button>
      </footer>
    </div>
  )
}

function sameAsDefaults(prefs: ReturnType<typeof usePreferences.getState>['prefs']): boolean {
  return JSON.stringify({ ...prefs, updatedAt: 0 }) === JSON.stringify(DEFAULT_PREFERENCES)
}

function range(from: number, to: number): number[] {
  return Array.from({ length: to - from + 1 }, (_, i) => from + i)
}

function Field({ label, hint, children }: { label: string; hint?: string | undefined; children: React.ReactNode }) {
  return (
    <div className={s.field}>
      <div className={s.fieldText}>
        <span className={s.label}>{label}</span>
        {hint && <span className={s.hint}>{hint}</span>}
      </div>
      <div className={s.control}>{children}</div>
    </div>
  )
}

function Toggle({ label, hint, checked, onChange }: { label: string; hint: string; checked: boolean; onChange: (v: boolean) => void }) {
  return (
    <div className={s.field}>
      <div className={s.fieldText}>
        <span className={s.label}>{label}</span>
        <span className={s.hint}>{hint}</span>
      </div>
      <div className={s.control}>
        <button
          type="button"
          role="switch"
          aria-checked={checked}
          aria-label={label}
          className={s.switch}
          data-on={checked}
          onClick={() => onChange(!checked)}
        >
          <span className={s.switchKnob} />
        </button>
      </div>
    </div>
  )
}

function Segmented<T extends string>({
  value,
  options,
  onChange,
  label,
}: {
  value: T
  options: readonly { value: T; label: string }[]
  onChange: (v: T) => void
  label: string
}) {
  return (
    <div className={s.segmented} role="radiogroup" aria-label={label}>
      {options.map((o) => (
        <button
          key={o.value}
          type="button"
          role="radio"
          aria-checked={o.value === value}
          className={s.segment}
          data-active={o.value === value}
          onClick={() => onChange(o.value)}
        >
          {o.label}
        </button>
      ))}
    </div>
  )
}

/** Seven identical permanents laid out under the rule — the quickest way to read what it does. */
function StackPreview({ rule, layers }: { rule: StackingRule; layers: number }) {
  const { t } = useTranslation()
  const N = 7
  const stacks: number[] = []
  if (rule.groupFrom === 0 || N < rule.groupFrom) {
    for (let i = 0; i < N; i++) stacks.push(1)
  } else if (rule.stackSize > 1 && N > rule.stackSize) {
    for (let left = N; left > 0; left -= rule.stackSize) stacks.push(Math.min(rule.stackSize, left))
  } else {
    stacks.push(N)
  }
  return (
    <div className={s.preview} aria-label={t('preferences.stackPreview', { cardCount: N, count: stacks.length, stackLabel: t(stacks.length === 1 ? 'preferences.stack' : 'preferences.stacks') })}>
      {stacks.map((n, i) => {
        const drawn = Math.min(n, Math.max(1, layers))
        return (
          <span key={i} className={s.previewStack} style={{ width: 14 + (drawn - 1) * 4 }}>
            {Array.from({ length: drawn }, (_, j) => (
              <span key={j} className={s.previewCard} style={{ left: j * 4 }} />
            ))}
            {n > 1 && <span className={s.previewCount}>{n}</span>}
          </span>
        )
      })}
    </div>
  )
}
