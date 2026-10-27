import { ObservationPolicy, summarizeMetrics } from './policy.js';

const $ = id => document.getElementById(id);
let config, stream, recognition, request, companionTimer;
let cameraGeneration = 0, companionGeneration = 0;
let history = [], rows = [], lastAnswer = '', captureMs = null;
const policy = new ObservationPolicy();
const status = (text, error = false) => { $('status').textContent = text; $('status').classList.toggle('error', error); };
const isMeta = () => $('mode').value === 'meta';

function renderMetrics() {
  const summary = summarizeMetrics(rows);
  $('metric-count').textContent = `${summary.completed} / ${summary.metaAttempts} intentos`;
  $('metric-latency').textContent = summary.roundTripMedianMs == null ? 'Sin datos' : `${(summary.roundTripMedianMs / 1000).toFixed(2)} s`;
  $('metric-cost').textContent = `US$ ${summary.knownEstimatedUsd.toFixed(4)}`;
  $('billing-note').textContent = `Respuesta completa, no primer audio. ${summary.unknownBillingAttempts} intento(s) con facturación desconocida. Exportar permite ver p95 y separar las respuestas preescritas.`;
}

function appendTurn(who, text, metadata = '') {
  const item = document.createElement('article'); item.className = `turn ${who}`;
  const label = document.createElement('strong'); label.textContent = who === 'user' ? 'Vos' : 'Luma';
  const paragraph = document.createElement('p'); paragraph.textContent = text;
  item.append(label, paragraph);
  if (metadata) { const note = document.createElement('small'); note.textContent = metadata; item.append(note); }
  $('transcript').append(item); item.scrollIntoView({ block: 'nearest', behavior: 'instant' });
}

function stopSpeech() { window.speechSynthesis?.cancel(); }
function speak(text) {
  if (!window.speechSynthesis) return status('Este navegador no tiene síntesis de voz. La respuesta está disponible en texto.', true);
  stopSpeech(); const utterance = new SpeechSynthesisUtterance(text); utterance.lang = 'es-AR'; utterance.rate = 1;
  window.speechSynthesis.speak(utterance);
}

function stopCompanion() {
  companionGeneration += 1;
  clearTimeout(companionTimer); companionTimer = null;
  $('companion').dataset.active = 'false';
  $('companion').textContent = 'Iniciar sesión de 2 minutos';
}

function stopCamera() {
  cameraGeneration += 1;
  stream?.getTracks().forEach(track => track.stop()); stream = null;
  $('preview').srcObject = null; $('preview').hidden = true; $('sample').hidden = false;
  $('camera').textContent = 'Activar cámara del teléfono / PC';
  $('frame-state').textContent = 'Cámara apagada. Ninguna imagen se está capturando.';
  $('camera-label').textContent = 'Horizonte compartido · obra ficticia para ensayar';
}

function pauseAll(message = 'Pausado. Cámara, dictado y voz detenidos. Cancelar no garantiza evitar cargos ya iniciados.') {
  stopCompanion(); stopSpeech(); recognition?.abort(); stopCamera(); request?.abort(); status(message);
}

async function toggleCamera() {
  if (stream) { pauseAll(); return; }
  if (!navigator.mediaDevices?.getUserMedia) return status('Cámara no disponible. Abrí localhost o una conexión HTTPS compatible.', true);
  const generation = ++cameraGeneration;
  $('camera').disabled = true;
  try {
    const next = await navigator.mediaDevices.getUserMedia({ video: { facingMode: 'environment', width: { ideal: 1280 } }, audio: false });
    if (generation !== cameraGeneration) { next.getTracks().forEach(track => track.stop()); return; }
    stream = next; $('preview').srcObject = next;
    await $('preview').play();
    if (generation !== cameraGeneration) return;
    $('preview').hidden = false; $('sample').hidden = true;
    $('camera').textContent = 'Apagar cámara';
    $('camera-label').textContent = 'Cámara del teléfono / PC · sin conexión con los lentes';
    $('frame-state').textContent = 'Vista previa local activa. Se envía una captura por consulta solo si elegís y autorizás Meta.';
    next.getVideoTracks()[0].addEventListener('ended', () => { if (stream === next) pauseAll('La cámara se desconectó. Sesión detenida.'); });
    status('Cámara lista. Podés preguntar cuando quieras.');
  } catch { if (generation === cameraGeneration) { stopCamera(); status('No pudimos abrir la cámara. Revisá el permiso del navegador y volvé a intentar.', true); } }
  finally { $('camera').disabled = false; }
}

function capture() {
  const video = $('preview');
  if (!stream || !video.videoWidth || stream.getVideoTracks()[0]?.readyState !== 'live') throw new Error('Activá una cámara antes de consultar a Meta.');
  const started = performance.now();
  const canvas = document.createElement('canvas'); const scale = Math.min(1, 1280 / Math.max(video.videoWidth, video.videoHeight));
  canvas.width = Math.round(video.videoWidth * scale); canvas.height = Math.round(video.videoHeight * scale);
  canvas.getContext('2d').drawImage(video, 0, 0, canvas.width, canvas.height);
  const image = canvas.toDataURL('image/jpeg', 0.75);
  captureMs = Math.round(performance.now() - started);
  return { image, capturedAt: Date.now() };
}

async function observe(question) {
  if (request) return false;
  const mode = isMeta() ? 'meta' : 'fixture';
  if (!question.trim()) { status('Escribí o dictá una pregunta.', true); return false; }
  if (mode === 'meta' && (!config.metaEnabled || !$('paid').checked)) { status('Para usar Meta, configurá el servidor y autorizá el envío y la API paga.', true); return false; }
  let image = {};
  try { if (mode === 'meta') image = capture(); } catch (error) { status(error.message, true); return false; }
  stopSpeech(); recognition?.abort();
  const controller = new AbortController(); request = controller;
  const started = performance.now();
  $('ask').disabled = true;
  appendTurn('user', question);
  status(mode === 'meta' ? 'Luma está observando esta captura…' : 'Preparando respuesta preescrita…');
  const row = { mode, status: 'failed', roundTripMs: null, estimatedUsd: null, captureMs: mode === 'meta' ? captureMs : null };
  rows.push(row);
  try {
    const response = await fetch('/api/observe', {
      method: 'POST', headers: { 'Content-Type': 'application/json', 'X-Luma-Token': config.token },
      body: JSON.stringify({ mode, question, history, allowPaid: $('paid').checked, ...image }), signal: controller.signal,
    });
    const result = await response.json();
    if (result.gate) $('metric-remaining').textContent = result.gate.remaining;
    if (!response.ok) throw new Error(result.error || 'No pudimos completar la consulta.');
    if (controller.signal.aborted) return false;
    row.status = 'completed'; row.roundTripMs = Math.round(performance.now() - started);
    row.providerMs = result.providerMs; row.estimatedUsd = result.estimatedUsd; row.usage = result.usage;
    row.model = result.model;
    lastAnswer = result.text;
    history.push({ role: 'user', text: question }, { role: 'assistant', text: result.text }); history = history.slice(-4);
    appendTurn('assistant', result.text, result.mode === 'fixture' ? 'Demostración preescrita · sin análisis de imagen · US$ 0' : `Meta · captura puntual · ${(row.roundTripMs / 1000).toFixed(2)} s · ${result.estimatedUsd == null ? 'costo desconocido' : `~US$ ${result.estimatedUsd.toFixed(4)}`}`);
    $('metric-remaining').textContent = result.gate.remaining;
    status('Podés seguir preguntando. Luma conserva los últimos turnos de esta sesión.');
    if ($('speak').checked) speak(result.text);
    return true;
  } catch (error) {
    row.status = controller.signal.aborted ? 'cancelled' : 'failed';
    row.roundTripMs = Math.round(performance.now() - started);
    if (!controller.signal.aborted) status(error.message, true);
    stopCompanion(); return false;
  } finally {
    if (request === controller) { request = null; $('ask').disabled = false; }
    renderMetrics();
  }
}

async function startCompanion() {
  if (companionTimer || $('companion').dataset.active === 'true') { pauseAll(); return; }
  if (request) return status('Esperá a que termine la consulta actual.', true);
  const question = $('question').value.trim();
  if (!question) return status('Elegí primero qué querés explorar durante esta sesión.', true);
  if (isMeta() && (!stream || !$('paid').checked)) return status('Activá la cámara y autorizá el uso de Meta antes de iniciar.', true);
  const generation = ++companionGeneration;
  policy.interval = Number($('interval').value); policy.reset();
  $('companion').dataset.active = 'true'; $('companion').textContent = 'Detener sesión';
  const tick = async () => {
    if (generation !== companionGeneration) { $('companion').dataset.active = 'false'; return; }
    const next = policy.next();
    if (next === 'finished') { stopCompanion(); $('companion').dataset.active = 'false'; status('Sesión finalizada. La vista previa sigue activa; Pausar todo apaga la cámara.'); return; }
    if (next === 'capture' && policy.begin()) {
      const success = await observe(question); policy.end();
      if (!success || generation !== companionGeneration) { $('companion').dataset.active = 'false'; return; }
    }
    if (generation === companionGeneration) companionTimer = setTimeout(tick, 500);
  };
  await tick();
}

$('ask-form').addEventListener('submit', event => { event.preventDefault(); stopCompanion(); $('companion').dataset.active = 'false'; void observe($('question').value); });
document.querySelectorAll('[data-question]').forEach(button => button.addEventListener('click', () => { $('question').value = button.dataset.question; $('question').focus(); }));
$('camera').addEventListener('click', toggleCamera);
$('stop').addEventListener('click', () => { pauseAll(); $('companion').dataset.active = 'false'; });
$('companion').addEventListener('click', startCompanion);
$('repeat').addEventListener('click', () => lastAnswer ? speak(lastAnswer) : status('Todavía no hay una respuesta para repetir.'));
$('speak').addEventListener('change', () => { if (!$('speak').checked) stopSpeech(); });
$('paid').addEventListener('change', () => { if (!$('paid').checked) { pauseAll('Autorización retirada. Captura y consultas detenidas.'); $('companion').dataset.active = 'false'; } });
$('mode').addEventListener('change', () => {
  pauseAll('Modo cambiado. Empezamos una conversación nueva.'); history = []; lastAnswer = ''; $('transcript').replaceChildren();
  $('paid').checked = false; $('paid-label').hidden = !isMeta(); $('companion').dataset.active = 'false';
  $('mode-label').textContent = isMeta() ? 'Meta API · observaciones puntuales' : 'Demostración · respuestas preescritas';
  $('provider-note').textContent = isMeta() ? `${config.model}. Cada consulta envía una captura y hasta cuatro turnos de texto a Meta. La API puede cobrar también solicitudes canceladas o fallidas.` : 'Las respuestas del ejemplo son preescritas. No interpretan imágenes ni validan modelos.';
});
$('clear').addEventListener('click', () => { pauseAll('Conversación borrada de la memoria de esta página. Las métricas se conservan.'); history = []; lastAnswer = ''; $('transcript').replaceChildren(); $('companion').dataset.active = 'false'; });
$('export').addEventListener('click', () => {
  const report = { schemaVersion: 1, generatedAt: new Date().toISOString(), ...summarizeMetrics(rows), observations: rows, glassesBattery: 'not-measured', note: 'Local browser prototype. No provider/device/human validation implied. Failed or aborted requests can incur unreported costs.' };
  const url = URL.createObjectURL(new Blob([JSON.stringify(report, null, 2)], { type: 'application/json' }));
  const link = document.createElement('a'); link.href = url; link.download = 'luma-metrics.json'; link.click(); setTimeout(() => URL.revokeObjectURL(url), 1000);
});

const Recognition = window.SpeechRecognition || window.webkitSpeechRecognition;
if (Recognition) {
  $('dictate').hidden = false;
  $('dictate').addEventListener('click', () => {
    stopSpeech(); recognition?.abort();
    if (!confirm('El dictado del navegador puede enviar audio a su proveedor. ¿Querés dictar esta pregunta? También podés escribirla.')) return;
    recognition = new Recognition(); recognition.lang = 'es-AR'; recognition.continuous = false; recognition.interimResults = false;
    recognition.onresult = event => { $('question').value = event.results[0][0].transcript; status('Pregunta dictada. Revisala y elegí Preguntarle a Luma.'); $('question').focus(); };
    recognition.onerror = () => status('No pudimos dictar. Podés escribir la pregunta.', true);
    recognition.start(); status('Escuchando esta pregunta…');
  });
}
document.addEventListener('visibilitychange', () => { if (document.hidden) { pauseAll('La pestaña quedó oculta. Sesión y cámara detenidas.'); $('companion').dataset.active = 'false'; } });
window.addEventListener('pagehide', () => pauseAll());

try {
  const response = await fetch('/api/config'); if (!response.ok) throw new Error(); config = await response.json();
  $('meta-option').disabled = !config.metaEnabled;
  if (config.metaEnabled) $('meta-option').textContent = `Meta API · ${config.model}`;
  $('metric-remaining').textContent = config.gate.remaining;
  $('ask').disabled = false;
  status(config.metaEnabled ? 'Listo. Iniciamos en demostración; podés elegir Meta cuando quieras.' : 'Listo. Demostración sin costo. Meta requiere configurar .env en el servidor.');
} catch { status('No pudimos conectar con el servidor local. Ejecutá npm start y recargá.', true); }
