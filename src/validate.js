const { clean } = require('./links');

const RESERVED = new Set(['www', 'play', 'mc', 'store', 'staff', 'media', 'appeals', 'panel', 'admin', 'discord', 'bedrock', 'api', 'mail', 'shop', 'meda', 'dev', 'pack', 'hub']);

function text(value, max) {
    return clean(value).replace(/\s+/g, ' ').trim().slice(0, max || 200);
}

function longText(value, max) {
    return clean(value).replace(/\r\n/g, '\n').replace(/\n{3,}/g, '\n\n').trim().slice(0, max || 2000);
}

function percent(value) {
    const n = Number.parseFloat(String(value || '').replace('%', ''));
    if (!Number.isFinite(n) || n < 0 || n > 100) {
        return null;
    }
    return Math.round(n * 100) / 100;
}

function whole(value, min, max, fallback) {
    const n = Number.parseInt(value, 10);
    if (!Number.isFinite(n)) {
        return fallback;
    }
    return Math.max(min, Math.min(max, n));
}

function host(value, baseDomain) {
    let h = String(value || '').trim().toLowerCase().replace(/^[a-z]+:\/\//, '').replace(/[/:].*$/, '').replace(/\.+$/, '');
    if (!h) {
        return { error: 'Enter a domain.' };
    }
    if (!h.includes('.')) {
        h = `${h}.${baseDomain}`;
    }
    if (!/^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+$/.test(h)) {
        return { error: `"${h}" isn't a valid domain.` };
    }
    if (h === baseDomain) {
        return { error: `${baseDomain} is the main address and can't belong to a creator.` };
    }
    if (h.endsWith('.' + baseDomain) && RESERVED.has(h.slice(0, -(baseDomain.length + 1)))) {
        return { error: `${h} is already used by GateMC.` };
    }
    return { host: h };
}

function url(value) {
    const v = text(value, 300);
    if (!v) {
        return '';
    }
    const withScheme = /^https?:\/\//i.test(v) ? v : 'https://' + v;
    try {
        const u = new URL(withScheme);
        return u.protocol === 'https:' || u.protocol === 'http:' ? u.toString() : '';
    } catch (e) {
        return '';
    }
}

function money(value) {
    const n = Number.parseFloat(String(value || '').replace(/[$,\s]/g, ''));
    return Number.isFinite(n) && n > 0 && n < 1000000 ? Math.round(n * 100) / 100 : null;
}

function code(value) {
    const c = String(value || '').trim();
    if (!c) {
        return { code: null };
    }
    if (!/^[A-Za-z0-9_]{3,20}$/.test(c)) {
        return { error: 'Codes are 3-20 letters, numbers or underscores.' };
    }
    return { code: c };
}

module.exports = { text, longText, percent, whole, host, url, money, code };
