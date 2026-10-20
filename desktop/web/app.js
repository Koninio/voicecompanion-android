/* VoiceCompanion frontend — plain JS, no dependencies, works offline in WebView2.
 *
 * Contract: docs/INTERFACES.md
 *   client -> server (WS): chat, voice, set_persona, interrupt, speak_replies
 *   server -> client (WS): user_message, assistant_start, assistant_delta, assistant_end,
 *                          voice_state, level, barge_in, status, error, personas_changed
 * Phase 2 (docs/ROLEPLAY.md): character editor over /api/characters*, /api/models,
 *   llm.model_path / llm.n_cpu_moe in settings.
 */
(() => {
  'use strict';

  const $ = (id) => document.getElementById(id);

  const S = {
    personas: [],
    byId: {},
    current: null,
    config: null,
    speakReplies: true,
    // websocket
    ws: null,
    wsOpen: false,
    everConnected: false,
    reconnectDelay: 500,
    reconnectTimer: null,
    // chat
    msgs: {},            // id -> { el, body, text, persona }
    ignored: new Set(),  // assistant ids for other personas
    pendingUser: [],     // optimistic user bubbles awaiting server echo
    generatingId: null,
    awaitingReply: false,
    historyToken: 0,
    // voice
    voiceOpen: false,
    voicePaused: false,
    voiceState: 'idle',
    thinking: false,
    level: 0,
    levelTarget: 0,
    rafId: 0,
    // settings
    settingsApiKeyInitial: '',
    settingsLlmModel: '',
    settingsMoe: null,
    personasToken: 0,
  };

  // Character editor state
  const CE = {
    isNew: true,
    id: null,
    orig: null,        // character as loaded from the server (or defaults for a new one)
    builtin: false,
    overridden: false,
    tags: [],
    baseline: '',      // JSON of the draft right after loading -> unsaved-changes guard
    busy: false,
  };

  const els = {
    app: $('app'),
    personaList: $('personaList'),
    newChatBtn: $('newChatBtn'),
    settingsBtn: $('settingsBtn'),
    statusLLM: $('statusLLM'),
    statusSTT: $('statusSTT'),
    statusTTS: $('statusTTS'),
    headerEmoji: $('headerEmoji'),
    headerName: $('headerName'),
    headerTagline: $('headerTagline'),
    connIndicator: $('connIndicator'),
    sidebarToggle: $('sidebarToggle'),
    scroll: $('messagesScroll'),
    messages: $('messages'),
    composer: $('composer'),
    input: $('input'),
    sendBtn: $('sendBtn'),
    micBtn: $('micBtn'),
    voicePanel: $('voicePanel'),
    voiceEmoji: $('voiceEmoji'),
    voiceName: $('voiceName'),
    voiceCloseBtn: $('voiceCloseBtn'),
    orb: $('orb'),
    orbHalo: $('orbHalo'),
    voiceStateLabel: $('voiceStateLabel'),
    vtUser: $('vtUser'),
    vtBot: $('vtBot'),
    muteBtn: $('muteBtn'),
    muteLabel: $('muteLabel'),
    endCallBtn: $('endCallBtn'),
    voiceHint: document.querySelector('.voice-hint'),
    modal: $('settingsModal'),
    settingsForm: $('settingsForm'),
    settingsCloseBtn: $('settingsCloseBtn'),
    settingsCancelBtn: $('settingsCancelBtn'),
    settingsSaveBtn: $('settingsSaveBtn'),
    settingsMsg: $('settingsMsg'),
    apiKey: $('setApiKey'),
    apiKeyHelp: $('apiKeyHelp'),
    cloudModel: $('setCloudModel'),
    latency: $('setLatency'),
    cloudRefList: $('cloudRefList'),
    localUrl: $('setLocalUrl'),
    localRefList: $('localRefList'),
    speakRepliesChk: $('setSpeakReplies'),
    sttModel: $('setSttModel'),
    sttDevice: $('setSttDevice'),
    silence: $('setSilence'),
    silenceValue: $('silenceValue'),
    mic: $('setMic'),
    speaker: $('setSpeaker'),
    llmModel: $('setLlmModel'),
    llmModelHelp: $('llmModelHelp'),
    nCpuMoe: $('setNCpuMoe'),
    llmRestartNote: $('llmRestartNote'),
    toastArea: $('toastArea'),
    // character editor
    newCharBtn: $('newCharBtn'),
    charModal: $('charModal'),
    charForm: $('charForm'),
    charTitle: $('charTitle'),
    charBadge: $('charBadge'),
    charCloseBtn: $('charCloseBtn'),
    charCancelBtn: $('charCancelBtn'),
    charSaveBtn: $('charSaveBtn'),
    charPreviewBtn: $('charPreviewBtn'),
    charDupBtn: $('charDupBtn'),
    charDeleteBtn: $('charDeleteBtn'),
    charMsg: $('charMsg'),
    ceNav: $('ceNav'),
    ceAvatarPreview: $('ceAvatarPreview'),
    ceNameKo: $('ceNameKo'),
    ceNameErr: $('ceNameErr'),
    ceName: $('ceName'),
    ceTagline: $('ceTagline'),
    ceEmoji: $('ceEmoji'),
    ceEmojiPicks: $('ceEmojiPicks'),
    ceColor: $('ceColor'),
    ceColorPicks: $('ceColorPicks'),
    ceCharName: $('ceCharName'),
    ceUserName: $('ceUserName'),
    cePersonality: $('cePersonality'),
    ceSpeaking: $('ceSpeaking'),
    ceRelationship: $('ceRelationship'),
    ceBackground: $('ceBackground'),
    ceScenario: $('ceScenario'),
    ceRules: $('ceRules'),
    ceRulesEmpty: $('ceRulesEmpty'),
    ceAddRule: $('ceAddRule'),
    ceExamples: $('ceExamples'),
    ceExamplesEmpty: $('ceExamplesEmpty'),
    ceAddExample: $('ceAddExample'),
    ceGreeting: $('ceGreeting'),
    ceGreetingPreview: $('ceGreetingPreview'),
    ceTagBox: $('ceTagBox'),
    ceTagChips: $('ceTagChips'),
    ceTagInput: $('ceTagInput'),
    ceTagSuggest: $('ceTagSuggest'),
    ceCloudRef: $('ceCloudRef'),
    ceLocalRef: $('ceLocalRef'),
    ceSpeed: $('ceSpeed'),
    ceSpeedValue: $('ceSpeedValue'),
    ceTemp: $('ceTemp'),
    ceTempValue: $('ceTempValue'),
    ceOverrideToggle: $('ceOverrideToggle'),
    ceOverrideWrap: $('ceOverrideWrap'),
    ceOverride: $('ceOverride'),
    ceOverrideFill: $('ceOverrideFill'),
    // prompt preview / confirm
    promptModal: $('promptModal'),
    promptSystem: $('promptSystem'),
    promptReminder: $('promptReminder'),
    promptMsg: $('promptMsg'),
    promptCloseBtn: $('promptCloseBtn'),
    promptOkBtn: $('promptOkBtn'),
    confirmModal: $('confirmModal'),
    confirmTitle: $('confirmTitle'),
    confirmText: $('confirmText'),
    confirmOkBtn: $('confirmOkBtn'),
    confirmCancelBtn: $('confirmCancelBtn'),
  };

  const MIC_SVG = '<svg viewBox="0 0 24 24" width="13" height="13" aria-hidden="true"><rect x="9" y="3" width="6" height="11" rx="3" fill="none" stroke="currentColor" stroke-width="2.2"/><path d="M5.5 11a6.5 6.5 0 0 0 13 0M12 17.5V21" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round"/></svg>';

  /* ------------------------------------------------------------------ utils */

  function escapeHtml(s) {
    return String(s).replace(/[&<>"']/g, (c) => ({
      '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
    })[c]);
  }

  // Light markdown: escape HTML first, then `code`, **bold**, *italic*, line breaks.
  function renderMarkdown(src) {
    let s = escapeHtml(src || '');
    const codes = [];
    s = s.replace(/`([^`\n]+)`/g, (m, c) => { codes.push(c); return '\u0000' + (codes.length - 1) + '\u0000'; });
    s = s.replace(/\*\*([^*\n]+?)\*\*/g, '<strong>$1</strong>');
    s = s.replace(/__([^_\n]+?)__/g, '<strong>$1</strong>');
    s = s.replace(/(^|[^*])\*([^*\n]+?)\*(?!\*)/g, '$1<em>$2</em>');
    s = s.replace(/(^|[^\w])_([^_\n]+?)_(?!\w)/g, '$1<em>$2</em>');
    s = s.replace(/\u0000(\d+)\u0000/g, (m, i) => '<code>' + codes[Number(i)] + '</code>');
    // Drama 3 / S2 delivery tags: [direction], <emphasis>…</emphasis>, <whisper>…</whisper>
    s = s.replace(/\[([^\[\]\n]{1,60})\]/g, '<span class="etag">$1</span>');
    s = s.replace(/&lt;emphasis&gt;([\s\S]*?)&lt;\/emphasis&gt;/gi, '<strong class="emph">$1</strong>');
    s = s.replace(/&lt;whisper&gt;([\s\S]*?)&lt;\/whisper&gt;/gi, '<span class="whisper">$1</span>');
    s = s.replace(/&lt;\/[^&\n]{0,20}&gt;/g, '');  // stray / broken closing tags
    // model sometimes writes <단어> for emphasis
    s = s.replace(/&lt;(?![A-Za-z\/!])([^&\n]{1,40}?)&gt;/g, '<strong class="emph">$1</strong>');
    s = s.replace(/\r?\n/g, '<br>');
    return s;
  }

  function toast(text, isError) {
    const t = document.createElement('div');
    t.className = 'toast' + (isError ? ' err' : '');
    t.textContent = text;
    els.toastArea.appendChild(t);
    setTimeout(() => t.remove(), isError ? 6000 : 2800);
  }

  async function api(path, opts) {
    const o = Object.assign({ headers: {} }, opts || {});
    if (o.json !== undefined) {
      o.body = JSON.stringify(o.json);
      o.headers['Content-Type'] = 'application/json';
      delete o.json;
    }
    const res = await fetch(path, o);
    if (!res.ok) {
      let detail = '';
      try {
        const j = await res.json();
        detail = j.detail || j.message || '';
        // FastAPI 422: detail is a list of {loc, msg}
        if (Array.isArray(detail)) detail = detail.map((d) => (d && (d.msg || d.message)) || String(d)).join(', ');
        else if (detail && typeof detail === 'object') detail = JSON.stringify(detail);
      } catch (e) { /* ignore */ }
      const err = new Error(`${res.status} ${detail || res.statusText}`);
      err.status = res.status;
      err.detail = String(detail || '');
      throw err;
    }
    const ct = res.headers.get('content-type') || '';
    return ct.includes('application/json') ? res.json() : null;
  }

  function getPath(obj, path, dflt) {
    let cur = obj;
    for (const k of path.split('.')) {
      if (cur == null || typeof cur !== 'object' || !(k in cur)) return dflt;
      cur = cur[k];
    }
    return cur == null ? dflt : cur;
  }

  function persona() { return S.byId[S.current] || null; }
  function personaName() { const p = persona(); return p ? (p.name_ko || p.name || p.id) : ''; }

  function store(key, val) { try { localStorage.setItem(key, val); } catch (e) { /* ignore */ } }
  function load(key) { try { return localStorage.getItem(key); } catch (e) { return null; } }

  /* --------------------------------------------------------------- websocket */

  function wsSend(type, payload) {
    if (!S.ws || S.ws.readyState !== WebSocket.OPEN) return false;
    S.ws.send(JSON.stringify(Object.assign({ type }, payload || {})));
    return true;
  }

  function setConn(state) {
    els.connIndicator.dataset.state = state;
    els.connIndicator.textContent = state === 'open' ? '연결됨' : state === 'closed' ? '연결 끊김 · 재연결 중…' : '연결 중…';
    updateVoiceUI();
  }

  function connect() {
    clearTimeout(S.reconnectTimer);
    const url = (location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + '/ws';
    let ws;
    try { ws = new WebSocket(url); } catch (e) { scheduleReconnect(); return; }
    S.ws = ws;
    setConn('connecting');

    ws.onopen = () => {
      const reconnect = S.everConnected;
      S.wsOpen = true;
      S.everConnected = true;
      S.reconnectDelay = 500;
      setConn('open');
      if (S.current) wsSend('set_persona', { persona: S.current });
      wsSend('speak_replies', { enabled: !!S.speakReplies });
      if (S.voiceOpen && !S.voicePaused) wsSend('voice', { action: 'start', persona: S.current });
      if (reconnect && S.current) loadHistory();
    };
    ws.onmessage = (ev) => {
      let msg;
      try { msg = JSON.parse(ev.data); } catch (e) { return; }
      const h = handlers[msg && msg.type];
      if (h) {
        try { h(msg); } catch (e) { console.error('handler failed', msg.type, e); }
      }
    };
    ws.onclose = () => {
      if (S.ws !== ws) return;
      S.wsOpen = false;
      setConn('closed');
      finishGenerating();
      scheduleReconnect();
    };
    ws.onerror = () => { /* onclose follows */ };
  }

  function scheduleReconnect() {
    clearTimeout(S.reconnectTimer);
    S.reconnectTimer = setTimeout(connect, S.reconnectDelay);
    S.reconnectDelay = Math.min(5000, Math.round(S.reconnectDelay * 1.7));
  }

  /* -------------------------------------------- server -> client handlers */

  const handlers = {
    user_message(m) {
      const source = m.source === 'voice' ? 'voice' : 'text';
      let adopted = false;
      if (source === 'text') {
        const idx = S.pendingUser.findIndex((p) => p.text === (m.text || '').trim() || p.text === m.text);
        if (idx >= 0) {
          const p = S.pendingUser.splice(idx, 1)[0];
          if (m.id != null) p.el.dataset.id = m.id;
          adopted = true;
        }
      }
      if (!adopted) addUserMessage(m.text || '', source, m.id);
      S.awaitingReply = true;
      setGenerating(true);
      if (source === 'voice') {
        els.vtUser.textContent = m.text || '';
        els.vtBot.textContent = '';
        S.thinking = true;
        updateVoiceUI();
      }
    },

    assistant_start(m) {
      if (m.persona && S.current && m.persona !== S.current) { S.ignored.add(m.id); return; }
      ensureAssistant(m.id, m.persona || S.current);
      S.generatingId = m.id;
      S.awaitingReply = false;
      setGenerating(true);
      els.vtBot.textContent = '';
      els.vtBot.dataset.name = personaName();
    },

    assistant_delta(m) {
      if (S.ignored.has(m.id)) return;
      const rec = ensureAssistant(m.id, S.current);
      rec.text += m.text || '';
      const stick = nearBottom();
      rec.body.classList.remove('waiting');
      rec.body.innerHTML = renderMarkdown(rec.text);
      if (S.voiceOpen) {
        els.vtBot.dataset.name = personaName();
        els.vtBot.textContent = rec.text;
        els.vtBot.parentElement.scrollTop = els.vtBot.parentElement.scrollHeight;
      }
      if (stick) scrollToBottom();
    },

    assistant_end(m) {
      if (S.ignored.has(m.id)) { S.ignored.delete(m.id); finishGenerating(); return; }
      const rec = S.msgs[m.id];
      if (rec) {
        const text = typeof m.text === 'string' && m.text.length ? m.text : rec.text;
        rec.text = text;
        rec.body.classList.remove('streaming', 'waiting');
        if (!text && !m.interrupted) {
          rec.el.remove();
          delete S.msgs[m.id];
        } else {
          const stick = nearBottom();
          rec.body.innerHTML = renderMarkdown(text);
          if (m.interrupted) {
            const note = document.createElement('span');
            note.className = 'interrupted-note';
            note.textContent = '(중단됨)';
            rec.body.appendChild(note);
          }
          if (S.voiceOpen) els.vtBot.textContent = text;
          if (stick) scrollToBottom();
        }
      }
      if (S.generatingId === m.id || S.generatingId == null) finishGenerating();
      S.thinking = false;
      updateVoiceUI();
    },

    voice_state(m) {
      S.voiceState = m.state || 'idle';
      if (S.voiceState === 'speaking' || S.voiceState === 'user_speaking') S.thinking = false;
      updateVoiceUI();
    },

    level(m) {
      const v = Number(m.rms);
      if (!Number.isNaN(v)) S.levelTarget = Math.max(0, Math.min(1, v));
    },

    barge_in() {
      S.thinking = false;
      S.levelTarget = Math.max(S.levelTarget, 0.25);
      updateVoiceUI();
    },

    status(m) { renderStatus(m); },

    personas_changed() { refreshPersonas(); },

    error(m) {
      toast(m.message || '알 수 없는 오류가 발생했어요', true);
      if (S.awaitingReply && !S.generatingId) finishGenerating();
    },
  };

  /* ------------------------------------------------------------------ chat */

  function nearBottom() {
    const el = els.scroll;
    return el.scrollHeight - el.scrollTop - el.clientHeight < 90;
  }
  function scrollToBottom() { els.scroll.scrollTop = els.scroll.scrollHeight; }

  function addUserMessage(text, source, id) {
    const wrap = document.createElement('div');
    wrap.className = 'msg user';
    if (id != null) wrap.dataset.id = id;
    const bubble = document.createElement('div');
    bubble.className = 'bubble';
    bubble.innerHTML = (source === 'voice' ? '<span class="voice-badge" title="음성으로 입력됨">' + MIC_SVG + '</span>' : '') + renderMarkdown(text);
    wrap.appendChild(bubble);
    els.messages.appendChild(wrap);
    scrollToBottom();
    return wrap;
  }

  function addAssistantMessage(text, personaId, opts) {
    const p = S.byId[personaId] || persona() || {};
    const wrap = document.createElement('div');
    wrap.className = 'msg assistant' + (opts && opts.greeting ? ' greeting' : '');
    wrap.style.setProperty('--pc', p.color || 'var(--accent)');
    const av = document.createElement('div');
    av.className = 'avatar';
    av.textContent = p.avatar_emoji || '🙂';
    av.title = p.name_ko || p.name || '';
    const body = document.createElement('div');
    body.className = 'body';
    body.innerHTML = renderMarkdown(text || '');
    wrap.appendChild(av);
    wrap.appendChild(body);
    els.messages.appendChild(wrap);
    return { el: wrap, body, text: text || '', persona: personaId };
  }

  function ensureAssistant(id, personaId) {
    let rec = S.msgs[id];
    if (!rec) {
      const stick = nearBottom();
      rec = addAssistantMessage('', personaId);
      rec.body.classList.add('streaming', 'waiting');
      if (id != null) rec.el.dataset.id = id;
      S.msgs[id] = rec;
      if (stick) scrollToBottom();
    }
    return rec;
  }

  function setGenerating(on) {
    els.sendBtn.classList.toggle('stop', !!on);
    els.sendBtn.title = on ? '중지' : '보내기';
    els.sendBtn.setAttribute('aria-label', on ? '중지' : '보내기');
    updateSendEnabled();
  }

  function finishGenerating() {
    if (S.generatingId != null) {
      const rec = S.msgs[S.generatingId];
      if (rec) rec.body.classList.remove('streaming', 'waiting');
    }
    S.generatingId = null;
    S.awaitingReply = false;
    setGenerating(false);
  }

  function isGenerating() { return S.generatingId != null || S.awaitingReply; }

  function updateSendEnabled() {
    els.sendBtn.disabled = !isGenerating() && !els.input.value.trim();
  }

  function sendChat() {
    const text = els.input.value.trim();
    if (!text) return;
    if (!S.current) { toast('페르소나를 불러오지 못했어요', true); return; }
    if (!S.wsOpen) { toast('서버에 연결되어 있지 않아요. 잠시 후 다시 시도해 주세요.', true); return; }
    if (isGenerating()) wsSend('interrupt');
    const el = addUserMessage(text, 'text');
    S.pendingUser.push({ text, el });
    if (S.pendingUser.length > 5) S.pendingUser.shift();
    wsSend('chat', { persona: S.current, text });
    els.input.value = '';
    autosize();
    S.awaitingReply = true;
    setGenerating(true);
  }

  function interrupt() {
    wsSend('interrupt');
    S.thinking = false;
    updateVoiceUI();
  }

  function autosize() {
    const ta = els.input;
    ta.style.height = 'auto';
    ta.style.height = Math.min(ta.scrollHeight, 220) + 'px';
    updateSendEnabled();
  }

  async function loadHistory() {
    const pid = S.current;
    const token = ++S.historyToken;
    let hist = [];
    try {
      hist = await api('/api/history?persona=' + encodeURIComponent(pid)) || [];
    } catch (e) {
      toast('대화 기록을 불러오지 못했어요: ' + e.message, true);
    }
    if (token !== S.historyToken || pid !== S.current) return;
    els.messages.innerHTML = '';
    S.msgs = {};
    S.pendingUser = [];
    const p = persona();
    if (!hist.length) {
      addAssistantMessage((p && p.greeting) || '안녕!', pid, { greeting: true });
    } else {
      for (const h of hist) {
        if (h.role === 'user') addUserMessage(h.content || '', h.source === 'voice' ? 'voice' : 'text');
        else if (h.role === 'assistant') addAssistantMessage(h.content || '', pid);
      }
    }
    // A reply may currently be streaming for this persona; keep a placeholder for it.
    if (S.generatingId != null && !S.ignored.has(S.generatingId)) ensureAssistant(S.generatingId, pid);
    scrollToBottom();
  }

  async function newChat() {
    if (!S.current) return;
    if (isGenerating()) interrupt();
    try {
      await api('/api/history?persona=' + encodeURIComponent(S.current), { method: 'DELETE' });
    } catch (e) {
      toast('대화를 초기화하지 못했어요: ' + e.message, true);
      return;
    }
    finishGenerating();
    els.vtUser.textContent = '';
    els.vtBot.textContent = '';
    await loadHistory();
    els.input.focus();
  }

  /* -------------------------------------------------------------- personas */

  const EDIT_SVG = '<svg viewBox="0 0 24 24" width="15" height="15" aria-hidden="true"><path d="M4 20h4L19 9l-4-4L4 16z M13.5 6.5l4 4" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linejoin="round" stroke-linecap="round"/></svg>';

  function renderPersonas() {
    els.personaList.innerHTML = '';
    if (!S.personas.length) {
      const e = document.createElement('div');
      e.className = 'persona-list-empty';
      e.textContent = '아직 캐릭터가 없어요. 아래에서 만들어 보세요.';
      els.personaList.appendChild(e);
      return;
    }
    for (const p of S.personas) {
      const item = document.createElement('div');
      item.className = 'persona-item';
      const b = document.createElement('button');
      b.type = 'button';
      b.className = 'persona-card' + (p.id === S.current ? ' active' : '');
      b.dataset.id = p.id;
      b.style.setProperty('--pc', p.color || 'var(--accent)');
      b.innerHTML =
        '<span class="pc-avatar">' + escapeHtml(p.avatar_emoji || '🙂') + '</span>' +
        '<span class="pc-text"><span class="pc-name">' + escapeHtml(p.name_ko || p.name || p.id) + '</span>' +
        '<span class="pc-tag">' + escapeHtml(p.tagline || '') + '</span></span>';
      b.title = p.tagline || '';
      b.addEventListener('click', () => { selectPersona(p.id); els.app.classList.remove('sidebar-open'); });
      const edit = document.createElement('button');
      edit.type = 'button';
      edit.className = 'pc-edit';
      edit.title = '캐릭터 편집';
      edit.setAttribute('aria-label', `${p.name_ko || p.name || p.id} 편집`);
      edit.innerHTML = EDIT_SVG;
      edit.addEventListener('click', (e) => {
        e.stopPropagation();
        els.app.classList.remove('sidebar-open');
        openCharEditor(p.id);
      });
      item.appendChild(b);
      item.appendChild(edit);
      els.personaList.appendChild(item);
    }
    const active = els.personaList.querySelector('.persona-card.active');
    if (active && active.scrollIntoView) active.scrollIntoView({ block: 'nearest' });
  }

  function setPersonas(list) {
    S.personas = Array.isArray(list) ? list : [];
    S.byId = Object.fromEntries(S.personas.map((p) => [p.id, p]));
  }

  // Re-fetch the character list (after personas_changed or a local edit).
  async function refreshPersonas(selectId) {
    const token = ++S.personasToken;
    let list;
    try { list = await api('/api/personas'); } catch (e) { return; }
    if (token !== S.personasToken || !Array.isArray(list)) return;
    const before = persona();
    setPersonas(list);
    renderPersonas();
    if (selectId && S.byId[selectId]) {
      if (selectId !== S.current) { selectPersona(selectId); return; }
    }
    if (!S.current || !S.byId[S.current]) {
      if (S.personas.length) {
        const dflt = getPath(S.config, 'ui.default_persona', null);
        selectPersona(S.byId[dflt] ? dflt : S.personas[0].id, true);
      } else {
        S.current = null;
        applyPersonaTheme();
      }
      return;
    }
    applyPersonaTheme();
    // If the chat only shows the (old) greeting, show the new one.
    const after = persona();
    const onlyGreeting = els.messages.children.length === 1 && els.messages.firstElementChild.classList.contains('greeting');
    if (onlyGreeting && !isGenerating() && (!before || before.greeting !== after.greeting || before.avatar_emoji !== after.avatar_emoji || before.color !== after.color)) {
      loadHistory();
    }
  }

  function applyPersonaTheme() {
    const p = persona();
    const color = (p && p.color) || '#c96442';
    document.documentElement.style.setProperty('--persona', color);
    els.headerEmoji.textContent = p ? (p.avatar_emoji || '') : '';
    els.headerName.textContent = p ? (p.name_ko || p.name) : 'VoiceCompanion';
    els.headerTagline.textContent = p ? (p.tagline || '') : '';
    els.voiceEmoji.textContent = p ? (p.avatar_emoji || '') : '';
    els.voiceName.textContent = p ? (p.name_ko || p.name) : '음성 대화';
    els.vtBot.dataset.name = personaName();
    els.input.placeholder = p ? `${p.name_ko || p.name}에게 메시지 보내기…` : '메시지를 입력하세요…';
    for (const c of els.personaList.querySelectorAll('.persona-card')) {
      c.classList.toggle('active', c.dataset.id === S.current);
    }
  }

  function selectPersona(pid, force) {
    if (!S.byId[pid]) return;
    if (pid === S.current && !force) return;
    const wasVoice = S.voiceOpen && !S.voicePaused;
    if (isGenerating()) interrupt();
    finishGenerating();
    if (wasVoice && S.current) wsSend('voice', { action: 'stop', persona: S.current });
    S.current = pid;
    store('vc.persona', pid);
    wsSend('set_persona', { persona: pid });
    if (wasVoice) wsSend('voice', { action: 'start', persona: pid });
    S.thinking = false;
    els.vtUser.textContent = '';
    els.vtBot.textContent = '';
    applyPersonaTheme();
    updateVoiceUI();
    loadHistory();
  }

  /* ---------------------------------------------------------------- status */

  function setStatusRow(row, dot, text, tip) {
    row.querySelector('.dot').dataset.state = dot;
    row.querySelector('.status-text').textContent = text;
    row.title = tip || text;
  }

  function renderStatus(s) {
    if (!s) return;
    const llm = s.llm;
    const llmMsg = s.llm_message || s.llm_error || '';
    const llmMap = {
      ready: ['ok', '준비됨', 'Gemma 모델이 준비되었어요'],
      starting: ['loading', '로딩 중', 'llama-server가 모델을 불러오는 중이에요'],
      stopped: ['off', '중지됨', 'LLM 서버가 실행 중이 아니에요'],
      error: ['error', '오류', 'LLM 서버 오류'],
      missing: ['error', '파일 없음', 'LLM 모델(.gguf) 또는 bin/llama/llama-server.exe를 찾을 수 없어요. download_models.py를 실행해 주세요.'],
    };
    const l = llmMap[llm] || ['loading', '로딩 중', String(llm || '')];
    setStatusRow(els.statusLLM, l[0], l[1], [l[2], llmMsg].filter(Boolean).join(' — '));

    const stt = s.stt;
    const sttMsg = s.stt_message || s.stt_error || '';
    const dev = s.stt_device ? ` (${s.stt_device})` : '';
    const sttMap = {
      ready: ['ok', '준비됨' + dev, 'Whisper 음성 인식 준비됨' + dev],
      loading: ['loading', '로딩 중', 'Whisper 모델을 불러오는 중이에요'],
      unloaded: ['loading', '대기 중', 'Whisper 모델을 아직 불러오지 않았어요'],
      error: ['error', '오류', 'Whisper 모델 오류'],
    };
    const t = sttMap[stt] || ['loading', '로딩 중', String(stt || '')];
    setStatusRow(els.statusSTT, t[0], t[1], [t[2], sttMsg].filter(Boolean).join(' — '));

    const tts = s.tts || {};
    const modeName = { cloud: '클라우드', local: '로컬', off: '꺼짐' }[tts.mode] || (tts.mode || '');
    if (tts.mode === 'off') {
      setStatusRow(els.statusTTS, 'off', '꺼짐', '음성 합성이 꺼져 있어요 (설정에서 변경)');
    } else if (tts.ok === true) {
      setStatusRow(els.statusTTS, 'ok', `준비됨 · ${modeName}`, tts.message || `${modeName} TTS 준비됨`);
    } else if (tts.ok === false) {
      setStatusRow(els.statusTTS, 'error', `오류 · ${modeName}`, tts.message || 'TTS 오류');
    } else {
      setStatusRow(els.statusTTS, 'loading', '확인 중', tts.message || '');
    }

    if (S.voiceOpen && !S.voicePaused && s.voice_active === false && S.wsOpen && S.voiceState !== 'idle') {
      S.voiceState = 'idle';
      updateVoiceUI();
    }
  }

  /* ----------------------------------------------------------------- voice */

  function voiceLabel() {
    if (!S.wsOpen) return '연결 끊김 · 다시 연결 중…';
    if (S.voicePaused) return '일시정지됨';
    switch (S.voiceState) {
      case 'speaking': return `${personaName()} 말하는 중`;
      case 'user_speaking': return '말하는 중…(당신)';
      case 'transcribing': return '알아듣는 중…';
      default: break;
    }
    if (S.thinking) return '생각 중…';
    if (S.voiceState === 'listening') return '듣는 중…';
    return '준비 중…';
  }

  function orbState() {
    if (S.voicePaused || !S.wsOpen) return 'paused';
    if (S.voiceState === 'speaking') return 'speaking';
    if (S.voiceState === 'user_speaking') return 'user_speaking';
    if (S.voiceState === 'transcribing') return 'transcribing';
    if (S.thinking) return 'thinking';
    if (S.voiceState === 'listening') return 'listening';
    return 'idle';
  }

  function updateVoiceUI() {
    els.micBtn.classList.toggle('active', S.voiceOpen);
    if (!S.voiceOpen) return;
    els.voiceStateLabel.textContent = voiceLabel();
    els.orb.dataset.state = orbState();
    els.muteBtn.setAttribute('aria-pressed', S.voicePaused ? 'true' : 'false');
    els.muteLabel.textContent = S.voicePaused ? '다시 시작' : '일시정지';
    els.muteBtn.title = S.voicePaused ? '다시 듣기 시작' : '일시정지';
  }

  function updateHint() {
    const ms = Number(getPath(S.config, 'vad.end_silence_ms', 1000)) || 1000;
    const sec = Math.round(ms / 100) / 10;
    els.voiceHint.textContent = `말을 멈추면 ${sec}초 후에 대답해요 · 이어폰 사용을 권장해요`;
  }

  function openVoice() {
    if (!S.current) return;
    if (!S.wsOpen) { toast('서버에 연결되어 있지 않아요', true); return; }
    S.voiceOpen = true;
    S.voicePaused = false;
    S.voiceState = 'idle';
    S.thinking = false;
    els.vtUser.textContent = '';
    els.vtBot.textContent = '';
    els.app.classList.add('voice-open');
    els.voicePanel.setAttribute('aria-hidden', 'false');
    wsSend('voice', { action: 'start', persona: S.current });
    updateVoiceUI();
    startOrbLoop();
  }

  function togglePause() {
    if (!S.voiceOpen) return;
    S.voicePaused = !S.voicePaused;
    if (S.voicePaused) {
      wsSend('voice', { action: 'stop', persona: S.current });
      S.levelTarget = 0;
    } else {
      S.voiceState = 'idle';
      wsSend('voice', { action: 'start', persona: S.current });
    }
    updateVoiceUI();
  }

  function endCall() {
    if (!S.voiceOpen) return;
    wsSend('voice', { action: 'stop', persona: S.current });
    S.voiceOpen = false;
    S.voicePaused = false;
    S.voiceState = 'idle';
    S.thinking = false;
    els.app.classList.remove('voice-open');
    els.voicePanel.setAttribute('aria-hidden', 'true');
    cancelAnimationFrame(S.rafId);
    S.rafId = 0;
    updateVoiceUI();
    els.input.focus();
  }

  function startOrbLoop() {
    if (S.rafId) return;
    const tick = () => {
      if (!S.voiceOpen) { S.rafId = 0; return; }
      const live = !S.voicePaused && (S.voiceState === 'listening' || S.voiceState === 'user_speaking');
      const target = live ? S.levelTarget : 0;
      S.level += (target - S.level) * 0.28;
      S.levelTarget *= 0.92; // decay when level events stop
      const lv = Math.sqrt(Math.max(0, S.level));
      const scale = 1 + Math.min(0.42, lv * 0.75);
      els.orb.style.setProperty('--orb-scale', scale.toFixed(3));
      els.orbHalo.style.setProperty('--halo-scale', (1.08 + Math.min(0.7, lv * 1.3)).toFixed(3));
      els.orbHalo.style.setProperty('--halo-opacity', (0.35 + Math.min(0.5, lv)).toFixed(3));
      S.rafId = requestAnimationFrame(tick);
    };
    S.rafId = requestAnimationFrame(tick);
  }

  /* -------------------------------------------------------------- settings */

  function voiceRef(cfg, pid, key) {
    const fromCfg = getPath(cfg, `personas_voice.${pid}.${key}`, null);
    if (fromCfg != null) return fromCfg;
    return getPath(S.byId[pid] || {}, `voice.${key}`, '') || '';
  }

  function buildPersonaVoiceInputs(cfg) {
    const mk = (container, key, placeholder) => {
      container.innerHTML = '';
      for (const p of S.personas) {
        const row = document.createElement('div');
        row.className = 'pv-row';
        row.innerHTML = '<span class="pv-name">' + escapeHtml(p.avatar_emoji || '') + ' ' + escapeHtml(p.name_ko || p.name) + '</span>';
        const inp = document.createElement('input');
        inp.type = 'text';
        inp.spellcheck = false;
        inp.placeholder = placeholder;
        inp.dataset.persona = p.id;
        inp.dataset.key = key;
        inp.value = voiceRef(cfg, p.id, key);
        row.appendChild(inp);
        container.appendChild(row);
      }
    };
    mk(els.cloudRefList, 'cloud_reference_id', '예: 7f92f8afb8ec43bf81429cc1c9199cb1');
    mk(els.localRefList, 'local_reference_id', 'reference_id');
  }

  function fillDeviceSelect(sel, list, current) {
    sel.innerHTML = '<option value="">시스템 기본값</option>';
    let found = current == null;
    for (const d of list || []) {
      const o = document.createElement('option');
      o.value = String(d.index);
      o.textContent = d.name || `장치 #${d.index}`;
      if (current != null && String(current) === String(d.index)) { o.selected = true; found = true; }
      sel.appendChild(o);
    }
    if (!found) {
      const o = document.createElement('option');
      o.value = String(current);
      o.textContent = `저장된 장치 #${current} (현재 없음)`;
      o.selected = true;
      sel.appendChild(o);
    }
  }

  function setSelect(sel, value) {
    if (value == null) return;
    const v = String(value);
    if (![...sel.options].some((o) => o.value === v)) {
      const o = document.createElement('option');
      o.value = v;
      o.textContent = v;
      sel.appendChild(o);
    }
    sel.value = v;
  }

  function showTtsSub() {
    const mode = (els.settingsForm.querySelector('input[name="ttsMode"]:checked') || {}).value || 'cloud';
    for (const sub of els.settingsForm.querySelectorAll('.tts-sub')) sub.hidden = sub.dataset.mode !== mode;
  }

  async function openSettings() {
    els.settingsMsg.textContent = '불러오는 중…';
    els.settingsMsg.className = 'save-msg';
    els.modal.hidden = false;
    let cfg = S.config;
    try { cfg = await api('/api/config'); S.config = cfg; } catch (e) {
      els.settingsMsg.textContent = '설정을 불러오지 못했어요: ' + e.message;
      els.settingsMsg.className = 'save-msg err';
    }
    cfg = cfg || {};
    let devices = { input: [], output: [] };
    let models = null;
    const [devRes, modelRes] = await Promise.allSettled([api('/api/audio/devices'), api('/api/models')]);
    if (devRes.status === 'fulfilled' && devRes.value) devices = devRes.value;
    if (modelRes.status === 'fulfilled' && Array.isArray(modelRes.value)) models = modelRes.value;
    fillModelSelect(models, getPath(cfg, 'llm.model_path', ''));
    const moe = getPath(cfg, 'llm.n_cpu_moe', null);
    S.settingsMoe = (moe === null || moe === '' || Number.isNaN(Number(moe))) ? null : Number(moe);
    els.nCpuMoe.value = S.settingsMoe == null ? '' : String(S.settingsMoe);
    els.nCpuMoe.classList.remove('invalid');
    updateRestartNote();

    const mode = getPath(cfg, 'tts.mode', 'cloud');
    for (const r of els.settingsForm.querySelectorAll('input[name="ttsMode"]')) r.checked = r.value === mode;
    showTtsSub();

    S.settingsApiKeyInitial = getPath(cfg, 'tts.cloud.api_key', '') || '';
    els.apiKey.value = S.settingsApiKeyInitial;
    els.apiKeyHelp.textContent = S.settingsApiKeyInitial
      ? '저장된 키가 있어요. 바꾸려면 새 키를 입력하세요.'
      : 'fish.audio → API Keys 에서 발급할 수 있어요.';
    setSelect(els.cloudModel, getPath(cfg, 'tts.cloud.model', 'drama-3'));
    setSelect(els.latency, getPath(cfg, 'tts.cloud.latency', 'low'));
    els.localUrl.value = getPath(cfg, 'tts.local.url', 'http://127.0.0.1:8080');
    buildPersonaVoiceInputs(cfg);
    els.speakRepliesChk.checked = !!getPath(cfg, 'ui.speak_replies', S.speakReplies);
    setSelect(els.sttModel, getPath(cfg, 'stt.model', 'large-v3-turbo'));
    setSelect(els.sttDevice, getPath(cfg, 'stt.device', 'auto'));
    const silence = Number(getPath(cfg, 'vad.end_silence_ms', 1000)) || 1000;
    els.silence.value = String(Math.max(500, Math.min(2000, silence)));
    els.silenceValue.textContent = els.silence.value + ' ms';
    fillDeviceSelect(els.mic, devices.input, getPath(cfg, 'audio.input_device', null));
    fillDeviceSelect(els.speaker, devices.output, getPath(cfg, 'audio.output_device', null));
    if (!els.settingsMsg.classList.contains('err')) els.settingsMsg.textContent = '';
  }

  function closeSettings() { els.modal.hidden = true; }

  function normPath(p) { return String(p || '').replace(/\\/g, '/').replace(/^\.\//, '').toLowerCase(); }
  function baseName(p) { return String(p || '').split(/[\\/]/).pop(); }

  // LLM model select from GET /api/models: [{file, name, size_gb, active}]
  function fillModelSelect(models, currentPath) {
    const sel = els.llmModel;
    sel.innerHTML = '';
    let selected = '';
    for (const m of models || []) {
      if (!m || !m.file) continue;
      const o = document.createElement('option');
      o.value = m.file;
      const size = Number(m.size_gb);
      o.textContent = (m.name || baseName(m.file)) + (Number.isFinite(size) ? ` · ${size.toFixed(1)} GB` : '')
        + (m.moe ? ' · MoE' : '') + (m.active ? ' (사용 중)' : '');
      sel.appendChild(o);
      if (!selected && (m.active || (currentPath && normPath(m.file) === normPath(currentPath)))) selected = m.file;
    }
    if (!selected && currentPath) {
      const o = document.createElement('option');
      o.value = currentPath;
      o.textContent = `${baseName(currentPath)} (현재 설정 · 목록에 없음)`;
      sel.insertBefore(o, sel.firstChild);
      selected = currentPath;
    }
    if (!sel.options.length) {
      const o = document.createElement('option');
      o.value = '';
      o.textContent = '사용할 수 있는 모델이 없어요';
      sel.appendChild(o);
    }
    sel.value = selected;
    S.settingsLlmModel = sel.value;
    if (models == null) els.llmModelHelp.textContent = '모델 목록을 불러오지 못했어요. 현재 설정을 그대로 유지해요.';
    else if (!models.length) els.llmModelHelp.textContent = 'models 폴더에서 GGUF 파일을 찾지 못했어요.';
    else els.llmModelHelp.textContent = 'models 폴더의 GGUF 파일 중에서 골라요.';
  }

  // '' -> null (off); otherwise a non-negative integer. undefined = invalid.
  function readMoe() {
    const raw = String(els.nCpuMoe.value || '').trim();
    if (els.nCpuMoe.validity && els.nCpuMoe.validity.badInput) return undefined;
    if (raw === '') return null;
    const n = Number(raw);
    if (!Number.isInteger(n) || n < 0) return undefined;
    return n;
  }

  function llmPatch() {
    const p = {};
    if (els.llmModel.value && els.llmModel.value !== S.settingsLlmModel) p.model_path = els.llmModel.value;
    const moe = readMoe();
    if (moe !== undefined && moe !== S.settingsMoe) p.n_cpu_moe = moe;
    return p;
  }

  function updateRestartNote() {
    els.llmRestartNote.hidden = !Object.keys(llmPatch()).length;
  }

  async function saveSettings() {
    if (readMoe() === undefined) {
      els.nCpuMoe.classList.add('invalid');
      els.settingsMsg.textContent = 'MoE CPU 오프로드는 0 이상의 정수로 입력하거나 비워 두세요.';
      els.settingsMsg.className = 'save-msg err';
      els.nCpuMoe.focus();
      return;
    }
    els.nCpuMoe.classList.remove('invalid');
    const llm = llmPatch();
    const mode = (els.settingsForm.querySelector('input[name="ttsMode"]:checked') || {}).value || 'cloud';
    const devVal = (sel) => (sel.value === '' ? null : (Number.isNaN(Number(sel.value)) ? sel.value : Number(sel.value)));
    const patch = {
      tts: {
        mode,
        cloud: { model: els.cloudModel.value, latency: els.latency.value },
        local: { url: els.localUrl.value.trim() },
      },
      stt: { model: els.sttModel.value, device: els.sttDevice.value },
      vad: { end_silence_ms: Number(els.silence.value) },
      audio: { input_device: devVal(els.mic), output_device: devVal(els.speaker) },
      ui: { speak_replies: els.speakRepliesChk.checked },
      personas_voice: {},
    };
    if (Object.keys(llm).length) patch.llm = llm;
    if (els.apiKey.value !== S.settingsApiKeyInitial) patch.tts.cloud.api_key = els.apiKey.value.trim();
    for (const inp of els.settingsForm.querySelectorAll('.persona-voice-list input')) {
      const pid = inp.dataset.persona;
      patch.personas_voice[pid] = patch.personas_voice[pid] || {};
      patch.personas_voice[pid][inp.dataset.key] = inp.value.trim();
    }

    els.settingsSaveBtn.disabled = true;
    els.settingsMsg.textContent = '저장 중…';
    els.settingsMsg.className = 'save-msg';
    try {
      const res = await api('/api/config', { method: 'PUT', json: patch });
      if (res && typeof res === 'object') S.config = res;
      S.speakReplies = patch.ui.speak_replies;
      wsSend('speak_replies', { enabled: S.speakReplies });
      updateHint();
      closeSettings();
      if (patch.llm) {
        // llama-server restarts with the new model / offload; the status push will follow.
        setStatusRow(els.statusLLM, 'loading', '로딩 중', '새 설정으로 모델을 다시 불러오는 중이에요');
        toast('설정을 저장했어요 · 모델을 다시 시작하는 중이에요');
      } else {
        toast('설정을 저장했어요');
      }
    } catch (e) {
      els.settingsMsg.textContent = '저장하지 못했어요: ' + e.message;
      els.settingsMsg.className = 'save-msg err';
    } finally {
      els.settingsSaveBtn.disabled = false;
    }
  }

  /* ------------------------------------------------------- confirm dialog */

  let confirmResolve = null;

  // Promise<boolean>. Custom dialog (native dialogs block the WebView and look foreign).
  function confirmDialog(o) {
    return new Promise((resolve) => {
      if (confirmResolve) confirmResolve(false);
      confirmResolve = resolve;
      els.confirmTitle.textContent = o.title || '확인';
      els.confirmText.textContent = o.text || '';
      els.confirmOkBtn.textContent = o.ok || '확인';
      els.confirmCancelBtn.textContent = o.cancel || '취소';
      els.confirmOkBtn.classList.toggle('danger', !!o.danger);
      els.confirmModal.hidden = false;
      setTimeout(() => els.confirmOkBtn.focus(), 0);
    });
  }

  function closeConfirm(result) {
    if (els.confirmModal.hidden) return;
    els.confirmModal.hidden = true;
    const r = confirmResolve;
    confirmResolve = null;
    if (r) r(!!result);
  }

  /* ------------------------------------------------------ character editor */

  const EMOJI_PICKS = ['🌹', '😈', '🙄', '🌸', '🦊', '🐱', '🌙', '☕', '🎧', '🗡️', '👑', '🧛', '🧚', '🤖', '👻', '💼'];
  const COLOR_PICKS = ['#e86a92', '#d0533f', '#c96442', '#d6a13a', '#4f9d69', '#14b8a6', '#3b82f6', '#8b5cf6', '#6b7280'];
  const TAG_SUGGEST = ['[수줍게 웃으며]', '[한숨 쉬며]', '[속삭이듯]', '[장난스럽게]', '[놀라며]', '[삐진 듯]',
    '[울먹이며]', '[피식 웃으며]', '[나른하게]', '[단호하게]', '[신나서]', '[작게 웃음]'];
  const X_SVG = '<svg viewBox="0 0 24 24" width="14" height="14" aria-hidden="true"><path d="M6 6l12 12M18 6 6 18" stroke="currentColor" stroke-width="2" stroke-linecap="round"/></svg>';

  function charUrl(id, suffix) { return '/api/characters/' + encodeURIComponent(id) + (suffix || ''); }
  function errText(e) { return (e && (e.detail || e.message)) || '알 수 없는 오류'; }
  function clone(o) { return o == null ? o : JSON.parse(JSON.stringify(o)); }
  function val(el) { return String(el.value || '').trim(); }
  // IME guard for Korean input: the Enter/comma that commits a composition must not act.
  function imeBusy(e, composing) { return !!(e.isComposing || composing || e.keyCode === 229); }

  function newCharacterTemplate() {
    return {
      name: '', name_ko: '', tagline: '', color: COLOR_PICKS[Math.floor(Math.random() * COLOR_PICKS.length)],
      avatar_emoji: '🙂', greeting: '',
      profile: {
        char_name: '', user_name: '', personality: '', speaking_style: '', relationship: '',
        background: '', scenario: '', rules: [], example_dialogues: [], emotion_tags: [],
      },
      system_prompt_override: '',
      sampling: { temperature: 0.9 },
      voice: { cloud_reference_id: '', local_reference_id: '', speed: 1.0 },
    };
  }

  function setCharMsg(text, kind) {
    els.charMsg.textContent = text || '';
    els.charMsg.className = 'save-msg' + (kind ? ' ' + kind : '');
  }

  function setCharBusy(on) {
    CE.busy = !!on;
    for (const b of [els.charSaveBtn, els.charPreviewBtn, els.charDupBtn, els.charDeleteBtn]) b.disabled = !!on;
  }

  /* --- rules list */
  function updateListEmpty() {
    els.ceRulesEmpty.hidden = els.ceRules.children.length > 0;
    els.ceExamplesEmpty.hidden = els.ceExamples.children.length > 0;
    [...els.ceRules.children].forEach((row, i) => { row.querySelector('.row-num').textContent = (i + 1) + '.'; });
  }

  function addRuleRow(text, focus, after) {
    const row = document.createElement('div');
    row.className = 'list-row rule-row';
    const num = document.createElement('span');
    num.className = 'row-num';
    const inp = document.createElement('input');
    inp.type = 'text';
    inp.maxLength = 300;
    inp.placeholder = '예: 절대 존댓말을 쓰지 않는다';
    inp.value = text || '';
    let composing = false;
    inp.addEventListener('compositionstart', () => { composing = true; });
    inp.addEventListener('compositionend', () => { composing = false; });
    inp.addEventListener('keydown', (e) => {
      if (e.key !== 'Enter') return;
      if (imeBusy(e, composing)) return;   // IME guard
      e.preventDefault();
      if (inp.value.trim()) addRuleRow('', true, row);
    });
    const del = document.createElement('button');
    del.type = 'button';
    del.className = 'row-del';
    del.title = '규칙 삭제';
    del.setAttribute('aria-label', '규칙 삭제');
    del.innerHTML = X_SVG;
    del.addEventListener('click', () => {
      const next = row.nextElementSibling || row.previousElementSibling;
      row.remove();
      updateListEmpty();
      if (next) next.querySelector('input').focus();
    });
    row.append(num, inp, del);
    if (after && after.parentNode === els.ceRules) after.after(row);
    else els.ceRules.appendChild(row);
    updateListEmpty();
    if (focus) inp.focus();
    return row;
  }

  /* --- example dialogues */
  function charLabel() { return val(els.ceCharName) || val(els.ceNameKo) || '캐릭터'; }

  function updateExampleLabels() {
    const name = charLabel();
    for (const el of els.ceExamples.querySelectorAll('.ex-who.char')) el.textContent = name;
  }

  function addExampleRow(user, chr, focus) {
    const row = document.createElement('div');
    row.className = 'example-row';
    const fields = document.createElement('div');
    fields.className = 'ex-fields';
    const mk = (who, cls, value, ph) => {
      const line = document.createElement('div');
      line.className = 'ex-line';
      const lab = document.createElement('span');
      lab.className = 'ex-who ' + cls;
      lab.textContent = who;
      const ta = document.createElement('textarea');
      ta.rows = 1;
      ta.className = 'ex-' + cls;
      ta.placeholder = ph;
      ta.value = value || '';
      line.append(lab, ta);
      fields.appendChild(line);
      return ta;
    };
    const u = mk('사용자', 'user', user, '예: 오늘 뭐 했어?');
    mk(charLabel(), 'char', chr, '예: [나른하게] 그냥… 너 기다렸지. 왜, 이상해?');
    const del = document.createElement('button');
    del.type = 'button';
    del.className = 'row-del';
    del.title = '예시 삭제';
    del.setAttribute('aria-label', '예시 대화 삭제');
    del.innerHTML = X_SVG;
    del.addEventListener('click', () => { row.remove(); updateListEmpty(); });
    row.append(fields, del);
    els.ceExamples.appendChild(row);
    updateListEmpty();
    if (focus) u.focus();
    return row;
  }

  /* --- emotion tag chips */
  function normalizeTag(t) {
    let s = String(t || '').trim().replace(/^\[+/, '').replace(/\]+$/, '').trim();
    if (!s) return '';
    s = s.replace(/[\[\]]/g, '').slice(0, 40);
    return '[' + s + ']';
  }

  function addTag(t) {
    const tag = normalizeTag(t);
    if (!tag || CE.tags.includes(tag)) return false;
    if (CE.tags.length >= 30) { toast('감정 태그는 30개까지 넣을 수 있어요', true); return false; }
    CE.tags.push(tag);
    return true;
  }

  function commitTagInput() {
    const raw = els.ceTagInput.value;
    if (!raw.trim()) { if (raw) els.ceTagInput.value = ''; return; }
    let added = false;
    for (const part of raw.split(/[,，]/)) added = addTag(part) || added;
    els.ceTagInput.value = '';
    if (added) renderTags();
  }

  function renderTags() {
    els.ceTagChips.innerHTML = '';
    CE.tags.forEach((t, i) => {
      const chip = document.createElement('span');
      chip.className = 'chip';
      const txt = document.createElement('span');
      txt.textContent = t;
      const x = document.createElement('button');
      x.type = 'button';
      x.setAttribute('aria-label', `${t} 삭제`);
      x.innerHTML = X_SVG;
      x.addEventListener('click', (e) => {
        e.stopPropagation();
        CE.tags.splice(i, 1);
        renderTags();
        els.ceTagInput.focus();
      });
      chip.append(txt, x);
      els.ceTagChips.appendChild(chip);
    });
    els.ceTagSuggest.innerHTML = '';
    for (const t of TAG_SUGGEST) {
      if (CE.tags.includes(t)) continue;
      const b = document.createElement('button');
      b.type = 'button';
      b.textContent = t;
      b.title = '추가';
      b.addEventListener('click', () => { if (addTag(t)) renderTags(); });
      els.ceTagSuggest.appendChild(b);
    }
  }

  /* --- basic visuals */
  function updateAvatarPreview() {
    els.ceAvatarPreview.textContent = val(els.ceEmoji) || '🙂';
    els.ceAvatarPreview.style.setProperty('--pc', els.ceColor.value || 'var(--accent)');
    const c = String(els.ceColor.value || '').toLowerCase();
    for (const b of els.ceColorPicks.children) b.classList.toggle('on', b.dataset.color === c);
  }

  function buildPickers() {
    els.ceEmojiPicks.innerHTML = '';
    for (const em of EMOJI_PICKS) {
      const b = document.createElement('button');
      b.type = 'button';
      b.textContent = em;
      b.title = em;
      b.addEventListener('click', () => { els.ceEmoji.value = em; updateAvatarPreview(); });
      els.ceEmojiPicks.appendChild(b);
    }
    els.ceColorPicks.innerHTML = '';
    for (const c of COLOR_PICKS) {
      const b = document.createElement('button');
      b.type = 'button';
      b.dataset.color = c.toLowerCase();
      b.style.background = c;
      b.title = c;
      b.setAttribute('aria-label', '색 ' + c);
      b.addEventListener('click', () => { els.ceColor.value = c; updateAvatarPreview(); });
      els.ceColorPicks.appendChild(b);
    }
  }

  function updateGreetingPreview() {
    const g = els.ceGreeting.value.trim();
    els.ceGreetingPreview.innerHTML = g ? renderMarkdown(g) : '';
  }

  function updateRangeLabels() {
    els.ceSpeedValue.textContent = Number(els.ceSpeed.value).toFixed(2) + '×';
    els.ceTempValue.textContent = Number(els.ceTemp.value).toFixed(2);
  }

  function updateOverrideUI() {
    els.ceOverrideWrap.hidden = !els.ceOverrideToggle.checked;
  }

  function clearNameError() {
    els.ceNameErr.hidden = true;
    els.ceNameKo.classList.remove('invalid');
    const nb = els.ceNav.querySelector('[data-target="ceSecBasic"]');
    if (nb) nb.classList.remove('has-err');
    if (els.charMsg.classList.contains('err')) setCharMsg('');
  }

  function toHexColor(c) {
    const s = String(c || '').trim();
    if (/^#[0-9a-f]{6}$/i.test(s)) return s.toLowerCase();
    if (/^#[0-9a-f]{3}$/i.test(s)) return ('#' + s[1] + s[1] + s[2] + s[2] + s[3] + s[3]).toLowerCase();
    return '#c96442';
  }

  /* --- load / collect */
  function fillCharForm(c) {
    const p = c.profile || {};
    els.ceNameKo.value = c.name_ko || '';
    els.ceName.value = (c.name && c.name !== c.name_ko) ? c.name : '';
    els.ceTagline.value = c.tagline || '';
    els.ceEmoji.value = c.avatar_emoji || '';
    els.ceColor.value = toHexColor(c.color);
    els.ceCharName.value = p.char_name || '';
    els.ceUserName.value = p.user_name || '';
    els.cePersonality.value = p.personality || '';
    els.ceSpeaking.value = p.speaking_style || '';
    els.ceRelationship.value = p.relationship || '';
    els.ceBackground.value = p.background || '';
    els.ceScenario.value = p.scenario || '';
    els.ceGreeting.value = c.greeting || '';
    els.ceRules.innerHTML = '';
    for (const r of (Array.isArray(p.rules) ? p.rules : [])) addRuleRow(String(r));
    els.ceExamples.innerHTML = '';
    for (const ex of (Array.isArray(p.example_dialogues) ? p.example_dialogues : [])) {
      if (ex && typeof ex === 'object') addExampleRow(ex.user || '', ex.char || '');
    }
    updateListEmpty();
    CE.tags = [];
    for (const t of (Array.isArray(p.emotion_tags) ? p.emotion_tags : [])) addTag(t);
    els.ceTagInput.value = '';
    renderTags();
    const id = c.id;
    const voice = c.voice || {};
    // Effective value = non-empty settings override (config personas_voice) else the character's own.
    const eff = (key) => (id && getPath(S.config, `personas_voice.${id}.${key}`, '')) || voice[key] || '';
    els.ceCloudRef.value = eff('cloud_reference_id');
    els.ceLocalRef.value = eff('local_reference_id');
    const speed = Number(voice.speed);
    els.ceSpeed.value = String(Number.isFinite(speed) && speed > 0 ? speed : 1);
    const temp = Number(getPath(c, 'sampling.temperature', 0.9));
    els.ceTemp.value = String(Number.isFinite(temp) ? temp : 0.9);
    const ov = c.system_prompt_override || '';
    els.ceOverrideToggle.checked = !!ov.trim();
    els.ceOverride.value = ov;
    updateOverrideUI();
    updateRangeLabels();
    updateAvatarPreview();
    updateGreetingPreview();
    updateExampleLabels();
    clearNameError();
  }

  // The character draft as it would be sent to the server.
  function collectDraft() {
    const o = clone(CE.orig) || {};
    delete o.builtin;
    delete o.overridden;
    delete o.system_prompt;
    if (CE.isNew) delete o.id;
    const nameKo = val(els.ceNameKo);
    o.name_ko = nameKo;
    o.name = val(els.ceName) || nameKo;
    o.tagline = val(els.ceTagline);
    o.avatar_emoji = val(els.ceEmoji) || '🙂';
    o.color = els.ceColor.value || '#c96442';
    o.greeting = els.ceGreeting.value.trim();
    const rules = [...els.ceRules.querySelectorAll('input')].map((i) => i.value.trim()).filter(Boolean);
    const examples = [...els.ceExamples.querySelectorAll('.example-row')].map((row) => ({
      user: row.querySelector('textarea.ex-user').value.trim(),
      char: row.querySelector('textarea.ex-char').value.trim(),
    })).filter((ex) => ex.user || ex.char);
    const pending = normalizeTag(els.ceTagInput.value);   // typed but not yet committed
    const tags = CE.tags.slice();
    if (pending && !tags.includes(pending)) tags.push(pending);
    o.profile = Object.assign({}, o.profile || {}, {
      char_name: val(els.ceCharName),
      user_name: val(els.ceUserName),
      personality: els.cePersonality.value.trim(),
      speaking_style: els.ceSpeaking.value.trim(),
      relationship: els.ceRelationship.value.trim(),
      background: els.ceBackground.value.trim(),
      scenario: els.ceScenario.value.trim(),
      rules,
      example_dialogues: examples,
      emotion_tags: tags,
    });
    o.system_prompt_override = els.ceOverrideToggle.checked ? els.ceOverride.value.trim() : '';
    o.sampling = Object.assign({}, o.sampling || {}, { temperature: Number(els.ceTemp.value) });
    o.voice = Object.assign({}, o.voice || {}, {
      cloud_reference_id: val(els.ceCloudRef),
      local_reference_id: val(els.ceLocalRef),
      speed: Number(els.ceSpeed.value),
    });
    return o;
  }

  function isCharDirty() {
    return !els.charModal.hidden && JSON.stringify(collectDraft()) !== CE.baseline;
  }

  function validateDraft(d) {
    if (!d.name_ko) {
      els.ceNameErr.hidden = false;
      els.ceNameKo.classList.add('invalid');
      const nb = els.ceNav.querySelector('[data-target="ceSecBasic"]');
      if (nb) nb.classList.add('has-err');
      els.ceNameKo.focus();
      els.ceNameKo.scrollIntoView({ block: 'center' });
      setCharMsg('이름을 입력해 주세요.', 'err');
      return false;
    }
    if (els.ceOverrideToggle.checked && !d.system_prompt_override) {
      els.ceOverride.focus();
      setCharMsg('직접 쓰는 시스템 프롬프트가 비어 있어요. 내용을 쓰거나 토글을 꺼 주세요.', 'err');
      return false;
    }
    return true;
  }

  function applyEditorChrome() {
    els.charTitle.textContent = CE.isNew ? '캐릭터 만들기' : '캐릭터 편집';
    if (CE.isNew) {
      els.charBadge.hidden = true;
    } else {
      els.charBadge.hidden = false;
      els.charBadge.textContent = CE.builtin ? (CE.overridden ? '기본 캐릭터 · 수정됨' : '기본 캐릭터') : '내 캐릭터';
      els.charBadge.classList.toggle('mod', CE.builtin && CE.overridden);
    }
    els.charDupBtn.hidden = CE.isNew;
    if (CE.isNew || (CE.builtin && !CE.overridden)) {
      els.charDeleteBtn.hidden = true;
    } else {
      els.charDeleteBtn.hidden = false;
      els.charDeleteBtn.textContent = CE.builtin ? '기본값으로 되돌리기' : '삭제';
      els.charDeleteBtn.title = CE.builtin ? '수정한 내용을 지우고 기본 설정으로 돌아가요' : '이 캐릭터와 대화 기록을 삭제해요';
      els.charDeleteBtn.setAttribute('aria-label', els.charDeleteBtn.textContent);
    }
  }

  function loadIntoEditor(c, isNew) {
    CE.isNew = !!isNew;
    CE.orig = clone(c);
    CE.id = isNew ? null : c.id;
    CE.builtin = !isNew && !!c.builtin;
    CE.overridden = !isNew && !!c.overridden;
    fillCharForm(c);
    applyEditorChrome();
    setCharMsg('');
    setCharBusy(false);
    CE.baseline = JSON.stringify(collectDraft());
    els.charForm.scrollTop = 0;
    setActiveNav('ceSecBasic');
  }

  async function openCharEditor(id) {
    if (!els.charModal.hidden && !(await closeCharEditor())) return;
    // Fresh config for voice reference ids (settings may override character voice).
    const cfgP = api('/api/config').then((c) => { if (c) S.config = c; }).catch(() => {});
    if (!id) {
      await cfgP;
      loadIntoEditor(newCharacterTemplate(), true);
      els.charModal.hidden = false;
      setTimeout(() => els.ceNameKo.focus(), 0);
      return;
    }
    let c;
    try {
      [c] = await Promise.all([api(charUrl(id)), cfgP]);
    } catch (e) {
      toast('캐릭터 정보를 불러오지 못했어요: ' + errText(e), true);
      return;
    }
    if (!c || typeof c !== 'object') { toast('캐릭터 정보를 불러오지 못했어요', true); return; }
    loadIntoEditor(c, false);
    els.charModal.hidden = false;
    setTimeout(() => els.ceNameKo.focus(), 0);
  }

  async function closeCharEditor(force) {
    if (els.charModal.hidden) return true;
    if (!force && isCharDirty()) {
      const ok = await confirmDialog({
        title: '저장하지 않은 변경 사항',
        text: '편집한 내용이 아직 저장되지 않았어요.\n저장하지 않고 닫을까요?',
        ok: '저장 안 하고 닫기', cancel: '계속 편집', danger: true,
      });
      if (!ok) return false;
    }
    els.charModal.hidden = true;
    els.promptModal.hidden = true;
    return true;
  }

  // Settings' per-persona voice ids (config personas_voice) override the character's voice;
  // keep them in sync so what the editor shows is what is used.
  async function syncVoiceConfig(id, voice) {
    // The server normally syncs this itself on save; re-read and only patch if it didn't.
    try { const c = await api('/api/config'); if (c) S.config = c; } catch (e) { return; }
    const ov = getPath(S.config, 'personas_voice.' + id, null);
    if (!ov || typeof ov !== 'object') return;
    const want = { cloud_reference_id: voice.cloud_reference_id || '', local_reference_id: voice.local_reference_id || '' };
    if ((ov.cloud_reference_id || '') === want.cloud_reference_id && (ov.local_reference_id || '') === want.local_reference_id) return;
    try {
      const res = await api('/api/config', { method: 'PUT', json: { personas_voice: { [id]: want } } });
      if (res && typeof res === 'object') S.config = res;
    } catch (e) {
      toast('목소리 설정을 동기화하지 못했어요: ' + errText(e), true);
    }
  }

  // After a scene/greeting change: offer to restart that character's conversation.
  async function offerSceneRestart(id) {
    let hist = [];
    try { hist = await api('/api/history?persona=' + encodeURIComponent(id)) || []; } catch (e) { hist = []; }
    // Nothing said yet (empty, or only the stored greeting): just refresh to the new greeting.
    const talked = Array.isArray(hist) && hist.some((h) => h && h.role === 'user');
    if (!talked) {
      if (hist.length) {
        try { await api('/api/history?persona=' + encodeURIComponent(id), { method: 'DELETE' }); } catch (e) { /* keep */ }
      }
      if (id === S.current && !isGenerating()) loadHistory();
      return;
    }
    const yes = await confirmDialog({
      title: '새 장면으로 대화를 새로 시작할까요?',
      text: '장면이나 첫 인사가 바뀌었어요.\n지금까지의 대화 기록을 지우고 새 첫 인사부터 다시 시작해요.',
      ok: '새로 시작', cancel: '이어서 대화',
    });
    if (!yes) return;
    if (id === S.current && isGenerating()) interrupt();
    try {
      await api('/api/history?persona=' + encodeURIComponent(id), { method: 'DELETE' });
    } catch (e) {
      toast('대화를 초기화하지 못했어요: ' + errText(e), true);
      return;
    }
    if (id !== S.current) { selectPersona(id); return; }
    finishGenerating();
    els.vtUser.textContent = '';
    els.vtBot.textContent = '';
    await loadHistory();
  }

  async function saveCharacter() {
    if (CE.busy) return;
    commitTagInput();
    const draft = collectDraft();
    if (!validateDraft(draft)) return;
    clearNameError();
    const isNew = CE.isNew;
    const orig = CE.orig || {};
    const sceneChanged = !isNew && (
      (draft.profile.scenario || '') !== (getPath(orig, 'profile.scenario', '') || '').trim() ||
      (draft.greeting || '') !== String(orig.greeting || '').trim());
    setCharBusy(true);
    setCharMsg('저장 중…');
    let saved;
    try {
      saved = await api(isNew ? '/api/characters' : charUrl(CE.id), { method: isNew ? 'POST' : 'PUT', json: draft });
    } catch (e) {
      setCharBusy(false);
      setCharMsg('저장하지 못했어요', 'err');
      toast(errText(e), true);
      return;
    }
    const sid = (saved && saved.id) || CE.id;
    await syncVoiceConfig(sid, draft.voice);
    await closeCharEditor(true);
    setCharBusy(false);
    toast(isNew ? `'${draft.name_ko}' 캐릭터를 만들었어요` : `'${draft.name_ko}' 저장했어요`);
    await refreshPersonas(isNew ? sid : null);
    if (isNew && sid && S.byId[sid] && sid !== S.current) selectPersona(sid);
    if (sceneChanged && sid) await offerSceneRestart(sid);
  }

  async function previewPrompt(fillOverride) {
    if (CE.busy) return;
    const draft = collectDraft();
    if (fillOverride) draft.system_prompt_override = '';
    if (!draft.name_ko) draft.name_ko = draft.name = '이름 없음';
    setCharBusy(true);
    setCharMsg('프롬프트 만드는 중…');
    let res;
    try {
      res = await api('/api/characters/preview', { method: 'POST', json: draft });
    } catch (e) {
      setCharMsg('미리보기를 만들지 못했어요', 'err');
      toast(errText(e), true);
      return;
    } finally {
      setCharBusy(false);
    }
    setCharMsg('');
    const sys = (res && res.system_prompt) || '';
    if (fillOverride) {
      els.ceOverride.value = sys;
      els.ceOverride.focus();
      return;
    }
    els.promptSystem.textContent = sys || '(비어 있음)';
    els.promptReminder.textContent = (res && res.reminder) || '(없음)';
    els.promptMsg.textContent = sys ? `${sys.length.toLocaleString()}자` : '';
    els.promptModal.hidden = false;
    els.promptSystem.scrollTop = 0;
    setTimeout(() => els.promptOkBtn.focus(), 0);
  }

  async function duplicateCurrentCharacter() {
    if (CE.busy || CE.isNew || !CE.id) return;
    if (isCharDirty()) {
      const ok = await confirmDialog({
        title: '복제',
        text: '저장하지 않은 변경 사항은 복제본에 포함되지 않아요.\n저장된 버전을 복제할까요?',
        ok: '복제',
      });
      if (!ok) return;
    }
    setCharBusy(true);
    setCharMsg('복제 중…');
    let copy;
    try {
      copy = await api(charUrl(CE.id, '/duplicate'), { method: 'POST' });
    } catch (e) {
      setCharBusy(false);
      setCharMsg('복제하지 못했어요', 'err');
      toast(errText(e), true);
      return;
    }
    setCharBusy(false);
    await refreshPersonas();
    if (copy && copy.id) {
      loadIntoEditor(copy, false);
      toast(`'${copy.name_ko || copy.name || copy.id}'을(를) 만들었어요 · 지금 편집 중이에요`);
    } else {
      await closeCharEditor(true);
    }
  }

  async function deleteCurrentCharacter() {
    if (CE.busy || CE.isNew || !CE.id) return;
    const name = val(els.ceNameKo) || CE.id;
    const reset = CE.builtin;
    if (reset && !CE.overridden) return;
    const ok = await confirmDialog(reset ? {
      title: '기본값으로 되돌릴까요?',
      text: `'${name}'에 적용한 수정 사항이 모두 사라지고 처음 상태로 돌아가요.`,
      ok: '되돌리기', danger: true,
    } : {
      title: '캐릭터를 삭제할까요?',
      text: `'${name}' 캐릭터와 대화 기록이 함께 삭제돼요.\n이 작업은 되돌릴 수 없어요.`,
      ok: '삭제', danger: true,
    });
    if (!ok) return;
    const id = CE.id;
    setCharBusy(true);
    try {
      await api(charUrl(id), { method: 'DELETE' });
    } catch (e) {
      setCharBusy(false);
      toast(errText(e), true);
      return;
    }
    setCharBusy(false);
    await closeCharEditor(true);
    toast(reset ? `'${name}'을(를) 기본값으로 되돌렸어요` : `'${name}' 캐릭터를 삭제했어요`);
    await refreshPersonas();
  }

  /* --- section nav (scroll spy) */
  function setActiveNav(target) {
    for (const b of els.ceNav.querySelectorAll('button')) b.classList.toggle('active', b.dataset.target === target);
  }

  function onCharScroll() {
    const body = els.charForm;
    const top = body.getBoundingClientRect().top;
    let current = 'ceSecBasic';
    for (const sec of body.querySelectorAll('.set-section')) {
      if (sec.getBoundingClientRect().top - top <= 48) current = sec.id;
    }
    if (body.scrollTop + body.clientHeight >= body.scrollHeight - 4) {
      const all = body.querySelectorAll('.set-section');
      if (all.length) current = all[all.length - 1].id;
    }
    setActiveNav(current);
  }

  function bindCharEditor() {
    buildPickers();
    els.newCharBtn.addEventListener('click', () => { els.app.classList.remove('sidebar-open'); openCharEditor(null); });
    els.charCloseBtn.addEventListener('click', () => closeCharEditor());
    els.charCancelBtn.addEventListener('click', () => closeCharEditor());
    els.charSaveBtn.addEventListener('click', saveCharacter);
    els.charPreviewBtn.addEventListener('click', () => previewPrompt(false));
    els.charDupBtn.addEventListener('click', duplicateCurrentCharacter);
    els.charDeleteBtn.addEventListener('click', deleteCurrentCharacter);
    els.charForm.addEventListener('submit', (e) => e.preventDefault());
    els.charModal.addEventListener('mousedown', (e) => { if (e.target === els.charModal) closeCharEditor(); });
    els.charModal.addEventListener('keydown', (e) => {
      // Ctrl+S saves
      if ((e.ctrlKey || e.metaKey) && (e.key === 's' || e.key === 'S')) { e.preventDefault(); saveCharacter(); }
    });

    for (const b of els.ceNav.querySelectorAll('button')) {
      b.addEventListener('click', () => {
        const sec = $(b.dataset.target);
        if (sec) sec.scrollIntoView({ block: 'start', behavior: 'smooth' });
        setActiveNav(b.dataset.target);
      });
    }
    els.charForm.addEventListener('scroll', onCharScroll, { passive: true });

    els.ceNameKo.addEventListener('input', () => { clearNameError(); updateExampleLabels(); });
    els.ceCharName.addEventListener('input', updateExampleLabels);
    els.ceEmoji.addEventListener('input', updateAvatarPreview);
    els.ceColor.addEventListener('input', updateAvatarPreview);
    els.ceGreeting.addEventListener('input', updateGreetingPreview);
    els.ceSpeed.addEventListener('input', updateRangeLabels);
    els.ceTemp.addEventListener('input', updateRangeLabels);
    els.ceOverrideToggle.addEventListener('change', () => {
      updateOverrideUI();
      if (els.ceOverrideToggle.checked) setTimeout(() => els.ceOverride.focus(), 0);
    });
    els.ceOverrideFill.addEventListener('click', async () => {
      if (els.ceOverride.value.trim()) {
        const ok = await confirmDialog({ title: '프롬프트 덮어쓰기', text: '지금 쓴 내용을 생성된 프롬프트로 바꿀까요?', ok: '바꾸기' });
        if (!ok) return;
      }
      previewPrompt(true);
    });
    els.ceAddRule.addEventListener('click', () => addRuleRow('', true));
    els.ceAddExample.addEventListener('click', () => addExampleRow('', '', true));

    // Emotion tag chip input — Enter / comma adds, IME-safe.
    let tagComposing = false;
    els.ceTagInput.addEventListener('compositionstart', () => { tagComposing = true; });
    els.ceTagInput.addEventListener('compositionend', () => {
      tagComposing = false;
      if (/[,，]/.test(els.ceTagInput.value)) commitTagInput();
    });
    els.ceTagInput.addEventListener('keydown', (e) => {
      if (imeBusy(e, tagComposing)) return;   // IME guard
      if (e.key === 'Enter' || e.key === ',') {
        e.preventDefault();
        commitTagInput();
      } else if (e.key === 'Backspace' && !els.ceTagInput.value && CE.tags.length) {
        CE.tags.pop();
        renderTags();
      }
    });
    els.ceTagInput.addEventListener('input', () => {
      if (!tagComposing && /[,，]/.test(els.ceTagInput.value)) commitTagInput();
    });
    els.ceTagInput.addEventListener('blur', () => { if (!tagComposing) commitTagInput(); });
    els.ceTagBox.addEventListener('mousedown', (e) => {
      if (e.target === els.ceTagBox || e.target === els.ceTagChips) { e.preventDefault(); els.ceTagInput.focus(); }
    });

    // prompt preview + confirm dialogs
    els.promptCloseBtn.addEventListener('click', () => { els.promptModal.hidden = true; });
    els.promptOkBtn.addEventListener('click', () => { els.promptModal.hidden = true; });
    els.promptModal.addEventListener('mousedown', (e) => { if (e.target === els.promptModal) els.promptModal.hidden = true; });
    els.confirmOkBtn.addEventListener('click', () => closeConfirm(true));
    els.confirmCancelBtn.addEventListener('click', () => closeConfirm(false));
    els.confirmModal.addEventListener('mousedown', (e) => { if (e.target === els.confirmModal) closeConfirm(false); });
  }

  /* ---------------------------------------------------------------- events */

  function bindEvents() {
    let composing = false;
    els.input.addEventListener('compositionstart', () => { composing = true; });
    els.input.addEventListener('compositionend', () => { composing = false; });
    els.input.addEventListener('input', autosize);
    window.addEventListener('resize', autosize);
    els.input.addEventListener('keydown', (e) => {
      if (e.key !== 'Enter') return;
      // IME guard (critical for Korean): the Enter that commits a composition must not send.
      if (e.isComposing || composing || e.keyCode === 229) return;
      if (e.shiftKey) return; // newline
      e.preventDefault();
      sendChat();
    });
    els.composer.addEventListener('submit', (e) => {
      e.preventDefault();
      if (isGenerating() && !els.input.value.trim()) interrupt();
      else sendChat();
    });

    els.micBtn.addEventListener('click', () => { if (S.voiceOpen) endCall(); else openVoice(); });
    els.muteBtn.addEventListener('click', togglePause);
    els.endCallBtn.addEventListener('click', endCall);
    els.voiceCloseBtn.addEventListener('click', endCall);

    els.newChatBtn.addEventListener('click', newChat);
    els.settingsBtn.addEventListener('click', openSettings);
    els.sidebarToggle.addEventListener('click', () => els.app.classList.toggle('sidebar-open'));
    els.scroll.addEventListener('click', () => els.app.classList.remove('sidebar-open'));

    els.settingsCloseBtn.addEventListener('click', closeSettings);
    els.settingsCancelBtn.addEventListener('click', closeSettings);
    els.settingsSaveBtn.addEventListener('click', saveSettings);
    els.settingsForm.addEventListener('submit', (e) => { e.preventDefault(); saveSettings(); });
    els.modal.addEventListener('mousedown', (e) => { if (e.target === els.modal) closeSettings(); });
    for (const r of els.settingsForm.querySelectorAll('input[name="ttsMode"]')) r.addEventListener('change', showTtsSub);
    els.silence.addEventListener('input', () => { els.silenceValue.textContent = els.silence.value + ' ms'; });
    els.llmModel.addEventListener('change', updateRestartNote);
    els.nCpuMoe.addEventListener('input', () => { els.nCpuMoe.classList.remove('invalid'); updateRestartNote(); });

    bindCharEditor();

    document.addEventListener('keydown', (e) => {
      if (e.key === 'Escape') {
        // topmost layer first
        if (!els.confirmModal.hidden) { e.preventDefault(); closeConfirm(false); }
        else if (!els.promptModal.hidden) els.promptModal.hidden = true;
        else if (!els.charModal.hidden) { if (!e.isComposing) closeCharEditor(); }
        else if (!els.modal.hidden) closeSettings();
        else if (isGenerating()) interrupt();
      }
    });
  }

  /* ------------------------------------------------------------------ init */

  async function init() {
    bindEvents();
    autosize();

    try { S.config = await api('/api/config'); } catch (e) { S.config = null; }
    S.speakReplies = !!getPath(S.config, 'ui.speak_replies', true);
    updateHint();

    let personas = [];
    for (let attempt = 0; attempt < 20 && !personas.length; attempt++) {
      try { personas = await api('/api/personas') || []; } catch (e) {
        await new Promise((r) => setTimeout(r, 500));
      }
    }
    S.personas = personas;
    S.byId = Object.fromEntries(personas.map((p) => [p.id, p]));
    renderPersonas();

    if (!personas.length) {
      els.messages.innerHTML = '<div class="empty-state"><div class="es-emoji">🔌</div><h2>서버에 연결할 수 없어요</h2><p>앱을 다시 시작해 주세요.</p></div>';
    } else {
      const saved = load('vc.persona');
      const dflt = getPath(S.config, 'ui.default_persona', null);
      const pid = S.byId[saved] ? saved : (S.byId[dflt] ? dflt : personas[0].id);
      S.current = null;
      selectPersona(pid, true);
    }

    try { renderStatus(await api('/api/status')); } catch (e) { /* status arrives over WS */ }
    connect();
    els.input.focus();
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
  else init();
})();
