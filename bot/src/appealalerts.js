const { EmbedBuilder } = require('discord.js');
const { pool } = require('./db');
const cfg = require('./features.json').appealAlerts || {};

const POLL_MS = 30_000;
const RED = 0xed4245;
const DAY = 86_400_000;

async function recipients(client) {
  const guild = await client.guilds.fetch(process.env.DISCORD_GUILD_ID);
  const found = [];
  let after = '0';
  for (let page = 0; page < 20; page += 1) {
    const batch = await guild.members.list({ limit: 1000, after });
    if (batch.size === 0) {
      break;
    }
    for (const member of batch.values()) {
      if (!member.user.bot && member.roles.cache.has(cfg.roleId)) {
        found.push(member);
      }
    }
    after = batch.lastKey();
    if (batch.size < 1000) {
      break;
    }
  }
  return found;
}

function card(appeal) {
  const what = appeal.type ? `${appeal.type.replace(/_/g, ' ').toLowerCase()}${appeal.short_id ? ` #${appeal.short_id}` : ''}` : 'a punishment';
  return new EmbedBuilder()
    .setColor(RED)
    .setTitle('⚖️ New appeal')
    .setDescription(`**${appeal.target_name}** appealed their ${what}.\n\nReview it here: ${cfg.reviewUrl}`)
    .setTimestamp(new Date(Number(appeal.created_at)));
}

async function tick(client) {
  const { rows } = await pool.query(
    `SELECT r.id, r.target_name, r.created_at, p.type, p.short_id
       FROM pvp_revoke_requests r
       LEFT JOIN pvp_punishments p ON p.id = r.punishment_id
      WHERE r.status = 'pending' AND r.created_at > $1
        AND NOT EXISTS (SELECT 1 FROM bot_appeal_alerts a WHERE a.appeal_id = r.id)
      ORDER BY r.id LIMIT 5`,
    [Date.now() - DAY],
  );
  if (rows.length === 0) {
    return;
  }
  const members = await recipients(client);
  for (const appeal of rows) {
    const claimed = await pool.query(
      'INSERT INTO bot_appeal_alerts (appeal_id) VALUES ($1) ON CONFLICT DO NOTHING RETURNING appeal_id',
      [appeal.id],
    );
    if (claimed.rowCount === 0) {
      continue;
    }
    const embed = card(appeal);
    let sent = 0;
    for (const member of members) {
      if (await member.send({ embeds: [embed] }).then(() => true).catch(() => false)) {
        sent += 1;
      }
    }
    console.log(`[hawkeye-bot] appeal #${appeal.id} from ${appeal.target_name}: told ${sent} of ${members.length} Hawk Eye members`);
  }
}

function start(client) {
  if (!cfg.roleId || !cfg.reviewUrl) {
    return;
  }
  const run = () => tick(client).catch((e) => console.error('[hawkeye-bot] appeal alerts failed:', e.message));
  setTimeout(run, 15_000).unref();
  setInterval(run, POLL_MS).unref();
}

module.exports = { start };
