/**
 * `/cubes/shared/:code` — a cube someone shared by link.
 *
 * Laid out like the deckbuilder's Deck view, since reading a list is what a friend came to do: the
 * hovered card large in the left rail, the cube in flowing columns grouped by type, colour or rarity,
 * and its balance numbers along the bottom. Read-only, and always the owner's latest saved version.
 * What a friend wants from it is to look, then either keep a copy (into their own library —
 * localStorage for a guest, so no account is needed) or play it straight away. "Copy list" covers
 * everything else.
 */
import { useEffect, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useNavigate, useParams } from 'react-router-dom'
import { type SharedCubeView, getSharedCube } from '@/api/account'
import type { CardSummary } from '@/components/deckbuilder/cardFilter'
import { CubeColumns, CubeColumnsToolbar, CubeRailPreview, useCubeColumns } from '@/components/cube/CubeColumns'
import { CubePlayButton } from '@/components/cube/CubePlayButton'
import { CubeStats, PackStrip } from '@/components/cube/CubeStats'
import { cubeToText, isBoosterStyle, uniqueCubeName } from '@/components/cube/cubeModel'
import { useCardCatalog } from '@/components/cube/useCardCatalog'
import { PageShell, pageStyles as p } from '@/components/ui/PageShell'
import { useHoverStore } from '@/components/ui/hoverStore'
import { cubeCardCount } from '@/store/cubeLibrary'
import { type UnifiedCube, cubeToShared, useUnifiedCubes } from '@/store/useUnifiedCubes'
import db from '@/components/deckbuilder/deckbuilder.module.css'
import styles from '@/components/cube/SharedCube.module.css'

type Load =
  | { state: 'loading' }
  | { state: 'missing' }
  | { state: 'error'; message: string }
  | { state: 'ready'; view: SharedCubeView }

export function SharedCubePage() {
  const { t } = useTranslation()
  const { code = '' } = useParams<{ code: string }>()
  const [load, setLoad] = useState<Load>({ state: 'loading' })

  useEffect(() => {
    let cancelled = false
    setLoad({ state: 'loading' })
    getSharedCube(code)
      .then((view) => {
        if (!cancelled) setLoad(view ? { state: 'ready', view } : { state: 'missing' })
      })
      .catch((err: unknown) => {
        if (!cancelled) setLoad({ state: 'error', message: err instanceof Error ? err.message : '' })
      })
    return () => {
      cancelled = true
    }
  }, [code])

  if (load.state === 'ready') {
    return (
      <PageShell title={t('library.sharedCube.pageTitle')} fit width="full" plain>
        <SharedCube view={load.view} />
      </PageShell>
    )
  }
  return (
    <PageShell title={t('library.sharedCube.pageTitle')} width="wide">
      {load.state === 'loading' && <p className={p.empty}>{t('library.sharedCube.loading')}</p>}
      {(load.state === 'missing' || load.state === 'error') && <NotFound message={load.state === 'error' ? load.message || null : null} />}
    </PageShell>
  )
}

function NotFound({ message }: { message: string | null }) {
  const { t } = useTranslation()
  const navigate = useNavigate()
  return (
    <div className={p.panel}>
      <h1 className={p.h1}>{t('library.sharedCube.notFoundTitle')}</h1>
      <p className={p.lede}>
        {message ?? t('library.sharedCube.notFoundBody')}
      </p>
      <p>
        <button type="button" className={p.button} onClick={() => navigate('/library?tab=cubes')}>
          {t('library.sharedCube.yourCubes')}
        </button>
      </p>
    </div>
  )
}

function SharedCube({ view }: { view: SharedCubeView }) {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const catalog = useCardCatalog()
  const library = useUnifiedCubes()
  const [copied, setCopied] = useState<UnifiedCube | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const hover = useHoverStore<CardSummary>()

  const cube = view.cube
  const lookup = useMemo(
    () => (name: string) => catalog.byName.get(name.toLowerCase()),
    [catalog.byName],
  )
  const total = cubeCardCount(cube.cards)
  const unresolved = catalog.ready ? cube.cards.filter((e) => !lookup(e.name)).length : 0
  const style = cube.boosterStyle ?? 'SHUFFLED'
  const rarityMatters = isBoosterStyle(style)

  const columns = useCubeColumns(cube.cards, lookup, catalog.ready)
  const { groups } = columns

  // The rail is never blank: until something is hovered, it shows the cube's first card.
  const firstCard = useMemo(() => {
    const first = groups[0]?.entries[0]
    return first ? lookup(first.name) ?? null : null
  }, [groups, lookup])

  async function saveCopy() {
    setBusy(true)
    setNotice(null)
    try {
      const name = uniqueCubeName(cube.name, library.cubes.map((c) => c.name))
      const saved = await library.saveCube({ ...cubeToShared(cube), name })
      setCopied(saved)
    } catch (err) {
      setNotice(err instanceof Error ? err.message : t('library.sharedCube.saveError'))
    } finally {
      setBusy(false)
    }
  }

  async function copyList() {
    try {
      await navigator.clipboard.writeText(cubeToText(cube))
      setNotice(t('library.sharedCube.listCopied'))
    } catch {
      setNotice(t('library.sharedCube.clipboardBlocked'))
    }
  }

  return (
    <div className={`${db.workspace} ${db.workspaceDeckMode}`}>
      <aside className={db.left}>
        <CubeRailPreview hover={hover} fallback={firstCard} />
        {rarityMatters && (
          <div className={styles.packCard}>
            <span className={styles.packLabel}>{t('library.sharedCube.eachPack')}</span>
            <PackStrip style={style} />
            <span className={styles.packBlurb}>{t(`library.cubeStyles.${style}.blurb`)}</span>
          </div>
        )}
      </aside>

      <main className={db.centerDeck}>
        <header className={styles.header}>
          <div className={styles.titleBlock}>
            <h1 className={styles.title}>{cube.name}</h1>
            <span className={styles.byline}>
              {view.ownerName ? t('library.sharedCube.sharedBy', { name: view.ownerName }) : t('library.sharedCube.aSharedCube')} ·{' '}
              {t('library.sharedCube.cardCount', { count: total })} · {t(`library.cubeStyles.${style}.label`)}
            </span>
            {cube.description && <p className={styles.description}>{cube.description}</p>}
          </div>
          <div className={styles.actions}>
            <CubePlayButton
              cube={cube}
              blockedReason={unresolved > 0 ? t('library.sharedCube.unimplementedCards', { count: unresolved }) : null}
            />
            {copied ? (
              <button
                type="button"
                className={p.button}
                onClick={() => navigate(`/cubes/edit/${encodeURIComponent(copied.id)}`)}
              >
                {t('library.sharedCube.openCopy')}
              </button>
            ) : (
              <button type="button" className={p.button} disabled={busy || !library.ready} onClick={() => void saveCopy()}>
                {busy ? t('library.sharedCube.saving') : t('library.sharedCube.saveCopy')}
              </button>
            )}
            <button type="button" className={p.buttonGhost} onClick={() => void copyList()}>
              {t('library.sharedCube.copyList')}
            </button>
          </div>
        </header>

        {(copied || notice) && (
          <p className={styles.notice} role="status">
            {copied
              ? t('library.sharedCube.copySaved', {
                  name: copied.name,
                  location: t(copied.online ? 'library.sharedCube.inAccount' : 'library.sharedCube.onDevice'),
                })
              : notice}
          </p>
        )}

        <CubeColumnsToolbar state={columns} total={cube.cards.length} />

        <CubeColumns
          groups={groups}
          filter={columns.filter}
          lookup={lookup}
          catalogReady={catalog.ready}
          rarityMatters={rarityMatters}
          hover={hover}
          fallbackName={firstCard?.name ?? null}
        />

        <div className={styles.footer}>
          <CubeStats entries={cube.cards} lookup={lookup} boosterStyle={style} packSize={cube.packSize} />
        </div>
      </main>
    </div>
  )
}
