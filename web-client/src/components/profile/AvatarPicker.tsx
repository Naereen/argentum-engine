/**
 * The avatar picker: a preview of the pick on top, the preset portraits as a radio grid below, and a
 * "Use my initial" button for the default (and the way back to it). Arrow keys move through the
 * grid, Enter saves, Escape closes.
 */
import { useRef, useState } from 'react'
import type React from 'react'
import { useTranslation } from 'react-i18next'
import { pageStyles as p } from '@/components/ui/PageShell'
import { AccountModal } from './accountUi'
import { AvatarArt } from './AvatarArt'
import { AVATARS, AVATAR_GROUPS, avatarOption, initialOf, resolveAvatar } from './avatars'
import { CardArtPanel, type CardArtSelection, cardSelectionValue } from './CardArtPanel'
import s from './AvatarPicker.module.css'

/** `null` is the display name's initial. */
type Choice = string | null

export function AvatarPicker({
  name,
  current,
  onSave,
  onClose,
}: {
  name: string
  current: string | null
  onSave: (avatar: string | null) => Promise<void>
  onClose: () => void
}) {
  const { t } = useTranslation()
  const choices: string[] = AVATARS.map((a) => a.id)
  const initial = resolveAvatar(current)
  const [tab, setTab] = useState<'portraits' | 'card'>(initial?.kind === 'card' ? 'card' : 'portraits')
  const [selected, setSelected] = useState<Choice>(initial ? current : null)
  const [card, setCard] = useState<CardArtSelection | null>(
    initial?.kind === 'card' ? { name: null, path: initial.path, crop: initial.crop } : null,
  )
  const chooseCard = (sel: CardArtSelection | null) => {
    setCard(sel)
    const value = cardSelectionValue(sel)
    if (value) setSelected(value)
  }
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const gridRef = useRef<HTMLDivElement>(null)

  const option = avatarOption(selected)
  const resolved = resolveAvatar(selected)
  const unchanged = selected === (initial ? current : null)

  const save = async () => {
    if (unchanged) {
      onClose()
      return
    }
    setSaving(true)
    setError(null)
    try {
      await onSave(selected)
      onClose()
    } catch (e) {
      setError(e instanceof Error ? t('avatarUi.saveError', { message: e.message }) : t('avatarUi.saveErrorGeneric'))
      setSaving(false)
    }
  }

  const focusChoice = (index: number) => {
    const clamped = Math.max(0, Math.min(choices.length - 1, index))
    setSelected(choices[clamped] ?? null)
    gridRef.current?.querySelectorAll<HTMLButtonElement>('[role="radio"]')[clamped]?.focus()
  }

  const onGridKey = (e: React.KeyboardEvent) => {
    const index = selected ? choices.indexOf(selected) : -1
    const columns = gridRef.current
      ? getComputedStyle(gridRef.current).gridTemplateColumns.split(' ').length
      : 1
    const step = { ArrowRight: 1, ArrowLeft: -1, ArrowDown: columns, ArrowUp: -columns } as Record<string, number>
    if (e.key in step) {
      e.preventDefault()
      focusChoice(index + (step[e.key] ?? 0))
    } else if (e.key === 'Home') {
      e.preventDefault()
      focusChoice(0)
    } else if (e.key === 'End') {
      e.preventDefault()
      focusChoice(choices.length - 1)
    } else if (e.key === 'Enter') {
      e.preventDefault()
      void save()
    }
  }

  return (
    <AccountModal title={t('avatarUi.chooseTitle')} onClose={onClose}>
      <div className={s.preview} style={{ '--tint': option?.tint ?? '#f2b45c' } as React.CSSProperties}>
        <span className={s.previewPortrait} data-portrait={resolved !== undefined}>
          {resolved ? <AvatarArt avatar={resolved} /> : initialOf(name)}
        </span>
        <div className={s.previewText}>
          <span className={s.previewName}>{name}</span>
          <span className={s.previewLabel}>{option ? option.name : resolved?.kind === 'card' ? `${t('avatarUi.cardArtLabel')}${card?.name ? ` · ${card.name}` : ''}` : t('avatarUi.useInitial')}</span>
          <span className={s.previewHint}>
            {t('avatarUi.shownOnProfile')}
          </span>
        </div>
      </div>

      <div className={s.tabs} role="tablist">
        <button type="button" role="tab" aria-selected={tab === 'portraits'} className={s.tab} onClick={() => setTab('portraits')}>{t('avatarUi.portraits')}</button>
        <button type="button" role="tab" aria-selected={tab === 'card'} className={s.tab} onClick={() => setTab('card')} data-testid="avatar-tab-card">{t('avatarUi.cardArt')}</button>
      </div>

      {tab === 'card' ? (
        <div className={s.body}><CardArtPanel selection={card} onSelect={chooseCard} /></div>
      ) : (
      <div
        ref={gridRef}
        className={s.portraits}
        role="radiogroup"
        aria-label={t('avatarUi.avatars')}
        onKeyDown={onGridKey}
      >
        {AVATAR_GROUPS.map((group) => (
          <section key={group.label} className={s.group} aria-label={group.label}>
            <h3 className={s.groupLabel}>
              <span className={s.groupDot} style={{ background: group.color }} aria-hidden />
              {group.label === 'White' ? t('avatarUi.groups.white')
                : group.label === 'Blue' ? t('avatarUi.groups.blue')
                  : group.label === 'Black' ? t('avatarUi.groups.black')
                    : group.label === 'Red' ? t('avatarUi.groups.red')
                      : group.label === 'Green' ? t('avatarUi.groups.green')
                        : group.label === 'Across the Multiverse' ? t('avatarUi.groups.multiverse')
                          : t('avatarUi.groups.artifacts')}
            </h3>
            <div className={s.grid}>
              {group.avatars.map(({ id }) => {
                const opt = avatarOption(id)
                const checked = id === selected
                // Roving tab stop: the checked portrait, or the first one when no portrait is picked.
                const tabStop = checked || (!avatarOption(selected) && id === choices[0])
                return (
                <button
                  key={id}
                  type="button"
                  role="radio"
                  aria-checked={checked}
                  aria-label={opt?.name ?? id}
                  title={opt?.name ?? id}
                  tabIndex={tabStop ? 0 : -1}
                  className={s.tile}
                  style={{ '--tint': opt?.tint ?? '#f2b45c' } as React.CSSProperties}
                  onClick={() => setSelected(id)}
                  onDoubleClick={() => void save()}
                  data-testid={`avatar-option-${id}`}
                >
                  <AvatarArt avatar={id} />
                  {checked && (
                    <span className={s.check} aria-hidden>
                      <svg width="12" height="12" viewBox="0 0 12 12" fill="none">
                        <path d="M2.5 6.2 5 8.5l4.5-5" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
                      </svg>
                    </span>
                  )}
                </button>
                )
              })}
            </div>
          </section>
        ))}
      </div>

      )}

      <div className={s.footer}>
        <button
          type="button"
          className={s.initialButton}
          aria-pressed={selected === null}
          onClick={() => setSelected(null)}
          data-testid="avatar-option-initial"
        >
          <span className={s.initialDot} aria-hidden>{initialOf(name)}</span>
          {t('avatarUi.useInitial')}
        </button>
        <span className={s.spacer} />
        {error && <span className={s.error}>{error}</span>}
        <button type="button" className={p.buttonGhost} onClick={onClose}>
          {t('common.cancel')}
        </button>
        <button
          type="button"
          className={p.buttonPrimary}
          disabled={saving || unchanged}
          onClick={() => void save()}
          data-testid="avatar-save"
        >
          {saving ? t('avatarUi.saving') : t('avatarUi.save')}
        </button>
      </div>
    </AccountModal>
  )
}
