const express = require('express');
const db = require('../db');
const stats = require('../stats');
const tebex = require('../tebex');
const v = require('../validate');
const notify = require('../notify');
const program = require('../program');
const { requireAdmin } = require('../auth');

const router = express.Router();
router.use(requireAdmin);

const base = () => process.env.MEDIA_BASE_DOMAIN || 'gatemc.org';

function flash(req, kind, text) {
    req.session.flash = { kind, text };
}

function takeFlash(req) {
    const f = req.session.flash || null;
    req.session.flash = null;
    return f;
}

async function pendingCount() {
    const { rows } = await db.query("SELECT kind, count(*)::int AS n FROM media_applications WHERE status IN ('pending', 'countered') GROUP BY kind");
    const out = { creator: 0, increase: 0 };
    for (const r of rows) {
        out[r.kind] = r.n;
    }
    out.total = out.creator + out.increase;
    return out;
}

async function setRate(client, creatorId, percent, by, reason) {
    const { rows } = await client.query('SELECT cut_percent FROM media_creators WHERE id = $1 FOR UPDATE', [creatorId]);
    if (!rows[0]) {
        return false;
    }
    const old = Number(rows[0].cut_percent);
    if (old === percent) {
        return true;
    }
    await client.query('UPDATE media_creators SET cut_percent = $2, updated_at = now() WHERE id = $1', [creatorId, percent]);
    await client.query('INSERT INTO media_rate_changes (creator_id, old_percent, new_percent, changed_by, reason) VALUES ($1, $2, $3, $4, $5)',
        [creatorId, old, percent, by, reason || null]);
    return true;
}

async function addHost(client, creatorId, raw) {
    const h = v.host(raw, base());
    if (h.error) {
        return h.error;
    }
    const { rows } = await client.query('SELECT creator_id FROM media_hosts WHERE host = $1', [h.host]);
    if (rows[0]) {
        return rows[0].creator_id === creatorId ? null : `${h.host} already belongs to another creator.`;
    }
    await client.query('INSERT INTO media_hosts (host, creator_id) VALUES ($1, $2)', [h.host, creatorId]);
    return null;
}

async function codeError(raw, creatorId) {
    const c = v.code(raw);
    if (c.error) {
        return { error: c.error };
    }
    if (c.code) {
        const { rows } = await db.query('SELECT name FROM media_creators WHERE lower(code) = lower($1) AND id <> $2', [c.code, creatorId || 0]);
        if (rows[0]) {
            return { error: `The code ${c.code} already belongs to ${rows[0].name}.` };
        }
    }
    return { code: c.code };
}

async function tx(work) {
    const client = await db.connect();
    try {
        await client.query('BEGIN');
        const result = await work(client);
        await client.query('COMMIT');
        return result;
    } catch (e) {
        await client.query('ROLLBACK');
        throw e;
    } finally {
        client.release();
    }
}

function who(req) {
    return req.session.viewer.username;
}

router.get('/', async (req, res, next) => {
    try {
        const creators = await stats.creators();
        res.render('admin-overview', {
            title: 'Overview',
            active: 'overview',
            creators,
            totals: stats.totals(creators),
            months: await stats.monthly(null),
            recent: await stats.purchases({ limit: 12 }),
            retention: await stats.retention(null),
            pending: await pendingCount(),
            sync: tebex.status(),
            flash: takeFlash(req),
        });
    } catch (e) {
        next(e);
    }
});

router.post('/sync', async (req, res) => {
    const s = await tebex.sync();
    flash(req, s.ok ? 'ok' : 'bad', s.ok ? `Tebex synced: ${s.added} new payments, ${s.earnings} new creator earnings.` : `Tebex sync failed: ${s.message}`);
    res.redirect('/admin');
});

router.get('/players', async (req, res, next) => {
    try {
        const creatorId = Number.parseInt(req.query.creator, 10) || null;
        const q = v.text(req.query.q, 32);
        res.render('admin-players', {
            title: 'Players',
            active: 'players',
            players: await stats.players({ creatorId, q, limit: 1000 }),
            creators: await stats.creators(),
            creatorId,
            q,
        });
    } catch (e) {
        next(e);
    }
});

router.get('/payouts', async (req, res, next) => {
    try {
        const creators = await stats.creators();
        res.render('admin-payouts', {
            title: 'Payouts',
            active: 'payouts',
            creators,
            totals: stats.totals(creators),
            payouts: await stats.payouts(null),
            flash: takeFlash(req),
        });
    } catch (e) {
        next(e);
    }
});

router.get('/creators/new', (req, res) => {
    res.render('admin-creator-new', { title: 'Add creator', active: 'creators', values: {}, error: null, baseDomain: base() });
});

router.post('/creators', async (req, res, next) => {
    const values = req.body;
    const name = v.text(values.name, 64);
    const discordId = v.text(values.discord_id, 25);
    const cut = v.percent(values.cut_percent);
    const fail = (error) => res.status(400).render('admin-creator-new', { title: 'Add creator', active: 'creators', values, error, baseDomain: base() });
    if (!name) {
        return fail('Give the creator a name.');
    }
    if (discordId && !/^\d{15,22}$/.test(discordId)) {
        return fail('The Discord ID should be the long number from Copy User ID.');
    }
    if (cut === null) {
        return fail('The cut must be a percentage between 0 and 100.');
    }
    const code = await codeError(values.code, 0);
    if (code.error) {
        return fail(code.error);
    }
    try {
        const id = await tx(async (client) => {
            if (discordId) {
                const dup = await client.query('SELECT name FROM media_creators WHERE discord_id = $1', [discordId]);
                if (dup.rows[0]) {
                    throw Object.assign(new Error(`That Discord account is already linked to ${dup.rows[0].name}.`), { user: true });
                }
            }
            const { rows } = await client.query(
                `INSERT INTO media_creators (name, discord_id, minecraft_name, channel_url, cut_percent, window_days, hold_days, notes, code)
                 VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9) RETURNING id`,
                [name, discordId || null, v.text(values.minecraft_name, 32) || null, v.url(values.channel_url) || null, cut,
                    v.whole(values.window_days, 0, 3650, 60), v.whole(values.hold_days, 0, 180, 30), v.longText(values.notes, 1000) || null, code.code],
            );
            await client.query('INSERT INTO media_rate_changes (creator_id, old_percent, new_percent, changed_by, reason) VALUES ($1, NULL, $2, $3, $4)',
                [rows[0].id, cut, who(req), 'Starting rate']);
            if (v.text(values.host, 120)) {
                const err = await addHost(client, rows[0].id, values.host);
                if (err) {
                    throw Object.assign(new Error(err), { user: true });
                }
            }
            return rows[0].id;
        });
        flash(req, 'ok', `${name} added.`);
        res.redirect(`/admin/creators/${id}`);
    } catch (e) {
        if (e.user) {
            return fail(e.message);
        }
        next(e);
    }
});

async function loadCreator(req, res, next) {
    try {
        const c = await stats.creator(Number.parseInt(req.params.id, 10) || 0);
        if (!c) {
            return res.status(404).render('error', { title: 'Creator not found', message: 'That creator does not exist.' });
        }
        req.creator = c;
        next();
    } catch (e) {
        next(e);
    }
}

router.get('/creators/:id', loadCreator, async (req, res, next) => {
    try {
        const c = req.creator;
        const apps = await db.query("SELECT * FROM media_applications WHERE creator_id = $1 OR discord_id = $2 ORDER BY created_at DESC LIMIT 20", [c.id, c.discord_id || '-']);
        res.render('admin-creator', {
            title: c.name,
            active: 'creators',
            c,
            months: await stats.monthly(c.id),
            players: await stats.players({ creatorId: c.id, limit: 500 }),
            purchases: await stats.purchases({ creatorId: c.id, limit: 200 }),
            payouts: await stats.payouts(c.id),
            rates: await stats.rateHistory(c.id),
            content: await stats.content(c.id),
            activity: await stats.activity(c),
            retention: await stats.retention(c.id),
            apps: apps.rows,
            flash: takeFlash(req),
            baseDomain: base(),
        });
    } catch (e) {
        next(e);
    }
});

router.get('/creators/:id/preview', loadCreator, async (req, res, next) => {
    try {
        const c = req.creator;
        res.render('creator-dashboard', Object.assign(await require('./creator').dashboardData(c), { title: `${c.name} (preview)`, active: 'creators', preview: true, flash: null }));
    } catch (e) {
        next(e);
    }
});

router.post('/creators/:id', loadCreator, async (req, res, next) => {
    const c = req.creator;
    const b = req.body;
    const name = v.text(b.name, 64);
    const discordId = v.text(b.discord_id, 25);
    if (!name) {
        flash(req, 'bad', 'The creator needs a name.');
        return res.redirect(`/admin/creators/${c.id}`);
    }
    if (discordId && !/^\d{15,22}$/.test(discordId)) {
        flash(req, 'bad', 'The Discord ID should be the long number from Copy User ID.');
        return res.redirect(`/admin/creators/${c.id}`);
    }
    const code = await codeError(b.code, c.id);
    if (code.error) {
        flash(req, 'bad', code.error);
        return res.redirect(`/admin/creators/${c.id}`);
    }
    try {
        const dup = discordId ? await db.query('SELECT name FROM media_creators WHERE discord_id = $1 AND id <> $2', [discordId, c.id]) : { rows: [] };
        if (dup.rows[0]) {
            flash(req, 'bad', `That Discord account is already linked to ${dup.rows[0].name}.`);
            return res.redirect(`/admin/creators/${c.id}`);
        }
        await db.query(
            `UPDATE media_creators SET name = $2, discord_id = $3, minecraft_name = $4, channel_url = $5, window_days = $6, hold_days = $7,
             active = $8, notes = $9, code = $10, updated_at = now(),
             activity_reset_at = CASE WHEN $8 AND NOT active THEN now() ELSE activity_reset_at END,
             inactivity_warned_at = CASE WHEN $8 AND NOT active THEN NULL ELSE inactivity_warned_at END,
             removed_at = CASE WHEN $8 THEN NULL ELSE removed_at END,
             removed_reason = CASE WHEN $8 THEN NULL ELSE removed_reason END
             WHERE id = $1`,
            [c.id, name, discordId || null, v.text(b.minecraft_name, 32) || null, v.url(b.channel_url) || null,
                v.whole(b.window_days, 0, 3650, c.window_days), v.whole(b.hold_days, 0, 180, c.hold_days), b.active === 'on', v.longText(b.notes, 1000) || null, code.code],
        );
        flash(req, 'ok', 'Settings saved.');
        res.redirect(`/admin/creators/${c.id}`);
    } catch (e) {
        next(e);
    }
});

router.post('/creators/:id/rate', loadCreator, async (req, res, next) => {
    const c = req.creator;
    const cut = v.percent(req.body.cut_percent);
    if (cut === null) {
        flash(req, 'bad', 'The cut must be a percentage between 0 and 100.');
        return res.redirect(`/admin/creators/${c.id}`);
    }
    try {
        await tx((client) => setRate(client, c.id, cut, who(req), v.text(req.body.reason, 200)));
        flash(req, 'ok', `Cut set to ${cut}%. It applies to purchases from now on.`);
        res.redirect(`/admin/creators/${c.id}`);
    } catch (e) {
        next(e);
    }
});

router.post('/creators/:id/hosts', loadCreator, async (req, res, next) => {
    const c = req.creator;
    try {
        const err = await tx((client) => addHost(client, c.id, req.body.host));
        flash(req, err ? 'bad' : 'ok', err || 'Domain added.');
        res.redirect(`/admin/creators/${c.id}`);
    } catch (e) {
        next(e);
    }
});

router.post('/creators/:id/hosts/remove', loadCreator, async (req, res, next) => {
    const c = req.creator;
    try {
        await db.query('DELETE FROM media_hosts WHERE host = $1 AND creator_id = $2', [String(req.body.host || ''), c.id]);
        flash(req, 'ok', 'Domain removed. Players who already joined through it stay linked only while it belongs to a creator.');
        res.redirect(`/admin/creators/${c.id}`);
    } catch (e) {
        next(e);
    }
});

router.post('/creators/:id/payouts', loadCreator, async (req, res, next) => {
    const c = req.creator;
    const amount = v.money(req.body.amount);
    if (amount === null) {
        flash(req, 'bad', 'Enter the amount you paid.');
        return res.redirect(`/admin/creators/${c.id}#payouts`);
    }
    try {
        await db.query('INSERT INTO media_payouts (creator_id, amount_usd, method, note, paid_by) VALUES ($1, $2, $3, $4, $5)',
            [c.id, amount, v.text(req.body.method, 40) || null, v.text(req.body.note, 200) || null, who(req)]);
        notify.dm(c.discord_id, `💰 **You've been paid!** ${notify.usd(amount)}${req.body.method ? ' via ' + v.text(req.body.method, 40) : ''} for your GateMC creator earnings. Thanks for repping GateMC!\n${notify.portal('/me')}`);
        flash(req, 'ok', `Recorded a $${amount.toFixed(2)} payout to ${c.name}.`);
        res.redirect(`/admin/creators/${c.id}#payouts`);
    } catch (e) {
        next(e);
    }
});

router.post('/payouts/:id/delete', async (req, res, next) => {
    try {
        const { rows } = await db.query('DELETE FROM media_payouts WHERE id = $1 RETURNING creator_id', [Number.parseInt(req.params.id, 10) || 0]);
        flash(req, 'ok', 'Payout removed.');
        res.redirect(rows[0] ? `/admin/creators/${rows[0].creator_id}#payouts` : '/admin/payouts');
    } catch (e) {
        next(e);
    }
});

router.get('/applications', async (req, res, next) => {
    try {
        const groups = { pending: ['pending', 'countered'], offered: ['offered'], accepted: ['accepted'], denied: ['denied', 'declined', 'withdrawn'] };
        const status = groups[req.query.status] || req.query.status === 'all' ? req.query.status : 'pending';
        const { rows } = await db.query(
            `SELECT a.*, c.name AS creator_name FROM media_applications a LEFT JOIN media_creators c ON c.id = a.creator_id
             ${status === 'all' ? '' : 'WHERE a.status = ANY($1)'} ORDER BY a.created_at DESC LIMIT 300`,
            status === 'all' ? [] : [groups[status]],
        );
        res.render('admin-applications', { title: 'Applications', active: 'applications', apps: rows, status, pending: await pendingCount(), flash: takeFlash(req) });
    } catch (e) {
        next(e);
    }
});

async function loadApp(req, res, next) {
    try {
        const { rows } = await db.query('SELECT a.*, c.name AS creator_name, c.cut_percent AS creator_cut FROM media_applications a LEFT JOIN media_creators c ON c.id = a.creator_id WHERE a.id = $1',
            [Number.parseInt(req.params.id, 10) || 0]);
        if (!rows[0]) {
            return res.status(404).render('error', { title: 'Application not found', message: 'That application does not exist.' });
        }
        req.app_ = rows[0];
        next();
    } catch (e) {
        next(e);
    }
}

router.get('/applications/:id', loadApp, async (req, res, next) => {
    try {
        const a = req.app_;
        const c = a.creator_id ? await stats.creator(a.creator_id) : null;
        res.render('admin-application', { title: `Application #${a.id}`, active: 'applications', a, c, flash: takeFlash(req), baseDomain: base(),
            defaultCut: Number(process.env.DEFAULT_CUT_PERCENT || 10), denyReasons: program.DENY_REASONS });
    } catch (e) {
        next(e);
    }
});

router.post('/applications/:id/accept', loadApp, async (req, res, next) => {
    const a = req.app_;
    const cut = v.percent(req.body.cut_percent);
    const note = v.text(req.body.note, 500) || null;
    if (cut === null) {
        flash(req, 'bad', 'The cut must be a percentage between 0 and 100.');
        return res.redirect(`/admin/applications/${a.id}`);
    }
    if (a.kind === 'increase') {
        if (a.status !== 'pending') {
            flash(req, 'bad', 'This request was already reviewed.');
            return res.redirect(`/admin/applications/${a.id}`);
        }
        try {
            await tx(async (client) => {
                await setRate(client, a.creator_id, cut, who(req), `Increase request #${a.id}`);
                await client.query(
                    "UPDATE media_applications SET status = 'accepted', reviewed_by = $2, review_note = $3, granted_percent = $4, reviewed_at = now() WHERE id = $1",
                    [a.id, who(req), note, cut]);
            });
            notify.dm(a.discord_id, `📈 Your request for a higher cut was approved. Your cut is now **${cut}%**.${note ? '\n> ' + note : ''}\n${notify.portal('/me')}`);
            flash(req, 'ok', `Approved. Their cut is now ${cut}%.`);
            return res.redirect(`/admin/creators/${a.creator_id}`);
        } catch (e) {
            return next(e);
        }
    }
    if (a.status !== 'pending') {
        flash(req, 'bad', 'An offer was already sent for this application.');
        return res.redirect(`/admin/applications/${a.id}`);
    }
    try {
        const terms = await program.checkTerms(db, { host: v.text(req.body.host, 120) || null, code: v.text(req.body.code, 20) || null }, null);
        const { rows } = await db.query(
            `UPDATE media_applications SET status = 'offered', offer_percent = $2, offer_window_days = $3, offer_hold_days = $4, offer_name = $5,
             offer_host = $6, offer_code = $7, offer_note = $8, offered_by = $9, offered_at = now(), reviewed_by = $9, reviewed_at = now(), offer_final = false
             WHERE id = $1 AND status = 'pending' RETURNING *`,
            [a.id, cut, v.whole(req.body.window_days, 0, 3650, 60), v.whole(req.body.hold_days, 0, 180, 30), v.text(req.body.name, 64) || a.discord_name,
                terms.host, terms.code, note, who(req)]);
        if (!rows[0]) {
            flash(req, 'bad', 'This application changed while you were reviewing it.');
            return res.redirect(`/admin/applications/${a.id}`);
        }
        const sent = await notify.dm(a.discord_id, `🎉 **Good news! GateMC wants to partner with you.** Here's our offer:\n${program.offerText(rows[0], cut, false)}`);
        flash(req, 'ok', `Offer of ${cut}% sent to ${a.discord_name}.${sent ? ' They got a Discord DM.' : ' The Discord DM could not be sent, so let them know to check the portal.'}`);
        res.redirect(`/admin/applications/${a.id}`);
    } catch (e) {
        if (e.user) {
            flash(req, 'bad', e.message);
            return res.redirect(`/admin/applications/${a.id}`);
        }
        next(e);
    }
});

router.post('/applications/:id/counter', loadApp, async (req, res, next) => {
    const a = req.app_;
    if (a.status !== 'countered') {
        flash(req, 'bad', 'There is no counter offer to answer.');
        return res.redirect(`/admin/applications/${a.id}`);
    }
    const action = String(req.body.action || '');
    const note = v.text(req.body.note, 500) || null;
    try {
        if (action === 'accept') {
            const percent = Number(a.counter_percent);
            const result = await tx((client) => program.finalize(client, a, percent, who(req)));
            await welcome(a, result, percent);
            flash(req, 'ok', `Accepted their ${percent}%. ${a.discord_name} is now a creator.`);
            return res.redirect(`/admin/creators/${result.creatorId}`);
        }
        let percent = Number(a.offer_percent);
        if (action === 'final') {
            const p = v.percent(req.body.cut_percent);
            if (p === null) {
                flash(req, 'bad', 'The final offer must be a percentage between 0 and 100.');
                return res.redirect(`/admin/applications/${a.id}`);
            }
            percent = p;
        } else if (action !== 'keep') {
            flash(req, 'bad', 'Choose how to answer the counter offer.');
            return res.redirect(`/admin/applications/${a.id}`);
        }
        const { rows } = await db.query(
            `UPDATE media_applications SET status = 'offered', offer_percent = $2, offer_final = true, offer_note = coalesce($3, offer_note), offered_by = $4, offered_at = now()
             WHERE id = $1 AND status = 'countered' RETURNING *`, [a.id, percent, note, who(req)]);
        if (rows[0]) {
            const lead = action === 'keep'
                ? `We can't do ${Number(a.counter_percent)}%, but our offer of **${percent}%** still stands.`
                : `We can't do ${Number(a.counter_percent)}%, but we can offer **${percent}%**.`;
            await notify.dm(a.discord_id, `${lead}\n${program.offerText(rows[0], percent, true)}`);
        }
        flash(req, 'ok', `Final offer of ${percent}% sent. They can now only accept or decline.`);
        res.redirect(`/admin/applications/${a.id}`);
    } catch (e) {
        if (e.user) {
            flash(req, 'bad', e.message);
            return res.redirect(`/admin/applications/${a.id}`);
        }
        next(e);
    }
});

async function welcome(a, result, percent) {
    const { rows } = await db.query('SELECT name, window_days, hold_days FROM media_creators WHERE id = $1', [result.creatorId]);
    const c = rows[0];
    await notify.dm(a.discord_id, program.welcomeText({ name: c.name, percent, host: result.host, code: result.code, window: c.window_days, hold: c.hold_days }));
}

router.post('/applications/:id/deny', loadApp, async (req, res, next) => {
    const a = req.app_;
    if (!['pending', 'offered', 'countered'].includes(a.status)) {
        flash(req, 'bad', 'This application is already closed.');
        return res.redirect(`/admin/applications/${a.id}`);
    }
    const preset = program.DENY_REASONS[Number.parseInt(req.body.preset, 10)];
    const custom = v.text(req.body.note, 500);
    const reason = [preset, custom].filter(Boolean).join(' ');
    if (!reason) {
        flash(req, 'bad', 'Pick a reason or write one. It is sent to them on Discord.');
        return res.redirect(`/admin/applications/${a.id}`);
    }
    try {
        const { rowCount } = await db.query(
            "UPDATE media_applications SET status = 'denied', reviewed_by = $2, review_note = $3, reviewed_at = now() WHERE id = $1 AND status IN ('pending', 'offered', 'countered')",
            [a.id, who(req), reason]);
        let sent = false;
        if (rowCount) {
            sent = await notify.dm(a.discord_id, a.kind === 'creator'
                ? `Thanks for applying to the GateMC Media program. Unfortunately we can't accept your application right now.\n> ${reason}\nYou're welcome to apply again later: ${notify.portal('/apply')}`
                : `Your request for a higher cut wasn't approved this time.\n> ${reason}\n${notify.portal('/me')}`);
        }
        flash(req, 'ok', `Application denied.${sent ? ' They got a Discord DM with the reason.' : ' The Discord DM could not be sent.'}`);
        res.redirect('/admin/applications');
    } catch (e) {
        next(e);
    }
});

module.exports = router;
