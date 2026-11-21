const fs = require('fs');
const path = require('path');
const db = require('./db');

const REAPPLY_DAYS = Number(process.env.APPLY_REAPPLY_DAYS || 14);
const SETTINGS_FILE = path.join(process.env.UPLOAD_DIR || path.join(__dirname, '..', 'uploads'), 'settings', 'applications.json');

function readSettings() {
    try {
        return JSON.parse(fs.readFileSync(SETTINGS_FILE, 'utf8'));
    } catch (e) {
        return {};
    }
}

function status(now = Date.now()) {
    const s = readSettings();
    const openAt = s.openAt ? Date.parse(s.openAt) : NaN;
    const closeAt = s.closeAt ? Date.parse(s.closeAt) : NaN;
    if (!Number.isNaN(openAt)) {
        if (now < openAt) {
            return { state: 'scheduled', openAt, closeAt: Number.isNaN(closeAt) ? null : closeAt };
        }
        if (Number.isNaN(closeAt) || now < closeAt) {
            return { state: 'open', openAt, closeAt: Number.isNaN(closeAt) ? null : closeAt };
        }
        return { state: 'closed', openAt, closeAt };
    }
    return { state: s.open === false ? 'closed' : 'open', openAt: null, closeAt: null };
}

function isOpen() {
    return status().state === 'open';
}

function setOpen(open, by) {
    fs.mkdirSync(path.dirname(SETTINGS_FILE), { recursive: true });
    const tmp = SETTINGS_FILE + '.tmp';
    fs.writeFileSync(tmp, JSON.stringify({ open: Boolean(open), by, at: new Date().toISOString() }));
    fs.renameSync(tmp, SETTINGS_FILE);
}

function easternTime(ms) {
    return new Date(ms).toLocaleString('en-US', {
        timeZone: 'America/New_York', weekday: 'long', month: 'long', day: 'numeric', hour: 'numeric', minute: '2-digit', timeZoneName: 'short',
    });
}

const RECOMMENDED_PLAYTIME_DAYS = 1;

const QUESTIONS = [
    { id: 'age', label: 'How old are you?' },
    { id: 'timezone', label: 'What is your timezone and region?' },
    { id: 'weekly_hours', label: 'How many hours can you dedicate to in-game moderation weekly?' },
    { id: 'experience', label: 'Do you have previous experience with server staffing? (please include server name, player count, and highest rank)' },
    { id: 'recording', label: 'Do you have the ability to clip/record audio and video? If so, name the software you use.' },
    { id: 'why', label: 'Why are you interested in staffing on GateMC?' },
    { id: 'community_interaction', label: 'How do you currently interact with the community?' },
    { id: 'availability', label: 'What does your general weekly availability look like? Which days and for how long could you dedicate time to help Guide new players?' },
    { id: 'accomplish', label: 'What would you like to accomplish as a Guide?' },
].map((q) => ({ ...q, max: 2000 }));

async function playtimeSeconds(uuid) {
    const { rows } = await db.query('SELECT playtime_ms FROM pvp_players WHERE uuid = $1', [uuid]);
    return rows[0] ? Math.floor(Number(rows[0].playtime_ms) / 1000) : 0;
}

async function latest(discordId) {
    const { rows } = await db.query(
        'SELECT id, status, created_at, reviewed_at FROM bot_staff_applications WHERE discord_id = $1 ORDER BY id DESC LIMIT 1',
        [discordId],
    );
    return rows[0] || null;
}

async function history(discordId) {
    const { rows } = await db.query(
        'SELECT id, status, created_at, reviewed_at, review_note FROM bot_staff_applications WHERE discord_id = $1 ORDER BY id DESC',
        [discordId],
    );
    return rows;
}

async function withdraw(id, discordId) {
    const { rowCount } = await db.query(
        "UPDATE bot_staff_applications SET status = 'withdrawn' WHERE id = $1 AND discord_id = $2 AND status = 'pending'",
        [id, discordId],
    );
    return rowCount > 0;
}

async function linkFor(discordId) {
    const { rows } = await db.query('SELECT uuid, player_name FROM pvp_discord_links WHERE discord_id = $1', [discordId]);
    return rows[0] || null;
}

async function blocker(discordId) {
    const { rows } = await db.query(
        "SELECT status, reviewed_at FROM bot_staff_applications WHERE discord_id = $1 AND (status = 'pending' OR (status = 'denied' AND reviewed_at > now() - make_interval(days => $2::int))) ORDER BY id DESC LIMIT 1",
        [discordId, REAPPLY_DAYS],
    );
    const row = rows[0];
    if (!row) {
        return null;
    }
    if (row.status === 'pending') {
        return { kind: 'pending' };
    }
    return { kind: 'cooldown', until: new Date(new Date(row.reviewed_at).getTime() + REAPPLY_DAYS * 86400000) };
}

function validate(body) {
    const answers = [];
    const values = {};
    for (const q of QUESTIONS) {
        const value = String(body[q.id] || '').trim();
        values[q.id] = value;
        if (!value) {
            if (q.optional) {
                continue;
            }
            return { error: `Please answer: "${q.label}"`, values };
        }
        if (q.type === 'select' && !q.options.includes(value)) {
            return { error: `Please pick an option for: "${q.label}"`, values };
        }
        if (q.type === 'number') {
            const n = Number(value);
            if (!Number.isInteger(n) || n < q.min || n > q.max) {
                return { error: 'Please enter a real age.', values };
            }
        }
        if (q.min && q.type !== 'number' && value.length < q.min) {
            return { error: `Please give a bit more detail for: "${q.label}"`, values };
        }
        if (q.max && q.type !== 'number' && value.length > q.max) {
            return { error: `Your answer is too long for: "${q.label}"`, values };
        }
        answers.push({ id: q.id, label: q.label, answer: value });
    }
    return { answers, values };
}

async function insert(applicant, link, answers) {
    try {
        const { rows } = await db.query(
            'INSERT INTO bot_staff_applications (discord_id, discord_name, player_uuid, player_name, answers) VALUES ($1, $2, $3, $4, $5) RETURNING id',
            [applicant.id, applicant.username, link.uuid, link.player_name, JSON.stringify(answers)],
        );
        return rows[0].id;
    } catch (e) {
        if (e.code === '23505') {
            return null;
        }
        throw e;
    }
}

module.exports = {
    QUESTIONS, REAPPLY_DAYS, RECOMMENDED_PLAYTIME_DAYS, status, isOpen, setOpen, easternTime,
    linkFor, blocker, validate, insert, playtimeSeconds, latest, history, withdraw,
};
