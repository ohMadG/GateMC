const PLATFORM_HOSTS = {
    YouTube: ['youtube.com', 'youtu.be'],
    TikTok: ['tiktok.com'],
    Instagram: ['instagram.com'],
    Twitch: ['twitch.tv'],
    Kick: ['kick.com'],
};

const PROFILE_PATHS = {
    YouTube: /^\/(@[\w.-]{2,}|channel\/UC[\w-]{10,}|c\/[\w.-]+|user\/[\w.-]+)\/?/i,
    TikTok: /^\/@[\w.]{2,}\/?/i,
    Instagram: /^\/(?!p\/|reel\/|reels\/|stories\/|explore\/|accounts\/)[\w.]{1,30}\/?$/i,
    Twitch: /^\/(?!videos\/|directory\/)[\w]{3,25}\/?$/i,
    Kick: /^\/[\w-]{3,25}\/?$/i,
};

const BRANDS = {
    youtube: 'youtube.com', tiktok: 'tiktok.com', instagram: 'instagram.com', twitch: 'twitch.tv',
    discord: 'discord.com', steamcommunity: 'steamcommunity.com', steampowered: 'steampowered.com', paypal: 'paypal.com',
    gatemc: 'gatemc.org', tebex: 'tebex.io',
};

const OFFICIAL = new Set(['youtube.com', 'youtu.be', 'tiktok.com', 'instagram.com', 'twitch.tv', 'kick.com', 'discord.com', 'discord.gg',
    'discordapp.com', 'steampowered.com', 'steamcommunity.com', 'paypal.com', 'gatemc.org', 'minecraft.net', 'tebex.io', 'tebex.store',
    'google.com', 'roblox.com', 'x.com', 'twitter.com', 'facebook.com', 'reddit.com', 'github.com', 'linktr.ee', 'medal.tv', 'streamlabs.com',
    'socialblade.com', 'twitchtracker.com', 'sullygnome.com', 'streamscharts.com', 'twitchmetrics.net', 'streamelements.com']);

const SHORTENERS = new Set(['bit.ly', 'tinyurl.com', 't.co', 'goo.gl', 'ow.ly', 'is.gd', 'buff.ly', 'cutt.ly', 'rb.gy', 'shorturl.at', 'tiny.cc',
    'rebrand.ly', 'bl.ink', 'v.gd', 'qr.ae', 'lnkd.in', 'shorte.st', 'adf.ly', 'grabify.link', 'iplogger.org', 'iplogger.com', 'yip.su',
    '2no.co', 'blasze.tk', 'ps3cfw.com', 'bmwforum.co', 'leancoding.co', 'spottyfly.com', 'stopify.co', 'freegiftcards.co']);

const SCAM_WORDS = /(free-?nitro|nitro-?(gift|free)|discord-?gift|gift-?discord|steam-?gift|free-?robux|claim-?(reward|prize)|giveaway-?claim|verify-?account|account-?verify|login-?verify)/i;

const HIDDEN = /[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F​-‏‪-‮⁠-⁤⁦-⁩﻿]/g;

function clean(value) {
    return String(value || '').normalize('NFC').replace(HIDDEN, '');
}

function rootOf(host) {
    const parts = host.split('.');
    return parts.length <= 2 ? host : parts.slice(-2).join('.');
}

function onDomain(host, domain) {
    return host === domain || host.endsWith('.' + domain);
}

function deconfuse(s) {
    return s.replace(/0/g, 'o').replace(/[1!|]/g, 'l').replace(/3/g, 'e').replace(/4/g, 'a').replace(/5/g, 's').replace(/7/g, 't')
        .replace(/8/g, 'b').replace(/vv/g, 'w').replace(/rn/g, 'm').replace(/[^a-z]/g, '');
}

function lookalike(host) {
    if ([...OFFICIAL].some((d) => onDomain(host, d))) {
        return null;
    }
    const flat = deconfuse(host.replace(/\.[a-z]+$/, ''));
    const loose = flat.replace(/l/g, 'i');
    for (const [brand, real] of Object.entries(BRANDS)) {
        if (brand.length < 5) {
            continue;
        }
        const b = brand.replace(/l/g, 'i');
        if (flat.includes(brand) || loose.includes(b)) {
            return real;
        }
    }
    return null;
}

function parse(raw) {
    const v = clean(raw).trim();
    if (!v) {
        return { empty: true };
    }
    if (/\s/.test(v)) {
        return { error: "Links can't contain spaces." };
    }
    if (/^(javascript|data|vbscript|file):/i.test(v)) {
        return { error: "That isn't a web link." };
    }
    let u;
    try {
        u = new URL(/^https?:\/\//i.test(v) ? v : 'https://' + v);
    } catch (e) {
        return { error: "That link doesn't look right." };
    }
    if (u.protocol !== 'https:' && u.protocol !== 'http:') {
        return { error: "That isn't a web link." };
    }
    u.protocol = 'https:';
    return { u, host: u.hostname.toLowerCase().replace(/\.+$/, '') };
}

function suspicion(u, host) {
    if (u.username || u.password) {
        return 'it hides the real address with an @';
    }
    if (u.port) {
        return 'it uses an unusual port';
    }
    if (/^\d{1,3}(\.\d{1,3}){3}$/.test(host) || host.includes(':') || host.startsWith('[')) {
        return 'it points to a bare IP address';
    }
    if (host.split('.').some((p) => p.startsWith('xn--'))) {
        return 'it uses look-alike characters in the address';
    }
    if (!host.includes('.')) {
        return "it isn't a full web address";
    }
    if (SHORTENERS.has(host) || SHORTENERS.has(rootOf(host))) {
        return 'it is a link shortener, which hides where it goes';
    }
    if (SCAM_WORDS.test(host) || SCAM_WORDS.test(u.pathname)) {
        return 'it looks like a gift or login scam';
    }
    const fake = lookalike(host);
    if (fake) {
        return `it looks like a fake ${fake} address`;
    }
    return null;
}

function channel(raw, platform) {
    const p = parse(raw);
    if (p.empty) {
        return { error: 'Add a link to your channel.' };
    }
    if (p.error) {
        return { error: p.error };
    }
    const allowed = PLATFORM_HOSTS[platform];
    if (allowed) {
        if (!allowed.some((d) => onDomain(p.host, d))) {
            const why = suspicion(p.u, p.host);
            return { error: why ? `That channel link was blocked because ${why}. Use your real ${platform} link.` : `That isn't a ${platform} link. Use the link to your ${platform} channel (for example ${allowed[0]}/...).` };
        }
        if (platform === 'YouTube' && onDomain(p.host, 'youtu.be')) {
            return { error: 'That is a video link. Use the link to your YouTube channel (youtube.com/@yourname).' };
        }
        if (PROFILE_PATHS[platform] && !PROFILE_PATHS[platform].test(p.u.pathname)) {
            return { error: `Use the link to your ${platform} profile or channel, not a single video or post.` };
        }
        p.u.hash = '';
        return { url: p.u.toString() };
    }
    const why = suspicion(p.u, p.host);
    if (why) {
        return { error: `That channel link was blocked because ${why}.` };
    }
    p.u.hash = '';
    return { url: p.u.toString() };
}

function content(raw) {
    const p = parse(raw);
    if (p.empty || p.error) {
        return { error: p.error || 'Add the link to your video or stream.' };
    }
    const ok = Object.values(PLATFORM_HOSTS).flat().some((d) => onDomain(p.host, d));
    if (!ok) {
        const why = suspicion(p.u, p.host);
        return { error: why ? `That link was blocked because ${why}.` : 'Use a YouTube, TikTok, Instagram, Twitch or Kick link.' };
    }
    return { url: p.u.toString() };
}

const IN_TEXT = /\b((?:https?:\/\/|www\.)[^\s<>"']+|[a-z0-9][a-z0-9-]*(?:\.[a-z0-9-]+)*\.(?:com|net|org|io|gg|tv|ly|co|me|xyz|ru|tk|ml|ga|cf|gq|info|link|click|top|site|online|shop|store|live|app|be|cc|su|gift|uk|us|de)(?:\/[^\s<>"']*)?)/gi;

function textIssue(raw, label) {
    const v = clean(raw);
    const found = v.match(IN_TEXT) || [];
    for (const link of found.slice(0, 20)) {
        const p = parse(link.replace(/[.,;:!?)\]]+$/, ''));
        if (!p.u) {
            continue;
        }
        const why = suspicion(p.u, p.host);
        if (why) {
            return `A link in "${label}" was blocked because ${why}: ${p.host}`;
        }
    }
    return null;
}

function noLinks(raw, label) {
    return (clean(raw).match(IN_TEXT) || []).length ? `"${label}" should just be a number or short note, not a link.` : null;
}

module.exports = { clean, channel, content, textIssue, noLinks, PLATFORM_HOSTS };
