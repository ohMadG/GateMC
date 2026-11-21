(() => {
  "use strict";

  const sky = document.querySelector(".sky");
  if (!sky) return;
  const reduce = window.matchMedia("(prefers-reduced-motion: reduce)").matches;

  const canvas = document.createElement("canvas");
  canvas.className = "sky__canvas";
  sky.appendChild(canvas);
  const ctx = canvas.getContext("2d");

  const COLORS = ["255,122,24", "255,154,60", "244,237,228", "255,213,79"];
  const LINK = 130;
  const REACH = 190;

  let w = 0, h = 0, dpr = 1;
  let dots = [];
  const mouse = { x: -9999, y: -9999, tx: -9999, ty: -9999, active: false };

  function size() {
    dpr = Math.min(window.devicePixelRatio || 1, 2);
    w = window.innerWidth;
    h = window.innerHeight;
    canvas.width = w * dpr;
    canvas.height = h * dpr;
    canvas.style.width = w + "px";
    canvas.style.height = h + "px";
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    const want = Math.min(130, Math.round((w * h) / 11000));
    while (dots.length < want) dots.push(spawn());
    dots.length = want;
  }

  function spawn() {
    const gold = Math.random() < 0.08;
    return {
      x: Math.random() * w,
      y: Math.random() * h,
      vx: (Math.random() - 0.5) * 0.25,
      vy: (Math.random() - 0.5) * 0.25,
      r: gold ? 1.8 : 0.8 + Math.random() * 1.2,
      c: gold ? COLORS[3] : COLORS[Math.floor(Math.random() * 3)],
      tw: Math.random() * Math.PI * 2
    };
  }

  function move(x, y) {
    mouse.tx = x;
    mouse.ty = y;
    if (!mouse.active) { mouse.x = x; mouse.y = y; mouse.active = true; }
    sky.style.setProperty("--mx", x + "px");
    sky.style.setProperty("--my", y + "px");
    sky.classList.add("is-live");
  }

  window.addEventListener("pointermove", e => move(e.clientX, e.clientY), { passive: true });
  window.addEventListener("pointerdown", e => move(e.clientX, e.clientY), { passive: true });
  document.addEventListener("pointerleave", () => { mouse.active = false; sky.classList.remove("is-live"); });
  window.addEventListener("blur", () => { mouse.active = false; sky.classList.remove("is-live"); });
  window.addEventListener("resize", () => { size(); if (reduce) { frame(0); cancelAnimationFrame(raf); } });

  function frame(t) {
    ctx.clearRect(0, 0, w, h);
    if (mouse.active) {
      mouse.x += (mouse.tx - mouse.x) * 0.15;
      mouse.y += (mouse.ty - mouse.y) * 0.15;
    }

    for (const d of dots) {
      if (mouse.active) {
        const dx = mouse.x - d.x, dy = mouse.y - d.y;
        const dist = Math.hypot(dx, dy);
        if (dist < REACH && dist > 1) {
          const f = (1 - dist / REACH) * 0.035;
          d.vx += (dx / dist) * f;
          d.vy += (dy / dist) * f;
        }
      }
      d.vx *= 0.985;
      d.vy *= 0.985;
      const sp = Math.hypot(d.vx, d.vy);
      if (sp < 0.08) { d.vx += (Math.random() - 0.5) * 0.02; d.vy += (Math.random() - 0.5) * 0.02; }
      if (sp > 1.6) { d.vx *= 1.6 / sp; d.vy *= 1.6 / sp; }
      d.x += d.vx;
      d.y += d.vy;
      if (d.x < -10) d.x = w + 10; else if (d.x > w + 10) d.x = -10;
      if (d.y < -10) d.y = h + 10; else if (d.y > h + 10) d.y = -10;
    }

    ctx.lineWidth = 1;
    for (let i = 0; i < dots.length; i++) {
      const a = dots[i];
      for (let j = i + 1; j < dots.length; j++) {
        const b = dots[j];
        const dx = a.x - b.x, dy = a.y - b.y;
        if (Math.abs(dx) > LINK || Math.abs(dy) > LINK) continue;
        const dist = Math.hypot(dx, dy);
        if (dist >= LINK) continue;
        let alpha = (1 - dist / LINK) * 0.12;
        if (mouse.active) {
          const md = Math.hypot(mouse.x - (a.x + b.x) / 2, mouse.y - (a.y + b.y) / 2);
          if (md < REACH) alpha += (1 - md / REACH) * 0.35 * (1 - dist / LINK);
        }
        ctx.strokeStyle = "rgba(255,140,40," + alpha.toFixed(3) + ")";
        ctx.beginPath();
        ctx.moveTo(a.x, a.y);
        ctx.lineTo(b.x, b.y);
        ctx.stroke();
      }
      if (mouse.active) {
        const md = Math.hypot(mouse.x - a.x, mouse.y - a.y);
        if (md < REACH * 0.8) {
          ctx.strokeStyle = "rgba(255,213,79," + ((1 - md / (REACH * 0.8)) * 0.28).toFixed(3) + ")";
          ctx.beginPath();
          ctx.moveTo(a.x, a.y);
          ctx.lineTo(mouse.x, mouse.y);
          ctx.stroke();
        }
      }
    }

    for (const d of dots) {
      const twinkle = 0.55 + Math.sin(t / 900 + d.tw) * 0.3;
      let glow = 0;
      if (mouse.active) {
        const md = Math.hypot(mouse.x - d.x, mouse.y - d.y);
        if (md < REACH) glow = 1 - md / REACH;
      }
      ctx.fillStyle = "rgba(" + d.c + "," + Math.min(1, twinkle + glow * 0.5).toFixed(3) + ")";
      ctx.beginPath();
      ctx.arc(d.x, d.y, d.r + glow * 1.2, 0, Math.PI * 2);
      ctx.fill();
    }

    if (!document.hidden) raf = requestAnimationFrame(frame);
  }

  let raf = 0;
  size();
  if (reduce) {
    frame(0);
    cancelAnimationFrame(raf);
    return;
  }
  document.addEventListener("visibilitychange", () => {
    cancelAnimationFrame(raf);
    if (!document.hidden) raf = requestAnimationFrame(frame);
  });
  raf = requestAnimationFrame(frame);
})();
