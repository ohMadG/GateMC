const fs = require('fs');
const path = require('path');
const LEET = require('./leet.json');

const CONFIG_PATH = process.env.HAWKMC_CONFIG_PATH || '/app/hawkmc-config.yml';
const DICTIONARY_PATH = path.join(__dirname, '..', 'data', 'dictionary-en.txt');
const STRETCHED = /(.)\1+/g;

let dictionary = null;
let lists = { stamp: 0, enabled: true, severe: [], banned: [], safe: [], display: new Map() };

function loadDictionary() {
  if (dictionary) {
    return dictionary;
  }
  dictionary = new Set();
  try {
    for (const line of fs.readFileSync(DICTIONARY_PATH, 'utf8').split('\n')) {
      const word = line.trim().toLowerCase();
      if (word) {
        dictionary.add(word);
      }
    }
  } catch (e) {
    console.error('[hawkeye-bot] could not load the filter dictionary:', e.message);
  }
  return dictionary;
}

function stripToAlnum(input) {
  let out = '';
  const lowered = String(input).toLowerCase().replace(/(?<=[a-z0-9])!(?=[a-z0-9])/g, 'i');
  for (const ch of lowered) {
    const code = ch.charCodeAt(0);
    const folded = code >= 0xff01 && code <= 0xff5e ? String.fromCharCode(code - 0xfee0) : ch;
    out += LEET[folded] !== undefined ? LEET[folded] : folded;
  }
  return out.replace(/[^a-z0-9]/g, '');
}

function collapse(text) {
  return text.replace(STRETCHED, '$1');
}

function normalize(input) {
  return collapse(stripToAlnum(input));
}

function isTiny(word) {
  return collapse(word).length <= 2;
}

function tokenize(input) {
  const words = String(input).trim().split(/\s+/).map(stripToAlnum).filter(Boolean);
  const glued = [];
  let i = 0;
  while (i < words.length) {
    if (!isTiny(words[i])) {
      glued.push(words[i]);
      i++;
      continue;
    }
    let end = i;
    let singles = 0;
    while (end < words.length && isTiny(words[end])) {
      if (words[end].length === 1) {
        singles++;
      }
      end++;
    }
    if (end - i >= 3 && singles >= 2) {
      glued.push(words.slice(i, end).join(''));
    } else {
      let k = i;
      while (k < end) {
        if (words[k].length === 1) {
          let run = '';
          while (k < end && words[k].length === 1) {
            run += words[k];
            k++;
          }
          glued.push(run);
        } else {
          glued.push(words[k]);
          k++;
        }
      }
    }
    i = end;
  }
  return glued.map((w) => ({ display: w, matchable: collapse(w) }));
}

function parseYamlLists(text) {
  const out = { enabled: true, 'zero-tolerance-words': [], 'banned-words': [], 'safe-words': [] };
  const lines = text.split('\n');
  let inChat = false;
  let inFilter = false;
  let current = null;
  for (const raw of lines) {
    if (!raw.trim() || /^\s*#/.test(raw)) {
      continue;
    }
    const indent = raw.match(/^ */)[0].length;
    const key = raw.match(/^( *)([\w-]+):\s*(.*?)\s*$/);
    if (indent === 0) {
      inChat = Boolean(key && key[2] === 'chat');
      inFilter = false;
      current = null;
      continue;
    }
    if (!inChat) {
      continue;
    }
    if (indent === 2) {
      inFilter = Boolean(key && key[2] === 'filter');
      current = null;
      continue;
    }
    if (!inFilter) {
      continue;
    }
    if (indent === 4 && key) {
      current = key[2] in out && Array.isArray(out[key[2]]) ? key[2] : null;
      if (key[2] === 'enabled') {
        out.enabled = key[3] !== 'false';
      }
      continue;
    }
    const item = raw.match(/^\s*-\s*(.*?)\s*$/);
    if (current && item) {
      let value = item[1];
      if ((value.startsWith("'") && value.endsWith("'")) || (value.startsWith('"') && value.endsWith('"'))) {
        value = value.slice(1, -1).replace(/''/g, "'");
      }
      out[current].push(value);
    }
  }
  return out;
}

function normalizeAll(words, display) {
  const out = [];
  for (const raw of words) {
    const norm = normalize(raw);
    if (!norm) {
      continue;
    }
    if (display && !display.has(norm)) {
      display.set(norm, raw.trim().toLowerCase());
    }
    if (!out.includes(norm)) {
      out.push(norm);
    }
  }
  return out;
}

function loadLists() {
  try {
    const stamp = fs.statSync(CONFIG_PATH).mtimeMs;
    if (stamp === lists.stamp) {
      return lists;
    }
    const parsed = parseYamlLists(fs.readFileSync(CONFIG_PATH, 'utf8'));
    const display = new Map();
    lists = {
      stamp,
      enabled: parsed.enabled,
      severe: normalizeAll(parsed['zero-tolerance-words'], display),
      banned: normalizeAll(parsed['banned-words'], display),
      safe: normalizeAll(parsed['safe-words']),
      display,
    };
    console.log(`[hawkeye-bot] word filter loaded ${lists.severe.length} high-risk, ${lists.banned.length} banned, ${lists.safe.length} safe words`);
  } catch (e) {
    if (!lists.stamp) {
      console.error('[hawkeye-bot] could not read the in-game filter lists:', e.message);
      lists.stamp = -1;
    }
  }
  return lists;
}

function spansFromWordStart(tokens, word) {
  for (let i = 0; i < tokens.length - 1; i++) {
    const first = collapse(tokens[i]);
    if (!first || first.length >= word.length || !word.startsWith(first)) {
      continue;
    }
    let run = tokens[i];
    for (let k = i + 1; k < tokens.length; k++) {
      run += tokens[k];
      const collapsed = collapse(run);
      if (collapsed.startsWith(word)) {
        return true;
      }
      if (!word.startsWith(collapsed)) {
        break;
      }
    }
  }
  return false;
}

function contains(joined, tokens, words, safe) {
  const dict = loadDictionary();
  const tokensWithoutSafe = tokens.map((t) => safe.reduce((s, w) => s.split(w).join(''), t.matchable));
  for (const w of words) {
    const exactOnly = w.length < 4;
    for (let i = 0; i < tokens.length; i++) {
      const rawToken = tokens[i].matchable;
      if (rawToken === w) {
        return w;
      }
      if (exactOnly || !tokensWithoutSafe[i].includes(w) || dict.has(tokens[i].display)) {
        continue;
      }
      return w;
    }
    if (!exactOnly && tokens.length > 1 && joined.includes(w) && spansFromWordStart(tokensWithoutSafe, w)) {
      return w;
    }
  }
  return null;
}

function check(text) {
  const l = loadLists();
  if (!l.enabled || !text) {
    return null;
  }
  let joined = normalize(text);
  for (const s of l.safe) {
    joined = joined.split(s).join('');
  }
  const tokens = tokenize(text);
  const severe = contains(joined, tokens, l.severe, l.safe);
  if (severe) {
    return { level: 'high-risk', word: l.display.get(severe) || severe };
  }
  const banned = contains(joined, tokens, l.banned, l.safe);
  if (banned) {
    return { level: 'banned', word: l.display.get(banned) || banned };
  }
  return null;
}

module.exports = { check, normalize, tokenize, loadLists, loadDictionary, parseYamlLists };
