const {
  Client, GatewayIntentBits, ActivityType, Events, SlashCommandBuilder, EmbedBuilder, MessageFlags, PermissionFlagsBits,
} = require('discord.js');
const linking = require('./linking');
const { syncPanels } = require('./panels');
const tickets = require('./tickets');
const applications = require('./applications');
const welcome = require('./welcome');
const moderation = require('./moderation');
const rolesync = require('./rolesync');
const staffserver = require('./staffserver');
const giveaways = require('./giveaways');
const appealalerts = require('./appealalerts');

const TOKEN = process.env.DISCORD_BOT_TOKEN;
const GUILD_ID = process.env.DISCORD_GUILD_ID;
const LINKED_ROLE_ID = process.env.DISCORD_LINKED_ROLE_ID || '';
const MEMBER_INTENT = process.env.DISCORD_MEMBER_INTENT === 'true';
const CONTENT_INTENT = process.env.DISCORD_CONTENT_INTENT === 'true';
const SYNC_MINUTES = Math.max(2, Number(process.env.BOOST_SYNC_MINUTES || 10));
const PINK = 0xf47fff;
const BLURPLE = 0x5865f2;
const DAY = 86_400_000;

if (!TOKEN) {
  console.error('DISCORD_BOT_TOKEN is required.');
  process.exit(1);
}

const linkingEnabled = Boolean(GUILD_ID && process.env.PGHOST);
if (!linkingEnabled) {
  console.warn('[hawkeye-bot] DISCORD_GUILD_ID or PGHOST missing - linking and boost tracking are off, presence only.');
}

const intents = [GatewayIntentBits.Guilds];
if (MEMBER_INTENT) {
  intents.push(GatewayIntentBits.GuildMembers);
}
if (CONTENT_INTENT) {
  intents.push(GatewayIntentBits.GuildMessages, GatewayIntentBits.MessageContent);
}
const client = new Client({ intents });

const commands = [
  new SlashCommandBuilder()
    .setName('link')
    .setDescription('Link your Minecraft account (run /link in game for a code)')
    .addStringOption((o) => o.setName('code').setDescription('The 6-character code from /link in game').setRequired(true)
      .setMinLength(6).setMaxLength(6)),
  new SlashCommandBuilder()
    .setName('unlink')
    .setDescription('Unlink your Minecraft account'),
  new SlashCommandBuilder()
    .setName('linked')
    .setDescription('See which Minecraft account a member is linked to')
    .addUserOption((o) => o.setName('user').setDescription('Defaults to you')),
  new SlashCommandBuilder()
    .setName('forceunlink')
    .setDescription('Unlink a member\'s Minecraft account')
    .setDefaultMemberPermissions(PermissionFlagsBits.ManageRoles)
    .addUserOption((o) => o.setName('user').setDescription('The member to unlink').setRequired(true)),
  ...tickets.commands(SlashCommandBuilder),
  ...giveaways.commands(SlashCommandBuilder),
].map((c) => c.toJSON());

function premiumSince(member) {
  return member && member.premiumSinceTimestamp ? member.premiumSinceTimestamp : 0;
}

async function fetchMember(guild, id) {
  try {
    return await guild.members.fetch({ user: id, force: true });
  } catch (e) {
    if (e.code === 10007 || e.code === 10013) {
      return null;
    }
    throw e;
  }
}

async function setLinkedRole(member, linked) {
  if (!LINKED_ROLE_ID || !member) {
    return;
  }
  try {
    if (linked && !member.roles.cache.has(LINKED_ROLE_ID)) {
      await member.roles.add(LINKED_ROLE_ID, 'Linked Minecraft account');
    } else if (!linked && member.roles.cache.has(LINKED_ROLE_ID)) {
      await member.roles.remove(LINKED_ROLE_ID, 'Unlinked Minecraft account');
    }
  } catch (e) {
    console.warn(`[hawkeye-bot] could not ${linked ? 'add' : 'remove'} the linked role for ${member.id}: ${e.message}`);
  }
}

function reply(interaction, color, title, description) {
  const embed = new EmbedBuilder().setColor(color).setTitle(title).setDescription(description);
  return interaction.reply({ embeds: [embed], flags: MessageFlags.Ephemeral });
}

async function onLink(interaction) {
  const member = await fetchMember(interaction.guild, interaction.user.id);
  const since = premiumSince(member);
  const result = await linking.claimCode(interaction.options.getString('code', true), interaction.user.id,
    interaction.user.username, since);
  if (result.error) {
    return reply(interaction, 0xff5555, 'Couldn\'t link', result.error);
  }
  console.log(`[hawkeye-bot] ${interaction.user.username} (${interaction.user.id}) linked to ${result.playerName}`);
  await setLinkedRole(member, true);
  await rolesync.recordRoles(member);
  const lines = [`Your Discord is now linked to **${result.playerName}**.`, 'Your link reward will arrive in game within a few seconds.'];
  if (result.boosting) {
    lines.push('', 'You\'re boosting - thank you! Open `/nitro` in game for your booster rewards.');
  } else {
    lines.push('', 'Boost the server to unlock the Nitro kit and reward path (`/nitro` in game).');
  }
  return reply(interaction, BLURPLE, 'Account linked', lines.join('\n'));
}

async function onUnlink(interaction) {
  const link = await linking.linkByDiscord(interaction.user.id);
  if (!link) {
    return reply(interaction, 0xff5555, 'Not linked', 'Your Discord isn\'t linked. Run `/link` in game to get a code.');
  }
  const hours = Number(process.env.UNLINK_COOLDOWN_HOURS || 24);
  const left = Number(link.linked_at) + hours * 3_600_000 - Date.now();
  if (left > 0) {
    return reply(interaction, 0xff5555, 'Too soon', `You can unlink <t:${Math.ceil((Date.now() + left) / 1000)}:R>.`);
  }
  await linking.unlink(interaction.user.id);
  await setLinkedRole(interaction.member, false);
  await rolesync.stripRanks(interaction.member);
  console.log(`[hawkeye-bot] ${interaction.user.username} (${interaction.user.id}) unlinked from ${link.player_name}`);
  return reply(interaction, BLURPLE, 'Unlinked', `Your Discord is no longer linked to **${link.player_name}**.`);
}

async function onLinked(interaction) {
  const user = interaction.options.getUser('user') || interaction.user;
  const link = await linking.linkByDiscord(user.id);
  if (!link) {
    return reply(interaction, 0xaaaaaa, 'Not linked', `${user} hasn't linked a Minecraft account.`);
  }
  const now = Date.now();
  const boosting = Number(link.boosting) === 1;
  const ms = (await linking.bankedMs(user.id)) + (boosting && Number(link.boost_since) > 0 ? now - Number(link.boost_since) : 0);
  const lines = [
    `${user} is linked to **${link.player_name}**`,
    `Linked <t:${Math.floor(Number(link.linked_at) / 1000)}:R>`,
    `Boosting: **${boosting ? 'yes' : 'no'}** · ${Math.floor(ms / DAY)} days boosted`,
  ];
  return reply(interaction, boosting ? PINK : BLURPLE, 'Linked account', lines.join('\n'));
}

async function onForceUnlink(interaction) {
  const user = interaction.options.getUser('user', true);
  const removed = await linking.unlink(user.id);
  if (!removed) {
    return reply(interaction, 0xaaaaaa, 'Not linked', `${user} isn't linked.`);
  }
  const target = await fetchMember(interaction.guild, user.id);
  await setLinkedRole(target, false);
  await rolesync.stripRanks(target);
  console.log(`[hawkeye-bot] ${interaction.user.username} force-unlinked ${user.username} (${user.id}) from ${removed.player_name}`);
  return reply(interaction, BLURPLE, 'Unlinked', `${user} is no longer linked to **${removed.player_name}**.`);
}

async function applyBoost(member, userId, name) {
  const change = await linking.setBoost(userId, premiumSince(member), name);
  if (change) {
    console.log(`[hawkeye-bot] ${name || userId} ${change} boosting`);
  }
}

let syncing = false;
async function syncBoosts() {
  if (syncing) {
    return;
  }
  syncing = true;
  try {
    const guild = await client.guilds.fetch(GUILD_ID);
    const ids = await linking.linkedDiscordIds();
    if (MEMBER_INTENT) {
      const members = await guild.members.fetch();
      for (const id of ids) {
        const m = members.get(id) || null;
        await applyBoost(m, id, m ? m.user.username : null);
        await setLinkedRole(m, true);
      }
      if (LINKED_ROLE_ID) {
        const linked = new Set(ids);
        for (const m of members.values()) {
          if (m.roles.cache.has(LINKED_ROLE_ID) && !linked.has(m.id)) {
            await setLinkedRole(m, false);
          }
        }
      }
    } else {
      for (const id of ids) {
        const m = await fetchMember(guild, id);
        await applyBoost(m, id, m ? m.user.username : null);
      }
    }
  } catch (e) {
    console.error('[hawkeye-bot] boost sync failed:', e.message);
  } finally {
    syncing = false;
  }
}

client.once(Events.ClientReady, async () => {
  console.log(`[hawkeye-bot] logged in as ${client.user.tag}`);
  client.user.setPresence({
    status: 'online',
    activities: [{ name: 'over GateMC', type: ActivityType.Watching }],
  });
  if (!linkingEnabled) {
    syncPanels(client);
    return;
  }
  syncPanels(client, [{ key: 'tickets', ...tickets.panel() }, { key: 'applications', ...applications.panel() }]);
  tickets.startPurge();
  if (CONTENT_INTENT) {
    moderation.start(client, GUILD_ID);
  }
  applications.startPoller(client);
  giveaways.start(client);
  appealalerts.start(client);
  try {
    const guild = await client.guilds.fetch(GUILD_ID);
    await guild.commands.set(commands);
    console.log(`[hawkeye-bot] registered ${commands.length} slash commands in ${guild.name}`);
  } catch (e) {
    console.error('[hawkeye-bot] could not register slash commands:', e.message);
  }
  syncBoosts();
  setInterval(syncBoosts, SYNC_MINUTES * 60_000);
  if (MEMBER_INTENT) {
    rolesync.start(client, GUILD_ID);
  }
});

client.on(Events.InteractionCreate, async (interaction) => {
  if (!linkingEnabled || interaction.guildId !== GUILD_ID) {
    return;
  }
  try {
    if (await tickets.handle(interaction) || await applications.handle(interaction) || await moderation.handle(interaction)
      || await giveaways.handle(interaction)) {
      return;
    }
  } catch (e) {
    console.error(`[hawkeye-bot] interaction ${interaction.customId || interaction.commandName} failed:`, e);
    const msg = { content: 'Something went wrong - try again in a moment.', flags: MessageFlags.Ephemeral };
    if (interaction.replied || interaction.deferred) {
      interaction.followUp(msg).catch(() => {});
    } else if (interaction.isRepliable()) {
      interaction.reply(msg).catch(() => {});
    }
    return;
  }
  if (!interaction.isChatInputCommand()) {
    return;
  }
  const handlers = { link: onLink, unlink: onUnlink, linked: onLinked, forceunlink: onForceUnlink };
  const handler = handlers[interaction.commandName];
  if (!handler) {
    return;
  }
  try {
    await handler(interaction);
  } catch (e) {
    console.error(`[hawkeye-bot] /${interaction.commandName} failed:`, e);
    const msg = { content: 'Something went wrong - try again in a moment.', flags: MessageFlags.Ephemeral };
    if (interaction.replied || interaction.deferred) {
      interaction.followUp(msg).catch(() => {});
    } else {
      interaction.reply(msg).catch(() => {});
    }
  }
});

client.on(Events.GuildMemberUpdate, async (before, after) => {
  if (!linkingEnabled || after.guild.id !== GUILD_ID) {
    return;
  }
  rolesync.onMemberUpdate(before, after);
  if (!before.partial && premiumSince(before) === premiumSince(after)) {
    return;
  }
  try {
    await applyBoost(after, after.id, after.user.username);
  } catch (e) {
    console.error('[hawkeye-bot] boost update failed:', e.message);
  }
});

client.on(Events.GuildMemberAdd, async (member) => {
  await staffserver.onJoin(member).catch((e) => console.error('[hawkeye-bot] staff server role failed:', e.message));
  if (member.guild.id !== GUILD_ID) {
    return;
  }
  await welcome.onJoin(member).catch((e) => console.error('[hawkeye-bot] welcome failed:', e.message));
});

client.on(Events.GuildMemberRemove, async (member) => {
  if (member.guild.id !== GUILD_ID) {
    return;
  }
  await welcome.onLeave(member).catch(() => {});
  if (!linkingEnabled) {
    return;
  }
  try {
    await applyBoost(null, member.id, null);
  } catch (e) {
    console.error('[hawkeye-bot] boost update on leave failed:', e.message);
  }
});

client.on('error', (e) => console.error('[hawkeye-bot] client error:', e.message));
client.on('shardError', (e) => console.error('[hawkeye-bot] shard error:', e.message));
client.on('shardDisconnect', () => console.warn('[hawkeye-bot] disconnected, discord.js will try to reconnect'));
client.on('shardReconnecting', () => console.log('[hawkeye-bot] reconnecting...'));
client.on('shardResume', () => console.log('[hawkeye-bot] resumed'));

process.on('unhandledRejection', (e) => console.error('[hawkeye-bot] unhandled rejection:', e));
process.on('uncaughtException', (e) => {
  console.error('[hawkeye-bot] uncaught exception, exiting for a clean restart:', e);
  process.exit(1);
});

client.login(TOKEN);
