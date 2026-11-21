require('dotenv').config();
const path = require('path');
const crypto = require('crypto');
const express = require('express');
const session = require('express-session');
const rateLimit = require('express-rate-limit');
const db = require('./db');
const google = require('./google');
const discord = require('./discord');
const webhook = require('./webhook');
const mailer = require('./mailer');
const uploads = require('./uploads');
const antivirus = require('./antivirus');
const captcha = require('./captcha');
const applications = require('./applications');

const app = express();
const PORT = Number(process.env.PORT || 8095);

app.set('trust proxy', 1);
app.set('view engine', 'ejs');

const ICON_PATHS = {
    check: '<path d="M20 6 9 17l-5-5"/>',
    alert: '<path d="M10.3 3.9 1.8 18.6A2 2 0 0 0 3.5 21.6h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0Z"/><path d="M12 9v4"/><path d="M12 17h.01"/>',
    shieldCheck: '<path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10Z"/><path d="m9 12 2 2 4-4"/>',
    clock: '<circle cx="12" cy="12" r="10"/><path d="M12 6v6l4 2"/>',
    pen: '<path d="M12 20h9"/><path d="M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4Z"/>',
    info: '<circle cx="12" cy="12" r="10"/><path d="M12 16v-4"/><path d="M12 8h.01"/>',
    x: '<path d="M18 6 6 18"/><path d="m6 6 12 12"/>',
    arrowRight: '<path d="M5 12h14"/><path d="m12 5 7 7-7 7"/>',
};
app.locals.icon = (name) => `<svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${ICON_PATHS[name] || ''}</svg>`;
app.locals.discordGlyph = (size) => `<svg viewBox="0 0 24 24" width="${size}" height="${size}" fill="currentColor" aria-hidden="true"><path d="M20.3 4.4A19.8 19.8 0 0 0 15.4 3l-.6 1.3a18.4 18.4 0 0 0-5.6 0L8.6 3a19.7 19.7 0 0 0-4.9 1.4C.6 9 -.3 13.5.1 18a19.9 19.9 0 0 0 6 3l1.3-2.1a12.9 12.9 0 0 1-2-1l.5-.4a14.2 14.2 0 0 0 12.2 0l.5.4a12.9 12.9 0 0 1-2 1l1.3 2.1a19.8 19.8 0 0 0 6-3c.5-5.2-.8-9.7-3.6-13.6ZM8.5 15.3c-1.2 0-2.2-1.1-2.2-2.4s1-2.4 2.2-2.4 2.2 1.1 2.2 2.4-1 2.4-2.2 2.4Zm7 0c-1.2 0-2.2-1.1-2.2-2.4s1-2.4 2.2-2.4 2.2 1.1 2.2 2.4-1 2.4-2.2 2.4Z"/></svg>`;
app.set('views', path.join(__dirname, '..', 'views'));
app.use(express.urlencoded({ extended: false }));
app.use(express.static(path.join(__dirname, '..', 'public')));
app.use(session({
    secret: process.env.SESSION_SECRET,
    resave: false,
    saveUninitialized: false,
    cookie: { httpOnly: true, sameSite: 'lax', secure: 'auto' },
}));

// A general ceiling against abuse everywhere, plus a tighter one on the routes that actually
// touch the database or do real work (appeal submission, login attempts).
const generalLimiter = rateLimit({ windowMs: 15 * 60 * 1000, limit: 100, standardHeaders: true, legacyHeaders: false });
const submitLimiter = rateLimit({ windowMs: 15 * 60 * 1000, limit: 10, standardHeaders: true, legacyHeaders: false });
const loginLimiter = rateLimit({ windowMs: 15 * 60 * 1000, limit: 20, standardHeaders: true, legacyHeaders: false });
app.use(generalLimiter);

function regenerateSession(req) {
    return new Promise((resolve, reject) => {
        req.session.regenerate((err) => (err ? reject(err) : resolve()));
    });
}

const MAX_MESSAGE_LENGTH = 1500;
const PAGE_SIZE = 20;

const TERMINAL_STATUSES = ['approved', 'denied', 'reduced', 'expired'];
// Punishment types severe enough that lifting or shortening them needs the admin role, not just
// the base staff role. There's no separate "blacklist" type in the plugin - IPBAN is it.
const ADMIN_ONLY_TYPES = ['IPBAN'];
const TRIAGE_STATUSES = ['pending', 'under_review', 'owner_approval_required', 'manager_approval_required'];
// A denied appeal can be resubmitted after this long, instead of being locked out forever -
// catches the case where new evidence shows up later without leaving the slot open to spam.
const RESUBMIT_COOLDOWN_MS = 14 * 24 * 60 * 60 * 1000;
// An appeal is "stale" in the pending queue once it's been sitting this long with no decision.
const STALE_AFTER_MS = 48 * 60 * 60 * 1000;
const STATUS_LABELS = {
    pending: 'Pending review',
    under_review: 'Under review',
    owner_approval_required: 'Owner approval required',
    manager_approval_required: 'Manager approval required',
    approved: 'Approved',
    denied: 'Denied',
    reduced: 'Approved (shortened)',
    expired: 'Expired on its own',
};

/**
 * Moves any appeal whose underlying punishment already expired on its own out of the active
 * queue into a terminal "expired" status, with no staff action needed. Checked lazily whenever
 * the staff queue or a player's status page is loaded, rather than on a schedule.
 */
async function autoExpireStaleAppeals() {
    const now = Date.now();
    const result = await db.query(
        `UPDATE pvp_revoke_requests r SET status = 'expired', reviewed_by_name = 'System', reviewed_at = $1,
                status_history = COALESCE(r.status_history, '[]'::jsonb)
                    || jsonb_build_array(jsonb_build_object('status', 'expired', 'by', 'System', 'at', $1))
         FROM pvp_punishments p
         WHERE r.punishment_id = p.id AND r.status = ANY($2::text[])
           AND p.expires_at IS NOT NULL AND p.expires_at < $1
         RETURNING r.target_name, r.submitter_email, p.short_id AS ban_short_id`,
        [now, TRIAGE_STATUSES]
    );
    for (const row of result.rows) {
        await webhook.postWebhook(`:hourglass: **${row.target_name}**'s appeal (#${row.ban_short_id}) auto-expired - the punishment ended on its own`);
        await mailer.appealUpdateEmail(row.submitter_email, {
            targetName: row.target_name,
            shortId: row.ban_short_id,
            status: 'expired',
        });
    }
}

// One entry to append to an appeal's status_history jsonb column via `|| $N::jsonb`.
function historyEntry(status, by, reason) {
    return JSON.stringify([{ status, by, at: Date.now(), reason: reason || null }]);
}

function requireStaff(req, res, next) {
    if (req.session && req.session.staffName) {
        return next();
    }
    res.redirect('/staff/login');
}

function discordRedirectUri(req) {
    return `${req.protocol}://${req.get('host')}/staff/discord/callback`;
}

function isoOrPermanent(ms) {
    return ms ? new Date(Number(ms)).toISOString() : 'permanent';
}

// Unauthenticated, minimal - for uptime monitors and the deploy script's health check. Never
// exposes anything beyond up/down for each dependency.
app.get('/healthz', async (req, res) => {
    const [dbOk, clamavOk] = await Promise.all([
        db.query('SELECT 1').then(() => true).catch(() => false),
        antivirus.ping().then(() => true).catch(() => false),
    ]);
    res.status(dbOk && clamavOk ? 200 : 503).json({ ok: dbOk && clamavOk, db: dbOk, clamav: clamavOk });
});

// --- Player-facing appeal form, gated by Google sign-in ---

app.get('/', (req, res) => {
    if (!req.session.playerEmail) {
        return res.render('google-login', {
            error: req.query.error || null,
            googleClientId: process.env.GOOGLE_CLIENT_ID,
            loginUri: `${req.protocol}://${req.get('host')}/auth/google`,
        });
    }
    res.render('appeal', {
        error: null,
        old: {},
        playerEmail: req.session.playerEmail,
        maxFiles: uploads.MAX_FILES,
        hcaptchaSiteKey: process.env.HCAPTCHA_SITE_KEY || null,
    });
});

app.get('/status', async (req, res) => {
    if (!req.session.playerEmail) {
        return res.redirect('/');
    }
    await autoExpireStaleAppeals();
    const result = await db.query(
        `SELECT r.*, p.short_id AS ban_short_id, p.active AS ban_active, p.expires_at AS ban_expires_at
         FROM pvp_revoke_requests r
         JOIN pvp_punishments p ON p.id = r.punishment_id
         WHERE r.submitter_email = $1
         ORDER BY r.created_at DESC`,
        [req.session.playerEmail]
    );
    const appeals = result.rows.map(({ reviewed_by_name, claimed_by_name, claimed_at, staff_notes, status_history, ...rest }) => rest);
    res.render('status', { playerEmail: req.session.playerEmail, appeals, statusLabels: STATUS_LABELS });
});

app.post('/auth/google', loginLimiter, async (req, res) => {
    try {
        const email = await google.verifyGoogleCredential(req.body.credential);
        if (!email) {
            return res.redirect('/?error=' + encodeURIComponent('Google sign-in failed.'));
        }
        await regenerateSession(req);
        req.session.playerEmail = email;
        res.redirect('/');
    } catch (e) {
        console.error('Google sign-in failed:', e);
        res.redirect('/?error=' + encodeURIComponent('Google sign-in failed.'));
    }
});

app.post('/auth/logout', (req, res) => {
    delete req.session.playerEmail;
    res.redirect('/');
});

app.post('/submit', submitLimiter, (req, res, next) => {
    uploads.upload.array('screenshots', uploads.MAX_FILES)(req, res, (err) => {
        if (err) {
            if (!req.session.playerEmail) {
                return res.redirect('/');
            }
            return res.render('appeal', {
                error: uploads.uploadErrorMessage(err),
                old: {
                    banId: req.body.banId || '',
                    username: req.body.username || '',
                    platform: req.body.platform || '',
                    appealType: req.body.appealType || '',
                    message: req.body.message || '',
                },
                playerEmail: req.session.playerEmail,
                maxFiles: uploads.MAX_FILES,
                hcaptchaSiteKey: process.env.HCAPTCHA_SITE_KEY || null,
            });
        }
        next();
    });
}, async (req, res) => {
    const files = req.files || [];
    const render = (error) => {
        uploads.cleanupTemp(files);
        res.render('appeal', {
            error,
            old,
            playerEmail: req.session.playerEmail,
            maxFiles: uploads.MAX_FILES,
            hcaptchaSiteKey: process.env.HCAPTCHA_SITE_KEY || null,
        });
    };

    if (!req.session.playerEmail) {
        uploads.cleanupTemp(files);
        return res.redirect('/');
    }

    if (!(await captcha.verify(req.body['h-captcha-response'], req.ip))) {
        return render('Captcha check failed. Please try again.');
    }

    const banId = (req.body.banId || '').trim().replace(/^#/, '').toUpperCase();
    const username = (req.body.username || '').trim();
    const platform = req.body.platform === 'Bedrock' ? 'Bedrock' : req.body.platform === 'Java' ? 'Java' : '';
    const appealType = ['incorrect', 'reconsideration', 'other'].includes(req.body.appealType) ? req.body.appealType : '';
    const message = (req.body.message || '').trim();
    const old = { banId: req.body.banId || '', username, platform, appealType, message };

    if (!banId || !username || !platform || !appealType || !message) {
        return render('Please fill in every field.');
    }
    if (message.length > MAX_MESSAGE_LENGTH) {
        return render(`Appeal message is too long (max ${MAX_MESSAGE_LENGTH} characters).`);
    }

    const APPEAL_TYPE_LABELS = {
        incorrect: 'Believes the punishment was issued incorrectly',
        reconsideration: 'Permanent punishment - requesting reconsideration',
        other: 'Other',
    };

    try {
        const punishment = await db.query(
            "SELECT * FROM pvp_punishments WHERE short_id = $1 AND type IN ('BAN', 'IPBAN', 'MUTE')",
            [banId]
        );
        if (punishment.rows.length === 0) {
            return render('No punishment was found with that ID.');
        }
        const row = punishment.rows[0];
        if (row.target_name.toLowerCase() !== username.toLowerCase()) {
            return render("That ID doesn't match that username.");
        }
        if (!row.active) {
            return render('That punishment is no longer active - there is nothing to appeal.');
        }

        const existing = await db.query(
            'SELECT id, status, reviewed_at FROM pvp_revoke_requests WHERE punishment_id = $1 ORDER BY created_at DESC LIMIT 1',
            [row.id]
        );
        if (existing.rows.length > 0) {
            const last = existing.rows[0];
            if (TRIAGE_STATUSES.includes(last.status)) {
                return render('There is already an appeal in progress for this punishment. Please wait for staff to review it.');
            }
            if (last.status === 'denied') {
                const cooldownEnds = Number(last.reviewed_at) + RESUBMIT_COOLDOWN_MS;
                if (Date.now() < cooldownEnds) {
                    return render(
                        `Your last appeal for this punishment was denied. You can resubmit after ${new Date(cooldownEnds).toLocaleDateString()}.`
                    );
                }
                // Cooldown has passed - fall through and let them submit a fresh appeal.
            } else {
                return render('An appeal has already been submitted and reviewed for this punishment. It cannot be appealed again.');
            }
        }

        let screenshotNames = [];
        if (files.length > 0) {
            try {
                screenshotNames = await uploads.verifyAndStore(files);
            } catch (e) {
                return render(e.message);
            }
        }

        const storedReason = `Platform: ${platform}\nAppeal type: ${APPEAL_TYPE_LABELS[appealType]}\n\n${message}`;

        await db.query(
            `INSERT INTO pvp_revoke_requests
                (punishment_id, target_uuid, target_name, requested_by_uuid, requested_by_name, request_reason, status, created_at, submitter_email, screenshots)
             VALUES ($1, $2, $3, $2, $3, $4, 'pending', $5, $6, $7)`,
            [row.id, row.target_uuid, row.target_name, storedReason, Date.now(), req.session.playerEmail, JSON.stringify(screenshotNames)]
        );

        const adminRoleIds = ADMIN_ONLY_TYPES.includes(row.type) ? discord.parseRoleIds(process.env.DISCORD_ADMIN_ROLE_IDS) : [];
        await webhook.postWebhook(
            [
                adminRoleIds.length ? adminRoleIds.map((id) => `<@&${id}>`).join(' ') : null,
                `:triangular_flag_on_post: **New appeal** from **${row.target_name}** (\`${row.target_uuid}\`)`,
                `${row.type} **#${row.short_id}**${row.category ? ', ' + row.category : ''}: ${row.reason || 'No reason given'}`,
                `Issued by **${row.staff_name}** on ${isoOrPermanent(row.created_at)}, expires ${isoOrPermanent(row.expires_at)}`,
                `Signed in as: ${req.session.playerEmail}`,
                screenshotNames.length ? `Screenshots attached: ${screenshotNames.length}` : null,
                storedReason,
            ].filter(Boolean).join('\n'),
            { mentionRoleIds: adminRoleIds }
        );

        await mailer.appealReceivedEmail(req.session.playerEmail, {
            targetName: row.target_name,
            shortId: row.short_id,
        });

        res.render('success', { playerEmail: req.session.playerEmail });
    } catch (err) {
        console.error('Appeal submission failed:', err);
        render('Something went wrong submitting your appeal. Please try again later.');
    }
});

// --- Staff review queue, gated by Discord login + staff role ---

app.get('/staff/login', (req, res) => {
    const state = crypto.randomBytes(16).toString('hex');
    req.session.discordState = state;
    res.render('staff-login', {
        error: req.query.error || null,
        discordAuthUrl: discord.buildAuthUrl(discordRedirectUri(req), state),
    });
});

app.get('/staff/discord/callback', loginLimiter, async (req, res) => {
    const { code, state } = req.query;
    if (state && req.session.applyState && state === req.session.applyState) {
        return applyCallback(req, res, code);
    }
    if (!code || !state || state !== req.session.discordState) {
        return res.redirect('/staff/login?error=' + encodeURIComponent('Login expired, try again.'));
    }
    delete req.session.discordState;
    try {
        const token = await discord.exchangeCode(code, discordRedirectUri(req));
        const identity = await discord.getIdentity(token.access_token);
        const standing = await discord.getStanding(identity.id);
        if (!standing.isStaff) {
            return res.redirect('/staff/login?error=' + encodeURIComponent('That Discord account is not GateMC staff.'));
        }
        await regenerateSession(req);
        req.session.staffName = identity.global_name || identity.username;
        req.session.staffDiscordId = identity.id;
        req.session.isAdmin = standing.isAdmin;
        req.session.canLiftSevere = standing.isAdmin || standing.isSenior;
        res.redirect('/staff');
    } catch (err) {
        console.error('Discord login failed:', err);
        res.redirect('/staff/login?error=' + encodeURIComponent('Discord login failed.'));
    }
});

app.post('/staff/logout', (req, res) => {
    req.session.destroy(() => res.redirect('/staff/login'));
});

function applyMessage(res, applicant, title, message, tone) {
    res.render('apply-message', { applicant, title, message, tone: tone || 'muted' });
}

async function applyCallback(req, res, code) {
    delete req.session.applyState;
    if (!code) {
        return res.redirect('/apply');
    }
    try {
        const token = await discord.exchangeCode(code, discordRedirectUri(req));
        const identity = await discord.getIdentity(token.access_token);
        await regenerateSession(req);
        req.session.applicant = {
            id: identity.id,
            username: identity.username,
            name: identity.global_name || identity.username,
        };
        res.redirect('/apply');
    } catch (err) {
        console.error('Applicant Discord login failed:', err);
        res.redirect('/apply?error=' + encodeURIComponent('Discord login failed, please try again.'));
    }
}

const APPLICATIONS_CLOSED_MESSAGE = 'Thank you for your interest in GateMC. We have received the maximum number of applications. If you were unable to apply, please try again soon.';

function applyClosed(res, applicant) {
    const s = applications.status();
    res.render('apply-closed', {
        applicant: applicant || null,
        message: APPLICATIONS_CLOSED_MESSAGE,
        openAt: s.state === 'scheduled' ? s.openAt : null,
        openAtText: s.state === 'scheduled' ? applications.easternTime(s.openAt) : null,
    });
}

function fmtDate(value) {
    return new Date(value).toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric', timeZone: 'America/New_York' });
}

async function applyPageState(applicant) {
    const member = await discord.isMember(applicant.id);
    const link = await applications.linkFor(applicant.id);
    const existing = await applications.latest(applicant.id);
    const block = await applications.blocker(applicant.id);
    const playtime = link ? await applications.playtimeSeconds(link.uuid) : null;
    return { member, link, existing, block, playtime };
}

function renderApply(res, applicant, state, values, error, statusCode) {
    res.status(statusCode || 200).render('apply', {
        applicant,
        member: state.member,
        link: state.link,
        existing: state.existing,
        pending: Boolean(state.block && state.block.kind === 'pending'),
        cooldownUntil: state.block && state.block.kind === 'cooldown' ? state.block.until : null,
        playtimeSeconds: state.playtime,
        recommendedSeconds: applications.RECOMMENDED_PLAYTIME_DAYS * 86400,
        questions: applications.QUESTIONS,
        values: values || {},
        error: error || null,
        fmtDate,
    });
}

app.get('/apply', async (req, res) => {
    const applicant = req.session.applicant;
    if (!applications.isOpen()) {
        return applyClosed(res, applicant);
    }
    if (!applicant) {
        const state = crypto.randomBytes(16).toString('hex');
        req.session.applyState = state;
        return res.render('apply-login', {
            error: req.query.error || null,
            discordAuthUrl: discord.buildAuthUrl(discordRedirectUri(req), state),
            recommendedPlaytimeDays: applications.RECOMMENDED_PLAYTIME_DAYS,
        });
    }
    try {
        renderApply(res, applicant, await applyPageState(applicant));
    } catch (err) {
        console.error('Apply page failed:', err);
        applyMessage(res, applicant, 'Something went wrong', 'Please try again in a minute.', 'error');
    }
});

app.post('/apply', submitLimiter, async (req, res) => {
    const applicant = req.session.applicant;
    if (!applications.isOpen()) {
        return applyClosed(res, applicant);
    }
    if (!applicant) {
        return res.redirect('/apply');
    }
    try {
        const state = await applyPageState(applicant);
        if (!state.member || !state.link || state.block) {
            return res.redirect('/apply');
        }
        const result = applications.validate(req.body);
        if (result.error) {
            return renderApply(res, applicant, state, result.values, result.error, 400);
        }
        const id = await applications.insert(applicant, state.link, result.answers);
        if (id) {
            console.log(`Staff application #${id} submitted by ${applicant.username}`);
        }
        res.render('apply-thanks', { applicant, alreadyPending: !id });
    } catch (err) {
        console.error('Application submit failed:', err);
        applyMessage(res, applicant, 'Something went wrong', 'Your application was not sent. Please try again in a minute.', 'error');
    }
});

app.get('/apply/history', async (req, res) => {
    const applicant = req.session.applicant;
    if (!applicant) {
        return res.redirect('/apply');
    }
    try {
        res.render('apply-history', { applicant, applications: await applications.history(applicant.id), fmtDate });
    } catch (err) {
        console.error('Application history failed:', err);
        applyMessage(res, applicant, 'Something went wrong', 'Please try again in a minute.', 'error');
    }
});

app.post('/apply/:id/withdraw', async (req, res) => {
    const applicant = req.session.applicant;
    const id = Number(req.params.id);
    if (!applicant || !Number.isInteger(id)) {
        return res.redirect('/apply');
    }
    try {
        if (await applications.withdraw(id, applicant.id)) {
            console.log(`Staff application #${id} withdrawn by ${applicant.username}`);
        }
        res.redirect('/apply');
    } catch (err) {
        console.error('Application withdraw failed:', err);
        applyMessage(res, applicant, 'Something went wrong', 'Please try again in a minute.', 'error');
    }
});

app.post('/apply/logout', (req, res) => {
    req.session.destroy(() => res.redirect('/apply'));
});

app.post('/staff/applications', requireStaff, (req, res) => {
    if (!req.session.isAdmin) {
        return res.redirect('/staff?error=' + encodeURIComponent('Only an admin can open or close staff applications.'));
    }
    const open = req.body.open === '1';
    try {
        applications.setOpen(open, req.session.staffName);
        console.log(`Staff applications ${open ? 'opened' : 'closed'} by ${req.session.staffName}`);
        res.redirect('/staff');
    } catch (err) {
        console.error('Could not change application status:', err);
        res.redirect('/staff?error=' + encodeURIComponent('Could not change the application status. Try again.'));
    }
});

app.get('/staff', requireStaff, async (req, res) => {
    await autoExpireStaleAppeals();
    const q = (req.query.q || '').trim();
    const page = Math.max(1, Number(req.query.page) || 1);
    const offset = (page - 1) * PAGE_SIZE;
    const result = await db.query(
        `SELECT r.*, p.reason AS ban_reason, p.type AS ban_type, p.category AS ban_category,
                p.short_id AS ban_short_id, p.staff_name AS ban_issued_by,
                p.created_at AS ban_created_at, p.expires_at AS ban_expires_at, p.active AS ban_active,
                count(*) OVER () AS total_count
         FROM pvp_revoke_requests r
         JOIN pvp_punishments p ON p.id = r.punishment_id
         WHERE NOT (r.status = ANY($1::text[])) AND ($2 = '' OR r.target_name ILIKE '%' || $2 || '%')
         ORDER BY r.created_at ASC
         LIMIT $3 OFFSET $4`,
        [TERMINAL_STATUSES, q, PAGE_SIZE, offset]
    );
    const total = result.rows.length > 0 ? Number(result.rows[0].total_count) : 0;
    res.render('staff-list', {
        staffName: req.session.staffName,
        isAdmin: Boolean(req.session.isAdmin),
        canLiftSevere: Boolean(req.session.isAdmin || req.session.canLiftSevere),
        applicationsOpen: applications.isOpen(),
        applicationsStatus: (() => {
            const s = applications.status();
            return {
                state: s.state,
                openAtText: s.openAt ? applications.easternTime(s.openAt) : null,
                closeAtText: s.closeAt ? applications.easternTime(s.closeAt) : null,
            };
        })(),
        appeals: result.rows,
        statusLabels: STATUS_LABELS,
        triageStatuses: TRIAGE_STATUSES,
        adminOnlyTypes: ADMIN_ONLY_TYPES,
        error: req.query.error || null,
        q,
        page,
        totalPages: Math.max(1, Math.ceil(total / PAGE_SIZE)),
    });
});

app.get('/staff/resolved', requireStaff, async (req, res) => {
    const q = (req.query.q || '').trim();
    const status = TERMINAL_STATUSES.includes(req.query.status) ? req.query.status : '';
    const statusFilter = status ? [status] : TERMINAL_STATUSES;
    const page = Math.max(1, Number(req.query.page) || 1);
    const offset = (page - 1) * PAGE_SIZE;
    const result = await db.query(
        `SELECT r.*, p.reason AS ban_reason, p.type AS ban_type, p.category AS ban_category,
                p.short_id AS ban_short_id, p.staff_name AS ban_issued_by,
                p.created_at AS ban_created_at, p.expires_at AS ban_expires_at, p.active AS ban_active,
                count(*) OVER () AS total_count
         FROM pvp_revoke_requests r
         JOIN pvp_punishments p ON p.id = r.punishment_id
         WHERE r.status = ANY($1::text[]) AND ($2 = '' OR r.target_name ILIKE '%' || $2 || '%')
         ORDER BY r.reviewed_at DESC
         LIMIT $3 OFFSET $4`,
        [statusFilter, q, PAGE_SIZE, offset]
    );
    const total = result.rows.length > 0 ? Number(result.rows[0].total_count) : 0;
    res.render('staff-resolved', {
        staffName: req.session.staffName,
        appeals: result.rows,
        statusLabels: STATUS_LABELS,
        q,
        status,
        page,
        totalPages: Math.max(1, Math.ceil(total / PAGE_SIZE)),
    });
});

app.get('/staff/stats', requireStaff, async (req, res) => {
    const sevenDaysAgo = Date.now() - 7 * 24 * 60 * 60 * 1000;
    const [pendingResult, byStatus] = await Promise.all([
        db.query('SELECT count(*) FROM pvp_revoke_requests WHERE NOT (status = ANY($1::text[]))', [TERMINAL_STATUSES]),
        db.query(
            `SELECT status,
                    count(*) FILTER (WHERE reviewed_at >= $1) AS last_7_days,
                    count(*) AS all_time
             FROM pvp_revoke_requests
             WHERE status = ANY($2::text[])
             GROUP BY status`,
            [sevenDaysAgo, TERMINAL_STATUSES]
        ),
    ]);
    const stats = {};
    for (const status of TERMINAL_STATUSES) {
        stats[status] = { last7: 0, allTime: 0 };
    }
    for (const row of byStatus.rows) {
        stats[row.status] = { last7: Number(row.last_7_days), allTime: Number(row.all_time) };
    }
    res.render('staff-stats', {
        staffName: req.session.staffName,
        pendingCount: Number(pendingResult.rows[0].count),
        stats,
        statusLabels: STATUS_LABELS,
        terminalStatuses: TERMINAL_STATUSES,
    });
});

function csvField(value) {
    const s = value === null || value === undefined ? '' : String(value);
    return /[",\n]/.test(s) ? '"' + s.replace(/"/g, '""') + '"' : s;
}

app.get('/staff/export.csv', requireStaff, async (req, res) => {
    const result = await db.query(
        `SELECT r.id, r.target_name, r.target_uuid, p.short_id AS ban_short_id, p.type AS ban_type,
                r.status, r.created_at, r.reviewed_by_name, r.reviewed_at, r.staff_reason,
                r.submitter_email, r.request_reason
         FROM pvp_revoke_requests r
         JOIN pvp_punishments p ON p.id = r.punishment_id
         WHERE r.status = ANY($1::text[])
         ORDER BY r.reviewed_at DESC
         LIMIT 5000`,
        [TERMINAL_STATUSES]
    );
    const header = ['id', 'target_name', 'target_uuid', 'ban_short_id', 'ban_type', 'status',
        'submitted_at', 'reviewed_by', 'reviewed_at', 'staff_reason', 'submitter_email', 'request_reason'];
    const lines = [header.join(',')];
    for (const r of result.rows) {
        lines.push([
            r.id, r.target_name, r.target_uuid, r.ban_short_id, r.ban_type, r.status,
            new Date(Number(r.created_at)).toISOString(),
            r.reviewed_by_name,
            r.reviewed_at ? new Date(Number(r.reviewed_at)).toISOString() : '',
            r.staff_reason, r.submitter_email, r.request_reason,
        ].map(csvField).join(','));
    }
    res.set('Content-Type', 'text/csv');
    res.set('Content-Disposition', 'attachment; filename="gatemc-appeals-export.csv"');
    res.send(lines.join('\n'));
});

app.post('/staff/:id/status', requireStaff, async (req, res) => {
    const id = Number(req.params.id);
    const status = req.body.status;
    if (!TRIAGE_STATUSES.includes(status)) {
        return res.redirect('/staff');
    }
    try {
        const updated = await db.query(
            `UPDATE pvp_revoke_requests SET status = $1, reviewed_by_name = $2, reviewed_at = $3,
                    status_history = COALESCE(status_history, '[]'::jsonb) || $6::jsonb
             WHERE id = $4 AND NOT (status = ANY($5::text[])) RETURNING target_name`,
            [status, req.session.staffName, Date.now(), id, TERMINAL_STATUSES, historyEntry(status, req.session.staffName)]
        );
        if (updated.rows.length > 0) {
            await webhook.postWebhook(
                `:eyes: **${req.session.staffName}** marked **${updated.rows[0].target_name}**'s appeal as **${STATUS_LABELS[status]}**`
            );
        }
    } catch (err) {
        console.error('Status update failed:', err);
    }
    res.redirect('/staff');
});

// Claiming is a soft coordination signal, not a lock - any staff member can still act on a
// claimed appeal, this just shows who's already looking at it so two people don't duplicate work.
app.post('/staff/:id/claim', requireStaff, async (req, res) => {
    const id = Number(req.params.id);
    try {
        await db.query(
            `UPDATE pvp_revoke_requests SET claimed_by_name = $1, claimed_at = $2
             WHERE id = $3 AND NOT (status = ANY($4::text[])) AND claimed_by_name IS NULL`,
            [req.session.staffName, Date.now(), id, TERMINAL_STATUSES]
        );
    } catch (err) {
        console.error('Claim failed:', err);
    }
    res.redirect(req.body.back === '/staff/resolved' ? '/staff/resolved' : '/staff');
});

app.post('/staff/:id/unclaim', requireStaff, async (req, res) => {
    const id = Number(req.params.id);
    try {
        await db.query(
            'UPDATE pvp_revoke_requests SET claimed_by_name = NULL, claimed_at = NULL WHERE id = $1',
            [id]
        );
    } catch (err) {
        console.error('Unclaim failed:', err);
    }
    res.redirect(req.body.back === '/staff/resolved' ? '/staff/resolved' : '/staff');
});

// Reopening sends a decided appeal (denied/reduced) back to pending for another look - e.g. new
// evidence came in, or a staff member thinks the call was wrong. Not offered for 'approved' (the
// punishment itself was already lifted - there's nothing left to undo by reopening the appeal) or
// 'expired' (the punishment ended on its own, so there's still nothing to decide).
const REOPENABLE_STATUSES = ['denied', 'reduced'];

app.post('/staff/:id/reopen', requireStaff, async (req, res) => {
    const id = Number(req.params.id);
    try {
        const found = await db.query(
            'SELECT target_name, status FROM pvp_revoke_requests WHERE id = $1',
            [id]
        );
        if (found.rows.length === 0 || !REOPENABLE_STATUSES.includes(found.rows[0].status)) {
            return res.redirect('/staff/resolved');
        }
        await db.query(
            `UPDATE pvp_revoke_requests
             SET status = 'pending', reviewed_by_name = NULL, reviewed_at = NULL, staff_reason = NULL,
                 status_history = COALESCE(status_history, '[]'::jsonb) || $2::jsonb
             WHERE id = $1`,
            [id, historyEntry('reopened', req.session.staffName)]
        );
        await webhook.postWebhook(
            `:recycle: **${req.session.staffName}** reopened **${found.rows[0].target_name}**'s appeal for another look`
        );
    } catch (err) {
        console.error('Reopen failed:', err);
    }
    res.redirect('/staff');
});

const SAFE_BACK_PATHS = ['/staff', '/staff/resolved'];

app.post('/staff/:id/notes', requireStaff, async (req, res) => {
    const id = Number(req.params.id);
    const notes = (req.body.notes || '').trim().slice(0, 2000);
    const back = SAFE_BACK_PATHS.includes(req.body.back) ? req.body.back : '/staff';
    try {
        await db.query('UPDATE pvp_revoke_requests SET staff_notes = $1 WHERE id = $2', [notes || null, id]);
    } catch (err) {
        console.error('Notes update failed:', err);
    }
    res.redirect(back);
});

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

app.get('/staff/history/:uuid', requireStaff, async (req, res) => {
    if (!UUID_RE.test(req.params.uuid)) {
        return res.status(400).send('Bad UUID');
    }
    const result = await db.query(
        'SELECT * FROM pvp_punishments WHERE target_uuid = $1 ORDER BY created_at DESC',
        [req.params.uuid]
    );
    const name = result.rows.length > 0 ? result.rows[0].target_name : req.params.uuid;
    res.render('staff-history', { staffName: req.session.staffName, targetName: name, targetUuid: req.params.uuid, punishments: result.rows });
});

// Screenshots are only ever served to signed-in staff, never publicly.
app.get('/staff/uploads/:filename', requireStaff, (req, res) => {
    const filePath = uploads.screenshotPath(req.params.filename);
    if (!filePath) {
        return res.status(400).send('Bad filename');
    }
    res.set('X-Content-Type-Options', 'nosniff');
    res.sendFile(filePath, (err) => {
        if (err && !res.headersSent) {
            res.status(404).send('Not found');
        }
    });
});

app.post('/staff/:id/approve', requireStaff, async (req, res) => {
    const id = Number(req.params.id);
    const client = await db.connect();
    let info = null;
    try {
        await client.query('BEGIN');
        const found = await client.query(
            `SELECT r.*, p.type AS ban_type, p.short_id AS ban_short_id
             FROM pvp_revoke_requests r JOIN pvp_punishments p ON p.id = r.punishment_id
             WHERE r.id = $1 AND NOT (r.status = ANY($2::text[]))`,
            [id, TERMINAL_STATUSES]
        );
        if (found.rows.length === 0) {
            await client.query('ROLLBACK');
            return res.redirect('/staff');
        }
        info = found.rows[0];
        if (ADMIN_ONLY_TYPES.includes(info.ban_type) && !req.session.isAdmin && !req.session.canLiftSevere) {
            await client.query('ROLLBACK');
            return res.redirect('/staff?error=' + encodeURIComponent(`Only an admin or Senior Staff can approve a ${info.ban_type} appeal.`));
        }
        await client.query(
            "UPDATE pvp_punishments SET active = 0, remove_admin = $1, remove_reason = 'Appeal approved', removed_at = $2 WHERE id = $3 AND active = 1",
            [req.session.staffName, Date.now(), info.punishment_id]
        );
        await client.query(
            `UPDATE pvp_revoke_requests SET status = 'approved', reviewed_by_name = $1, reviewed_at = $2,
                    status_history = COALESCE(status_history, '[]'::jsonb) || $4::jsonb
             WHERE id = $3`,
            [req.session.staffName, Date.now(), id, historyEntry('approved', req.session.staffName)]
        );
        await client.query('COMMIT');
    } catch (err) {
        await client.query('ROLLBACK');
        console.error('Approve failed:', err);
        info = null;
    } finally {
        client.release();
    }
    if (info) {
        await webhook.postWebhook(
            `:white_check_mark: **${req.session.staffName}** approved the appeal and lifted the ${info.ban_type} on **${info.target_name}** (#${info.ban_short_id})`
        );
        await mailer.appealUpdateEmail(info.submitter_email, {
            targetName: info.target_name,
            shortId: info.ban_short_id,
            status: 'approved',
        });
    }
    res.redirect('/staff');
});

app.post('/staff/:id/deny', requireStaff, async (req, res) => {
    const id = Number(req.params.id);
    const reason = (req.body.reason || '').trim().slice(0, 1000) || null;
    try {
        const updated = await db.query(
            `UPDATE pvp_revoke_requests r SET status = 'denied', reviewed_by_name = $1, reviewed_at = $2, staff_reason = $3,
                    status_history = COALESCE(r.status_history, '[]'::jsonb) || $6::jsonb
             FROM pvp_punishments p
             WHERE r.id = $4 AND NOT (r.status = ANY($5::text[])) AND p.id = r.punishment_id
             RETURNING r.target_name, r.submitter_email, p.short_id AS ban_short_id`,
            [req.session.staffName, Date.now(), reason, id, TERMINAL_STATUSES, historyEntry('denied', req.session.staffName, reason)]
        );
        if (updated.rows.length > 0) {
            const row = updated.rows[0];
            await webhook.postWebhook(
                `:x: **${req.session.staffName}** denied **${row.target_name}**'s appeal` + (reason ? `\nReason: ${reason}` : '')
            );
            await mailer.appealUpdateEmail(row.submitter_email, {
                targetName: row.target_name,
                shortId: row.ban_short_id,
                status: 'denied',
                staffReason: reason,
            });
        }
    } catch (err) {
        console.error('Deny failed:', err);
    }
    res.redirect('/staff');
});

app.post('/staff/:id/reduce', requireStaff, async (req, res) => {
    const id = Number(req.params.id);
    const amount = Number(req.body.amount);
    const unit = req.body.unit === 'hours' ? 'hours' : 'days';
    const reason = (req.body.reason || '').trim().slice(0, 1000) || null;
    if (!Number.isFinite(amount) || amount <= 0) {
        return res.redirect('/staff');
    }
    const unitMs = unit === 'hours' ? 3600000 : 86400000;
    const client = await db.connect();
    let info = null;
    let newExpiry = null;
    try {
        await client.query('BEGIN');
        const found = await client.query(
            `SELECT r.*, p.type AS ban_type, p.short_id AS ban_short_id
             FROM pvp_revoke_requests r JOIN pvp_punishments p ON p.id = r.punishment_id
             WHERE r.id = $1 AND NOT (r.status = ANY($2::text[]))`,
            [id, TERMINAL_STATUSES]
        );
        if (found.rows.length === 0) {
            await client.query('ROLLBACK');
            return res.redirect('/staff');
        }
        info = found.rows[0];
        if (ADMIN_ONLY_TYPES.includes(info.ban_type) && !req.session.isAdmin && !req.session.canLiftSevere) {
            await client.query('ROLLBACK');
            return res.redirect('/staff?error=' + encodeURIComponent(`Only an admin or Senior Staff can reduce a ${info.ban_type} appeal.`));
        }
        newExpiry = Date.now() + amount * unitMs;
        await client.query(
            "UPDATE pvp_punishments SET expires_at = $1 WHERE id = $2 AND active = 1",
            [newExpiry, info.punishment_id]
        );
        await client.query(
            `UPDATE pvp_revoke_requests SET status = 'reduced', reviewed_by_name = $1, reviewed_at = $2, staff_reason = $3,
                    status_history = COALESCE(status_history, '[]'::jsonb) || $5::jsonb
             WHERE id = $4`,
            [req.session.staffName, Date.now(), reason, id, historyEntry('reduced', req.session.staffName, reason)]
        );
        await client.query('COMMIT');
    } catch (err) {
        await client.query('ROLLBACK');
        console.error('Reduce failed:', err);
        info = null;
    } finally {
        client.release();
    }
    if (info) {
        const newExpiryStr = new Date(newExpiry).toISOString();
        await webhook.postWebhook(
            `:arrow_down: **${req.session.staffName}** reduced **${info.target_name}**'s ${info.ban_type} (#${info.ban_short_id}) to expire ${newExpiryStr}`
            + (reason ? `\nReason: ${reason}` : '')
        );
        await mailer.appealUpdateEmail(info.submitter_email, {
            targetName: info.target_name,
            shortId: info.ban_short_id,
            status: 'reduced',
            newExpiry: newExpiryStr,
            staffReason: reason,
        });
    }
    res.redirect('/staff');
});

// Checks clamd every 5 minutes and posts a Discord alert only when it actually changes state
// (down or back up), not on every check - staff otherwise have no way to know uploads are
// silently failing closed until a player complains.
let clamavWasUp = true;
function startClamavMonitor() {
    setInterval(async () => {
        const up = await antivirus.ping().then(() => true).catch(() => false);
        if (up !== clamavWasUp) {
            clamavWasUp = up;
            await webhook.postWebhook(
                up
                    ? ':white_check_mark: ClamAV is back up - screenshot uploads are working again.'
                    : ':rotating_light: ClamAV is unreachable - screenshot uploads are failing closed (rejected, not skipped) until this recovers.'
            );
        }
    }, 5 * 60 * 1000);
}

// Fail fast on a broken dependency (e.g. a Node/package version mismatch) instead of only
// discovering it when the first real upload comes in and crashes the whole process - a deploy's
// health check then correctly sees this container never come up and leaves the old one live.
uploads.ready
    .then(() => {
        app.listen(PORT, () => {
            console.log(`GateMC appeals listening on port ${PORT}`);
            startClamavMonitor();
        });
    })
    .catch((err) => {
        console.error('Startup check failed (uploads dependency):', err);
        process.exit(1);
    });
