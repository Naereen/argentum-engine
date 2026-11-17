/**
 * The builder in cube mode — `/cubes/new` and `/cubes/edit/:cubeId`.
 *
 * Decks and cubes are built with the same tool: the deckbuilder's chrome, its filter rail and its
 * card grid, with the {@link BuilderModeSwitch} in the title flipping between them. What changes in
 * cube mode is the right rail and the rules behind it — no sideboard, format or copy cap; instead how
 * the cube becomes packs ({@link CubePackControls}), a rarity on every card ({@link CubeCardList}), and
 * the two cube-sized ways in: a whole set at its own rarities ({@link SetPane}) and a pasted list
 * ({@link PastePane}).
 *
 * Like the deckbuilder it has two views, toggled in the toolbar and kept in the URL (`?view=cube`):
 * "Search cards" — the catalog grid with the cube in the right rail — and "Cube list", the deckbuilder's
 * Deck view for a cube: the hovered card large on the left and the whole cube in flowing columns
 * ({@link CubeColumns}), edited in place, with a search-and-add bar above it.
 *
 * The editing rules live in {@link useCubeDraft}, shared with the lobby's compact {@link CubeEditor}
 * dialog, so the two surfaces can't drift.
 */
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useLocation, useMatch, useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { listBasicLandSets } from '@/api/cubeSets'
import type { CardSummary } from '@/components/deckbuilder/cardFilter'
import {
  BuilderModeSwitch,
  clearWorkingCopy,
  peekWorkingCopy,
  stashWorkingCopy,
} from '@/components/deckbuilder/BuilderModeSwitch'
import { BuilderViewToggle } from '@/components/deckbuilder/BuilderViewToggle'
import { CardGrid, FilterSection, SearchBar, useCardBrowse, useCardCatalog } from '@/components/deckbuilder/browser'
import { PageShell, pageStyles as p } from '@/components/ui/PageShell'
import { type UnifiedCube, useUnifiedCubes } from '@/store/useUnifiedCubes'
import { CubeCardList } from './CubeCardList'
import { CubeColumns, CubeColumnsToolbar, CubeRailPreview, useCubeColumns } from './CubeColumns'
import { CubePackControls } from './CubePackControls'
import { cubeToText, isBoosterStyle } from './cubeModel'
import { CubeStats } from './CubeStats'
import { PastePane, SetPane } from './CubeSources'
import { ShareCubeDialog } from './ShareCubeDialog'
import { useCardHover } from './useCardHover'
import { type BasicLandSetOption, type CubeWorkingCopy, useCubeDraft } from './useCubeDraft'
import { ManaCost } from '@/components/ui/ManaSymbols'
import { useHoverStore } from '@/components/ui/hoverStore'
import db from '@/components/deckbuilder/deckbuilder.module.css'
import editor from './CubeEditor.module.css'
import shared from './SharedCube.module.css'
import styles from './CubeBuilder.module.css'

/** The catalog grid with the cube in the rail, or the cube itself in columns. */
type ViewMode = 'cards' | 'cube'

/** Narrow screens show one column of the cards view at a time; these tabs pick it. */
type Pane = 'cards' | 'filters'

/** A source dialog over the builder: a whole set, or a pasted list. */
type SourceDialog = 'set' | 'paste' | null

export function CubeBuilderPage() {
  const { t } = useTranslation()
  const { cubeId } = useParams<{ cubeId?: string }>()
  const isNew = useMatch('/cubes/new') !== null
  const navigate = useNavigate()
  const library = useUnifiedCubes()
  const [basicLandSets, setBasicLandSets] = useState<BasicLandSetOption[]>([])
  /** A cube saved here that the library hook hasn't re-fetched yet. */
  const [savedHere, setSavedHere] = useState<UnifiedCube | null>(null)

  useEffect(() => {
    let cancelled = false
    void listBasicLandSets().then((sets) => {
      if (!cancelled) setBasicLandSets(sets)
    })
    return () => {
      cancelled = true
    }
  }, [])

  // A fresh working copy whenever the route opens a different cube — except for the redirect this
  // page makes itself after saving a new cube, which must keep the copy being edited.
  const routeKey = isNew ? 'new' : cubeId ?? 'new'
  const adoptedRef = useRef<string | null>(null)
  const [generation, setGeneration] = useState(0)
  const [openedKey, setOpenedKey] = useState(routeKey)
  if (routeKey !== openedKey) {
    setOpenedKey(routeKey)
    if (routeKey === adoptedRef.current) adoptedRef.current = null
    else setGeneration((g) => g + 1)
  }

  const cube = isNew || cubeId === undefined
    ? null
    : library.cubes.find((c) => c.id === cubeId) ?? (savedHere?.id === cubeId ? savedHere : null)

  if (!isNew && !cube) {
    return (
      <PageShell title={<BuilderModeSwitch mode="cube" />}>
        {library.ready ? (
          <div className={p.panel}>
            <p className={p.empty}>{t('cubeUi.cubeNotInLibrary')}</p>
            <button type="button" className={p.button} onClick={() => navigate('/library?tab=cubes')}>
              {t('cubeUi.backToCubes')}
            </button>
          </div>
        ) : (
          <p className={p.empty}>{t('cubeUi.loadingCube')}</p>
        )}
      </PageShell>
    )
  }

  return (
    <CubeBuilder
      key={generation}
      cube={cube}
      availableSets={basicLandSets}
      onSaved={(saved) => {
        setSavedHere(saved)
        if (saved.id !== cubeId) {
          adoptedRef.current = saved.id
          navigate(`/cubes/edit/${encodeURIComponent(saved.id)}`, { replace: true })
        }
      }}
    />
  )
}

function CubeBuilder({
  cube,
  availableSets,
  onSaved,
}: {
  cube: UnifiedCube | null
  availableSets: readonly BasicLandSetOption[]
  onSaved: (cube: UnifiedCube) => void
}) {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const location = useLocation()
  const [search, setSearch] = useSearchParams()
  // Unsaved edits left here by a switch to deck mode come back exactly as they were.
  const [resume] = useState(() => peekWorkingCopy<CubeWorkingCopy>('cube', location.pathname))
  useEffect(() => {
    if (resume) clearWorkingCopy('cube')
  }, [resume])
  const { catalog, setInfos, loading, error } = useCardCatalog()
  const byName = useMemo(() => {
    const map = new Map<string, CardSummary>()
    for (const card of catalog) map.set(card.name.toLowerCase(), card)
    return map
  }, [catalog])
  const lookup = useCallback((name: string) => byName.get(name.toLowerCase()), [byName])
  const draft = useCubeDraft({ cube, availableSets, lookup, catalogReady: !loading && !error, onSaved, resume })
  const { entries, totalCards, unresolved, dirty, saving } = draft

  // Basics aren't cube cards — every cube's deckbuilding hands them out from its land-art set.
  const browsable = useMemo(() => catalog.filter((c) => !c.basicLand), [catalog])
  const browse = useCardBrowse(browsable)

  const from = search.get('from')
  const [dialog, setDialog] = useState<SourceDialog>(from === 'paste' ? 'paste' : from === 'set' ? 'set' : null)
  const [pane, setPane] = useState<Pane>('cards')
  const viewMode: ViewMode = search.get('view') === 'cube' ? 'cube' : 'cards'
  const setViewMode = useCallback(
    (next: ViewMode) =>
      setSearch(
        (prev) => {
          const params = new URLSearchParams(prev)
          if (next === 'cube') params.set('view', 'cube')
          else params.delete('view')
          return params
        },
        { replace: true },
      ),
    [setSearch],
  )
  const [toolsOpen, setToolsOpen] = useState(false)
  const [shareOpen, setShareOpen] = useState(false)
  /** Where an exit with unsaved changes was headed, while the confirm bar asks about it. */
  const [pendingExit, setPendingExit] = useState<(() => void) | null>(null)
  const { onHover, preview } = useCardHover()

  const columns = useCubeColumns(entries, lookup, !loading)
  const hover = useHoverStore<CardSummary>()
  // The rail is never blank: until something is hovered, it shows the cube's first card.
  const firstCard = useMemo(() => {
    const first = columns.groups[0]?.entries[0]
    return first ? lookup(first.name) ?? null : null
  }, [columns.groups, lookup])

  const identityInputs = (
    <>
      <input
        className={editor.titleInput}
        value={draft.name}
        onChange={(e) => draft.setName(e.target.value)}
        placeholder={t('cubeUi.nameYourCube')}
        aria-label={t('cubeUi.cubeName')}
      />
      <input
        className={editor.descriptionInput}
        value={draft.description}
        onChange={(e) => draft.setDescription(e.target.value)}
        placeholder={t('cubeUi.cubeDescriptionPlaceholder')}
        aria-label={t('cubeUi.cubeDescription')}
        maxLength={280}
      />
    </>
  )

  const unresolvedWarning = unresolved.length > 0 && (
    <div className={`${editor.warning} ${viewMode === 'cards' ? styles.railWarning : ''}`}>
      <span>
        {t('cubeUi.unresolvedWarning', { count: unresolved.length })}
      </span>
      <button type="button" className={editor.secondaryButton} onClick={draft.dropUnresolved}>
        {t('cubeUi.dropUnresolved', { count: unresolved.length })}
      </button>
    </div>
  )

  const countLabel = `${t('cubeUi.totalCards', { count: totalCards })}${
    entries.length !== totalCards ? ` · ${t('cubeUi.differentCards', { count: entries.length })}` : ''
  }`
  const clearAll = entries.length > 0 && (
    <button type="button" className={editor.linkButton} onClick={() => draft.setEntries([])}>
      {t('cubeUi.clearAll')}
    </button>
  )

  const guard = (proceed: () => void) => (dirty ? setPendingExit(() => proceed) : proceed())
  // Flipping to deck mode is a tab switch, not an exit: the unsaved cube waits for the way back.
  const stashDraft = () => {
    const copy = draft.workingCopy()
    if (copy) stashWorkingCopy('cube', copy)
    else clearWorkingCopy('cube')
  }
  const openTool = (action: () => void) => () => {
    setToolsOpen(false)
    action()
  }

  return (
    <PageShell title={<BuilderModeSwitch mode="cube" onLeave={stashDraft} />} fit width="full" plain>
      <div className={db.toolbar}>
        <BuilderViewToggle
          className={db.viewToggle}
          noun="Cube"
          count={totalCards}
          view={viewMode === 'cube' ? 'list' : 'cards'}
          onChange={(v) => setViewMode(v === 'list' ? 'cube' : 'cards')}
        />
        {/* Narrow screens: one tab bar picks the pane and stands in for the view toggle. */}
        <div className={db.paneTabs} role="tablist" aria-label={t('cubeUi.builderPanes')}>
          {(['cards', 'filters'] as const).map((p) => (
            <button
              key={p}
              type="button"
              role="tab"
              className={db.paneTab}
              aria-selected={viewMode === 'cards' && pane === p}
              onClick={() => {
                setViewMode('cards')
                setPane(p)
              }}
            >
              {p === 'cards' ? t('cubeUi.cards') : t('cubeUi.filters')}
            </button>
          ))}
          <button
            type="button"
            role="tab"
            className={db.paneTab}
            aria-selected={viewMode === 'cube'}
            onClick={() => setViewMode('cube')}
          >
            {t('cubeUi.cube')} <span className={db.paneTabCount}>{totalCards}</span>
          </button>
        </div>
        <div className={db.toolbarSpacer} />
        {toolsOpen && <div className={db.toolsBackdrop} onClick={() => setToolsOpen(false)} aria-hidden />}
        <div className={db.tools} data-open={toolsOpen}>
          <button type="button" className={db.toolButton} onClick={openTool(() => guard(() => navigate('/library?tab=cubes')))}>
            {t('cubeUi.myCubes')}
          </button>
          <button type="button" className={db.toolButton} onClick={openTool(() => setDialog('set'))}>
            {t('cubeUi.addASet')}
          </button>
          <button type="button" className={db.toolButton} onClick={openTool(() => setDialog('paste'))}>
            {t('cubeUi.import')}
          </button>
          <button
            type="button"
            className={db.toolButton}
            disabled={totalCards === 0}
            onClick={openTool(() => void draft.copyList())}
            title={t('cubeUi.copyCubeAsText')}
          >
            {t('cubeUi.copyList')}
          </button>
          <button type="button" className={db.toolButton} disabled={totalCards === 0} onClick={openTool(() => setShareOpen(true))}>
            {t('cubeUi.share')}
          </button>
          <button type="button" className={db.toolButton} onClick={openTool(() => guard(() => navigate('/cubes/new')))}>
            {t('cubeUi.newCube')}
          </button>
        </div>
        <button
          type="button"
          className={db.moreButton}
          onClick={() => setToolsOpen((v) => !v)}
          aria-expanded={toolsOpen}
          aria-haspopup="menu"
        >
          {t('deckbuilder.more')}
          <svg width="12" height="12" viewBox="0 0 12 12" aria-hidden focusable="false">
            <path d="M2.5 4.5L6 8l3.5-3.5" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" />
          </svg>
        </button>
        <span className={styles.saveState} data-dirty={dirty && !draft.notice} aria-live="polite">
          {draft.saveState}
        </span>
        <button
          type="button"
          className={`${db.newDeckButton} ${styles.saveButton}`}
          disabled={saving || totalCards === 0 || (!dirty && draft.saved !== null)}
          onClick={() => void draft.save()}
        >
          {saving ? t('cubeUi.saving') : t('cubeUi.save')}
        </button>
      </div>

      {pendingExit && (
        <div className={`${editor.confirmBar} ${styles.banner}`} role="alert">
          <span>{t('cubeUi.unsavedChanges')}</span>
          <button
            type="button"
            className={editor.primaryButton}
            disabled={saving || totalCards === 0}
            onClick={async () => {
              if (await draft.save()) {
                pendingExit()
                setPendingExit(null)
              }
            }}
          >
            {t('cubeUi.saveAndContinue')}
          </button>
          <button
            type="button"
            className={editor.secondaryButton}
            onClick={() => {
              pendingExit()
              setPendingExit(null)
            }}
          >
            {t('cubeUi.discardChanges')}
          </button>
          <button type="button" className={editor.linkButton} onClick={() => setPendingExit(null)}>
            {t('cubeUi.keepEditing')}
          </button>
        </div>
      )}
      {draft.saveError && <div className={`${editor.warning} ${styles.banner}`}>{draft.saveError}</div>}

      {viewMode === 'cube' ? (
        <div className={`${db.workspace} ${db.workspaceDeckMode}`}>
          <aside className={db.left}>
            <CubeRailPreview hover={hover} fallback={firstCard} />
            <section className={styles.startCard} aria-label={t('cubeUi.addWholeSet')}>
              <span className={styles.startTitle}>{t('cubeUi.startFromSet')}</span>
              <span className={styles.startText}>
                {t('cubeUi.startFromSetHint')}
              </span>
              <button type="button" className={editor.primaryButton} onClick={() => setDialog('set')}>
                {t('cubeUi.addASetEllipsis')}
              </button>
            </section>
          </aside>

          <main className={db.centerDeck}>
            <header className={shared.header}>
              <div className={shared.titleBlock}>{identityInputs}</div>
            </header>
            <CubePackControls draft={draft} layout="bar" />
            {unresolvedWarning}
            <CubeAddSearch
              catalog={browsable}
              counts={draft.counts}
              onAdd={(card) => {
                draft.addOne(card.name)
                hover.set(card)
              }}
              onHover={hover.set}
              onAddSet={() => setDialog('set')}
            />
            <CubeColumnsToolbar state={columns} total={entries.length}>
              <span className={shared.listMeta}>
                {countLabel}
                {clearAll}
              </span>
            </CubeColumnsToolbar>
            <CubeColumns
              groups={columns.groups}
              filter={columns.filter}
              lookup={lookup}
              catalogReady={!loading}
              rarityMatters={isBoosterStyle(draft.boosterStyle)}
              hover={hover}
              fallbackName={firstCard?.name ?? null}
              onCount={draft.setCount}
              onRarity={draft.setRarity}
              emptyHint={
                <div className={db.deckCentricEmpty}>
                  <p className={db.deckCentricEmptyText}>
                    {t('cubeUi.emptyCubeSearchHint')}
                  </p>
                </div>
              }
            />
            <div className={shared.footer}>
              <CubeStats entries={entries} lookup={lookup} boosterStyle={draft.boosterStyle} packSize={draft.packSize} />
            </div>
          </main>
        </div>
      ) : (
        <>
        <div className={`${db.workspace} ${styles.workspace}`} data-pane={pane}>
          <aside className={db.left}>
            <section className={styles.startCard} aria-label={t('cubeUi.addWholeSet')}>
              <span className={styles.startTitle}>{t('cubeUi.startFromSet')}</span>
              <span className={styles.startText}>
                {t('cubeUi.startFromSetHint')}
              </span>
              <button type="button" className={editor.primaryButton} onClick={() => setDialog('set')}>
                {t('cubeUi.addASetEllipsis')}
              </button>
            </section>
            <FilterSection
              query={browse.query}
              onQueryChange={browse.setQuery}
              catalog={browsable}
              setInfos={setInfos}
              advanced={browse.advanced}
            />
          </aside>

          <main className={db.center}>
            <SearchBar
              query={browse.query}
              onQueryChange={browse.setQuery}
              sortMode={browse.sortMode}
              onSortChange={browse.setSortMode}
              errors={browse.errors}
              explanation={browse.explanation}
              resultLabel={loading ? 'Loading…' : `Showing ${browse.displayed.length} of ${browse.filtered.length}`}
            />
            {error ? (
              <p className={styles.notice}>Couldn’t load the card catalogue: {error}</p>
            ) : (
              <CardGrid
                cards={browse.displayed}
                deckCards={draft.counts}
                onAdd={(card) => draft.addOne(card.name)}
                onRemove={draft.removeOne}
                hasMore={browse.hasMore}
                onShowMore={browse.showMore}
              />
            )}
          </main>

          <aside className={`${db.right} ${styles.rail}`}>
            <div className={styles.identity}>{identityInputs}</div>

            <div className={styles.railSection}>
              <CubePackControls draft={draft} layout="stacked" />
            </div>

            {unresolvedWarning}

            <div className={styles.listHeader}>
              <span className={styles.listTitle}>{countLabel}</span>
              {clearAll}
            </div>
            <CubeCardList
              entries={entries}
              lookup={lookup}
              catalogReady={!loading}
              rarityMatters={isBoosterStyle(draft.boosterStyle)}
              onHover={onHover}
              onCount={draft.setCount}
              onRarity={draft.setRarity}
              emptyHint={
                <>
                  <p className={editor.emptyTitle}>{t('cubeUi.emptyTitle')}</p>
                  <p className={editor.hint}>
                    {t('cubeUi.emptyGridHint')}
                  </p>
                </>
              }
            />
          </aside>
        </div>

        {/* The cube's balance numbers. On a phone they belong to the Cube view, not under the grid. */}
        <div className={styles.footer} data-mobile-hidden>
          <CubeStats entries={entries} lookup={lookup} boosterStyle={draft.boosterStyle} packSize={draft.packSize} />
        </div>
        </>
      )}

      {dialog && (
        <>
          <div className={styles.dialogBackdrop} onClick={() => setDialog(null)} aria-hidden />
          <div
            className={styles.dialog}
            role="dialog"
            aria-label={dialog === 'set' ? t('cubeUi.addASet') : t('cubeUi.importList')}
          >
            <div className={styles.dialogHeader}>
              <span className={styles.dialogTitle}>{dialog === 'set' ? t('cubeUi.addASet') : t('cubeUi.importList')}</span>
              <button type="button" className={editor.secondaryButton} onClick={() => setDialog(null)}>
                {t('cubeUi.done')}
              </button>
            </div>
            <div className={styles.dialogBody}>
              {dialog === 'set' ? (
                <SetPane inCube={draft.inCube} onAdd={draft.addFromSet} />
              ) : (
                <PastePane
                  catalog={catalog}
                  cubeIsEmpty={entries.length === 0}
                  onImport={(list, replace) => {
                    draft.importList(list, replace)
                    setDialog(null)
                  }}
                  onSuggestName={draft.setName}
                />
              )}
            </div>
          </div>
        </>
      )}

      {shareOpen && (
        <ShareCubeDialog
          saved={draft.saved}
          dirty={dirty}
          isLoggedIn={draft.isLoggedIn}
          text={cubeToText(draft.shared)}
          onSave={draft.save}
          share={draft.shareSaved}
          unshare={draft.unshareSaved}
          onClose={() => setShareOpen(false)}
        />
      )}

      {preview}
    </PageShell>
  )
}

/**
 * The Cube view's search-and-add bar — the Deck view's, minus the copy cap: type a name, Enter or
 * click adds one copy. Hovering a match shows it in the rail; "Add a set…" sits where the deck view
 * offers basic lands.
 */
function CubeAddSearch({
  catalog,
  counts,
  onAdd,
  onHover,
  onAddSet,
}: {
  catalog: readonly CardSummary[]
  counts: Readonly<Record<string, number>>
  onAdd: (card: CardSummary) => void
  onHover: (card: CardSummary) => void
  onAddSet: () => void
}) {
  const { t } = useTranslation()
  const [text, setText] = useState('')
  const [open, setOpen] = useState(false)
  const containerRef = useRef<HTMLDivElement | null>(null)
  const inputRef = useRef<HTMLInputElement | null>(null)

  const matches = useMemo(() => {
    const t = text.trim().toLowerCase()
    if (!t) return []
    const rank = (name: string) => (name === t ? 0 : name.startsWith(t) ? 1 : 2)
    return catalog
      .filter((c) => c.name.toLowerCase().includes(t))
      .sort((a, b) => {
        const al = a.name.toLowerCase()
        const bl = b.name.toLowerCase()
        return rank(al) - rank(bl) || al.localeCompare(bl)
      })
      .slice(0, 14)
  }, [catalog, text])

  // The dropdown floats over the columns; close it on an outside click so it doesn't swallow hovers.
  useEffect(() => {
    if (!open) return
    const onDoc = (e: MouseEvent) => {
      if (containerRef.current && !containerRef.current.contains(e.target as Node)) setOpen(false)
    }
    document.addEventListener('mousedown', onDoc)
    return () => document.removeEventListener('mousedown', onDoc)
  }, [open])

  const add = (card: CardSummary) => {
    onAdd(card)
    inputRef.current?.focus()
  }

  return (
    <div ref={containerRef} className={db.addCardSearch}>
      <input
        ref={inputRef}
        className={db.addCardInput}
        type="text"
        value={text}
        onChange={(e) => {
          setText(e.target.value)
          setOpen(true)
        }}
        onFocus={() => setOpen(true)}
        onKeyDown={(e) => {
          if (e.key === 'Escape') {
            setOpen(false)
            inputRef.current?.blur()
          } else if (e.key === 'Enter' && matches[0]) {
            e.preventDefault()
            add(matches[0])
          }
        }}
        placeholder={t('cubeUi.findAndAddCardsToCube')}
        aria-label={t('cubeUi.findAndAddCardsToCube')}
      />
      <button type="button" className={db.addCardBasicsButton} onClick={onAddSet} title={t('cubeUi.addWholeSetTitle')}>
        {t('cubeUi.addASetEllipsis')}
      </button>
      {open && matches.length > 0 && (
        <div className={db.addCardDropdown} role="listbox">
          {matches.map((card) => (
            <button
              key={card.name}
              type="button"
              role="option"
              aria-selected={false}
              className={db.addCardResult}
              onClick={() => add(card)}
              onMouseEnter={() => onHover(card)}
              title={t('cubeUi.addCardNamed', { name: card.name })}
            >
              <span className={db.addCardResultCount}>{counts[card.name] ?? 0}×</span>
              <span className={db.addCardResultName}>{card.name}</span>
              <span className={db.addCardResultCost}>
                <ManaCost cost={card.manaCost || null} size={11} />
              </span>
            </button>
          ))}
        </div>
      )}
    </div>
  )
}
