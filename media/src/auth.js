const crypto = require('crypto');
const express = require('express');
const discord = require('./discord');
const db = require('./db');

const REFRESH_MS = 5 * 60 * 1000;

function ids(value) {
    return String(value || '').split(',').map((s) => s.trim()).filter(Boolean);
}

function isAdmin(discordId) {
    return ids(process.env.ADMIN_DISCORD_IDS).includes(discordId);
}

function allowed(discordId) {
    const list = ids(process.env.ALLOWED_DISCORD_IDS);
    return list.length === 0 || list.includes(discordId) || isAdmin(discordId);
}

function redirectUri(req) {
    return `${req.protocol}://${req.get('host')}/auth/discord/callback`;
}

function safeReturn(value) {
    const v = String(value || '/');
    return v.startsWith('/') && !v.startsWith('//') && !v.startsWith('/\\') && !/[\r\n]/.test(v) ? v : '/';
}

async function refresh(viewer) {
    viewer.isAdmin = isAdmin(viewer.discordId);
    const { rows } = await db.query('SELECT id, name, active FROM media_creators WHERE discord_id = $1', [viewer.discordId]);
    viewer.creatorId = rows[0] ? rows[0].id : null;
    viewer.creatorName = rows[0] ? rows[0].name : null;
    viewer.creatorActive = rows[0] ? rows[0].active : false;
    viewer.checkedAt = Date.now();
}

function viewerMiddleware() {
    return async (req, res, next) => {
        const viewer = req.session.viewer || null;
        if (viewer && Date.now() - (viewer.checkedAt || 0) > REFRESH_MS) {
            try {
                await refresh(viewer);
            } catch (e) {
                console.error('Viewer refresh failed:', e.message);
            }
        }
        if (viewer && !allowed(viewer.discordId)) {
            req.session.viewer = null;
            res.locals.viewer = null;
        } else {
            res.locals.viewer = viewer;
        }
        res.locals.path = req.path;
        next();
    };
}

function signIn(req, res) {
    return res.redirect('/auth/discord?returnTo=' + encodeURIComponent(req.originalUrl));
}

function requireSignIn(req, res, next) {
    return req.session.viewer ? next() : signIn(req, res);
}

function requireAdmin(req, res, next) {
    const v = req.session.viewer;
    if (!v) {
        return signIn(req, res);
    }
    if (!v.isAdmin) {
        return res.status(404).render('error', { title: 'Page not found', message: "That page doesn't exist. It may have moved, or the link may be wrong." });
    }
    return next();
}

function requireCreator(req, res, next) {
    const v = req.session.viewer;
    if (!v) {
        return signIn(req, res);
    }
    if (!v.creatorId) {
        return res.redirect('/apply');
    }
    return next();
}

function routes(loginLimiter) {
    const router = express.Router();

    router.get(['/login', '/signin'], (req, res) => {
        const target = safeReturn(req.query.returnTo);
        res.redirect(req.session.viewer ? target : '/auth/discord?returnTo=' + encodeURIComponent(target));
    });

    router.get('/auth/discord', (req, res) => {
        const state = crypto.randomBytes(16).toString('hex');
        req.session.oauthState = state;
        req.session.returnTo = safeReturn(req.query.returnTo);
        res.redirect(discord.buildAuthUrl(redirectUri(req), state));
    });

    router.get('/auth/discord/callback', loginLimiter, async (req, res) => {
        const { code, state } = req.query;
        const returnTo = safeReturn(req.session.returnTo);
        if (!code || !state || state !== req.session.oauthState) {
            return res.redirect('/?signin=failed');
        }
        try {
            const token = await discord.exchangeCode(code, redirectUri(req));
            const identity = await discord.getIdentity(token.access_token);
            if (!allowed(identity.id)) {
                console.log(`Sign-in refused (not on the allow list): ${identity.username} ${identity.id}`);
                req.session.oauthState = null;
                return res.redirect('/?signin=closed');
            }
            await new Promise((resolve, reject) => req.session.regenerate((err) => (err ? reject(err) : resolve())));
            const viewer = {
                discordId: identity.id,
                username: identity.username,
                name: identity.global_name || identity.username,
                avatarUrl: discord.avatarUrl(identity),
            };
            await refresh(viewer);
            req.session.viewer = viewer;
            console.log(`Signed in: ${viewer.username}${viewer.isAdmin ? ' (admin)' : viewer.creatorId ? ' (creator)' : ''}`);
            res.redirect(returnTo);
        } catch (err) {
            console.error('Discord sign-in failed:', err.message);
            res.redirect('/?signin=failed');
        }
    });

    router.post('/auth/logout', (req, res) => {
        req.session.destroy(() => res.redirect('/'));
    });

    return router;
}

module.exports = { routes, viewerMiddleware, requireSignIn, requireAdmin, requireCreator, refresh };
