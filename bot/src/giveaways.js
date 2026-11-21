const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const {
  EmbedBuilder, ActionRowBuilder, ButtonBuilder, ButtonStyle, MessageFlags, PermissionFlagsBits, ChannelType,
} = require('discord.js');
const linking = require('./linking');
const features = require('./features.json');

const cfg = features.giveaways;
const FILE = process.env.GIVEAWAY_FILE || path.join(__dirname, '..', 'state', 'giveaways.json');
const GOLD = 0xffd24b;
const GREY = 0x5d6475;
const TICK_MS = 10_000;
const KEEP_MS = 30 * 86_400_000;
const MAX_MS = 60 * 86_400_000;

let state = { next: 1, giveaways: {} };
let saveTimer = null;
const refreshTimers = new Map();
const ending = new Set();

function load() {
  try {
    const data = JSON.parse(fs.readFileSync(FILE, 'utf8'));
    if (data && typeof data.next === 'number' && data.giveaways) {
      state = data;
    }
  } catch (e) {
    if (e.code !== 'ENOENT') {
      console.error('[hawkeye-bot] could not read giveaways:', e.message);
    }
  }
}

function writeNow() {
  saveTimer = null;
  try {
    fs.mkdirSync(path.dirname(FILE), { recursive: true });
    const tmp = `${FILE}.tmp`;
    fs.writeFileSync(tmp, JSON.stringify(state));
    fs.renameSync(tmp, FILE);
  } catch (e) {
    console.error('[hawkeye-bot] could not save giveaways:', e.message);
  }
}

function save(now) {
  if (now) {
    if (saveTimer) {
      clearTimeout(saveTimer);
    }
    writeNow();
  } else if (!saveTimer) {
    saveTimer = setTimeout(writeNow, 1000);
  }
}

function ephemeral(interaction, content, components) {
  const msg = { content, flags: MessageFlags.Ephemeral, components: components || [], allowedMentions: { parse: [] } };
  return interaction.replied || interaction.deferred ? interaction.followUp(msg) : interaction.reply(msg);
}

function isHost(member) {
  if (!member) {
    return false;
  }
  if (member.permissions && (member.permissions.has(PermissionFlagsBits.Administrator) || member.permissions.has(PermissionFlagsBits.ManageGuild))) {
    return true;
  }
  return cfg.hostRoleIds.some((id) => member.roles.cache.has(id));
}

function parseDuration(text) {
  const clean = String(text || '').toLowerCase().replace(/\s+/g, '');
  if (!/^(\d+(w|d|h|m|s))+$/.test(clean)) {
    return 0;
  }
  const unit = { w: 604_800_000, d: 86_400_000, h: 3_600_000, m: 60_000, s: 1000 };
  let ms = 0;
  for (const [, n, u] of clean.matchAll(/(\d+)(w|d|h|m|s)/g)) {
    ms += Number(n) * unit[u];
  }
  return ms;
}

function ts(ms, style) {
  return `<t:${Math.floor(ms / 1000)}:${style}>`;
}

function requirementLines(g) {
  const lines = [];
  if (g.requireLink) {
    lines.push('• Linked Minecraft account (`/link`)');
  }
  if (g.roleId) {
    lines.push(`• The <@&${g.roleId}> role`);
  }
  return lines;
}

function view(g) {
  const embed = new EmbedBuilder().setTitle(g.prize.length > 250 ? `${g.prize.slice(0, 247)}...` : g.prize);
  const lines = [];
  if (g.description) {
    lines.push(g.description, '');
  }
  if (g.cancelled) {
    embed.setColor(GREY).setAuthor({ name: 'GIVEAWAY CANCELLED' });
    lines.push('This giveaway was cancelled.');
  } else if (g.ended) {
    embed.setColor(GREY).setAuthor({ name: 'GIVEAWAY ENDED' });
    lines.push(g.winnerIds.length ? `**Winner${g.winnerIds.length > 1 ? 's' : ''}:** ${g.winnerIds.map((id) => `<@${id}>`).join(', ')}` : '**No valid entries, no winner.**');
    lines.push(`**Ended:** ${ts(g.endedAt || g.endsAt, 'R')}`);
  } else {
    embed.setColor(GOLD).setAuthor({ name: '🎉 GIVEAWAY' });
    lines.push('Click **Enter** below to join!', '');
    lines.push(`**Ends:** ${ts(g.endsAt, 'R')} (${ts(g.endsAt, 'f')})`);
    lines.push(`**Winners:** ${g.winners}`);
  }
  lines.push(`**Entries:** ${g.entries.length}`);
  lines.push(`**Hosted by:** <@${g.hostId}>`);
  const req = requirementLines(g);
  if (req.length && !g.ended && !g.cancelled) {
    lines.push('', '**To enter you need:**', ...req);
  }
  embed.setDescription(lines.join('\n')).setFooter({ text: `Giveaway #${g.id}` }).setTimestamp(new Date(g.endsAt));
  const open = !g.ended && !g.cancelled;
  const row = new ActionRowBuilder().addComponents(
    new ButtonBuilder().setCustomId(`gw_enter:${g.id}`).setLabel(open ? 'Enter' : 'Ended').setEmoji('🎉')
      .setStyle(open ? ButtonStyle.Success : ButtonStyle.Secondary).setDisabled(!open),
  );
  return { embeds: [embed], components: [row], allowedMentions: { parse: [] } };
}

async function fetchMessage(client, g) {
  const channel = await client.channels.fetch(g.channelId).catch(() => null);
  if (!channel) {
    return { channel: null, message: null };
  }
  const message = await channel.messages.fetch(g.messageId).catch(() => null);
  return { channel, message };
}

function scheduleRefresh(client, g) {
  if (refreshTimers.has(g.id)) {
    return;
  }
  refreshTimers.set(g.id, setTimeout(async () => {
    refreshTimers.delete(g.id);
    const { message } = await fetchMessage(client, g);
    if (message) {
      await message.edit(view(g)).catch(() => {});
    }
  }, 5000));
}

async function eligible(guild, g, userId) {
  const member = await guild.members.fetch(userId).catch(() => null);
  if (!member) {
    return { ok: false };
  }
  if (g.roleId && !member.roles.cache.has(g.roleId)) {
    return { ok: false, why: `You need the <@&${g.roleId}> role to enter this giveaway.` };
  }
  let link = null;
  if (g.requireLink) {
    link = await linking.linkByDiscord(userId);
    if (!link) {
      return { ok: false, why: 'You need to link your Minecraft account first: run `/link` in game, then `/link <code>` here.' };
    }
  }
  return { ok: true, member, link };
}

function draw(pool, count) {
  const left = pool.slice();
  const picked = [];
  while (left.length && picked.length < count) {
    picked.push(left.splice(crypto.randomInt(left.length), 1)[0]);
  }
  return picked;
}

async function pickWinners(guild, g, count, exclude) {
  const pool = g.entries.filter((id) => !exclude.includes(id));
  const out = [];
  while (pool.length && out.length < count) {
    const [id] = draw(pool, 1);
    pool.splice(pool.indexOf(id), 1);
    const check = await eligible(guild, g, id);
    if (check.ok) {
      out.push({ id, player: check.link ? check.link.player_name : null });
    }
  }
  return out;
}

function winnerList(picked) {
  return picked.map((w) => `<@${w.id}>${w.player ? ` (**${w.player}**)` : ''}`).join(', ');
}

async function notify(client, g, picked) {
  const claim = cfg.claimChannelId ? `open a ticket in <#${cfg.claimChannelId}>` : 'open a support ticket';
  for (const w of picked) {
    const user = await client.users.fetch(w.id).catch(() => null);
    if (!user) {
      continue;
    }
    const embed = new EmbedBuilder().setColor(GOLD).setTitle('🎉 You won a GateMC giveaway!')
      .setDescription(`You won **${g.prize}**!\n\nStaff will get your prize to you${w.player ? ` on **${w.player}**` : ''}. If you don't hear back within a day, ${claim}.`);
    await user.send({ embeds: [embed] }).catch(() => {});
  }
}

async function finish(client, g, by) {
  if (g.ended || g.cancelled || ending.has(g.id)) {
    return null;
  }
  ending.add(g.id);
  try {
    const guild = await client.guilds.fetch(g.guildId);
    const picked = await pickWinners(guild, g, g.winners, []);
    g.ended = true;
    g.endedAt = Date.now();
    g.winnerIds = picked.map((w) => w.id);
    save(true);
    const { channel, message } = await fetchMessage(client, g);
    if (message) {
      await message.edit(view(g)).catch(() => {});
    }
    if (channel) {
      const text = picked.length
        ? `🎉 Congratulations ${winnerList(picked)}! You won **${g.prize}**!`
        : `Giveaway for **${g.prize}** ended with no valid entries, so there's no winner.`;
      await channel.send({ content: text, allowedMentions: { users: g.winnerIds }, reply: message ? { messageReference: message.id, failIfNotExists: false } : undefined }).catch(() => {});
    }
    await notify(client, g, picked);
    console.log(`[hawkeye-bot] giveaway #${g.id} ended${by ? ` by ${by}` : ''}: ${g.winnerIds.join(', ') || 'no winner'}`);
    return picked;
  } finally {
    ending.delete(g.id);
  }
}

function tick(client) {
  const now = Date.now();
  let pruned = false;
  for (const g of Object.values(state.giveaways)) {
    if (!g.ended && !g.cancelled && g.endsAt <= now) {
      finish(client, g).catch((e) => console.error(`[hawkeye-bot] giveaway #${g.id} end failed:`, e.message));
    } else if ((g.ended || g.cancelled) && now - (g.endedAt || g.endsAt) > KEEP_MS) {
      delete state.giveaways[g.id];
      pruned = true;
    }
  }
  if (pruned) {
    save();
  }
}

function start(client) {
  load();
  tick(client);
  setInterval(() => tick(client), TICK_MS).unref();
}

function commands(SlashCommandBuilder) {
  const id = (o) => o.setName('id').setDescription('Giveaway number (shown in its footer)').setRequired(true).setMinValue(1);
  return [
    new SlashCommandBuilder().setName('giveaway').setDescription('Run giveaways (staff)')
      .addSubcommand((s) => s.setName('start').setDescription('Start a giveaway')
        .addStringOption((o) => o.setName('prize').setDescription('What they win').setRequired(true).setMaxLength(200))
        .addStringOption((o) => o.setName('duration').setDescription('How long it runs, e.g. 30m, 2h, 1d12h').setRequired(true).setMaxLength(20))
        .addIntegerOption((o) => o.setName('winners').setDescription('How many winners (default 1)').setMinValue(1).setMaxValue(cfg.maxWinners))
        .addChannelOption((o) => o.setName('channel').setDescription('Where to post it (default here)').addChannelTypes(ChannelType.GuildText, ChannelType.GuildAnnouncement))
        .addStringOption((o) => o.setName('description').setDescription('Extra details shown on the giveaway').setMaxLength(1000))
        .addRoleOption((o) => o.setName('role').setDescription('Only members with this role can enter'))
        .addBooleanOption((o) => o.setName('require_link').setDescription(`Require a linked Minecraft account (default ${cfg.requireLink ? 'yes' : 'no'})`))
        .addBooleanOption((o) => o.setName('ping').setDescription('Ping the giveaway role when it starts')))
      .addSubcommand((s) => s.setName('end').setDescription('End a giveaway now and draw winners').addIntegerOption(id))
      .addSubcommand((s) => s.setName('reroll').setDescription('Draw new winners for an ended giveaway').addIntegerOption(id)
        .addUserOption((o) => o.setName('replace').setDescription('A winner to replace (e.g. they never claimed)'))
        .addIntegerOption((o) => o.setName('winners').setDescription('How many extra winners to draw (default 1)').setMinValue(1).setMaxValue(cfg.maxWinners)))
      .addSubcommand((s) => s.setName('cancel').setDescription('Cancel a giveaway without drawing').addIntegerOption(id))
      .addSubcommand((s) => s.setName('list').setDescription('List running giveaways')),
  ];
}

async function onStart(interaction) {
  const ms = parseDuration(interaction.options.getString('duration', true));
  if (ms < 60_000 || ms > MAX_MS) {
    return ephemeral(interaction, 'Duration must be between 1 minute and 60 days, written like `30m`, `2h` or `1d12h`.');
  }
  const channel = interaction.options.getChannel('channel') || interaction.channel;
  const me = interaction.guild.members.me;
  const perms = channel.permissionsFor(me);
  if (!perms || !perms.has([PermissionFlagsBits.ViewChannel, PermissionFlagsBits.SendMessages, PermissionFlagsBits.EmbedLinks])) {
    return ephemeral(interaction, `I can't post in <#${channel.id}>. I need View Channel, Send Messages and Embed Links there.`);
  }
  const role = interaction.options.getRole('role');
  const linkOpt = interaction.options.getBoolean('require_link');
  const g = {
    id: state.next,
    guildId: interaction.guildId,
    channelId: channel.id,
    messageId: null,
    prize: interaction.options.getString('prize', true).trim(),
    description: (interaction.options.getString('description') || '').trim(),
    winners: interaction.options.getInteger('winners') || 1,
    endsAt: Date.now() + ms,
    hostId: interaction.user.id,
    hostName: interaction.user.username,
    requireLink: linkOpt === null ? cfg.requireLink : linkOpt,
    roleId: role && role.id !== interaction.guildId ? role.id : '',
    entries: [],
    winnerIds: [],
    rerolled: [],
    ended: false,
    cancelled: false,
  };
  const ping = interaction.options.getBoolean('ping') && cfg.pingRoleId;
  const sent = await channel.send({ ...view(g), content: ping ? `<@&${cfg.pingRoleId}>` : undefined, allowedMentions: { roles: ping ? [cfg.pingRoleId] : [] } });
  g.messageId = sent.id;
  state.next += 1;
  state.giveaways[g.id] = g;
  save(true);
  console.log(`[hawkeye-bot] giveaway #${g.id} started by ${interaction.user.username}: ${g.prize}`);
  return ephemeral(interaction, `Giveaway #${g.id} is live in <#${channel.id}>, ending ${ts(g.endsAt, 'R')}. ${sent.url}`);
}

function find(interaction) {
  return state.giveaways[interaction.options.getInteger('id', true)];
}

async function onEnd(interaction) {
  const g = find(interaction);
  if (!g || g.ended || g.cancelled) {
    return ephemeral(interaction, 'There is no running giveaway with that number. `/giveaway list` shows them.');
  }
  await interaction.deferReply({ flags: MessageFlags.Ephemeral });
  const picked = await finish(interaction.client, g, interaction.user.username);
  return interaction.editReply(picked ? `Giveaway #${g.id} ended. ${picked.length ? `Winners: ${winnerList(picked)}` : 'No valid entries.'}` : 'That giveaway is already ending.');
}

async function onReroll(interaction) {
  const g = find(interaction);
  if (!g || !g.ended) {
    return ephemeral(interaction, 'You can only reroll a giveaway that has ended.');
  }
  await interaction.deferReply({ flags: MessageFlags.Ephemeral });
  const replace = interaction.options.getUser('replace');
  if (replace && !g.winnerIds.includes(replace.id)) {
    return interaction.editReply({ content: `<@${replace.id}> isn't a winner of giveaway #${g.id}.`, allowedMentions: { parse: [] } });
  }
  const count = replace ? 1 : interaction.options.getInteger('winners') || 1;
  const picked = await pickWinners(interaction.guild, g, count, g.winnerIds.concat(g.rerolled || []));
  if (!picked.length) {
    return interaction.editReply('Nobody else is eligible to win.');
  }
  if (replace) {
    g.rerolled = (g.rerolled || []).concat(replace.id);
    g.winnerIds = g.winnerIds.map((id) => (id === replace.id ? picked[0].id : id));
  } else {
    g.winnerIds = g.winnerIds.concat(picked.map((w) => w.id));
  }
  save(true);
  const { channel, message } = await fetchMessage(interaction.client, g);
  if (message) {
    await message.edit(view(g)).catch(() => {});
  }
  if (channel) {
    await channel.send({ content: `🔁 Reroll! Congratulations ${winnerList(picked)}, you won **${g.prize}**!`, allowedMentions: { users: g.winnerIds } }).catch(() => {});
  }
  await notify(interaction.client, g, picked);
  console.log(`[hawkeye-bot] giveaway #${g.id} rerolled by ${interaction.user.username}: ${g.winnerIds.join(', ')}`);
  return interaction.editReply(`Rerolled giveaway #${g.id}. New winner${picked.length > 1 ? 's' : ''}: ${winnerList(picked)}`);
}

async function onCancel(interaction) {
  const g = find(interaction);
  if (!g || g.ended || g.cancelled) {
    return ephemeral(interaction, 'There is no running giveaway with that number. `/giveaway list` shows them.');
  }
  g.cancelled = true;
  g.endedAt = Date.now();
  save(true);
  const { message } = await fetchMessage(interaction.client, g);
  if (message) {
    await message.edit(view(g)).catch(() => {});
  }
  console.log(`[hawkeye-bot] giveaway #${g.id} cancelled by ${interaction.user.username}`);
  return ephemeral(interaction, `Giveaway #${g.id} was cancelled.`);
}

function onList(interaction) {
  const running = Object.values(state.giveaways).filter((g) => !g.ended && !g.cancelled).sort((a, b) => a.endsAt - b.endsAt);
  if (!running.length) {
    return ephemeral(interaction, 'No giveaways are running.');
  }
  const lines = running.map((g) => `**#${g.id}** ${g.prize} in <#${g.channelId}>, ${g.entries.length} entries, ends ${ts(g.endsAt, 'R')}`);
  return ephemeral(interaction, lines.join('\n').slice(0, 1900));
}

async function onEnter(interaction, id) {
  const g = state.giveaways[id];
  if (!g || g.ended || g.cancelled) {
    return ephemeral(interaction, 'This giveaway has ended.');
  }
  if (g.entries.includes(interaction.user.id)) {
    const row = new ActionRowBuilder().addComponents(
      new ButtonBuilder().setCustomId(`gw_leave:${g.id}`).setLabel('Leave giveaway').setStyle(ButtonStyle.Danger),
    );
    return ephemeral(interaction, 'You\'re already entered. Good luck!', [row]);
  }
  await interaction.deferReply({ flags: MessageFlags.Ephemeral });
  const check = await eligible(interaction.guild, g, interaction.user.id);
  if (!check.ok) {
    return interaction.editReply({ content: check.why || 'You can\'t enter this giveaway.', allowedMentions: { parse: [] } });
  }
  if (g.ended || g.cancelled) {
    return interaction.editReply('This giveaway has ended.');
  }
  if (!g.entries.includes(interaction.user.id)) {
    g.entries.push(interaction.user.id);
    save();
    scheduleRefresh(interaction.client, g);
  }
  return interaction.editReply(`You're in! Winners are drawn ${ts(g.endsAt, 'R')}. Keep your DMs open so I can tell you if you win.`);
}

async function onLeave(interaction, id) {
  const g = state.giveaways[id];
  if (!g || g.ended || g.cancelled) {
    return ephemeral(interaction, 'This giveaway has ended.');
  }
  const at = g.entries.indexOf(interaction.user.id);
  if (at >= 0) {
    g.entries.splice(at, 1);
    save();
    scheduleRefresh(interaction.client, g);
  }
  return interaction.update({ content: 'You left the giveaway.', components: [] });
}

async function handle(interaction) {
  const cid = interaction.customId || '';
  if (interaction.isButton()) {
    if (cid.startsWith('gw_enter:')) {
      await onEnter(interaction, Number(cid.split(':')[1]));
      return true;
    }
    if (cid.startsWith('gw_leave:')) {
      await onLeave(interaction, Number(cid.split(':')[1]));
      return true;
    }
    return false;
  }
  if (!interaction.isChatInputCommand() || interaction.commandName !== 'giveaway') {
    return false;
  }
  if (!isHost(interaction.member)) {
    await ephemeral(interaction, 'Only staff managers and admins can run giveaways.');
    return true;
  }
  const sub = interaction.options.getSubcommand();
  const run = { start: onStart, end: onEnd, reroll: onReroll, cancel: onCancel, list: onList }[sub];
  await run(interaction);
  return true;
}

module.exports = { commands, handle, start, parseDuration, view, draw };
