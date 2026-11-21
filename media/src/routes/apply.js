const express = require('express');
const db = require('../db');
const v = require('../validate');
const links = require('../links');
const notify = require('../notify');
const program = require('../program');
const { requireSignIn, refresh } = require('../auth');

const router = express.Router();
router.use(requireSignIn);

const PLATFORMS = ['YouTube', 'Twitch', 'TikTok', 'Kick', 'Instagram', 'Other'];

async function linkedAccount(discordId) {
    try {
        const { rows } = await db.query('SELECT uuid, player_name FROM pvp_discord_links WHERE discord_id = $1', [discordId]);
        return rows[0] || null;
    } catch (e) {
        return null;
    }
}

async function state(viewer) {
    const { rows } = await db.query("SELECT * FROM media_applications WHERE discord_id = $1 AND kind = 'creator' ORDER BY created_at DESC LIMIT 10", [viewer.discordId]);
    return {
        history: rows,
        pending: rows.find((r) => ['pending', 'offered', 'countered'].includes(r.status)) || null,
        link: await linkedAccount(viewer.discordId),
    };
}

router.get('/', async (req, res, next) => {
    try {
        if (req.session.viewer.creatorId && !req.session.viewer.isAdmin) {
            return res.redirect('/me');
        }
        const s = await state(req.session.viewer);
        const flash = req.session.flash || null;
        req.session.flash = null;
        res.render('apply', Object.assign(s, { title: 'Apply', active: 'apply', platforms: PLATFORMS, values: {}, error: null, thanks: req.query.sent === '1', flash, inactiveDays: program.INACTIVE_DAYS() }));
    } catch (e) {
        next(e);
    }
});

router.post('/', async (req, res, next) => {
    try {
        const viewer = req.session.viewer;
        const s = await state(viewer);
        const values = req.body;
        const fail = (error) => res.status(400).render('apply', Object.assign(s, { title: 'Apply', active: 'apply', platforms: PLATFORMS, values, error, thanks: false, flash: null, inactiveDays: program.INACTIVE_DAYS() }));
        if (s.pending) {
            return res.redirect('/apply');
        }
        const platform = PLATFORMS.includes(values.platform) ? values.platform : null;
        const checked = platform ? links.channel(values.channel_url, platform) : { error: null };
        const channel = checked.url || '';
        const minecraft = s.link ? s.link.player_name : v.text(values.minecraft_name, 32);
        const content = v.longText(values.content, 1500);
        const why = v.longText(values.why, 1500);
        if (!platform) {
            return fail('Pick the platform you mainly create on.');
        }
        if (!channel) {
            return fail(checked.error || 'Add a link to your channel.');
        }
        if (!/^\.?[A-Za-z0-9_]{3,16}$/.test(minecraft || '')) {
            return fail('Enter your Minecraft username.');
        }
        if (content.length < 20 || why.length < 20) {
            return fail('Tell us a bit more about your content and why you want to partner (a sentence or two each).');
        }
        const issue = links.noLinks(values.followers, 'Followers') || links.noLinks(values.avg_viewers, 'Average views')
            || links.textIssue(values.schedule, 'Schedule') || links.textIssue(content, 'Your content') || links.textIssue(why, 'Why you want to partner');
        if (issue) {
            console.log(`Application blocked for ${viewer.username}: ${issue}`);
            return fail(issue);
        }
        await db.query(
            `INSERT INTO media_applications (kind, discord_id, discord_name, minecraft_name, platform, channel_url, followers, avg_viewers, schedule, content, why)
             VALUES ('creator', $1, $2, $3, $4, $5, $6, $7, $8, $9, $10)`,
            [viewer.discordId, viewer.username, minecraft, platform, channel, v.text(values.followers, 60) || null,
                v.text(values.avg_viewers, 60) || null, v.text(values.schedule, 200) || null, content, why],
        );
        console.log(`Media application from ${viewer.username}`);
        notify.admins(`📝 **New creator application** from ${viewer.username} (${platform}, ${v.text(values.followers, 60) || '?'} followers)\n${channel}\n${notify.portal('/admin/applications')}`);
        res.redirect('/apply?sent=1');
    } catch (e) {
        next(e);
    }
});

router.post('/:id/withdraw', async (req, res, next) => {
    try {
        await db.query("UPDATE media_applications SET status = 'withdrawn', reviewed_at = now() WHERE id = $1 AND discord_id = $2 AND status = 'pending'",
            [Number.parseInt(req.params.id, 10) || 0, req.session.viewer.discordId]);
        res.redirect('/apply');
    } catch (e) {
        next(e);
    }
});

router.post('/:id/respond', async (req, res, next) => {
    const viewer = req.session.viewer;
    const id = Number.parseInt(req.params.id, 10) || 0;
    const action = String(req.body.action || '');
    const back = (kind, text) => {
        req.session.flash = { kind, text };
        res.redirect('/apply');
    };
    try {
        const { rows } = await db.query("SELECT * FROM media_applications WHERE id = $1 AND discord_id = $2 AND kind = 'creator'", [id, viewer.discordId]);
        const a = rows[0];
        if (!a || a.status !== 'offered') {
            return back('bad', 'That offer is no longer open.');
        }
        if (action === 'accept') {
            const percent = Number(a.offer_percent);
            const result = await tx((client) => program.finalize(client, a, percent, viewer.username));
            const c = (await db.query('SELECT name, window_days, hold_days FROM media_creators WHERE id = $1', [result.creatorId])).rows[0];
            await notify.dm(a.discord_id, program.welcomeText({ name: c.name, percent, host: result.host, code: result.code, window: c.window_days, hold: c.hold_days }));
            await notify.admins(`✅ **${a.discord_name}** accepted the media offer at ${percent}%.\n${notify.portal('/admin/creators/' + result.creatorId)}`);
            await refresh(viewer);
            req.session.flash = { kind: 'ok', text: `Welcome to the program! You're now a GateMC media partner at ${percent}%. We sent the details to your Discord DMs too.` };
            return res.redirect('/me');
        }
        if (action === 'decline') {
            await db.query("UPDATE media_applications SET status = 'declined', responded_at = now() WHERE id = $1 AND status = 'offered'", [a.id]);
            await notify.admins(`❌ **${a.discord_name}** declined the media offer of ${Number(a.offer_percent)}%.\n${notify.portal('/admin/applications/' + a.id)}`);
            return back('ok', 'You declined the offer. You can apply again any time.');
        }
        if (action === 'counter') {
            if (a.offer_final || a.counter_percent !== null) {
                return back('bad', 'You already used your counter offer. You can accept or decline this one.');
            }
            const percent = v.percent(req.body.counter_percent);
            if (percent === null || percent <= Number(a.offer_percent)) {
                return back('bad', `Your counter has to be higher than the ${Number(a.offer_percent)}% offered.`);
            }
            const note = v.longText(req.body.counter_note, 1000);
            const noteIssue = links.textIssue(note, 'Note');
            if (noteIssue) {
                return back('bad', noteIssue);
            }
            const { rowCount } = await db.query(
                "UPDATE media_applications SET status = 'countered', counter_percent = $2, counter_note = $3, countered_at = now() WHERE id = $1 AND status = 'offered' AND counter_percent IS NULL",
                [a.id, percent, note || null]);
            if (rowCount) {
                await notify.admins(`↔️ **${a.discord_name}** countered the media offer: ${Number(a.offer_percent)}% → **${percent}%**${note ? `\n> ${note}` : ''}\n${notify.portal('/admin/applications/' + a.id)}`);
            }
            return back('ok', `Counter offer of ${percent}% sent. We'll get back to you on Discord.`);
        }
        back('bad', 'Choose accept, decline or counter.');
    } catch (e) {
        if (e.user) {
            return back('bad', 'Something about this offer needs fixing on our side. The team has been told.');
        }
        next(e);
    }
});

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

module.exports = router;
