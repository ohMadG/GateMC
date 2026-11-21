require('dotenv').config();
const fs = require('fs');
const path = require('path');
const express = require('express');
const session = require('express-session');
const rateLimit = require('express-rate-limit');
const helpers = require('./helpers');
const auth = require('./auth');
const db = require('./db');
const tebex = require('./tebex');

const app = express();
const PORT = Number(process.env.PORT || 8104);

app.set('trust proxy', 1);
app.set('view engine', 'ejs');
app.set('views', path.join(__dirname, '..', 'views'));
app.disable('x-powered-by');
Object.assign(app.locals, helpers, {
    cssVersion: '20261121a',
    siteName: 'GateMC Media',
    discordInvite: 'https://discord.gg/gatemc',
    storeUrl: 'https://store.gatemc.org',
    baseDomain: process.env.MEDIA_BASE_DOMAIN || 'gatemc.org',
});

app.use((req, res, next) => {
    res.set('X-Content-Type-Options', 'nosniff');
    res.set('Referrer-Policy', 'strict-origin-when-cross-origin');
    res.set('X-Frame-Options', 'DENY');
    next();
});
app.use(express.urlencoded({ extended: false, limit: '64kb' }));
app.use(express.static(path.join(__dirname, '..', 'public'), { maxAge: '1h' }));
app.use(session({
    name: 'gatemc_media',
    secret: process.env.SESSION_SECRET,
    resave: false,
    saveUninitialized: false,
    cookie: { httpOnly: true, sameSite: 'lax', secure: 'auto', maxAge: 7 * 24 * 60 * 60 * 1000 },
}));

app.use(rateLimit({ windowMs: 15 * 60 * 1000, limit: 300, standardHeaders: true, legacyHeaders: false }));
app.use(auth.viewerMiddleware());
app.use(auth.routes(rateLimit({ windowMs: 15 * 60 * 1000, limit: 30, standardHeaders: true, legacyHeaders: false })));

app.use('/api', require('./routes/api'));
app.get('/healthz', (req, res) => res.type('text').send('ok'));

app.use('/admin', require('./routes/admin'));
app.use('/me', require('./routes/creator'));
app.use('/apply', require('./routes/apply'));
app.use('/', require('./routes/home'));

app.use((req, res) => {
    res.status(404).render('error', { title: 'Page not found', message: "That page doesn't exist. It may have moved, or the link may be wrong." });
});

app.use((err, req, res, next) => {
    console.error('Unhandled error:', err);
    if (res.headersSent) {
        return next(err);
    }
    res.status(500).render('error', { title: 'Something went wrong', message: 'Please try again in a minute.' });
});

async function boot() {
    const sql = fs.readFileSync(path.join(__dirname, '..', 'sql', 'media-tables.sql'), 'utf8');
    await db.query(sql);
    app.listen(PORT, () => console.log(`GateMC media portal listening on ${PORT}`));
    tebex.start(Number(process.env.TEBEX_SYNC_MINUTES || 10));
    require('./program').startActivity();
}

boot().catch((e) => {
    console.error('Startup failed:', e);
    process.exit(1);
});
