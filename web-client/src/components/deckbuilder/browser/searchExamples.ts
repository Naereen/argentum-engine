/**
 * The search help panel's cheat sheet: example queries grouped by what the reader is trying to
 * find, each with a plain-words answer. Written for someone who has never used Scryfall — every
 * example is clickable, and `searchExamples.test.ts` checks each one parses cleanly.
 */
export interface SearchExample {
  query: string
  descriptionKey:
    | 'nameContainsBolt' | 'exactNameMatch' | 'rulesTextPhrase' | 'flying'
    | 'creatures' | 'goblins' | 'legendaryCreatures' | 'permanents'
    | 'redCards' | 'exactlyWhiteBlue' | 'fitsRedGreen' | 'guildNames' | 'colorlessCards'
    | 'manaValueAtMostTwo' | 'powerAtLeastFour' | 'toughnessAtMostOne' | 'powerGreaterThanToughness' | 'exactManaCost'
    | 'printedInBloomburrow' | 'mythicRares' | 'rareOrBetter' | 'legalInStandard'
    | 'redGoblins' | 'eitherType' | 'nonCreatures' | 'groupedCreatures'
}

export interface SearchExampleGroup {
  titleKey: 'namesAndText' | 'type' | 'colour' | 'costAndStats' | 'setRarityFormat' | 'combine'
  examples: SearchExample[]
}

export const SEARCH_EXAMPLE_GROUPS: SearchExampleGroup[] = [
  {
    titleKey: 'namesAndText',
    examples: [
      { query: 'bolt', descriptionKey: 'nameContainsBolt' },
      { query: '!"Lightning Bolt"', descriptionKey: 'exactNameMatch' },
      { query: 'o:"draw a card"', descriptionKey: 'rulesTextPhrase' },
      { query: 'kw:flying', descriptionKey: 'flying' },
    ],
  },
  {
    titleKey: 'type',
    examples: [
      { query: 't:creature', descriptionKey: 'creatures' },
      { query: 't:goblin', descriptionKey: 'goblins' },
      { query: 't:"legendary creature"', descriptionKey: 'legendaryCreatures' },
      { query: 'is:permanent', descriptionKey: 'permanents' },
    ],
  },
  {
    titleKey: 'colour',
    examples: [
      { query: 'c:r', descriptionKey: 'redCards' },
      { query: 'c=wu', descriptionKey: 'exactlyWhiteBlue' },
      { query: 'c<=rg', descriptionKey: 'fitsRedGreen' },
      { query: 'c:izzet', descriptionKey: 'guildNames' },
      { query: 'c:colorless', descriptionKey: 'colorlessCards' },
    ],
  },
  {
    titleKey: 'costAndStats',
    examples: [
      { query: 'mv<=2', descriptionKey: 'manaValueAtMostTwo' },
      { query: 'pow>=4', descriptionKey: 'powerAtLeastFour' },
      { query: 'tou<=1', descriptionKey: 'toughnessAtMostOne' },
      { query: 'pow>tou', descriptionKey: 'powerGreaterThanToughness' },
      { query: 'mana:{1}{G}', descriptionKey: 'exactManaCost' },
    ],
  },
  {
    titleKey: 'setRarityFormat',
    examples: [
      { query: 's:blb', descriptionKey: 'printedInBloomburrow' },
      { query: 'r:mythic', descriptionKey: 'mythicRares' },
      { query: 'r>=rare', descriptionKey: 'rareOrBetter' },
      { query: 'f:standard', descriptionKey: 'legalInStandard' },
    ],
  },
  {
    titleKey: 'combine',
    examples: [
      { query: 't:goblin c:r', descriptionKey: 'redGoblins' },
      { query: 't:elf or t:goblin', descriptionKey: 'eitherType' },
      { query: '-t:creature', descriptionKey: 'nonCreatures' },
      { query: '(c:u or c:b) t:creature', descriptionKey: 'groupedCreatures' },
    ],
  },
]
