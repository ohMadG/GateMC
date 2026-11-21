const {
  EmbedBuilder, ActionRowBuilder, ButtonBuilder, ButtonStyle, ModalBuilder, TextInputBuilder, TextInputStyle, MessageFlags, AttachmentBuilder,
} = require('discord.js');
const { pool } = require('./db');
const { tellApplicationUpdate } = require('./rcon');
const features = require('./features.json');

const cfg = features.applications;
const PURPLE = 0x8b5cf6;
const GREEN = 0x57f287;
const RED = 0xed4245;
const POLL_MS = 20_000;
const POSTING = 'posting';

function ephemeral(interaction, content, components) {
  const msg = { content, flags: MessageFlags.Ephemeral, components: components || [] };
  return interaction.replied || interaction.deferred ? interaction.followUp(msg) : interaction.reply(msg);
}

function isReviewer(member) {
  return Boolean(member && member.roles && cfg.reviewerRoleIds.some((id) => member.roles.cache.has(id)));
}

function panel() {
  const embed = new EmbedBuilder()
    .setColor(PURPLE)
    .setTitle('📋 Apply for GateMC Staff')
    .setDescription('Want to help run GateMC? Click **Apply** below to open the staff application.\n\n'
      + '**Before you apply**\n'
      + '• Link your Minecraft account first: run `/link` in game, then `/link <code>` here\n'
      + '• You\'ll sign in with Discord, then fill out one short form\n'
      + '• Answer honestly and in detail, low-effort applications are denied\n'
      + `• If you're denied, you can apply again after ${cfg.reapplyDays} days\n`
      + '• Keep your DMs open, I\'ll message you when it\'s received and when it\'s reviewed')
    .setFooter({ text: 'GateMC Staff Team' });
  const row = new ActionRowBuilder().addComponents(
    new ButtonBuilder().setURL(cfg.applyUrl).setLabel('Apply').setEmoji('📋').setStyle(ButtonStyle.Link),
  );
  return { channelId: cfg.panelChannelId, payload: { embeds: [embed], components: [row] } };
}

function entries(app) {
  if (Array.isArray(app.answers)) {
    return app.answers.map((a) => ({ label: a.label, answer: a.answer, long: String(a.answer).length > 60 }));
  }
  return Object.entries(app.answers || {}).map(([id, answer]) => ({ label: id, answer, long: String(answer).length > 60 }));
}

function reviewMessage(app, state) {
  const color = state === 'accepted' ? GREEN : state === 'denied' ? RED : PURPLE;
  const embed = new EmbedBuilder()
    .setColor(color)
    .setTitle(`Staff application #${app.id}`)
    .setDescription(`<@${app.discord_id}> (${app.discord_name})${app.player_name ? `\nMinecraft: **${app.player_name}**` : ''}`)
    .setTimestamp(new Date(app.created_at));
  let used = 0;
  let fields = 0;
  for (const e of entries(app)) {
    const answer = String(e.answer || '-');
    const value = answer.length > 500 ? `${answer.slice(0, 497)}...` : answer;
    const name = e.label.length > 256 ? `${e.label.slice(0, 253)}...` : e.label;
    used += name.length + value.length;
    if (used > 5200 || fields >= 25) {
      break;
    }
    embed.addFields({ name, value, inline: !e.long });
    fields += 1;
  }
  if (state === 'accepted') {
    embed.setFooter({ text: `Accepted by ${app.reviewed_by}` });
  } else if (state === 'denied') {
    embed.setFooter({ text: `Denied by ${app.reviewed_by}${app.review_note ? `: ${app.review_note}`.slice(0, 1900) : ''}` });
  } else {
    embed.setFooter({ text: 'Pending review' });
  }
  const row = new ActionRowBuilder().addComponents(
    new ButtonBuilder().setCustomId(`app_accept:${app.id}`).setLabel(state === 'accepted' ? 'Accepted' : 'Accept')
      .setStyle(ButtonStyle.Success).setDisabled(state !== 'pending'),
    new ButtonBuilder().setCustomId(`app_deny:${app.id}`).setLabel(state === 'denied' ? 'Denied' : 'Deny')
      .setStyle(ButtonStyle.Danger).setDisabled(state !== 'pending'),
  );
  return { embeds: [embed], components: [row], allowedMentions: { parse: [] } };
}

function fullText(app) {
  const lines = [`Staff application #${app.id}`, `Discord: ${app.discord_name} (${app.discord_id})`];
  if (app.player_name) {
    lines.push(`Minecraft: ${app.player_name} (${app.player_uuid})`);
  }
  lines.push(`Submitted: ${new Date(app.created_at).toISOString()}`, '');
  for (const e of entries(app)) {
    lines.push(e.label, String(e.answer || '-'), '');
  }
  return new AttachmentBuilder(Buffer.from(lines.join('\n'), 'utf8'), { name: `application-${app.id}.txt` });
}

async function dm(client, app, embed) {
  const user = await client.users.fetch(app.discord_id).catch(() => null);
  const sent = user ? await user.send({ embeds: [embed] }).then(() => true).catch(() => false) : false;
  if (sent) {
    tellApplicationUpdate(app.player_uuid);
  }
  return sent;
}

async function postNew(client) {
  const { rows } = await pool.query(
    "UPDATE bot_staff_applications SET message_id = $1 WHERE id IN (SELECT id FROM bot_staff_applications WHERE status = 'pending' AND message_id IS NULL ORDER BY id LIMIT 5) RETURNING *",
    [POSTING],
  );
  for (const app of rows) {
    try {
      const channel = await client.channels.fetch(cfg.reviewChannelId);
      const sent = await channel.send({ ...reviewMessage(app, 'pending'), files: [fullText(app)] });
      await pool.query('UPDATE bot_staff_applications SET message_id = $2 WHERE id = $1', [app.id, sent.id]);
    } catch (e) {
      console.error(`[hawkeye-bot] could not post application #${app.id}:`, e.message);
      await pool.query('UPDATE bot_staff_applications SET message_id = NULL WHERE id = $1 AND message_id = $2', [app.id, POSTING]).catch(() => {});
      continue;
    }
    console.log(`[hawkeye-bot] staff application #${app.id} from ${app.discord_name}`);
    await dm(client, app, new EmbedBuilder().setColor(PURPLE).setTitle('📋 We got your GateMC staff application!')
      .setDescription('Thanks for applying! The staff team will read through it soon.\n\n'
        + 'You don\'t need to do anything else. I\'ll message you here as soon as it\'s been reviewed, so keep your DMs open.'));
  }
}

function startPoller(client) {
  pool.query('UPDATE bot_staff_applications SET message_id = NULL WHERE message_id = $1', [POSTING])
    .catch((e) => console.error('[hawkeye-bot] application reset failed:', e.message))
    .finally(() => {
      const tick = () => postNew(client).catch((e) => console.error('[hawkeye-bot] application poll failed:', e.message));
      tick();
      setInterval(tick, POLL_MS).unref();
    });
}

async function review(interaction, id, accepted, note) {
  const { rows } = await pool.query(
    "UPDATE bot_staff_applications SET status = $2, reviewed_by = $3, reviewed_at = now(), review_note = $4 WHERE id = $1 AND status = 'pending' RETURNING *",
    [id, accepted ? 'accepted' : 'denied', interaction.user.username, note || null],
  );
  const app = rows[0];
  if (!app) {
    return ephemeral(interaction, 'This application was already reviewed.');
  }
  await interaction.update(reviewMessage(app, app.status));
  const problems = [];
  const embed = accepted
    ? new EmbedBuilder().setColor(GREEN).setTitle('🎉 You\'ve been accepted as a GateMC Guide!')
      .setDescription('Congratulations, your staff application was accepted and you\'re joining the team as a **Guide**.\n\n'
        + `**Before anything else**, go to ${cfg.agreementUrl}, sign in with Discord and sign the staff agreement.\n\n`
        + 'You\'ll get your staff roles as soon as you\'ve signed it.')
    : new EmbedBuilder().setColor(RED).setTitle('Your GateMC staff application was denied')
      .setDescription(`${note ? `**Reason:** ${note}\n\n` : ''}Thanks for applying. You can apply again in ${cfg.reapplyDays} days.`);
  if (!(await dm(interaction.client, app, embed))) {
    problems.push('their DMs are closed, so they weren\'t notified');
  }
  console.log(`[hawkeye-bot] application #${app.id} ${app.status} by ${interaction.user.username}`);
  if (problems.length) {
    return ephemeral(interaction, `Application #${app.id} ${app.status}, but ${problems.join('; ')}.`);
  }
  if (accepted) {
    return ephemeral(interaction, `Application #${app.id} accepted. They've been told they're a Guide and to sign the staff agreement first; they get their roles once they sign.`);
  }
  return null;
}

function denyModal(id) {
  return new ModalBuilder().setCustomId(`app_denyreason:${id}`).setTitle(`Deny application #${id}`).addComponents(
    new ActionRowBuilder().addComponents(new TextInputBuilder().setCustomId('reason').setLabel('Reason (sent to the applicant)')
      .setStyle(TextInputStyle.Paragraph).setRequired(false).setMaxLength(500)),
  );
}

async function handle(interaction) {
  const id = interaction.customId || '';
  if (interaction.isButton()) {
    if (id === 'app_start' || id.startsWith('app_next:')) {
      await ephemeral(interaction, `Applications are on the website now: ${cfg.applyUrl}`);
      return true;
    }
    if (id.startsWith('app_accept:') || id.startsWith('app_deny:')) {
      if (!isReviewer(interaction.member)) {
        await ephemeral(interaction, 'Only staff managers and admins can review applications.');
        return true;
      }
      const appId = Number(id.split(':')[1]);
      if (id.startsWith('app_accept:')) {
        await review(interaction, appId, true);
      } else {
        await interaction.showModal(denyModal(appId));
      }
      return true;
    }
    return false;
  }
  if (interaction.isModalSubmit() && id.startsWith('app_denyreason:')) {
    if (!isReviewer(interaction.member)) {
      await ephemeral(interaction, 'Only staff managers and admins can review applications.');
      return true;
    }
    await review(interaction, Number(id.split(':')[1]), false, interaction.fields.getTextInputValue('reason').trim());
    return true;
  }
  return false;
}

module.exports = { panel, handle, startPoller };
