'use strict';
/* Наставник НТ — client. Talks only to this app's own server (/api/*), which talks only to the local Ollama. */

const $ = (s, root = document) => root.querySelector(s);
const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

const state = {
  course: null, byId: new Map(), order: [], current: null,
  history: [], busy: null, openModules: new Set(), status: null, quizFor: null,
};

// ------------------------------------------------------------------ progress (this browser only)
const PROGRESS_KEY = 'nt-progress-v1';
const progress = (() => {
  try { return JSON.parse(localStorage.getItem(PROGRESS_KEY)) || { read: {}, quiz: {} }; } catch { return { read: {}, quiz: {} }; }
})();
progress.read ||= {}; progress.quiz ||= {};
function saveProgress() { try { localStorage.setItem(PROGRESS_KEY, JSON.stringify(progress)); } catch { /* private mode */ } }
function remember(key, value) { try { localStorage.setItem(key, value); } catch { /* ignore */ } }
function recall(key) { try { return localStorage.getItem(key); } catch { return null; } }

// ------------------------------------------------------------------ markdown
const dirOf = id => (id.includes('/') ? id.slice(0, id.lastIndexOf('/')) : '');
function resolvePath(base, rel) {
  const parts = base ? base.split('/') : [];
  for (const p of rel.split('/')) {
    if (p === '..') parts.pop(); else if (p && p !== '.') parts.push(p);
  }
  return parts.join('/');
}
function cleanHref(h) { let x = h || ''; try { x = decodeURIComponent(x); } catch { /* keep */ } return x.replace(/^<|>$/g, ''); }
const norm = t => String(t).toLowerCase().replace(/\\/g, '').replace(/[^\p{L}\p{N}]+/gu, ' ').trim();

let mdBase = '';
marked.use({
  gfm: true,
  renderer: {
    html(token) {
      const t = token.text || '';
      if (/^\s*<!--/.test(t)) return '';
      if (/^<br\s*\/?>$/i.test(t.trim())) return '<br>';
      return esc(t); // course text and model output never inject markup
    },
    link(token) {
      const text = this.parser.parseInline(token.tokens);
      const href = cleanHref(token.href);
      if (/^https?:/i.test(href)) return `<a href="${esc(href)}" target="_blank" rel="noopener">${text}</a>`;
      const path = href.split('#')[0];
      if (path.endsWith('.md')) {
        const id = resolvePath(mdBase, path);
        return `<a href="#${encodeURIComponent(id)}">${text}</a>`;
      }
      return `<a href="${esc(href)}">${text}</a>`;
    },
    image(token) {
      const href = cleanHref(token.href);
      const src = /^https?:/i.test(href) ? href
        : '/course-file/' + resolvePath(mdBase, href).split('/').map(encodeURIComponent).join('/');
      return `<img src="${esc(src)}" alt="${esc(token.text || '')}" loading="lazy">`;
    },
    heading(token) {
      const inner = this.parser.parseInline(token.tokens);
      return `<h${token.depth} data-key="${esc(norm(inner.replace(/<[^>]+>/g, '')))}">${inner}</h${token.depth}>`;
    },
  },
});
function renderMd(md, base) { mdBase = base; return marked.parse(md); }
const stripThink = t => t.replace(/<think>[\s\S]*?(<\/think>|$)/g, '').trimStart();

// ------------------------------------------------------------------ server calls
async function api(path) {
  const r = await fetch(path);
  const data = await r.json().catch(() => ({}));
  if (!r.ok) throw new Error(data.error || `HTTP ${r.status}`);
  return data;
}
async function stream(path, body, onEvent, signal) {
  const r = await fetch(path, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body), signal });
  if (!r.ok) throw new Error(`Сервер ответил ${r.status}`);
  const reader = r.body.getReader();
  const dec = new TextDecoder();
  let buf = '';
  for (;;) {
    const { value, done } = await reader.read();
    if (done) break;
    buf += dec.decode(value, { stream: true });
    let i;
    while ((i = buf.indexOf('\n')) >= 0) {
      const line = buf.slice(0, i); buf = buf.slice(i + 1);
      if (line.trim()) onEvent(JSON.parse(line));
    }
  }
  if (buf.trim()) onEvent(JSON.parse(buf));
}

// ------------------------------------------------------------------ status
async function refreshStatus() {
  const el = $('#status');
  try {
    const s = await api('/api/status');
    state.status = s;
    if (!s.ollamaVersion) throw new Error(s.error || 'Ollama не отвечает');
    if (!state.busy) {
      el.dataset.state = s.modelReady ? 'ok' : 'down';
      $('#statusText').textContent = s.modelReady ? `${s.model} на этом компьютере` : `Нет модели ${s.model}`;
    }
  } catch (e) {
    el.dataset.state = 'down';
    $('#statusText').textContent = 'Ollama не отвечает';
    state.status = { ...(state.status || {}), ollamaVersion: null, error: e.message };
  }
  renderStatusPop();
}
function renderStatusPop() {
  const s = state.status || {};
  const emb = String(s.embedder || '');
  const search = emb.startsWith('api:') ? `по смыслу (${emb.slice(4)})` : 'по словам (embedding-модели нет)';
  $('#statusPop').innerHTML = `
    <dl>
      <dt>Модель</dt><dd>${esc(s.model)}${s.modelDetails ? ` (${esc(s.modelDetails)})` : ''}</dd>
      <dt>Ollama</dt><dd>${s.ollamaVersion ? esc(s.ollamaVersion) : 'не отвечает'} — ${esc(s.ollamaHost)}</dd>
      <dt>Поиск по курсу</dt><dd>${esc(search)}</dd>
      <dt>Фрагментов</dt><dd>${esc(s.chunks)}</dd>
      <dt>Окно контекста</dt><dd>${esc(s.numCtx)} токенов</dd>
    </dl>
    <p>${s.ollamaVersion
      ? (s.local ? `Все запросы уходят только в Ollama на ${esc(s.ollamaHost)}. Облачные модели не используются.`
                 : `Внимание: Ollama настроена на удалённый адрес ${esc(s.ollamaHost)}.`)
      : 'Запустите Ollama: <code>brew services start ollama</code> или откройте Ollama.app.'}</p>`;
}
$('#status').addEventListener('click', () => { $('#statusPop').hidden = !$('#statusPop').hidden; });
document.addEventListener('click', e => {
  if (!e.target.closest('#status, #statusPop')) $('#statusPop').hidden = true;
});
function setBusy(label) {
  const el = $('#status');
  if (label) { el.dataset.state = 'busy'; $('#statusText').textContent = label; } else refreshStatus();
  document.querySelectorAll('.actions button:not(.done-toggle)').forEach(b => { b.disabled = !!label; });
  const send = $('#send');
  send.textContent = label ? 'Остановить' : 'Спросить';
  send.classList.toggle('stop', !!label);
  send.type = label ? 'button' : 'submit';
}

// ------------------------------------------------------------------ course tree + progress profile
const shortTitle = l => {
  if (l.kind !== 'ASSIGNMENT') return l.title;
  const t = l.title.replace(/^Задани[ея]\s*(к уроку\s*)?[-—:]?\s*/i, '').replace(/^«(.*)»$/, '$1');
  return t.charAt(0).toUpperCase() + t.slice(1);
};
function renderCourse() {
  const list = $('#modules');
  list.innerHTML = '';
  const filter = $('#filter').value.trim().toLowerCase();
  for (const m of state.course.modules) {
    const lessons = m.lessons.filter(l => !filter || l.title.toLowerCase().includes(filter));
    if (!lessons.length) continue;
    const open = !!filter || state.openModules.has(m.name);
    const done = m.lessons.filter(l => progress.read[l.id]).length;
    const li = document.createElement('li');
    li.className = 'module';
    li.dataset.open = String(open);
    li.dataset.module = m.name;
    li.innerHTML = `<button type="button" aria-expanded="${open}"><span>${esc(m.name)}</span><span class="count">${done} из ${m.lessons.length}</span></button><ol></ol>`;
    li.firstElementChild.addEventListener('click', () => {
      const next = li.dataset.open !== 'true';
      li.dataset.open = String(next);
      li.firstElementChild.setAttribute('aria-expanded', String(next));
      if (next) state.openModules.add(m.name); else state.openModules.delete(m.name);
    });
    const ol = li.querySelector('ol');
    for (const l of lessons) {
      const q = progress.quiz[l.id];
      const a = document.createElement('a');
      a.className = 'lesson-link';
      a.href = '#' + encodeURIComponent(l.id);
      a.dataset.kind = l.kind;
      a.dataset.done = String(!!progress.read[l.id]);
      if (state.current?.id === l.id) a.setAttribute('aria-current', 'page');
      a.innerHTML = `<span class="num">${esc(l.number)}</span><span class="title">${esc(shortTitle(l))}</span>`
        + `<span class="mark">${q ? `<span class="score" title="Лучший результат теста">${q.best}/${q.total}</span>` : ''}</span>`;
      const item = document.createElement('li');
      item.append(a);
      ol.append(item);
    }
    list.append(li);
  }
  renderProfile();
}
$('#filter').addEventListener('input', renderCourse);

/* Progress drawn as a stepped load profile: one step per module, step height = share of the module done. */
function renderProfile() {
  const mods = state.course.modules.filter(m => m.name !== 'Начало');
  const W = 280, top = 6, base = 74, w = W / mods.length;
  let line = '', total = 0, done = 0, here = -1;
  const ticks = [], hits = [];
  mods.forEach((m, i) => {
    const d = m.lessons.filter(l => progress.read[l.id]).length;
    total += m.lessons.length; done += d;
    const y = base - (d / m.lessons.length) * (base - top);
    line += `${i ? 'L' : 'M'}${(i * w).toFixed(1)} ${y.toFixed(1)} L${((i + 1) * w).toFixed(1)} ${y.toFixed(1)} `;
    if (m.lessons.some(l => l.id === state.current?.id)) here = i;
    const label = (m.name.match(/^\d+/) || [m.name[0]])[0];
    ticks.push(`<text x="${(i * w + w / 2).toFixed(1)}" y="89" text-anchor="middle">${esc(label)}</text>`);
    hits.push(`<rect class="hit" data-module="${esc(m.name)}" x="${(i * w).toFixed(1)}" y="0" width="${w.toFixed(1)}" height="${base}"><title>${esc(m.name)}: ${d} из ${m.lessons.length}</title></rect>`);
  });
  const area = `M0 ${base} ${line.replace(/^M/, 'L')} L${W} ${base} Z`;
  const svg = $('#profileChart');
  svg.setAttribute('aria-label', `Пройдено ${done} из ${total} уроков и заданий`);
  svg.innerHTML = `
    <line class="grid" x1="0" x2="${W}" y1="${top}" y2="${top}" stroke-dasharray="3 4"/>
    <line class="grid" x1="0" x2="${W}" y1="${base}" y2="${base}"/>
    <path class="area" d="${area}"/>
    <path class="line" d="${line}"/>
    ${here >= 0 ? `<circle class="here" cx="${(here * w + w / 2).toFixed(1)}" cy="${base}" r="3.5"/>` : ''}
    <g fill="currentColor" font-size="10" opacity=".6">${ticks.join('')}</g>
    ${hits.join('')}`;
  svg.style.color = 'var(--muted)';
  $('#profileCaption').innerHTML = `Пройдено <strong>${done}</strong> из ${total}. Ступень — модуль, высота — доля пройденного.`;
}
$('#profileChart').addEventListener('click', e => {
  const name = e.target.closest('.hit')?.dataset.module;
  if (!name) return;
  state.openModules.add(name);
  renderCourse();
  document.querySelector(`.module[data-module="${CSS.escape(name)}"]`)?.scrollIntoView({ block: 'start', behavior: 'smooth' });
});

// ------------------------------------------------------------------ lesson
async function openLesson(id, section) {
  if (state.current?.id !== id) {
    let data;
    try { data = await api('/api/lesson?id=' + encodeURIComponent(id)); } catch (e) {
      $('#lessonBody').innerHTML = `<p class="err">Урок не открылся: ${esc(e.message)}</p>`;
      return;
    }
    const l = data.lesson;
    state.current = l;
    state.openModules.add(l.module);
    remember('nt-last', id);
    document.title = `${l.title} — Наставник НТ`;
    $('#crumb').textContent = l.module;
    $('#lessonTitle').textContent = l.title;
    $('#lessonBody').innerHTML = renderMd(data.markdown.replace(/^#\s+.*\n/, ''), dirOf(id));
    const isMap = l.kind === 'MAP';
    $('#actions').hidden = false;
    $('[data-act="hint"]').hidden = l.kind !== 'ASSIGNMENT';
    $('[data-act="quiz"]').hidden = isMap;
    $('[data-act="summary"]').hidden = isMap;
    $('[data-act="simple"]').hidden = isMap || l.kind === 'ASSIGNMENT';
    updateDoneToggle();
    $('#pager').innerHTML =
      (data.prev ? `<a class="prev" href="#${encodeURIComponent(data.prev.id)}"><small>Назад</small>${esc(data.prev.title)}</a>` : '') +
      (data.next ? `<a class="next" href="#${encodeURIComponent(data.next.id)}"><small>Дальше</small>${esc(data.next.title)}</a>` : '');
    $('#lessonPane').scrollTop = 0;
    renderCourse();
    document.querySelector('.lesson-link[aria-current="page"]')?.scrollIntoView({ block: 'nearest' });
    updateScope();
    if (!state.history.length) renderEmpty();
    if (state.quizFor !== id) renderQuizIntro();
  }
  setView('lesson');
  if (section) scrollToSection(section);
}
function scrollToSection(section) {
  const target = norm(section.split('›').pop());
  if (!target) return;
  const heads = [...document.querySelectorAll('#lessonBody [data-key]')];
  const h = heads.find(x => x.dataset.key === target) || heads.find(x => x.dataset.key.includes(target) || target.includes(x.dataset.key));
  if (!h) return;
  h.scrollIntoView({ block: 'start', behavior: 'smooth' });
  h.classList.remove('flash'); void h.offsetWidth; h.classList.add('flash');
}
function updateDoneToggle() {
  const on = !!progress.read[state.current?.id];
  const b = $('[data-act="done"]');
  b.setAttribute('aria-pressed', String(on));
  b.textContent = on ? 'Пройдено' : 'Отметить пройденным';
}
function route() {
  const id = decodeURIComponent(location.hash.slice(1));
  if (state.byId.has(id)) openLesson(id);
}
window.addEventListener('hashchange', route);

$('#actions').addEventListener('click', e => {
  const act = e.target.closest('button')?.dataset.act;
  const l = state.current;
  if (!act || !l) return;
  if (act === 'done') {
    if (progress.read[l.id]) delete progress.read[l.id]; else progress.read[l.id] = Date.now();
    saveProgress(); updateDoneToggle(); renderCourse();
  } else if (act === 'summary') {
    ask('/api/explain', { lessonId: l.id, kind: 'summary' }, `Конспект урока «${l.title}»`);
  } else if (act === 'simple') {
    ask('/api/explain', { lessonId: l.id, kind: 'simple' }, `Объясни проще урок «${l.title}»`);
  } else if (act === 'hint') {
    ask('/api/ask', { lessonId: l.id, mode: 'hint', question: 'С чего начать это задание? Дай подсказку, но не решение.' },
      'С чего начать это задание? Дай подсказку, но не решение.');
  } else if (act === 'quiz') {
    setTab('quiz'); setView('tutor'); startQuiz();
  }
});

// ------------------------------------------------------------------ tutor: dialog
function updateScope() {
  const l = state.current;
  $('#scope').textContent = l && l.kind !== 'MAP' ? `Контекст: ${shortTitle(l)}` : 'Контекст: весь курс';
}
function renderEmpty() {
  const l = state.current;
  const own = l && l.kind === 'ASSIGNMENT'
    ? ['С чего начать это задание?', 'Какие уроки повторить перед этим заданием?']
    : l && l.kind !== 'MAP' ? [`Какая главная мысль урока «${l.title}»?`] : [];
  const course = [
    'Чем открытая модель нагрузки отличается от закрытой?',
    'Как по закону Литтла посчитать число пользователей?',
    'Что смотреть в отчёте Gatling в первую очередь?',
  ];
  $('#thread').innerHTML = `<div class="empty">
      <h2>Спросите о курсе</h2>
      <p>Наставник отвечает по материалам курса и показывает, из какого урока взят ответ. Модель работает на вашем компьютере, без интернета.</p>
      <div class="suggest">${[...own, ...course].map(q => `<button type="button" class="chip">${esc(q)}</button>`).join('')}</div>
    </div>`;
}
$('#thread').addEventListener('click', e => {
  const chip = e.target.closest('.chip');
  if (chip) {
    const q = chip.textContent;
    const hint = state.current?.kind === 'ASSIGNMENT' && /задани/i.test(q);
    ask('/api/ask', { question: q, lessonId: state.current?.id, mode: hint ? 'hint' : 'ask' }, q);
    return;
  }
  const src = e.target.closest('[data-lesson]');
  if (src) {
    if (state.byId.has(src.dataset.lesson)) {
      if (location.hash.slice(1) !== encodeURIComponent(src.dataset.lesson)) history.pushState(null, '', '#' + encodeURIComponent(src.dataset.lesson));
      openLesson(src.dataset.lesson, src.dataset.section);
    }
  }
});

function addUser(text) {
  const el = document.createElement('div');
  el.className = 'msg user';
  const ctx = state.current && state.current.kind !== 'MAP' ? `<span class="ctx">${esc(shortTitle(state.current))}</span>` : '';
  el.innerHTML = ctx + esc(text);
  $('#thread').append(el);
}
function addBot() {
  const el = document.createElement('div');
  el.className = 'msg bot';
  el.innerHTML = `<details class="thinking" hidden open><summary>Рассуждение модели</summary><div></div></details><div class="md cursor"></div>`;
  $('#thread').append(el);
  const body = $('.md', el);
  let sources = [], text = '', frame = 0;
  const scroll = () => { const t = $('#thread'); if (t.scrollHeight - t.scrollTop - t.clientHeight < 160) t.scrollTop = t.scrollHeight; };
  const paint = () => {
    frame = 0;
    body.innerHTML = renderMd(stripThink(text), dirOf(state.current?.id || ''));
    linkCitations(body, sources);
    scroll();
  };
  return {
    sources(items) { sources = items; },
    thinking(t) { const d = $('details', el); d.hidden = false; $('div', d).textContent = t; scroll(); },
    text(t) { text = t; $('details', el).open = false; if (!frame) frame = requestAnimationFrame(paint); },
    done(stats) {
      cancelAnimationFrame(frame); paint(); body.classList.remove('cursor');
      if (!stripThink(text).trim()) body.innerHTML = '<p class="err">Модель вернула пустой ответ — попробуйте переформулировать.</p>';
      if (sources.length) {
        const ul = document.createElement('ul');
        ul.className = 'sources';
        ul.innerHTML = sources.map(s => `<li><button type="button" data-lesson="${esc(s.lessonId)}" data-section="${esc(s.section)}">`
          + `<b>[${s.n}]</b>${esc(s.lessonTitle)}${s.section && s.section !== 'весь урок' ? ' › ' + esc(s.section) : ''}</button></li>`).join('');
        el.append(ul);
      }
      if (stats) {
        const m = document.createElement('div');
        m.className = 'meta';
        m.innerHTML = `<span>${stats.outputTokens} ток.</span><span>${stats.tokensPerSecond} ток/с</span><span>${stats.seconds} с</span>`
          + (stats.doneReason === 'length' ? '<span>ответ обрезан по длине</span>' : '');
        el.append(m);
      }
      scroll();
    },
    fail(msg) { body.classList.remove('cursor'); el.insertAdjacentHTML('beforeend', `<p class="err">${esc(msg)}</p>`); scroll(); },
    stopped() { cancelAnimationFrame(frame); paint(); body.classList.remove('cursor'); el.insertAdjacentHTML('beforeend', '<div class="meta"><span>Остановлено</span></div>'); },
    get answer() { return stripThink(text); },
  };
}
/* [n] in the answer → a button that opens the source fragment; code blocks are left alone. */
function linkCitations(root, sources) {
  if (!sources.length) return;
  const byN = new Map(sources.map(s => [String(s.n), s]));
  const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, {
    acceptNode: n => (n.parentElement.closest('code, pre') || !/\[\d{1,2}]/.test(n.nodeValue) ? NodeFilter.FILTER_REJECT : NodeFilter.FILTER_ACCEPT),
  });
  const nodes = [];
  while (walker.nextNode()) nodes.push(walker.currentNode);
  for (const node of nodes) {
    const frag = document.createDocumentFragment();
    let last = 0;
    node.nodeValue.replace(/\[(\d{1,2})]/g, (m, n, at) => {
      frag.append(node.nodeValue.slice(last, at));
      const s = byN.get(n);
      if (s) {
        const b = document.createElement('button');
        b.type = 'button'; b.className = 'cite'; b.textContent = n;
        b.dataset.lesson = s.lessonId; b.dataset.section = s.section;
        b.title = s.lessonTitle + (s.section ? ' › ' + s.section : '');
        frag.append(b);
      } else frag.append(m);
      last = at + m.length;
      return m;
    });
    frag.append(node.nodeValue.slice(last));
    node.replaceWith(frag);
  }
}

async function ask(endpoint, body, userText) {
  if (state.busy) return;
  setTab('chat'); setView('tutor');
  $('.empty', $('#thread'))?.remove();
  addUser(userText);
  const bot = addBot();
  const ctrl = new AbortController();
  state.busy = ctrl;
  setBusy(`${state.status?.model || 'Модель'} отвечает…`);
  let text = '', thinking = '';
  try {
    await stream(endpoint, { ...body, history: state.history.slice(-6), think: $('#think').checked }, ev => {
      if (ev.type === 'sources') bot.sources(ev.items);
      else if (ev.type === 'thinking') { thinking += ev.text; bot.thinking(thinking); }
      else if (ev.type === 'token') { text += ev.text; bot.text(text); }
      else if (ev.type === 'done') bot.done(ev.stats);
      else if (ev.type === 'error') bot.fail(`Модель не ответила: ${ev.message}`);
    }, ctrl.signal);
  } catch (e) {
    if (e.name === 'AbortError') bot.stopped(); else bot.fail(`Нет связи с приложением: ${e.message}`);
  } finally {
    state.busy = null;
    setBusy(null);
  }
  if (bot.answer.trim()) state.history.push({ role: 'user', content: userText }, { role: 'assistant', content: bot.answer });
}

const q = $('#question');
$('#composer').addEventListener('submit', e => {
  e.preventDefault();
  const text = q.value.trim();
  if (!text || state.busy) return;
  q.value = ''; q.style.height = '';
  ask('/api/ask', { question: text, lessonId: state.current?.id, mode: state.current?.kind === 'ASSIGNMENT' ? 'hint' : 'ask' }, text);
});
$('#send').addEventListener('click', () => { if (state.busy) state.busy.abort(); });
q.addEventListener('keydown', e => {
  if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) { e.preventDefault(); $('#composer').requestSubmit(); }
});
q.addEventListener('input', () => { q.style.height = ''; q.style.height = Math.min(q.scrollHeight, 160) + 'px'; });
$('#newChat').addEventListener('click', () => {
  if (state.busy) state.busy.abort();
  state.history = [];
  renderEmpty();
});
$('#think').addEventListener('change', () => remember('nt-think', $('#think').checked ? '1' : '0'));

// ------------------------------------------------------------------ tutor: quiz
function renderQuizIntro() {
  const l = state.current;
  state.quizFor = null;
  if (!l || l.kind === 'MAP') {
    $('#quiz').innerHTML = '<div class="empty"><h2>Проверь себя</h2><p>Откройте урок — наставник составит по нему тест.</p></div>';
    return;
  }
  const best = progress.quiz[l.id];
  $('#quiz').innerHTML = `<div class="empty">
      <h2>Проверь себя</h2>
      <p>Четыре вопроса с выбором ответа по уроку «${esc(l.title)}». Вопросы составляются из случайных фрагментов урока, поэтому каждый раз новые.</p>
      ${best ? `<p>Лучший результат: ${best.best} из ${best.total}.</p>` : ''}
      <button type="button" class="primary" data-quiz="start">Составить вопросы</button>
    </div>`;
}
async function startQuiz() {
  const l = state.current;
  if (!l || l.kind === 'MAP' || state.busy) return;
  const box = $('#quiz');
  const t0 = Date.now();
  let chars = 0, questions = null, stats = null, error = null;
  box.innerHTML = `<p class="quiz-progress" id="qp">Составляю вопросы по уроку «${esc(l.title)}»…</p><div class="bar"><i></i></div>`;
  const tick = setInterval(() => {
    const p = $('#qp');
    if (p) p.textContent = `Составляю вопросы по уроку «${l.title}»… ${Math.round((Date.now() - t0) / 1000)} с, получено ${chars} символов`;
  }, 500);
  const ctrl = new AbortController();
  state.busy = ctrl;
  setBusy(`${state.status?.model || 'Модель'} составляет тест…`);
  try {
    await stream('/api/quiz', { lessonId: l.id, count: 4, think: $('#think').checked }, ev => {
      if (ev.type === 'progress') chars += ev.chars;
      else if (ev.type === 'quiz') questions = ev.questions;
      else if (ev.type === 'done') stats = ev.stats;
      else if (ev.type === 'error') error = ev.message;
    }, ctrl.signal);
  } catch (e) {
    error = e.name === 'AbortError' ? 'Остановлено.' : e.message;
  } finally {
    clearInterval(tick);
    state.busy = null;
    setBusy(null);
  }
  if (state.current?.id !== l.id) return;
  if (error || !questions?.length) {
    box.innerHTML = `<p class="err">${esc(error || 'Модель не смогла составить корректные вопросы.')}</p><button type="button" class="primary" data-quiz="start">Попробовать ещё раз</button>`;
    return;
  }
  state.quizFor = l.id;
  renderQuestions(l, questions, stats);
}
function renderQuestions(l, questions, stats) {
  const box = $('#quiz');
  box.innerHTML = `<div id="qResult"></div>` + questions.map((qq, i) => `
    <section class="q" data-i="${i}">
      <h3><span>${i + 1}.</span>${esc(qq.question)}</h3>
      <ol>${qq.options.map((o, j) => `<li><button type="button" class="opt" data-j="${j}">${esc(o)}</button></li>`).join('')}</ol>
    </section>`).join('')
    + (stats ? `<div class="meta"><span>${stats.outputTokens} ток.</span><span>${stats.tokensPerSecond} ток/с</span><span>${stats.seconds} с</span></div>` : '');
  let answered = 0, right = 0;
  box.onclick = e => {
    const opt = e.target.closest('.opt');
    if (opt && !opt.disabled) {
      const sec = opt.closest('.q');
      const qq = questions[+sec.dataset.i];
      const j = +opt.dataset.j;
      sec.querySelectorAll('.opt').forEach((b, k) => {
        b.disabled = true;
        if (k === qq.correct) b.dataset.state = 'right'; else if (k === j) b.dataset.state = 'wrong';
      });
      const ok = j === qq.correct;
      answered++; if (ok) right++;
      sec.insertAdjacentHTML('beforeend', `<p class="why"><strong>${ok ? 'Верно.' : 'Неверно.'}</strong> ${esc(qq.explanation)}`
        + (qq.section ? ` <button type="button" class="chip" data-lesson="${esc(l.id)}" data-section="${esc(qq.section)}">К разделу «${esc(qq.section)}»</button>` : '') + '</p>');
      if (answered === questions.length) finishQuiz(l, right, questions.length);
      return;
    }
    const src = e.target.closest('[data-lesson]');
    if (src) openLesson(src.dataset.lesson, src.dataset.section);
  };
}
function finishQuiz(l, right, total) {
  const prev = progress.quiz[l.id];
  if (!prev || right / total > prev.best / prev.total) progress.quiz[l.id] = { best: right, total };
  const passed = right / total >= 0.75;
  if (passed && !progress.read[l.id]) progress.read[l.id] = Date.now();
  saveProgress(); renderCourse(); updateDoneToggle();
  $('#qResult').innerHTML = `<p class="quiz-score">${right} из ${total}</p>
    <p class="quiz-progress">${passed ? 'Урок отмечен пройденным.' : 'Перечитайте разделы, где были ошибки, и попробуйте новые вопросы.'}</p>
    <p><button type="button" class="primary" data-quiz="start">Новые вопросы</button></p>`;
  $('#quiz').scrollTop = 0;
}
$('#quizPanel').addEventListener('click', e => { if (e.target.closest('[data-quiz="start"]')) startQuiz(); });

// ------------------------------------------------------------------ tabs + mobile views
function setTab(tab) {
  document.querySelectorAll('[role="tab"]').forEach(t => t.setAttribute('aria-selected', String(t.dataset.tab === tab)));
  $('#chatPanel').hidden = tab !== 'chat';
  $('#quizPanel').hidden = tab !== 'quiz';
  $('#newChat').hidden = tab !== 'chat';
}
document.querySelectorAll('[role="tab"]').forEach(t => t.addEventListener('click', () => setTab(t.dataset.tab)));
function setView(view) {
  $('#layout').dataset.view = view;
  document.querySelectorAll('.mobile-tabs button').forEach(b => b.setAttribute('aria-current', String(b.dataset.view === view)));
}
document.querySelectorAll('.mobile-tabs button').forEach(b => b.addEventListener('click', () => setView(b.dataset.view)));
$('#modules').addEventListener('click', e => { if (e.target.closest('.lesson-link')) setView('lesson'); });

// ------------------------------------------------------------------ start
(async function init() {
  $('#think').checked = recall('nt-think') === '1';
  refreshStatus().then(() => { if (recall('nt-think') === null && state.status?.thinkDefault) $('#think').checked = true; });
  setInterval(() => { if (!state.busy) refreshStatus(); }, 20000);
  try {
    state.course = await api('/api/course');
  } catch (e) {
    $('#lessonTitle').textContent = 'Курс не загрузился';
    $('#lessonBody').innerHTML = `<p class="err">${esc(e.message)}</p>`;
    return;
  }
  $('#courseName').textContent = state.course.title;
  for (const m of state.course.modules) for (const l of m.lessons) { state.byId.set(l.id, l); state.order.push(l.id); }
  renderEmpty();
  const fromHash = decodeURIComponent(location.hash.slice(1));
  const start = state.byId.has(fromHash) ? fromHash : (state.byId.has(recall('nt-last')) ? recall('nt-last') : state.order[0]);
  await openLesson(start);
  if (window.matchMedia('(max-width: 900px)').matches) setView('lesson');
})();
