const express = require('express');
const db = require('../db');
const stats = require('../stats');
const v = require('../validate');
const links = require('../links');
const notify = require('../notify');
const { requireCreator } = require('../auth');

const router = express.Router();

async function dashboardData(c) {
    const pendingIncrease = await db.query(
        "SELECT * FROM media_applications WHERE creator_id = $1 AND kind = 'increase' ORDER BY created_at DESC LIMIT 5", [c.id]);
    return {
        c,
        months: await stats.monthly(c.id),
        players: await stats.players({ creatorId: c.id, limit: 500 }),
        purchases: await stats.purchases({ creatorId: c.id, limit: 100 }),
        payouts: await stats.payouts(c.id),
        rates: await stats.rateHistory(c.id),
        increases: pendingIncrease.rows,
        content: await stats.content(c.id),
        activity: await stats.activity(c),
        retention: await stats.retention(c.id),
        preview: false,
    };
}

router.get('/', requireCreator, async (req, res, next) => {
    try {
        const c = await stats.creator(req.session.viewer.creatorId);
        if (!c) {
            return res.redirect('/apply');
        }
        const flash = req.session.flash || null;
        req.session.flash = null;
        res.render('creator-dashboard', Object.assign(await dashboardData(c), { title: 'Dashboard', active: 'me', flash }));
    } catch (e) {
        next(e);
    }
});

router.get('/increase', requireCreator, async (req, res, next) => {
    try {
        const c = await stats.creator(req.session.viewer.creatorId);
        const pending = await db.query("SELECT * FROM media_applications WHERE creator_id = $1 AND kind = 'increase' AND status = 'pending' LIMIT 1", [c.id]);
        res.render('creator-increase', { title: 'Request a higher cut', active: 'increase', c, pending: pending.rows[0] || null, values: {}, error: null });
    } catch (e) {
        next(e);
    }
});

router.post('/increase', requireCreator, async (req, res, next) => {
    try {
        const c = await stats.creator(req.session.viewer.creatorId);
        const pending = await db.query("SELECT 1 FROM media_applications WHERE creator_id = $1 AND kind = 'increase' AND status = 'pending' LIMIT 1", [c.id]);
        const values = req.body;
        const fail = (error) => res.status(400).render('creator-increase', { title: 'Request a higher cut', active: 'increase', c, pending: null, values, error });
        if (pending.rows.length) {
            return res.redirect('/me/increase');
        }
        const requested = v.percent(values.requested_percent);
        if (requested === null || requested <= c.cut_percent) {
            return fail(`Ask for a percentage higher than your current ${c.cut_percent}%.`);
        }
        const why = v.longText(values.why, 2000);
        if (why.length < 20) {
            return fail('Tell us a bit more about why (at least a sentence or two).');
        }
        const issue = links.noLinks(values.followers, 'Followers') || links.noLinks(values.avg_viewers, 'Average views') || links.textIssue(why, 'Why');
        if (issue) {
            return fail(issue);
        }
        await db.query(
            `INSERT INTO media_applications (kind, discord_id, discord_name, creator_id, minecraft_name, channel_url, followers, avg_viewers, why, current_percent, requested_percent)
             VALUES ('increase', $1, $2, $3, $4, $5, $6, $7, $8, $9, $10)`,
            [req.session.viewer.discordId, req.session.viewer.username, c.id, c.minecraft_name, c.channel_url,
                v.text(values.followers, 60) || null, v.text(values.avg_viewers, 60) || null, why, c.cut_percent, requested],
        );
        notify.admins(`📈 **Cut increase request** from ${c.name}: ${c.cut_percent}% → ${requested}%\n${notify.portal('/admin/applications')}`);
        req.session.flash = { kind: 'ok', text: `Request sent. We'll review your ask for ${requested}% and let you know.` };
        res.redirect('/me');
    } catch (e) {
        next(e);
    }
});

router.post('/content', requireCreator, async (req, res, next) => {
    try {
        const c = await stats.creator(req.session.viewer.creatorId);
        const checked = links.content(req.body.url);
        const url = checked.url || '';
        const posted = String(req.body.posted_on || '');
        const day = /^\d{4}-\d{2}-\d{2}$/.test(posted) ? new Date(posted + 'T12:00:00Z') : null;
        const now = Date.now();
        if (!url) {
            req.session.flash = { kind: 'bad', text: checked.error || 'Add the link to your video or stream.' };
        } else if (!day || Number.isNaN(day.getTime()) || day.getTime() > now + 86400000 || day.getTime() < now - 365 * 86400000) {
            req.session.flash = { kind: 'bad', text: 'Pick the date it went up (within the last year).' };
        } else if (!c.active) {
            req.session.flash = { kind: 'bad', text: 'Your partnership is not active, so content can no longer be logged here.' };
        } else {
            await db.query('INSERT INTO media_content (creator_id, url, title, posted_on, added_by) VALUES ($1, $2, $3, $4, $5)',
                [c.id, url, v.text(req.body.title, 120) || null, posted, req.session.viewer.username]);
            req.session.flash = { kind: 'ok', text: 'Content logged. Thanks for repping GateMC!' };
        }
        res.redirect('/me#content');
    } catch (e) {
        next(e);
    }
});

router.post('/content/:id/delete', requireCreator, async (req, res, next) => {
    try {
        await db.query('DELETE FROM media_content WHERE id = $1 AND creator_id = $2', [Number.parseInt(req.params.id, 10) || 0, req.session.viewer.creatorId]);
        req.session.flash = { kind: 'ok', text: 'Removed.' };
        res.redirect('/me#content');
    } catch (e) {
        next(e);
    }
});

module.exports = router;
module.exports.dashboardData = dashboardData;
