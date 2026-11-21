const { OAuth2Client } = require('google-auth-library');

const client = new OAuth2Client(process.env.GOOGLE_CLIENT_ID);

/** Verifies a Google Identity Services credential and returns the signed-in email, or null. */
async function verifyGoogleCredential(credential) {
    const ticket = await client.verifyIdToken({
        idToken: credential,
        audience: process.env.GOOGLE_CLIENT_ID,
    });
    const payload = ticket.getPayload();
    if (!payload || !payload.email || !payload.email_verified) {
        return null;
    }
    return payload.email.toLowerCase();
}

module.exports = { verifyGoogleCredential };
