(() => {
  "use strict";

  const TOKEN = "159di-af9966eba067389d133ab5157e8ff1479c8380ed";
  const API = "https://headless.tebex.io/api";
  const STORE_KEY = "gatemc-store-v1";
  const UPSELL_KEY = "gatemc-upsell";
  const NAME_RE = /^\.?[A-Za-z0-9_]{3,16}$/;

  const RANKS = {
    Mechanic: {
      cls: "mechanic", boost: "+10% tokens · +5% sale value",
      perks: ["+10% tokens", "+5% sale value", "+1 token every hour", "Mechanic chat tag", "Aqua glow and Sky chat colour",
        "Turbo speed in the hub", "Ironworks base style", "2 vaults"]
    },
    Machinist: {
      cls: "machinist", boost: "+20% tokens · +10% sale value",
      perks: ["!Everything in Mechanic", "+20% tokens", "+10% sale value", "+2 tokens every hour", "Free Foreman",
        "Blue glow and BELT BOSS tag", "Copperworks base style", "/hat and 3 vaults"]
    },
    Overseer: {
      cls: "overseer", boost: "+35% tokens · +15% sale value", flag: "POPULAR",
      perks: ["!Everything in Machinist", "+35% tokens", "+15% sale value", "+3 tokens every hour", "Remote gate", "Chat colours",
        "3 pet slots and 4 vaults", "Nether Forge base style", "Sunset name paint and Flame Halo aura"]
    },
    Industrialist: {
      cls: "industrialist", boost: "+50% tokens · +20% sale value",
      perks: ["!Everything in Overseer", "+50% tokens", "+20% sale value", "+4 tokens every hour", "Free Supervisor",
        "/craft and /enderchest", "4 pet slots and 6 vaults", "Deep Sea base style", "Galaxy name paint, Runes aura and Fire glow"]
    },
    Tycoon: {
      cls: "tycoon", boost: "+75% tokens · +25% sale value", flag: "BEST VALUE",
      perks: ["!Everything in Industrialist", "+75% tokens", "+25% sale value", "+6 tokens every hour", "Free Director",
        "/anvil and /nick", "5 pet slots and 8 vaults", "Royal Void base style", "Gilded name paint, BARON tag and Gold Dust",
        "Rainbow glow and Legend join and leave messages"]
    }
  };
  const RANK_ORDER = ["Mechanic", "Machinist", "Overseer", "Industrialist", "Tycoon"];
  const UPSELL_PICK = "Overseer";

  const COMPARE = [
    ["Token bonus", "+10%", "+20%", "+35%", "+50%", "+75%"],
    ["Sale value bonus", "+5%", "+10%", "+15%", "+20%", "+25%"],
    ["Tokens every hour", "+1", "+2", "+3", "+4", "+6"],
    ["Vaults", "2", "3", "4", "6", "8"],
    ["Pet slots", false, false, "3", "4", "5"],
    ["Free unlock", false, "Foreman", "Foreman", "Supervisor", "Director"],
    ["New base style", "Ironworks", "Copperworks", "Nether Forge", "Deep Sea", "Royal Void"],
    ["Turbo speed in the hub", true, true, true, true, true],
    ["/hat", false, true, true, true, true],
    ["Chat colours", false, false, true, true, true],
    ["Remote gate", false, false, true, true, true],
    ["/craft and /enderchest", false, false, false, true, true],
    ["/anvil and /nick", false, false, false, false, true],
    ["Legend join and leave messages", false, false, false, false, true],
    ["Cosmetics", "Aqua glow, Sky chat colour", "+ Blue glow, BELT BOSS tag", "+ Sunset paint, Flame Halo aura", "+ Galaxy paint, Runes aura, Fire glow", "+ Gilded paint, BARON tag, Gold Dust, Rainbow glow"]
  ];

  const BUNDLES = {
    "Starter Bundle": { rank: "Mechanic", credits: 500, flag: "NEW",
      blurb: "A head start for new factories.",
      lines: ["!Mechanic rank, permanent", "+10% tokens and +5% sale value", "Ironworks base style", "!500 Credits"] },
    "Foundry Bundle": { rank: "Overseer", credits: 2500, flag: "BEST VALUE",
      blurb: "Everything a growing factory needs.",
      lines: ["!Overseer rank, permanent", "Includes Mechanic and Machinist perks", "3 pet slots, Nether Forge base style", "!2,500 Credits"] },
    "Overlord Bundle": { rank: "Tycoon", credits: 6000, flag: "TOP TIER",
      blurb: "The top of the line.",
      lines: ["!Tycoon rank, permanent", "Every rank perk on GateMC", "5 pet slots, every base style", "!6,000 Credits"] }
  };

  const CREDIT_ART = { 500: "credits_500", 1000: "credits_1100", 2500: "credits_2800", 6000: "credits_6000" };
  const CREDIT_SPLIT = { 2500: [2000, 500], 6000: [4000, 2000] };

  const $ = (s, r = document) => r.querySelector(s);
  const el = (tag, cls, html) => { const e = document.createElement(tag); if (cls) e.className = cls; if (html !== undefined) e.innerHTML = html; return e; };
  const esc = s => String(s).replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
  const rankTag = (name, cls) => el("span", "rtag rtag--" + (RANKS[name] ? RANKS[name].cls : "mechanic") + (cls ? " " + cls : ""), esc(name));

  let state = load();
  let catalogue = null;
  let packages = new Map();
  let bundleIds = new Set();
  let currency = "USD";

  function load() {
    try {
      const s = JSON.parse(localStorage.getItem(STORE_KEY));
      if (s && Array.isArray(s.items)) return { username: s.username || "", items: s.items };
    } catch (e) { }
    return { username: "", items: [] };
  }
  function save() {
    try { localStorage.setItem(STORE_KEY, JSON.stringify(state)); } catch (e) { }
    renderBasket();
  }

  function money(n) {
    try { return new Intl.NumberFormat(undefined, { style: "currency", currency }).format(n); }
    catch (e) { return n.toFixed(2) + " " + currency; }
  }

  function toast(msg, ms = 2600) {
    const t = $("#toast");
    t.textContent = msg;
    t.hidden = false;
    clearTimeout(toast.timer);
    toast.timer = setTimeout(() => { t.hidden = true; }, ms);
  }

  async function api(path, opts = {}) {
    const res = await fetch(API + path, {
      method: opts.method || "GET",
      headers: opts.body ? { "Content-Type": "application/json", Accept: "application/json" } : { Accept: "application/json" },
      body: opts.body ? JSON.stringify(opts.body) : undefined
    });
    let data = null;
    try { data = await res.json(); } catch (e) { }
    if (!res.ok) {
      const msg = data && (data.detail || data.title || data.message);
      throw new Error(msg || ("Request failed (" + res.status + ")"));
    }
    return data && data.data !== undefined ? data.data : data;
  }

  const regular = p => p.total_price + (p.discount > 0 ? p.discount : 0);
  const onSale = p => p.discount > 0;
  const salePct = p => onSale(p) ? Math.round(p.discount / regular(p) * 100) : 0;

  function priceHtml(p) {
    const now = money(p.total_price);
    return onSale(p) ? "<s>" + money(regular(p)) + "</s>" + now : now;
  }

  function saleBadge(p, cls) {
    return onSale(p) ? el("span", "sale-badge" + (cls ? " " + cls : ""), salePct(p) >= 1 ? "&minus;" + salePct(p) + "%" : "SALE") : null;
  }

  function addButton(p, label, cls) {
    const b = el("button", "btn " + (cls || "btn--gold") + " btn--sm", esc(label));
    b.type = "button";
    b.addEventListener("click", () => addToBasket(p));
    return b;
  }

  function perkList(items) {
    const ul = el("ul", "perks");
    for (const perk of items) ul.appendChild(el("li", perk.startsWith("!") ? "lead-in" : "", esc(perk.replace(/^!/, ""))));
    return ul;
  }

  function descText(p) {
    const d = document.createElement("div");
    d.innerHTML = p.description || "";
    return (d.textContent || "").replace(/\s+/g, " ").trim();
  }

  function renderRanks(list) {
    const grid = $("#ranks-grid");
    grid.innerHTML = "";
    list.sort((a, b) => RANK_ORDER.indexOf(a.name) - RANK_ORDER.indexOf(b.name));
    for (const p of list) {
      const info = RANKS[p.name] || { cls: "mechanic", perks: [], boost: "" };
      const card = el("article", "rank rank--" + info.cls + (p.name === UPSELL_PICK ? " rank--pick" : "") + (onSale(p) ? " is-sale" : ""));
      if (info.flag) card.appendChild(el("span", "rank__flag", info.flag));
      const rb = saleBadge(p, "sale-badge--corner");
      if (rb) card.appendChild(rb);
      card.appendChild(rankTag(p.name, "rank__tag"));
      card.appendChild(el("div", "rank__price", "<strong>" + priceHtml(p) + "</strong><span>/ month</span>"));
      if (info.boost) card.appendChild(el("div", "rank__monthly", esc(info.boost)));
      card.appendChild(perkList(info.perks.length ? info.perks : [descText(p)]));
      const b = addButton(p, "Get " + p.name, "btn--" + info.cls);
      b.classList.remove("btn--sm");
      card.appendChild(b);
      grid.appendChild(card);
    }
    if (!list.length) grid.innerHTML = '<div class="loading">Ranks are coming soon.</div>';
  }

  function productCard(p, media, desc, tag) {
    const c = el("article", "card" + (onSale(p) ? " is-sale" : ""));
    const m = el("div", "card__media");
    m.appendChild(media);
    const sb = saleBadge(p, "sale-badge--corner");
    if (sb) m.appendChild(sb);
    c.appendChild(m);
    const body = el("div", "card__body");
    if (tag) body.appendChild(el("span", "tag", esc(tag)));
    body.appendChild(el("div", "card__name", esc(p.name)));
    if (desc) body.appendChild(el("div", "card__desc", esc(desc)));
    const row = el("div", "card__row");
    row.appendChild(el("div", "card__price", priceHtml(p)));
    row.appendChild(addButton(p, "Add"));
    body.appendChild(row);
    c.appendChild(body);
    return c;
  }

  const creditAmount = p => parseInt(String(p.name).replace(/[^0-9]/g, ""), 10) || 0;
  const creditArt = (n, alt) => Object.assign(el("img", "art"), { src: "img/" + (CREDIT_ART[n] || "credits_category") + ".png", alt: alt || "", loading: "lazy" });

  function renderCredits(list) {
    const grid = $("#credits-grid");
    grid.innerHTML = "";
    list.sort((a, b) => creditAmount(a) - creditAmount(b));
    const baseRate = list.length ? creditAmount(list[0]) / regular(list[0]) : 0;
    list.forEach((p, i) => {
      const n = creditAmount(p);
      const bonus = baseRate ? Math.round((n / regular(p) / baseRate - 1) * 100) : 0;
      const split = CREDIT_SPLIT[n];
      const desc = (split ? split[0].toLocaleString() + " Credits + " + split[1].toLocaleString() + " bonus. " : "") + "Spend on super powers and crate keys.";
      const tag = i === list.length - 1 && list.length > 1 ? "BEST VALUE" : bonus >= 5 ? "+" + bonus + "% BONUS" : "";
      grid.appendChild(productCard(p, creditArt(n, n.toLocaleString() + " Credits"), desc, tag));
    });
    if (!list.length) grid.innerHTML = '<div class="loading">Credit packs are coming soon.</div>';
  }

  function renderBundles(list) {
    const grid = $("#bundles-grid");
    grid.innerHTML = "";
    list.sort((a, b) => a.total_price - b.total_price);
    for (const p of list) {
      const info = BUNDLES[p.name];
      const cls = info && RANKS[info.rank] ? RANKS[info.rank].cls : "tycoon";
      const card = el("article", "bundle bundle--" + cls + (onSale(p) ? " is-sale" : ""));
      if (info && info.flag) card.appendChild(el("span", "rank__flag", info.flag));
      const art = el("div", "bundle__art");
      if (info) {
        art.appendChild(rankTag(info.rank, "rtag--lg"));
        art.appendChild(el("span", "bundle__plus", "+"));
        const coin = el("div", "bundle__coin");
        coin.appendChild(creditArt(info.credits, ""));
        coin.appendChild(el("span", "", info.credits.toLocaleString()));
        art.appendChild(coin);
      } else {
        art.appendChild(el("div", "crate", '<span class="crate__lid"></span><span class="crate__band"></span>'));
      }
      const sb = saleBadge(p, "sale-badge--corner");
      if (sb) art.appendChild(sb);
      card.appendChild(art);
      const body = el("div", "bundle__body");
      body.appendChild(el("h3", "", esc(p.name)));
      body.appendChild(el("p", "bundle__blurb", esc(info ? info.blurb : descText(p))));
      if (info) body.appendChild(perkList(info.lines));
      const row = el("div", "bundle__buy");
      row.appendChild(el("strong", "", priceHtml(p)));
      row.appendChild(el("span", "", "one-time"));
      body.appendChild(row);
      const b = addButton(p, "Get " + p.name, "btn--" + cls);
      b.classList.remove("btn--sm");
      body.appendChild(b);
      card.appendChild(body);
      grid.appendChild(card);
    }
    if (!list.length) grid.innerHTML = '<div class="loading">Bundles are coming soon.</div>';
  }

  function rankList() {
    return RANK_ORDER.map(n => [...packages.values()].find(p => p.name === n && p.type === "subscription")).filter(Boolean);
  }

  function renderCompare() {
    const t = $("#compare-table");
    const ranks = rankList();
    const byName = Object.fromEntries(ranks.map(p => [p.name, p]));
    const hl = RANK_ORDER.indexOf(UPSELL_PICK) + 1;
    let h = "<thead><tr><th>Perk</th>";
    RANK_ORDER.forEach((n, i) => {
      const p = byName[n];
      h += '<th class="c-' + RANKS[n].cls + (i + 1 === hl ? " hl" : "") + '"><span class="compare__rank">' + n + "</span>" + (p ? '<span class="compare__price">' + priceHtml(p) + " / mo</span>" + (onSale(p) ? '<span class="sale-badge sale-badge--sm">&minus;' + salePct(p) + "%</span>" : "") : "") + "</th>";
    });
    h += "</tr></thead><tbody>";
    for (const row of COMPARE) {
      h += "<tr><th scope=\"row\">" + esc(row[0]) + "</th>";
      for (let i = 1; i <= RANK_ORDER.length; i++) {
        const v = row[i];
        const cell = v === true ? '<span class="yes" aria-label="Yes">&#10003;</span>' : v === false ? '<span class="no" aria-label="No">&mdash;</span>' : esc(v);
        h += "<td" + (i === hl ? ' class="hl"' : "") + ">" + cell + "</td>";
      }
      h += "</tr>";
    }
    h += '</tbody><tfoot><tr><th></th>';
    RANK_ORDER.forEach((n, i) => { h += "<td" + (i + 1 === hl ? ' class="hl"' : "") + ">" + (byName[n] ? '<button type="button" class="btn btn--sm btn--' + RANKS[n].cls + '" data-add="' + byName[n].id + '">Get ' + n + "</button>" : "") + "</td>"; });
    h += "</tr></tfoot>";
    t.innerHTML = h;
    t.querySelectorAll("[data-add]").forEach(b => {
      const p = packages.get(Number(b.dataset.add));
      b.addEventListener("click", () => addToBasket(p));
    });
  }

  function renderTiles(byName) {
    document.querySelectorAll("[data-from]").forEach(e => {
      const list = byName[e.dataset.from] || [];
      if (!list.length) { e.textContent = ""; return; }
      const min = Math.min(...list.map(p => p.total_price));
      const sub = list.every(p => p.type === "subscription");
      e.innerHTML = (list.length > 1 ? "From " : "") + "<strong>" + money(min) + "</strong>" + (sub ? " / month" : "");
      const tile = e.closest(".tile");
      const best = Math.max(0, ...list.map(salePct));
      tile.querySelectorAll(".tile__sale").forEach(x => x.remove());
      tile.classList.toggle("is-sale", list.some(onSale));
      if (list.some(onSale)) tile.querySelector(".tile__art").appendChild(el("span", "tile__sale", best > 0 ? (list.length > 1 ? "UP TO " : "") + best + "% OFF" : "ON SALE"));
    });
  }

  function renderSaleBanner(byName) {
    const host = $("#sale-banner");
    const labels = { ranks: "Ranks", credits: "Credits", bundles: "Bundles" };
    const routes = { ranks: "#/ranks", credits: "#/credits", bundles: "#/bundles" };
    const hits = Object.keys(labels).filter(k => (byName[k] || []).some(onSale));
    if (!hits.length) { host.hidden = true; return; }
    const best = Math.max(0, ...hits.flatMap(k => byName[k].map(salePct)));
    const names = hits.map(k => labels[k]);
    const list = names.length > 1 ? names.slice(0, -1).join(", ") + " and " + names[names.length - 1] : names[0];
    host.innerHTML = "";
    host.appendChild(el("span", "sale-banner__tag", "SALE"));
    host.appendChild(el("span", "sale-banner__text", (best > 0 ? "Up to <strong>" + best + "% off</strong> " : "Discounts on ") + esc(list) + " right now."));
    const go = el("a", "sale-banner__go", "Shop the sale &rarr;");
    go.href = routes[hits[0]];
    host.appendChild(go);
    host.hidden = false;
  }

  function upsellPick() {
    const list = rankList();
    return list.find(p => p.name === UPSELL_PICK) || list[0] || null;
  }

  function rankPromo(host, line) {
    if (!host) return;
    const pick = upsellPick();
    if (!pick) { host.hidden = true; return; }
    const info = RANKS[pick.name];
    host.innerHTML = "";
    host.className = "promo promo--" + info.cls;
    const txt = el("div", "promo__text", "<strong>" + esc(line) + "</strong><span>" + esc(pick.name) + " is " + priceHtml(pick) + "/month" + (onSale(pick) ? " (" + salePct(pick) + "% off)" : "") + " with " + esc(info.boost.replace(" · ", ", ")) + (pick.name === "Overseer" ? ", a remote gate, 3 pet slots" : "") + " and more.</span>");
    const go = el("a", "btn btn--ghost btn--sm", "Compare ranks"); go.href = "#/ranks";
    const add = addButton(pick, "Add " + pick.name, "btn--" + info.cls);
    const btns = el("div", "promo__btns"); btns.append(go, add);
    host.append(rankTag(pick.name, "promo__badge"), txt, btns);
    host.hidden = false;
  }

  async function loadCatalogue() {
    try {
      const cats = await api("/accounts/" + TOKEN + "/categories?includePackages=1");
      const byName = {};
      for (const c of cats) {
        const pk = (c.packages || []).filter(p => !p.disabled);
        for (const p of pk) {
          packages.set(p.id, p);
          if (p.currency) currency = p.currency;
        }
        const key = String(c.name).toLowerCase();
        byName[key] = pk;
        if (key === "bundles") pk.forEach(p => bundleIds.add(p.id));
      }
      catalogue = byName;
      renderCatalogue();
      state.items = state.items.filter(i => packages.has(i.id));
      save();
    } catch (e) {
      for (const id of ["#ranks-grid", "#credits-grid", "#bundles-grid"]) {
        $(id).innerHTML = '<div class="loading">Couldn\'t load the store right now. Refresh to try again.</div>';
      }
    }
  }

  function renderCatalogue() {
    if (!catalogue) return;
    const byName = catalogue;
    renderRanks(byName["ranks"] || []);
    renderCredits(byName["credits"] || []);
    renderBundles(byName["bundles"] || []);
    renderCompare();
    renderTiles(byName);
    renderSaleBanner(byName);
    rankPromo($("#credits-promo"), "Earn more from every belt?");
  }

  function addToBasket(p) {
    if (!state.username) {
      openLogin(() => addToBasket(p));
      return;
    }
    const sub = p.type === "subscription";
    const existing = state.items.find(i => i.id === p.id);
    if (existing) {
      if (sub || p.disable_quantity) { toast(p.name + " is already in your basket"); openBasket(); return; }
      existing.qty += 1;
    } else {
      if (sub && state.items.some(i => i.type === "subscription")) {
        state.items = state.items.filter(i => i.type !== "subscription");
        toast("Swapped your rank for " + p.name);
      }
      state.items.push({ id: p.id, qty: 1, type: sub ? "subscription" : "single" });
    }
    save();
    bump();
    if (!sub && !bundleIds.has(p.id) && !state.items.some(i => i.type === "subscription") && upsellPick() && !upsellSeen()) { openUpsell(p); return; }
    if (!toast.timer || $("#toast").hidden) toast(p.name + " added to your basket");
  }

  function upsellSeen() {
    try { return sessionStorage.getItem(UPSELL_KEY) === "1"; } catch (e) { return false; }
  }
  function markUpsell() {
    try { sessionStorage.setItem(UPSELL_KEY, "1"); } catch (e) { }
  }

  function upsellCard(p) {
    const info = RANKS[p.name];
    const c = el("button", "upsell__rank upsell__rank--" + info.cls + (p.name === UPSELL_PICK ? " is-pick" : ""));
    c.type = "button";
    if (p.name === UPSELL_PICK) c.appendChild(el("span", "upsell__flag", "RECOMMENDED"));
    c.appendChild(rankTag(p.name));
    c.appendChild(el("div", "upsell__price", "<strong>" + priceHtml(p) + "</strong> / month"));
    const ub = saleBadge(p, "sale-badge--sm");
    if (ub) c.appendChild(ub);
    c.appendChild(el("div", "upsell__monthly", esc(info.boost)));
    c.appendChild(el("span", "upsell__add", "Add " + esc(p.name)));
    return c;
  }

  function openUpsell(p) {
    markUpsell();
    const ranks = rankList();
    if (!ranks.length) { toast(p.name + " added to your basket"); return; }
    $("#upsell-added").textContent = "✓ " + p.name + " added";
    const box = $("#upsell-ranks");
    box.innerHTML = "";
    for (const r of ranks) {
      const c = upsellCard(r);
      c.addEventListener("click", () => { closeUpsell(); addToBasket(r); openBasket(); });
      box.appendChild(c);
    }
    $("#upsell-modal").hidden = false;
  }
  function closeUpsell() { $("#upsell-modal").hidden = true; }
  $("#upsell-skip").addEventListener("click", () => { closeUpsell(); toast("Added to your basket"); });
  $("#upsell-basket").addEventListener("click", () => { closeUpsell(); openBasket(); });

  function renderBasketUpsell() {
    const host = $("#basket-upsell");
    const pick = upsellPick();
    const onlyBundles = state.items.every(i => bundleIds.has(i.id));
    if (!pick || !state.items.length || onlyBundles || state.items.some(i => i.type === "subscription")) { host.hidden = true; return; }
    const info = RANKS[pick.name];
    host.innerHTML = "";
    const txt = el("div", "drawer__upsell-text", "<strong>Add " + esc(pick.name) + "</strong><span>" + priceHtml(pick) + "/mo" + (onSale(pick) ? " (" + salePct(pick) + "% off)" : "") + " &middot; " + esc(info.boost) + "</span>");
    const b = addButton(pick, "Add", "btn--" + info.cls);
    const more = el("a", "drawer__upsell-more", "Compare"); more.href = "#/ranks";
    more.addEventListener("click", closeBasket);
    host.append(rankTag(pick.name, "rtag--sm"), txt, b, more);
    host.hidden = false;
  }

  function bump() {
    const b = $("#basket-btn");
    b.animate([{ transform: "scale(1)" }, { transform: "scale(1.15)" }, { transform: "scale(1)" }], { duration: 260 });
  }

  function renderBasket() {
    const count = state.items.reduce((n, i) => n + i.qty, 0);
    const c = $("#basket-count");
    c.textContent = count;
    c.hidden = count === 0;
    $("#login-btn").textContent = state.username ? state.username : "Log in";
    const who = $("#basket-who");
    who.innerHTML = state.username
      ? "Delivering to <strong class=\"mono\">" + esc(state.username) + "</strong> &middot; <button type=\"button\" id=\"change-name\">change</button>"
      : "Not logged in &middot; <button type=\"button\" id=\"change-name\">log in</button>";
    $("#change-name").addEventListener("click", () => openLogin(openBasket));
    const box = $("#basket-items");
    box.innerHTML = "";
    let total = 0;
    for (const item of state.items) {
      const p = packages.get(item.id);
      if (!p) continue;
      total += p.total_price * item.qty;
      const line = el("div", "line");
      line.appendChild(el("div", "line__name", esc(p.name)));
      line.appendChild(el("div", "line__price", (onSale(p) ? "<s>" + money(regular(p) * item.qty) + "</s>" : "") + money(p.total_price * item.qty)));
      line.appendChild(el("div", "line__meta", item.type === "subscription" ? "Renews monthly · cancel any time" : money(p.total_price) + " each"));
      const ctl = el("div", "line__ctl");
      if (item.type !== "subscription" && !p.disable_quantity) {
        const minus = el("button", "", "&minus;"); minus.type = "button"; minus.setAttribute("aria-label", "One fewer");
        minus.addEventListener("click", () => { item.qty -= 1; if (item.qty <= 0) state.items = state.items.filter(i => i !== item); save(); });
        const qty = el("span", "mono", String(item.qty));
        const plus = el("button", "", "+"); plus.type = "button"; plus.setAttribute("aria-label", "One more");
        plus.addEventListener("click", () => { item.qty = Math.min(item.qty + 1, 99); save(); });
        ctl.append(minus, qty, plus);
      }
      const rm = el("button", "rm", "Remove"); rm.type = "button";
      rm.addEventListener("click", () => { state.items = state.items.filter(i => i !== item); save(); });
      ctl.appendChild(rm);
      line.appendChild(ctl);
      box.appendChild(line);
    }
    if (!state.items.length) box.innerHTML = '<div class="drawer__empty">Your basket is empty.</div>';
    $("#basket-total").textContent = state.items.length ? money(total) : "—";
    $("#checkout-btn").disabled = !state.items.length;
    renderBasketUpsell();
  }

  let afterLogin = null;
  function openLogin(then) {
    afterLogin = then || null;
    $("#basket").hidden = true;
    $("#login-modal").hidden = false;
    $("#login-error").hidden = true;
    const input = $("#login-name");
    input.value = state.username || "";
    setTimeout(() => input.focus(), 30);
  }
  function closeLogin() { $("#login-modal").hidden = true; }
  function openBasket() { renderBasket(); $("#basket").hidden = false; }
  function closeBasket() { $("#basket").hidden = true; }

  $("#login-form").addEventListener("submit", e => {
    e.preventDefault();
    const name = $("#login-name").value.trim();
    const err = $("#login-error");
    if (!NAME_RE.test(name)) {
      err.textContent = "That doesn't look like a Minecraft username (3-16 letters, numbers or _, with a dot in front for Bedrock).";
      err.hidden = false;
      return;
    }
    state.username = name;
    save();
    closeLogin();
    const then = afterLogin;
    afterLogin = null;
    if (then) then(); else toast("Logged in as " + name);
  });

  async function checkout() {
    const btn = $("#checkout-btn");
    const err = $("#checkout-error");
    err.hidden = true;
    if (!state.username) { openLogin(checkout); return; }
    if (!state.items.length) return;
    btn.disabled = true;
    btn.textContent = "Opening checkout…";
    try {
      const here = location.origin + location.pathname;
      const basket = await api("/accounts/" + TOKEN + "/baskets", {
        method: "POST",
        body: { complete_url: here + "?purchased=1", cancel_url: here, complete_auto_redirect: true, username: state.username }
      });
      let last = basket;
      for (const item of state.items) {
        last = await api("/baskets/" + basket.ident + "/packages", {
          method: "POST",
          body: Object.assign({ package_id: item.id, quantity: item.qty }, item.type === "subscription" ? { type: "subscription" } : {})
        });
      }
      const link = (last && last.links && last.links.checkout) || (basket.links && basket.links.checkout);
      if (!link) throw new Error("Tebex didn't return a checkout link.");
      location.href = link;
    } catch (e) {
      err.textContent = /username|player|user/i.test(e.message)
        ? "Tebex couldn't find the username \"" + state.username + "\". Check the spelling, or add a dot in front for Bedrock."
        : "Checkout failed: " + e.message;
      err.hidden = false;
      btn.disabled = false;
      btn.textContent = "Checkout";
    }
  }

  $("#checkout-btn").addEventListener("click", checkout);
  document.querySelectorAll(".drawer__note a").forEach(x => x.addEventListener("click", closeBasket));
  $("#basket-btn").addEventListener("click", openBasket);
  $("#login-btn").addEventListener("click", () => openLogin(null));
  document.querySelectorAll("[data-close]").forEach(x => x.addEventListener("click", () => { closeLogin(); closeBasket(); closeUpsell(); }));
  document.addEventListener("keydown", e => { if (e.key === "Escape") { closeLogin(); closeBasket(); closeUpsell(); } });

  document.querySelectorAll("[data-copy]").forEach(b => b.addEventListener("click", async () => {
    const text = b.getAttribute("data-copy");
    try { await navigator.clipboard.writeText(text); toast("Copied " + text); }
    catch (e) { toast("Server IP: " + text); }
  }));

  const VIEWS = ["home", "ranks", "credits", "bundles", "terms", "privacy", "impressum", "refunds", "subscription"];
  const TITLES = { home: "GateMC Store", ranks: "Ranks", credits: "Credits", bundles: "Bundles", terms: "Terms", privacy: "Privacy", impressum: "Impressum", refunds: "Refund Policy", subscription: "Manage your rank" };
  let currentView = null;
  function route() {
    const h = location.hash;
    let view = "home", anchor = null;
    if (h.startsWith("#/")) {
      const v = h.slice(2).split("/")[0];
      view = VIEWS.includes(v) ? v : "home";
    } else if (h.length > 1) {
      anchor = h.slice(1);
    }
    if (anchor && !document.querySelector('[data-view="' + view + '"] #' + CSS.escape(anchor)) && document.getElementById(anchor)) {
      const host = document.getElementById(anchor).closest("[data-view]");
      if (host) view = host.dataset.view;
    }
    document.querySelectorAll("[data-view]").forEach(v => { v.hidden = v.dataset.view !== view; });
    document.querySelectorAll(".subnav a").forEach(a => a.classList.toggle("is-active", a.dataset.route === view && !anchor));
    document.title = view === "home" ? TITLES.home : TITLES[view] + " · GateMC Store";
    if (anchor) {
      const t = document.getElementById(anchor);
      if (t) requestAnimationFrame(() => t.scrollIntoView({ behavior: currentView === view ? "smooth" : "auto" }));
    } else if (currentView !== view) {
      window.scrollTo(0, 0);
    }
    currentView = view;
  }
  window.addEventListener("hashchange", route);
  route();

  $("#year").textContent = new Date().getFullYear();

  const params = new URLSearchParams(location.search);
  if (params.has("purchased")) {
    state.items = [];
    save();
    toast("Thanks for supporting GateMC! Your purchase is on its way in game.", 6000);
    history.replaceState(null, "", location.pathname + location.hash);
  }

  renderBasket();
  loadCatalogue();
})();
