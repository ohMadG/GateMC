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
    const res = await fetch(`${API_BASE}/users/@me`, {
        headers: { Authorization: `Bearer ${accessToken}` },
    });
    if (!res.ok) {
        throw new Error('Discord identity lookup failed: ' + res.status);
    }
    return res.json();
}

/** The GateMC guild's roles for this Discord user, or null if they aren't a member. */
async function getMemberRoles(discordUserId) {
    const guildId = process.env.DISCORD_GUILD_ID;
    const res = await fetch(`${API_BASE}/guilds/${guildId}/members/${discordUserId}`, {
        headers: { Authorization: `Bot ${process.env.DISCORD_BOT_TOKEN}` },
    });
    if (res.status === 404) {
        return null;
    }
    if (!res.ok) {
        throw new Error('Discord guild member lookup failed: ' + res.status);
    }
    const member = await res.json();
    return Array.isArray(member.roles) ? member.roles : [];
}

function parseRoleIds(envValue) {
    return (envValue || '').split(',').map((s) => s.trim()).filter(Boolean);
}

function hasAnyRole(memberRoles, roleIds) {
    return roleIds.length > 0 && memberRoles.some((r) => roleIds.includes(r));
}

/**
 * Looks up staff/admin standing in one member-roles fetch. isStaff is required to use /staff at
 * all; isAdmin additionally gates approving/reducing severe punishments (IP bans). Each side is
 * a comma-separated list of role IDs (ranks don't stack on this server, so "Sr Mod or higher"
 * means checking membership in any of several distinct roles, not one). A blank list means no
 * one gets that tier yet, rather than everyone.
 */
async function getStanding(discordUserId) {
    const roles = await getMemberRoles(discordUserId);
    if (roles === null) {
        return { isStaff: false, isAdmin: false, isSenior: false };
    }
    const staffRoleIds = parseRoleIds(process.env.DISCORD_STAFF_ROLE_IDS);
    const adminRoleIds = parseRoleIds(process.env.DISCORD_ADMIN_ROLE_IDS);
    const senior = hasAnyRole(roles, parseRoleIds(process.env.DISCORD_SENIOR_ROLE_IDS));
    return {
        isStaff: hasAnyRole(roles, staffRoleIds) || senior,
        isAdmin: hasAnyRole(roles, adminRoleIds),
        isSenior: senior,
    };
}

async function isMember(discordUserId) {
    return (await getMemberRoles(discordUserId)) !== null;
}

module.exports = { buildAuthUrl, exchangeCode, getIdentity, getStanding, parseRoleIds, isMember };
