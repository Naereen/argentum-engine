#!/usr/bin/env node
import assert from 'node:assert/strict'
import { mkdir, readFile, writeFile } from 'node:fs/promises'
import { resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

export const sets = { J22: 'Jumpstart_2022', J25: 'Foundations_Jumpstart' }
export const categories = ['White', 'Blue', 'Black', 'Red', 'Green', 'Other']

function text(value) {
  const entities = { amp: '&', quot: '"', apos: "'", lt: '<', gt: '>', nbsp: ' ' }
  return value.replace(/&(#x[\da-f]+|#\d+|[a-z]+);/gi, (_, entity) => {
    if (entity.startsWith('#')) {
      return String.fromCodePoint(entity[1].toLowerCase() === 'x'
        ? parseInt(entity.slice(2), 16) : parseInt(entity.slice(1), 10))
    }
    assert.ok(Object.hasOwn(entities, entity), `Unknown HTML entity: &${entity};`)
    return entities[entity]
  }).replace(/[‘’]/g, "'").replace(/\s+/g, ' ').trim().normalize('NFC')
}

/** Read the export widgets, not headings (which also contain rarity sections and navigation). */
export function parsePage(html, category) {
  assert.ok(categories.includes(category), `Unknown category: ${category}`)
  const blocks = html.split(/<span class="ext-scryfall-decktitle">/).slice(1)
  assert.ok(blocks.length, `No deck widgets found in ${category}`)
  return blocks.map((block) => {
    const title = text(block.slice(0, block.indexOf('</span>')))
    const id = title.replace(/ (\d+)$/, ' ($1)')
    const cards = new Map()
    for (const match of block.matchAll(/<span class="ext-scryfall-deckcardcount">(\d+)<\/span>\s*<a\b[^>]*data-card-name="([^"]*)"[^>]*>/g)) {
      const count = Number(match[1])
      const name = text(match[2])
      assert.ok(count > 0 && name.length > 0, `Invalid entry in ${id}`)
      cards.set(name, (cards.get(name) ?? 0) + count)
    }
    assert.equal([...cards.values()].reduce((a, b) => a + b, 0), 20, `${category}: ${id} is not 20 cards`)
    return { id, category, cards }
  })
}

export function parseLists(content) {
  const packs = new Map()
  let cards
  for (const line of content.split(/\r?\n/).map((value) => value.trim())) {
    if (!line || line.startsWith('#')) continue
    if (line.startsWith('[') && line.endsWith(']')) {
      const id = line.slice(1, -1)
      assert.ok(!packs.has(id), `Duplicate pack: ${id}`)
      cards = new Map()
      packs.set(id, cards)
    } else {
      const match = /^([1-9]\d*) (.+)$/.exec(line)
      assert.ok(cards && match, `Invalid decklist line: ${line}`)
      cards.set(text(match[2]), (cards.get(text(match[2])) ?? 0) + Number(match[1]))
    }
  }
  return packs
}

async function main() {
  const args = process.argv.slice(2)
  const allowed = new Set(['--set', '--cache-dir', '--output-dir'])
  for (let index = 0; index < args.length; index += 2) {
    assert.ok(allowed.has(args[index]), `Unknown option: ${args[index]}`)
    assert.ok(args[index + 1] && !args[index + 1].startsWith('--'), `${args[index]} needs a value`)
  }
  const option = (name) => {
    const index = args.indexOf(name)
    if (index < 0) return undefined
    assert.ok(args[index + 1] && !args[index + 1].startsWith('--'), `${name} needs a value`)
    return args[index + 1]
  }
  const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
  const cache = option('--cache-dir')
  const output = option('--output-dir')
  const selected = option('--set')
  assert.ok(!selected || Object.hasOwn(sets, selected), 'Use --set J22 or --set J25')
  for (const [code, page] of Object.entries(sets).filter(([code]) => !selected || code === selected)) {
    const existing = parseLists(await readFile(resolve(root, `game-server/src/main/resources/jumpstart/${code.toLowerCase()}.txt`), 'utf8'))
    const canonicalNames = new Map([...existing.values()].flatMap((cards) =>
      [...cards.keys()].map((name) => [name.toLowerCase(), name])))
    const packs = []
    for (const category of categories) {
      const url = `https://mtg.wiki/page/${page}/Decklists_-_${category}`
      const cached = cache && resolve(cache, `${code.toLowerCase()}-${category.toLowerCase()}.html`)
      let html
      if (cached) {
        try {
          html = await readFile(cached, 'utf8')
        } catch (error) {
          if (error.code !== 'ENOENT') throw error
        }
      }
      if (!html) {
        const response = await fetch(url, { signal: AbortSignal.timeout(40000) })
        assert.ok(response.ok, `${url}: HTTP ${response.status}`)
        html = await response.text()
        if (cached) {
          await mkdir(dirname(cached), { recursive: true })
          await writeFile(cached, html)
        }
      }
      const found = parsePage(html, category)
      for (const pack of found) {
        pack.cards = new Map([...pack.cards].map(([name, count]) => {
          const canonical = canonicalNames.get(name.toLowerCase())
          assert.ok(canonical, `${code}: unknown card "${name}" in ${pack.id}; check the canonical catalog before importing`)
          return [canonical, count]
        }))
      }
      console.log(`${code} ${category}: ${found.length} boosters`)
      packs.push(...found)
    }
    assert.equal(packs.length, 121, `${code}: expected 121 booster variants`)
    assert.equal(new Set(packs.map(({ id }) => id)).size, 121, `${code}: duplicate variants`)
    if (output) {
      await mkdir(output, { recursive: true })
      await writeFile(resolve(output, `${code.toLowerCase()}.txt`),
        `# Source: https://mtg.wiki/page/${page}/Decklists\n\n` +
        packs.map(({ id, category, cards }) => `# ${category}\n[${id}]\n` +
          [...cards].map(([name, count]) => `${count} ${name}`).join('\n')).join('\n\n') + '\n')
    } else {
      assert.equal(existing.size, packs.length)
      for (const { id, cards } of packs) {
        assert.deepEqual(existing.get(id), cards, `${code}: ${id} differs from checked-in list`)
      }
      console.log(`${code}: all 121 checked-in packs match the wiki (20 cards each).`)
    }
  }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch((error) => { console.error(error); process.exitCode = 1 })
}
