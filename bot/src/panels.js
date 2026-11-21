const staticPanels = require('./panels.json');

function plain(payload) {
  const embeds = (payload.embeds || []).map((e) => (typeof e.toJSON === 'function' ? e.toJSON() : e));
  const components = (payload.components || []).map((r) => (typeof r.toJSON === 'function' ? r.toJSON() : r));
  return { embeds, components };
}

function signature(embeds, components) {
  return JSON.stringify({
    embeds: embeds.map((e) => ({
      title: e.title || null,
      description: e.description || null,
      url: e.url || null,
      color: e.color ?? null,
      fields: (e.fields || []).map((f) => [f.name, f.value]),
      footer: (e.footer && e.footer.text) || null,
    })),
    components: components.map((r) => (r.components || []).map((c) => [c.custom_id || c.customId || null, c.label || null,
      c.emoji ? (c.emoji.name || null) : null, c.style ?? null])),
  });
}

function messageSignature(message) {
  return signature(message.embeds.map((e) => e.toJSON()), message.components.map((r) => r.toJSON()));
}

async function syncPanel(client, panel) {
  const channel = await client.channels.fetch(panel.channelId);
  const recent = await channel.messages.fetch({ limit: 50 });
  const own = [...recent.filter((m) => m.author.id === client.user.id).values()].sort((a, b) => a.createdTimestamp - b.createdTimestamp);
  const payload = plain(panel.payload);
  const existing = own[0];
  if (!existing) {
    await channel.send(payload);
    return 'posted';
  }
  for (const extra of own.slice(1)) {
    await extra.delete().catch(() => {});
  }
  if (!existing.content && messageSignature(existing) === signature(payload.embeds, payload.components)) {
    return 'unchanged';
  }
  await existing.edit({ content: null, ...payload });
  return 'updated';
}

async function syncPanels(client, extra) {
  const panels = [
    ...staticPanels.map((p) => ({ key: p.key, channelId: p.channelId, payload: { embeds: [p.embed] } })),
    ...(extra || []),
  ];
  for (const panel of panels) {
    try {
      const result = await syncPanel(client, panel);
      console.log(`[hawkeye-bot] panel ${panel.key}: ${result}`);
    } catch (e) {
      console.error(`[hawkeye-bot] panel ${panel.key} failed: ${e.message}`);
    }
  }
}

module.exports = { syncPanels };
