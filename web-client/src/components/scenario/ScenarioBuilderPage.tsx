/**
 * Scenario Builder / Tester.
 *
 * Two panes, resizable: the deckbuilder's card browser on the left (full Scryfall-style query
 * language, filter chips, set picker, image grid, hover previews) and an editable board on the
 * right. Cards are dragged from the browser into any seat's battlefield / hand / graveyard /
 * exile / library / command zone, or clicked to drop into the currently targeted zone; cards
 * already on the board drag between zones. Battlefield permanents open an editor for tapped
 * state, counters, attachments, and pre-set ETB choices.
 *
 * Then play it against yourself (hotseat), the AI, or as two players. Scenarios round-trip
 * through an encoded `?s=` share URL, a JSON drawer, and `.json` files; a `?replay=&frame=`
 * link jumps straight into a stored replay position.
 */
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { CardBrowser, useCardCatalog, type CardDragPayload } from '@/components/deckbuilder/browser'
import type { CardSummary } from '@/components/deckbuilder/cardFilter'
import { CardEditorModal } from './CardEditorModal'
import { ScenarioBoard, type BoardTarget } from './ScenarioBoard'
import { CustomCardPanel } from './CustomCardPanel'
import { customCardSummary } from './customCardJson'
import {
  PHASES,
  addCardToZone,
  addCustomCard,
  clearSeat,
  emptyBuilderState,
  emptySeat,
  fromSpec,
  moveCard,
  placedCounts,
  removeCardAt,
  removeCustomCard,
  toSpec,
  updateBattlefieldCard,
  type BuilderSeat,
  type BuilderState,
  type CustomCard,
} from './builderState'
import { useUndoable } from './builderHistory'
import {
  SCENARIO_FRAME_PARAM,
  SCENARIO_REPLAY_PARAM,
  SCENARIO_SHARE_PARAM,
  buildScenarioUrl,
  decodeScenario,
  encodeScenario,
} from './shareScenario'
import {
  type ScenarioCreateResponse,
  type ScenarioMode,
  type ScenarioSpec,
  type ScenarioZone,
  SCENARIO_ZONES,
} from './types'
import { BrandMark, PLAIN_BACKDROP, pageStyles } from '@/components/ui/PageShell'
import styles from './ScenarioBuilder.module.css'
import { useTranslation } from 'react-i18next'

/** A plain dark backdrop: the builder is a board full of cards, and card art behind it is noise. */
const pageBackground = { backgroundImage: PLAIN_BACKDROP }

const SPLIT_STORAGE_KEY = 'scenarioBuilder.browserWidth'
const DEFAULT_SPLIT = 520
const MIN_BROWSER = 320
const MIN_BOARD = 380

/** Copies added per click in the browser — filling a library one card at a time is tedious. */
const COPY_OPTIONS = [1, 2, 3, 4, 10, 20, 40]

export function ScenarioBuilderPage() {
  const { t } = useTranslation()
  const [searchParams, setSearchParams] = useSearchParams()
  const { catalog, setInfos, index, loading, error: catalogError } = useCardCatalog()

  const { state, commit, reset, undo, redo, canUndo, canRedo } = useUndoable<BuilderState>(
    emptyBuilderState,
  )

  const [target, setTarget] = useState<BoardTarget>(() => ({
    seatId: '',
    zone: 'battlefield',
  }))
  const [copies, setCopies] = useState(1)
  const [editing, setEditing] = useState<{ seatId: string; index: number } | null>(null)

  const [jsonText, setJsonText] = useState('')
  const [jsonOpen, setJsonOpen] = useState(false)
  const [customOpen, setCustomOpen] = useState(false)
  const [status, setStatus] = useState<string | null>(null)
  const [errors, setErrors] = useState<string[]>([])
  const [starting, setStarting] = useState(false)
  const [snapshotLoading, setSnapshotLoading] = useState(false)

  // Keep the click-target pointed at a seat that exists (seats are added/removed, and loading a
  // scenario mints fresh seat ids).
  useEffect(() => {
    if (state.seats.some((s) => s.id === target.seatId)) return
    const first = state.seats[0]
    if (first) setTarget({ seatId: first.id, zone: target.zone })
  }, [state.seats, target.seatId, target.zone])

  const targetSeat = state.seats.find((s) => s.id === target.seatId) ?? state.seats[0]

  // --- board mutations -----------------------------------------------------

  const addCard = useCallback(
    (name: string, seatId: string, zone: ScenarioZone, count = 1) => {
      commit((prev) => addCardToZone(prev, seatId, zone, name, count))
    },
    [commit],
  )

  const handleBrowserAdd = useCallback(
    (card: CardSummary) => {
      if (!targetSeat) return
      addCard(card.name, targetSeat.id, target.zone, copies)
      setStatus(t(copies > 1 ? 'scenario.cardsAdded' : 'scenario.cardAdded', {
        count: copies,
        name: card.name,
        seat: targetSeat.name,
        zone: t(`scenario.zoneNames.${target.zone}`),
      }))
    },
    [addCard, copies, target.zone, targetSeat, t],
  )

  const handleAddCustomCard = useCallback(
    (card: CustomCard) => {
      commit((prev) => addCustomCard(prev, card))
      setStatus(t('scenario.customCardAdded', { name: card.name }))
    },
    [commit, t],
  )

  const handleRemoveCustomCard = useCallback(
    (name: string) => {
      commit((prev) => removeCustomCard(prev, name))
      setStatus(t('scenario.customCardRemoved', { name }))
    },
    [commit, t],
  )

  /** Shift/right-click in the browser grid removes the most recent copy of that card. */
  const handleBrowserRemove = useCallback(
    (name: string) => {
      commit((prev) => {
        for (const seat of prev.seats) {
          for (const zone of ['battlefield', 'hand', 'graveyard', 'exile', 'library', 'commanders'] as ScenarioZone[]) {
            const names =
              zone === 'battlefield' ? seat.battlefield.map((c) => c.name) : seat[zone]
            const idx = names.lastIndexOf(name)
            if (idx >= 0) return removeCardAt(prev, seat.id, zone, idx)
          }
        }
        return prev
      })
    },
    [commit],
  )

  const handleDropCard = useCallback(
    (payload: CardDragPayload, seatId: string, zone: ScenarioZone) => {
      if (payload.source === 'zone' && payload.zoneRef) {
        const [fromSeatId, fromZone, rawIndex] = payload.zoneRef.split('|')
        const fromIndex = Number(rawIndex)
        if (fromSeatId && fromZone && Number.isInteger(fromIndex)) {
          if (fromSeatId === seatId && fromZone === zone) return
          commit((prev) =>
            moveCard(
              prev,
              { seatId: fromSeatId, zone: fromZone as ScenarioZone, index: fromIndex },
              { seatId, zone },
            ),
          )
          return
        }
      }
      addCard(payload.name, seatId, zone)
    },
    [addCard, commit],
  )

  const handleRemove = useCallback(
    (seatId: string, zone: ScenarioZone, index: number) => {
      commit((prev) => removeCardAt(prev, seatId, zone, index))
    },
    [commit],
  )

  const handleQuickPatch = useCallback(
    (seatId: string, index: number, patch: Parameters<typeof updateBattlefieldCard>[3]) => {
      commit((prev) => updateBattlefieldCard(prev, seatId, index, patch))
    },
    [commit],
  )

  const handleSeatPatch = useCallback(
    (seatId: string, patch: Partial<Pick<BuilderSeat, 'name' | 'life'>>) => {
      commit((prev) => ({
        ...prev,
        seats: prev.seats.map((s) => (s.id === seatId ? { ...s, ...patch } : s)),
      }))
    },
    [commit],
  )

  const handleClearZone = useCallback(
    (seatId: string, zone: ScenarioZone) => {
      commit((prev) => ({
        ...prev,
        seats: prev.seats.map((s) =>
          s.id === seatId ? { ...s, [zone]: [] as never } : s,
        ),
      }))
    },
    [commit],
  )

  const handleClearSeat = useCallback(
    (seatId: string) => commit((prev) => clearSeat(prev, seatId)),
    [commit],
  )

  const handleRemoveSeat = useCallback(
    (seatId: string) => {
      commit((prev) => {
        if (prev.seats.length <= 2) return prev
        const seats = prev.seats.filter((s) => s.id !== seatId)
        return {
          ...prev,
          seats,
          activePlayer: Math.min(prev.activePlayer, seats.length),
          mode: seats.length > 2 ? 'SELF' : prev.mode,
        }
      })
    },
    [commit],
  )

  const handleAddSeat = useCallback(() => {
    commit((prev) => ({
      ...prev,
      seats: [...prev.seats, emptySeat(`Player ${prev.seats.length + 1}`)],
      // Pods of 3-4 seats are hotseat-only (the server restricts AI / two-player to duels).
      mode: 'SELF',
    }))
  }, [commit])

  // --- undo/redo shortcuts -------------------------------------------------

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (!(e.metaKey || e.ctrlKey) || e.key.toLowerCase() !== 'z') return
      const el = e.target as HTMLElement | null
      const tag = el?.tagName
      if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || el?.isContentEditable) return
      e.preventDefault()
      if (e.shiftKey) redo()
      else undo()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [undo, redo])

  // --- split pane ----------------------------------------------------------

  const [browserWidth, setBrowserWidth] = useState(() => {
    const stored = typeof window !== 'undefined' ? window.localStorage.getItem(SPLIT_STORAGE_KEY) : null
    const parsed = stored ? Number(stored) : NaN
    return Number.isFinite(parsed) ? parsed : DEFAULT_SPLIT
  })
  const [dragging, setDragging] = useState(false)
  useEffect(() => {
    if (!dragging) return
    const onMove = (e: MouseEvent) => {
      const max = Math.max(MIN_BROWSER, window.innerWidth - MIN_BOARD)
      setBrowserWidth(Math.min(max, Math.max(MIN_BROWSER, e.clientX)))
    }
    const onUp = () => setDragging(false)
    window.addEventListener('mousemove', onMove)
    window.addEventListener('mouseup', onUp)
    // Kill text selection while dragging the divider.
    document.body.style.userSelect = 'none'
    return () => {
      window.removeEventListener('mousemove', onMove)
      window.removeEventListener('mouseup', onUp)
      document.body.style.userSelect = ''
    }
  }, [dragging])
  useEffect(() => {
    window.localStorage.setItem(SPLIT_STORAGE_KEY, String(browserWidth))
  }, [browserWidth])

  // --- exact-snapshot jump-in on load (?replay=<id>&frame=<n>) -------------
  // A snapshot link references a stored replay frame (kept short); inject it server-side and
  // jump straight into the exact position (it isn't editable in the builder).

  // Drive a from-state / from-replay-frame response: on success, jump into the new session.
  const jumpInto = useCallback(async (req: Promise<Response>) => {
    try {
      const res = await req
      if (!res.ok) {
        setSnapshotLoading(false)
        setStatus(t('scenario.snapshotFailed'))
        return
      }
      const data = (await res.json()) as ScenarioCreateResponse
      const human =
        data.player1.token && data.player1.token !== '(AI)' ? data.player1 : data.player2
      window.location.href = `/?token=${encodeURIComponent(human.token)}`
    } catch {
      setSnapshotLoading(false)
      setStatus(t('scenario.snapshotFailed'))
    }
  }, [t])

  const snapshotLoadedRef = useRef(false)
  useEffect(() => {
    if (snapshotLoadedRef.current) return
    const replay = searchParams.get(SCENARIO_REPLAY_PARAM)
    const frame = searchParams.get(SCENARIO_FRAME_PARAM)
    if (!replay || frame == null) return
    snapshotLoadedRef.current = true
    setSnapshotLoading(true)
    void jumpInto(
      fetch('/api/scenarios/from-replay-frame', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ gameId: replay, frame: Number(frame) }),
      }),
    )
  }, [searchParams, jumpInto])

  // --- decode shared scenario on load (?s=<code>) --------------------------
  const applySpec = useCallback(
    (spec: ScenarioSpec) => {
      const next = fromSpec(spec)
      reset(next)
      const first = next.seats[0]
      if (first) setTarget({ seatId: first.id, zone: 'battlefield' })
    },
    [reset],
  )

  const sharedLoadedRef = useRef(false)
  useEffect(() => {
    if (sharedLoadedRef.current) return
    const code = searchParams.get(SCENARIO_SHARE_PARAM)
    if (!code) return
    sharedLoadedRef.current = true
    void decodeScenario(code).then((spec) => {
      if (spec) {
        applySpec(spec)
        setStatus(t('scenario.sharedLoaded'))
      } else {
        setStatus(t('scenario.sharedLoadFailed'))
      }
      // Strip the param so a refresh doesn't reload it over edits.
      setSearchParams(
        (prev) => {
          const next = new URLSearchParams(prev)
          next.delete(SCENARIO_SHARE_PARAM)
          return next
        },
        { replace: true },
      )
    })
  }, [searchParams, setSearchParams, applySpec, t])

  // --- actions -------------------------------------------------------------

  const currentSpec = useCallback(() => toSpec(state), [state])

  const handleLoadJson = useCallback(() => {
    try {
      const parsed = JSON.parse(jsonText) as ScenarioSpec
      applySpec(parsed)
      setStatus(t('scenario.jsonLoaded'))
      setErrors([])
    } catch {
      setStatus(t('scenario.invalidJson'))
    }
  }, [jsonText, applySpec, t])

  const handleShowJson = useCallback(() => {
    setJsonText(JSON.stringify(currentSpec(), null, 2))
    setJsonOpen(true)
  }, [currentSpec])

  // Load a file: a downloaded full-state snapshot (has `entities`/`zones`) jumps straight in;
  // a name-based scenario file (like the manual-scenarios JSONs) loads into the editable builder.
  const fileInputRef = useRef<HTMLInputElement>(null)
  const handleLoadFile = useCallback(
    (file: File) => {
      void (async () => {
        const text = await file.text()
        let parsed: unknown
        try {
          parsed = JSON.parse(text)
        } catch {
          setStatus(t('scenario.invalidJsonFile'))
          return
        }
        if (parsed && typeof parsed === 'object' && 'entities' in parsed && 'zones' in parsed) {
          // Full-state snapshot → inject server-side and jump into the exact position.
          setSnapshotLoading(true)
          void jumpInto(
            fetch('/api/scenarios/from-state', {
              method: 'POST',
              headers: { 'Content-Type': 'text/plain' },
              body: text,
            }),
          )
        } else {
          // Name-based scenario → load into the editable builder.
          applySpec(parsed as ScenarioSpec)
          setStatus(t('scenario.fileLoaded'))
          setErrors([])
        }
      })()
    },
    [applySpec, jumpInto, t],
  )

  const handleShare = useCallback(async () => {
    const code = await encodeScenario(currentSpec())
    const url = buildScenarioUrl(window.location.origin, code)
    try {
      await navigator.clipboard.writeText(url)
      setStatus(t('scenario.linkCopied'))
    } catch {
      window.prompt(t('scenario.copyScenarioPrompt'), url)
    }
  }, [currentSpec, t])

  const handleStart = useCallback(async () => {
    setStarting(true)
    setErrors([])
    setStatus(t('scenario.creating'))
    try {
      const res = await fetch('/api/scenarios', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(currentSpec()),
      })
      if (!res.ok) {
        const body: unknown = await res.json().catch(() => null)
        const msgs =
          body && typeof body === 'object' && Array.isArray((body as { errors?: unknown }).errors)
            ? ((body as { errors: string[] }).errors)
            : [t('scenario.requestFailed', { status: res.status })]
        setErrors(msgs)
        setStatus(null)
        setStarting(false)
        return
      }
      const data = (await res.json()) as ScenarioCreateResponse
      // Pick the seat this client should connect as: SELF/AI hand off a single human token.
      const human =
        data.player1.token && data.player1.token !== '(AI)' ? data.player1 : data.player2
      // Full navigation so the app makes a clean token-based connect to the new session.
      window.location.href = `/?token=${encodeURIComponent(human.token)}`
    } catch (e: unknown) {
      setErrors([e instanceof Error ? e.message : t('scenario.createFailed')])
      setStatus(null)
      setStarting(false)
    }
  }, [currentSpec, t])

  // --- derived -------------------------------------------------------------

  const counts = useMemo(() => placedCounts(state), [state])

  /**
   * The catalog, plus a summary per custom card read from its own pasted JSON — art, mana symbols,
   * type line, P/T. Every surface that draws a card here already reads this index, so a compiled
   * custom card renders like any other without those components knowing it is one.
   */
  const cardIndex = useMemo(() => {
    if (state.customCards.length === 0) return index
    const merged = { ...index }
    for (const card of state.customCards) {
      const summary = customCardSummary(card.json)
      if (summary) merged[card.name] = summary
    }
    return merged
  }, [index, state.customCards])

  const creatureTypes = useMemo(() => {
    const out = new Set<string>()
    for (const c of catalog) {
      if (c.cardTypes.includes('CREATURE')) for (const s of c.subtypes) out.add(s)
    }
    return [...out].sort()
  }, [catalog])

  const editingSeat = editing ? state.seats.find((s) => s.id === editing.seatId) : undefined
  const editingCard = editing && editingSeat ? editingSeat.battlefield[editing.index] : undefined

  // --- render --------------------------------------------------------------

  if (snapshotLoading) {
    return (
      <div className={styles.page} style={pageBackground}>
        <div className={styles.loading}>{t('scenario.loadingSnapshot')}</div>
      </div>
    )
  }

  return (
    <div className={styles.page} style={pageBackground}>
      <div className={styles.topbar}>
        <div className={styles.titleGroup}>
          <Link to="/" className={pageStyles.brand} aria-label={t('scenario.home')}>
            <BrandMark />
            <span className={pageStyles.brandText}>Argentum</span>
          </Link>
          <span className={pageStyles.crumbSep} aria-hidden>/</span>
          <h1 className={styles.title}>{t('scenario.title')}</h1>
          <button
            className={styles.iconBtn}
            onClick={undo}
            disabled={!canUndo}
            title={`${t('scenario.undo')} (⌘Z)`}
            aria-label={t('scenario.undo')}
          >
            ↶
          </button>
          <button
            className={styles.iconBtn}
            onClick={redo}
            disabled={!canRedo}
            title={`${t('scenario.redo')} (⇧⌘Z)`}
            aria-label={t('scenario.redo')}
          >
            ↷
          </button>
        </div>
        <div className={styles.spacer} />
        <input
          ref={fileInputRef}
          type="file"
          accept="application/json,.json"
          style={{ display: 'none' }}
          onChange={(e) => {
            const file = e.target.files?.[0]
            if (file) handleLoadFile(file)
            e.target.value = ''
          }}
        />
        <div className={styles.actions}>
          <ActionButton
            label={t('scenario.loadFile')}
            hint={t('scenario.loadFileHint')}
            onClick={() => fileInputRef.current?.click()}
          />
          <ActionButton label={t('scenario.viewJson')} hint={t('scenario.copyScenario')} onClick={handleShowJson} />
          <ActionButton label={t('scenario.share')} hint={t('scenario.copyEditableLink')} onClick={() => void handleShare()} />
          <ActionButton
            label={starting ? t('scenario.starting') : t('scenario.start')}
            hint={t('scenario.startHint')}
            primary
            disabled={starting}
            onClick={() => void handleStart()}
          />
        </div>
      </div>

      {(status || errors.length > 0) && (
        <div className={styles.statusBar}>
          {errors.length > 0 ? (
            <ul className={styles.errorList}>
              {errors.map((e, i) => (
                <li key={i}>{e}</li>
              ))}
            </ul>
          ) : (
            <span className={styles.statusOk}>{status}</span>
          )}
        </div>
      )}

      <div className={styles.body}>
        <div className={styles.browserPane} style={{ width: browserWidth, flex: `0 0 ${browserWidth}px` }}>
          <CardBrowser
            catalog={catalog}
            setInfos={setInfos}
            loading={loading}
            error={catalogError}
            counts={counts}
            onAdd={handleBrowserAdd}
            onRemove={handleBrowserRemove}
            actionHint={
              <>
                <kbd>{t('scenario.click')}</kbd> {t('scenario.addTo').toLowerCase()} · <kbd>{t('scenario.drag')}</kbd> to any zone
              </>
            }
            header={
              <div className={styles.targetRow}>
                <span className={styles.smallLabel}>{t('scenario.addTo')}</span>
                <select
                  className={styles.select}
                  value={target.seatId}
                  onChange={(e) => setTarget({ ...target, seatId: e.target.value })}
                  aria-label={t('scenario.targetSeat')}
                >
                  {state.seats.map((s) => (
                    <option key={s.id} value={s.id}>
                      {s.name}
                    </option>
                  ))}
                </select>
                <select
                  className={styles.select}
                  value={target.zone}
                  onChange={(e) => setTarget({ ...target, zone: e.target.value as ScenarioZone })}
                  aria-label={t('scenario.targetZone')}
                >
                  {SCENARIO_ZONES.map((z) => (
                    <option key={z} value={z}>
                      {t(`scenario.zoneNames.${z}`)}
                    </option>
                  ))}
                </select>
                <select
                  className={styles.select}
                  value={copies}
                  onChange={(e) => setCopies(Number(e.target.value))}
                  aria-label={t('scenario.copiesPerClick')}
                >
                  {COPY_OPTIONS.map((n) => (
                    <option key={n} value={n}>
                      ×{n}
                    </option>
                  ))}
                </select>
              </div>
            }
          />
        </div>

        <div
          className={dragging ? styles.splitterActive : styles.splitter}
          onMouseDown={() => setDragging(true)}
          role="separator"
          aria-orientation="vertical"
          aria-label={t('scenario.resizeBrowser')}
        />

        <div className={styles.boardPane}>
          <div className={styles.boardBar}>
            <label className={styles.field}>
              <span className={styles.smallLabel}>{t('scenario.opponent')}</span>
              <select
                className={styles.select}
                value={state.mode}
                disabled={state.seats.length > 2}
                onChange={(e) => commit((prev) => ({ ...prev, mode: e.target.value as ScenarioMode }))}
              >
                <option value="SELF">{t('scenario.yourselfHotseat')}</option>
                <option value="AI">{t('scenario.ai')}</option>
                <option value="TWO_PLAYER">{t('scenario.twoPlayers')}</option>
              </select>
            </label>
            <label className={styles.field}>
              <span className={styles.smallLabel}>{t('scenario.phase')}</span>
              <select
                className={styles.select}
                value={state.phase}
                onChange={(e) => commit((prev) => ({ ...prev, phase: e.target.value }))}
              >
                {PHASES.map((ph) => (
                  <option key={ph} value={ph}>
                    {t(`scenario.phases.${ph}`)}
                  </option>
                ))}
              </select>
            </label>
            <label className={styles.field}>
              <span className={styles.smallLabel}>{t('scenario.activePlayer')}</span>
              <select
                className={styles.select}
                value={state.activePlayer}
                onChange={(e) => commit((prev) => ({ ...prev, activePlayer: Number(e.target.value) }))}
              >
                {state.seats.map((s, i) => (
                  <option key={s.id} value={i + 1}>
                    {s.name}
                  </option>
                ))}
              </select>
            </label>
            <button
              type="button"
              className={styles.ghostBtn}
              onClick={handleAddSeat}
              disabled={state.seats.length >= 4}
              title={t('scenario.addSeatHint')}
            >
              {t('scenario.addSeat')}
            </button>
            {/* Second row: what this setup means on the left, the board tools on the right. */}
            <span className={styles.rowBreak} aria-hidden />
            <span className={styles.modeHint}>
              {state.seats.length > 2
                ? t('scenario.threeFourSeatsHint')
                : t(`scenario.modes.${state.mode}`)}
            </span>
            <div className={styles.boardTools}>
              <button
                type="button"
                className={styles.ghostBtn}
                onClick={() => setCustomOpen((o) => !o)}
                title={t('scenario.customCardHint')}
              >
                {customOpen ? t('scenario.hideCustomCards') : `${t('scenario.customCards')}${state.customCards.length ? ` (${state.customCards.length})` : ''}`}
              </button>
              <button type="button" className={styles.ghostBtn} onClick={() => setJsonOpen((o) => !o)}>
                {jsonOpen ? t('scenario.hideJson') : t('scenario.editJson')}
              </button>
              <button
                type="button"
                className={styles.ghostBtn}
                onClick={() => {
                  reset(emptyBuilderState())
                  setStatus(t('scenario.boardCleared'))
                }}
              >
                {t('scenario.reset')}
              </button>
            </div>
          </div>

          <ScenarioBoard
            state={state}
            index={cardIndex}
            target={target}
            onTargetChange={setTarget}
            onSeatPatch={handleSeatPatch}
            onDropCard={handleDropCard}
            onRemove={handleRemove}
            onEditCard={(seatId, i) => setEditing({ seatId, index: i })}
            onQuickPatch={handleQuickPatch}
            onClearZone={handleClearZone}
            onClearSeat={handleClearSeat}
            onRemoveSeat={handleRemoveSeat}
          />

          {customOpen && (
            <CustomCardPanel
              cards={state.customCards}
              onAdd={handleAddCustomCard}
              onRemove={handleRemoveCustomCard}
              onPlace={(name) => {
                if (!targetSeat) return
                addCard(name, targetSeat.id, target.zone, copies)
                setStatus(t(copies > 1 ? 'scenario.cardsAdded' : 'scenario.cardAdded', {
                  count: copies,
                  name,
                  seat: targetSeat.name,
                  zone: t(`scenario.zoneNames.${target.zone}`),
                }))
              }}
              placeHint={t('scenario.placeHint', {
                seat: targetSeat?.name ?? t('scenario.seat'),
                zone: t(`scenario.zoneNames.${target.zone}`),
              })}
            />
          )}

          {jsonOpen && (
            <div className={styles.jsonDrawer}>
              <span className={styles.hint}>
                {t('scenario.pasteJsonHint')}
              </span>
              <textarea
                className={styles.jsonArea}
                value={jsonText}
                placeholder={t('scenario.pasteJsonPlaceholder')}
                onChange={(e) => setJsonText(e.target.value)}
              />
              <div className={styles.jsonActions}>
                <button type="button" className={styles.ghostBtn} onClick={handleLoadJson}>
                  {t('scenario.apply')}
                </button>
                <button type="button" className={styles.ghostBtn} onClick={handleShowJson}>
                  {t('scenario.refreshFromBoard')}
                </button>
              </div>
            </div>
          )}
        </div>
      </div>

      {editing && editingCard && editingSeat && (
        <CardEditorModal
          card={editingCard}
          summary={cardIndex[editingCard.name]}
          hostOptions={editingSeat.battlefield
            .filter((_, i) => i !== editing.index)
            .map((c) => c.name)}
          creatureTypes={creatureTypes}
          onChange={(patch) => handleQuickPatch(editing.seatId, editing.index, patch)}
          onRemove={() => handleRemove(editing.seatId, 'battlefield', editing.index)}
          onClose={() => setEditing(null)}
        />
      )}
    </div>
  )
}

function ActionButton(props: {
  label: string
  hint: string
  onClick: () => void
  primary?: boolean
  disabled?: boolean
}) {
  return (
    <button
      className={props.primary ? styles.actionBtnPrimary : styles.actionBtn}
      disabled={props.disabled}
      onClick={props.onClick}
      type="button"
    >
      <span className={styles.actionLabel}>{props.label}</span>
      <span className={styles.actionHint}>{props.hint}</span>
    </button>
  )
}

export default ScenarioBuilderPage
