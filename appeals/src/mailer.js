function escapeHtml(text) {
    return String(text ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

function wrap(bodyHtml) {
    return `<!doctype html>
<html>
<body style="margin:0; padding:32px 16px; background:#121018; font-family:'Segoe UI', Arial, sans-serif;">
    <div style="max-width:480px; margin:0 auto; background:#1c1828; border:1px solid #35304a; border-radius:12px; padding:28px; color:#f1eefb;">
        <div style="font-weight:700; letter-spacing:0.3px; font-size:14px; color:#a79ccb; margin-bottom:20px;">GATEMC APPEALS</div>
        ${bodyHtml}
        <p style="margin:24px 0 0; font-size:12px; color:#a79ccb;">
            <a href="https://appeals.gatemc.org/status" style="color:#9b7fe0;">View your appeals</a>
        </p>
    </div>
</body>
</html>`;
}

async function sendMail(to, subject, bodyHtml) {
    const apiKey = process.env.RESEND_API_KEY;
    if (!apiKey || !to) {
        return;
    }
    try {
        const res = await fetch('https://api.resend.com/emails', {
            method: 'POST',
            headers: {
                Authorization: `Bearer ${apiKey}`,
                'Content-Type': 'application/json',
            },
            body: JSON.stringify({
                from: process.env.RESEND_FROM || 'GateMC Appeals <onboarding@resend.dev>',
                to,
                subject,
                html: wrap(bodyHtml),
            }),
        });
        if (!res.ok) {
            console.error('Resend send failed:', res.status, await res.text());
        }
    } catch (e) {
        console.error('Resend send error:', e.message);
    }
}

function reasonBlock(staffReason) {
    if (!staffReason) {
        return '';
    }
    return `<div style="margin-top:14px; padding:12px 14px; background:#121018; border:1px solid #35304a; border-radius:8px; color:#f1eefb; white-space:pre-wrap;">${escapeHtml(staffReason)}</div>`;
}

function appealUpdateEmail(email, { targetName, shortId, status, newExpiry, staffReason }) {
    const name = escapeHtml(targetName);
    const lines = {
        approved: `<h2 style="margin:0 0 12px; font-size:19px;">Your appeal was approved</h2>
            <p style="color:#a79ccb; line-height:1.6;">The punishment on <strong style="color:#f1eefb;">${name}</strong>
            (#${shortId}) has been lifted. You should be able to rejoin normally.</p>${reasonBlock(staffReason)}`,
        denied: `<h2 style="margin:0 0 12px; font-size:19px;">Your appeal was denied</h2>
            <p style="color:#a79ccb; line-height:1.6;">Staff reviewed the appeal for <strong style="color:#f1eefb;">${name}</strong>
            (#${shortId}) and decided the punishment stands.</p>${reasonBlock(staffReason)}`,
        reduced: `<h2 style="margin:0 0 12px; font-size:19px;">Your appeal was approved - punishment shortened</h2>
            <p style="color:#a79ccb; line-height:1.6;">The punishment on <strong style="color:#f1eefb;">${name}</strong>
            (#${shortId}) was shortened instead of fully lifted. It now expires
            <strong style="color:#f1eefb;">${escapeHtml(newExpiry)}</strong>.</p>${reasonBlock(staffReason)}`,
        expired: `<h2 style="margin:0 0 12px; font-size:19px;">No action needed on your appeal</h2>
            <p style="color:#a79ccb; line-height:1.6;">The punishment on <strong style="color:#f1eefb;">${name}</strong>
            (#${shortId}) ended on its own before staff reviewed your appeal, so there's nothing left to decide.</p>`,
    };
    return sendMail(email, `GateMC appeal update - #${shortId}`, lines[status]);
}

function appealReceivedEmail(email, { targetName, shortId }) {
    return sendMail(
        email,
        `GateMC appeal received - #${shortId}`,
        `<h2 style="margin:0 0 12px; font-size:19px;">We got your appeal</h2>
        <p style="color:#a79ccb; line-height:1.6;">Your appeal for <strong style="color:#f1eefb;">${escapeHtml(targetName)}</strong>
        (#${shortId}) was submitted and is waiting for staff to review it. You'll get another email
        once it's been decided.</p>`
    );
}

module.exports = { sendMail, appealUpdateEmail, appealReceivedEmail };
