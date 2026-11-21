// mentionRoleIds lets a specific call (e.g. a new IPBAN appeal) actually ping a role; everything
// else stays silent by default (allowed_mentions.parse: []) so a player-supplied appeal message
// can never trigger an @everyone/@here or an accidental role ping.
async function postWebhook(content, { mentionRoleIds = [] } = {}) {
    const url = process.env.DISCORD_WEBHOOK_URL;
    if (!url) {
        return;
    }
    try {
        await fetch(url, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                content,
                allowed_mentions: { parse: [], roles: mentionRoleIds },
            }),
        });
    } catch (e) {
        console.error('Discord webhook post failed:', e.message);
    }
}

module.exports = { postWebhook };
