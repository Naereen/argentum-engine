/**
 * The four axes, as the lobby's primary controls.
 *
 * Every lobby shows every value of each, whichever server implementation is backing it. What differs
 * is what a value *costs* — `axisChoices.ts` decides that, and this file only renders the answer:
 *
 * - selectable → a normal button
 * - selectable but only on the other lobby kind → a button marked `⇄`, which asks for confirmation
 *   before tearing this lobby down (plan § 4b v1)
 * - not implemented anywhere yet → **disabled with the reason attached**, not hidden
 *
 * Sub-options hang off their controlling axis: sealed and draft shape belong to **Cards**, while
 * deck legality belongs to **Rules** because that choice determines which formats are coherent.
 * This keeps "Format" from meaning two different things and remains the seam the settings groups
 * are cut along (`settingsGroups.ts`).
 *
 * Each axis therefore exports two pieces rather than one row: a **strip** of buttons in the group
 * header and an always-visible **body** of sub-options and captions. Reading order is unchanged: what deck → under what rules → at
 * what table → over how many games.
 */
import { useEffect } from 'react'
import {
  cardsLabel,
  cardsSeatCap,
  isCommanderLimited,
  legalityOptionsForRules,
  rulesTableBlock,
  type CardsAxis,
  type CardsKind,
  type EventAxis,
  type RulesAxis,
  type TableAxis,
} from './axes'
import {
  RECREATE_NOTE,
  cardsChoices,
  eventChoices,
  recreateTargetLabel,
  rulesChoices,
  tableChoices,
  type AxisChoice,
  type RecreateSpec,
} from './axisChoices'
import type { UnifiedLobbyView } from './lobbyViewModel'
import type { LobbyCommands } from './useLobbyCommands'
import styles from '../ui/GameUI.module.css'
import { useTranslation } from 'react-i18next'

const CARDS_CAPTIONS: Record<CardsKind, 'lobbyAxes.cardsBringDeck' | 'lobbyAxes.cardsRandom' | 'lobbyAxes.cardsMomir' | 'lobbyAxes.cardsJumpIn' | 'lobbyAxes.cardsSealed' | 'lobbyAxes.cardsDraft'> = {
  BRING_A_DECK: 'lobbyAxes.cardsBringDeck',
  RANDOM:
    'lobbyAxes.cardsRandom',
  MOMIR: 'lobbyAxes.cardsMomir',
  JUMP_IN: 'lobbyAxes.cardsJumpIn',
  SEALED: 'lobbyAxes.cardsSealed',
  DRAFT: 'lobbyAxes.cardsDraft',
}

const RULES_CAPTIONS: Record<RulesAxis, 'lobbyAxes.rulesStandard' | 'lobbyAxes.rulesCommander'> = {
  STANDARD: 'lobbyAxes.rulesStandard',
  COMMANDER: 'lobbyAxes.rulesCommander',
}

const TABLE_CAPTIONS: Record<TableAxis, 'lobbyAxes.tableOneVsOne' | 'lobbyAxes.tableFreeForAll' | 'lobbyAxes.tableTwoHeadedGiant' | 'lobbyAxes.tableTeamVsTeam'> = {
  ONE_V_ONE: 'lobbyAxes.tableOneVsOne',
  FREE_FOR_ALL: 'lobbyAxes.tableFreeForAll',
  TWO_HEADED_GIANT:
    'lobbyAxes.tableTwoHeadedGiant',
  TEAM_VS_TEAM:
    'lobbyAxes.tableTeamVsTeam',
}

interface AxisProps {
  view: UnifiedLobbyView
  commands: LobbyCommands
  /** A value that lives on the other lobby kind — confirmed by the screen before it happens. */
  onRecreate: (spec: RecreateSpec) => void
}

/* ── Strips: always visible, in the group headers ─────────────────────────── */

export function CardsAxisStrip({ view, commands, onRecreate }: AxisProps) {
  return <AxisButtons choices={cardsChoices(view)} onPick={commands.setCards} onRecreate={onRecreate} />
}

export function RulesAxisStrip({ view, commands, onRecreate }: AxisProps) {
  return <AxisButtons choices={rulesChoices(view)} onPick={commands.setRules} onRecreate={onRecreate} />
}

export function TableAxisStrip({ view, commands, onRecreate }: AxisProps) {
  return <AxisButtons choices={tableChoices(view)} onPick={commands.setTable} onRecreate={onRecreate} />
}

export function EventAxisStrip({ view, onRecreate }: AxisProps) {
  return (
    <AxisButtons
      choices={eventChoices(view)}
      // Event has no directly-settable second value on either kind: server-side
      // `gameMode = TOURNAMENT` *is* the bracket. Every cross-value pick recreates.
      onPick={() => {}}
      onRecreate={onRecreate}
    />
  )
}

/* ── Bodies: always-visible sub-options and captions ──────────────────── */

export function CardsAxisBody({ view, commands, jumpstart = false }: Omit<AxisProps, 'onRecreate'> & { jumpstart?: boolean }) {
  const { t } = useTranslation()
  const cards = view.axes.cards

  return (
    <>
      <div className={styles.settingsRow}>
        <span className={styles.settingsLabel} />
        <div className={styles.variantCaption}>{t(jumpstart ? 'lobbyAxes.jumpstartCaption' : CARDS_CAPTIONS[cards.kind])}</div>
      </div>

      {/* Cards → Sealed: which sealed shape. */}
      {cards.kind === 'SEALED' && (
        <div className={`${styles.settingsRow} ${styles.settingsRowSub}`}>
          <span className={styles.settingsLabel}>{t('lobbyAxes.sealedShape')}</span>
          <div className={styles.variantGroup}>
            <div className={styles.settingsButtons}>
              <ShapeButton
                label={t('lobbyAxes.standard')}
                active={cards.shape === 'STANDARD'}
                onClick={() => commands.setCardsShape('SEALED')}
              />
              <ShapeButton
                label={t('lobbyAxes.commander')}
                active={cards.shape === 'COMMANDER'}
                blocked={shapeBlock(view, { kind: 'SEALED', shape: 'COMMANDER' })}
                onClick={() => commands.setCardsShape('COMMANDER_SEALED')}
              />
            </div>
            <div className={styles.variantCaption}>
              {t(jumpstart ? 'lobbyAxes.jumpstartPackCaption' : cards.shape === 'COMMANDER'
                ? 'lobbyAxes.sealedCommanderCaption'
                : 'lobbyAxes.sealedStandardCaption')}
            </div>
          </div>
        </div>
      )}

      {/* Cards → Draft: which of the four draft shapes. */}
      {cards.kind === 'DRAFT' && (
        <div className={`${styles.settingsRow} ${styles.settingsRowSub}`}>
          <span className={styles.settingsLabel}>{t('lobbyAxes.draftShape')}</span>
          <div className={styles.variantGroup}>
            <div className={styles.settingsButtons}>
              <ShapeButton
                label={t('lobbyAxes.booster')}
                draft
                active={cards.shape === 'BOOSTER'}
                blocked={shapeBlock(view, { kind: 'DRAFT', shape: 'BOOSTER' })}
                onClick={() => commands.setCardsShape('DRAFT')}
              />
              <ShapeButton
                label={t('lobbyAxes.winston')}
                draft
                active={cards.shape === 'WINSTON'}
                blocked={shapeBlock(view, { kind: 'DRAFT', shape: 'WINSTON' })}
                onClick={() => commands.setCardsShape('WINSTON_DRAFT')}
              />
              <ShapeButton
                label={t('lobbyAxes.grid')}
                draft
                active={cards.shape === 'GRID'}
                blocked={shapeBlock(view, { kind: 'DRAFT', shape: 'GRID' })}
                onClick={() => commands.setCardsShape('GRID_DRAFT')}
              />
              <ShapeButton
                label={t('lobbyAxes.commander')}
                draft
                active={cards.shape === 'COMMANDER'}
                blocked={shapeBlock(view, { kind: 'DRAFT', shape: 'COMMANDER' })}
                onClick={() => commands.setCardsShape('COMMANDER_DRAFT')}
              />
            </div>
            <div className={styles.variantCaption}>
              {t(cards.shape === 'COMMANDER'
                ? 'lobbyAxes.draftCommanderCaption'
                : cards.shape === 'WINSTON' ? 'lobbyAxes.draftWinstonCaption'
                : cards.shape === 'GRID' ? 'lobbyAxes.draftGridCaption'
                : 'lobbyAxes.draftBoosterCaption')}
            </div>
          </div>
        </div>
      )}
    </>
  )
}

export function RulesAxisBody({ view, commands }: { view: UnifiedLobbyView; commands: LobbyCommands }) {
  const { t } = useTranslation()
  // A lobby can be sitting on a Rules × Table contradiction (the server defaults Rules to Commander
  // when the host picks commander deck legality, whatever the table). Say so here rather than only
  // on a disabled Start button.
  const rulesConflict = rulesTableBlock(view.axes.rules, view.axes.table)
  const cards = view.axes.cards
  const legalityOptions = legalityOptionsForRules(view.axes.rules, view.axes.table)

  // Standard rules may leave legality open ("Any deck": 60+ cards, at most four of each nonbasic,
  // no format list). Commander rules can't — commander legality is what anchors colour identity.
  const allowsAnyDeck = view.axes.rules === 'STANDARD'

  // Rules own deck construction. If the host changes Rules, immediately replace a now-incoherent
  // legality; Commander rules always carry one of the commander-aware formats.
  useEffect(() => {
    if (cards.kind !== 'BRING_A_DECK') return
    if (legalityOptions.length === 0) return
    if (cards.legality === null && allowsAnyDeck) return
    if (cards.legality && legalityOptions.some((option) => option.value === cards.legality)) return
    commands.setLegality(view.axes.rules === 'COMMANDER' ? 'COMMANDER' : null)
  }, [allowsAnyDeck, cards, commands, legalityOptions, view.axes.rules])

  return (
    <>
      <div className={styles.settingsRow}>
        <span className={styles.settingsLabel} />
        <div className={styles.variantCaption}>
          {rulesConflict ?? t(RULES_CAPTIONS[view.axes.rules])}
        </div>
      </div>
      {cards.kind === 'BRING_A_DECK' && (
        <div className={`${styles.settingsRow} ${styles.settingsRowSub}`}>
          <span className={styles.settingsLabel}>{t('lobbyAxes.deckLegality')}</span>
          <select
            value={cards.legality ?? ''}
            onChange={(e) => commands.setLegality((e.target.value || null) as never)}
            className={styles.settingsSelect}
            title={t('lobbyAxes.submittedDeckLegalityHint')}
          >
            {allowsAnyDeck && <option value="">{t('lobbyAxes.anyDeck')}</option>}
            {legalityOptions.map((format) => (
              <option key={format.value} value={format.value}>{format.label}</option>
            ))}
          </select>
        </div>
      )}
    </>
  )
}

export function TableAxisBody({ view }: { view: UnifiedLobbyView }) {
  const { t } = useTranslation()
  return (
    <div className={styles.settingsRow}>
      <span className={styles.settingsLabel} />
      <div className={styles.variantCaption}>{t(TABLE_CAPTIONS[view.axes.table])}</div>
    </div>
  )
}

export function EventAxisBody({ view }: { view: UnifiedLobbyView }) {
  const caption = eventCaption(view)
  if (!caption) return null
  return (
    <div className={styles.settingsRow}>
      <span className={styles.settingsLabel} />
      <div className={styles.variantCaption}>{caption}</div>
    </div>
  )
}

/** The caption under Event: what the value you *didn't* pick would take. Empty when there is
 *  nothing to say, which is how `LobbyScreen` knows the group has no body. */
export function eventCaption(view: UnifiedLobbyView): string {
  const other = eventChoices(view).find((c) => !c.selected)
  if (!other) return ''
  switch (other.availability.kind) {
    case 'BLOCKED':
      return other.availability.reason
    case 'RECREATE':
      return `“${other.label}” runs on a different lobby — picking it starts a fresh one.`
    case 'DIRECT':
      return ''
  }
}

function AxisButtons<V extends CardsKind | RulesAxis | TableAxis | EventAxis>({
  choices,
  onPick,
  onRecreate,
}: {
  choices: AxisChoice<V>[]
  onPick: (value: V) => void
  onRecreate: (spec: RecreateSpec) => void
}) {
  return (
    <div className={styles.settingsButtons}>
      {choices.map((choice) => {
        const a = choice.availability
        const recreates = a.kind === 'RECREATE'
        return (
          <button
            key={choice.value}
            type="button"
            disabled={a.kind === 'BLOCKED'}
            aria-pressed={choice.selected}
            onClick={() => {
              if (a.kind === 'RECREATE') onRecreate(a.spec)
              else if (a.kind === 'DIRECT' && !choice.selected) onPick(choice.value)
            }}
            className={[
              styles.settingsButton,
              choice.selected ? styles.settingsButtonActive : '',
              recreates ? styles.settingsButtonRecreate : '',
            ].filter(Boolean).join(' ')}
            title={
              a.kind === 'BLOCKED' ? a.reason
                : a.kind === 'RECREATE'
                  ? `Starts a new lobby: ${recreateTargetLabel(a.spec)}. ${RECREATE_NOTE[a.spec.to]} Your invite code will change.`
                  : ''
            }
            data-testid={`axis-choice-${choice.value.toLowerCase().replace(/_/g, '-')}`}
          >
            {choice.label}
            {recreates && <span className={styles.settingsButtonRecreateMark} aria-hidden> ⇄</span>}
          </button>
        )
      })}
    </div>
  )
}

/**
 * Why a Cards sub-shape can't be picked here, or null.
 *
 * Two reasons, both facts shared with the landing wizard rather than numbers written at the call
 * site: the shape seats fewer players than this lobby is holding ({@link cardsSeatCap}), or it would
 * default the Rules axis to Commander at a table that can't have it ({@link rulesTableBlock} — the
 * one statement of that rule, so this row can never offer what the Rules row refuses).
 */
function shapeBlock(view: UnifiedLobbyView, cards: CardsAxis): string | null {
  const cap = cardsSeatCap(cards)
  if (view.players.length > cap) {
    return `${cardsLabel(cards)} seats at most ${cap} — this lobby has ${view.players.length}`
  }
  // Picking a Commander pack shape defaults Rules to Commander, so it inherits both of the rules'
  // own blocks; a non-commander shape asks them of the rules the lobby already has.
  const wouldRun: RulesAxis = isCommanderLimited(cards) ? 'COMMANDER' : view.axes.rules
  return rulesTableBlock(wouldRun, view.axes.table)
}

function ShapeButton({
  label,
  active,
  blocked = null,
  draft = false,
  onClick,
}: {
  label: string
  active: boolean
  blocked?: string | null
  draft?: boolean
  onClick: () => void
}) {
  return (
    <button
      type="button"
      disabled={blocked !== null}
      aria-pressed={active}
      onClick={() => { if (blocked === null && !active) onClick() }}
      className={[
        styles.settingsButton,
        active ? styles.settingsButtonActive : '',
        active && draft ? styles.settingsButtonDraft : '',
      ].filter(Boolean).join(' ')}
      title={blocked ?? ''}
    >
      {label}
    </button>
  )
}
