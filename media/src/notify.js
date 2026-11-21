const API = 'https://discord.com/api/v10';

function ids(value) {
    return String(value || '').split(',').map((s) => s.trim()).filter(Boolean);
}

async function call(path, body) {
    const res = await fetch(API + path, {
        method: 'POST',
        headers: { Authorization: `Bot ${process.env.DISCORD_BOT_TOKEN}`, 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
    });
    if (!res.ok) {
        throw new Error(`${path} ${res.status}`);
    }
    return res.json();
}

async function dm(discordId, content) {
    if (!process.env.DISCORD_BOT_TOKEN || !discordId) {
        return false;
    }
    try {
        const channel = await call('/users/@me/channels', { recipient_id: String(discordId) });
        await call(`/channels/${channel.id}/messages`, { content: String(content).slice(0, 1900), allowed_mentions: { parse: [] } });
        return true;
    } catch (e) {
        console.error(`Discord DM to ${discordId} failed:`, e.message);
        return false;
    }
}

async function admins(content) {
    await Promise.all(ids(process.env.ADMIN_DISCORD_IDS).map((id) => dm(id, content)));
}

function portal(path) {
    return `https://media.${process.env.MEDIA_BASE_DOMAIN || 'gatemc.org'}${path}`;
}

function usd(n) {
    return '$' + Number(n || 0).toFixed(2);
}

module.exports = { dm, admins, portal, usd };
