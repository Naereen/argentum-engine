# Headless Otaria batch simulation

Run real **Explorer of: Otaria** matches with every seat controlled by the local engine AI:

```sh
just otaria-simulate --matches 100 --players 3 --seed 41
just otaria-simulate --matches 100 --parallelism 8 --timeout-minutes 30 --quiet
just otaria-simulate --config otaria-config.json --matches 20
just otaria-simulate --matches 100 --quiet
just otaria-simulate --help
```

Requires the normal JDK 25/Gradle development environment. No Spring application, HTTP server,
browser, database, remote AI service or thinking delay is started. Matches run concurrently up to
`--parallelism` (the default is the number of available processors, bounded by the batch size);
JSONL writing, reports and statistics remain coordinated by one thread. Records are emitted in
match-completion order, and each record carries its seed and match ID. Terminal progress is
**verbose by default**: batch destination, each match's start and seed, every player turn with active
player and resource/position snapshots, chosen Otaria
actions, match results, and the number/percentage of matches written. Use `--quiet` (or JSON
`"verbose": false`) to suppress per-turn progress; failure logging, final batch statistics and output
paths remain available.
The existing full-bot playtest's published Jumpstart packs, authentic Otaria
supplies, starting rules and campaign AI are reused; this runner does not substitute an outcome model
for playing the game. It uses the live AI profile by default.

## Configuration

CLI options override the JSON configuration: `--matches`, `--players`, `--seed`, `--max-turns`,
`--max-actions`, `--timeout-minutes`, `--parallelism`, `--output`, and the valueless `--quiet` switch.
Defaults are 10 matches, 3 players, seed 1, 500 engine turns, 100,000 accepted engine actions per
match, a 30-minute per-match timeout, processor-count parallelism, and `simulation-results/otaria/`.
Turn limits use the engine's turn number, including encounter-side turns.

Example configuration (all fields are optional):

```json
{
  "matches": 10,
  "players": 3,
  "seed": 41,
  "maxTurns": 500,
  "maxActions": 100000,
  "timeoutMinutes": 30,
  "parallelism": 8,
  "output": "simulation-results/otaria",
  "randomMapPositions": false,
  "verbose": true,
  "ai": {
    "profile": "live",
    "determinizeHiddenInformation": true
  },
  "seats": [
    {
      "boosterId": "J22:Urza's",
      "startingLocation": "CABAL_CITY",
      "position": { "column": 8, "row": 7 },
      "ai": { "profile": "current", "useCardIntent": true }
    },
    {},
    {}
  ]
}
```

- Supports 3, 4 or 5 players. Omit `seats` for independent random pack selections; otherwise provide
  one object per seat. Booster IDs are the same `J22:`/`J25:` IDs exposed by the Otaria playtest.
- A custom deck replaces `boosterId` with `cards` (20 card names, including repeated names) and
  `startingLands` (two basic-land names). It defaults to `deckKind: "CONSTRUCTED"` and
  `startingLandSource: "DECK"` and is validated by the existing Otaria initializer.
  Published-style custom packs can set `deckKind: "JUMPSTART"`; that kind also allows
  `startingLandSource: "SEPARATE_STOCK"`. Pack selection supplies its own kind and land source.
- The normal map layout is fixed. By default, players start at distinct randomly selected stellar
  locations, as in the mode. `startingLocation` fixes a player's stellar home (an `OtariaLocation`
  enum name); `position` independently fixes their initial coordinates. Partial home overrides
  leave other players with seeded, distinct unused homes.
- `randomMapPositions: true` samples distinct playable hexes for unspecified positions. This is an
  experimental initial condition, not a change to the normal game's starting rule. Fixed positions
  must be playable and all initial positions must be distinct.
- AI profiles are `live`, `current`, or `legacy`. Optional `determinizeHiddenInformation` and
  `useCardIntent` override the selected profile's defaults. A seat's `ai` replaces the batch AI
  configuration for that seat. Encounter decisions/actions use the encounter manager's profile,
  but are submitted under the encounter's own actor ID.
- Each match's seed is `seed + zeroBasedMatchIndex`; the same seed fixes opening cards, starting
  seat, supplies shuffles and randomized starting locations/positions. Player IDs are stable by
  seat. Full decision-for-decision determinism is not promised for time-budgeted AI search or
  across code/profile versions; telemetry records the resolved profile ID.

## JSONL contract (schemaVersion 2)

Each invocation creates a unique `batch-<UUID>.jsonl`. **One complete JSON object per match per line**,
including failed and truncated matches. Lines are flushed after every match; output is ignored by git.
At most `parallelism` match logs are in flight or awaiting output. Exact statistical medians retain
numeric observations, not full match logs.

- `metadata`: unique match ID, UTC start timestamp, actual match seed, player count, `mapSeed`,
  map layout identifier and every terrain tile, effective per-seat decks/lands, starting stellar
  homes, coordinates, requested AI settings and resolved profile IDs, turn/action/time limits.
  `mapSeed` is the setup/shuffle seed; terrain itself is not randomized.
- `requestedSetup`: complete batch configuration, retained even if initialization fails.
- `turns`: one entry per engine turn that executed an action, with `turnNumber`, `activePlayerId`,
  event-backed `chosenActions` (`MOVE`, `EXPLORE`, `PREPARE`, `COMBAT`) and `steps`.
  Encounter choices can add another chosen action during the same player turn; they retain their
  actor IDs. Encounter-side turns have their own engine turn numbers and encounter actor as active
  player, not an invented extra explorer. A failure before any accepted action has an empty `turns` list.
- Each step records a global accepted-action `sequence`, the submitting actor, the full Kotlin
  typed action/decision-response JSON (with its engine `type` discriminator), `nextTurnNumber`,
  and `changes` for every player. Each
  change contains absolute `before` and `after` library size, hand size, reserve count, life,
  treasure-token counts, coordinates and elimination status. Subtract numeric snapshots to obtain
  deltas. These are authoritative backend snapshots, including private information.
  A step that begins the next turn includes that transition's effects (including its draw);
  `nextTurnNumber` makes the boundary explicit. Resource observations are not inferred from labels.
- `decisions` records observed treasure acquisition (typed `treasure` with card name/entity or token
  kind), Mirari
  discovery, retreat and contest result, exploration, encounter start, capture, unsupported-card
  skips and player elimination with its engine reason. Retreat's typed `retreat` contains the
  dice contests rather than a debug string.
  `EMERGENCY_DECK_OUT_PREPARATION` means cards were actually prepared when the library had at most
  four cards before the action. It is an observed low-library preparation, not hidden AI intent.
  Empty treasure discoveries do not count as acquisitions.
- `finalPlayers`: final snapshots, also retained at a rejected action or exception.
- `outcome`: `status`, nullable `winnerId`, `totalTurns` (recorded engine turns), `totalActions`
  (accepted engine actions), and `reason`.
  - `COMPLETED`: winner and end reason come from the engine's game-ended event. Mirari victory is
    named `MIRARI_RETURNED_TO_STELLAR_LOCATION`; other reasons retain engine names such as
    `DECK_EMPTY`, `LIFE_ZERO`, `CONCESSION`, or `ALTERNATIVE_WIN`. Draws have no winner.
  - `TRUNCATED`: `TURN_LIMIT`, `ACTION_LIMIT`, or `TIME_LIMIT`, never a fabricated winner.
  - `FAILED`: `INITIALIZATION_FAILED` or `SIMULATION_FAILED`, never a fabricated winner.
- `failure`: nullable; on failure includes exception class/message/stack trace, attempted action,
  turn/step, priority holder and pending decision at the failure boundary. Rejected actions are
  failures, not silently replaced with passes.

The deadline is measured from the start of each match using a monotonic clock and checked before and
after AI decisions and engine actions. A currently executing AI/engine call is not forcibly killed;
if one call itself runs beyond the deadline, that call must return before the runner can stop safely.
Match-local exceptions are logged and do not terminate the remaining batch. Every match recreates
the initializer, engine services, action processor and AI instances; only the read-only card/printing
catalogs are shared. Invalid batch configuration and output I/O errors abort explicitly, because an
unwritable output cannot preserve reliable telemetry. JVM-fatal errors are not swallowed.

The live profile may encounter existing AI search/engine errors in this experimental mode; these
remain failed matches with their diagnostic stack traces, not synthetic wins. The `current` profile
offers a lighter comparison without the live profile's rollout search.

## English Markdown reports

Every invocation also creates:

```text
simulation-results/otaria/
  batch-<UUID>.jsonl
  batch-<UUID>-matches/
    match-1.md
    match-2.md
    ...
  batch-<UUID>-summary.md
  batch-<UUID>-summary.json
```

Each match report is written immediately after its JSONL line, including failed and truncated matches.
It contains English descriptions of the setup, starting decks and card counts, AI configuration,
outcome, every recorded turn and accepted action, resource/position changes, important decision flags,
final player state, and failure diagnostics when applicable. Exact action/decision JSON appears in
expandable details where needed; generated entity IDs are not guessed into card names.
Initialization failures show the requested setup even when no effective deck was produced.

The batch summary is saved in Markdown and machine-readable JSON, and printed to the terminal at the
end of the run, **also with `--quiet`**. JSONL remains the authoritative raw record. Output I/O errors
remain fatal rather than silently omitting a requested report; lines and reports already written survive.
The final summary is produced only after the run finishes, not when a process is forcibly interrupted.

## Basic statistical summary

- Counts total matches, completed matches, real bot victories, draws, failures, truncated matches and
  other completed outcomes. A real bot victory requires `COMPLETED`, no failure, and a winner ID
  belonging to an initial AI seat. Its rate uses all matches in this run as the denominator.
- Lists wins by bot seat, end reasons grouped by status, and observed decision-flag counts.
  A bot seat is not a stable deck or profile across randomized matches; these counts are not a
  causal comparison of AI strategies.
- Reports **N, minimum, maximum, mean, median, population variance (stdVar), and population standard
  deviation** for:
  - recorded engine turns per match;
  - total accepted actions per match;
  - accepted actions per recorded turn, pooled across turns;
  - mean actions per turn per match.
- Keeps two separate cohorts: **all matches**, including only the observed prefix of failed or
  truncated matches, and **real bot victories only**. This prevents an incomplete duration from being
  mistaken for the duration of a finished game.
- Pooled actions per turn weights longer matches by their number of turns. The per-match ratio
  gives each nonempty match one observation. Zero-turn matches remain in turns/actions per match
  with value zero, but have no per-turn ratio.
- Variance divides by **N**, not N-1; standard deviation is its square root. Empty cohorts use
  `null` in JSON and `N/A` in Markdown, never NaN. A single observation has zero variance and
  standard deviation. Human-readable numbers use three decimal places; JSON keeps full precision.
  Counts and limits include encounter-side engine turns, not just explorer turns.

No strategy-optimization or advanced analysis pipeline is included. The runner does not change bot policy,
mode rules, SDK vocabulary, public endpoints or player-facing UI.
