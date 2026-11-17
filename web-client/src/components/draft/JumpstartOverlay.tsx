import { useEffect, useMemo, useState, type ReactNode } from 'react'
import { ChatButton } from '@/components/messages/ChatButton'
import { useGameStore } from '@/store/gameStore'
import { getCdnArtCropUrl } from '@/utils/cardImages'
import { useHasHover } from '@/hooks/useHasHover'
import type { JumpstartOffer } from '@/types'
import { JumpstartPackExplorer } from './JumpstartPackExplorer'
import { ColorPips, CurveBars, RarityDot } from './JumpstartPackParts'
import { colorsLabel, faceCard, mergeColors, summarizePack, type PackSummary } from './jumpstartPack'
import styles from './JumpstartOverlay.module.css'
import { useTranslation } from 'react-i18next'

function PackArt({ pack, className }: { pack: JumpstartOffer; className: string | undefined }) {
  const face = faceCard(pack.cards)
  const src = face?.imageUri ? getCdnArtCropUrl(face.imageUri) ?? face.imageUri : null
  return src ? <img src={src} alt="" className={className} /> : <div className={className} />
}

/** One half of the deck: a chosen pack, the pack being considered, or an empty slot. */
function DeckSlot({ pack, label, tone, ghost, onView }: {
  pack: JumpstartOffer | undefined
  label: string
  tone: 'base' | 'pack'
  ghost: boolean
  onView?: (() => void) | undefined
}) {
  const { t } = useTranslation()
  const summary = useMemo(() => pack ? summarizePack(pack.cards) : null, [pack])
  if (!pack || !summary) return <div className={`${styles.slot} ${styles.slotEmpty}`}>
    <span className={styles.slotLabel}>{label}</span>
    <span className={styles.slotPlaceholder}>{t('draft.notChosen')}</span>
  </div>
  return <div className={`${styles.slot} ${tone === 'base' ? styles.slotBase : styles.slotPack} ${ghost ? styles.slotGhost : ''}`}>
    <PackArt pack={pack} className={styles.slotArt} />
    <div className={styles.slotText}>
      <span className={styles.slotLabel}>{ghost ? t('draft.considering') : label}</span>
      <strong>{pack.theme}</strong>
      <ColorPips colors={summary.colors} size={15} />
    </div>
    <div className={styles.slotActions}>
      {onView && <button className={styles.slotView} onClick={onView} aria-label={t('draft.viewPack', { theme: pack.theme })}>{t('draft.view')}</button>}
    </div>
  </div>
}

/**
 * The deck under construction — Arena's two-packet strip. Each slot holds the pack chosen from its
 * row; hovering an offer drops it into its row's slot as a ghost, so the colors, counts and curve
 * shown are those of the 40-card deck the pair would make.
 */
function DeckBar({ slots, ghosts, locked, onView, confirm }: {
  slots: readonly [JumpstartOffer | undefined, JumpstartOffer | undefined]
  ghosts: readonly [boolean, boolean]
  locked: boolean
  onView: (slot: 0 | 1) => void
  confirm: ReactNode
}) {
  const { t } = useTranslation()
  const [first, second] = slots
  const parts = useMemo(() => [first, second].filter((p): p is JumpstartOffer => p !== undefined)
    .map((p) => summarizePack(p.cards)), [first, second])
  const colors = mergeColors(...parts.map((p) => p.colors))
  const total = (key: keyof Pick<PackSummary, 'creatures' | 'spells' | 'lands'>) => parts.reduce((n, p) => n + p[key], 0)
  const complete = parts.length === 2
  return <section className={styles.deckBar} aria-label={t('draft.yourDeck')}>
    <div className={styles.slots}>
      <DeckSlot pack={first} label={t('draft.firstPack')} tone="base" ghost={ghosts[0]}
        onView={first && !ghosts[0] ? () => onView(0) : undefined} />
      <span className={styles.plus} aria-hidden>+</span>
      <DeckSlot pack={second} label={t('draft.secondPack')} tone="pack" ghost={ghosts[1]}
        onView={second && !ghosts[1] ? () => onView(1) : undefined} />
    </div>
    <div className={styles.deckSummary}>
      <div className={styles.deckStats}>
        <span className={styles.slotLabel}>{complete ? (locked ? t('draft.yourFortyCardDeck') : t('draft.togetherMake')) : t('draft.yourDeck')}</span>
        {parts.length > 0 ? <>
          <div className={styles.deckColors}><ColorPips colors={colors} size={16} /><strong>{colorsLabel(colors)}</strong></div>
          <span className={styles.deckCounts}>{t('draft.packComposition', { creatures: total('creatures'), spells: total('spells'), lands: total('lands') })}</span>
        </> : <>
          <div className={styles.deckColors}><strong className={styles.deckEmpty}>{t('draft.twoPacksFortyCards')}</strong></div>
          <span className={styles.deckCounts}>{t('draft.hoverToPreview')}</span>
        </>}
        {confirm}
      </div>
      {/* Always drawn, so previewing a pack never changes the bar's height under the cursor. */}
      <CurveBars curve={parts[parts.length - 1]?.curve ?? [0, 0, 0, 0, 0, 0]} base={complete ? parts[0]!.curve : undefined} />
    </div>
  </section>
}

function OfferCard({ offer, partner, tone, selected, disabled, onChoose, onExplore, onPreview }: {
  offer: JumpstartOffer
  /** The pack in the other slot, for the "Makes …" badge. */
  partner: JumpstartOffer | undefined
  tone: 'base' | 'pack'
  selected: boolean
  disabled: boolean
  onChoose: () => void
  onExplore: () => void
  onPreview: (previewing: boolean) => void
}) {
  const { t } = useTranslation()
  const summary = useMemo(() => summarizePack(offer.cards), [offer])
  const partnerColors = useMemo(() => partner ? summarizePack(partner.cards).colors : undefined, [partner])
  const paired = partnerColors && partner?.id !== offer.id ? mergeColors(partnerColors, summary.colors) : undefined
  const toneClass = tone === 'base' ? styles.packSelectedBase : styles.packSelectedPack
  return <article className={`${styles.pack} ${selected ? toneClass : ''}`} aria-current={selected || undefined}
    onMouseEnter={() => onPreview(true)} onMouseLeave={() => onPreview(false)} onFocus={() => onPreview(true)}>
    <button className={styles.artFrame} onClick={onExplore} aria-label={`See every card in ${offer.theme}`} tabIndex={-1}>
      <PackArt pack={offer} className={styles.art} />
      <span className={styles.artPips}><ColorPips colors={summary.colors} size={20} /></span>
      {paired && <span className={styles.pairBadge}>Makes {colorsLabel(paired)}</span>}
      <span className={styles.packLabel}>{t('draft.cardsIncludingLands')}</span>
    </button>
    <div className={styles.packBody}>
      <h2>{offer.theme}</h2>
      <ul className={styles.highlights} aria-label={t('draft.notableCards')}>
        {summary.highlights.slice(0, 3).map((card) => <li key={card.name}><RarityDot card={card} /><span>{card.name}</span></li>)}
      </ul>
      <div className={styles.packStats}>
        <CurveBars curve={summary.curve} />
        <span>{t('draft.packStats', { creatures: summary.creatures, spells: summary.spells })}</span>
      </div>
      <div className={styles.packActions}>
        <button className={styles.explore} onClick={onExplore}>{t('draft.explorePack')}</button>
        <button className={`${styles.choose} ${selected ? styles.chosen : ''}`} disabled={disabled} onClick={onChoose}
          aria-pressed={selected}>
          {selected ? t('draft.chosen') : t('draft.chooseTheme', { theme: offer.theme })}
        </button>
      </div>
    </div>
  </article>
}

type Row = 0 | 1
type Explorer = { from: Row | 'chosen'; index: number }

/**
 * Both halves of the deck are chosen on one screen: a pack from each row, then one confirm. Pack
 * legality, offers and deck assembly all come from the server.
 */
export function JumpstartOverlay() {
  const { t } = useTranslation()
  const lobby = useGameStore((s) => s.lobbyState)
  const pick = useGameStore((s) => s.pickJumpstartPacks)
  const leave = useGameStore((s) => s.leaveLobby)
  const error = useGameStore((s) => s.lastError)
  const connection = useGameStore((s) => s.connectionStatus)
  const [pending, setPending] = useState(false)
  const [choice, setChoice] = useState<[string | null, string | null]>([null, null])
  const [previewing, setPreviewing] = useState<{ row: Row; id: string } | null>(null)
  const [explorer, setExplorer] = useState<Explorer | null>(null)
  // Touch has no hover: previewing on tap would reflow the page under the finger.
  const hasHover = useHasHover()
  const state = lobby?.jumpstart
  const ready = (state?.selectedPacks.length ?? 0) === 2
  useEffect(() => { setPending(false) }, [ready, error, connection])
  useEffect(() => { setChoice([null, null]); setPreviewing(null); setExplorer(null) }, [lobby?.lobbyId])
  if (!state || !lobby) return null
  const rows: readonly [readonly JumpstartOffer[], readonly JumpstartOffer[]] = [state.offers, state.secondOffers ?? []]
  const chosen = state.selected ?? []
  const chosenIn = (row: Row) => rows[row].find((o) => o.id === choice[row])
  const previewIn = (row: Row) => previewing?.row === row ? rows[row].find((o) => o.id === previewing.id) : undefined
  const slots = ready ? [chosen[0], chosen[1]] as const
    : [previewIn(0) ?? chosenIn(0), previewIn(1) ?? chosenIn(1)] as const
  const ghosts = ready ? [false, false] as const
    : [!!previewIn(0) && previewIn(0) !== chosenIn(0), !!previewIn(1) && previewIn(1) !== chosenIn(1)] as const
  const setName = lobby.settings.setNames.join(' + ')
  const canPick = !pending && connection === 'connected'
  const select = (row: Row, offer: JumpstartOffer) => setChoice((prev) => {
    const next: [string | null, string | null] = [...prev]
    next[row] = prev[row] === offer.id ? null : offer.id
    return next
  })
  const first = chosenIn(0)
  const second = chosenIn(1)
  const confirm = () => {
    if (!canPick || !first || !second) return
    setPending(true)
    pick(first.id, second.id)
  }
  const explorerPacks = explorer?.from === 'chosen' ? chosen : explorer ? rows[explorer.from] : []
  const table = lobby.settings.gameMode === 'FREE_FOR_ALL' ? t('draft.freeForAll', { count: lobby.players.length }) : null
  const confirmButton = ready ? null : <button className={styles.confirm} disabled={!first || !second || !canPick} onClick={confirm}>
    {pending ? t('draft.buildingDeck') : first && second ? t('draft.playPacks', { first: first.theme, second: second.theme }) : t('draft.chooseBoth')}
  </button>
  return (
    <div className={styles.overlay}>
      <main className={styles.content}>
        <header className={styles.header}>
          <div>
            <span className={styles.eyebrow}>{t('draft.jumpIn')}</span>
            <span className={styles.setName}>{[setName, table].filter(Boolean).join(' · ')}</span>
          </div>
          <div className={styles.headerEnd}>
            <button className={styles.leave} onClick={leave}>{t('draft.leaveLobby')}</button>
            <ChatButton className={`${styles.leave} ${styles.chat}`} />
          </div>
        </header>
        <h1>{ready ? t('draft.twoThemesOneDeck') : t('draft.chooseTwoThemes')}</h1>
        <p className={styles.intro}>
          {ready ? t('draft.deckReady') : t('draft.choosePackPrompt')}
        </p>
        {ready && <span className={styles.srOnly} aria-label={t('draft.chosenThemes')}>{state.selectedPacks.map((name) => `✓ ${name}`).join(' ')}</span>}
        <div className={styles.deckBarDock}>
          <DeckBar slots={slots} ghosts={ghosts} locked={ready} confirm={confirmButton}
            onView={(slot) => ready ? setExplorer({ from: 'chosen', index: slot })
              : setExplorer({ from: slot, index: rows[slot].findIndex((o) => o.id === choice[slot]) })} />
        </div>
        {connection !== 'connected' && <p role="status" className={styles.intro}>{t('draft.reconnectingSaved')}</p>}
        {error && <p className={styles.error} role="alert">{error.message}</p>}
        {!ready && ([0, 1] as const).map((row) => <section key={row} aria-label={row === 0 ? t('draft.firstPack') : t('draft.secondPack')}>
          <h2 className={`${styles.rowHeading} ${row === 0 ? styles.rowBase : styles.rowPack}`}>
            {row === 0 ? t('draft.firstPack') : t('draft.secondPack')}
          </h2>
          <div className={styles.packs} aria-busy={pending}>
            {rows[row].map((offer, i) => <OfferCard key={`${row}-${offer.id}`}
              offer={offer} partner={slots[row === 0 ? 1 : 0]} tone={row === 0 ? 'base' : 'pack'}
              selected={choice[row] === offer.id} disabled={!canPick}
              onChoose={() => select(row, offer)}
              onExplore={() => setExplorer({ from: row, index: i })}
              onPreview={(on) => hasHover && setPreviewing((prev) =>
                on ? { row, id: offer.id } : prev?.row === row && prev.id === offer.id ? null : prev)} />)}
          </div>
        </section>)}
        {ready && <section className={styles.waiting} aria-label={t('draft.playerReadiness')}>
          <h2>{t('draft.atTable')}</h2>
          <ul>{lobby.players.map((player) => <li key={player.playerId}>
            <span>{player.playerName}{player.isAi ? ` · ${t('scenario.ai')}` : ''}</span>
            <span className={player.deckSubmitted ? styles.ready : styles.picking}>
              {player.deckSubmitted ? t('draft.readyToPlay') : t('draft.choosingThemes')}
            </span>
          </li>)}</ul>
          <p role="status">{t('draft.waitingForTable')}</p>
        </section>}
        <p className={styles.note}>{t('draft.jumpstartRules')}</p>
      </main>
      {explorer && explorerPacks[explorer.index] && <JumpstartPackExplorer
        packs={explorerPacks}
        index={explorer.index}
        onIndex={(index) => setExplorer({ ...explorer, index })}
        base={explorer.from === 'chosen' ? chosen[0] : slots[explorer.from === 0 ? 1 : 0]}
        onChoose={explorer.from === 'chosen' ? undefined : (offer) => {
          const row = explorer.from as Row
          setChoice((prev) => { const next: [string | null, string | null] = [...prev]; next[row] = offer.id; return next })
          setExplorer(null)
        }}
        chooseDisabled={!canPick}
        onClose={() => setExplorer(null)}
      />}
    </div>
  )
}
