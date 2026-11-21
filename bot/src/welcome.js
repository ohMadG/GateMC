const { EmbedBuilder } = require('discord.js');
const features = require('./features.json');

const cfg = features.welcome;
const DANGEROUS = ['Administrator', 'ManageGuild', 'ManageRoles', 'ManageChannels', 'BanMembers', 'KickMembers', 'ManageMessages',
  'ManageWebhooks', 'MentionEveryone', 'ModerateMembers'];

function fill(text, member) {
  const vars = {
    user: `<@${member.id}>`,
    username: member.user.username,
    displayname: member.displayName || member.user.username,
    server: member.guild.name,
    membercount: member.guild.memberCount.toLocaleString('en-US'),
  };
  return String(text || '').replace(/\{(\w+)\}/g, (m, key) => (key in vars ? vars[key] : m));
}

function message(member) {
  const embed = new EmbedBuilder()
    .setColor(cfg.color)
    .setTitle(fill(cfg.title, member))
    .setDescription(fill(cfg.description, member))
    .setThumbnail(member.user.displayAvatarURL({ size: 256 }));
  if (cfg.footer) {
    embed.setFooter({ text: fill(cfg.footer, member) });
  }
  return { content: `<@${member.id}>`, embeds: [embed], allowedMentions: { users: [member.id] } };
}

function assignable(role, guild) {
  const me = guild.members.me;
  return Boolean(role && !role.managed && role.id !== guild.id && me && role.position < me.roles.highest.position
    && !DANGEROUS.some((p) => role.permissions.has(p)));
}

async function onJoin(member) {
  if (!cfg.enabled || member.user.bot) {
    return;
  }
  if (cfg.channelId) {
    try {
      const channel = await member.client.channels.fetch(cfg.channelId);
      await channel.send(message(member));
    } catch (e) {
      console.error('[hawkeye-bot] welcome message failed:', e.message);
    }
  }
  for (const id of cfg.autoRoleIds || []) {
    const role = member.guild.roles.cache.get(id);
    if (!assignable(role, member.guild)) {
      console.warn(`[hawkeye-bot] auto-role ${id} can't be given by the bot, skipped`);
      continue;
    }
    await member.roles.add(role, 'Auto-role on join').catch((e) => console.error('[hawkeye-bot] auto-role failed:', e.message));
  }
  if (cfg.dmEnabled && cfg.dmMessage) {
    await member.send(fill(cfg.dmMessage, member).slice(0, 2000)).catch(() => {});
  }
}

async function onLeave(member) {
  if (!cfg.leaveEnabled || !cfg.leaveChannelId || (member.user && member.user.bot)) {
    return;
  }
  try {
    const channel = await member.client.channels.fetch(cfg.leaveChannelId);
    await channel.send({ content: fill(cfg.leaveMessage, member).replace(`<@${member.id}>`, `**${member.user.username}**`).slice(0, 2000), allowedMentions: { parse: [] } });
  } catch (e) {
    console.error('[hawkeye-bot] leave message failed:', e.message);
  }
}

module.exports = { onJoin, onLeave, message };
