function esc(text) {
  return String(text == null ? '' : text)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}

function format(text, names) {
  let out = esc(text);
  out = out.replace(/```([\s\S]*?)```/g, (m, code) => `<pre>${code}</pre>`);
  out = out.replace(/`([^`\n]+)`/g, '<code>$1</code>');
  out = out.replace(/\*\*([^*]+)\*\*/g, '<b>$1</b>');
  out = out.replace(/__([^_]+)__/g, '<u>$1</u>');
  out = out.replace(/\*([^*\n]+)\*/g, '<i>$1</i>');
  out = out.replace(/&lt;@!?(\d+)&gt;/g, (m, id) => `<span class="mention">@${esc(names[id] || id)}</span>`);
  out = out.replace(/&lt;#(\d+)&gt;/g, '<span class="mention">#channel</span>');
  out = out.replace(/&lt;a?:(\w+):\d+&gt;/g, ':$1:');
  out = out.replace(/(https?:\/\/[^\s<]+)/g, '<a href="$1" target="_blank" rel="noopener noreferrer">$1</a>');
  return out.replace(/\n/g, '<br>');
}

function time(ms) {
  return new Date(ms).toISOString().replace('T', ' ').slice(0, 19) + ' UTC';
}

function buildTranscript({ ticket, category, channelName, messages }) {
  const names = {};
  for (const m of messages) {
    names[m.author.id] = m.author.name;
  }
  const rows = messages.map((m) => {
    const files = m.attachments.map((a) => (/^image\//.test(a.contentType)
      ? `<a href="${esc(a.url)}" target="_blank" rel="noopener noreferrer"><img class="att" src="${esc(a.url)}" alt="${esc(a.name)}"></a>`
      : `<div class="file">📎 <a href="${esc(a.url)}" target="_blank" rel="noopener noreferrer">${esc(a.name)}</a> (${Math.ceil(a.size / 1024)} KB)</div>`)).join('');
    const embeds = m.embeds.map((e) => `<div class="embed" style="border-color:${esc(e.color || '#8b5cf6')}">${e.title ? `<div class="et">${format(e.title, names)}</div>` : ''}${e.description ? `<div>${format(e.description, names)}</div>` : ''}${e.fields.map((f) => `<div class="ef"><b>${format(f.name, names)}</b><br>${format(f.value, names)}</div>`).join('')}${e.footer ? `<div class="efoot">${esc(e.footer)}</div>` : ''}</div>`).join('');
    return `<div class="msg"><img class="av" src="${esc(m.author.avatar)}" alt=""><div class="body"><div class="head"><span class="name">${esc(m.author.name)}</span>${m.author.bot ? '<span class="bot">BOT</span>' : ''}<span class="user">@${esc(m.author.username)}</span><span class="time">${time(m.createdAt)}</span></div>${m.content ? `<div class="text">${format(m.content, names)}</div>` : ''}${files}${embeds}</div></div>`;
  }).join('\n');
  return `<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>GateMC ticket #${ticket.id}</title>
<style>
:root{--bg:#1e1f22;--panel:#2b2d31;--text:#dbdee1;--muted:#949ba4;--accent:#8b5cf6}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--text);font:15px/1.45 system-ui,-apple-system,Segoe UI,Roboto,sans-serif}
header{background:var(--panel);padding:20px 16px;border-bottom:3px solid var(--accent)}header h1{margin:0 0 6px;font-size:20px}
header .meta{color:var(--muted);font-size:13px;display:flex;flex-wrap:wrap;gap:4px 18px}
main{max-width:960px;margin:0 auto;padding:12px 16px 40px}
.msg{display:flex;gap:12px;padding:8px 0}.av{width:40px;height:40px;border-radius:50%;flex:none;background:#000}
.body{min-width:0;flex:1}.head{display:flex;flex-wrap:wrap;align-items:baseline;gap:6px}.name{font-weight:600;color:#fff}
.user,.time{color:var(--muted);font-size:12px}.bot{background:var(--accent);color:#fff;font-size:10px;padding:1px 4px;border-radius:3px}
.text{word-wrap:break-word;overflow-wrap:anywhere}a{color:#00a8fc}code,pre{background:#111214;border-radius:4px;padding:1px 4px;font-family:ui-monospace,Consolas,monospace}
pre{padding:8px;white-space:pre-wrap}.mention{background:rgba(139,92,246,.25);color:#c4b5fd;border-radius:3px;padding:0 2px}
.att{max-width:min(400px,100%);max-height:300px;border-radius:6px;margin-top:6px;display:block}.file{margin-top:6px}
.embed{background:var(--panel);border-left:4px solid;border-radius:4px;padding:8px 12px;margin-top:6px;max-width:520px}
.et{font-weight:600;color:#fff;margin-bottom:4px}.ef{margin-top:6px}.efoot{color:var(--muted);font-size:12px;margin-top:6px}
</style></head><body>
<header><h1>${esc(category.emoji)} ${esc(category.label)} · Ticket #${ticket.id}</h1>
<div class="meta"><span>Channel: #${esc(channelName)}</span><span>Opened by: ${esc(ticket.discord_name)} (${esc(ticket.discord_id)})</span>
<span>Opened: ${time(new Date(ticket.created_at).getTime())}</span><span>Claimed by: ${esc(ticket.claimed_by || 'nobody')}</span>
<span>Closed by: ${esc(ticket.closed_by || '')}</span><span>Messages: ${messages.length}</span></div></header>
<main>
${rows || '<p>No messages.</p>'}
</main></body></html>
`;
}

module.exports = { buildTranscript };
