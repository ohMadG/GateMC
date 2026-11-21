const {
  EmbedBuilder, ActionRowBuilder, ButtonBuilder, ButtonStyle, ChannelType, PermissionFlagsBits, MessageFlags, AttachmentBuilder,
} = require('discord.js');
const fs = require('fs');
const path = require('path');
const { pool } = require('./db');
const features = require('./features.json');
const { buildTranscript } = require('./transcript');

const cfg = features.tickets;
const STAFF = features.staffRoleIds;
const CATEGORIES = cfg.categories;
const MAX_MESSAGES = 5000;
const DELAY_MS = (cfg.closeDelaySeconds || 10) * 1000;
const PURPLE = 0x8b5cf6;
const FILES_DIR = process.env.TICKET_FILES_DIR || '/app/ticket-files';
const MAX_FILE_BYTES = 25 * 1024 * 1024;
const MAX_TOTAL_BYTES = 250 * 1024 * 1024;
const KEEP_DAYS = Math.max(1, Number(cfg.keepDays || 30));

function isStaff(member) {
  return Boolean(member && member.roles && STAFF.some((id) => member.roles.cache.has(id)));
}

function ephemeral(interaction, content) {
  const msg = { content, flags: MessageFlags.Ephemeral };
  return interaction.replied || interaction.deferred ? interaction.followUp(msg) : interaction.reply(msg);
}

function panel() {
  const embed = new EmbedBuilder()
    .setColor(PURPLE)
    .setTitle('🎫 Need help? Open a ticket')
    .setDescription('Click a button below to open a **private ticket** that only you and our staff can see.\n\n'
      + Object.values(CATEGORIES).map((c) => `${c.emoji} **${c.label}**\n${c.line}`).join('\n\n'))
    .addFields({
      name: 'Before you open one',
      value: '• You can have one open ticket at a time\n• Include your Minecraft username\n• Please be patient, a staff member will be with you soon',
    })
    .setFooter({ text: 'GateMC Support' });
  const row = new ActionRowBuilder().addComponents(Object.entries(CATEGORIES).map(([key, c]) => new ButtonBuilder()
    .setCustomId(`ticket_open:${key}`).setLabel(c.label).setEmoji(c.emoji)
    .setStyle(ButtonStyle[c.style] || ButtonStyle.Primary)));
  return { channelId: cfg.panelChannelId, payload: { embeds: [embed], components: [row] } };
}

function controls(state) {
  const claimed = Boolean(state.claimedBy);
  const closed = state.status === 'closed';
  const row = new ActionRowBuilder().addComponents(
    new ButtonBuilder().setCustomId('ticket_claim').setStyle(ButtonStyle.Secondary)
      .setLabel(claimed ? `Claimed by ${state.claimedBy}` : 'Claim').setDisabled(claimed || closed),
    new ButtonBuilder().setCustomId('ticket_close').setStyle(ButtonStyle.Danger)
      .setLabel(closed ? 'Closed' : 'Close Ticket').setDisabled(closed),
  );
  return [row];
}

async function ticketByChannel(channelId) {
  const { rows } = await pool.query('SELECT * FROM bot_tickets WHERE channel_id = $1', [channelId]);
  return rows[0] || null;
}

async function openTicket(interaction, key) {
  const category = CATEGORIES[key];
  if (!category) {
    return ephemeral(interaction, 'Unknown ticket type.');
  }
  const { rows: open } = await pool.query("SELECT channel_id FROM bot_tickets WHERE discord_id = $1 AND status = 'open'", [interaction.user.id]);
  if (open[0]) {
    return ephemeral(interaction, `You already have an open ticket: <#${open[0].channel_id}>`);
  }
  await interaction.deferReply({ flags: MessageFlags.Ephemeral });
  let row;
  try {
    ({ rows: [row] } = await pool.query(
      'INSERT INTO bot_tickets (category, discord_id, discord_name) VALUES ($1, $2, $3) RETURNING *',
      [key, interaction.user.id, interaction.user.username],
    ));
  } catch (e) {
    if (e.code === '23505') {
      return interaction.editReply('You already have an open ticket.');
    }
    throw e;
  }
  const guild = interaction.guild;
  const allow = [PermissionFlagsBits.ViewChannel, PermissionFlagsBits.SendMessages, PermissionFlagsBits.ReadMessageHistory,
    PermissionFlagsBits.AttachFiles, PermissionFlagsBits.EmbedLinks];
  let channel;
  try {
    channel = await guild.channels.create({
      name: `ticket-${row.id}-${interaction.user.username.toLowerCase().replace(/[^a-z0-9]/g, '').slice(0, 20) || 'user'}`,
      type: ChannelType.GuildText,
      parent: guild.channels.cache.has(cfg.categoryId) ? cfg.categoryId : null,
      topic: `${category.label} ticket #${row.id} for ${interaction.user.username} (${interaction.user.id})`,
      permissionOverwrites: [
        { id: guild.roles.everyone.id, deny: [PermissionFlagsBits.ViewChannel] },
        { id: interaction.client.user.id, allow: [...allow, PermissionFlagsBits.ManageChannels] },
        { id: interaction.user.id, allow },
        ...STAFF.filter((id) => guild.roles.cache.has(id)).map((id) => ({ id, allow: [...allow, PermissionFlagsBits.ManageMessages] })),
      ],
    });
  } catch (e) {
    await pool.query('DELETE FROM bot_tickets WHERE id = $1', [row.id]);
    console.error('[hawkeye-bot] ticket channel create failed:', e.message);
    return interaction.editReply(`Couldn't create your ticket (${e.message}). Please tell a staff member.`);
  }
  await pool.query('UPDATE bot_tickets SET channel_id = $2 WHERE id = $1', [row.id, channel.id]);
  const embed = new EmbedBuilder()
    .setColor(category.color)
    .setTitle(`${category.emoji} ${category.label}`)
    .setDescription(`${category.prompt}\n\nA staff member will claim this ticket shortly.`)
    .setFooter({ text: `Ticket #${row.id}` });
  await channel.send({
    content: `<@${interaction.user.id}>`,
    embeds: [embed],
    components: controls({ status: 'open' }),
    allowedMentions: { users: [interaction.user.id] },
  });
  await log(interaction.client, new EmbedBuilder().setColor(category.color).setTitle(`Ticket #${row.id} opened`)
    .setDescription(`${category.emoji} ${category.label}\nBy <@${interaction.user.id}> (${interaction.user.username})\n<#${channel.id}>`)
    .setTimestamp());
  console.log(`[hawkeye-bot] ticket #${row.id} (${key}) opened by ${interaction.user.username}`);
  return interaction.editReply(`Ticket created: <#${channel.id}>`);
}

async function claimTicket(interaction) {
  if (!isStaff(interaction.member)) {
    return ephemeral(interaction, 'Only staff can claim tickets.');
  }
  const { rows } = await pool.query(
    "UPDATE bot_tickets SET claimed_by = $2, claimed_by_id = $3 WHERE channel_id = $1 AND status = 'open' AND claimed_by IS NULL RETURNING *",
    [interaction.channelId, interaction.user.username, interaction.user.id],
  );
  if (!rows[0]) {
    return ephemeral(interaction, 'This ticket is already claimed or closed.');
  }
  await interaction.update({ components: controls({ status: 'open', claimedBy: interaction.user.username }) });
  await interaction.channel.send(`🙋 <@${interaction.user.id}> has claimed this ticket.`);
  return log(interaction.client, new EmbedBuilder().setColor(PURPLE).setTitle(`Ticket #${rows[0].id} claimed`)
    .setDescription(`By <@${interaction.user.id}> in <#${interaction.channelId}>`).setTimestamp());
}

async function saveAttachments(ticketId, all) {
  const dir = path.join(FILES_DIR, String(ticketId));
  const saved = new Map();
  let total = 0;
  for (const m of all) {
    for (const a of m.attachments.values()) {
      if (a.size > MAX_FILE_BYTES || total + a.size > MAX_TOTAL_BYTES) {
        saved.set(a.id, { error: 'too large to keep' });
        continue;
      }
      try {
        const res = await fetch(a.url);
        if (!res.ok) {
          throw new Error(`HTTP ${res.status}`);
        }
        const file = `${m.id}-${a.id}-${a.name.replace(/[^\w.-]/g, '_')}`.slice(0, 180);
        fs.mkdirSync(dir, { recursive: true });
        fs.writeFileSync(path.join(dir, file), Buffer.from(await res.arrayBuffer()));
        total += a.size;
        saved.set(a.id, { file });
      } catch (e) {
        saved.set(a.id, { error: e.message });
      }
    }
  }
  return saved;
}

async function archive(channel, ticketId) {
  const all = [];
  let before;
  while (all.length < MAX_MESSAGES) {
    const batch = await channel.messages.fetch({ limit: 100, before });
    if (!batch.size) {
      break;
    }
    all.push(...batch.values());
    before = batch.last().id;
  }
  all.reverse();
  const saved = await saveAttachments(ticketId, all);
  return all.map((m) => ({
    id: m.id,
    createdAt: m.createdTimestamp,
    author: {
      id: m.author.id, username: m.author.username, name: (m.member && m.member.displayName) || m.author.globalName || m.author.username,
      avatar: m.author.displayAvatarURL({ size: 64 }), bot: Boolean(m.author.bot),
    },
    content: m.content || '',
    attachments: [...m.attachments.values()].map((a) => ({
      name: a.name, url: a.url, size: a.size, contentType: a.contentType || '', ...(saved.get(a.id) || {}),
    })),
    embeds: m.embeds.map((e) => ({
      title: e.title || '', description: e.description || '', color: e.hexColor || null,
      fields: (e.fields || []).map((f) => ({ name: f.name, value: f.value })), footer: e.footer ? e.footer.text : '',
    })),
  }));
}

async function finish(client, channel, ticket, closedBy) {
  let messages;
  try {
    messages = await archive(channel, ticket.id);
    await pool.query('UPDATE bot_tickets SET transcript = $2, message_count = $3 WHERE id = $1',
      [ticket.id, JSON.stringify(messages), messages.length]);
  } catch (e) {
    console.error(`[hawkeye-bot] ticket #${ticket.id} transcript failed:`, e.message);
    await channel.send(`⚠️ The transcript couldn't be saved (${e.message}), so this channel is being kept.`).catch(() => {});
    return;
  }
  const category = CATEGORIES[ticket.category] || { label: ticket.category, emoji: '🎫', color: PURPLE };
  const html = buildTranscript({ ticket, category, channelName: channel.name, messages });
  const file = new AttachmentBuilder(Buffer.from(html, 'utf8'), { name: `ticket-${ticket.id}.html` });
  const participants = [...new Set(messages.filter((m) => !m.author.bot).map((m) => `<@${m.author.id}>`))];
  const summary = new EmbedBuilder()
    .setColor(category.color)
    .setTitle(`Ticket #${ticket.id} closed`)
    .addFields(
      { name: 'Type', value: `${category.emoji} ${category.label}`, inline: true },
      { name: 'Opened by', value: `<@${ticket.discord_id}>`, inline: true },
      { name: 'Claimed by', value: ticket.claimed_by_id ? `<@${ticket.claimed_by_id}>` : 'Nobody', inline: true },
      { name: 'Closed by', value: closedBy, inline: true },
      { name: 'Messages', value: String(messages.length), inline: true },
      { name: 'Participants', value: participants.join(' ').slice(0, 1024) || 'None', inline: false },
    )
    .setTimestamp();
  const links = cfg.panelUrl ? [new ActionRowBuilder().addComponents(new ButtonBuilder().setStyle(ButtonStyle.Link)
    .setLabel(`View in panel (${KEEP_DAYS} days)`).setURL(`${cfg.panelUrl.replace(/\/$/, '')}/tickets/${ticket.id}`))] : [];
  let posted = false;
  try {
    const target = await client.channels.fetch(cfg.transcriptChannelId);
    await target.send({ embeds: [summary], files: [file], components: links });
    posted = true;
  } catch (e) {
    console.error(`[hawkeye-bot] ticket #${ticket.id} transcript post failed:`, e.message);
  }
  await log(client, summary, links);
  try {
    const user = await client.users.fetch(ticket.discord_id);
    await user.send({
      embeds: [new EmbedBuilder().setColor(category.color).setTitle(`Your GateMC ticket #${ticket.id} was closed`)
        .setDescription('Here is a copy of the conversation. Open a new ticket any time if you need more help.')],
      files: [new AttachmentBuilder(Buffer.from(html, 'utf8'), { name: `ticket-${ticket.id}.html` })],
    });
  } catch (e) {
    console.log(`[hawkeye-bot] could not DM ticket #${ticket.id} transcript: ${e.message}`);
  }
  if (!posted) {
    await channel.send('⚠️ The transcript is saved, but it couldn\'t be posted to the transcripts channel, so this channel is being kept.').catch(() => {});
    return;
  }
  await channel.delete(`Ticket #${ticket.id} closed by ${closedBy}`).catch((e) => console.error('[hawkeye-bot] ticket channel delete failed:', e.message));
}

async function closeTicket(interaction) {
  const ticket = await ticketByChannel(interaction.channelId);
  if (!ticket) {
    return ephemeral(interaction, 'This isn\'t a ticket channel.');
  }
  const opener = ticket.discord_id === interaction.user.id;
  if (!opener && !isStaff(interaction.member)) {
    return ephemeral(interaction, 'Only the ticket opener or staff can close this ticket.');
  }
  const { rows } = await pool.query(
    "UPDATE bot_tickets SET status = 'closed', closed_by = $2, closed_at = now() WHERE id = $1 AND status = 'open' RETURNING *",
    [ticket.id, interaction.user.username],
  );
  if (!rows[0]) {
    return ephemeral(interaction, 'This ticket is already closed.');
  }
  const closed = rows[0];
  if (interaction.isButton()) {
    await interaction.update({ components: controls({ status: 'closed', claimedBy: closed.claimed_by }) });
  } else {
    await interaction.reply({ content: 'Closing this ticket.', flags: MessageFlags.Ephemeral });
  }
  const channel = interaction.channel;
  await channel.permissionOverwrites.edit(closed.discord_id, { SendMessages: false }).catch(() => {});
  await channel.setName(`closed-${channel.name}`.slice(0, 100)).catch(() => {});
  await channel.send(`🔒 Ticket closed by **${interaction.user.username}**. A transcript is being saved and this channel will be deleted in ${DELAY_MS / 1000} seconds.`);
  setTimeout(() => {
    finish(interaction.client, channel, closed, `<@${interaction.user.id}>`).catch((e) => console.error('[hawkeye-bot] ticket finish failed:', e.message));
  }, DELAY_MS);
  return null;
}

async function addMember(interaction, add) {
  const ticket = await ticketByChannel(interaction.channelId);
  if (!ticket || ticket.status !== 'open') {
    return ephemeral(interaction, 'Use this inside an open ticket.');
  }
  if (!isStaff(interaction.member)) {
    return ephemeral(interaction, 'Only staff can change who is in a ticket.');
  }
  const user = interaction.options.getUser('user', true);
  if (add) {
    await interaction.channel.permissionOverwrites.edit(user.id, {
      ViewChannel: true, SendMessages: true, ReadMessageHistory: true, AttachFiles: true, EmbedLinks: true,
    });
    return interaction.reply({ content: `Added <@${user.id}> to this ticket.`, allowedMentions: { users: [user.id] } });
  }
  if (user.id === ticket.discord_id) {
    return ephemeral(interaction, 'You can\'t remove the person who opened the ticket.');
  }
  await interaction.channel.permissionOverwrites.delete(user.id).catch(() => {});
  return interaction.reply({ content: `Removed **${user.username}** from this ticket.` });
}

async function log(client, embed, components) {
  if (!cfg.logChannelId) {
    return;
  }
  try {
    const channel = await client.channels.fetch(cfg.logChannelId);
    await channel.send({ embeds: [embed], components: components || [], allowedMentions: { parse: [] } });
  } catch (e) {
    console.error('[hawkeye-bot] ticket log failed:', e.message);
  }
}

function commands(SlashCommandBuilder) {
  return [
    new SlashCommandBuilder().setName('ticket').setDescription('Manage the ticket you are in')
      .addSubcommand((s) => s.setName('close').setDescription('Close this ticket'))
      .addSubcommand((s) => s.setName('add').setDescription('Add a member to this ticket (staff)')
        .addUserOption((o) => o.setName('user').setDescription('Member to add').setRequired(true)))
      .addSubcommand((s) => s.setName('remove').setDescription('Remove a member from this ticket (staff)')
        .addUserOption((o) => o.setName('user').setDescription('Member to remove').setRequired(true))),
  ];
}

async function handle(interaction) {
  if (interaction.isButton()) {
    if (interaction.customId.startsWith('ticket_open:')) {
      await openTicket(interaction, interaction.customId.slice('ticket_open:'.length));
      return true;
    }
    if (interaction.customId === 'ticket_claim') {
      await claimTicket(interaction);
      return true;
    }
    if (interaction.customId === 'ticket_close') {
      await closeTicket(interaction);
      return true;
    }
    return false;
  }
  if (interaction.isChatInputCommand() && interaction.commandName === 'ticket') {
    const sub = interaction.options.getSubcommand();
    if (sub === 'close') {
      await closeTicket(interaction);
    } else {
      await addMember(interaction, sub === 'add');
    }
    return true;
  }
  return false;
}

async function purge() {
  try {
    const { rows } = await pool.query(
      "DELETE FROM bot_tickets WHERE status = 'closed' AND closed_at < now() - make_interval(days => $1::int) RETURNING id",
      [KEEP_DAYS],
    );
    for (const { id } of rows) {
      fs.rmSync(path.join(FILES_DIR, String(id)), { recursive: true, force: true });
    }
    if (rows.length) {
      console.log(`[hawkeye-bot] purged ${rows.length} tickets older than ${KEEP_DAYS} days`);
    }
  } catch (e) {
    console.error('[hawkeye-bot] ticket purge failed:', e.message);
  }
}

function startPurge() {
  purge();
  setInterval(purge, 60 * 60_000).unref();
}

module.exports = { panel, handle, commands, startPurge };
