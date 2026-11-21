const { pool } = require('./db');
const features = require('./features.json');

const cfg = features.roleSync || { rankRoles: {}, intervalSeconds: 120 };
const RANK_ROLE_IDS = Object.values(cfg.rankRoles || {});
let columnsMissing = false;
let warned = false;
let running = false;
let lastFull = 0;

function missing(e) {
  if (e && e.code === '42703') {
    columnsMissing = true;
    if (!warned) {
      warned = true;
      console.warn('[hawkeye-bot] role sync is waiting for the plugin to add its database columns (next server restart)');
    }
    return true;
  }
  return false;
}

function roleList(member) {
  return [...member.roles.cache.keys()].filter((id) => id !== member.guild.id).sort().join(',');
}

async function recordRoles(member) {
  if (!member || member.user.bot) {
    return;
  }
  try {
    await pool.query(
      'UPDATE pvp_discord_links SET discord_roles = $2 WHERE discord_id = $1 AND discord_roles IS DISTINCT FROM $2',
      [member.id, roleList(member)],
    );
  } catch (e) {
    if (!missing(e)) {
      console.error('[hawkeye-bot] could not record roles:', e.message);
    }
  }
}

async function applyRanks(member, ranks) {
  if (!member) {
    return;
  }
  const wanted = new Set(ranks.map((r) => cfg.rankRoles[r]).filter(Boolean));
  const add = [...wanted].filter((id) => !member.roles.cache.has(id));
  const remove = RANK_ROLE_IDS.filter((id) => !wanted.has(id) && member.roles.cache.has(id));
  try {
    if (add.length) {
      await member.roles.add(add, 'In-game store rank');
    }
    if (remove.length) {
      await member.roles.remove(remove, 'In-game store rank ended or unlinked');
    }
    if (add.length || remove.length) {
      console.log(`[hawkeye-bot] rank roles for ${member.user.username}: ${ranks.join(', ') || 'none'}`);
    }
  } catch (e) {
    console.warn(`[hawkeye-bot] could not update rank roles for ${member.id}: ${e.message}`);
  }
}

async function stripRanks(member) {
  await applyRanks(member, []);
}

async function syncAll(client, guildId) {
  if (running) {
    return;
  }
  running = true;
  try {
    let rows;
    try {
      ({ rows } = await pool.query('SELECT discord_id, game_ranks FROM pvp_discord_links'));
      columnsMissing = false;
    } catch (e) {
      if (!missing(e)) {
        throw e;
      }
      return;
    }
    const guild = await client.guilds.fetch(guildId);
    let members = guild.members.cache;
    if (Date.now() - lastFull > 3600000 || members.size < 2) {
      members = await guild.members.fetch();
      lastFull = Date.now();
    }
    const linked = new Map(rows.map((r) => [r.discord_id, (r.game_ranks || '').split(',').filter(Boolean)]));
    for (const [id, ranks] of linked) {
      const member = members.get(id);
      if (member) {
        await recordRoles(member);
        await applyRanks(member, ranks);
      }
    }
    if (RANK_ROLE_IDS.length) {
      for (const member of members.values()) {
        if (!linked.has(member.id) && RANK_ROLE_IDS.some((r) => member.roles.cache.has(r))) {
          await stripRanks(member);
        }
      }
    }
  } catch (e) {
    console.error('[hawkeye-bot] role sync failed:', e.message);
  } finally {
    running = false;
  }
}

function start(client, guildId) {
  syncAll(client, guildId);
  setInterval(() => syncAll(client, guildId), Math.max(30, cfg.intervalSeconds || 120) * 1000).unref();
}

function onMemberUpdate(before, after) {
  if (before.partial || before.roles.cache.size !== after.roles.cache.size || [...after.roles.cache.keys()].some((id) => !before.roles.cache.has(id))) {
    recordRoles(after);
  }
}

module.exports = { start, recordRoles, stripRanks, onMemberUpdate };
