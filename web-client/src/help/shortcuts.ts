export interface Shortcut {
  id: string
  keys: string
  label: string
  where: string
}

export const SHORTCUTS: readonly Shortcut[] = [
  { id: 'opponent-boards', keys: '1 – 9', label: 'Focus an opponent’s board', where: 'Multiplayer games (3+ players)' },
  { id: 'overview', keys: '0', label: 'Toggle the table overview — every board side by side', where: 'Multiplayer games, desktop and landscape tablet' },
  { id: 'escape', keys: 'Esc', label: 'Cancel: unpin the camera, close a modal or zone browser, leave a replay', where: 'Everywhere' },
  { id: 'strip-swipe', keys: 'Swipe ← / →', label: 'Slide to the previous or next opponent’s board', where: 'Multiplayer games, on the opponent board (focused camera)' },
  { id: 'deck-browser', keys: 'D', label: 'Open or close the deck browser', where: 'In a game' },
  { id: 'flip-dfc', keys: 'F', label: 'Flip a double-faced card while previewing it', where: 'Any card preview — hand, battlefield, deckbuilder' },
  { id: 'replay-frame', keys: '← / →', label: 'Step one frame back or forward', where: 'Replay viewer' },
  { id: 'replay-play', keys: 'Space', label: 'Play / pause', where: 'Replay viewer' },
  { id: 'submit', keys: 'Enter', label: 'Submit the focused field or dialog', where: 'Name entry, join code, deck name, search' },
  { id: 'deckbuilder-remove', keys: 'Right-click / Shift-click', label: 'Remove one copy of a card', where: 'Deckbuilder' },
  { id: 'help-search', keys: '/', label: 'Jump to the search box', where: 'Help wiki' },
  { id: 'scenario-undo', keys: 'Ctrl/⌘ Z / Ctrl/⌘ Shift Z', label: 'Undo / redo a board edit', where: 'Scenario Builder' },
  { id: 'stack-yield-menu', keys: 'Right-click', label: 'Open the yield menu for an ability on the stack (or press Yield… under the top item)', where: 'In a game, on a stack item' },
]

export function shortcutById(id: string): Shortcut | undefined {
  return SHORTCUTS.find((shortcut) => shortcut.id === id)
}
