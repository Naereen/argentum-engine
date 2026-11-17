# Handoff: finish web-client English/French i18n

## Original request: just a reminder

## TODO: Implement Full Internationalization (i18n) for the Web Client UI (English & French)

We need to internationalize the entire `web-client` application so that all UI text can be dynamically switched between **English** (default) and **French**, while laying a clean foundation to easily add other languages (Italian, Spanish, German, etc.) later.

### 1. Technical Stack & Architecture
- **Library:** Inspect `web-client/package.json` and install the standard React i18n ecosystem (`i18next`, `react-i18next`, and `i18next-browser-languagedetector`) if not already present.
- **Locale Structure:**
  - Centralize all extracted UI strings in a clean, hierarchically structured JSON file: `web-client/src/i18n/locales/en.json` (grouped logically by feature/view: `common`, `lobby`, `game`, `phases`, `logs`, `settings`, `docs`, `otaria`, etc.).
  - Provide a complete, natural **French translation** in `web-client/src/i18n/locales/fr.json` covering 100% of the keys defined in `en.json`.
  - Configure TypeScript module augmentation for `i18next` so translation keys are strictly type-checked at compile time.
- **Global Persistence:** Persist the selected language in `localStorage` so that changing the language anywhere updates the entire application immediately and persists across page reloads and routes.

### 2. Scope: What to Translate vs. What NOT to Translate
- **MUST BE TRANSLATED:**
  - All menus, navigation bars, buttons, modals, tooltips, form labels, and error/status notifications.
  - Game lobby, deck selection, player status, phase/step indicators, and settings panels.
  - System/UI game logs and action prompts (use `i18next` interpolation `{{variable}}` properly for dynamic player names, card names, or numbers—do not concatenate raw strings).
  - All documentation/help views and all UI elements specific to the **"Explorer of: Otaria"** campaign mode.
- **MUST NOT BE TRANSLATED (Strict Exclusion):**
  - Magic: The Gathering card Oracle texts, rules text, flavor text, and card names coming from the game engine or Scryfall.
  - Scryfall card image URLs / fetched card assets.
  - Internal WebSocket message types, enum keys, or backend protocol identifiers.

### 3. Language Switcher UI Component
- Create a clean, reusable `<LanguageSwitcher />` component.
- Display the active language clearly using flag emojis and labels: **🇬🇧 English** (default) and **🇫🇷 Français**.
- Render this switcher globally across all views/pages (e.g., in the top navigation bar / header) AND inside the client Settings panel, synchronized with the same global i18n state.

### 4. Testing & Verification
- Write unit/component tests (using the existing test runner in `web-client`, e.g., Vitest / React Testing Library) verifying:
  1. Key parity between `en.json` and `fr.json` (automated test ensuring no missing or extra keys between locale files).
  2. Rendering and interaction of `<LanguageSwitcher />` (switching from `en` to `fr` updates rendered text and persists to `localStorage`).
- Run the test suite and `npm run build` inside `web-client/` to guarantee zero TypeScript or bundling errors.

### 5. Git Workflow (Local Commit Only)
Once all code changes, translations, and tests are implemented and passing:
1. Check `git status` and `git diff` to ensure no unintended or temporary files are included.
2. Stage all relevant modifications using `git add`.
3. Write a comprehensive, well-structured commit message summarizing the i18n architecture, extracted namespaces, French translation, UI switcher, and test coverage, and run `git commit -m "..."`.
4. **IMPORTANT:** Do **NOT** run `git push`. Leave the commit locally for my review.

----

# What you have to do now:

Continue the in-progress i18n task from this working tree. The user chose **core gameplay first** (lobby, game HUD/decisions, preferences, Otaria), while the original goal remains full client i18n. Do not push. The original request asks for a local commit after implementation and checks pass.

## Work already present

- Added `i18next`, `react-i18next`, `i18next-browser-languagedetector`; added RTL and jsdom dev dependencies. `npm test` stays in Vitest's default Node environment; component tests set `// @vitest-environment jsdom` inline.
- Added `src/i18n/index.ts` with English fallback, supported `en`/`fr`, localStorage key `argentum-language`, typed module augmentation at `src/i18n/i18next.d.ts`, and `src/i18n/locales/{en,fr}.json`.
- Added reusable `LanguageSwitcher` in `src/components/ui/LanguageSwitcher.tsx`, stylesheet, persisted-language/render test and locale leaf-key parity test. It is mounted persistently from `src/main.tsx` and also in PreferencesPanel; it updates `<html lang>` through the i18next languageChanged listener.
- Added translated UI across home navigation, preferences, phase labels, gameplay decisions/HUD, Otaria playtest/setup/lobby/map/reserve/movement, account/profile/friends/messages, library/cards/cubes, and stats.
- Game logs now retain structured client events, render recognized events using localized interpolation, preserve card names, and fall back to server descriptions for legacy or unknown entries.
- Added French labels to lobby cube management/capacity, stats filters/dashboard, card-selection empty states, yield controls, initiative notices, speed gauge, stack badges, zone labels, card previews, and gameplay accessibility labels.
- Corrected the stale Otaria layout test assertion in `src/components/game/otariaOverviewLayout.test.ts`: `(true, 1, 1)` is right; use `(true, 0, 1)` for the no-map/null case.
- Existing tests adapted to render translated Otaria components under an `I18nextProvider` and use the changed label copy.

## Verification known at handoff

- `npm run typecheck` passes.
- Full `npm test` passes: 122 files, 1,411 tests.
- `npm run build` passes.
- The running browser was manually checked in French on the Cards and Stats pages, including the global language switcher and route persistence. Gameplay overlays have not had a full scenario-driven browser walkthrough.

## Current working tree / safety

There is unrelated untracked `Explorer-Of-version-novembre-2026/`. Do not stage it, delete it, or otherwise alter it. Stage only intended web-client files and the requested handoff file. No commit has been made yet. Run `git status`, `git diff`, and inspect status/diff before committing.

## Important remaining work

1. **Stabilize current partial implementation.** Inspect the relevant diffs, ensure locale JSON is valid and parity test passes, run `npm run typecheck`, targeted Otaria/i18n tests, full `npm test`, and `npm run build`. Fix regressions introduced by this work; distinguish unrelated failures.
2. **Continue the core game audit.** Some status/zone/decision labels and overlays remain untranslated. Search rendered JSX, user-facing ARIA labels, tooltips, confirmations, errors, and empty states. Do not translate card Oracle/rules/flavor text, card names, Scryfall data, user-authored text, or protocol identifiers/enums.
3. **Finish lobby and join-flow audit.** Core lobby settings and cube management have translations, but continue checking quick-join/public-lobby surfaces and player status. Keep backend values and stable format IDs unchanged; translate display mappings only.
4. **Help/documentation exclusion.** Per the user's explicit direction, do not translate `src/help/topics.ts` or the large Help page/content. Leave the help content in English; do not alter stable topic IDs or deep links.
5. **Broader app remains unfinished.** Continue beyond the core milestone through learn, admin/dev/scenario, set guide/completion, replay, draft/sealed/tournament, announcements, and other remaining user-facing screens. Keep card data and protocol identifiers unchanged.
6. **Locale integrity.** Leaf-key parity, non-empty values, and matching interpolation placeholders are tested. Keep TypeScript i18next key checking enabled and add focused tests as new features are localized.
7. **Final UX and commit.** Do not run E2E. Manually exercise the running app for player-visible changes when a suitable scenario is available. Inspect the intended diff/status, leave `Explorer-Of-version-novembre-2026/` untouched, stage only task files and this handoff, create the requested local commit, and never push.

## Useful commands

Run from `web-client/`:

```bash
npm run typecheck
npm test
npm run build
```

Keep heavy verification scoped to this frontend assignment. Read the current source before editing, follow strict TS settings, and do not add source comments unless required by the user.
