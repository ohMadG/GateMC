const express = require('express');
const rateLimit = require('express-rate-limit');
const db = require('../db');
const tebex = require('../tebex');
const status = require('../status');
const v = require('../validate');

const router = express.Router();
const ORIGINS = new Set(String(process.env.STORE_ORIGINS || 'https://store.gatemc.org').split(',').map((s) => s.trim()).filter(Boolean));

router.use((req, res, next) => {
    const origin = req.get('Origin');
    if (origin && ORIGINS.has(origin)) {
        res.set('Access-Control-Allow-Origin', origin);
        res.set('Vary', 'Origin');
        res.set('Access-Control-Allow-Methods', 'GET, POST');
        res.set('Access-Control-Allow-Headers', 'Content-Type');
        res.set('Access-Control-Max-Age', '600');
    }
    if (req.method === 'OPTIONS') {
        return res.sendStatus(204);
    }
    next();
});
router.get('/players', rateLimit({ windowMs: 60 * 1000, limit: 20, standardHeaders: true, legacyHeaders: false }), async (req, res) => {
    const p = await status.players();
    res.set('Cache-Control', 'public, max-age=30');
    res.json({ ok: true, online: p.online, max: p.max, up: p.up });
});

router.use(rateLimit({ windowMs: 10 * 60 * 1000, limit: 60, standardHeaders: true, legacyHeaders: false }));
router.use(express.json({ limit: '2kb' }));

async function findCode(raw) {
    const c = v.code(raw);
    if (!c.code) {
        return null;
    }
    const { rows } = await db.query('SELECT id, name, code FROM media_creators WHERE lower(code) = lower($1) AND active', [c.code]);
    return rows[0] || null;
}

router.get('/code/:code', async (req, res) => {
    try {
        const c = await findCode(req.params.code);
        if (!c) {
            return res.status(404).json({ ok: false, error: "That creator code doesn't exist." });
        }
        res.json({ ok: true, code: c.code, name: c.name });
    } catch (e) {
        console.error('Code lookup failed:', e.message);
        res.status(500).json({ ok: false, error: 'Try again in a moment.' });
    }
});

router.get('/owned/:name', async (req, res) => {
    const name = String(req.params.name || '');
    if (!/^\.?[A-Za-z0-9_]{3,16}$/.test(name)) {
        return res.status(400).json({ ok: false, error: 'Bad username.' });
    }
    try {
        const o = await tebex.owned(name);
        res.json({ ok: true, ranks: o.ranks, pass: o.pass });
    } catch (e) {
        console.error('Owned lookup failed:', e.message);
        res.status(502).json({ ok: false, error: 'Try again in a moment.' });
    }
});

router.post('/claim', async (req, res) => {
    try {
        const body = req.body || {};
        const basketId = String(body.basket || '');
        if (!/^[A-Za-z0-9-]{10,80}$/.test(basketId)) {
            return res.status(400).json({ ok: false, error: 'Bad basket.' });
        }
        const c = await findCode(body.code);
        if (!c) {
            return res.status(404).json({ ok: false, error: "That creator code doesn't exist." });
        }
        const basket = await tebex.fetchBasket(basketId);
        if (!basket || basket.complete) {
            return res.status(400).json({ ok: false, error: 'Basket not found.' });
        }
        const uuid = tebex.normalizeUuid(basket.username_id);
        if (!uuid) {
            return res.status(400).json({ ok: false, error: 'Basket has no player.' });
        }
        await db.query(
            `INSERT INTO media_code_claims (basket, creator_id, code, player_uuid, player_name) VALUES ($1, $2, $3, $4, $5)
             ON CONFLICT (basket) DO UPDATE SET creator_id = excluded.creator_id, code = excluded.code, created_at = now()
             WHERE media_code_claims.payment_id IS NULL`,
            [basketId, c.id, c.code, uuid, basket.username || null],
        );
        res.json({ ok: true, name: c.name });
    } catch (e) {
        console.error('Code claim failed:', e.message);
        res.status(500).json({ ok: false, error: 'Try again in a moment.' });
    }
});

module.exports = router;
