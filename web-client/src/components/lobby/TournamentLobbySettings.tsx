/**
 * The knobs that only a tournament-backed lobby has.
 *
 * `LobbyScreen` owns everything both lobby kinds share — the axes, visibility, ranked, the player
 * list, the actions row. What's left is genuinely specific to the tournament implementation: a
 * card pool has sets, boosters and a ban list; a draft has timers; a team table has team setup.
 * Keeping them here rather than behind `view.kind === 'TOURNAMENT'` checks scattered through the
 * screen is what keeps the screen readable as one thing.
 *
 * Everything in this file is a faithful move out of the old `LobbyOverlay`, except that the rows
 * are ordered by what they belong to rather than by the order they were added.
 */
import { useEffect, useState } from 'react'
import { useGameStore } from '@/store/gameStore'
import type { LobbyState } from '@/store/slices/types'
import { teamColor } from '@/styles/seatColors'
import { BanListEditor } from '../ui/BanListEditor'
import { CubePanel } from './CubePanel'
import { SetSelector, nextRandomSetCode } from '../ui/SetSelector'
import { SettingsLabel } from '../ui/SettingsLabel'
import { effectiveCommanderPreset } from './axes'
import type { UnifiedLobbyView } from './lobbyViewModel'
import type { GroupId } from './settingsGroups'
import { PICK_TIME_OPTIONS } from './pickTime'
import { JUMPSTART_SETS } from './jumpstartSets'
import styles from '../ui/GameUI.module.css'
import { useTranslation } from 'react-i18next'

/**
 * The tournament-only rows belonging to one settings group.
 *
 * Everything below is a faithful move from the single flat list this file used to render; what
 * changed is that each row now declares which axis it refines, and `LobbyScreen` asks for one group
 * at a time. The mapping is not a new taxonomy — it is what the conditions in this file already
 * said. The Commander deckbuild knobs were already gated on `axes.rules === 'COMMANDER'`, the
 * matchup count on `axes.event === 'ROUND_ROBIN'`, and the attack rule on a Free-for-All *table*.
 *
 * | group | rows |
 * |---|---|
 * | Cards | cube · card pool · sets · booster mix · ban list · packs · pick timer · cards per pick |
 * | Rules | preset · min deck size · singleton · copy limit |
 * | Table | teams · attack |
 * | Event | match length (best of 1 / 3), times each pair meets |
 * | This lobby | AI assistance |
 *
 * There is no Seats row to place: the lobby already holds as many players as its shape allows and
 * people join until it is full.
 */
export function TournamentLobbySettings({
  group,
  view,
  lobbyState,
}: {
  group: GroupId
  view: UnifiedLobbyView
  lobbyState: LobbyState
}) {
  const { t } = useTranslation()
  const updateLobbySettings = useGameStore((s) => s.updateLobbySettings)

  const s = lobbyState.settings
  const format = s.format
  const isSealed = format === 'SEALED'
  const isDraft = format === 'DRAFT'
  const isWinston = format === 'WINSTON_DRAFT'
  const isGridDraft = format === 'GRID_DRAFT'
  const isCommanderDraft = format === 'COMMANDER_DRAFT'
  const isPremade = format === 'PREMADE_DECKS'
  const isAnyDraft = isDraft || isWinston || isGridDraft || isCommanderDraft
  // The Commander deckbuild knobs (life preset, minimum deck size, singleton) tune a *pool-built*
  // 60-card Commander deck, which is what the server validates against — so they follow the Rules
  // axis rather than the pack format (a Commander game over ordinary draft packs wants them too),
  // minus the brought-deck case, where paper Commander's own construction rules apply instead.
  const isPoolBuiltCommander = view.axes.rules === 'COMMANDER' && !isPremade
  // A pod overrides the host's 1v1 life tuning with paper Commander's 40 (see
  // TournamentLobby.effectiveCommanderPreset), so the picker reports rather than offers.
  const effectivePreset = effectiveCommanderPreset(s.commanderPreset, s.gameMode)
  const podPreset = effectivePreset === 'POD'
  const isFfa = s.gameMode === 'FREE_FOR_ALL'
  const isCube = Boolean(s.cubeName)
  const [cardSource, setCardSource] = useState<'SETS' | 'CUBE'>(isCube ? 'CUBE' : 'SETS')
  useEffect(() => {
    setCardSource(isCube ? 'CUBE' : 'SETS')
  }, [isCube])

  const chooseCardSource = (source: 'SETS' | 'CUBE') => {
    setCardSource(source)
    if (source === 'SETS' && isCube) updateLobbySettings({ cubeCards: [] })
  }
  // Pool Play hands out the whole cube instead of dealing packs, so the pack-count controls are
  // meaningless while it's on. Matches TournamentLobby.isCubePoolPlay.
  const isPoolPlay = isCube && isSealed && Boolean(s.cubePoolPlay)

  const allSets = s.availableSets

  const toggleSet = (code: string) => {
    const removing = s.setCodes.includes(code)
    const next = removing ? s.setCodes.filter((c) => c !== code) : [...s.setCodes, code]
    const includedSetProducts = { ...s.includedSetProducts }
    if (removing) delete includedSetProducts[code]
    updateLobbySettings({ setCodes: next, includedSetProducts })
  }

  const toggleSetProduct = (setCode: string, productId: string) => {
    const current = s.includedSetProducts[setCode] ?? []
    const next = current.includes(productId)
      ? current.filter((id) => id !== productId)
      : [...current, productId]
    updateLobbySettings({
      includedSetProducts: { ...s.includedSetProducts, [setCode]: next },
    })
  }

  // "Random Set" in the picker: a deferred slot the server rolls to a complete, non-extension set
  // at game start. Suffixed codes keep multiple random slots distinct.
  const addRandomSet = () => {
    updateLobbySettings({ setCodes: [...s.setCodes, nextRandomSetCode(s.setCodes)] })
  }

  const perSetCounts = s.setCodes.length > 1 && !s.chaosBoosters
  // Booster Draft and Commander Draft count *packs*, capped at 6. Sealed and Winston count
  // boosters, capped at 16 — Winston hands out a shared pile, so it sits with sealed here even
  // though it is a draft everywhere else.
  const countsPacks = isDraft || isCommanderDraft
  const boosterCap = countsPacks ? 6 : 16

  return (
    <>
      {/* Team setup (2HG — CR 810; Team vs. Team — CR 808). */}
      {group === 'TABLE' && view.teams.mode !== 'NONE' && (
        <div className={styles.settingsRow}>
          <SettingsLabel topicId="table-two-headed-giant">{t('lobbySettings.teams')}</SettingsLabel>
          <div className={styles.variantGroup}>
            <div className={styles.settingsButtons}>
              <button
                onClick={() => updateLobbySettings({ randomTeams: true })}
                className={`${styles.settingsButton} ${view.teams.mode === 'RANDOM' ? styles.settingsButtonActive : ''}`}
                title={t('lobbySettings.randomTeamsHint')}
              >
                {t('lobbySettings.random')}
              </button>
              <button
                onClick={() => updateLobbySettings({ randomTeams: false })}
                className={`${styles.settingsButton} ${view.teams.mode === 'MANUAL' ? styles.settingsButtonActive : ''}`}
                title={t('lobbySettings.manualTeamsHint')}
              >
                {t('lobbySettings.chooseTeams')}
              </button>
            </div>
            <div className={styles.variantCaption}>
              {view.teams.mode === 'RANDOM'
                ? t('lobbySettings.randomTeamsDescription')
                : view.teams.balanced
                  ? t('lobbySettings.movePlayerTeamHint')
                  : t('lobbySettings.teamBalanceHint', { count: view.teams.size })}
            </div>
          </div>
        </div>
      )}

      {/* Free-for-All attack rule (CR 802/803) — only relevant once 3+ players share one table. */}
      {group === 'TABLE' && isFfa && (
        <div className={styles.settingsRow}>
          <SettingsLabel topicId="table-free-for-all">{t('lobbySettings.attack')}</SettingsLabel>
          <div className={styles.variantGroup}>
            <div className={styles.settingsButtons}>
              {([
                ['LEFT', 'lobbySettings.leftOnly', 'lobbySettings.attackLeftHint'],
                ['RIGHT', 'lobbySettings.rightOnly', 'lobbySettings.attackRightHint'],
                ['MULTIPLE', 'lobbySettings.anyOpponent', 'lobbySettings.attackAnyHint'],
              ] as const).map(([mode, label, title]) => (
                <button
                  key={mode}
                  onClick={() => updateLobbySettings({ attackMode: mode })}
                  className={`${styles.settingsButton} ${(s.attackMode ?? 'LEFT') === mode ? styles.settingsButtonActive : ''}`}
                  title={t(title)}
                >
                  {t(label)}
                </button>
              ))}
            </div>
            <div className={styles.variantCaption}>
              {t('lobbySettings.attackDescription')}
            </div>
          </div>
        </div>
      )}

      {/* Sets and cubes are alternative pack sources. Keep them in one row so the relationship is
          explicit; a lobby with no cube starts on Sets. */}
      {group === 'CARDS' && !isPremade && (
        <div className={styles.settingsRow} style={{ alignItems: 'flex-start' }}>
          <span style={{ paddingTop: 7 }}>
            <SettingsLabel topicId="cards-cube">{t('lobbySettings.cardSource')}</SettingsLabel>
          </span>
          <div className={styles.cardSourceControl}>
            <div className={styles.settingsButtons}>
              <button
                type="button"
                aria-pressed={cardSource === 'SETS'}
                onClick={() => chooseCardSource('SETS')}
                className={`${styles.settingsButton} ${cardSource === 'SETS' ? styles.settingsButtonActive : ''}`}
              >
                {t('lobbySettings.sets')}
              </button>
              <button
                type="button"
                aria-pressed={cardSource === 'CUBE'}
                onClick={() => chooseCardSource('CUBE')}
                className={`${styles.settingsButton} ${cardSource === 'CUBE' ? styles.settingsButtonActive : ''}`}
              >
                {t('lobbySettings.cube')}
              </button>
            </div>

            {cardSource === 'CUBE' ? (
              <CubePanel
                settings={s}
                playerCount={view.players.length}
                updateLobbySettings={updateLobbySettings}
              />
            ) : (
              <SetSelector
                sets={allSets}
                selectedCodes={s.setCodes}
                onToggleSet={toggleSet}
                onSelectRandom={addRandomSet}
                selectedProducts={s.includedSetProducts}
                onToggleProduct={toggleSetProduct}
                accent={isAnyDraft ? 'draft' : 'sealed'}
              />
            )}
          </div>
        </div>
      )}

      {group === 'CARDS' && s.jumpstartEligible && (
        <div className={styles.settingsRow}>
          <span className={styles.settingsLabel}>{t('lobbySettings.jumpIn')}</span>
          <div className={styles.variantGroup}>
            <div className={styles.settingsButtons}>
              <button aria-pressed={s.useJumpstart !== false}
                onClick={() => updateLobbySettings({ useJumpstart: true })}
                className={`${styles.settingsButton} ${s.useJumpstart !== false ? styles.settingsButtonActive : ''}`}>
                {t('lobbySettings.chooseThemedPacks')}
              </button>
              <button aria-pressed={s.useJumpstart === false}
                onClick={() => updateLobbySettings({ useJumpstart: false })}
                className={`${styles.settingsButton} ${s.useJumpstart === false ? styles.settingsButtonActive : ''}`}>
                {t(isDraft ? 'lobbySettings.traditionalDraft' : 'lobbySettings.traditionalSealed')}
              </button>
            </div>
            <p className={styles.variantCaption}>
              {s.useJumpstart !== false
                ? t('lobbySettings.jumpInDescription')
                : t('lobbySettings.traditionalDescription')}
            </p>
          </div>
        </div>
      )}

      {/* Which published Jumpstart packs are dealt — a one-click switch between the Jumpstart sets,
          rather than hunting for them in the full set list above. */}
      {group === 'CARDS' && s.jumpstartActive && (
        <div className={styles.settingsRow}>
          <SettingsLabel topicId="cards-jump-in">{t('lobbySettings.packsFrom')}</SettingsLabel>
          <div className={styles.settingsButtons}>
            {JUMPSTART_SETS.filter((j) => allSets.some((set) => set.code === j.code)).map((j) => (
              <button
                key={j.code}
                type="button"
                aria-pressed={s.setCodes[0] === j.code}
                onClick={() => updateLobbySettings({ setCodes: [j.code] })}
                className={`${styles.settingsButton} ${s.setCodes[0] === j.code ? styles.settingsButtonActive : ''}`}
                title={allSets.find((set) => set.code === j.code)?.name}
              >
                {j.label} <span style={{ opacity: 0.6 }}>{j.year}</span>
              </button>
            ))}
          </div>
        </div>
      )}

      {/* Pool Play (cube Sealed only): no draft at all — everyone builds from the whole cube. */}
      {group === 'CARDS' && isCube && isSealed && (
        <div className={styles.settingsRow}>
          <SettingsLabel topicId="cube-pool-play">{t('lobbySettings.cardPool')}</SettingsLabel>
          <div className={styles.variantGroup}>
            <div className={styles.settingsButtons}>
              <button
                onClick={() => updateLobbySettings({ cubePoolPlay: false })}
                className={`${styles.settingsButton} ${!s.cubePoolPlay ? styles.settingsButtonActive : ''}`}
                title={t('lobbySettings.sealedPacksHint')}
              >
                {t('lobbySettings.sealedPacks')}
              </button>
              <button
                onClick={() => updateLobbySettings({ cubePoolPlay: true })}
                className={`${styles.settingsButton} ${s.cubePoolPlay ? styles.settingsButtonActive : ''}`}
                title={t('lobbySettings.poolPlayHint')}
              >
                {t('lobbySettings.poolPlay')}
              </button>
            </div>
            <div className={styles.variantCaption}>
              {s.cubePoolPlay
                ? t('lobbySettings.poolPlayDescription')
                : t('lobbySettings.sealedCubeDescription')}
            </div>
          </div>
        </div>
      )}

      {group === 'CARDS' && !isPremade && cardSource === 'SETS' && (
        <>
          {/* Chaos boosters — only meaningful with >1 set and a booster-based format. */}
          {!isGridDraft && s.setCodes.length > 1 && (
            <div className={styles.settingsRow}>
              <span className={styles.settingsLabel}>{t('lobbySettings.boosterMix')}</span>
              <div className={styles.variantGroup}>
                <div className={styles.settingsButtons}>
                  <button
                    onClick={() => updateLobbySettings({ chaosBoosters: false })}
                    className={`${styles.settingsButton} ${!s.chaosBoosters ? styles.settingsButtonActive : ''}`}
                  >
                    {t('lobbySettings.perSet')}
                  </button>
                  <button
                    onClick={() => updateLobbySettings({ chaosBoosters: true })}
                    className={`${styles.settingsButton} ${s.chaosBoosters ? styles.settingsButtonActive : ''}`}
                  >
                    {t('lobbySettings.chaos')}
                  </button>
                </div>
                <div className={styles.variantCaption}>
                  {s.chaosBoosters
                    ? t('lobbySettings.chaosBoosterDescription')
                    : t('lobbySettings.perSetBoosterDescription')}
                </div>
              </div>
            </div>
          )}

          <BanListEditor
            setCodes={s.setCodes}
            bannedCardNames={s.bannedCardNames ?? []}
            onChange={(names) => updateLobbySettings({ bannedCardNames: names })}
          />
        </>
      )}

      {/* Booster/pack counts. Grid Draft uses fixed counts, Premade generates none, and Pool Play
          deals no packs at all — so it gets no pack count rather than an inert one. */}
      {group === 'CARDS' && !s.jumpstartActive && !isPremade && !isGridDraft && !isPoolPlay && (
        perSetCounts ? (
          <div className={styles.settingsRow} style={{ flexDirection: 'column', alignItems: 'stretch', gap: 8 }}>
            <span className={styles.settingsLabel}>{t(isWinston ? 'lobbySettings.boostersTotal' : countsPacks ? 'lobbySettings.packsPerPlayer' : 'lobbySettings.boostersPerPlayer')}</span>
            <div className={styles.boosterDistribution}>
              {s.setCodes.map((code) => {
                const setName = s.setNames[s.setCodes.indexOf(code)] ?? code
                const dist = s.boosterDistribution
                const count = dist[code] ?? 0
                const total = Object.values(dist).reduce((a, b) => a + b, 0)
                return (
                  <div key={code} className={styles.boosterDistributionRow}>
                    <span className={styles.boosterDistributionSetName}>{setName}</span>
                    <div className={styles.boosterDistributionControls}>
                      <button
                        className={styles.boosterDistributionBtn}
                        disabled={count <= 0}
                        onClick={() => updateLobbySettings({
                          boosterDistribution: { ...dist, [code]: count - 1 },
                          boosterCount: total - 1,
                        })}
                      >-</button>
                      <span className={styles.boosterDistributionCount}>{count}</span>
                      <button
                        className={styles.boosterDistributionBtn}
                        disabled={total >= boosterCap}
                        onClick={() => updateLobbySettings({
                          boosterDistribution: { ...dist, [code]: count + 1 },
                          boosterCount: total + 1,
                        })}
                      >+</button>
                    </div>
                  </div>
                )
              })}
              <div className={styles.boosterDistributionTotal}>
                <span style={{ flex: 1 }}>{t('lobbySettings.total')}</span>
                <span className={styles.boosterDistributionTotalCount}>
                  {Object.values(s.boosterDistribution).reduce((a, b) => a + b, 0)}
                  {countsPacks ? ` ${t('lobbySettings.packs')}` : ` ${t('lobbySettings.boosters')}`}
                </span>
              </div>
            </div>
          </div>
        ) : (
          <div className={styles.settingsRow}>
            <span className={styles.settingsLabel}>
              {t(isWinston ? 'lobbySettings.boostersTotal' : countsPacks ? 'lobbySettings.packsPerPlayer' : 'lobbySettings.boostersPerPlayer')}
            </span>
            <select
              value={s.boosterCount}
              onChange={(e) => updateLobbySettings({ boosterCount: Number(e.target.value) })}
              className={styles.settingsSelect}
            >
              {Array.from({ length: boosterCap }, (_, i) => i + 1).map((n) => (
                <option key={n} value={n}>{n}</option>
              ))}
            </select>
          </div>
        )
      )}

      {/* Draft timing and pick size. */}
      {group === 'CARDS' && !s.jumpstartActive && isAnyDraft && (
        <div className={styles.settingsRow}>
          <span className={styles.settingsLabel}>{t(isWinston ? 'lobbySettings.turnTimer' : 'lobbySettings.pickTimer')}</span>
          <select
            value={s.pickTimeSeconds}
            onChange={(e) => updateLobbySettings({ pickTimeSeconds: Number(e.target.value) })}
            className={styles.settingsSelect}
          >
            {PICK_TIME_OPTIONS.map((n) => <option key={n} value={n}>{n === 0 ? t('lobbySettings.noLimit') : t('lobbySettings.seconds', { count: n })}</option>)}
          </select>
        </div>
      )}
      {group === 'CARDS' && (isDraft || isCommanderDraft) && (
        <div className={styles.settingsRow}>
          <span className={styles.settingsLabel}>{t('lobbySettings.cardsPerPick')}</span>
          <div className={styles.settingsButtons}>
            {[1, 2].map((n) => (
              <button
                key={n}
                onClick={() => updateLobbySettings({ picksPerRound: n })}
                className={`${styles.settingsButton} ${s.picksPerRound === n ? `${styles.settingsButtonActive} ${styles.settingsButtonDraft}` : ''}`}
              >
                {n}
              </button>
            ))}
          </div>
        </div>
      )}

      {/* Commander preset + deck-shape knobs — a Commander game built from a generated pool. */}
      {group === 'RULES' && isPoolBuiltCommander && (
        <>
          <div className={styles.settingsRow}>
            <span className={styles.settingsLabel}>{t('lobbySettings.preset')}</span>
            <div className={styles.variantGroup}>
              <div className={styles.settingsButtons}>
                {(['BRAWL', 'COMMANDER'] as const).map((preset) => (
                  <button
                    key={preset}
                    onClick={() => updateLobbySettings({ commanderPreset: preset })}
                    disabled={podPreset}
                    className={`${styles.settingsButton} ${effectivePreset === preset ? styles.settingsButtonActive : ''}`}
                    title={t(preset === 'BRAWL' ? 'lobbySettings.brawlPresetHint' : 'lobbySettings.commanderPresetHint')}
                  >
                    {t(preset === 'BRAWL' ? 'lobbySettings.brawlPreset' : 'lobbySettings.commanderPreset')}
                  </button>
                ))}
                {/* The pod preset is the table's, not the host's — shown as the live selection, not a choice. */}
                {podPreset && (
                  <button
                    disabled
                    className={`${styles.settingsButton} ${styles.settingsButtonActive}`}
                    title={t('lobbySettings.podPresetHint')}
                  >
                    {t('lobbySettings.podPreset')}
                  </button>
                )}
              </div>
              {podPreset && (
                <div className={styles.variantCaption}>
                  {t('lobbySettings.podPresetDescription')}
                </div>
              )}
            </div>
          </div>
          <div className={styles.settingsRow}>
            <span className={styles.settingsLabel}>{t('lobbySettings.minDeckSize')}</span>
            <select
              value={s.deckSizeMin}
              onChange={(e) => updateLobbySettings({ deckSizeMin: Number(e.target.value) })}
              className={styles.settingsSelect}
            >
              {[40, 50, 60, 75, 100].map((n) => <option key={n} value={n}>{n}</option>)}
            </select>
          </div>
          <div className={styles.settingsRow}>
            <span className={styles.settingsLabel}>{t('lobbySettings.singleton')}</span>
            <div className={styles.settingsButtons}>
              <button
                onClick={() => updateLobbySettings({ allowDuplicates: true })}
                className={`${styles.settingsButton} ${s.allowDuplicates ? styles.settingsButtonActive : ''}`}
                title={t('lobbySettings.duplicatesHint')}
              >
                {t('lobbySettings.duplicatesOk')}
              </button>
              <button
                onClick={() => updateLobbySettings({ allowDuplicates: false })}
                className={`${styles.settingsButton} ${!s.allowDuplicates ? styles.settingsButtonActive : ''}`}
                title={t('lobbySettings.singletonHint')}
              >
                {t('lobbySettings.singleton')}
              </button>
            </div>
          </div>
        </>
      )}

      {/* Limited copy cap — a deck built from opened/drafted packs. Pool Play always caps at 4 (its
          pool has no copy count), brought decks are constructed, and singleton already caps at 1. */}
      {group === 'RULES' && !isPremade && !s.jumpstartActive && !isPoolPlay &&
        !(isPoolBuiltCommander && !s.allowDuplicates) && (
        <div className={styles.settingsRow}>
          <SettingsLabel topicId="limited-deckbuilding">{t('lobbySettings.copyLimit')}</SettingsLabel>
          <div className={styles.variantGroup}>
            <div className={styles.settingsButtons}>
              <button
                onClick={() => updateLobbySettings({ limitedCopyLimit: false })}
                className={`${styles.settingsButton} ${!s.limitedCopyLimit ? styles.settingsButtonActive : ''}`}
                title={t('lobbySettings.noCopyLimitHint')}
              >
                {t('lobbySettings.none')}
              </button>
              <button
                onClick={() => updateLobbySettings({ limitedCopyLimit: true })}
                className={`${styles.settingsButton} ${s.limitedCopyLimit ? styles.settingsButtonActive : ''}`}
                title={t('lobbySettings.maxFourCopiesHint')}
              >
                {t('lobbySettings.maxFour')}
              </button>
            </div>
            <div className={styles.variantCaption}>
              {s.limitedCopyLimit
                ? t('lobbySettings.maxFourDescription')
                : t('lobbySettings.noCopyLimitDescription')}
            </div>
          </div>
        </div>
      )}

      {/* No Seats row: the lobby holds as many as its shape allows and people join until it is full.
          The cap was never a quorum — `startBlockReason` counts the players actually present, so the
          host starts when everyone has arrived — which made it a number to predict and then correct.
          It follows the shape server-side (`LobbyHandler.seatCapFor`), and the header's `n / max`
          says where it currently stands. */}

      {/* Only a bracket has matchups. */}
      {group === 'EVENT' && view.axes.event === 'ROUND_ROBIN' && (
        <>
          <div className={styles.settingsRow}>
            <SettingsLabel topicId="best-of-three">{t('lobbySettings.match')}</SettingsLabel>
            <div className={styles.variantGroup}>
              <div className={styles.settingsButtons}>
                <button
                  onClick={() => updateLobbySettings({ bestOf: 1 })}
                  className={`${styles.settingsButton} ${(s.bestOf ?? 1) < 3 ? styles.settingsButtonActive : ''}`}
                  title={t('lobbySettings.bestOfOneHint')}
                >
                  {t('lobbySettings.bestOfOne')}
                </button>
                <button
                  onClick={() => updateLobbySettings({ bestOf: 3 })}
                  className={`${styles.settingsButton} ${(s.bestOf ?? 1) >= 3 ? styles.settingsButtonActive : ''}`}
                  title={t('lobbySettings.bestOfThreeHint')}
                  data-testid="lobby-best-of-3"
                >
                  {t('lobbySettings.bestOfThree')}
                </button>
              </div>
              <div className={styles.variantCaption}>
                {(s.bestOf ?? 1) >= 3
                  ? s.allowsSideboarding === false
                    ? t('lobbySettings.bestOfThreeNoSideboard')
                    : t('lobbySettings.bestOfThreeSideboard')
                  : t('lobbySettings.singleGameMatch')}
              </div>
            </div>
          </div>
          <div className={styles.settingsRow}>
            <span className={styles.settingsLabel} title={t('lobbySettings.eachPairMeetsHint')}>{t('lobbySettings.eachPairMeets')}</span>
            <select
              value={s.gamesPerMatch ?? 1}
              onChange={(e) => updateLobbySettings({ gamesPerMatch: Number(e.target.value) })}
              className={styles.settingsSelect}
            >
              {[1, 2, 3, 4, 5].map((n) => <option key={n} value={n}>{t(n === 1 ? 'lobbySettings.once' : n === 2 ? 'lobbySettings.twice' : 'lobbySettings.times', { count: n })}</option>)}
            </select>
          </div>
        </>
      )}

      {group === 'LOBBY' && (
      <div className={styles.settingsRow}>
        <span className={styles.settingsLabel} title={t('lobbySettings.aiAssistanceHint')}>
          {t('lobbySettings.aiAssistance')}
        </span>
        <div className={styles.settingsButtons}>
          <button
            onClick={() => updateLobbySettings({ aiAssistEnabled: false })}
            className={`${styles.settingsButton} ${!s.aiAssistEnabled ? styles.settingsButtonActive : ''}`}
          >
            {t('lobby.off')}
          </button>
          <button
            onClick={() => updateLobbySettings({ aiAssistEnabled: true })}
            className={`${styles.settingsButton} ${s.aiAssistEnabled ? styles.settingsButtonActive : ''}`}
          >
            {t('lobbySettings.on')}
          </button>
        </div>
      </div>
      )}

    </>
  )
}

/** Team chip for the player list — colour-coded, and clickable for the host in manual mode. */
export function TeamChip({
  team,
  editable,
  onClick,
}: {
  /** null = teams are randomised at game start. */
  team: number | null
  editable: boolean
  onClick: () => void
}) {
  const c = team === null ? null : teamColor(team)
  const style = {
    fontSize: 10,
    fontWeight: 800,
    letterSpacing: '0.05em',
    textTransform: 'uppercase' as const,
    color: c?.bright ?? 'rgba(226, 232, 240, 0.7)',
    border: `1px solid ${c?.base ?? 'rgba(148, 163, 184, 0.45)'}`,
    background: c?.soft ?? 'rgba(148, 163, 184, 0.12)',
    borderRadius: 4,
    padding: '1px 6px',
  }
  const { t } = useTranslation()
  const label = team === null ? t('lobbySettings.random') : t('lobbySettings.teamNumber', { count: team + 1 })
  return editable && team !== null ? (
    <button onClick={onClick} style={{ ...style, cursor: 'pointer' }} title={t('lobbySettings.movePlayerToOtherTeam')}>
      {label}
    </button>
  ) : (
    <span style={style}>{label}</span>
  )
}
