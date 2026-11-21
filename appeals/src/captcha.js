const VERIFY_URL = 'https://hcaptcha.com/siteverify';

// Feature-flagged: with no secret configured, every submission is treated as verified so the
// form keeps working while hCaptcha isn't set up yet.
async function verify(token, remoteIp) {
    const secret = process.env.HCAPTCHA_SECRET;
    if (!secret) {
        return true;
    }
    if (!token) {
        return false;
    }
    try {
        const res = await fetch(VERIFY_URL, {
            method: 'POST',
            headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
            body: new URLSearchParams({ secret, response: token, remoteip: remoteIp || '' }),
        });
        const data = await res.json();
        return Boolean(data.success);
    } catch (e) {
        console.error('hCaptcha verify failed:', e.message);
        return false;
    }
}

module.exports = { verify };
