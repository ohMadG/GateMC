const db = require('./db');
const v = require('./validate');
const notify = require('./notify');

const INACTIVE_DAYS = () => Math.max(7, Number(process.env.INACTIVE_DAYS || 30));
const WARN_DAYS = 7;

const DENY_REASONS = [
    'Your audience is a bit small for the program right now. Keep growing and apply again later.',
    "We couldn't find or verify your channel from the link you gave.",
    "We're looking for more regular uploads or streams before we partner.",
    "Your content isn't a good fit for GateMC right now.",
    "Your application didn't have enough detail for us to review.",
    'Your account has recent punishments or rule breaks on GateMC.',
    "We've reached the number of partners we can support right now.",
];

const base = () => process.env.MEDIA_BASE_DOMAIN || 'gatemc.org';

function userError(message) {
    return Object.assign(new Error(message), { user: true });
}

async function checkTerms(client, terms, creatorId) {
    if (terms.host) {
        const h = v.host(terms.host, base());
        if (h.error) {
            throw userError(h.error);
        }
        terms.host = h.host;
        const { rows } = await client.query('SELECT creator_id FROM media_hosts WHERE host = $1', [h.host]);
        if (rows[0] && rows[0].creator_id !== creatorId) {
            throw userError(`${h.host} already belongs to another creator.`);
        }
    }
    if (terms.code) {
        const c = v.code(terms.code);
        if (c.error) {
            throw userError(c.error);
        }
        terms.code = c.code;
        const { rows } = await client.query('SELECT name FROM media_creators WHERE lower(code) = lower($1) AND id <> $2', [c.code, creatorId || 0]);
        if (rows[0]) {
            throw userError(`The code ${c.code} already belongs to ${rows[0].name}.`);
        }
    }
    return terms;
}

async function finalize(client, a, percent, by) {
    const existing = await client.query('SELECT id, cut_percent FROM media_creators WHERE discord_id = $1 FOR UPDATE', [a.discord_id]);
    let creatorId = existing.rows[0] ? existing.rows[0].id : null;
    const terms = await checkTerms(client, { host: a.offer_host, code: a.offer_code }, creatorId);
    if (creatorId) {
        await client.query(
            `UPDATE media_creators SET name = $2, discord_name = $3, minecraft_name = coalesce($4, minecraft_name), channel_url = coalesce($5, channel_url),
             cut_percent = $6, window_days = $7, hold_days = $8, code = coalesce($9, code), active = true, activity_reset_at = now(),
             inactivity_warned_at = NULL, removed_at = NULL, removed_reason = NULL, updated_at = now() WHERE id = $1`,
            [creatorId, a.offer_name || a.discord_name, a.discord_name, a.minecraft_name, a.channel_url, percent,
                a.offer_window_days === null ? 60 : a.offer_window_days, a.offer_hold_days === null ? 30 : a.offer_hold_days, terms.code],
        );
        await client.query('INSERT INTO media_rate_changes (creator_id, old_percent, new_percent, changed_by, reason) VALUES ($1, $2, $3, $4, $5)',
            [creatorId, existing.rows[0].cut_percent, percent, by, `Application #${a.id}`]);
    } else {
        const { rows } = await client.query(
            `INSERT INTO media_creators (name, discord_id, discord_name, minecraft_name, channel_url, cut_percent, window_days, hold_days, code)
             VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9) RETURNING id`,
            [a.offer_name || a.discord_name, a.discord_id, a.discord_name, a.minecraft_name, a.channel_url, percent,
                a.offer_window_days === null ? 60 : a.offer_window_days, a.offer_hold_days === null ? 30 : a.offer_hold_days, terms.code],
        );
        creatorId = rows[0].id;
        await client.query('INSERT INTO media_rate_changes (creator_id, old_percent, new_percent, changed_by, reason) VALUES ($1, NULL, $2, $3, $4)',
            [creatorId, percent, by, `Application #${a.id}`]);
    }
    if (terms.host) {
        await client.query('INSERT INTO media_hosts (host, creator_id) VALUES ($1, $2) ON CONFLICT (host) DO NOTHING', [terms.host, creatorId]);
    }
    await client.query(
        "UPDATE media_applications SET status = 'accepted', granted_percent = $2, creator_id = $3, responded_at = now(), reviewed_at = coalesce(reviewed_at, now()) WHERE id = $1",
        [a.id, percent, creatorId],
    );
    return { creatorId, host: terms.host, code: terms.code };
}

function offerText(a, percent, final) {
    const lines = [
        `**Cut:** ${percent}% of what your players spend${a.offer_window_days ? ` during their first ${a.offer_window_days} days` : ' for as long as they play'}`,
        a.offer_host ? `**Your server address:** ${a.offer_host}` : null,
        a.offer_code ? `**Your creator code:** ${a.offer_code}` : null,
    ].filter(Boolean);
    return lines.join('\n') + (a.offer_note ? `\n> ${a.offer_note}` : '') +
        `\n\n${final ? 'This is our final offer. You can accept or decline it' : 'You can accept, decline, or counter with a different rate once'}: ${notify.portal('/apply')}`;
}

function welcomeText(c) {
    const days = INACTIVE_DAYS();
    return [
        `🎉 **Welcome to the GateMC Media program, ${c.name}!** Your partnership is now active at **${c.percent}%**.`,
        '',
        '**How it works**',
        c.host ? `• Share **${c.host}** as the server address. New players who join through it are linked to you.` : '• Your personal server address will appear on your dashboard once it is set up.',
        c.code ? `• Tell viewers to use creator code **${c.code}** at checkout, or share ${'https://store.' + base()}/?code=${c.code}. That credits the purchase to you even if they joined another way.` : null,
        `• You earn ${c.percent}% of what your players spend${c.window ? ` in their first ${c.window} days` : ''}. Earnings stay pending for ${c.hold} days in case of refunds, then they are confirmed.`,
        '• Confirmed earnings are paid out by the GateMC team. You get a DM for every sale and every payout.',
        `• Log each video or stream on your dashboard. **If you have no content logged for ${days} days your partnership is removed.** You get a reminder ${WARN_DAYS} days before.`,
        '• Once you have results, you can request a higher cut from your dashboard.',
        '',
        `Your dashboard: ${notify.portal('/me')}`,
    ].filter((l) => l !== null).join('\n');
}

async function lastActive(creatorId) {
    const { rows } = await db.query(
        `SELECT greatest(c.activity_reset_at, (SELECT max(posted_on)::timestamptz FROM media_content WHERE creator_id = c.id)) AS last_active
         FROM media_creators c WHERE c.id = $1`, [creatorId]);
    return rows[0] ? rows[0].last_active : null;
}

async function checkActivity() {
    const days = INACTIVE_DAYS();
    const { rows } = await db.query(`
        SELECT c.id, c.name, c.discord_id, c.inactivity_warned_at,
            greatest(c.activity_reset_at, (SELECT max(posted_on)::timestamptz FROM media_content WHERE creator_id = c.id)) AS last_active
        FROM media_creators c WHERE c.active`);
    const now = Date.now();
    for (const c of rows) {
        const last = new Date(c.last_active).getTime();
        const idle = (now - last) / 86400000;
        if (idle >= days) {
            const { rowCount } = await db.query(
                "UPDATE media_creators SET active = false, removed_at = now(), removed_reason = $2, updated_at = now() WHERE id = $1 AND active",
                [c.id, `No content logged for ${days} days`]);
            if (rowCount) {
                console.log(`Media creator ${c.name} removed for inactivity`);
                await notify.dm(c.discord_id, `Your GateMC media partnership has been removed because no content was logged for ${days} days. Purchases no longer count toward your cut. Earnings you already made are still paid out. Reach out to the team if you want to come back.\n${notify.portal('/me')}`);
                await notify.admins(`⏸️ **${c.name}** was removed from the media program (no content for ${days} days).\n${notify.portal('/admin/creators/' + c.id)}`);
            }
        } else if (idle >= days - WARN_DAYS && (!c.inactivity_warned_at || new Date(c.inactivity_warned_at).getTime() < last)) {
            await db.query('UPDATE media_creators SET inactivity_warned_at = now() WHERE id = $1', [c.id]);
            const left = Math.max(1, Math.ceil(days - idle));
            await notify.dm(c.discord_id, `⏰ Heads up: you haven't logged any GateMC content in ${Math.floor(idle)} days. Log a video or stream within **${left} day${left === 1 ? '' : 's'}** or your media partnership will be removed.\n${notify.portal('/me')}`);
        }
    }
}

function startActivity() {
    const run = () => checkActivity().catch((e) => console.error('Activity check failed:', e.message));
    setTimeout(run, 30000);
    setInterval(run, 60 * 60 * 1000);
}

module.exports = { DENY_REASONS, INACTIVE_DAYS, WARN_DAYS, checkTerms, finalize, offerText, welcomeText, lastActive, checkActivity, startActivity, userError };
