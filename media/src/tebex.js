const db = require('./db');
const notify = require('./notify');

const API = 'https://plugin.tebex.io';
const HEADLESS = 'https://headless.tebex.io/api';
const HEADLESS_TOKEN = () => process.env.TEBEX_HEADLESS_TOKEN || '14zzd-4d9f2f0e5a87e44b8c2c82cd043ba46086e5b3c3';
const FALLBACK_RATES = { USD: 1, GBP: 0.79, EUR: 0.92, CAD: 1.37, AUD: 1.52 };
let rates = { at: 0, map: FALLBACK_RATES };
let running = null;
let last = { at: null, ok: null, message: 'Not synced yet', added: 0, updated: 0, earnings: 0 };

async function fxRates() {
    if (Date.now() - rates.at < 12 * 60 * 60 * 1000) {
        return rates.map;
    }
    try {
        const res = await fetch('https://open.er-api.com/v6/latest/USD');
        const body = await res.json();
        if (res.ok && body && body.rates && body.rates.GBP) {
            rates = { at: Date.now(), map: body.rates };
        }
    } catch (e) {
        console.error('FX rate fetch failed:', e.message);
    }
    return rates.map;
}

function toUsd(amount, currency, map) {
    const rate = Number(map[currency] || FALLBACK_RATES[currency] || 0);
    if (!rate) {
        return amount;
    }
    return Math.round((amount / rate) * 100) / 100;
}

function normalizeUuid(raw) {
    const v = String(raw || '').trim().toLowerCase();
    if (/^[0-9a-f]{32}$/.test(v)) {
        return `${v.slice(0, 8)}-${v.slice(8, 12)}-${v.slice(12, 16)}-${v.slice(16, 20)}-${v.slice(20)}`;
    }
    if (/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(v)) {
        return v;
    }
    if (/^\d{1,20}$/.test(v)) {
        const hex = BigInt(v).toString(16).padStart(16, '0');
        return `00000000-0000-0000-${hex.slice(0, 4)}-${hex.slice(4)}`;
    }
    return null;
}

async function fetchPage(page) {
    const res = await fetch(`${API}/payments?paged=1&page=${page}`, { headers: { 'X-Tebex-Secret': process.env.TEBEX_SECRET } });
    if (!res.ok) {
        throw new Error('Tebex payments request failed: ' + res.status);
    }
    return res.json();
}

const PAYMENTS_SINCE = Date.parse(process.env.PAYMENTS_SINCE || '2026-11-21T00:00:00Z');

async function syncPayments() {
    const map = await fxRates();
    let added = 0;
    let updated = 0;
    let page = 1;
    let lastPage = 1;
    do {
        const body = await fetchPage(page);
        lastPage = Math.min(Number(body.last_page || 1), 400);
        for (const p of body.data || []) {
            if (Date.parse(p.date) < PAYMENTS_SINCE) {
                continue;
            }
            const amount = Number(p.amount || 0);
            const currency = (p.currency && p.currency.iso_4217) || 'USD';
            const player = p.player || {};
            const packages = (p.packages || []).map((k) => (k.quantity > 1 ? `${k.quantity}x ${k.name}` : k.name)).join(', ');
            const { rows } = await db.query(
                `INSERT INTO media_payments (id, player_uuid, player_name, amount, currency, amount_usd, status, packages, paid_at)
                 VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9)
                 ON CONFLICT (id) DO UPDATE SET status = excluded.status, player_name = excluded.player_name, synced_at = now()
                 RETURNING (xmax = 0) AS inserted, status`,
                [String(p.id), normalizeUuid(player.uuid), player.name || null, amount, currency, toUsd(amount, currency, map), p.status || 'Unknown', packages, p.date],
            );
            if (rows[0] && rows[0].inserted) {
                added++;
            } else {
                updated++;
            }
        }
        page++;
    } while (page <= lastPage);
    return { added, updated };
}

async function fetchBasket(ident) {
    const res = await fetch(`${HEADLESS}/accounts/${HEADLESS_TOKEN()}/baskets/${encodeURIComponent(ident)}`, { headers: { Accept: 'application/json' } });
    if (!res.ok) {
        return null;
    }
    const body = await res.json();
    return body && body.data ? body.data : null;
}

const RANK_PACKAGES = { Mechanic: 'Mechanic', Machinist: 'Machinist', Overseer: 'Overseer', Industrialist: 'Industrialist', Tycoon: 'Tycoon' };
const PERMANENT_RANKS = new Set(['Mechanic', 'Machinist', 'Overseer', 'Industrialist', 'Tycoon']);
const PASS_PACKAGES = new Set();
const ownedCache = new Map();

async function pluginGet(secret, path) {
    const res = await fetch(`${API}${path}`, { headers: { 'X-Tebex-Secret': secret, Accept: 'application/json' } });
    if (res.status === 404) {
        return null;
    }
    if (!res.ok) {
        throw new Error('Tebex request failed: ' + res.status);
    }
    return res.json();
}

async function activePackages(secret, name) {
    const user = await pluginGet(secret, `/user/${encodeURIComponent(name)}`);
    const id = user && user.player && user.player.id;
    if (!id) {
        return [];
    }
    const list = await pluginGet(secret, `/player/${encodeURIComponent(id)}/packages`);
    return Array.isArray(list) ? list : [];
}

async function owned(name) {
    const key = name.toLowerCase();
    const hit = ownedCache.get(key);
    if (hit && Date.now() - hit.at < 60 * 1000) {
        return hit.value;
    }
    const secrets = [process.env.TEBEX_SECRET, process.env.TEBEX_LEGACY_SECRET].filter(Boolean);
    const lists = await Promise.all(secrets.map((s) => activePackages(s, name)));
    const ranks = new Map();
    let pass = false;
    for (const p of lists.flat()) {
        const n = p && p.package && p.package.name;
        const rank = RANK_PACKAGES[n];
        if (rank) {
            const prev = ranks.get(rank) || { rank, permanent: false, subscription: false };
            const perm = PERMANENT_RANKS.has(n);
            ranks.set(rank, { rank, permanent: prev.permanent || perm, subscription: prev.subscription || !perm });
        }
        if (PASS_PACKAGES.has(n)) {
            pass = true;
        }
    }
    const value = { ranks: [...ranks.values()], pass };
    ownedCache.set(key, { at: Date.now(), value });
    if (ownedCache.size > 2000) {
        ownedCache.delete(ownedCache.keys().next().value);
    }
    return value;
}

async function checkClaims() {
    const { rows } = await db.query(
        "SELECT basket FROM media_code_claims WHERE NOT complete AND created_at > now() - interval '3 days' ORDER BY created_at DESC LIMIT 200");
    for (const r of rows) {
        try {
            const b = await fetchBasket(r.basket);
            await db.query('UPDATE media_code_claims SET complete = $2, checked_at = now() WHERE basket = $1', [r.basket, Boolean(b && b.complete)]);
        } catch (e) {
            console.error('Basket check failed:', e.message);
        }
    }
}

const NOT_OWN = `
    (c.minecraft_name IS NULL OR lower(coalesce(p.player_name, '')) <> lower(c.minecraft_name))
    AND NOT EXISTS (
        SELECT 1 FROM pvp_join_hosts own JOIN pvp_join_hosts buyer ON buyer.uuid = p.player_uuid
        WHERE c.minecraft_name IS NOT NULL AND lower(own.player_name) = lower(c.minecraft_name)
          AND own.first_ip IS NOT NULL AND own.first_ip = buyer.first_ip
    )`;

async function attribute() {
    const codes = await db.query(`
        WITH matched AS (
            SELECT DISTINCT ON (p.id) p.id AS payment_id, k.basket, k.creator_id, p.player_uuid, p.amount_usd
            FROM media_payments p
            JOIN media_code_claims k ON k.player_uuid = p.player_uuid AND k.complete AND k.payment_id IS NULL
                AND p.paid_at BETWEEN k.created_at - interval '10 minutes' AND k.created_at + interval '1 day'
            JOIN media_creators c ON c.id = k.creator_id AND c.active
            WHERE p.status = 'Complete' AND p.amount_usd > 0
              AND NOT EXISTS (SELECT 1 FROM media_earnings e WHERE e.payment_id = p.id)
              AND NOT EXISTS (SELECT 1 FROM media_code_claims u WHERE u.payment_id = p.id)
              AND ${NOT_OWN}
            ORDER BY p.id, k.created_at DESC
        ),
        claimed AS (
            UPDATE media_code_claims k SET payment_id = m.payment_id FROM matched m WHERE k.basket = m.basket RETURNING k.basket
        )
        INSERT INTO media_earnings (payment_id, creator_id, player_uuid, amount_usd, cut_percent, earning_usd, source)
        SELECT m.payment_id, c.id, m.player_uuid, m.amount_usd, c.cut_percent, round(m.amount_usd * c.cut_percent / 100, 2), 'code'
        FROM matched m JOIN media_creators c ON c.id = m.creator_id
        ON CONFLICT (payment_id) DO NOTHING
        RETURNING payment_id, creator_id, earning_usd, source`);
    const links = await db.query(`
        INSERT INTO media_earnings (payment_id, creator_id, player_uuid, amount_usd, cut_percent, earning_usd, source)
        SELECT p.id, c.id, p.player_uuid, p.amount_usd, c.cut_percent, round(p.amount_usd * c.cut_percent / 100, 2), 'link'
        FROM media_payments p
        JOIN pvp_join_hosts j ON j.uuid = p.player_uuid AND j.new_player
        JOIN media_hosts h ON h.host = j.first_host
        JOIN media_creators c ON c.id = h.creator_id AND c.active
        WHERE p.status = 'Complete' AND p.amount_usd > 0
          AND p.paid_at >= to_timestamp(j.first_seen / 1000.0)
          AND (c.window_days = 0 OR p.paid_at < to_timestamp(j.first_seen / 1000.0) + c.window_days * interval '1 day')
          AND ${NOT_OWN}
        ON CONFLICT (payment_id) DO NOTHING
        RETURNING payment_id, creator_id, earning_usd, source`);
    const added = codes.rows.concat(links.rows);
    await announce(added);
    return added.length;
}

async function announce(added) {
    if (!added.length) {
        return;
    }
    const { rows } = await db.query(`
        SELECT e.payment_id, e.earning_usd, e.source, p.player_name, p.packages, p.amount_usd, p.paid_at, c.discord_id, c.code
        FROM media_earnings e JOIN media_payments p ON p.id = e.payment_id JOIN media_creators c ON c.id = e.creator_id
        WHERE e.payment_id = ANY($1) AND p.paid_at > now() - interval '2 days'`, [added.map((r) => r.payment_id)]);
    for (const r of rows) {
        const how = r.source === 'code' ? `used your code **${r.code}**` : 'joined through your link';
        await notify.dm(r.discord_id, `💸 **New sale!** ${r.player_name || 'A player'} ${how} and bought ${r.packages || 'something'} (${notify.usd(r.amount_usd)}). You earned **${notify.usd(r.earning_usd)}**.\n${notify.portal('/me')}`);
    }
}

async function sync() {
    if (running) {
        return running;
    }
    running = (async () => {
        try {
            if (!process.env.TEBEX_SECRET) {
                throw new Error('TEBEX_SECRET is not set');
            }
            const { added, updated } = await syncPayments();
            await checkClaims();
            const earnings = await attribute();
            last = { at: new Date(), ok: true, message: 'Synced', added, updated, earnings };
            if (added || earnings) {
                console.log(`Tebex sync: ${added} new payments, ${earnings} new creator earnings`);
            }
        } catch (e) {
            last = { at: new Date(), ok: false, message: e.message, added: 0, updated: 0, earnings: 0 };
            console.error('Tebex sync failed:', e.message);
        } finally {
            running = null;
        }
        return last;
    })();
    return running;
}

function start(minutes) {
    setTimeout(sync, 5000);
    setInterval(sync, Math.max(2, minutes) * 60 * 1000);
}

module.exports = { sync, start, attribute, status: () => last, normalizeUuid, fetchBasket, owned };
