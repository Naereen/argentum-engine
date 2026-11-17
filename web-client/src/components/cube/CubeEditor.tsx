/**
 * CubeEditor — the compact cube editor the lobby opens as a dialog, where a host can tweak a cube and
 * hand it straight to the table. Full-size cube building happens in the builder's cube mode
 * ({@link CubeBuilderPage}); both sit on {@link useCubeDraft}, so they share every editing rule and
 * differ only in layout.
 *
 * A cube is a list of card *names* with counts — and, for the booster styles, a rarity per card — so
 * this editor never resolves printings and never decides what a pack contains: it edits a list and
 * hands it back. The lobby sends it to the server (`UpdateLobbySettings.cubeCards`), which resolves
 * it against the card registry and remains the authority on packs, capacity and legality.
 *
 * Layout: name and actions on top; how the cube becomes packs ({@link CubePackControls}) beneath; then
 * the two working columns — where cards come from ({@link SearchPane}, {@link SetPane},
 * {@link PastePane}) and what the cube holds ({@link CubeCardList}) — and the balance numbers
 * ({@link CubeStats}) along the bottom.
 *
 * Unimplemented cards are the one cube-specific rule: a cube with unresolvable names is *unplayable*
 * (the server rejects it wholesale), so they're surfaced in red with a one-click "drop them" action and
 * "Use this cube" stays disabled until the list is clean. Saving a still-dirty cube is allowed — sets
 * ship continuously, and a cube that names a card landing next month is a legitimate thing to keep.
 */
import { useCallback, useState } from 'react'
import { createPortal } from 'react-dom'
import { useTranslation } from 'react-i18next'
import type { SharedCube } from '@/api/account'
import type { UnifiedCube } from '@/store/useUnifiedCubes'
import { CubeCardList } from './CubeCardList'
import { CubePackControls } from './CubePackControls'
import { cubeToText, isBoosterStyle } from './cubeModel'
import { CubeStats } from './CubeStats'
import { PastePane, SearchPane, SetPane, type SourceTab } from './CubeSources'
import { ShareCubeDialog } from './ShareCubeDialog'
import { useCardHover } from './useCardHover'
import { useCardCatalog } from './useCardCatalog'
import { type BasicLandSetOption, useCubeDraft } from './useCubeDraft'
import styles from './CubeEditor.module.css'

export type { BasicLandSetOption } from './useCubeDraft'

const SOURCE_TABS: readonly SourceTab[] = ['SEARCH', 'SET', 'PASTE']

interface CubeEditorProps {
  /** Cube to edit, or null to start a new one. */
  readonly cube: UnifiedCube | null
  /** Sets offered as the basic-land art source (a cube has no basics of its own). */
  readonly availableSets: readonly BasicLandSetOption[]
  readonly onClose: () => void
  /**
   * Called when the host commits the cube to the lobby. Only reachable once every name resolves —
   * an unresolved cube can't start a game.
   */
  readonly onUse?: (cube: SharedCube) => void
}

export function CubeEditor({ cube, availableSets, onClose, onUse }: CubeEditorProps) {
  const { t } = useTranslation()
  const catalog = useCardCatalog()
  const lookup = useCallback((cardName: string) => catalog.byName.get(cardName.toLowerCase()), [catalog.byName])
  const draft = useCubeDraft({ cube, availableSets, lookup, catalogReady: catalog.ready })
  const { name, setName, description, setDescription, entries, totalCards, unresolved, dirty, saved, saving } = draft

  const [tab, setTab] = useState<SourceTab>(entries.length === 0 ? 'SET' : 'SEARCH')
  const [shareOpen, setShareOpen] = useState(false)
  const [confirmingLeave, setConfirmingLeave] = useState(false)
  const { onHover: handleHover, preview } = useCardHover()

  const requestClose = () => (dirty ? setConfirmingLeave(true) : onClose())

  const editor = (
    <div className={styles.dialog} role="dialog" aria-label={t('cubeUi.editor')}>
      <header className={styles.header}>
        <div className={styles.titleBlock}>
          <input
            className={styles.titleInput}
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder={t('cubeUi.nameYourCube')}
            aria-label={t('cubeUi.cubeName')}
          />
          <input
            className={styles.descriptionInput}
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            placeholder={t('cubeUi.cubeDescriptionPlaceholder')}
            aria-label={t('cubeUi.cubeDescription')}
            maxLength={280}
          />
        </div>
        <div className={styles.headerActions}>
          <span className={styles.saveState} data-dirty={dirty && !draft.notice} aria-live="polite">
            {draft.saveState}
          </span>
          <button type="button" className={styles.secondaryButton} disabled={totalCards === 0} onClick={() => void draft.copyList()}>
            {t('cubeUi.copyList')}
          </button>
          <button type="button" className={styles.secondaryButton} disabled={totalCards === 0} onClick={() => setShareOpen(true)}>
            {t('cubeUi.share')}
          </button>
          <button
            type="button"
            className={onUse ? styles.secondaryButton : styles.primaryButton}
            disabled={saving || totalCards === 0 || (!dirty && saved !== null)}
            onClick={() => void draft.save()}
          >
            {saving ? t('cubeUi.saving') : t('cubeUi.save')}
          </button>
          {onUse && (
            <button
              type="button"
              className={styles.primaryButton}
              disabled={!draft.canUse || saving}
              title={
                draft.canUse
                  ? undefined
                  : unresolved.length > 0
                    ? t('cubeUi.cardsNotImplemented', { count: unresolved.length })
                    : t('cubeUi.addCardsFirst')
              }
              onClick={async () => {
                if (!draft.canUse) return
                if (!dirty && saved) onUse(draft.shared)
                else if (await draft.save()) onUse(draft.shared)
              }}
            >
              {t('cubeUi.useThisCube')}
            </button>
          )}
          <button type="button" className={styles.secondaryButton} onClick={requestClose}>
            {t('common.close')}
          </button>
        </div>
      </header>

      {confirmingLeave && (
        <div className={styles.confirmBar} role="alert">
          <span>{t('cubeUi.unsavedChanges')}</span>
          <button
            type="button"
            className={styles.primaryButton}
            disabled={saving || totalCards === 0}
            onClick={async () => {
              if (await draft.save()) onClose()
            }}
          >
            {t('cubeUi.saveAndClose')}
          </button>
          <button type="button" className={styles.secondaryButton} onClick={onClose}>
            {t('cubeUi.discardChanges')}
          </button>
          <button type="button" className={styles.linkButton} onClick={() => setConfirmingLeave(false)}>
            {t('cubeUi.keepEditing')}
          </button>
        </div>
      )}

      <CubePackControls draft={draft} layout="bar" />

      {unresolved.length > 0 && (
        <div className={styles.warning}>
          <span>
            <strong>{unresolved.length}</strong> of {entries.length} cards aren&apos;t implemented yet, so this cube
            can&apos;t be played: {unresolved.slice(0, 6).map((e) => e.name).join(', ')}
            {unresolved.length > 6 ? `, +${unresolved.length - 6} more` : ''}
          </span>
          <button type="button" className={styles.secondaryButton} onClick={draft.dropUnresolved}>
            Drop the {unresolved.length} unimplemented card{unresolved.length === 1 ? '' : 's'}
          </button>
        </div>
      )}

      {draft.saveError && <div className={styles.warning}>{draft.saveError}</div>}

      <div className={styles.body}>
        <section className={styles.column} aria-label={t('cubeUi.addCards')}>
          <div className={styles.sourceTabs} role="tablist" aria-label={t('cubeUi.addCardsBy')}>
            {SOURCE_TABS.map((sourceTab) => (
              <button
                key={sourceTab}
                type="button"
                role="tab"
                aria-selected={tab === sourceTab}
                className={styles.sourceTab}
                onClick={() => setTab(sourceTab)}
              >
                {sourceTab === 'SEARCH'
                  ? t('cubeUi.sourceTabs.search')
                  : sourceTab === 'SET'
                    ? t('cubeUi.sourceTabs.set')
                    : t('cubeUi.sourceTabs.paste')}
              </button>
            ))}
          </div>
          {tab === 'SEARCH' && (
            <SearchPane
              catalog={catalog.cards}
              catalogReady={catalog.ready}
              inCube={draft.inCube}
              onAdd={draft.addOne}
              onHover={handleHover}
            />
          )}
          {tab === 'SET' && <SetPane inCube={draft.inCube} onAdd={draft.addFromSet} />}
          {tab === 'PASTE' && (
            <PastePane catalog={catalog.cards} cubeIsEmpty={entries.length === 0} onImport={draft.importList} onSuggestName={setName} />
          )}
        </section>

        <section className={styles.column} aria-label={t('cubeUi.cardsInCube')}>
          <div className={styles.columnHeader}>
            <span className={styles.columnHeaderTitle}>
              {t('cubeUi.totalCards', { count: totalCards })}
              {entries.length !== totalCards ? ` (${t('cubeUi.differentCards', { count: entries.length })})` : ''}
            </span>
            {entries.length > 0 && (
              <button type="button" className={styles.linkButton} onClick={() => draft.setEntries([])}>
                {t('cubeUi.clearAll')}
              </button>
            )}
          </div>
          <CubeCardList
            entries={entries}
            lookup={lookup}
            catalogReady={catalog.ready}
            rarityMatters={isBoosterStyle(draft.boosterStyle)}
            onHover={handleHover}
            onCount={draft.setCount}
            onRarity={draft.setRarity}
            emptyHint={
              <>
                <p className={styles.emptyTitle}>{t('cubeUi.emptyTitle')}</p>
                <p className={styles.hint}>
                  {t('cubeUi.emptyHint')}
                </p>
              </>
            }
          />
        </section>
      </div>

      <CubeStats entries={entries} lookup={lookup} boosterStyle={draft.boosterStyle} packSize={draft.packSize} />

      {shareOpen && (
        <ShareCubeDialog
          saved={saved}
          dirty={dirty}
          isLoggedIn={draft.isLoggedIn}
          text={cubeToText(draft.shared)}
          onSave={draft.save}
          share={draft.shareSaved}
          unshare={draft.unshareSaved}
          onClose={() => setShareOpen(false)}
        />
      )}
    </div>
  )

  // Portalled to <body>: the lobby panel this opens from sits inside a `backdrop-filter` ancestor,
  // which is a containing block for `position: fixed` — a full-screen dialog rendered in place would
  // size itself to the lobby's scroll height instead of the viewport.
  return createPortal(
    <>
      <div className={styles.backdrop} onClick={requestClose} />
      {editor}
      {preview}
    </>,
    document.body,
  )
}
