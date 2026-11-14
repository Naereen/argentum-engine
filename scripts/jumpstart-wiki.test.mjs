import { test } from 'node:test'
import assert from 'node:assert/strict'
import { parsePage, parseLists } from './jumpstart-wiki.mjs'

const widget = (title, name, count = 20) => `<span class="ext-scryfall-decktitle">${title}</span>
<span class="ext-scryfall-deckcardcount">${count}</span> <a data-card-name="${name}">ignored</a>`

test('extracts distinct variants, decodes entities and trims card names', () => {
  const packs = parsePage(`<h2>Rare</h2>${widget('Elves 1', ' Llanowar Elves ')}${widget('Elves 2', 'Dwynen&#39;s Elite')}`, 'Green')
  assert.deepEqual(packs.map((pack) => pack.id), ['Elves (1)', 'Elves (2)'])
  assert.equal(packs[0].cards.get('Llanowar Elves'), 20)
  assert.equal(packs[1].cards.get("Dwynen's Elite"), 20)
})

test('Other is preserved, not treated as a sixth Magic color', () => {
  const [pack] = parsePage(widget('Urza&#39;s', ' Karn Liberated '), 'Other')
  assert.equal(pack.id, "Urza's")
  assert.equal(pack.category, 'Other')
  assert.equal(pack.cards.get('Karn Liberated'), 20)
})

test('rejects missing widgets, partial decks and unknown categories', () => {
  assert.throws(() => parsePage('<h2>Green</h2>', 'Green'))
  assert.throws(() => parsePage(widget('Elves', 'Forest', 19), 'Green'))
  assert.throws(() => parsePage(widget('Elves', 'Forest'), 'Purple'))
})

test('raw list reader retains quantities and rejects duplicate pack IDs', () => {
  assert.deepEqual(parseLists('# Green\n[Elves]\n1 Llanowar Elves\n19 Forest\n').get('Elves'),
    new Map([['Llanowar Elves', 1], ['Forest', 19]]))
  assert.throws(() => parseLists('[Elves]\n20 Forest\n[Elves]\n20 Forest'))
})
