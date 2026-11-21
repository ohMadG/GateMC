const {
  EmbedBuilder, ActionRowBuilder, ButtonBuilder, ButtonStyle, MessageFlags, PermissionFlagsBits,
} = require('discord.js');
const { pool } = require('./db');
const features = require('./features.json');
const wordfilter = require('./wordfilter');
const linkguard = require('./linkguard');

const cfg = features.moderation;
const REVIEWERS = features.staffRoleIds;
const COLORS = { critical: 0xff2e63, high: 0xff8c1a, medium: 0xf4b929, 'high-risk': 0xff2e63, ok: 0x00e08a, closed: 0x5d6475 };
const LABEL = { critical: '🔴 Critical', high: '🟠 High', medium: '🟡 Review', 'high-risk': '🔴 High-risk words' };
const recent = new Map();
const openByKey = new Map();

function isExempt(member) {
  if (!member) {
    return false;
  }
  if (member.permissions && (member.permissions.has(PermissionFlagsBits.Administrator) || member.permissions.has(PermissionFlagsBits.ManageMessages))) {
    return true;
  }
  return cfg.exemptRoleIds.concat(REVIEWERS).some((id) => member.roles.cache.has(id));
}

function isReviewer(member) {
  return Boolean(member && (member.permissions.has(PermissionFlagsBits.Administrator) || REVIEWERS.some((id) => member.roles.cache.has(id))));
}

function ageText(ms) {
  const d = ms / 86_400_000;
  if (d < 1 / 24) {
    return `${Math.max(1, Math.round(ms / 60_000))} min`;
  }
  if (d < 1) {
    return `${Math.round(d * 24)} h`;
  }
  if (d < 60) {
    return `${Math.round(d)} days`;
  }
  return `${Math.round(d / 30)} months`;
}

function isNew(member) {
  const now = Date.now();
  const joined = member && member.joinedTimestamp ? now - member.joinedTimestamp : 0;
  const created = member ? now - member.user.createdTimestamp : 0;
  return joined < cfg.newMemberDays * 86_400_000 || created < cfg.newAccountDays * 86_400_000;
}

function redact(row) {
  let out = String(row.content || '');
  row.urls.forEach((l, i) => {
    out = out.split(l.raw).join(`[link ${i + 1}]`);
  });
  out = out.replace(linkguard.URL_RE, '[link]').replace(/@(everyone|here)/g, '@​$1');
  return out.length > 900 ? `${out.slice(0, 900)}…` : out;
}

function card(row, member) {
  const closed = row.status !== 'open';
  const color = closed ? (['approved', 'trusted', 'dismissed'].includes(row.status) ? COLORS.ok : COLORS.closed) : COLORS[row.priority];
  const reasons = Array.isArray(row.reasons) ? row.reasons : [];
  const isLink = row.kind === 'link';
  const embed = new EmbedBuilder()
    .setColor(color)
    .setTitle(`${LABEL[row.priority] || row.priority}${isLink ? ' link' : ''}${reasons[0] ? ` · ${reasons[0]}` : ''}`.slice(0, 250))
    .setDescription(isLink ? (redact(row) ? `>>> ${redact(row)}` : '*no text, link only*') : `>>> ${String(row.content || '').replace(/@(everyone|here)/g, '@​$1').slice(0, 900)}`)
    .addFields(
      { name: 'Member', value: `<@${row.author_id}>\n${row.author_tag}\n\`${row.author_id}\``, inline: true },
      { name: 'Where', value: row.channel_id ? `<#${row.channel_id}>` : '-', inline: true },
      {
        name: 'Account',
        value: member ? `made ${ageText(Date.now() - member.user.createdTimestamp)} ago\njoined ${member.joinedTimestamp ? `${ageText(Date.now() - member.joinedTimestamp)} ago` : '?'}` : 'not in the server',
        inline: true,
      },
    );
  if (isLink) {
    embed.addFields({
      name: 'Links (hidden, press Reveal to see them)',
      value: row.urls.map((u, i) => `\`${i + 1}\` ${LABEL[u.level] || '🟢'} \`${u.defanged}\`\n   ${u.reasons.join(', ') || 'safe'}`).join('\n').slice(0, 1020),
    });
  } else {
    embed.addFields({ name: 'Matched', value: reasons.join(', ').slice(0, 1020) || '-' });
  }
  embed.addFields({ name: 'What the bot did', value: `${row.action}${row.hits > 1 ? `\nSeen ${row.hits} times` : ''}`.slice(0, 1020) })
    .setFooter({ text: closed ? `${row.status} by ${row.reviewed_by}` : `Review #${row.id}` })
    .setTimestamp(Number(row.created_at));
  const id = row.id;
  const first = new ActionRowBuilder();
  if (isLink) {
    first.addComponents(
      new ButtonBuilder().setCustomId(`mod:reveal:${id}`).setLabel('Reveal').setEmoji('👁️').setStyle(ButtonStyle.Secondary),
      new ButtonBuilder().setCustomId(`mod:approve:${id}`).setLabel(row.held ? 'Approve & repost' : 'Looks fine').setStyle(ButtonStyle.Success).setDisabled(closed),
      new ButtonBuilder().setCustomId(`mod:trust:${id}`).setLabel('Trust site').setStyle(ButtonStyle.Success).setDisabled(closed),
      new ButtonBuilder().setCustomId(`mod:remove:${id}`).setLabel(row.held ? 'Keep removed' : 'Remove').setStyle(ButtonStyle.Danger).setDisabled(closed),
    );
  } else {
    first.addComponents(
      new ButtonBuilder().setCustomId(`mod:dismiss:${id}`).setLabel('No action').setStyle(ButtonStyle.Success).setDisabled(closed),
      new ButtonBuilder().setCustomId(`mod:actioned:${id}`).setLabel('Handled').setStyle(ButtonStyle.Secondary).setDisabled(closed),
    );
  }
  const second = new ActionRowBuilder().addComponents(
    new ButtonBuilder().setCustomId(`mod:to1h:${id}`).setLabel('Timeout 1h').setStyle(ButtonStyle.Secondary),
    new ButtonBuilder().setCustomId(`mod:to1d:${id}`).setLabel('Timeout 1 day').setStyle(ButtonStyle.Secondary),
  );
  if (isLink) {
    second.addComponents(new ButtonBuilder().setCustomId(`mod:block:${id}`).setLabel('Block site').setStyle(ButtonStyle.Danger));
  }
  second.addComponents(new ButtonBuilder().setCustomId(`mod:ban:${id}`).setLabel('Ban').setStyle(ButtonStyle.Danger));
  return { embeds: [embed], components: [first, second], allowedMentions: { parse: [] } };
}

async function alertChannel(client) {
  try {
    return await client.channels.fetch(cfg.alertChannelId);
  } catch {
    return null;
  }
}

async function record(client, message, member, data) {
  const key = `${data.kind}|${message.author.id}|${data.key}`;
  const prev = openByKey.get(key);
  if (prev && Date.now() - prev.t < 600_000) {
    const { rows: [row] } = await pool.query(
      `UPDATE bot_mod_reviews SET hits = hits + 1, action = $2, held = held OR $3 WHERE id = $1 AND status = 'open' RETURNING *`,
      [prev.id, data.action, data.held],
    );
    if (row) {
      prev.t = Date.now();
      const ch = await alertChannel(client);
      if (ch && row.alert_message_id) {
        ch.messages.edit(row.alert_message_id, card(row, member)).catch(() => {});
      }
      return;
    }
  }
  const { rows: [row] } = await pool.query(
    `INSERT INTO bot_mod_reviews (kind, created_at, channel_id, message_id, author_id, author_tag, content, urls, priority, reasons, action, held)
     VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12) RETURNING *`,
    [data.kind, Date.now(), message.channelId, message.id, message.author.id, message.author.tag, String(message.content || '').slice(0, 4000),
      JSON.stringify(data.urls || []), data.level, JSON.stringify(data.reasons), data.action, data.held],
  );
  openByKey.set(key, { id: row.id, t: Date.now() });
  console.log(`[hawkeye-bot] ${data.kind} review #${row.id}: ${message.author.tag} in #${message.channel.name} (${data.level})`);
  const ch = await alertChannel(client);
  if (!ch) {
    return;
  }
  const ping = (data.level === 'critical' || data.level === 'high-risk') && cfg.pingRoleId
    ? { content: `<@&${cfg.pingRoleId}>`, allowedMentions: { roles: [cfg.pingRoleId] } } : {};
  const sent = await ch.send({ ...card(row, member), ...ping }).catch((e) => console.error('[hawkeye-bot] mod alert failed:', e.message));
  if (sent) {
    await pool.query('UPDATE bot_mod_reviews SET alert_message_id = $2 WHERE id = $1', [row.id, sent.id]);
  }
}

async function tell(message, text) {
  try {
    await message.author.send(text);
  } catch {
    try {
      const note = await message.channel.send({ content: `<@${message.author.id}> ${text}`, allowedMentions: { users: [message.author.id] } });
      setTimeout(() => note.delete().catch(() => {}), 12_000);
    } catch {
    }
  }
}

function trackBurst(message, scored) {
  const now = Date.now();
  let worst = 0;
  for (const l of scored) {
    const k = `${message.author.id}|${l.host}${l.path}`;
    const seen = (recent.get(k) || []).filter((e) => now - e.t < 120_000);
    seen.push({ t: now, channel: message.channelId, id: message.id });
    recent.set(k, seen);
    worst = Math.max(worst, new Set(seen.map((e) => e.channel)).size);
  }
  if (recent.size > 5000) {
    for (const [k, v] of recent) {
      if (!v.some((e) => now - e.t < 120_000)) {
        recent.delete(k);
      }
    }
  }
  return worst;
}

async function checkLinks(client, message, member, edited) {
  const result = await linkguard.analyze(client, message.guild.id, message, cfg);
  if (!result) {
    return false;
  }
  const { level, scored } = result;
  const fresh = isNew(member);
  if (fresh && level === 'medium') {
    scored[0].reasons.push('new member');
  }
  const spread = trackBurst(message, scored);
  const hold = level !== 'medium' || (cfg.holdUnknownForNew && fresh) || spread >= 3;
  const actions = [];
  let held = false;
  if (hold) {
    try {
      await message.delete();
      held = true;
      actions.push('hid the message');
    } catch (e) {
      actions.push(`could not hide it: ${e.message}`);
    }
  } else {
    actions.push('left the message up, staff check only');
  }
  if (spread >= 3) {
    actions.push(`same link in ${spread} channels in 2 minutes (hacked account?)`);
    for (const l of scored) {
      for (const e of recent.get(`${message.author.id}|${l.host}${l.path}`) || []) {
        if (e.id !== message.id) {
          const ch = message.guild.channels.cache.get(e.channel);
          if (ch) {
            ch.messages.delete(e.id).catch(() => {});
          }
        }
      }
    }
  }
  if (held || level !== 'critical') {
    tell(message, held
      ? `your message in <#${message.channelId}> had a link our safety check wants staff to look at first, so it's hidden for now. If it's fine, staff will put it back.`
      : `the link you posted in <#${message.channelId}> was sent to staff for a quick check. Please only share links you trust.`);
  }
  const urls = scored.map((s) => ({ raw: s.raw, url: s.url, host: s.host, defanged: s.defanged, level: s.level, reasons: s.reasons, how: s.how }));
  await record(client, message, member, {
    kind: 'link', key: scored.map((s) => s.host + s.path).sort().join(','), level, urls,
    reasons: [...new Set(scored.flatMap((s) => s.reasons))], action: `${edited ? 'edited message · ' : ''}${actions.join(' · ')}`, held,
  });
  return held;
}

async function checkWords(client, message, member, edited) {
  const hit = wordfilter.check(message.content);
  if (!hit) {
    return false;
  }
  let removed = false;
  try {
    await message.delete();
    removed = true;
  } catch (e) {
    console.error('[hawkeye-bot] could not delete filtered message:', e.message);
  }
  tell(message, `your message in <#${message.channelId}> was removed because it contains "${hit.word}". Please keep chat friendly.`);
  if (hit.level === 'high-risk' || cfg.alertBannedWords) {
    await record(client, message, member, {
      kind: 'word', key: hit.word, level: hit.level === 'high-risk' ? 'high-risk' : 'medium', reasons: [`"${hit.word}"`],
      action: `${edited ? 'edited message · ' : ''}${removed ? 'removed the message' : 'could not remove the message'}`, held: removed,
    });
  }
  return true;
}

async function inspect(client, guildId, message, edited) {
  if (!cfg.enabled || !message.guild || message.guild.id !== guildId || message.author.bot || message.webhookId || message.system) {
    return;
  }
  if (cfg.ignoreChannelIds.includes(message.channelId) || (message.channel.parentId && cfg.ignoreChannelIds.includes(message.channel.parentId))) {
    return;
  }
  const member = message.member || await message.guild.members.fetch(message.author.id).catch(() => null);
  if (isExempt(member)) {
    return;
  }
  if (cfg.wordFilter && await checkWords(client, message, member, edited)) {
    return;
  }
  if (cfg.linkGuard) {
    await checkLinks(client, message, member, edited);
  }
}

async function timeout(member, minutes, reason) {
  if (!member) {
    return 'not in the server';
  }
  if (!member.moderatable) {
    return 'could not time out (role too high)';
  }
  try {
    await member.timeout(minutes * 60_000, reason);
    return `timed out ${minutes >= 60 ? `${minutes / 60} h` : `${minutes} min`}`;
  } catch (e) {
    return `timeout failed: ${e.message}`;
  }
}

async function getRow(id) {
  const { rows } = await pool.query('SELECT * FROM bot_mod_reviews WHERE id = $1', [id]);
  return rows[0] || null;
}

async function closeRow(row, status, by, note) {
  const { rows } = await pool.query(
    'UPDATE bot_mod_reviews SET status = $2, reviewed_by = $3, reviewed_at = $4, action = action || $5 WHERE id = $1 RETURNING *',
    [row.id, status, by, Date.now(), note || ''],
  );
  for (const [k, v] of openByKey) {
    if (v.id === row.id) {
      openByKey.delete(k);
    }
  }
  return rows[0];
}

async function repost(client, row) {
  const ch = await client.channels.fetch(row.channel_id).catch(() => null);
  if (!ch) {
    return 'channel is gone';
  }
  const member = await ch.guild.members.fetch(row.author_id).catch(() => null);
  const name = member ? member.displayName : row.author_tag;
  try {
    await ch.send({
      content: `**${name}** said (approved by staff):\n${String(row.content || '').slice(0, 1900)}`,
      allowedMentions: { parse: [] },
    });
    return null;
  } catch (e) {
    return e.message;
  }
}

function ephemeral(interaction, content) {
  const msg = { content, flags: MessageFlags.Ephemeral };
  return interaction.replied || interaction.deferred ? interaction.followUp(msg) : interaction.reply(msg);
}

async function handle(interaction) {
  if (!interaction.isButton() || !interaction.customId.startsWith('mod:')) {
    return false;
  }
  const [, act, idText] = interaction.customId.split(':');
  if (!isReviewer(interaction.member)) {
    await ephemeral(interaction, 'Only Mod and up can use these buttons.');
    return true;
  }
  const row = await getRow(Number(idText));
  if (!row) {
    await ephemeral(interaction, 'That review is gone.');
    return true;
  }
  const by = interaction.user.username;
  const member = await interaction.guild.members.fetch(row.author_id).catch(() => null);
  const refresh = (updated) => interaction.message.edit(card(updated, member)).catch(() => {});
  if (act === 'reveal') {
    const lines = row.urls.map((u, i) => `**${i + 1}.** \`${u.url.replace(/`/g, 'ˋ')}\``).join('\n');
    await ephemeral(interaction, `Only you can see this. Links stay unclickable on purpose; don't open critical ones in your normal browser.\n${lines}\n**Full message:**\n\`\`\`\n${String(row.content || '').replace(/`/g, 'ˋ').slice(0, 1400)}\n\`\`\``);
    return true;
  }
  if (act === 'ban') {
    if (!interaction.member.permissions.has(PermissionFlagsBits.BanMembers)) {
      await ephemeral(interaction, 'You need the Ban Members permission for that.');
      return true;
    }
    try {
      await interaction.guild.members.ban(row.author_id, { reason: `Review #${row.id} by ${by}`, deleteMessageSeconds: 3600 });
    } catch (e) {
      await ephemeral(interaction, `Ban failed: ${e.message}`);
      return true;
    }
    await interaction.deferUpdate();
    await refresh(await closeRow(row, 'banned', by, ` · banned by ${by}`));
    return true;
  }
  if (act === 'to1h' || act === 'to1d') {
    const t = await timeout(member, act === 'to1h' ? 60 : 1440, `Review #${row.id} by ${by}`);
    await pool.query('UPDATE bot_mod_reviews SET action = action || $2 WHERE id = $1', [row.id, ` · ${t} by ${by}`]);
    await interaction.deferUpdate();
    await refresh(await getRow(row.id));
    return true;
  }
  if (act === 'block') {
    const top = row.urls.slice().sort((a, b) => linkguard.PRIORITY[b.level] - linkguard.PRIORITY[a.level])[0];
    if (!top || top.host === 'attachment') {
      await ephemeral(interaction, 'Files can\'t be blocked by site.');
      return true;
    }
    const host = linkguard.registrable(top.host);
    await linkguard.setHost(host, 'blocked', by);
    await ephemeral(interaction, `**${host.replace(/\./g, '[.]')}** is now blocked. Anyone posting it gets the critical treatment.`);
    return true;
  }
  if (row.status !== 'open') {
    await ephemeral(interaction, `Already ${row.status} by ${row.reviewed_by}.`);
    return true;
  }
  if (act === 'approve' || act === 'trust') {
    if (act === 'trust') {
      for (const h of new Set(row.urls.filter((u) => u.host !== 'attachment').map((u) => linkguard.registrable(u.host)))) {
        await linkguard.setHost(h, 'safe', by);
      }
    }
    let note = '';
    if (row.held) {
      const err = await repost(interaction.client, row);
      note = err ? ` · repost failed: ${err}` : ` · reposted by ${by}`;
    }
    await interaction.deferUpdate();
    await refresh(await closeRow(row, act === 'trust' ? 'trusted' : 'approved', by, note));
    return true;
  }
  if (act === 'remove') {
    if (!row.held && row.message_id) {
      const ch = await interaction.client.channels.fetch(row.channel_id).catch(() => null);
      if (ch) {
        await ch.messages.delete(row.message_id).catch(() => {});
      }
    }
    await interaction.deferUpdate();
    await refresh(await closeRow(row, 'removed', by));
    return true;
  }
  if (act === 'dismiss' || act === 'actioned') {
    await interaction.deferUpdate();
    await refresh(await closeRow(row, act === 'dismiss' ? 'dismissed' : 'handled', by));
    return true;
  }
  return false;
}

function start(client, guildId) {
  if (!cfg.enabled) {
    return;
  }
  wordfilter.loadLists();
  wordfilter.loadDictionary();
  client.on('messageCreate', (m) => inspect(client, guildId, m, false).catch((e) => console.error('[hawkeye-bot] moderation check failed:', e.message)));
  client.on('messageUpdate', (oldM, newM) => {
    if (!newM.content || (oldM && oldM.content === newM.content)) {
      return;
    }
    inspect(client, guildId, newM, true).catch((e) => console.error('[hawkeye-bot] moderation edit check failed:', e.message));
  });
  console.log(`[hawkeye-bot] moderation on: word filter ${cfg.wordFilter ? 'on' : 'off'}, link guard ${cfg.linkGuard ? 'on' : 'off'}`);
}

module.exports = { start, handle, isExempt };
