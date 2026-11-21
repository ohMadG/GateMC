const { pool, tx } = require('./db');

const CODE = /^[A-Z0-9]{6}$/;

async function linkByDiscord(discordId) {
  const { rows } = await pool.query(
    'SELECT uuid, player_name, discord_id, discord_name, linked_at, boosting, boost_since FROM pvp_discord_links WHERE discord_id = $1',
    [discordId],
  );
  return rows[0] || null;
}

async function claimCode(rawCode, discordId, discordName, premiumSince) {
  const code = String(rawCode || '').trim().toUpperCase();
  if (!CODE.test(code)) {
    return { error: 'That code doesn\'t look right. Run `/link` in game to get one.' };
  }
  return tx(async (c) => {
    const now = Date.now();
    const found = await c.query(
      'SELECT uuid, player_name, expires_at FROM pvp_discord_codes WHERE code = $1 FOR UPDATE',
      [code],
    );
    const row = found.rows[0];
    if (!row || Number(row.expires_at) < now) {
      return { error: 'That code is invalid or expired. Run `/link` in game for a new one.' };
    }
    const byDiscord = await c.query('SELECT player_name FROM pvp_discord_links WHERE discord_id = $1', [discordId]);
    if (byDiscord.rows[0]) {
      return { error: `Your Discord is already linked to **${byDiscord.rows[0].player_name}**. Use \`/unlink\` first.` };
    }
    const byUuid = await c.query('SELECT discord_name FROM pvp_discord_links WHERE uuid = $1', [row.uuid]);
    if (byUuid.rows[0]) {
      return { error: `**${row.player_name}** is already linked to another Discord account.` };
    }
    await c.query('DELETE FROM pvp_discord_codes WHERE code = $1 OR uuid = $2', [code, row.uuid]);
    const boosting = premiumSince ? 1 : 0;
    await c.query(
      'INSERT INTO pvp_discord_links (uuid, player_name, discord_id, discord_name, linked_at, boosting, boost_since, rewarded, updated_at) '
        + 'VALUES ($1, $2, $3, $4, $5, $6, $7, 0, $5)',
      [row.uuid, row.player_name, discordId, discordName, now, boosting, premiumSince || 0],
    );
    return { uuid: row.uuid, playerName: row.player_name, boosting: boosting === 1 };
  });
}

async function unlink(discordId) {
  const { rows } = await pool.query('DELETE FROM pvp_discord_links WHERE discord_id = $1 RETURNING player_name, linked_at', [discordId]);
  return rows[0] || null;
}

async function setBoost(discordId, premiumSince, discordName) {
  return tx(async (c) => {
    const { rows } = await c.query(
      'SELECT boosting, boost_since, discord_name FROM pvp_discord_links WHERE discord_id = $1 FOR UPDATE',
      [discordId],
    );
    const row = rows[0];
    if (!row) {
      return null;
    }
    const now = Date.now();
    const was = Number(row.boosting) === 1;
    const since = Number(row.boost_since);
    const nameChanged = discordName && discordName !== row.discord_name;
    if (premiumSince) {
      if (was && since === premiumSince && !nameChanged) {
        return null;
      }
      await c.query(
        'UPDATE pvp_discord_links SET boosting = 1, boost_since = $2, discord_name = COALESCE($3, discord_name), updated_at = $4 WHERE discord_id = $1',
        [discordId, premiumSince, discordName || null, now],
      );
      return was ? null : 'started';
    }
    if (!was) {
      if (nameChanged) {
        await c.query('UPDATE pvp_discord_links SET discord_name = $2, updated_at = $3 WHERE discord_id = $1', [discordId, discordName, now]);
      }
      return null;
    }
    const banked = since > 0 ? Math.max(0, now - since) : 0;
    await c.query(
      'INSERT INTO pvp_discord_boosts (discord_id, boost_banked_ms, updated_at) VALUES ($1, $2, $3) '
        + 'ON CONFLICT (discord_id) DO UPDATE SET boost_banked_ms = pvp_discord_boosts.boost_banked_ms + EXCLUDED.boost_banked_ms, updated_at = EXCLUDED.updated_at',
      [discordId, banked, now],
    );
    await c.query(
      'UPDATE pvp_discord_links SET boosting = 0, boost_since = 0, discord_name = COALESCE($2, discord_name), updated_at = $3 WHERE discord_id = $1',
      [discordId, discordName || null, now],
    );
    return 'stopped';
  });
}

async function linkedDiscordIds() {
  const { rows } = await pool.query('SELECT discord_id FROM pvp_discord_links');
  return rows.map((r) => r.discord_id);
}

async function bankedMs(discordId) {
  const { rows } = await pool.query('SELECT boost_banked_ms FROM pvp_discord_boosts WHERE discord_id = $1', [discordId]);
  return rows[0] ? Number(rows[0].boost_banked_ms) : 0;
}

module.exports = { linkByDiscord, claimCode, unlink, setBoost, linkedDiscordIds, bankedMs };
