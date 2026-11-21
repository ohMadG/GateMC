const db = require('./db');

const SUMMARY = `
    WITH refs AS (
        SELECT h.creator_id, j.uuid, j.first_seen, GREATEST(j.last_seen, COALESCE(p.last_seen, 0)) AS last_seen
        FROM pvp_join_hosts j JOIN media_hosts h ON h.host = j.first_host
        LEFT JOIN pvp_players p ON p.uuid = j.uuid
        WHERE j.new_player
    ),
    earn AS (
        SELECT e.creator_id, e.player_uuid, e.amount_usd, e.earning_usd, p.status, p.paid_at, c.hold_days
        FROM media_earnings e
        JOIN media_payments p ON p.id = e.payment_id
        JOIN media_creators c ON c.id = e.creator_id
    )
    SELECT c.id, c.name, c.discord_id, c.discord_name, c.minecraft_name, c.channel_url, c.cut_percent, c.window_days, c.hold_days, c.active, c.created_at, c.code, c.removed_at, c.removed_reason,
        COALESCE((SELECT array_agg(host ORDER BY host) FROM media_hosts WHERE creator_id = c.id), '{}') AS hosts,
        (SELECT count(*) FROM refs r WHERE r.creator_id = c.id)::int AS referred,
        (SELECT count(*) FROM refs r WHERE r.creator_id = c.id AND r.last_seen > (extract(epoch FROM now()) * 1000 - 7 * 86400000))::int AS active_week,
        (SELECT count(*) FROM refs r WHERE r.creator_id = c.id AND r.first_seen <= (extract(epoch FROM now()) * 1000 - 86400000))::int AS d1_n,
        (SELECT count(*) FROM refs r WHERE r.creator_id = c.id AND r.first_seen <= (extract(epoch FROM now()) * 1000 - 86400000) AND r.last_seen >= r.first_seen + 86400000)::int AS d1,
        (SELECT count(*) FROM refs r WHERE r.creator_id = c.id AND r.first_seen <= (extract(epoch FROM now()) * 1000 - 7 * 86400000))::int AS d7_n,
        (SELECT count(*) FROM refs r WHERE r.creator_id = c.id AND r.first_seen <= (extract(epoch FROM now()) * 1000 - 7 * 86400000) AND r.last_seen >= r.first_seen + 7 * 86400000)::int AS d7,
        (SELECT count(DISTINCT player_uuid) FROM earn WHERE creator_id = c.id AND status = 'Complete')::int AS buyers,
        (SELECT count(*) FROM earn WHERE creator_id = c.id AND status = 'Complete')::int AS purchases,
        COALESCE((SELECT sum(amount_usd) FROM earn WHERE creator_id = c.id AND status = 'Complete'), 0)::float AS revenue,
        COALESCE((SELECT sum(earning_usd) FROM earn WHERE creator_id = c.id AND status = 'Complete'), 0)::float AS earned,
        COALESCE((SELECT sum(earning_usd) FROM earn WHERE creator_id = c.id AND status = 'Complete' AND paid_at > now() - hold_days * interval '1 day'), 0)::float AS pending,
        COALESCE((SELECT sum(earning_usd) FROM earn WHERE creator_id = c.id AND status <> 'Complete'), 0)::float AS reversed,
        COALESCE((SELECT sum(amount_usd) FROM media_payouts WHERE creator_id = c.id), 0)::float AS paid
    FROM media_creators c`;

function finish(row) {
    const available = Math.max(0, row.earned - row.pending);
    return Object.assign(row, { available, owed: Math.round((available - row.paid) * 100) / 100, cut_percent: Number(row.cut_percent) });
}

async function creators() {
    const { rows } = await db.query(`${SUMMARY} ORDER BY c.active DESC, c.name`);
    return rows.map(finish);
}

async function creator(id) {
    const { rows } = await db.query(`${SUMMARY} WHERE c.id = $1`, [id]);
    return rows[0] ? finish(rows[0]) : null;
}

function totals(list) {
    const keys = ['referred', 'active_week', 'd1_n', 'd1', 'd7_n', 'd7', 'buyers', 'purchases', 'revenue', 'earned', 'pending', 'available', 'paid', 'owed', 'reversed'];
    const t = Object.fromEntries(keys.map((k) => [k, 0]));
    for (const c of list) {
        for (const k of keys) {
            t[k] += Number(c[k] || 0);
        }
    }
    t.creators = list.length;
    t.activeCreators = list.filter((c) => c.active).length;
    return t;
}

async function monthly(creatorId) {
    const params = [];
    let where = "p.status = 'Complete'";
    if (creatorId) {
        params.push(creatorId);
        where += ' AND e.creator_id = $1';
    }
    const { rows } = await db.query(`
        SELECT to_char(date_trunc('month', p.paid_at AT TIME ZONE 'America/New_York'), 'YYYY-MM') AS month,
            count(*)::int AS purchases, count(DISTINCT e.player_uuid)::int AS buyers,
            sum(e.amount_usd)::float AS revenue, sum(e.earning_usd)::float AS earned
        FROM media_earnings e JOIN media_payments p ON p.id = e.payment_id
        WHERE ${where}
        GROUP BY 1 ORDER BY 1 DESC LIMIT 24`, params);
    const joins = await db.query(`
        SELECT to_char(date_trunc('month', to_timestamp(j.first_seen / 1000.0) AT TIME ZONE 'America/New_York'), 'YYYY-MM') AS month, count(*)::int AS joins
        FROM pvp_join_hosts j JOIN media_hosts h ON h.host = j.first_host
        WHERE j.new_player ${creatorId ? 'AND h.creator_id = $1' : ''}
        GROUP BY 1`, params);
    const byMonth = new Map(rows.map((r) => [r.month, Object.assign(r, { joins: 0 })]));
    for (const j of joins.rows) {
        if (!byMonth.has(j.month)) {
            byMonth.set(j.month, { month: j.month, purchases: 0, buyers: 0, revenue: 0, earned: 0, joins: 0 });
        }
        byMonth.get(j.month).joins = j.joins;
    }
    return [...byMonth.values()].sort((a, b) => b.month.localeCompare(a.month));
}

async function players({ creatorId, q, limit } = {}) {
    const params = [];
    const where = ['j.new_player'];
    if (creatorId) {
        params.push(creatorId);
        where.push(`h.creator_id = $${params.length}`);
    }
    if (q) {
        params.push(`%${q.toLowerCase()}%`);
        where.push(`lower(j.player_name) LIKE $${params.length}`);
    }
    params.push(Math.min(Number(limit) || 500, 2000));
    const { rows } = await db.query(`
        SELECT j.uuid, j.player_name, j.first_host, j.first_seen, GREATEST(j.last_seen, COALESCE(pl.last_seen, 0)) AS last_seen,
            COALESCE(pl.playtime_ms, 0)::bigint AS playtime_ms, c.id AS creator_id, c.name AS creator_name,
            COALESCE((SELECT sum(p.amount_usd) FROM media_payments p WHERE p.player_uuid = j.uuid AND p.status = 'Complete'), 0)::float AS spent,
            COALESCE((SELECT sum(e.amount_usd) FROM media_earnings e JOIN media_payments p ON p.id = e.payment_id WHERE e.player_uuid = j.uuid AND p.status = 'Complete'), 0)::float AS counted,
            COALESCE((SELECT sum(e.earning_usd) FROM media_earnings e JOIN media_payments p ON p.id = e.payment_id WHERE e.player_uuid = j.uuid AND p.status = 'Complete'), 0)::float AS earned,
            (SELECT count(*) FROM media_payments p WHERE p.player_uuid = j.uuid AND p.status = 'Complete')::int AS purchases
        FROM pvp_join_hosts j
        JOIN media_hosts h ON h.host = j.first_host
        JOIN media_creators c ON c.id = h.creator_id
        LEFT JOIN pvp_players pl ON pl.uuid = j.uuid
        WHERE ${where.join(' AND ')}
        ORDER BY spent DESC, j.first_seen DESC
        LIMIT $${params.length}`, params);
    return rows;
}

async function purchases({ creatorId, limit } = {}) {
    const params = [];
    let where = '';
    if (creatorId) {
        params.push(creatorId);
        where = 'WHERE e.creator_id = $1';
    }
    params.push(Math.min(Number(limit) || 100, 1000));
    const { rows } = await db.query(`
        SELECT p.id, p.player_name, p.player_uuid, p.packages, p.amount, p.currency, p.status, p.paid_at,
            e.amount_usd::float, e.cut_percent::float, e.earning_usd::float, e.source, c.name AS creator_name, c.id AS creator_id, c.hold_days
        FROM media_earnings e JOIN media_payments p ON p.id = e.payment_id JOIN media_creators c ON c.id = e.creator_id
        ${where}
        ORDER BY p.paid_at DESC LIMIT $${params.length}`, params);
    return rows;
}

async function payouts(creatorId) {
    const params = [];
    let where = '';
    if (creatorId) {
        params.push(creatorId);
        where = 'WHERE o.creator_id = $1';
    }
    const { rows } = await db.query(`
        SELECT o.*, o.amount_usd::float AS amount, c.name AS creator_name
        FROM media_payouts o JOIN media_creators c ON c.id = o.creator_id ${where}
        ORDER BY o.paid_at DESC LIMIT 200`, params);
    return rows;
}

async function rateHistory(creatorId) {
    const { rows } = await db.query('SELECT *, old_percent::float AS old_p, new_percent::float AS new_p FROM media_rate_changes WHERE creator_id = $1 ORDER BY changed_at DESC', [creatorId]);
    return rows;
}

async function content(creatorId) {
    const { rows } = await db.query('SELECT * FROM media_content WHERE creator_id = $1 ORDER BY posted_on DESC, id DESC LIMIT 100', [creatorId]);
    return rows;
}

const DAY = 86400000;

const RETENTION = `
    SELECT count(*)::int AS players,
        count(*) FILTER (WHERE first_seen <= $1::bigint - ${DAY})::int AS d1_n,
        count(*) FILTER (WHERE first_seen <= $1::bigint - ${DAY} AND last_seen >= first_seen + ${DAY})::int AS d1,
        count(*) FILTER (WHERE first_seen <= $1::bigint - ${7 * DAY})::int AS d7_n,
        count(*) FILTER (WHERE first_seen <= $1::bigint - ${7 * DAY} AND last_seen >= first_seen + ${7 * DAY})::int AS d7,
        count(*) FILTER (WHERE first_seen <= $1::bigint - ${30 * DAY})::int AS d30_n,
        count(*) FILTER (WHERE first_seen <= $1::bigint - ${30 * DAY} AND last_seen >= first_seen + ${30 * DAY})::int AS d30,
        count(*) FILTER (WHERE playtime_ms >= 3600000)::int AS hour,
        count(*) FILTER (WHERE last_seen > $1::bigint - ${7 * DAY})::int AS week,
        COALESCE(percentile_cont(0.5) WITHIN GROUP (ORDER BY playtime_ms), 0)::float AS median_ms,
        min(first_seen) AS first
    FROM (
        SELECT j.first_seen, GREATEST(j.last_seen, COALESCE(p.last_seen, 0)) AS last_seen, COALESCE(p.playtime_ms, 0) AS playtime_ms
        FROM pvp_join_hosts j
        LEFT JOIN media_hosts h ON h.host = j.first_host
        LEFT JOIN pvp_players p ON p.uuid = j.uuid
        WHERE j.new_player AND %WHERE%
    ) t`;

async function retentionFor(where, params) {
    const { rows } = await db.query(RETENTION.replace('%WHERE%', where), [Date.now(), ...params]);
    return rows[0];
}

async function retention(creatorId) {
    const [mine, organic, since] = await Promise.all([
        creatorId ? retentionFor('h.creator_id = $2', [creatorId]) : retentionFor('h.creator_id IS NOT NULL', []),
        retentionFor('h.creator_id IS NULL', []),
        db.query('SELECT min(first_seen) AS at FROM pvp_join_hosts'),
    ]);
    return { mine, organic, since: since.rows[0].at ? Number(since.rows[0].at) : null };
}

async function activity(c) {
    const program = require('./program');
    const last = await program.lastActive(c.id);
    const days = program.INACTIVE_DAYS();
    const idle = last ? Math.floor((Date.now() - new Date(last).getTime()) / 86400000) : 0;
    const deadline = last ? new Date(new Date(last).getTime() + days * 86400000) : null;
    return { last, idle, days, deadline, left: Math.max(0, days - idle), warn: idle >= days - program.WARN_DAYS };
}

module.exports = { creators, creator, totals, monthly, players, purchases, payouts, rateHistory, content, activity, retention };
