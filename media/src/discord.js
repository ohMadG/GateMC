const AUTHORIZE_URL = 'https://discord.com/api/oauth2/authorize';
const TOKEN_URL = 'https://discord.com/api/oauth2/token';
const API_BASE = 'https://discord.com/api';

function buildAuthUrl(redirectUri, state) {
    const params = new URLSearchParams({
        client_id: process.env.DISCORD_CLIENT_ID,
        redirect_uri: redirectUri,
        response_type: 'code',
        scope: 'identify',
        state,
    });
    return `${AUTHORIZE_URL}?${params.toString()}`;
}

async function exchangeCode(code, redirectUri) {
    const res = await fetch(TOKEN_URL, {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: new URLSearchParams({
            client_id: process.env.DISCORD_CLIENT_ID,
            client_secret: process.env.DISCORD_CLIENT_SECRET,
            grant_type: 'authorization_code',
            code,
            redirect_uri: redirectUri,
        }),
    });
    if (!res.ok) {
        throw new Error('Discord token exchange failed: ' + res.status);
    }
    return res.json();
}

async function getIdentity(accessToken) {
    const res = await fetch(`${API_BASE}/users/@me`, { headers: { Authorization: `Bearer ${accessToken}` } });
    if (!res.ok) {
        throw new Error('Discord identity lookup failed: ' + res.status);
    }
    return res.json();
}

function avatarUrl(identity) {
    if (identity.avatar) {
        return `https://cdn.discordapp.com/avatars/${identity.id}/${identity.avatar}.png?size=64`;
    }
    return `https://cdn.discordapp.com/embed/avatars/${(BigInt(identity.id) >> 22n) % 6n}.png`;
}

module.exports = { buildAuthUrl, exchangeCode, getIdentity, avatarUrl };
