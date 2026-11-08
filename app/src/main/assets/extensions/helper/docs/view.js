'use strict';
// Swift's document viewer (2.4, 2.5). Swift sends the file over its private channel (a native port):
// PDFs piece by piece as pdf.js asks for them, everything else whole. Nothing leaves the phone.
// Each kind has a renderer below; `viewer` holds what the bar's buttons do for the open one.

const id = decodeURIComponent(location.hash.slice(1));
const $ = s => document.querySelector(s);
const docEl = $('#doc');
// 2.8: on Swift's document screen (not in a browser tab): the bar has a Back arrow of its own.
const ownScreen = /[?&]screen\b/.test(location.search);
if (ownScreen) {
  document.body.classList.add('screen');
  $('#back').hidden = false;
  $('#back').addEventListener('click', () => action('close'));
}

const KINDS = {
  pdf: ['PDF', '#d93025'],
  word: ['DOC', '#2b579a'],
  oldword: ['DOC', '#2b579a'],
  odt: ['ODT', '#2b579a'],
  rtf: ['RTF', '#2b579a'],
  sheet: ['XLS', '#217346'],
  slides: ['PPT', '#c43e1c'],
  oldslides: ['PPT', '#c43e1c'],
  odp: ['ODP', '#c43e1c'],
  epub: ['EPUB', '#7b3fa0'],
  text: ['TXT', '#5f6368'],
  zip: ['ZIP', '#8d6e00'],
};

// The app's own icons (Material Symbols Rounded, in the same viewBox), for what the page draws itself.
const SYMBOL = {
  back: 'm313,-440l196,196q12,12 11.5,28T508,-188q-12,11 -28,11.5T452,-188L188,-452q-6,-6 -8.5,-13t-2.5,-15q0,-8 2.5,-15t8.5,-13l264,-264q11,-11 27.5,-11t28.5,11q12,12 12,28.5T508,-715L313,-520h447q17,0 28.5,11.5T800,-480q0,17 -11.5,28.5T760,-440H313Z',
  play: 'M320,-273v-414q0,-17 12,-28.5t28,-11.5q5,0 10.5,1.5T381,-721l326,207q9,6 13.5,15t4.5,19q0,10 -4.5,19T707,-446L381,-239q-5,3 -10.5,4.5T360,-233q-16,0 -28,-11.5T320,-273Z',
  search: 'M380,-320q-109,0 -184.5,-75.5T120,-580q0,-109 75.5,-184.5T380,-840q109,0 184.5,75.5T640,-580q0,44 -14,83t-38,69l224,224q11,11 11,28t-11,28q-11,11 -28,11t-28,-11L532,-372q-30,24 -69,38t-83,14Zm0,-80q75,0 127.5,-52.5T560,-580q0,-75 -52.5,-127.5T380,-760q-75,0 -127.5,52.5T200,-580q0,75 52.5,127.5T380,-400Z',
  add: 'M440,-440H240q-17,0 -28.5,-11.5T200,-480q0,-17 11.5,-28.5T240,-520h200v-200q0,-17 11.5,-28.5T480,-760q17,0 28.5,11.5T520,-720v200h200q17,0 28.5,11.5T760,-480q0,17 -11.5,28.5T720,-440H520v200q0,17 -11.5,28.5T480,-200q-17,0 -28.5,-11.5T440,-240v-200Z',
  volume: 'M760,-481q0,-83 -44,-151.5T598,-735q-15,-7 -22,-21.5t-2,-29.5q6,-16 21.5,-23t31.5,0q97,43 155,131.5T840,-481q0,108 -58,196.5T627,-153q-16,7 -31.5,0T574,-176q-5,-15 2,-29.5t22,-21.5q74,-34 118,-102.5T760,-481ZM280,-360H160q-17,0 -28.5,-11.5T120,-400v-160q0,-17 11.5,-28.5T160,-600h120l132,-132q19,-19 43.5,-8.5T480,-703v446q0,27 -24.5,37.5T412,-228L280,-360Zm380,-120q0,42 -19,79.5T591,-339q-10,6 -20.5,0.5T560,-356v-250q0,-12 10.5,-17.5t20.5,0.5q31,25 50,63t19,80ZM400,-606l-86,86H200v80h114l86,86v-252ZM300,-480Z',
  more: 'M480,-160q-33,0 -56.5,-23.5T400,-240q0,-33 23.5,-56.5T480,-320q33,0 56.5,23.5T560,-240q0,33 -23.5,56.5T480,-160Zm0,-240q-33,0 -56.5,-23.5T400,-480q0,-33 23.5,-56.5T480,-560q33,0 56.5,23.5T560,-480q0,33 -23.5,56.5T480,-400Zm0,-240q-33,0 -56.5,-23.5T400,-720q0,-33 23.5,-56.5T480,-800q33,0 56.5,23.5T560,-720q0,33 -23.5,56.5T480,-640Z',
  up: 'M480,-528L324,-372q-11,11 -28,11t-28,-11q-11,-11 -11,-28t11,-28l184,-184q12,-12 28,-12t28,12l184,184q11,11 11,28t-11,28q-11,11 -28,11t-28,-11L480,-528Z',
  down: 'M480,-361q-8,0 -15,-2.5t-13,-8.5L268,-556q-11,-11 -11,-28t11,-28q11,-11 28,-11t28,11l156,156 156,-156q11,-11 28,-11t28,11q11,11 11,28t-11,28L508,-372q-6,6 -13,8.5t-15,2.5Z',
  close: 'M480,-424L284,-228q-11,11 -28,11t-28,-11q-11,-11 -11,-28t11,-28l196,-196 -196,-196q-11,-11 -11,-28t11,-28q11,-11 28,-11t28,11l196,196 196,-196q11,-11 28,-11t28,11q11,11 11,28t-11,28L536,-480l196,196q11,11 11,28t-11,28q-11,11 -28,11t-28,-11L480,-424Z',
  remove: 'M240-440q-17 0-28.5-11.5T200-480q0-17 11.5-28.5T240-520h480q17 0 28.5 11.5T760-480q0 17-11.5 28.5T720-440H240Z',
  prev: 'm432-480 156 156q11 11 11 28t-11 28q-11 11-28 11t-28-11L348-452q-6-6-8.5-13t-2.5-15q0-8 2.5-15t8.5-13l184-184q11-11 28-11t28 11q11 11 11 28t-11 28L432-480Z',
  next: 'M504,-480L348,-636q-11,-11 -11,-28t11,-28q11,-11 28,-11t28,11l184,184q6,6 8.5,13t2.5,15q0,8 -2.5,15t-8.5,13L404,-268q-11,11 -28,11t-28,-11q-11,-11 -11,-28t11,-28l156,-156Z',
  share: 'M720,-80q-50,0 -85,-35t-35,-85q0,-7 1,-14.5t3,-13.5L322,-392q-17,15 -38,23.5t-44,8.5q-50,0 -85,-35t-35,-85q0,-50 35,-85t85,-35q23,0 44,8.5t38,23.5l282,-164q-2,-6 -3,-13.5t-1,-14.5q0,-50 35,-85t85,-35q50,0 85,35t35,85q0,50 -35,85t-85,35q-23,0 -44,-8.5T638,-672L356,-508q2,6 3,13.5t1,14.5q0,7 -1,14.5t-3,13.5l282,164q17,-15 38,-23.5t44,-8.5q50,0 85,35t35,85q0,50 -35,85t-85,35Zm0,-640q17,0 28.5,-11.5T760,-760q0,-17 -11.5,-28.5T720,-800q-17,0 -28.5,11.5T680,-760q0,17 11.5,28.5T720,-720ZM240,-440q17,0 28.5,-11.5T280,-480q0,-17 -11.5,-28.5T240,-520q-17,0 -28.5,11.5T200,-480q0,17 11.5,28.5T240,-440Zm480,280q17,0 28.5,-11.5T760,-200q0,-17 -11.5,-28.5T720,-240q-17,0 -28.5,11.5T680,-200q0,17 11.5,28.5T720,-160Zm0,-600ZM240,-480Zm480,280Z',
  open: 'M200,-120q-33,0 -56.5,-23.5T120,-200v-560q0,-33 23.5,-56.5T200,-840h240q17,0 28.5,11.5T480,-800q0,17 -11.5,28.5T440,-760H200v560h560v-240q0,-17 11.5,-28.5T800,-480q17,0 28.5,11.5T840,-440v240q0,33 -23.5,56.5T760,-120H200Zm560,-584L416,-360q-11,11 -28,11t-28,-11q-11,-11 -11,-28t11,-28l344,-344H600q-17,0 -28.5,-11.5T560,-800q0,-17 11.5,-28.5T600,-840h200q17,0 28.5,11.5T840,-800v200q0,17 -11.5,28.5T800,-560q-17,0 -28.5,-11.5T760,-600v-104Z',
  download: 'M480,-337q-8,0 -15,-2.5t-13,-8.5L308,-492q-12,-12 -11.5,-28t11.5,-28q12,-12 28.5,-12.5T365,-549l75,75v-286q0,-17 11.5,-28.5T480,-800q17,0 28.5,11.5T520,-760v286l75,-75q12,-12 28.5,-11.5T652,-548q11,12 11.5,28T652,-492L508,-348q-6,6 -13,8.5t-15,2.5ZM240,-160q-33,0 -56.5,-23.5T160,-240v-80q0,-17 11.5,-28.5T200,-360q17,0 28.5,11.5T240,-320v80h480v-80q0,-17 11.5,-28.5T760,-360q17,0 28.5,11.5T800,-320v80q0,33 -23.5,56.5T720,-160H240Z',
  edit: 'M200,-200h57l391,-391 -57,-57 -391,391v57Zm-40,80q-17,0 -28.5,-11.5T120,-160v-97q0,-16 6,-30.5t17,-25.5l505,-504q12,-11 26.5,-17t30.5,-6q16,0 31,6t26,18l55,56q12,11 17.5,26t5.5,30q0,16 -5.5,30.5T817,-647L313,-143q-11,11 -25.5,17t-30.5,6h-97Zm600,-584l-56,-56 56,56Zm-141,85l-28,-29 57,57 -29,-28Z',
  style: 'M560,-680H420q-25,0 -42.5,-17.5T360,-740q0,-25 17.5,-42.5T420,-800h400q25,0 42.5,17.5T880,-740q0,25 -17.5,42.5T820,-680H680v460q0,25 -17.5,42.5T620,-160q-25,0 -42.5,-17.5T560,-220v-460ZM200,-480h-60q-25,0 -42.5,-17.5T80,-540q0,-25 17.5,-42.5T140,-600h240q25,0 42.5,17.5T440,-540q0,25 -17.5,42.5T380,-480h-60v260q0,25 -17.5,42.5T260,-160q-25,0 -42.5,-17.5T200,-220v-260Z',
  reader: 'M200,-120q-33,0 -56.5,-23.5T120,-200v-560q0,-33 23.5,-56.5T200,-840h560q33,0 56.5,23.5T840,-760v560q0,33 -23.5,56.5T760,-120H200Zm0,-80h560v-560H200v560Zm0,-560v560 -560Zm120,480h200q17,0 28.5,-11.5T560,-320q0,-17 -11.5,-28.5T520,-360H320q-17,0 -28.5,11.5T280,-320q0,17 11.5,28.5T320,-280Zm0,-160h320q17,0 28.5,-11.5T680,-480q0,-17 -11.5,-28.5T640,-520H320q-17,0 -28.5,11.5T280,-480q0,17 11.5,28.5T320,-440Zm0,-160h320q17,0 28.5,-11.5T680,-640q0,-17 -11.5,-28.5T640,-680H320q-17,0 -28.5,11.5T280,-640q0,17 11.5,28.5T320,-600Z',
  pages: 'M200,-520q-33,0 -56.5,-23.5T120,-600v-160q0,-33 23.5,-56.5T200,-840h160q33,0 56.5,23.5T440,-760v160q0,33 -23.5,56.5T360,-520H200Zm0,400q-33,0 -56.5,-23.5T120,-200v-160q0,-33 23.5,-56.5T200,-440h160q33,0 56.5,23.5T440,-360v160q0,33 -23.5,56.5T360,-120H200Zm400,-400q-33,0 -56.5,-23.5T520,-600v-160q0,-33 23.5,-56.5T600,-840h160q33,0 56.5,23.5T840,-760v160q0,33 -23.5,56.5T760,-520H600Zm0,400q-33,0 -56.5,-23.5T520,-200v-160q0,-33 23.5,-56.5T600,-440h160q33,0 56.5,23.5T840,-360v160q0,33 -23.5,56.5T760,-120H600ZM200,-600h160v-160H200v160Zm400,0h160v-160H600v160Zm0,400h160v-160H600v160Zm-400,0h160v-160H200v160Zm400,-400Zm0,240Zm-240,0Zm0,-240Z',
  info: 'M480,-280q17,0 28.5,-11.5T520,-320v-160q0,-17 -11.5,-28.5T480,-520q-17,0 -28.5,11.5T440,-480v160q0,17 11.5,28.5T480,-280Zm0,-320q17,0 28.5,-11.5T520,-640q0,-17 -11.5,-28.5T480,-680q-17,0 -28.5,11.5T440,-640q0,17 11.5,28.5T480,-600Zm0,520q-83,0 -156,-31.5T197,-197q-54,-54 -85.5,-127T80,-480q0,-83 31.5,-156T197,-763q54,-54 127,-85.5T480,-880q83,0 156,31.5T763,-763q54,54 85.5,127T880,-480q0,83 -31.5,156T763,-197q-54,54 -127,85.5T480,-80Zm0,-80q134,0 227,-93t93,-227q0,-134 -93,-227t-227,-93q-134,0 -227,93t-93,227q0,134 93,227t227,93Zm0,-320Z',
  home: 'M320,-40q-33,0 -56.5,-23.5T240,-120v-120q0,-17 11.5,-28.5T280,-280q17,0 28.5,11.5T320,-240h400v-480H320q0,17 -11.5,28.5T280,-680q-17,0 -28.5,-11.5T240,-720v-120q0,-33 23.5,-56.5T320,-920h400q33,0 56.5,23.5T800,-840v720q0,33 -23.5,56.5T720,-40H320Zm0,-120v40h400v-40H320Zm80,-344L204,-308q-11,11 -28,11t-28,-11q-11,-11 -11,-28t11,-28l196,-196H240q-17,0 -28.5,-11.5T200,-600q0,-17 11.5,-28.5T240,-640h200q17,0 28.5,11.5T480,-600v200q0,17 -11.5,28.5T440,-360q-17,0 -28.5,-11.5T400,-400v-104Zm-80,-296h400v-40H320v40Zm0,0v-40 40Zm0,640v40 -40Z',
  goto: 'M647-440H200q-17 0-28.5-11.5T160-480q0-17 11.5-28.5T200-520h447L451-716q-12-12-11.5-28t12.5-28q12-11 28-11.5t28 11.5l264 264q6 6 8.5 13t2.5 15q0 8-2.5 15t-8.5 13L508-188q-11 11-27.5 11T452-188q-12-12-12-28.5t12-28.5l195-195Z',
  chapters: 'M160-280q-17 0-28.5-11.5T120-320q0-17 11.5-28.5T160-360h480q17 0 28.5 11.5T680-320q0 17-11.5 28.5T640-280H160Zm0-160q-17 0-28.5-11.5T120-480q0-17 11.5-28.5T160-520h480q17 0 28.5 11.5T680-480q0 17-11.5 28.5T640-440H160Zm0-160q-17 0-28.5-11.5T120-640q0-17 11.5-28.5T160-680h480q17 0 28.5 11.5T680-640q0 17-11.5 28.5T640-600H160Zm640 320q-17 0-28.5-11.5T760-320q0-17 11.5-28.5T800-360q17 0 28.5 11.5T840-320q0 17-11.5 28.5T800-280Zm0-160q-17 0-28.5-11.5T760-480q0-17 11.5-28.5T800-520q17 0 28.5 11.5T840-480q0 17-11.5 28.5T800-440Zm0-160q-17 0-28.5-11.5T760-640q0-17 11.5-28.5T800-680q17 0 28.5 11.5T840-640q0 17-11.5 28.5T800-600Z',
  pdf: 'M400-540h40q17 0 28.5-11.5T480-580v-40q0-17-11.5-28.5T440-660h-60q-8 0-14 6t-6 14v160q0 8 6 14t14 6q8 0 14-6t6-14v-60Zm0-40v-40h40v40h-40Zm200 120q17 0 28.5-11.5T640-500v-120q0-17-11.5-28.5T600-660h-60q-8 0-14 6t-6 14v160q0 8 6 14t14 6h60Zm-40-40v-120h40v120h-40Zm160-40h20q8 0 14-6t6-14q0-8-6-14t-14-6h-20v-40h20q8 0 14-6t6-14q0-8-6-14t-14-6h-40q-8 0-14 6t-6 14v160q0 8 6 14t14 6q8 0 14-6t6-14v-60ZM320-240q-33 0-56.5-23.5T240-320v-480q0-33 23.5-56.5T320-880h480q33 0 56.5 23.5T880-800v480q0 33-23.5 56.5T800-240H320Zm0-80h480v-480H320v480ZM160-80q-33 0-56.5-23.5T80-160v-520q0-17 11.5-28.5T120-720q17 0 28.5 11.5T160-680v520h520q17 0 28.5 11.5T720-120q0 17-11.5 28.5T680-80H160Zm160-720v480-480Z',
  print: 'M320-120q-33 0-56.5-23.5T240-200v-80h-80q-33 0-56.5-23.5T80-360v-160q0-51 35-85.5t85-34.5h560q51 0 85.5 34.5T880-520v160q0 33-23.5 56.5T800-280h-80v80q0 33-23.5 56.5T640-120H320ZM160-360h80q0-33 23.5-56.5T320-440h320q33 0 56.5 23.5T720-360h80v-160q0-17-11.5-28.5T760-560H200q-17 0-28.5 11.5T160-520v160Zm480-280v-120H320v120h-80v-120q0-33 23.5-56.5T320-840h320q33 0 56.5 23.5T720-760v120h-80Zm80 180q17 0 28.5-11.5T760-500q0-17-11.5-28.5T720-540q-17 0-28.5 11.5T680-500q0 17 11.5 28.5T720-460Zm-80 260v-160H320v160h320ZM160-560h640-640Z',
  web: 'M480-80q-82 0-155-31.5t-127.5-86Q143-252 111.5-325T80-480q0-83 31.5-155.5t86-127Q252-817 325-848.5T480-880q83 0 155.5 31.5t127 86q54.5 54.5 86 127T880-480q0 82-31.5 155t-86 127.5q-54.5 54.5-127 86T480-80Zm0-82q26-36 45-75t31-83H404q12 44 31 83t45 75Zm-104-16q-18-33-31.5-68.5T322-320H204q29 50 72.5 87t99.5 55Zm208 0q56-18 99.5-55t72.5-87H638q-9 38-22.5 73.5T584-178ZM170-400h136q-3-20-4.5-39.5T300-480q0-21 1.5-40.5T306-560H170q-5 20-7.5 39.5T160-480q0 21 2.5 40.5T170-400Zm216 0h188q3-20 4.5-39.5T580-480q0-21-1.5-40.5T574-560H386q-3 20-4.5 39.5T380-480q0 21 1.5 40.5T386-400Zm268 0h136q5-20 7.5-39.5T800-480q0-21-2.5-40.5T790-560H654q3 20 4.5 39.5T660-480q0 21-1.5 40.5T654-400Zm-16-240h118q-29-50-72.5-87T584-782q18 33 31.5 68.5T638-640Zm-234 0h152q-12-44-31-83t-45-75q-26 36-45 75t-31 83Zm-200 0h118q9-38 22.5-73.5T376-782q-56 18-99.5 55T204-640Z',
  error: 'M480,-280q17,0 28.5,-11.5T520,-320q0,-17 -11.5,-28.5T480,-360q-17,0 -28.5,11.5T440,-320q0,17 11.5,28.5T480,-280Zm0,-160q17,0 28.5,-11.5T520,-480v-160q0,-17 -11.5,-28.5T480,-680q-17,0 -28.5,11.5T440,-640v160q0,17 11.5,28.5T480,-440Zm0,360q-83,0 -156,-31.5T197,-197q-54,-54 -85.5,-127T80,-480q0,-83 31.5,-156T197,-763q54,-54 127,-85.5T480,-880q83,0 156,31.5T763,-763q54,54 85.5,127T880,-480q0,83 -31.5,156T763,-197q-54,54 -127,85.5T480,-80Zm0,-80q134,0 227,-93t93,-227q0,-134 -93,-227t-227,-93q-134,0 -227,93t-93,227q0,134 93,227t227,93Zm0,-320Z',
};
function symbol(name) { return '<svg viewBox="0 -960 960 960" aria-hidden="true"><path d="' + SYMBOL[name] + '"/></svg>'; }

let info = null;
let port = null;
let viewer = {};
let scale = 1;

// ------------------------------------------------------------------ talking to Swift

function send(msg) {
  if (port === null) {
    try {
      port = browser.runtime.connectNative('swift');
    } catch (e) {
      port = null;
      return;
    }
    port.onMessage.addListener(onMessage);
    port.onDisconnect.addListener(() => { port = null; });
  }
  try { port.postMessage(msg); } catch (e) { port = null; }
}

function onMessage(msg) {
  if (!msg || !msg.type) { return; }
  if (msg.type === 'doc-info') { start(msg); }
  else if (msg.type === 'doc-data') { onData(msg); }
  else if (msg.type === 'doc-range-data') { onRange(msg); }
  else if (msg.type === 'doc-problem') { problem(msg.text, msg.actions || []); }
  else if (msg.type === 'doc-zip-list') { if (zipWait) { zipWait(msg); zipWait = null; } }
  else if (msg.type === 'doc-html') { onConverted(msg); }
  else if (msg.type === 'tts-state') { onTts(msg); }
}

function action(name, extra) { send(Object.assign({ type: 'doc-action', id, action: name }, extra || {})); }

// 3.0: the engine's own base64 (Firefox 133 and later) when it has it: many times faster than atob
// and a loop, on the thread uBlock Origin shares with this page.
function b64(s) {
  if (typeof Uint8Array.fromBase64 === 'function') { return Uint8Array.fromBase64(s); }
  const bin = atob(s);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) { out[i] = bin.charCodeAt(i); }
  return out;
}

function toB64(bytes) {
  if (typeof bytes.toBase64 === 'function') { return bytes.toBase64(); }
  let s = '';
  for (let i = 0; i < bytes.length; i += 32768) { s += String.fromCharCode.apply(null, bytes.subarray(i, i + 32768)); }
  return btoa(s);
}

/** A file made here (a filled-in PDF) goes to Swift in pieces, which saves it to Downloads. */
function sendFile(name, mime, bytes) {
  const PART = 196608;
  for (let at = 0, seq = 0; at < bytes.length || seq === 0; at += PART, seq++) {
    const end = Math.min(bytes.length, at + PART);
    send({ type: 'doc-save', id, name, mime, seq, data: toB64(bytes.subarray(at, end)), last: end >= bytes.length });
    if (end >= bytes.length) { break; }
  }
}

// The whole file, in pieces of a few hundred KB.
let whole = null;
function readAll() {
  return new Promise((resolve, reject) => {
    whole = { resolve, reject, parts: [], got: 0 };
    send({ type: 'doc-read', id });
  });
}

function onData(msg) {
  if (!whole) { return; }
  if (msg.data) {
    const bytes = b64(msg.data);
    whole.parts.push(bytes);
    whole.got += bytes.length;
  }
  if (info && info.size > 0 && !msg.last) {
    status('Opening… ' + Math.min(99, Math.round(whole.got * 100 / info.size)) + '%');
  }
  if (msg.last) {
    const all = new Uint8Array(whole.got);
    let at = 0;
    for (const p of whole.parts) { all.set(p, at); at += p.length; }
    const done = whole.resolve;
    whole = null;
    done(all.buffer);
  }
}

// PDFs: the pieces pdf.js asks for.
let pdfRange = null;
let nextReq = 1;
const askedAt = new Map();
function onRange(msg) {
  const begin = askedAt.get(msg.req);
  askedAt.delete(msg.req);
  if (msg.error) {
    problem("Swift couldn't read this PDF any more. It may have been moved, or the app it came from no longer shares it.",
      [{ label: 'Open with another app', action: 'open-with' }]);
    return;
  }
  if (pdfRange && begin !== undefined && msg.data) { pdfRange.onDataRange(begin, b64(msg.data)); }
}

// ------------------------------------------------------------------ the screen

function status(text) {
  const s = $('#status');
  keepStatus(s);
  s.hidden = false;
  s.classList.remove('problem');
  $('#statustext').textContent = text;
}

// The loading screen fades out over the page instead of snapping away.
let statusTimer = 0;
function ready() {
  const s = $('#status');
  if (s.hidden || s.classList.contains('leaving')) { return; }
  s.classList.add('leaving');
  statusTimer = setTimeout(() => { s.hidden = true; s.classList.remove('leaving'); }, 150);
}

/** Something more to say on the status screen: a fade-out under way stops. */
function keepStatus(s) {
  clearTimeout(statusTimer);
  s.classList.remove('leaving');
}

/** The status screen has gone (or is fading out). */
function statusGone() {
  const s = $('#status');
  return s.hidden || s.classList.contains('leaving');
}

/** What closes fades away: a copy of it, while the real one is hidden at once, so nothing waits for it. */
function hide(el) {
  if (el.hidden) { return; }
  const copy = el.cloneNode(true);
  copy.classList.add('going');
  copy.setAttribute('aria-hidden', 'true');
  document.body.appendChild(copy);
  copy.scrollTop = el.scrollTop;
  el.hidden = true;
  setTimeout(() => copy.remove(), 200);
}

function problem(text, actions) {
  const s = $('#status');
  keepStatus(s);
  s.hidden = false;
  s.classList.add('problem');
  $('#statustext').textContent = text;
  const box = $('#statusactions');
  box.textContent = '';
  for (const a of actions) {
    const b = document.createElement('button');
    b.textContent = a.label;
    // 3.0: a button can also act in the page itself (type a PDF's password again).
    b.addEventListener('click', () => (a.run ? a.run() : action(a.action)));
    box.appendChild(b);
  }
}

function note(text, button, onClick) {
  const n = $('#note');
  n.textContent = text;
  if (button) {
    const b = document.createElement('button');
    b.textContent = button;
    b.addEventListener('click', onClick);
    n.appendChild(b);
  }
  n.hidden = false;
}

let toastTimer = 0;
function toast(text, ms) {
  const t = $('#toast');
  t.textContent = text;
  t.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => hide(t), ms || 3000);
}

function dark() { return matchMedia('(prefers-color-scheme: dark)').matches; }

function cssVar(name) { return getComputedStyle(document.documentElement).getPropertyValue(name).trim(); }

function loadScript(src) {
  return new Promise((resolve, reject) => {
    if (document.querySelector('script[src="' + src + '"]')) { resolve(); return; }
    const s = document.createElement('script');
    s.src = src;
    s.onload = resolve;
    s.onerror = () => reject(new Error('Could not load ' + src));
    document.head.appendChild(s);
  });
}

function addCss(href) {
  const l = document.createElement('link');
  l.rel = 'stylesheet';
  l.href = href;
  document.head.appendChild(l);
}

/** Small things remembered on the phone (text size per document, the e-book style). */
function store(key, value) {
  try {
    if (value === undefined) { return localStorage.getItem(key); }
    localStorage.setItem(key, value);
  } catch (e) { /* private window or storage off: nothing is remembered */ }
  return null;
}

// Where you are, sent to Swift a moment after you stop (Continue on the home page, reopening).
let placeTimer = 0;
function savePlace(place, progress, total) {
  clearTimeout(placeTimer);
  placeTimer = setTimeout(() => {
    send({ type: 'doc-place', id, place: String(place), progress: Math.max(0, Math.min(1, progress || 0)), total: total || 0 });
  }, 700);
}

const PROTECTED = "This file is protected with a password. Swift can't open protected Office files: open it with another app.";

/** Office 2007+ files that are encrypted are stored inside an old-style container instead of a ZIP. */
function locked(buf) {
  if (!['word', 'sheet', 'slides'].includes(info.kind) || buf.byteLength < 8) { return false; }
  const a = new Uint8Array(buf, 0, 8);
  return a[0] === 0xD0 && a[1] === 0xCF && a[2] === 0x11 && a[3] === 0xE0 && !/\.(xls|csv|tsv)$/i.test(info.name);
}

async function start(msg) {
  if (info) { return; }
  info = msg;
  // 3.0.5: the app says whether its own font is in use (see fonts/swift-font.js).
  applySwiftFont(info.swiftFont !== false);
  document.title = info.name;
  $('#name').textContent = info.name;
  const k = KINDS[info.kind] || KINDS.text;
  $('#badge').textContent = k[0];
  document.documentElement.style.setProperty('--kind', k[1]);
  const accent = dark() ? info.accentDark : info.accentLight;
  if (accent) { document.documentElement.style.setProperty('--accent', accent); }
  // Swift's own colours (2.6), so the viewer looks like the rest of the app.
  const colors = dark() ? info.colorsDark : info.colorsLight;
  applyDocColors(colors);
  // For the next document's first paint (fonts/swift-font.js).
  keepDocColors(info.colorsLight, info.colorsDark);
  status('Opening…');
  try {
    if (info.kind === 'pdf') {
      await openPdf();
    } else if (info.kind === 'zip') {
      await RENDER.zip();
    } else {
      const buf = await readAll();
      status('Opening…');
      if (locked(buf)) {
        problem(PROTECTED, [{ label: 'Open with another app', action: 'open-with' }]);
        return;
      }
      await (RENDER[info.kind] || RENDER.text)(buf);
    }
    restoreSize();
    showButtons();
    document.documentElement.classList.toggle('paper', !!document.querySelector('.docx-wrapper, #slides, .oo-wrap'));
  } catch (e) {
    console.error(e);
    if (/password|encrypt/i.test(String(e && e.message))) {
      problem(PROTECTED, [{ label: 'Open with another app', action: 'open-with' }]);
      return;
    }
    problem("Swift couldn't show this file. It may be damaged, protected by a password, or in a form Swift doesn't read.",
      [{ label: 'Open with another app', action: 'open-with' }]);
  }
}

function showButtons() {
  $('#find').hidden = false;
  $('#smaller').hidden = !viewer.size;
  $('#bigger').hidden = !viewer.size;
  $('#listen').hidden = !viewer.texts;
  $('#play').hidden = !viewer.slideshow;
}

// Each document keeps its own text size (2.5).
const SIZE_KEY = 'swift-doc-size:' + id;
function restoreSize() {
  const s = parseFloat(store(SIZE_KEY));
  if (!viewer.size || info.kind === 'pdf' || !(s > 0) || s === 1) { return; }
  scale = s;
  viewer.size(scale);
}

// ------------------------------------------------------------------ Back closes what is open (2.6)

// The menu, panels, page previews, the slideshow, find and Fill & sign each take one step of
// Back: while any is open the page has one extra history entry, and Back closes the top one.
const overlays = [];
let pushed = false;
let skipPop = false;
function opened(close) {
  if (!overlays.includes(close)) { overlays.push(close); }
  if (!pushed) {
    history.pushState({ swift: 1 }, '');
    pushed = true;
  }
}
function closed(close) {
  const i = overlays.indexOf(close);
  if (i >= 0) { overlays.splice(i, 1); }
  if (!overlays.length && pushed) {
    pushed = false;
    skipPop = true;
    history.back();
  }
}
addEventListener('popstate', () => {
  if (skipPop) { skipPop = false; return; }
  pushed = false;
  const close = overlays.pop();
  if (close) { close(true); }
  // Something else is still open (a menu over the page previews): the next Back closes it.
  if (overlays.length) {
    history.pushState({ swift: 1 }, '');
    pushed = true;
  }
});

/** A question inside the page, with its own buttons (Save / Discard), unlike the browser's OK/Cancel. */
function ask(text, buttons) {
  const box = document.createElement('div');
  box.id = 'ask';
  const card = document.createElement('div');
  card.className = 'card';
  const p = document.createElement('p');
  p.textContent = text;
  card.appendChild(p);
  const row = document.createElement('div');
  row.className = 'row';
  for (const [label, run, main] of buttons) {
    const b = document.createElement('button');
    b.textContent = label;
    if (main) { b.className = 'main'; }
    b.addEventListener('click', () => { box.remove(); run(); });
    row.appendChild(b);
  }
  card.appendChild(row);
  box.appendChild(card);
  document.body.appendChild(box);
}

/**
 * 3.0: a box inside the page asking for something typed (a PDF's password, a page number): the
 * viewer's own screen has no browser dialogs (prompt() answered nothing there). [check] gets what was
 * typed and returns what is wrong with it, or nothing when it is fine; Back or Cancel run [cancel].
 * The returned box can say an answer was wrong ([wrong]) and closes with [close].
 */
function askFor({ text, type, value, placeholder, ok, check, cancel, showable }) {
  const box = document.createElement('div');
  box.id = 'ask';
  box.className = 'input';
  const card = document.createElement('div');
  card.className = 'card';
  const p = document.createElement('p');
  p.textContent = text;
  const input = document.createElement('input');
  input.type = type || 'text';
  input.autocomplete = 'off';
  input.spellcheck = false;
  if (type === 'number') { input.inputMode = 'numeric'; }
  if (value != null) { input.value = value; }
  if (placeholder) { input.placeholder = placeholder; }
  const err = document.createElement('p');
  err.className = 'error';
  err.hidden = true;
  const row = document.createElement('div');
  row.className = 'row';
  const button = (label, run, main) => {
    const b = document.createElement('button');
    b.textContent = label;
    if (main) { b.className = 'main'; }
    b.addEventListener('click', run);
    row.appendChild(b);
    return b;
  };
  let open = true;
  const remove = () => { open = false; box.remove(); };
  // Back closes it as Cancel does.
  const byBack = () => { remove(); if (cancel) { cancel(); } };
  const close = () => { if (open) { remove(); closed(byBack); } };
  const submit = () => {
    const problemText = check(input.value);
    if (problemText) { wrong(problemText); } else if (open && box.dataset.wait !== '1') { close(); }
  };
  const wrong = t => {
    err.textContent = t;
    err.hidden = false;
    box.dataset.wait = '';
    go.disabled = false;
    input.disabled = false;
    input.select();
    input.focus();
  };
  if (showable) {
    // A password can be shown while typed (no one is looking over the phone).
    button('Show', e => {
      const shown = input.type === 'text';
      input.type = shown ? 'password' : 'text';
      e.target.textContent = shown ? 'Show' : 'Hide';
      input.focus();
    }).classList.add('aside');
  }
  button('Cancel', () => { close(); if (cancel) { cancel(); } });
  const go = button(ok || 'OK', submit, true);
  input.addEventListener('keydown', e => { if (e.key === 'Enter') { e.preventDefault(); submit(); } });
  card.append(p, input, err, row);
  box.appendChild(card);
  document.body.appendChild(box);
  opened(byBack);
  setTimeout(() => { input.focus(); input.select(); }, 50);
  return {
    wrong,
    close,
    /** Waiting for the answer to be checked elsewhere (a password): the box stays until [close] or [wrong]. */
    wait() { box.dataset.wait = '1'; go.disabled = true; input.disabled = true; err.hidden = true; },
    get open() { return open; },
  };
}

// ------------------------------------------------------------------ reading mode (2.5)

// While reading, Swift's toolbar and the viewer's bar go away; scrolling up or a tap brings them back.
let reading = false;
function setReading(on, auto) {
  if (reading === on) { return; }
  reading = on;
  // A box that grows under the hidden bar (PDF, sheets) scrolls along, so the page stays still.
  const box = viewer.scroller;
  const before = box ? box.getBoundingClientRect().top : 0;
  document.body.classList.toggle('reading', on);
  if (box) {
    const moved = box.getBoundingClientRect().top - before;
    if (moved) { box.scrollTop += moved; lastTop = box.scrollTop; }
  }
  send({ type: 'doc-reading', id, on, auto: !!auto });
  if (!$('#fastscroll').hidden || viewer.pageLabel) { showPill(); }
}

function canHideBars() {
  return $('#findbar').hidden && $('#menu').hidden && $('#panel').hidden && !viewer.editing;
}

/** Taps on the page (not on links, buttons or form fields): one toggles the bars, two quick ones zoom (PDF). */
let tapStart = null;
let lastTap = null;
let tapTimer = 0;
addEventListener('touchstart', e => {
  if (e.touches.length !== 1) { tapStart = null; lastTap = null; return; }
  const t = e.touches[0];
  tapStart = { x: t.clientX, y: t.clientY, time: Date.now(), target: e.target };
}, { passive: true, capture: true });
addEventListener('touchmove', e => {
  if (!tapStart) { return; }
  const t = e.touches[0];
  if (Math.abs(t.clientX - tapStart.x) > 12 || Math.abs(t.clientY - tapStart.y) > 12) { tapStart = null; }
}, { passive: true, capture: true });
addEventListener('touchend', e => {
  const s = tapStart;
  tapStart = null;
  if (!s || e.touches.length || Date.now() - s.time > 350 || interactive(s.target)) { return; }
  onTap(s.x, s.y);
}, { passive: true, capture: true });

function interactive(el) {
  return !!(el && el.closest && el.closest('a, button, input, select, textarea, label, summary, [contenteditable], #bar, #findbar, ' +
    '#menu, #panel, #sheets, #tts, #note, #status, #editbar, #pagepill, #fastscroll, #show, #pages, #pageshead, #ask, .sheetmore, .annotationLayer section, .annotationEditorLayer'));
}

function onTap(x, y) {
  if (!$('#menu').hidden || !$('#panel').hidden || viewer.editing) { return; }
  const now = Date.now();
  if (lastTap && now - lastTap.time < 320 && Math.hypot(x - lastTap.x, y - lastTap.y) < 40) {
    clearTimeout(tapTimer);
    lastTap = null;
    if (viewer.doubleTap) { viewer.doubleTap(x, y); }
    return;
  }
  lastTap = { x, y, time: now };
  clearTimeout(tapTimer);
  tapTimer = setTimeout(() => {
    lastTap = null;
    if (String(getSelection() || '') !== '') { return; }
    setReading(!reading);
  }, viewer.doubleTap ? 320 : 0);
}

// Scrolling: down hides the bars, up brings them back; the page counter and fast-scroll handle show.
let lastTop = null;
let run = 0;
function watchScroll(el) {
  const target = el || window;
  target.addEventListener('scroll', () => {
    const top = (el || document.scrollingElement).scrollTop;
    if (lastTop !== null && !dragging) {
      const d = top - lastTop;
      if (d !== 0 && (d > 0) !== (run > 0)) { run = 0; }
      run += d;
      // As Swift's own toolbar does on websites: a finger's width down hides, a little less up shows.
      if (run > 64 && !reading && top > 60 && canHideBars()) { setReading(true, true); run = 0; }
      else if (run < -48 && reading && !viewer.holdReading) { setReading(false, true); run = 0; }
    }
    lastTop = top;
    showPill();
  }, { passive: true });
}

let pillTimer = 0;
let pillQueued = false;
function showPill() {
  if (pillQueued) { return; }
  pillQueued = true;
  requestAnimationFrame(() => { pillQueued = false; drawPill(); });
}
function drawPill() {
  const label = viewer.pageLabel ? viewer.pageLabel() : null;
  const pill = $('#pagepill');
  if (label) {
    if (pill.textContent !== label) { pill.textContent = label; }
    pill.hidden = false;
    pill.classList.remove('fade');
  }
  updateThumb();
  clearTimeout(pillTimer);
  pillTimer = setTimeout(() => {
    if (dragging) { return; }
    pill.classList.add('fade');
    $('#fastscroll').classList.add('fade');
  }, 1600);
}
$('#pagepill').addEventListener('click', () => { if (viewer.goTo) { viewer.goTo(); } });

// The fast-scroll handle: long documents, dragged along the right edge.
function scrollerEl() { return viewer.scroller || document.scrollingElement; }
const trackTop = () => (reading ? 12 : 64);
const trackRoom = () => Math.max(40, innerHeight - trackTop() - 48 - (document.body.classList.contains('sheet') && !reading ? 60 : 20));
let dragging = null;

function updateThumb() {
  const s = scrollerEl();
  const fs = $('#fastscroll');
  const room = s.scrollHeight - s.clientHeight;
  if (viewer.noFast || room < s.clientHeight * 3) { fs.hidden = true; return; }
  fs.hidden = false;
  fs.classList.remove('fade');
  if (!dragging) { $('#fastthumb').style.top = (trackTop() + (s.scrollTop / room) * trackRoom()) + 'px'; }
}

const thumbEl = $('#fastthumb');
thumbEl.addEventListener('touchstart', e => {
  e.preventDefault();
  dragging = { y: e.touches[0].clientY, top: parseFloat(thumbEl.style.top) || trackTop() };
}, { passive: false });
thumbEl.addEventListener('touchmove', e => {
  if (!dragging) { return; }
  e.preventDefault();
  const s = scrollerEl();
  const top = Math.min(Math.max(dragging.top + e.touches[0].clientY - dragging.y, trackTop()), trackTop() + trackRoom());
  thumbEl.style.top = top + 'px';
  s.scrollTop = ((top - trackTop()) / trackRoom()) * (s.scrollHeight - s.clientHeight);
}, { passive: false });
thumbEl.addEventListener('touchend', () => { dragging = null; showPill(); });

// ------------------------------------------------------------------ flowing documents

/** The elements read aloud and found in a document that flows top to bottom. */
function flowDone(box, selector) {
  ready();
  const place = Number(info.place);
  if (place > 0 && place < 1) {
    requestAnimationFrame(() => requestAnimationFrame(() => scrollTo(0, place * document.documentElement.scrollHeight)));
  }
  addEventListener('scroll', () => {
    const h = document.documentElement.scrollHeight;
    const room = h - innerHeight;
    savePlace((scrollY / Math.max(1, h)).toFixed(4), room > 0 ? (scrollY + innerHeight) / h : 1);
  }, { passive: true });
  watchScroll(null);
  viewer.pageLabel = () => {
    const room = document.documentElement.scrollHeight - innerHeight;
    return room > innerHeight ? Math.round(Math.min(1, scrollY / room) * 100) + '%' : null;
  };
  viewer.size = f => { box.style.zoom = f; };
  viewer.texts = () => {
    const els = Array.from(box.querySelectorAll(selector || 'p, li, h1, h2, h3, h4, h5, h6, pre'))
      .filter(e => e.textContent.trim() !== '' && !e.querySelector('p, li') && !e.closest('.notes'));
    return { texts: els.map(e => e.textContent.replace(/\s+/g, ' ').trim()), els, from: firstVisible(els) };
  };
}

function firstVisible(els) {
  const top = reading ? 0 : $('#bar').getBoundingClientRect().bottom;
  const i = els.findIndex(e => e.getBoundingClientRect().bottom > top + 4);
  return i < 0 ? 0 : i;
}

function paragraphs(list) {
  const box = document.createElement('div');
  box.className = 'flow';
  for (const item of list) {
    if (item.heading) {
      const h = document.createElement('h2');
      h.className = 'slide-title';
      h.textContent = item.heading;
      box.appendChild(h);
    }
    for (const t of item.lines || [item]) {
      if (typeof t !== 'string' || t.trim() === '') { continue; }
      const p = document.createElement('p');
      p.textContent = t;
      box.appendChild(p);
    }
  }
  if (!box.firstChild) {
    const p = document.createElement('p');
    p.textContent = 'There is no text in this file that Swift can show.';
    box.appendChild(p);
  }
  docEl.appendChild(box);
  flowDone(box, 'p, h2');
}

function decodeText(buf) {
  let s = new TextDecoder('utf-8').decode(buf);
  if ((s.slice(0, 20000).match(/�/g) || []).length > 10) { s = new TextDecoder('windows-1252').decode(buf); }
  return s;
}

const u16 = (a, o) => a[o] | (a[o + 1] << 8);
const u32 = (a, o) => (a[o] | (a[o + 1] << 8) | (a[o + 2] << 16) | (a[o + 3] << 24)) >>> 0;
const bytesOf = c => (c instanceof Uint8Array ? c : Uint8Array.from(c || []));

const RENDER = {};

// ------------------------------------------------------------------ Word (.docx)

let lastBuf = null;
let pageLayout = true;

RENDER.word = async buf => {
  lastBuf = buf;
  await loadScript('lib/jszip.min.js');
  await loadScript('lib/docx-preview.min.js');
  await renderWord();
};

let wordFlowSet = false;
async function renderWord() {
  document.body.classList.toggle('fit', !pageLayout);
  docEl.textContent = '';
  const box = document.createElement('div');
  docEl.appendChild(box);
  await docx.renderAsync(lastBuf, box, null, {
    className: 'docx',
    inWrapper: true,
    ignoreWidth: !pageLayout,
    ignoreHeight: !pageLayout,
    breakPages: true,
    ignoreLastRenderedPageBreak: true,
    experimental: true,
    useBase64URL: true,
    renderHeaders: pageLayout,
    renderFooters: pageLayout,
    renderFootnotes: true,
    renderEndnotes: true,
  });
  fixBullets();
  if (pageLayout) {
    // Real pages, shrunk to the screen's width (pinch to look closer).
    const page = box.querySelector('section.docx');
    if (page && page.offsetWidth > innerWidth) { box.style.zoom = ((innerWidth - 8) / page.offsetWidth).toFixed(3); }
  }
  if (!wordFlowSet) {
    wordFlowSet = true;
    flowDone(box);
  } else {
    // Switching layouts: the listeners stay, the box is new.
    viewer.size = f => { box.style.zoom = f; };
    const texts = viewer.texts;
    viewer.texts = () => {
      const els = Array.from(box.querySelectorAll('p, li, h1, h2, h3, h4, h5, h6, pre'))
        .filter(e => e.textContent.trim() !== '' && !e.querySelector('p, li'));
      return els.length ? { texts: els.map(e => e.textContent.replace(/\s+/g, ' ').trim()), els, from: firstVisible(els) } : texts();
    };
    ready();
  }
}

/**
 * Word's bullets are characters of Windows fonts (Symbol, Wingdings) that phones don't have, so they
 * showed as boxes: the usual ones become ordinary bullet characters.
 */
function fixBullets() {
  const map = { f0b7: '2022', f0a7: '25AA', f0a8: '25A1', f0d8: '27A2', f0fc: '2713', f076: '2756', f0e0: '27A2', f06e: '25A0', f0b2: '25E6' };
  for (const st of document.querySelectorAll('style')) {
    const css = st.textContent;
    const fixed = css.replace(/\\(f0[0-9a-f]{2})/gi, (m, code) => (map[code.toLowerCase()] ? '\\' + map[code.toLowerCase()] : '\\2022'))
      .replace(/[-]/g, ch => String.fromCharCode(parseInt(map[ch.charCodeAt(0).toString(16)] || '2022', 16)))
      .replace(/font-family:\s*(Symbol|Wingdings)[^;"]*/gi, 'font-family: sans-serif');
    if (fixed !== css) { st.textContent = fixed; }
  }
}

// ------------------------------------------------------------------ spreadsheets

// 3.0: spreadsheets (and OpenDocument files' text) are read by docs/work.js in a Worker: SheetJS
// works through a whole workbook in one go, and on this page that held up uBlock Origin, whose
// checks of every tab's requests run on the same thread. Where a Worker can't start or load its
// libraries, the same work runs here, as before.
let worker = null;
function startWorker() {
  if (worker) { return worker; }
  worker = new Promise(resolve => {
    let w;
    try { w = new Worker('work.js'); } catch (e) { resolve(null); return; }
    const calls = new Map();
    let next = 1;
    let up = false;
    const giveUp = () => {
      for (const c of calls.values()) { c.reject(new Error('The worker stopped')); }
      calls.clear();
      if (!up) { try { w.terminate(); } catch (e) { /* gone */ } resolve(null); }
    };
    const wait = setTimeout(giveUp, 10000);
    w.onmessage = e => {
      const m = e.data || {};
      if (m.ready) {
        up = true;
        clearTimeout(wait);
        resolve({
          call(op, args, transfer) {
            return new Promise((res, rej) => {
              const n = next++;
              calls.set(n, { resolve: res, reject: rej });
              w.postMessage({ n, op, args }, transfer || []);
            });
          },
        });
        return;
      }
      if (m.failed) { clearTimeout(wait); giveUp(); return; }
      const c = calls.get(m.n);
      if (!c) { return; }
      calls.delete(m.n);
      if (m.error) { c.reject(new Error(m.error)); } else { c.resolve(m.ok); }
    };
    w.onerror = e => {
      if (e && e.preventDefault) { e.preventDefault(); }
      clearTimeout(wait);
      giveUp();
    };
  });
  return worker;
}

/**
 * Runs [op] of docs/work.js with [args]: in the Worker, or on this page when there is none. [transfer]:
 * buffers handed over to the Worker (no copy; this page can't use them afterwards).
 */
async function work(op, args, transfer) {
  const w = await startWorker();
  if (w) { return w.call(op, args, transfer); }
  await loadScript('lib/xlsx.full.min.js');
  await loadScript('lib/jszip.min.js');
  await loadScript('work.js');
  return SwiftWork[op](...args);
}

// A very big sheet shows this many rows first, then more on request (each step reads it again; the
// rows handed over from the Worker are unpacked on this page, so not too many at once).
const SHEET_ROWS = [3000, 12000, 40000];

/** "B2:D40" as { s: { r, c }, e: { r, c } }, counted from 0 (as SheetJS's decode_range, which isn't loaded here). */
function decodeRange(ref) {
  const cell = a => {
    const m = /^\$?([A-Z]+)\$?(\d+)$/.exec(a || '');
    if (!m) { return null; }
    let c = 0;
    for (const ch of m[1]) { c = c * 26 + (ch.charCodeAt(0) - 64); }
    return { r: parseInt(m[2], 10) - 1, c: c - 1 };
  };
  const [a, b] = String(ref).split(':');
  const s = cell(a);
  const e = b ? cell(b) : s;
  return s && e ? { s, e } : null;
}

function lum(hex) {
  const n = parseInt(hex, 16);
  return (0.299 * ((n >> 16) & 255) + 0.587 * ((n >> 8) & 255) + 0.114 * (n & 255)) / 255;
}

RENDER.sheet = async buf => {
  // Only the sheet on screen is read (2.5): a big workbook opens as fast as its first sheet.
  // 3.0: in the Worker, which keeps the file (handed over, not copied) for the other sheets.
  const names = (await work('sheetOpen', [buf, info.name], [buf])).names || [];
  if (!names.length) { throw new Error('No sheets'); }
  document.body.classList.add('sheet');
  document.body.classList.toggle('onesheet', names.length < 2);
  const pane = document.createElement('div');
  pane.className = 'pane';
  docEl.appendChild(pane);
  viewer.scroller = pane;
  const tabs = $('#sheets');
  tabs.hidden = names.length < 2;
  const parsed = new Map();
  let current = Math.min(Math.max(0, parseInt(info.place, 10) || 0), names.length - 1);
  let s = null;
  const BATCH = 300;
  // How many of a very big sheet's rows each sheet shows (3.0: SHEET_ROWS, "Show more" for the next step).
  const step = new Map();
  viewer.amount = () => names.length + (names.length === 1 ? ' sheet' : ' sheets');

  // Found cells (see viewer.find below).
  let hits = [];
  let hitAt = -1;
  let lastQuery = '';
  let hitEl = null;
  function clearHit() {
    if (hitEl) { hitEl.classList.remove('hit'); }
    hitEl = null;
  }

  /** Sheet [i] as the Worker read it: its first rows (more of a big one after "Show more"). */
  async function sheetAt(i) {
    const rows = SHEET_ROWS[step.get(i) || 0];
    const key = i + ':' + rows;
    let ws = parsed.get(key);
    if (!ws) {
      ws = await work('sheetRead', [i, rows]);
      if (parsed.size >= 2) { parsed.delete(parsed.keys().next().value); }
      parsed.set(key, ws);
    }
    return ws;
  }

  const colName = n => {
    let t = '';
    for (n += 1; n > 0; n = Math.floor((n - 1) / 26)) { t = String.fromCharCode(65 + (n - 1) % 26) + t; }
    return t;
  };
  // 3.0: cells come from the Worker as { x: text, n: number, f: fill, b, i: bold, italic, c: colour }.
  const cellText = cell => (cell == null ? '' : cell.x);

  function cellEl(r, c) {
    const key = r * 16384 + c;
    if (s.covered.has(key)) { return null; }
    const cell = (s.data[r] || [])[c];
    const td = document.createElement('td');
    td.dataset.c = c;
    const text = cellText(cell);
    td.textContent = text;
    if (cell && cell.n) { td.className = 'num'; }
    const fill = cell && cell.f && /^[0-9A-Fa-f]{6}$/.test(cell.f) ? cell.f : null;
    if (fill) {
      td.style.background = '#' + fill;
      td.style.color = lum(fill) > 0.55 ? '#1c1d21' : '#f4f4f6';
    }
    if (cell && text !== '') {
      if (cell.b) { td.style.fontWeight = '700'; }
      if (cell.i) { td.style.fontStyle = 'italic'; }
      if (cell.c && /^[0-9A-Fa-f]{6}$/.test(cell.c)) { td.style.color = '#' + cell.c; }
    }
    const m = s.merges.get(key);
    if (m) {
      if (m.rs > 1) { td.rowSpan = m.rs; }
      if (m.cs > 1) { td.colSpan = m.cs; td.style.maxWidth = 'none'; }
    }
    // Pinned when it lies within the frozen columns (a merged cell reaching past them scrolls).
    if (c + (m ? m.cs : 1) - 1 < s.frozenCols) {
      td.classList.add('frozen');
      if (s.lefts.has(c)) { td.style.left = s.lefts.get(c) + 'px'; }
      if (c === s.frozenCols - 1) { td.classList.add('splitc'); }
    }
    return td;
  }

  function rowEl(r) {
    const tr = document.createElement('tr');
    const th = document.createElement('th');
    th.className = 'row';
    th.textContent = r + 1;
    tr.appendChild(th);
    for (const c of s.cols) {
      const td = cellEl(r, c);
      if (td) { tr.appendChild(td); }
    }
    const h = s.rowInfo[r];
    if (h && h.hpx > 30) { tr.style.height = Math.min(200, Math.round(h.hpx)) + 'px'; }
    s.rowEls.set(r, tr);
    return tr;
  }

  function more() {
    const end = Math.min(s.rows.length, s.shown + BATCH);
    const frag = document.createDocumentFragment();
    for (let k = s.shown; k < end; k++) { frag.appendChild(rowEl(s.rows[k])); }
    s.body.appendChild(frag);
    s.shown = end;
  }

  /** Frozen rows and columns stay in view while the rest scrolls under them. */
  function pin() {
    if (!s || !s.table) { return; }
    const heads = Array.from(s.head.rows);
    for (const tr of heads) { for (const cell of tr.cells) { cell.style.top = tr.offsetTop + 'px'; } }
    if (heads.length > 1) { heads[heads.length - 1].classList.add('split'); }
    if (s.frozenCols > 0) {
      s.lefts.clear();
      for (const th of heads[0].cells) { if (th.dataset.c) { s.lefts.set(parseInt(th.dataset.c, 10), th.offsetLeft); } }
      for (const el of s.table.querySelectorAll('td.frozen, th.frozen')) { el.style.left = (s.lefts.get(parseInt(el.dataset.c, 10)) || 0) + 'px'; }
    }
  }

  /** Shows sheet [i]; [keep]: rows drawn at once (as many as before "Show more"). */
  async function show(i, keep) {
    current = i;
    Array.from(tabs.children).forEach((b, j) => b.classList.toggle('on', j === i));
    clearHit();
    hits = [];
    let ws;
    try {
      ws = await sheetAt(i);
    } catch (e) {
      if (current !== i) { return; }
      pane.textContent = '';
      const p = document.createElement('p');
      p.className = 'flow';
      p.textContent = /password|encrypt/i.test(String(e && e.message)) ? PROTECTED : "Swift couldn't read this sheet.";
      pane.appendChild(p);
      s = null;
      return;
    }
    // Another sheet was chosen while this one was read.
    if (current !== i) { return; }
    pane.textContent = '';
    const range = ws.ref ? decodeRange(ws.ref) : null;
    const rowInfo = ws.rowInfo || [];
    const cols = ws.cols || [];
    const merges = new Map();
    const covered = new Set();
    for (const m of ws.merges || []) {
      merges.set(m.s.r * 16384 + m.s.c, { rs: m.e.r - m.s.r + 1, cs: m.e.c - m.s.c + 1 });
      for (let r = m.s.r; r <= m.e.r && covered.size < 300000; r++) {
        for (let c = m.s.c; c <= m.e.c; c++) { if (r !== m.s.r || c !== m.s.c) { covered.add(r * 16384 + c); } }
      }
    }
    const frozenRows = ws.frozenRows || 0;
    const rows = [];
    const frozen = [];
    if (range) {
      for (let r = range.s.r; r <= range.e.r; r++) {
        if (rowInfo[r] && rowInfo[r].hidden) { continue; }
        (r < frozenRows ? frozen : rows).push(r);
      }
    }
    s = {
      data: ws.data || [], cols, rows, rowInfo, merges, covered,
      frozenCols: ws.frozenCols || 0, lefts: new Map(), rowEls: new Map(), shown: 0, table: null, head: null, body: null,
    };
    if (!range || (rows.length === 0 && frozen.length === 0)) {
      const p = document.createElement('p');
      p.className = 'flow';
      p.textContent = 'This sheet is empty.';
      pane.appendChild(p);
      savePlace(i, 0, names.length);
      return;
    }
    const table = document.createElement('table');
    table.className = 'grid';
    table.style.zoom = scale;
    const head = document.createElement('thead');
    const htr = document.createElement('tr');
    const corner = document.createElement('th');
    corner.className = 'row corner';
    htr.appendChild(corner);
    for (const c of cols) {
      const th = document.createElement('th');
      th.textContent = colName(c);
      th.dataset.c = c;
      if (c < s.frozenCols) { th.className = 'frozen'; }
      // The column widths the sheet was made with.
      const w = (ws.widths || {})[c];
      if (w > 0) {
        const px = Math.max(36, Math.min(480, Math.round(w)));
        th.style.minWidth = th.style.maxWidth = th.style.width = px + 'px';
      }
      htr.appendChild(th);
    }
    head.appendChild(htr);
    table.appendChild(head);
    s.table = table;
    s.head = head;
    s.body = document.createElement('tbody');
    table.appendChild(s.body);
    pane.appendChild(table);
    for (const r of frozen) { head.appendChild(rowEl(r)); }
    more();
    // 3.0: the rows shown before "Show more" are drawn again at once, so the place stays.
    while (s.shown < Math.min(keep || 0, s.rows.length)) { more(); }
    if (ws.more) { pane.appendChild(sheetMore(i)); }
    requestAnimationFrame(pin);
    pane.scrollTo(0, 0);
    // Which sheet was open, for reopening; a sheet's number isn't how far it was read (2.6).
    savePlace(i, 0, names.length);
  }

  /** 3.0: under a very big sheet's first rows: how many are shown, and "Show more" for the next step. */
  function sheetMore(i) {
    const box = document.createElement('div');
    box.className = 'sheetmore';
    const k = step.get(i) || 0;
    const p = document.createElement('p');
    const shown = SHEET_ROWS[k].toLocaleString('en');
    box.appendChild(p);
    if (k + 1 >= SHEET_ROWS.length) {
      p.textContent = 'This sheet is very big: Swift shows its first ' + shown + ' rows. Open it with another app to see all of it.';
      return box;
    }
    p.textContent = 'This sheet is very big: Swift shows its first ' + shown + ' rows.';
    const b = document.createElement('button');
    b.className = 'showmore';
    b.textContent = 'Show more';
    b.addEventListener('click', async () => {
      b.disabled = true;
      b.textContent = 'Opening…';
      const top = pane.scrollTop;
      const had = s ? s.shown : 0;
      step.set(i, k + 1);
      await show(i, had);
      pane.scrollTop = top;
    });
    box.appendChild(b);
    return box;
  }

  names.forEach((name, i) => {
    const b = document.createElement('button');
    b.textContent = name;
    b.addEventListener('click', () => show(i));
    tabs.appendChild(b);
  });
  pane.addEventListener('scroll', () => {
    if (s && s.shown < s.rows.length && pane.scrollTop + pane.clientHeight > pane.scrollHeight - 800) { more(); }
  }, { passive: true });
  watchScroll(pane);
  await show(current);
  ready();
  viewer.size = f => {
    if (s && s.table) {
      s.table.style.zoom = f;
      requestAnimationFrame(pin);
    }
  };

  // Find in the whole sheet (2.5), not only the rows on screen.
  viewer.ownFind = true;
  viewer.find = (query, again, previous) => {
    const q = query.trim().toLowerCase();
    if (!s) { return; }
    if (!again || q !== lastQuery) {
      lastQuery = q;
      hits = [];
      hitAt = -1;
      if (q) {
        const all = Array.from(s.head ? s.head.rows : []).slice(1).map(tr => parseInt(tr.cells[0].textContent, 10) - 1).concat(s.rows);
        for (const r of all) {
          const row = s.data[r];
          if (!row) { continue; }
          for (const c of s.cols) {
            if (cellText(row[c]).toLowerCase().includes(q)) { hits.push([r, c]); }
          }
          if (hits.length >= 5000) { break; }
        }
      }
    }
    clearHit();
    if (!hits.length) {
      setFindCount(q ? 'None' : '');
      return;
    }
    hitAt = hitAt < 0 ? 0 : (hitAt + (previous ? -1 : 1) + hits.length) % hits.length;
    setFindCount((hitAt + 1) + '/' + hits.length);
    const [r, c] = hits[hitAt];
    const k = s.rows.indexOf(r);
    while (k >= 0 && s.shown <= k) { more(); }
    const tr = s.rowEls.get(r);
    const td = tr && tr.querySelector('td[data-c="' + c + '"]');
    if (td) {
      hitEl = td;
      td.classList.add('hit');
      td.scrollIntoView({ block: 'center', inline: 'center' });
    }
  };
  viewer.closeFind = clearHit;

  viewer.texts = () => {
    const batch = from => {
      const rows = s ? s.rows.slice(from, from + 300) : [];
      const texts = rows.map(r => s.cols.map(c => cellText((s.data[r] || [])[c])).filter(v => v !== '').join(', ')).filter(t => t);
      return { texts, els: null, from: 0, next: s && from + 300 < s.rows.length ? () => batch(from + 300) : null };
    };
    return batch(0);
  };
};

// ------------------------------------------------------------------ PowerPoint (.pptx)

/** The speaker notes of each slide, in order (pptx-preview doesn't show them). */
async function slideNotes(buf) {
  const zip = await JSZip.loadAsync(buf);
  const read = async p => { const f = zip.file(p); return f ? f.async('string') : ''; };
  const parse = s => new DOMParser().parseFromString(s, 'application/xml');
  const RNS = 'http://schemas.openxmlformats.org/officeDocument/2006/relationships';
  const ANS = 'http://schemas.openxmlformats.org/drawingml/2006/main';
  const relsOf = async path => {
    const dir = path.slice(0, path.lastIndexOf('/') + 1);
    const doc = parse(await read(dir + '_rels/' + path.slice(dir.length) + '.rels'));
    const out = {};
    for (const r of Array.from(doc.getElementsByTagName('Relationship'))) {
      out[r.getAttribute('Id')] = { type: r.getAttribute('Type') || '', target: resolve(dir, r.getAttribute('Target') || '') };
    }
    return out;
  };
  const resolve = (dir, target) => {
    if (target.startsWith('/')) { return target.slice(1); }
    const parts = (dir + target).split('/');
    const out = [];
    for (const p of parts) { if (p === '..') { out.pop(); } else if (p !== '.') { out.push(p); } }
    return out.join('/');
  };
  const pres = parse(await read('ppt/presentation.xml'));
  const presRels = await relsOf('ppt/presentation.xml');
  const notes = [];
  for (const sld of Array.from(pres.getElementsByTagNameNS('*', 'sldId'))) {
    const rel = presRels[sld.getAttributeNS(RNS, 'id')];
    const lines = [];
    if (rel) {
      const rels = await relsOf(rel.target);
      const n = Object.values(rels).find(x => /\/notesSlide$/.test(x.type));
      if (n) {
        const doc = parse(await read(n.target));
        for (const sp of Array.from(doc.getElementsByTagNameNS('*', 'sp'))) {
          const ph = sp.getElementsByTagNameNS('*', 'ph')[0];
          if (!ph || ph.getAttribute('type') !== 'body') { continue; }
          for (const p of Array.from(sp.getElementsByTagNameNS(ANS, 'p'))) {
            const t = Array.from(p.getElementsByTagNameNS(ANS, 't')).map(x => x.textContent).join('').trim();
            if (t) { lines.push(t); }
          }
        }
      }
    }
    notes.push(lines);
  }
  return notes;
}

RENDER.slides = async buf => {
  await loadScript('lib/pptx-preview.umd.js');
  const box = document.createElement('div');
  box.id = 'slides';
  docEl.appendChild(box);
  const width = Math.min(innerWidth - 12, 1100);
  const previewer = pptxPreview.init(box, { width, height: Math.round(width * 9 / 16), mode: 'list' });
  await previewer.preview(buf);
  let notes = [];
  try {
    await loadScript('lib/jszip.min.js');
    notes = await slideNotes(buf);
  } catch (e) {
    notes = [];
  }
  const slides = Array.from(box.querySelectorAll('.pptx-preview-slide-wrapper'));
  slides.forEach((slide, i) => {
    if (!notes[i] || !notes[i].length) { return; }
    const d = document.createElement('details');
    d.className = 'notes';
    const sum = document.createElement('summary');
    sum.textContent = 'Speaker notes';
    d.appendChild(sum);
    for (const line of notes[i]) {
      const p = document.createElement('p');
      p.textContent = line;
      d.appendChild(p);
    }
    slide.after(d);
  });
  flowDone(box, 'p, li');
  const firstSlide = () => {
    const top = reading ? 0 : $('#bar').getBoundingClientRect().bottom;
    const k = slides.findIndex(sl => sl.getBoundingClientRect().bottom > top + 40);
    return k < 0 ? slides.length - 1 : k;
  };
  if (slides.length > 1) { viewer.pageLabel = () => 'Slide ' + (firstSlide() + 1) + ' of ' + slides.length; }
  if (slides.length) { viewer.slideshow = () => slideshow(slides, notes, firstSlide()); }
  viewer.amount = () => slides.length + (slides.length === 1 ? ' slide' : ' slides');
};

/** The slideshow (2.5): one slide at a time, filling the screen; swipe, or tap the sides. */
function slideshow(slides, notes, at) {
  const show = document.createElement('div');
  show.id = 'show';
  const stage = document.createElement('div');
  stage.className = 'stage';
  show.appendChild(stage);
  const notesBox = document.createElement('div');
  notesBox.className = 'shownotes';
  notesBox.hidden = true;
  show.appendChild(notesBox);
  const bar = document.createElement('div');
  bar.className = 'bar';
  const button = (label, run, icon) => {
    const b = document.createElement('button');
    if (icon) {
      b.innerHTML = symbol(icon);
      b.setAttribute('aria-label', label);
    } else {
      b.textContent = label;
    }
    b.addEventListener('click', e => { e.stopPropagation(); run(); wake(); });
    bar.appendChild(b);
    return b;
  };
  button('Exit', close);
  button('Previous', () => go(at - 1), 'prev');
  const counter = document.createElement('span');
  bar.appendChild(counter);
  button('Next', () => go(at + 1), 'next');
  const notesButton = button('Notes', () => { notesOn = !notesOn; render(); });
  show.appendChild(bar);
  let notesOn = false;
  let barTimer = 0;
  function wake() {
    bar.classList.remove('fade');
    clearTimeout(barTimer);
    barTimer = setTimeout(() => bar.classList.add('fade'), 4000);
  }
  function render() {
    stage.textContent = '';
    const src = slides[at];
    const clone = src.cloneNode(true);
    clone.style.margin = '0';
    clone.style.boxShadow = 'none';
    stage.appendChild(clone);
    const w = src.offsetWidth || 1;
    const h = src.offsetHeight || 1;
    const k = Math.min(innerWidth / w, innerHeight / h);
    stage.style.width = w + 'px';
    stage.style.height = h + 'px';
    stage.style.transform = 'scale(' + k + ') translate(-50%, -50%)';
    counter.textContent = (at + 1) + ' / ' + slides.length;
    const lines = notes[at] || [];
    notesButton.hidden = notes.every(n => !n || !n.length);
    notesBox.hidden = !notesOn || !lines.length;
    notesBox.textContent = '';
    for (const l of lines) {
      const p = document.createElement('p');
      p.textContent = l;
      notesBox.appendChild(p);
    }
  }
  function go(i) {
    if (i < 0 || i >= slides.length) { return; }
    at = i;
    render();
    // A new slide comes in softly (not when it is only drawn again for a new screen size).
    if (stage.firstElementChild) { stage.firstElementChild.classList.add('turned'); }
  }
  function end() {
    removeEventListener('resize', render);
    show.remove();
    slides[at].scrollIntoView({ block: 'center' });
    setReading(false);
  }
  function close() {
    end();
    closed(end);
  }
  let t0 = null;
  show.addEventListener('touchstart', e => {
    const t = e.touches[0];
    t0 = e.touches.length === 1 ? { x: t.clientX, y: t.clientY, time: Date.now() } : null;
  }, { passive: true });
  show.addEventListener('touchend', e => {
    if (!t0 || e.target.closest('.bar, .shownotes')) { t0 = null; return; }
    const t = e.changedTouches[0];
    const dx = t.clientX - t0.x;
    const dy = t.clientY - t0.y;
    const quick = Date.now() - t0.time < 700;
    t0 = null;
    if (quick && Math.abs(dx) > 50 && Math.abs(dx) > Math.abs(dy)) { go(at + (dx < 0 ? 1 : -1)); return; }
    if (Math.abs(dx) < 12 && Math.abs(dy) < 12) {
      // Where the bar was: it comes back rather than the slide changing.
      if (bar.classList.contains('fade') && t.clientY > innerHeight - 80) { wake(); return; }
      if (t.clientX < innerWidth * 0.3) { go(at - 1); } else if (t.clientX > innerWidth * 0.7) { go(at + 1); } else { wake(); }
    }
  }, { passive: true });
  addEventListener('resize', render);
  document.body.appendChild(show);
  opened(end);
  setReading(true);
  render();
  wake();
}

// ------------------------------------------------------------------ e-books (.epub)

// 3.0: an e-book's locations (how far through it each place is) are kept per document in the
// extension's storage: working them out reads every chapter. The last 30 books; none for incognito.
const LOC_KEY = 'epub-loc:';
const LOC_LIST = 'epub-locs';

async function keptLocations(doc) {
  if (doc.incognito) { return null; }
  try {
    const r = await browser.storage.local.get(LOC_KEY + id);
    const k = r && r[LOC_KEY + id];
    return k && k.size === doc.size && typeof k.list === 'string' ? k.list : null;
  } catch (e) {
    return null;
  }
}

async function keepLocations(doc, list) {
  if (doc.incognito || typeof list !== 'string' || list.length > 2000000) { return; }
  try {
    const r = await browser.storage.local.get(LOC_LIST);
    const ids = ((r && r[LOC_LIST]) || []).filter(x => x !== id);
    ids.push(id);
    const gone = ids.splice(0, Math.max(0, ids.length - 30));
    await browser.storage.local.set({ [LOC_LIST]: ids, [LOC_KEY + id]: { size: doc.size, list, at: Date.now() } });
    if (gone.length) { await browser.storage.local.remove(gone.map(x => LOC_KEY + x)); }
  } catch (e) { /* not kept: worked out again next time */ }
}

const BOOK_STYLE = 'swift-book-style';
function bookStyle() {
  let st = null;
  try { st = JSON.parse(store(BOOK_STYLE) || 'null'); } catch (e) { st = null; }
  return Object.assign({ font: 'original', spacing: 1.6, layout: 'paged' }, st || {});
}

RENDER.epub = async buf => {
  await loadScript('lib/jszip.min.js');
  await loadScript('lib/epub.min.js');
  const book = ePub(buf);
  await book.ready;
  const area = document.createElement('div');
  area.id = 'book';
  docEl.appendChild(area);
  let style = bookStyle();
  let rendition = null;
  let where = info.place && info.place.startsWith('epubcfi') ? info.place : undefined;
  let section = 0;
  let paged = true;
  const foot = $('#bookfoot');

  function applyStyle() {
    const t = rendition.themes;
    const base = {
      img: { 'max-width': '100% !important', height: 'auto !important' },
      '.speaking': { background: 'rgba(255, 200, 0, 0.35) !important', 'border-radius': '4px' },
    };
    if (!paged) { base.body = { padding: '0 16px !important' }; }
    t.default(base);
    if (style.font !== 'original') { t.override('font-family', style.font === 'serif' ? 'serif' : 'sans-serif', true); }
    t.override('line-height', String(style.spacing), true);
    t.fontSize(Math.round(scale * 100) + '%');
  }

  function relocated(loc) {
    if (!loc || !loc.start) { return; }
    where = loc.start.cfi;
    section = loc.start.index || 0;
    const n = book.spine.length || 1;
    const pct = book.locations.length() ? book.locations.percentageFromCfi(loc.start.cfi) : section / n;
    savePlace(loc.start.cfi, pct, 0);
    const d = loc.start.displayed;
    let left = '';
    if (paged && d && d.total > 0) {
      const k = d.total - d.page;
      left = k <= 0 ? 'Last page of this chapter' : k === 1 ? '1 page left in this chapter' : k + ' pages left in this chapter';
    }
    $('#bookleft').textContent = left;
    $('#bookpct').textContent = Math.round(pct * 100) + '%';
    $('#bookdone').style.width = (pct * 100).toFixed(1) + '%';
  }

  // Turned after the touch is over (the page's frame is replaced when a chapter ends).
  function turn(forward) {
    setTimeout(() => { if (forward) { rendition.next(); } else { rendition.prev(); } }, 0);
  }

  function hookInput() {
    let t0 = null;
    const xOf = (e, t) => {
      const frame = e.view && e.view.frameElement;
      return (frame ? frame.getBoundingClientRect().left : 0) + t.clientX;
    };
    rendition.on('touchstart', e => {
      const t = e.touches && e.touches[0];
      t0 = t && e.touches.length === 1 ? { x: xOf(e, t), y: t.clientY, time: Date.now() } : null;
    });
    rendition.on('touchend', e => {
      const t = e.changedTouches && e.changedTouches[0];
      if (!t0 || !t) { t0 = null; return; }
      const x = xOf(e, t);
      const dx = x - t0.x;
      const dy = t.clientY - t0.y;
      const dt = Date.now() - t0.time;
      t0 = null;
      const sel = e.view && e.view.getSelection ? String(e.view.getSelection()) : '';
      if (sel) { return; }
      if (paged && Math.abs(dx) > 50 && Math.abs(dx) > Math.abs(dy) * 1.5 && dt < 800) {
        turn(dx < 0);
        return;
      }
      if (Math.abs(dx) > 12 || Math.abs(dy) > 12 || dt > 400) { return; }
      if (e.target && e.target.closest && e.target.closest('a')) { return; }
      if (!$('#menu').hidden || !$('#panel').hidden) { return; }
      if (paged && x < innerWidth * 0.28) { turn(false); } else if (paged && x > innerWidth * 0.72) { turn(true); } else { setReading(!reading); }
    });
  }

  async function make() {
    if (rendition) {
      try { rendition.destroy(); } catch (e) { /* gone */ }
      area.textContent = '';
    }
    paged = style.layout !== 'scrolled';
    area.className = paged ? 'pane paged' : 'pane scrolled';
    foot.hidden = false;
    rendition = book.renderTo(area, paged
      ? { manager: 'default', flow: 'paginated', spread: 'none', width: '100%', height: '100%', allowScriptedContent: false }
      : { manager: 'continuous', flow: 'scrolled', width: '100%', allowScriptedContent: false });
    applyStyle();
    rendition.on('relocated', relocated);
    hookInput();
    hookLinks();
    await rendition.display(where);
  }

  // 3.0: links to websites in a book did nothing (epub.js opens them in a new window, which its
  // frames may not open): a tap opens them in a new tab of Swift; e-mail and phone links go to the
  // phone's apps. Links within the book still turn to their page.
  function hookLinks() {
    rendition.hooks.content.register(contents => {
      contents.document.addEventListener('click', e => {
        const a = e.target && e.target.closest ? e.target.closest('a[href]') : null;
        const href = a ? (a.getAttribute('href') || '').trim() : '';
        if (!/^(https?:|mailto:|tel:)/i.test(href)) { return; }
        e.preventDefault();
        e.stopPropagation();
        action('link', { url: href });
      }, true);
    });
  }

  await make();
  ready();
  viewer.noFast = true;
  // Pages to turn: the bars wait until a tap in the middle.
  if (paged) {
    setReading(true);
    if (!store('swift-book-hint')) {
      store('swift-book-hint', '1');
      toast('Tap the sides or swipe to turn pages. Tap the middle for the menu.', 5000);
    }
  }
  // How far through the book, for the home page and the bar: worked out when the phone has a moment.
  // 3.0: once per book, then kept (it reads every chapter, on the thread uBlock Origin shares).
  setTimeout(async () => {
    try {
      const kept = await keptLocations(info);
      if (kept) {
        book.locations.load(kept);
      } else {
        await book.locations.generate(1600);
        keepLocations(info, book.locations.save());
      }
      const loc = rendition.currentLocation();
      if (loc && loc.start) { relocated(loc); }
    } catch (e) { /* the place shows by chapter instead */ }
  }, 4000);
  viewer.size = f => rendition.themes.fontSize(Math.round(f * 100) + '%');
  viewer.chapters = () => book.navigation.toc.map(t => ({ label: t.label.trim(), go: () => rendition.display(t.href) }));
  viewer.chaptersTitle = 'Chapters';
  viewer.style = () => stylePanel(style, async st => {
    style = st;
    store(BOOK_STYLE, JSON.stringify(st));
    await make();
  });

  // Read aloud: this chapter from the page on screen, then on through the next ones.
  const chapterTexts = (fromHere) => {
    const contents = rendition.getContents()[0];
    if (!contents) { return { texts: [], els: [] }; }
    const els = Array.from(contents.document.querySelectorAll('p, li, h1, h2, h3, h4, h5, h6, blockquote'))
      .filter(e => e.textContent.trim() !== '' && !e.querySelector('p, li'));
    let from = 0;
    if (fromHere && where) {
      try {
        const range = rendition.getRange(where);
        const node = range && range.startContainer;
        if (node) {
          const k = els.findIndex(e => e.contains(node) || (e.compareDocumentPosition(node) & Node.DOCUMENT_POSITION_PRECEDING));
          from = k < 0 ? 0 : k;
        }
      } catch (e) { from = 0; }
    }
    const here = section;
    return {
      texts: els.map(e => e.textContent.replace(/\s+/g, ' ').trim()),
      els,
      from,
      mark: i => {
        const el = els[i];
        if (!el) { return; }
        if (marked) { marked.classList.remove('speaking'); }
        marked = el;
        el.classList.add('speaking');
        try { rendition.display(contents.cfiFromNode(el)); } catch (e) { /* stays */ }
      },
      next: async () => {
        const sec = book.spine.get(here + 1);
        if (!sec) { return null; }
        await rendition.display(sec.href);
        return chapterTexts(false);
      },
    };
  };
  viewer.texts = () => chapterTexts(true);
};

/** The e-book's look: font, line spacing, pages or scrolling. */
function stylePanel(style, apply) {
  const panel = $('#panel');
  panel.textContent = '';
  const st = Object.assign({}, style);
  const group = (title, options, key) => {
    const h = document.createElement('h3');
    h.textContent = title;
    panel.appendChild(h);
    const row = document.createElement('div');
    row.className = 'row';
    for (const [label, value] of options) {
      const b = document.createElement('button');
      b.className = 'chip' + (st[key] === value ? ' on' : '');
      b.textContent = label;
      b.addEventListener('click', () => {
        st[key] = value;
        Array.from(row.children).forEach(x => x.classList.toggle('on', x === b));
      });
      row.appendChild(b);
    }
    panel.appendChild(row);
  };
  group('Font', [['As in the book', 'original'], ['Serif', 'serif'], ['Sans serif', 'sans']], 'font');
  group('Line spacing', [['Close', 1.35], ['Normal', 1.6], ['Wide', 1.9], ['Widest', 2.2]], 'spacing');
  group('Turning', [['Pages', 'paged'], ['Scrolling', 'scrolled']], 'layout');
  const done = document.createElement('button');
  done.className = 'done';
  done.textContent = 'Done';
  const panelOverlay = () => hide(panel);
  done.addEventListener('click', () => {
    hide(panel);
    closed(panelOverlay);
    if (JSON.stringify(st) !== JSON.stringify(style)) { apply(st); }
  });
  panel.appendChild(done);
  panel.hidden = false;
  opened(panelOverlay);
}

// ------------------------------------------------------------------ text files

RENDER.text = async buf => {
  let s = decodeText(buf);
  if (/\.json$/i.test(info.name) && s.length < 3000000) {
    try { s = JSON.stringify(JSON.parse(s), null, 2); } catch (e) { /* shown as it is */ }
  }
  const box = document.createElement('div');
  docEl.appendChild(box);
  const LIMIT = 300000;
  let at = 0;
  const add = () => {
    const pre = document.createElement('pre');
    pre.className = 'text';
    pre.textContent = s.slice(at, at + LIMIT);
    box.insertBefore(pre, button);
    at += LIMIT;
    button.hidden = at >= s.length;
  };
  const button = document.createElement('button');
  button.className = 'showmore';
  button.textContent = 'Show more';
  button.addEventListener('click', add);
  box.appendChild(button);
  add();
  flowDone(box);
  viewer.texts = () => {
    const batch = from => {
      const texts = s.slice(from, from + 200000).split(/\n\s*\n/).map(t => t.replace(/\s+/g, ' ').trim()).filter(t => t);
      return { texts, els: null, from: 0, next: from + 200000 < s.length ? () => batch(from + 200000) : null };
    };
    return batch(0);
  };
};

// ------------------------------------------------------------------ old Office files, as made (2.8)

// Swift turns an old Word file into its pages (fonts, colours, tables, pictures) and an old
// PowerPoint file into its slides' text and pictures, on the phone. When that fails, the text alone
// (below) is still shown.
let convertWait = null;
let convertParts = [];

// 3.0: how long Swift may take to lay out an old file before the page shows its text instead (a
// damaged file can keep Swift's converter busy for ever). Each piece that arrives waits again.
const CONVERT_WAIT = 45000;
let convertTimer = 0;

function onConverted(msg) {
  const w = convertWait;
  if (!w) { return; }
  if (msg.error) {
    convertWait = null;
    clearTimeout(convertTimer);
    // 3.0: "format": the file is really another kind, which [looksLike] names (rtf, docx, html…).
    const e = new Error(msg.error);
    e.looksLike = msg.looksLike || null;
    w.reject(e);
    return;
  }
  convertParts.push(msg.data || '');
  if (msg.last) {
    convertWait = null;
    clearTimeout(convertTimer);
    const html = convertParts.join('');
    convertParts = [];
    w.resolve({ html, note: msg.note || null });
  } else {
    waitForConversion();
  }
}

function waitForConversion() {
  clearTimeout(convertTimer);
  convertTimer = setTimeout(() => {
    const w = convertWait;
    if (!w) { return; }
    convertWait = null;
    convertParts = [];
    w.reject(new Error('timeout'));
  }, CONVERT_WAIT);
}

function converted() {
  return new Promise((resolve, reject) => {
    convertParts = [];
    convertWait = { resolve, reject };
    waitForConversion();
    send({ type: 'doc-convert', id });
  });
}

/** 3.0: why an old file shows its text alone when laying it out didn't work in time. */
function slowNote(what) {
  note('Swift took too long to lay out this ' + what + ' file, so it shows only its text.', 'Open with another app', () => action('open-with'));
}

/**
 * 3.0: a file named like an old Word or PowerPoint file that is really another kind (Swift's
 * converter said which): shown as what it is. False when Swift has nothing better than the text.
 */
async function showAs(kind, buf) {
  if (kind === 'rtf') { await RENDER.rtf(buf); return true; }
  if (kind === 'docx') { await RENDER.word(buf); return true; }
  if (kind === 'pptx') { await RENDER.slides(buf); return true; }
  if (kind === 'xlsx' || kind === 'xls') { await RENDER.sheet(buf); return true; }
  if (kind === 'html') { await RENDER.html(buf); return true; }
  if (kind === 'pdf') { await openPdf(buf); return true; }
  return false;
}

/** An Office 97–2003 file starts with D0 CF 11 E0 (its "compound file" container). */
function compound(buf) {
  const a = new Uint8Array(buf, 0, Math.min(4, buf.byteLength));
  return a.length === 4 && a[0] === 0xD0 && a[1] === 0xCF && a[2] === 0x11 && a[3] === 0xE0;
}

/**
 * 3.0: what the converter says a file named like an old [what] file really is: shown as that, or,
 * when it isn't an old Office file at all, as plain text. False: read its text as an old file.
 */
async function otherKind(kind, buf, what) {
  if (await showAs(kind, buf)) { return true; }
  if (compound(buf)) { return false; }
  note('This file isn\'t really an old ' + what + ' file, so Swift shows it as plain text.', 'Open with another app', () => action('open-with'));
  await RENDER.text(buf);
  return true;
}

async function showConverted() {
  const r = await converted();
  docEl.textContent = '';
  const box = document.createElement('div');
  box.className = 'oo-wrap';
  // Made by Swift's own converter, which escapes the file's text; pictures are data: links.
  box.innerHTML = r.html;
  docEl.appendChild(box);
  if (r.note) { note(r.note, 'Open with another app', () => action('open-with')); }
  flowDone(box);
  // Pages and slides keep their size, shrunk to the screen's width (+ and − from there).
  const widest = Math.max(0, ...Array.from(box.querySelectorAll('.oo-page, .oo-slide')).map(e => e.offsetWidth || 0));
  const fit = widest > innerWidth ? (innerWidth - 8) / widest : 1;
  box.style.zoom = fit.toFixed(3);
  viewer.size = f => { box.style.zoom = (fit * f).toFixed(3); };
}

// ------------------------------------------------------------------ old Office files: their text

function oldNote(what) {
  note('This is an old ' + what + ' file (before 2007): Swift shows only its text.', 'Open with another app', () => action('open-with'));
}

RENDER.oldword = async buf => {
  // 3.0: Swift's converter first: it also tells a file that is really another kind (RTF, a web
  // page, a newer Word file), which is then shown as that; after 45 seconds, the text alone.
  let slow = false;
  try {
    await showConverted();
    return;
  } catch (e) {
    if (e && e.message === 'password') { throw e; }
    if (e && e.message === 'format' && await otherKind(e.looksLike, buf, 'Word')) { return; }
    slow = !!(e && e.message === 'timeout');
  }
  await loadScript('lib/xlsx.full.min.js');
  const cfb = XLSX.CFB.read(new Uint8Array(buf), { type: 'array' });
  const wd = XLSX.CFB.find(cfb, 'WordDocument');
  if (!wd) { throw new Error('Not a Word file'); }
  const content = bytesOf(wd.content);
  // fEncrypted: the text can't be read without the password.
  if (u16(content, 0x0A) & 0x0100) { throw new Error('password'); }
  let text;
  try { text = wordText(cfb, content); } catch (e) { text = looseText(content); }
  if (slow) { slowNote('Word'); } else { oldNote('Word'); }
  paragraphs(wordParagraphs(text));
};

/** The text of a Word 97–2003 file, from its piece table (MS-DOC). */
function wordText(cfb, wd) {
  const flags = u16(wd, 0x0A);
  const table = XLSX.CFB.find(cfb, (flags & 0x0200) ? '1Table' : '0Table');
  const fcClx = u32(wd, 0x01A2);
  const lcbClx = u32(wd, 0x01A6);
  const ccpText = u32(wd, 0x004C);
  if (!table || !lcbClx) { throw new Error('No piece table'); }
  const clx = bytesOf(table.content).subarray(fcClx, fcClx + lcbClx);
  let i = 0;
  while (clx[i] === 0x01) { i += 3 + u16(clx, i + 1); }
  if (clx[i] !== 0x02) { throw new Error('Bad piece table'); }
  const lcb = u32(clx, i + 1);
  i += 5;
  // 3.0: as many pieces as the table really holds (a damaged length said hundreds of millions):
  // each takes 12 bytes, 4 for where it starts and 8 for where its text is.
  const n = Math.floor((Math.min(lcb, clx.length - i) - 4) / 12);
  if (!(n > 0)) { throw new Error('Bad piece table'); }
  const cps = [];
  for (let k = 0; k <= n; k++) { cps.push(u32(clx, i + 4 * k)); }
  const pcd = i + 4 * (n + 1);
  const parts = [];
  let got = 0;
  for (let k = 0; k < n && got < WORD_TEXT_MAX; k++) {
    const raw = u32(clx, pcd + 8 * k + 2);
    // 3.0: no piece longer than the text left to take, nor past the end of the file.
    const len = Math.min(cps[k + 1] - cps[k], WORD_TEXT_MAX - got);
    if (!(len > 0)) { continue; }
    let piece;
    if (raw & 0x40000000) {
      const fc = (raw & 0x3FFFFFFF) / 2;
      piece = latin1(wd.subarray(fc, Math.min(wd.length, fc + len)));
    } else {
      const fc = raw & 0x3FFFFFFF;
      const end = Math.min(wd.length, fc + 2 * len);
      piece = fc < end ? new TextDecoder('utf-16le').decode(wd.subarray(fc, end - ((end - fc) % 2))) : '';
    }
    parts.push(piece);
    got += piece.length;
  }
  const out = parts.join('');
  return ccpText > 0 && ccpText <= out.length ? out.slice(0, ccpText) : out;
}

// The most text read from an old Word file (5 million characters, some 2,500 pages).
const WORD_TEXT_MAX = 5000000;

/** Bytes as the characters 0–255 (an old Word file's "compressed" text), a slice at a time. */
function latin1(bytes) {
  let s = '';
  for (let i = 0; i < bytes.length; i += 8192) { s += String.fromCharCode.apply(null, bytes.subarray(i, i + 8192)); }
  return s;
}

/** When the piece table can't be read: runs of readable text. */
function looseText(a) {
  const runs = [];
  let cur = '';
  for (let i = 0; i + 1 < a.length; i += 2) {
    const c = u16(a, i);
    if ((c >= 32 && c < 0xD800) || c === 13) { cur += String.fromCharCode(c); } else {
      if (cur.replace(/[^A-Za-zÀ-￿]/g, '').length > 20) { runs.push(cur); }
      cur = '';
    }
  }
  if (cur.length > 20) { runs.push(cur); }
  return runs.join('\r');
}

function wordParagraphs(text) {
  // Fields: keep what they show (between \x14 and \x15), drop their codes (\x13 … \x14).
  text = text.replace(/\x13[^\x13\x14\x15]*\x14/g, '').replace(/\x13[^\x13\x14\x15]*\x15/g, '').replace(/[\x14\x15]/g, '');
  return text.split(/[\r\x07\x0C]/).map(t => t.replace(/[\x00-\x08\x0B\x0E-\x1F]/g, ' ').replace(/\s+/g, ' ').trim()).filter(t => t);
}

RENDER.oldslides = async buf => {
  let slow = false;
  try {
    await showConverted();
    return;
  } catch (e) {
    if (e && e.message === 'password') { throw e; }
    // 3.0: as for Word files above.
    if (e && e.message === 'format' && await otherKind(e.looksLike, buf, 'PowerPoint')) { return; }
    slow = !!(e && e.message === 'timeout');
  }
  await loadScript('lib/xlsx.full.min.js');
  const cfb = XLSX.CFB.read(new Uint8Array(buf), { type: 'array' });
  const f = XLSX.CFB.find(cfb, 'PowerPoint Document');
  if (!f) { throw new Error('Not a PowerPoint file'); }
  const s = bytesOf(f.content);
  // Slides' text is in the slide list (one group per slide) and in each slide's text boxes; the
  // master slide's placeholders ("Click to edit…") and the notes are left out.
  const listed = [];
  const boxed = [];
  let listCur = null;
  let boxCur = null;
  const stack = [];
  const inside = type => stack.some(c => c.type === type);
  let pos = 0;
  // 3.0: no more records, nor text, than a real file has (a damaged length was read as up to 4 GB).
  let records = 0;
  let textLeft = 5000000;
  while (pos + 8 <= s.length && records++ < 3000000 && textLeft > 0) {
    while (stack.length && pos >= stack[stack.length - 1].end) { stack.pop(); }
    const verInst = u16(s, pos);
    const type = u16(s, pos + 2);
    const len = u32(s, pos + 4);
    if ((verInst & 0xF) === 0xF) {
      if (stack.length < 64) { stack.push({ type, inst: verInst >> 4, end: pos + 8 + len }); }
      if (type === 0x03EE) { boxCur = []; boxed.push(boxCur); }
      pos += 8;
      continue;
    }
    const list = stack.find(c => c.type === 0x0FF0);
    if (type === 0x03F3 && list && list.inst === 0) {
      listCur = [];
      listed.push(listCur);
    } else if ((type === 0x0FA0 || type === 0x0FA8) && !inside(0x03F8) && !inside(0x03F0) && !inside(0x0FC9)) {
      const wide = type === 0x0FA0;
      const from = pos + 8;
      const to = Math.min(s.length, from + len, from + (wide ? 2 : 1) * textLeft);
      const text = wide ? new TextDecoder('utf-16le').decode(s.subarray(from, to - ((to - from) % 2))) : latin1(s.subarray(from, to));
      textLeft -= text.length;
      const lines = text.split(/[\r\x0B]/).filter(l => l.trim() && l.trim() !== '*');
      const into = list ? (list.inst === 0 ? listCur : null) : (inside(0x03EE) ? boxCur : null);
      if (into) { for (const l of lines) { into.push(l); } }
    }
    pos += 8 + len;
  }
  const slides = [];
  for (let i = 0; i < Math.max(listed.length, boxed.length); i++) {
    const lines = new Set();
    for (const l of (listed[i] || []).concat(boxed[i] || [])) { lines.add(l); }
    if (lines.size) { slides.push(Array.from(lines)); }
  }
  if (slow) { slowNote('PowerPoint'); } else { oldNote('PowerPoint'); }
  paragraphs(slides.map((lines, i) => ({ heading: 'Slide ' + (i + 1), lines })));
};

// ------------------------------------------------------------------ OpenDocument and RTF: their text

async function openDocumentXml(buf) {
  // 3.0: unpacked in the Worker (a big document's text is tens of megabytes).
  const xml = await work('zipText', [buf, 'content.xml'], [buf]);
  if (xml == null) { throw new Error('encrypted or not an OpenDocument file'); }
  return new DOMParser().parseFromString(xml, 'application/xml');
}

const TEXT_NS = 'urn:oasis:names:tc:opendocument:xmlns:text:1.0';
const DRAW_NS = 'urn:oasis:names:tc:opendocument:xmlns:drawing:1.0';

RENDER.odt = async buf => {
  const doc = await openDocumentXml(buf);
  const out = [];
  const walk = n => {
    for (let c = n.firstElementChild; c; c = c.nextElementSibling) {
      if (c.namespaceURI === TEXT_NS && (c.localName === 'p' || c.localName === 'h')) { out.push(c.textContent); } else { walk(c); }
    }
  };
  walk(doc.documentElement);
  paragraphs(out);
};

RENDER.odp = async buf => {
  const doc = await openDocumentXml(buf);
  const pages = Array.from(doc.getElementsByTagNameNS(DRAW_NS, 'page'));
  paragraphs(pages.map((pg, i) => ({
    heading: pg.getAttributeNS(DRAW_NS, 'name') || 'Slide ' + (i + 1),
    lines: Array.from(pg.getElementsByTagNameNS(TEXT_NS, 'p')).map(p => p.textContent),
  })));
};

RENDER.rtf = async buf => {
  paragraphs(rtfText(new TextDecoder('windows-1252').decode(buf)).split('\n'));
};

/**
 * 3.0: a web page saved as a Word file (Word's "Web Page" .doc): its text, paragraph by paragraph.
 * Read as data only: nothing in it runs or loads.
 */
RENDER.html = async buf => {
  const doc = new DOMParser().parseFromString(decodeText(buf), 'text/html');
  doc.querySelectorAll('script, style, noscript, template, head, object, iframe').forEach(e => e.remove());
  const BLOCKS = 'h1, h2, h3, h4, h5, h6, p, li, td, th, pre, blockquote, dt, dd, div';
  const out = [];
  for (const el of doc.body ? doc.body.querySelectorAll(BLOCKS) : []) {
    if (el.querySelector(BLOCKS)) { continue; }
    out.push(el.textContent);
  }
  if (!out.length && doc.body) { out.push(...doc.body.textContent.split(/\n+/).slice(0, 100000)); }
  paragraphs(out);
};

/** The text of an RTF file: groups that aren't text (fonts, colours, pictures) are skipped. */
function rtfText(s) {
  const SKIP = /^(fonttbl|colortbl|stylesheet|info|pict|object|header|footer|themedata|colorschememapping|datastore|latentstyles|listtable|listoverridetable|rsidtbl|generator|xmlnstbl|mmathPr)$/;
  let out = '';
  let depth = 0;
  let skipFrom = -1;
  for (let i = 0; i < s.length; i++) {
    const c = s[i];
    if (c === '{') { depth++; continue; }
    if (c === '}') { if (skipFrom === depth) { skipFrom = -1; } depth--; continue; }
    if (c === '\\') {
      const m = /^\\([a-z]+)(-?\d+)? ?|^\\'([0-9a-f]{2})|^\\([^a-z])/i.exec(s.slice(i, i + 40));
      if (!m) { continue; }
      i += m[0].length - 1;
      if (skipFrom >= 0) { continue; }
      if (m[4] === '*') { skipFrom = depth; continue; }
      if (m[3]) { out += String.fromCharCode(parseInt(m[3], 16)); continue; }
      if (m[4]) { if (m[4] === '~') { out += ' '; } else if (m[4] !== '\n' && m[4] !== '\r') { out += m[4]; } continue; }
      const w = m[1];
      if (SKIP.test(w)) { skipFrom = depth; } else if (w === 'par' || w === 'line' || w === 'row') { out += '\n'; } else if (w === 'tab' || w === 'cell') { out += '\t'; }
      continue;
    }
    if (skipFrom >= 0 || c === '\r' || c === '\n') { continue; }
    out += c;
  }
  return out;
}

// ------------------------------------------------------------------ ZIP files (2.5)

// Swift reads the ZIP (it can be large) and lists it; a document inside opens in its own tab.
let zipWait = null;
RENDER.zip = async () => {
  const list = await new Promise((resolve, reject) => {
    zipWait = resolve;
    send({ type: 'doc-zip', id });
    setTimeout(() => reject(new Error('No answer')), 60000);
  });
  if (list.error) {
    // 3.0: a ZIP locked with a password isn't "damaged": Files opens it with its password (a ZIP
    // that isn't a file on the phone yet is saved first).
    const actions = !list.locked ? [] : info.copy ? [{ label: 'Save a copy', action: 'save-copy' }] : [{ label: 'Open in Files', action: 'files' }];
    problem(list.error, actions.concat([{ label: 'Open with another app', action: 'open-with' }]));
    return;
  }
  const box = document.createElement('div');
  box.className = 'ziplist';
  const dirOf = n => (n.lastIndexOf('/') < 0 ? '' : n.slice(0, n.lastIndexOf('/')));
  const entries = (list.entries || []).filter(e => !e.name.endsWith('/'))
    .sort((a, b) => dirOf(a.name).localeCompare(dirOf(b.name)) || a.name.localeCompare(b.name));
  let folder = null;
  for (const e of entries) {
    const slash = e.name.lastIndexOf('/');
    const dir = slash < 0 ? '' : e.name.slice(0, slash);
    if (dir !== folder) {
      folder = dir;
      if (dir) {
        const h = document.createElement('div');
        h.className = 'folder';
        h.textContent = dir;
        box.appendChild(h);
      }
    }
    const b = document.createElement('button');
    const k = e.kind ? KINDS[e.kind] : null;
    if (!k) { b.className = 'off'; }
    const badge = document.createElement('span');
    badge.className = 'zbadge';
    badge.textContent = k ? k[0] : (e.name.split('.').pop() || '').slice(0, 4).toUpperCase() || 'FILE';
    badge.style.background = k ? k[1] : '#80868b';
    const name = document.createElement('span');
    name.className = 'zname';
    name.textContent = e.name.slice(slash + 1);
    const size = document.createElement('span');
    size.className = 'zsize';
    size.textContent = e.size >= 0 ? formatSize(e.size) : '';
    b.append(badge, name, size);
    if (e.locked) { b.classList.add('off'); }
    b.addEventListener('click', () => {
      if (e.locked) { toast('This file is locked with a password. Open the ZIP in Files to take it out with its password.', 5000); return; }
      if (!k) { toast("Swift can't show this kind of file."); return; }
      toast('Opening ' + name.textContent + '…');
      action('open-entry', { entry: e.name });
    });
    box.appendChild(b);
  }
  if (!entries.length) {
    const p = document.createElement('p');
    p.className = 'flow';
    p.textContent = 'This ZIP file is empty.';
    box.appendChild(p);
  }
  if (list.truncated) { note('This ZIP file holds more files than Swift lists here; the first ' + entries.length + ' are shown.'); }
  docEl.appendChild(box);
  ready();
  watchScroll(null);
};

// ------------------------------------------------------------------ PDF (pdf.js)

/** [given]: the file already read (3.0: a PDF named like an old Word file); else pdf.js asks for its pieces. */
async function openPdf(given) {
  const lib = await import('./lib/pdf.min.mjs');
  globalThis.pdfjsLib = lib;
  lib.GlobalWorkerOptions.workerSrc = 'lib/pdf.worker.min.mjs';
  const ui = await import('./lib/pdf_viewer.mjs');
  addCss('lib/pdf_viewer.css');
  const pane = document.createElement('div');
  pane.id = 'pdfpane';
  pane.className = 'pane';
  const inner = document.createElement('div');
  inner.className = 'pdfViewer';
  pane.appendChild(inner);
  docEl.appendChild(pane);
  const eventBus = new ui.EventBus();
  const linkService = new ui.PDFLinkService({ eventBus });
  const findController = new ui.PDFFindController({ eventBus, linkService });
  const pdfViewer = new ui.PDFViewer({
    container: pane,
    viewer: inner,
    eventBus,
    linkService,
    findController,
    removePageBorders: true,
    // Forms can be filled in, and pages marked (Fill & sign).
    annotationEditorMode: lib.AnnotationEditorType.NONE,
    // Pages drawn sharp up to about 6 megapixels each: enough when zoomed in, light on memory.
    maxCanvasPixels: 6 * 1024 * 1024,
    // 2.8: the pages keep their own colours, in dark mode too.
  });
  linkService.setViewer(pdfViewer);
  const params = {
    cMapUrl: 'lib/cmaps/',
    cMapPacked: true,
    standardFontDataUrl: 'lib/standard_fonts/',
    // 3.0: JPEG 2000, JBIG2 and fax pictures (scanned pages) are decoded by pdf.js's WebAssembly
    // decoders in lib/wasm (without them they stayed blank). A full address: pdf.js's worker loads
    // their fallback from it, and relative to itself it would look in the wrong folder.
    wasmUrl: new URL('lib/wasm/', location.href).href,
    isEvalSupported: false,
    disableAutoFetch: true,
    disableStream: true,
    rangeChunkSize: 196608,
  };
  if (given) {
    params.data = new Uint8Array(given);
  } else if (info.size > 0 && info.ranges) {
    // Only the pieces needed for the pages on screen: a big PDF opens quickly and stays light.
    pdfRange = new lib.PDFDataRangeTransport(info.size, null);
    pdfRange.requestDataRange = (begin, end) => {
      // pdf.js only accepts pieces that end on its own chunk size (or at the end of the file).
      const piece = Math.floor(4194304 / params.rangeChunkSize) * params.rangeChunkSize;
      for (let at = begin; at < end; at += piece) {
        const req = nextReq++;
        askedAt.set(req, at);
        send({ type: 'doc-range', id, req, begin: at, end: Math.min(end, at + piece) });
      }
    };
    params.range = pdfRange;
    params.length = info.size;
  } else {
    params.data = new Uint8Array(await readAll());
  }
  const task = lib.getDocument(params);
  // 3.0: the password is asked for in the page (prompt() answered nothing on the viewer's own screen,
  // so these PDFs couldn't be opened). pdf.js asks again when it was wrong.
  let pwBox = null;
  let pwUpdate = null;
  const askPassword = wrong => {
    if (pwBox && pwBox.open) {
      if (wrong) { pwBox.wrong('That password isn\'t right. Try again.'); }
      return;
    }
    pwBox = askFor({
      text: 'This PDF is locked with a password. Type its password to open it.',
      type: 'password',
      placeholder: 'Password',
      ok: 'Open',
      showable: true,
      check: pw => {
        if (!pw) { return 'Type the password first.'; }
        pwBox.wait();
        status('Opening…');
        pwUpdate(pw);
        return null;
      },
      cancel: () => problem('This PDF is locked with a password. Swift can open it once you type its password.', [
        { label: 'Type the password', run: () => askPassword(false) },
        { label: 'Open with another app', action: 'open-with' },
      ]),
    });
    if (wrong) { pwBox.wrong('That password isn\'t right. Try again.'); }
  };
  task.onPassword = (update, reason) => {
    pwUpdate = update;
    askPassword(reason === lib.PasswordResponses.INCORRECT_PASSWORD);
  };
  const pdf = await task.promise;
  if (pwBox && pwBox.open) { pwBox.close(); }
  pdfViewer.setDocument(pdf);
  linkService.setDocument(pdf, null);
  let fit = 1;
  eventBus.on('pagesinit', () => {
    pdfViewer.currentScaleValue = 'page-width';
    fit = pdfViewer.currentScale;
    const page = parseInt(info.place, 10);
    if (page > 1) { pdfViewer.currentPageNumber = Math.min(page, pdf.numPages); }
    ready();
    if (info.thumb) { setTimeout(() => sendThumb(pdf), 2500); }
  });
  eventBus.on('pagechanging', e => {
    savePlace(e.pageNumber, pdf.numPages > 1 ? e.pageNumber / pdf.numPages : 1, pdf.numPages);
  });
  const counted = e => {
    const m = e.matchesCount;
    setFindCount(m && m.total ? (m.current ? m.current + '/' : '') + m.total : '');
  };
  eventBus.on('updatefindmatchescount', counted);
  eventBus.on('updatefindcontrolstate', counted);

  viewer.scroller = pane;
  watchScroll(pane);
  viewer.pageLabel = () => 'Page ' + pdfViewer.currentPageNumber + ' of ' + pdf.numPages;
  viewer.amount = () => pdf.numPages + (pdf.numPages === 1 ? ' page' : ' pages');
  // 3.0: asked in the page (prompt() answered nothing here).
  viewer.goTo = () => askFor({
    text: 'Go to page (1–' + pdf.numPages + ')',
    type: 'number',
    value: String(pdfViewer.currentPageNumber),
    ok: 'Go',
    check: v => {
      const n = parseInt(v, 10);
      if (!(n >= 1 && n <= pdf.numPages)) { return 'Type a page number from 1 to ' + pdf.numPages + '.'; }
      pdfViewer.currentPageNumber = n;
      return null;
    },
  });

  // Zoom: two fingers, or a double tap; pdf.js draws the pages again at the new size, so text stays sharp.
  const zoomTo = (target, x, y) => {
    const t = Math.max(fit * 0.5, Math.min(fit * 5, target));
    const factor = t / pdfViewer.currentScale;
    if (Math.abs(factor - 1) > 0.01) { pdfViewer.updateScale({ drawingDelay: 300, scaleFactor: factor, origin: [x, y] }); }
  };
  viewer.doubleTap = (x, y) => zoomTo(pdfViewer.currentScale > fit * 1.25 ? fit : fit * 2.5, x, y);
  let pinch = null;
  const spread = t => Math.hypot(t[0].clientX - t[1].clientX, t[0].clientY - t[1].clientY);
  pane.addEventListener('touchstart', e => {
    pinch = e.touches.length === 2 && !viewer.drawing ? { d: spread(e.touches), scale: pdfViewer.currentScale } : null;
  }, { passive: true });
  pane.addEventListener('touchmove', e => {
    if (!pinch || e.touches.length !== 2) { return; }
    const t = e.touches;
    zoomTo(pinch.scale * spread(t) / pinch.d, (t[0].clientX + t[1].clientX) / 2, (t[0].clientY + t[1].clientY) / 2);
  }, { passive: true });
  pane.addEventListener('touchend', e => { if (e.touches.length < 2) { pinch = null; } }, { passive: true });

  viewer.ownFind = true;
  viewer.find = (query, again, previous) => eventBus.dispatch('find', {
    source: null, type: again ? 'again' : '', query, caseSensitive: false, entireWord: false,
    highlightAll: true, findPrevious: !!previous, matchDiacritics: false,
  });
  viewer.closeFind = () => eventBus.dispatch('findbarclose', { source: null });
  viewer.size = f => zoomTo(pdfViewer.currentScale * f, innerWidth / 2, innerHeight / 2);

  // The PDF's own table of contents.
  const outline = await pdf.getOutline().catch(() => null);
  if (outline && outline.length) {
    viewer.chaptersTitle = 'Contents';
    viewer.chapters = () => {
      const out = [];
      const walk = (items, depth) => {
        for (const it of items) {
          if (out.length >= 400) { return; }
          if (it.dest) { out.push({ label: (it.title || '').trim() || '…', depth, go: () => linkService.goToDestination(it.dest) }); }
          if (it.items && it.items.length && depth < 3) { walk(it.items, depth + 1); }
        }
      };
      walk(outline, 0);
      return out;
    };
  }
  viewer.pages = () => pagePreviews(pdf, pdfViewer);

  // Fill & sign: forms, highlights, drawing (a signature), text.
  let dirty = false;
  pdf.annotationStorage.onSetModified = () => { dirty = true; };
  const baseName = info.name.replace(/\.pdf$/i, '');
  const saveFilled = async () => {
    toast('Saving a copy…', 8000);
    try {
      const data = await pdf.saveDocument();
      sendFile(baseName + ' (filled in).pdf', 'application/pdf', data);
      dirty = false;
      // Swift says when it is saved.
      $('#toast').hidden = true;
    } catch (e) {
      toast("Swift couldn't save this PDF.");
    }
  };
  viewer.saveCopy = () => { if (dirty) { saveFilled(); } else { action('save-copy'); } };
  viewer.edit = () => {
    const T = lib.AnnotationEditorType;
    const P = lib.AnnotationEditorParamsType;
    const bar = $('#editbar');
    bar.textContent = '';
    viewer.editing = true;
    setReading(false);
    document.body.classList.add('editing');
    const setMode = mode => {
      pdfViewer.annotationEditorMode = { mode };
      viewer.drawing = mode === T.INK;
      Array.from(bar.children).forEach(b => b.classList.toggle('on', b.dataset.mode === String(mode)));
      if (mode === T.INK) {
        // Dark blue ink shows on light and dark pages alike, like a pen.
        eventBus.dispatch('switchannotationeditorparams', { source: null, type: P.INK_COLOR, value: '#1a4fc4' });
        eventBus.dispatch('switchannotationeditorparams', { source: null, type: P.INK_THICKNESS, value: 2 });
      } else if (mode === T.FREETEXT) {
        eventBus.dispatch('switchannotationeditorparams', { source: null, type: P.FREETEXT_COLOR, value: '#1a4fc4' });
      }
    };
    const add = (label, run, mode) => {
      const b = document.createElement('button');
      b.textContent = label;
      if (mode !== undefined) { b.dataset.mode = String(mode); }
      b.addEventListener('click', run);
      bar.appendChild(b);
      return b;
    };
    for (const [label, mode] of [['Highlight', T.HIGHLIGHT], ['Sign', T.INK], ['Text', T.FREETEXT]]) {
      add(label, () => setMode(pdfViewer.annotationEditorMode === mode ? T.NONE : mode), mode);
    }
    // The tools, a gap, then what acts on them.
    add('Undo', () => eventBus.dispatch('editingaction', { source: null, name: 'undo' })).classList.add('apart');
    add('Save', saveFilled).className = 'main';
    const finish = () => {
      setMode(T.NONE);
      viewer.editing = false;
      viewer.drawing = false;
      bar.hidden = true;
      document.body.classList.remove('editing');
    };
    // Leaving with changes not saved: say so instead of throwing them away (2.6).
    const leave = byBack => {
      if (!dirty) {
        finish();
        if (!byBack) { closed(leave); }
        return;
      }
      if (byBack) { opened(leave); }
      ask('Your changes to this PDF are not saved yet.', [
        ['Keep editing', () => {}],
        ['Discard', () => { dirty = false; finish(); closed(leave); }],
        ['Save a copy', () => { saveFilled(); finish(); closed(leave); }, true],
      ]);
    };
    add('Done', () => leave(false));
    opened(leave);
    bar.hidden = false;
    toast('Tap a form field to fill it in. Sign draws with your finger; Save puts a copy in Downloads.', 5000);
  };

  // Read aloud: a few pages at a time from the page on screen, on to the end; what is read is marked.
  const pageTexts = async first => {
    const texts = [];
    const pageOf = [];
    const last = Math.min(pdf.numPages, first + 9);
    for (let p = first; p <= last; p++) {
      const content = await (await pdf.getPage(p)).getTextContent();
      let s = '';
      for (const it of content.items) { s += it.str + (it.hasEOL ? '\n' : ''); }
      for (const para of s.split(/\n\s*\n|(?<=[.!?])\n/)) {
        const t = para.replace(/\s+/g, ' ').trim();
        if (t) { texts.push(t); pageOf.push(p); }
      }
    }
    return {
      texts,
      els: null,
      from: 0,
      mark: i => {
        const p = pageOf[i];
        if (!p) { return; }
        if (p !== pdfViewer.currentPageNumber) { pdfViewer.currentPageNumber = p; }
        const words = texts[i].split(' ').slice(0, 7).join(' ');
        if (words.length > 3) {
          eventBus.dispatch('find', {
            source: null, type: '', query: words, caseSensitive: false, entireWord: false,
            highlightAll: false, findPrevious: false, matchDiacritics: false,
          });
        }
      },
      unmark: () => eventBus.dispatch('findbarclose', { source: null }),
      next: last < pdf.numPages ? () => pageTexts(last + 1) : null,
    };
  };
  viewer.texts = () => pageTexts(pdfViewer.currentPageNumber);
}

/**
 * 3.0: the first page, small, for the PDF's picture in Files and on the home page's Continue: drawn
 * here once (Swift asks only when it has none), so Android's own PDF renderer, years behind on fixes,
 * needn't read the file inside Swift.
 */
async function sendThumb(pdf) {
  try {
    const page = await pdf.getPage(1);
    const vp1 = page.getViewport({ scale: 1 });
    const vp = page.getViewport({ scale: 360 / vp1.width });
    const canvas = document.createElement('canvas');
    canvas.width = Math.round(vp.width);
    canvas.height = Math.min(1000, Math.round(vp.height));
    // On white paper (a see-through page would turn black in a JPEG).
    await page.render({ canvas, viewport: vp, background: '#ffffff' }).promise;
    const data = canvas.toDataURL('image/jpeg', 0.82).split(',')[1] || '';
    canvas.width = canvas.height = 0;
    if (data) { send({ type: 'doc-thumb', id, data }); }
  } catch (e) { /* no picture: Swift keeps the PDF's mark */ }
}

/** Small pictures of every page (2.5): tap one to go there. Drawn only as they come into view. */
function pagePreviews(pdf, pdfViewer) {
  const wrap = document.createElement('div');
  wrap.id = 'pages';
  const head = document.createElement('div');
  head.id = 'pageshead';
  const title = document.createElement('span');
  title.textContent = 'Pages';
  const close = document.createElement('button');
  close.className = 'icon';
  close.setAttribute('aria-label', 'Close');
  close.innerHTML = symbol('close');
  head.append(title, close);
  const remove = () => { io.disconnect(); far.disconnect(); wrap.remove(); head.remove(); };
  const done = () => { remove(); closed(remove); };
  close.addEventListener('click', done);
  // Each page's state: '' blank, 'queued', 'drawn'.
  const queue = [];
  let busy = false;
  const drawNext = async () => {
    if (busy || !wrap.isConnected) { return; }
    let t = queue.shift();
    while (t && t.dataset.state !== 'queued') { t = queue.shift(); }
    if (!t) { return; }
    busy = true;
    try {
      const page = await pdf.getPage(parseInt(t.dataset.n, 10));
      const vp1 = page.getViewport({ scale: 1 });
      const vp = page.getViewport({ scale: 220 / vp1.width });
      const canvas = document.createElement('canvas');
      canvas.width = Math.round(vp.width);
      canvas.height = Math.round(vp.height);
      await page.render({ canvas, viewport: vp }).promise;
      const blank = t.querySelector('.blank');
      if (blank && t.dataset.state === 'queued') {
        blank.replaceWith(canvas);
        t.dataset.state = 'drawn';
      } else {
        canvas.width = canvas.height = 0;
      }
    } catch (e) {
      // Stays blank.
      t.dataset.state = '';
    }
    busy = false;
    drawNext();
  };
  const io = new IntersectionObserver(entries => {
    for (const en of entries) {
      const t = en.target;
      if (!en.isIntersecting || t.dataset.state) { continue; }
      t.dataset.state = 'queued';
      queue.push(t);
    }
    drawNext();
  }, { root: wrap, rootMargin: '400px' });
  // 3.0: pages scrolled well out of view give their picture back (about 270 KB each), and are drawn
  // again if they come back: a long PDF's previews no longer fill the memory.
  const far = new IntersectionObserver(entries => {
    for (const en of entries) {
      const t = en.target;
      if (en.isIntersecting || !t.dataset.state) { continue; }
      const canvas = t.querySelector('canvas');
      if (canvas) {
        const blank = document.createElement('div');
        blank.className = 'blank';
        blank.style.aspectRatio = (canvas.width / Math.max(1, canvas.height)).toFixed(3);
        canvas.replaceWith(blank);
        canvas.width = canvas.height = 0;
      }
      t.dataset.state = '';
    }
  }, { root: wrap, rootMargin: '1200px' });
  const current = pdfViewer.currentPageNumber;
  let currentEl = null;
  for (let n = 1; n <= pdf.numPages; n++) {
    const t = document.createElement('button');
    t.className = 'thumb' + (n === current ? ' on' : '');
    t.dataset.n = n;
    const blank = document.createElement('div');
    blank.className = 'blank';
    blank.style.aspectRatio = '0.707';
    const label = document.createElement('span');
    label.textContent = n;
    t.append(blank, label);
    t.addEventListener('click', () => { pdfViewer.currentPageNumber = n; done(); });
    wrap.appendChild(t);
    io.observe(t);
    far.observe(t);
    if (n === current) { currentEl = t; }
  }
  document.body.append(wrap, head);
  opened(remove);
  if (currentEl) { currentEl.scrollIntoView({ block: 'center' }); }
}

// ------------------------------------------------------------------ the bar's buttons

function setFindCount(n) { $('#findcount').textContent = n ? String(n) : ''; }

$('#find').addEventListener('click', () => {
  if (!viewer.ownFind) {
    // Swift's own find bar finds in Word, text and the rest.
    action('find');
    return;
  }
  const bar = $('#findbar');
  bar.hidden = false;
  setReading(false);
  document.body.classList.add('finding');
  opened(endFind);
  $('#findtext').focus();
});
function endFind() {
  hide($('#findbar'));
  document.body.classList.remove('finding');
  setFindCount('');
  if (viewer.closeFind) { viewer.closeFind(); }
}
let findTimer = 0;
$('#findtext').addEventListener('input', e => {
  clearTimeout(findTimer);
  const q = e.target.value;
  findTimer = setTimeout(() => viewer.find(q, false, false), info && info.kind === 'sheet' ? 300 : 0);
});
$('#findtext').addEventListener('keydown', e => { if (e.key === 'Enter') { viewer.find(e.target.value, true, e.shiftKey); } });
$('#findnext').addEventListener('click', () => viewer.find($('#findtext').value, true, false));
$('#findprev').addEventListener('click', () => viewer.find($('#findtext').value, true, true));
$('#findclose').addEventListener('click', () => {
  endFind();
  closed(endFind);
});

function zoom(f) {
  if (!viewer.size) { return; }
  if (info.kind === 'pdf') { viewer.size(f); return; }
  scale = Math.max(0.6, Math.min(2.5, Math.round(scale * f * 100) / 100));
  viewer.size(scale);
  store(SIZE_KEY, String(scale));
}
$('#smaller').addEventListener('click', () => zoom(1 / 1.15));
$('#bigger').addEventListener('click', () => zoom(1.15));
$('#play').addEventListener('click', () => { if (viewer.slideshow) { viewer.slideshow(); } });

// ------------------------------------------------------------------ the more menu

const menuOverlay = () => hide($('#menu'));
function hideMenu() {
  if ($('#menu').hidden) { return; }
  hide($('#menu'));
  closed(menuOverlay);
}

function openMenu() {
  const menu = $('#menu');
  menu.textContent = '';
  let newGroup = false;
  // A menu item: its icon, its name and a line saying more. A new group starts with a small gap.
  const item = (label, run, hint, icon) => {
    if (newGroup && menu.lastChild) {
      const gap = document.createElement('div');
      gap.className = 'gap';
      menu.appendChild(gap);
    }
    newGroup = false;
    const b = document.createElement('button');
    if (icon) {
      const i = document.createElement('span');
      i.className = 'mi';
      i.innerHTML = symbol(icon);
      b.appendChild(i);
    }
    const name = document.createElement('span');
    name.textContent = label;
    b.appendChild(name);
    if (hint) {
      const h = document.createElement('span');
      h.className = 'hint';
      h.textContent = hint;
      name.appendChild(h);
    }
    b.addEventListener('click', () => { run(); hideMenu(); });
    menu.appendChild(b);
  };
  const shown = info && statusGone();
  // 2.8: on a narrow screen of its own, zoom and Listen sit here (the bar keeps room for the name).
  if (shown && ownScreen && innerWidth < 600) {
    const row = document.createElement('div');
    row.className = 'row';
    for (const key of ['smaller', 'bigger', 'listen']) {
      const src = $('#' + key);
      if (src.hidden) { continue; }
      const b = src.cloneNode(true);
      b.removeAttribute('id');
      // Zooming keeps the menu open (a few taps in a row); Listen closes it.
      b.addEventListener('click', () => { src.click(); if (key === 'listen') { hideMenu(); } });
      row.appendChild(b);
    }
    if (row.childNodes.length) { menu.appendChild(row); }
  }
  // Going to a place in the document.
  if (shown && viewer.goTo) { item('Go to page…', viewer.goTo, null, 'goto'); }
  if (shown && viewer.chapters) { item(viewer.chaptersTitle || 'Chapters', showChapters, null, 'chapters'); }
  if (shown && viewer.pages) { item('Pages', viewer.pages, null, 'pages'); }
  if (shown && viewer.slideshow) { item('Slideshow', viewer.slideshow, null, 'play'); }
  // Changing how it reads, or the document.
  newGroup = true;
  if (shown && viewer.style) { item('Text style', viewer.style, null, 'style'); }
  if (shown && viewer.edit) { item('Fill & sign', viewer.edit, null, 'edit'); }
  if (info && info.kind === 'word' && lastBuf) {
    item(pageLayout ? 'Reading view' : 'Page view', () => { pageLayout = !pageLayout; renderWord(); }, null, 'reader');
  }
  // Sharing and saving.
  newGroup = true;
  item('Share', () => action('share'), null, 'share');
  item('Open with another app', () => action('open-with'), null, 'open');
  if (info && info.copy) { item('Save a copy', () => (viewer.saveCopy ? viewer.saveCopy() : action('save-copy')), null, 'download'); }
  if (shown && info.kind !== 'pdf' && info.kind !== 'epub' && info.kind !== 'zip') { item('Save as PDF', () => printOut('save-pdf'), null, 'pdf'); }
  if (shown && info.kind !== 'epub' && info.kind !== 'zip') { item('Print', () => printOut('print'), null, 'print'); }
  if (info && info.web && ['word', 'sheet', 'slides', 'oldword', 'oldslides'].includes(info.kind)) {
    // 3.0: it says what it does: the file's web address goes to Microsoft, whose viewer shows it.
    item('Exact view online', () => action('exact'), "Sends this file's web address to Microsoft's online viewer", 'web');
  }
  if (info) { item('Add to home screen', () => action('shortcut'), null, 'home'); }
  // Details. 3.0: in the page (alert() showed nothing on the viewer's own screen).
  newGroup = true;
  if (info) { item('Details', showDetails, null, 'info'); }
  menu.style.maxHeight = '';
  menu.hidden = false;
  opened(menuOverlay);
}

/** 3.0: the document's name, kind, size, length and where it is, in a panel like Text style's. */
function showDetails() {
  const panel = $('#panel');
  panel.textContent = '';
  const h = document.createElement('h3');
  h.textContent = 'Details';
  panel.appendChild(h);
  const list = document.createElement('dl');
  list.className = 'details';
  const row = (term, value) => {
    if (!value) { return; }
    const dt = document.createElement('dt');
    dt.textContent = term;
    const dd = document.createElement('dd');
    dd.textContent = value;
    list.append(dt, dd);
  };
  row('Name', info.name);
  row('Kind', info.label || (KINDS[info.kind] || KINDS.text)[0]);
  row('Size', info.size > 0 ? formatSize(info.size) : '');
  row('Length', viewer.amount ? viewer.amount() : '');
  row('Where', info.where || '');
  panel.appendChild(list);
  const done = document.createElement('button');
  done.className = 'done';
  done.textContent = 'Done';
  const panelOverlay = () => hide(panel);
  done.addEventListener('click', () => {
    hide(panel);
    closed(panelOverlay);
  });
  panel.appendChild(done);
  panel.hidden = false;
  opened(panelOverlay);
}

function showChapters() {
  const menu = $('#menu');
  menu.textContent = '';
  for (const ch of viewer.chapters().slice(0, 400)) {
    const b = document.createElement('button');
    b.textContent = ch.label;
    if (ch.depth) {
      b.className = 'sub';
      b.style.paddingLeft = (18 + ch.depth * 16) + 'px';
    }
    b.addEventListener('click', () => { ch.go(); hideMenu(); });
    menu.appendChild(b);
  }
  menu.style.maxHeight = '70vh';
  menu.hidden = false;
  opened(menuOverlay);
}

function formatSize(n) {
  if (n < 1024) { return n + ' B'; }
  if (n < 1048576) { return Math.round(n / 1024) + ' KB'; }
  return (n / 1048576).toFixed(1) + ' MB';
}

$('#more').addEventListener('click', e => {
  e.stopPropagation();
  if ($('#menu').hidden) { openMenu(); } else { hideMenu(); }
});
document.addEventListener('click', e => {
  if (!$('#menu').hidden && !e.target.closest('#menu')) { hideMenu(); }
});

/** Save as PDF or print (2.5): everything is drawn first (a long sheet's rows), then Swift takes over. */
function printOut(what) {
  if (info.kind === 'sheet') {
    // The rows not drawn yet (up to a few thousand) go on paper too.
    const pane = document.querySelector('.pane');
    for (let k = 0; k < 20 && pane && pane.scrollHeight > 0; k++) { pane.dispatchEvent(new Event('scroll')); pane.scrollTop = pane.scrollHeight; }
  }
  for (const d of document.querySelectorAll('details.notes')) { d.open = true; }
  toast(what === 'print' ? 'Preparing to print…' : 'Saving as PDF…');
  setTimeout(() => action(what), 300);
}

// ------------------------------------------------------------------ read aloud (Swift's Listen)

let ttsBatch = null;
let ttsState = 'idle';
let marked = null;

$('#listen').addEventListener('click', async () => {
  if (ttsState !== 'idle') { stopTts(); return; }
  if (!viewer.texts) { return; }
  startTts(await viewer.texts());
});

function startTts(t) {
  if (!t || !t.texts.length) {
    if (!ttsBatch) { note('There is no text here to read aloud.'); }
    setTts('idle');
    return;
  }
  ttsBatch = t;
  send({ type: 'tts-start', texts: t.texts, from: t.from || 0, rate: 1, lang: document.documentElement.lang || '', title: info.name });
  setTts('speaking');
}

function stopTts() {
  const b = ttsBatch;
  ttsBatch = null;
  send({ type: 'tts-stop' });
  setTts('idle');
  if (b && b.unmark) { b.unmark(); }
}

function setTts(state) {
  ttsState = state;
  $('#listen').classList.toggle('on', state !== 'idle');
  $('#tts').hidden = state === 'idle';
  document.body.classList.toggle('listening', state !== 'idle');
  $('#ttstoggle').textContent = state === 'paused' ? 'Resume' : 'Pause';
  if (state === 'idle' && marked) { marked.classList.remove('speaking'); marked = null; }
}

function onTts(st) {
  if (!st.speaking && !st.paused) {
    // The end of what was handed over: the next pages or chapter follow (2.5).
    const b = ttsBatch;
    if (st.finished && b && b.next) {
      ttsBatch = null;
      Promise.resolve(b.next()).then(t => {
        if (t && t.texts.length) { startTts(t); } else { setTts('idle'); }
      }).catch(() => setTts('idle'));
      return;
    }
    if (b && b.unmark) { b.unmark(); }
    ttsBatch = null;
    setTts('idle');
    return;
  }
  setTts(st.paused ? 'paused' : 'speaking');
  if (!ttsBatch || typeof st.index !== 'number') { return; }
  if (ttsBatch.mark) { ttsBatch.mark(st.index); return; }
  const els = ttsBatch.els;
  if (els && els[st.index]) {
    if (marked) { marked.classList.remove('speaking'); }
    marked = els[st.index];
    marked.classList.add('speaking');
    const r = marked.getBoundingClientRect();
    if (r.top < 64 || r.bottom > innerHeight - 90) { marked.scrollIntoView({ block: 'center', behavior: 'smooth' }); }
  }
}

$('#ttstoggle').addEventListener('click', () => {
  if (ttsState === 'speaking') { send({ type: 'tts-pause' }); setTts('paused'); } else { send({ type: 'tts-resume' }); setTts('speaking'); }
});
$('#ttsstop').addEventListener('click', stopTts);
addEventListener('pagehide', () => { if (ttsState !== 'idle') { send({ type: 'tts-stop' }); } });

// ------------------------------------------------------------------ go

// Swift may not be listening yet (a tab restored as Swift starts): say hello again until it answers.
send({ type: 'doc-hello', id });
let helloTries = 0;
const helloTimer = setInterval(() => {
  if (info || ++helloTries > 30) { clearInterval(helloTimer); return; }
  if (port) { try { port.disconnect(); } catch (e) { /* gone already */ } }
  port = null;
  send({ type: 'doc-hello', id });
}, 1500);
