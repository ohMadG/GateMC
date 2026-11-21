const { pool } = require('./db');

const IP_GRABBERS = ['grabify.link', 'grabify.icu', 'grabify.org', 'iplogger.org', 'iplogger.com', 'iplogger.ru', 'iplogger.co', 'iplogger.info', 'iplogger.cn',
  '2no.co', 'yip.su', 'iplis.ru', '02ip.ru', 'ezstat.ru', 'blasze.com', 'blasze.tk', 'ps3cfw.com', 'bmwforum.co', 'leancoding.co', 'quickmessage.us',
  'spottyfly.com', 'stopify.co', 'gyazos.com', 'freegiftcards.co', 'joinmy.site', 'curiouscat.club', 'catsnthings.fun', 'catsnthing.com', 'ipgrabber.ru',
  'ipgraber.ru', 'maper.info', 'whatstheirip.com', 'ip-tracker.org', 'iptrackeronline.com', 'canarytokens.com', 'canarytokens.org', 'xn--yutube-iqc.com',
  'xn--youtub-cxe.com', 'youramonkey.com', 'shrekis.life', 'gamingfun.me', 'screenshare.host', 'imageshare.best', 'myprivate.pics', 'headshot.monster',
  'trulyprivate.pics', 'shhh.lol', 'sharetext.me', 'bramp.net', 'webresolver.nl', 'ipaddress.com', 'iplookup.net', 'grabify.world', 'linkify.me',
  'datauth.io', 'dataur.link', 'discörd.com', 'xn--yotube-2ya.com', 'stopify.com', 'urlz.fr', 'lovebird.guru', 'trulove.guru', 'dateing.club',
  'otherhalf.life', 'shrekis.us', 'xtremefun.cam', 'fortnitechat.site', 'fortnight.space', 'ipgrab.org', 'iptracker.link', 'b1nd.net'];
const SHORTENERS = ['bit.ly', 'tinyurl.com', 'cutt.ly', 'rb.gy', 'is.gd', 'v.gd', 'shorturl.at', 'goo.gl', 'ow.ly', 'adf.ly', 'shorte.st', 't.ly', 'tiny.cc',
  'bl.ink', 'rebrand.ly', 'buff.ly', 'lnkd.in', 'qrco.de', 'shorturl.gg', 'linktr.ee', 'bitly.com', 'surl.li', 'u.to', 'clck.ru', 'gg.gg', 'short.io',
  'shrtco.de', 'urlz.fr', 'ouo.io', 'exe.io', 'link.tl', 'tr.ee', 'han.gl', 'lihi.cc', 's.id', '1url.cz', 'did.li', 'cli.re', 'tny.im', 'dub.sh', 'kutt.it'];
const FILE_HOSTS = ['mediafire.com', 'mega.nz', 'mega.io', 'anonfiles.com', 'gofile.io', 'pixeldrain.com', 'transfer.sh', 'wetransfer.com', 'we.tl', 'sendspace.com',
  'zippyshare.com', 'uploadhaven.com', 'file.io', 'filebin.net', 'catbox.moe', 'litter.catbox.moe', 'krakenfiles.com', 'bayfiles.com', 'letsupload.io',
  'dropbox.com', 'dl.dropboxusercontent.com', 'drive.google.com', 'onedrive.live.com', '1drv.ms', 'workupload.com', 'uploadfiles.io', 'rapidgator.net',
  'nitroflare.com', 'filemail.com', 'files.fm', 'buzzheavier.com', 'qiwi.gg', 'temp.sh', '0x0.st', 'bashupload.com', 'tmpfiles.org'];
const RISKY_TLDS = ['tk', 'ml', 'ga', 'cf', 'gq', 'xyz', 'top', 'click', 'zip', 'mov', 'icu', 'cfd', 'sbs', 'rest', 'monster', 'cam', 'bond', 'quest', 'buzz',
  'gift', 'work', 'support', 'country', 'kim', 'loan', 'win', 'download', 'su', 'ru.com', 'pw', 'cc', 'lol', 'shop', 'live', 'fun', 'site', 'online'];
const RUN_EXT = ['exe', 'scr', 'msi', 'msix', 'appx', 'bat', 'cmd', 'ps1', 'vbs', 'vbe', 'wsf', 'hta', 'lnk', 'dll', 'reg', 'cpl', 'pif', 'jse', 'com', 'dmg',
  'pkg', 'app', 'iso', 'img', 'vhd', 'apk', 'xapk', 'deb', 'rpm', 'sh', 'run', 'appimage'];
const PACK_EXT = ['jar', 'zip', 'rar', '7z', 'tar', 'gz', 'tgz', 'cab', 'js', 'py', 'docm', 'xlsm', 'pptm'];
const BRANDS = {
  discord: ['discord.com', 'discord.gg', 'discordapp.com', 'discordapp.net', 'discord.media', 'discord.new', 'discord.gift', 'discordstatus.com', 'discord.dev', 'discord.co'],
  steam: ['steamcommunity.com', 'steampowered.com', 'steamstatic.com', 'steam.tv', 'steamdeck.com'],
  steamcommunity: ['steamcommunity.com'],
  steampowered: ['steampowered.com'],
  youtube: ['youtube.com', 'youtu.be', 'youtube-nocookie.com'],
  minecraft: ['minecraft.net', 'minecraft.wiki'],
  mojang: ['mojang.com'],
  roblox: ['roblox.com'],
  epicgames: ['epicgames.com'],
  paypal: ['paypal.com', 'paypal.me'],
  hawkmc: ['hawkmc.org', 'gatemc.org', 'tebex.io', 'tebex.store'],
  twitch: ['twitch.tv'],
  github: ['github.com', 'github.io'],
  microsoft: ['microsoft.com', 'live.com', 'xbox.com'],
  spotify: ['spotify.com'],
};
const NITRO_BAIT = /\b(free\s*nitro|nitro\s*(gift|free|giveaway)|steam\s*(gift|giveaway)|claim\s*(your|ur)\s*(gift|nitro|prize)|airdrop|\$\d+\s*(gift|steam)|i\s*(leave|quit)\s*(cs|csgo|cs2)|who\s*is\s*first\?)\b/i;
const MULTI_SUFFIX = ['co.uk', 'org.uk', 'ac.uk', 'gov.uk', 'com.au', 'net.au', 'org.au', 'co.nz', 'co.jp', 'com.br', 'com.mx', 'co.in', 'com.tr', 'co.za', 'com.ar',
  'ru.com', 'com.cn', 'com.tw'];
const GRAB_WORDS = /grabify|iplogger|ip-?logger|ipgrab|ip-?grabber|iptrack|ip-?tracker|blasze|whatstheirip/i;
const PRIORITY = { critical: 3, high: 2, medium: 1, safe: 0 };
const CONFUSABLE = {
  0: 'o', 1: 'l', 3: 'e', 4: 'a', 5: 's', 7: 't', '@': 'a', 'і': 'i', 'о': 'o', 'а': 'a', 'е': 'e', 'с': 'c', 'р': 'p', 'х': 'x', 'ԁ': 'd', 'ӏ': 'l', 'ѕ': 's',
  'ö': 'o', 'ü': 'u', 'ë': 'e', 'ï': 'i', rn: 'm', vv: 'w',
};
const URL_RE = /\b((?:https?|ftp):\/\/[^\s<>()"'`|\]]+|(?:www\.)[^\s<>()"'`|\]]+)/gi;
const BARE_RE = /(?<![@\w.-])((?:[a-z0-9¡-￿](?:[a-z0-9¡-￿-]{0,61}[a-z0-9¡-￿])?\.)+(?:[a-z¡-￿]{2,24}|xn--[a-z0-9-]{2,30})(?::\d{2,5})?(?:\/[^\s<>()"'`|\]]*)?)/gi;
const DOT_WORD = /\s*[[({]\s*(?:dot|\.)\s*[\])}]\s*|\s+dot\s+/gi;
const IP_RE = /\b(?:(?:25[0-5]|2[0-4]\d|1?\d?\d)\.){3}(?:25[0-5]|2[0-4]\d|1?\d?\d)\b/;
const TLD_SET = new Set(['com', 'net', 'org', 'gg', 'io', 'co', 'tv', 'ly', 'uk', 'de', 'ru', 'fr', 'nl', 'info', 'biz', 'xyz', 'top', 'site', 'online',
  'link', 'live', 'app', 'dev', 'fun', 'shop', 'store', 'club', 'lol', 'pw', 'cc', 'tk', 'ml', 'ga', 'cf', 'gq', 'su', 'icu', 'click', 'zip', 'mov', 'cfd',
  'sbs', 'rest', 'monster', 'cam', 'bond', 'quest', 'buzz', 'gift', 'host', 'pics', 'guru', 'life', 'space', 'world', 'best', 'win', 'download', 'cn',
  'sh', 'st', 'moe', 'ee', 'cz', 'gl', 'ca', 'au', 'jp', 'br', 'pl', 'eu', 'nz', 'fm', 'ai', 'wtf', 'xxx', 'vip', 'tech', 'media', 'games', 'ws']);

const inviteCache = new Map();
let hostLists = { at: 0, safe: [], blocked: [] };

function registrable(host) {
  const parts = host.split('.');
  const two = parts.slice(-2).join('.');
  if (parts.length > 2 && MULTI_SUFFIX.includes(two)) {
    return parts.slice(-3).join('.');
  }
  return two;
}

function matches(host, list) {
  return list.some((d) => d && (host === d || host.endsWith(`.${d}`)));
}

function lev(a, b) {
  if (Math.abs(a.length - b.length) > 2) {
    return 9;
  }
  const d = Array.from({ length: a.length + 1 }, (_, i) => [i]);
  for (let j = 1; j <= b.length; j++) {
    d[0][j] = j;
  }
  for (let i = 1; i <= a.length; i++) {
    for (let j = 1; j <= b.length; j++) {
      d[i][j] = Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + (a[i - 1] === b[j - 1] ? 0 : 1));
    }
  }
  return d[a.length][b.length];
}

function skeleton(s) {
  let out = s.toLowerCase().normalize('NFD').replace(/\p{M}/gu, '');
  for (const [k, v] of Object.entries(CONFUSABLE)) {
    out = out.split(String(k)).join(v);
  }
  return out.replace(/[^a-z]/g, '');
}

function lookalike(asciiHost) {
  const host = require('url').domainToUnicode(asciiHost) || asciiHost;
  const label = registrable(host).split('.')[0];
  for (const [brand, real] of Object.entries(BRANDS)) {
    if (matches(asciiHost, real)) {
      return null;
    }
    const sk = skeleton(label);
    if (label !== brand && sk === brand) {
      return { level: 'critical', why: `fake ${brand} address made with look-alike letters` };
    }
    if (brand.length >= 7 && label.length >= 6 && lev(sk, brand) <= 2) {
      return { level: 'critical', why: `fake domain, looks like ${brand}` };
    }
    if (brand.length >= 6 && skeleton(host.replace(/\./g, '')).includes(brand)) {
      return { level: 'high', why: `uses the ${brand} name on a site that isn't ${brand}` };
    }
  }
  return null;
}

function extract(content, known) {
  const text = String(content || '');
  const found = new Map();
  const push = (raw, how) => {
    let s = raw.replace(/[.,!?;:*_~]+$/, '');
    if (!/^[a-z]+:\/\//i.test(s)) {
      s = `http://${s}`;
    }
    let u;
    try {
      u = new URL(s);
    } catch {
      return;
    }
    const host = u.hostname.toLowerCase().replace(/^www\./, '').replace(/\.$/, '');
    if (!host.includes('.')) {
      return;
    }
    const key = host + u.pathname.replace(/\/$/, '') + u.search;
    if (!found.has(key)) {
      found.set(key, { raw, url: u.href, host, path: u.pathname, search: u.search, how });
    }
  };
  for (const m of text.matchAll(URL_RE)) {
    push(m[1], 'link');
  }
  const stripped = text.replace(URL_RE, ' ');
  for (const m of stripped.matchAll(BARE_RE)) {
    const bareHost = m[1].split(/[/:]/)[0].toLowerCase().replace(/^www\./, '');
    const tld = bareHost.split('.').pop();
    if (TLD_SET.has(tld) || tld.startsWith('xn--') || matches(bareHost, known) || GRAB_WORDS.test(bareHost)) {
      push(m[1], 'bare');
    }
  }
  const spelled = stripped.replace(DOT_WORD, '.');
  if (spelled !== stripped) {
    for (const m of spelled.matchAll(BARE_RE)) {
      const tld = m[1].split(/[/:]/)[0].split('.').pop().toLowerCase();
      if (TLD_SET.has(tld)) {
        push(m[1], 'disguised');
      }
    }
  }
  for (const m of text.matchAll(/\b(?:discord(?:app)?\.com\/invite|discord\.gg|dsc\.gg|discord\.me|disboard\.org\/server\/join|invite\.gg|top\.gg\/servers)\/[\w-]+/gi)) {
    push(m[0], 'invite');
  }
  return [...found.values()];
}

async function loadHosts() {
  if (Date.now() - hostLists.at < 30_000) {
    return hostLists;
  }
  try {
    const { rows } = await pool.query('SELECT host, list FROM bot_link_hosts');
    hostLists = {
      at: Date.now(),
      safe: rows.filter((r) => r.list === 'safe').map((r) => r.host),
      blocked: rows.filter((r) => r.list === 'blocked').map((r) => r.host),
    };
  } catch (e) {
    console.error('[hawkeye-bot] link host lists failed:', e.message);
  }
  return hostLists;
}

async function setHost(host, list, by) {
  await pool.query(
    'INSERT INTO bot_link_hosts (host, list, added_by, added_at) VALUES ($1, $2, $3, $4) ON CONFLICT (host) DO UPDATE SET list = $2, added_by = $3, added_at = $4',
    [host, list, by, Date.now()],
  );
  hostLists.at = 0;
}

async function inviteGuild(client, code) {
  if (inviteCache.has(code)) {
    return inviteCache.get(code);
  }
  let gid = null;
  try {
    const inv = await client.fetchInvite(code);
    gid = inv.guild ? inv.guild.id : null;
  } catch {
    gid = 'dead';
  }
  inviteCache.set(code, gid);
  return gid;
}

async function score(client, guildId, link, cfg) {
  const lists = await loadHosts();
  const blocked = cfg.blockedDomains.concat(lists.blocked);
  const safeList = cfg.safeDomains.concat(lists.safe);
  const reasons = [];
  let level = 'medium';
  const bump = (lvl, why) => {
    reasons.push(why);
    if (PRIORITY[lvl] > PRIORITY[level]) {
      level = lvl;
    }
  };
  const { host } = link;
  const ext = ((link.path.split('/').pop().match(/\.([a-z0-9]{1,9})$/i) || [])[1] || '').toLowerCase();
  if (matches(host, blocked)) {
    bump('critical', 'on the blocked list');
  }
  if (matches(host, IP_GRABBERS) || GRAB_WORDS.test(host)) {
    bump('critical', 'known IP grabber / IP logger');
  }
  if (IP_RE.test(host)) {
    bump('critical', 'raw IP address instead of a website');
  }
  if (RUN_EXT.includes(ext)) {
    bump('critical', `downloads a program (.${ext}) straight away`);
  } else if (PACK_EXT.includes(ext)) {
    bump('high', `downloads a .${ext} file`);
  }
  const safe = !matches(host, blocked) && matches(host, safeList);
  const look = safe ? null : lookalike(host);
  if (look) {
    bump(look.level, look.why);
  }
  if (!safe && (host.includes('xn--') || /[^\x00-\x7f]/.test(host))) {
    bump('high', 'uses look-alike characters in the address');
  }
  if (link.how === 'disguised') {
    bump('high', 'link written with "dot" to dodge filters');
  }
  if (link.url.includes('@') && /^https?:\/\/[^/]*@/.test(link.url)) {
    bump('critical', 'hides the real site behind an @ in the address');
  }
  if (link.how === 'invite' || /^(discord\.gg|discord(app)?\.com\/invite|dsc\.gg|discord\.me)/.test(host + link.path)) {
    const code = link.path.split('/').filter(Boolean).pop();
    const gid = await inviteGuild(client, code);
    if (gid === guildId) {
      return { level: 'safe', reasons: ['our own invite'] };
    }
    bump('high', gid === 'dead' ? 'Discord invite that no longer works' : 'invite to another Discord server');
  }
  if (matches(host, SHORTENERS)) {
    bump('high', 'link shortener hides where it really goes');
  }
  if (matches(host, FILE_HOSTS)) {
    bump('high', 'file download site');
  }
  const tld = host.split('.').slice(-1)[0];
  if (!safe && (RISKY_TLDS.includes(tld) || RISKY_TLDS.includes(host.split('.').slice(-2).join('.')))) {
    bump('high', `.${tld} address, often used for scams`);
  }
  if (level === 'medium' && !reasons.length) {
    if (safe) {
      return { level: 'safe', reasons: [] };
    }
    reasons.push('site not on the safe list');
  }
  return { level, reasons };
}

function defang(link) {
  const host = link.host.replace(/\./g, '[.]');
  const rest = (link.path && link.path !== '/') || link.search ? `/${'•'.repeat(Math.min(8, Math.max(3, (link.path + link.search).length - 1)))}` : '';
  return `hxxp${link.url.startsWith('https') ? 's' : ''}://${host}${rest}`;
}

async function analyze(client, guildId, message, cfg) {
  const known = IP_GRABBERS.concat(SHORTENERS, FILE_HOSTS, cfg.safeDomains);
  const links = extract(message.content, known);
  for (const a of (message.attachments || new Map()).values()) {
    const ext = (((a.name || '').match(/\.([a-z0-9]{1,9})$/i) || [])[1] || '').toLowerCase();
    if (ext && (RUN_EXT.includes(ext) || PACK_EXT.includes(ext))) {
      links.push({ raw: a.url, url: a.url, host: 'attachment', path: `/${a.name}`, search: '', how: 'file', file: a.name, ext });
    }
  }
  const scored = [];
  for (const l of links) {
    const s = l.how === 'file'
      ? { level: RUN_EXT.includes(l.ext) ? 'critical' : 'high', reasons: [`uploaded a .${l.ext} file`] }
      : await score(client, guildId, l, cfg);
    if (s.level !== 'safe') {
      scored.push({ ...l, ...s, defanged: l.how === 'file' ? `file: ${l.file.replace(/\./g, '[.]')}` : defang(l) });
    }
  }
  if (!scored.length) {
    return null;
  }
  let level = scored.reduce((m, s) => (PRIORITY[s.level] > PRIORITY[m] ? s.level : m), 'medium');
  if (NITRO_BAIT.test(message.content || '') && level !== 'critical') {
    level = 'critical';
    scored[0].reasons.unshift('free gift / nitro bait text');
  }
  return { level, scored };
}

module.exports = { analyze, extract, registrable, setHost, PRIORITY, URL_RE };
