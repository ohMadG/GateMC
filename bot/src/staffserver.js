const { pool } = require('./db');
const cfg = require('./features.json').staffServer || {};

async function eligible(discordId) {
  const { rows } = await pool.query(
    `SELECT 1 FROM bot_staff_applications a
       WHERE a.discord_id = $1 AND a.status = 'accepted'
         AND EXISTS (SELECT 1 FROM portal_agreements p WHERE p.discord_id = $1)
       LIMIT 1`,
    [discordId],
  );
  return rows.length > 0;
}

function guideRole(guild) {
  if (cfg.guideRoleId) {
    return guild.roles.cache.get(cfg.guideRoleId) || null;
  }
  return guild.roles.cache.find((r) => /guide/i.test(r.name) && !r.managed) || null;
}

async function onJoin(member) {
  if (!cfg.guildId || member.guild.id !== cfg.guildId || member.user.bot) {
    return;
  }
  if (!(await eligible(member.id))) {
    return;
  }
  const role = guideRole(member.guild);
  if (!role) {
    console.warn('[hawkeye-bot] staff server has no Guide role the bot can find');
    return;
  }
  await member.roles.add(role, 'New Guide: accepted and signed the staff agreement');
  console.log(`[hawkeye-bot] gave Guide in the staff server to ${member.user.username}`);
}

module.exports = { onJoin };
