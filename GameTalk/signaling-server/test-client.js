/** اختبار سيرفر GameTalk: يحاكي جهازين + جهاز ثالث للتأكد من الرفض */
const WebSocket = require("ws");
const URL = "ws://127.0.0.1:8080";

const results = [];
const check = (name, cond) => { results.push([name, !!cond]); console.log(`${cond ? "✅" : "❌"} ${name}`); };

function client() {
  const ws = new WebSocket(URL);
  const listeners = [];
  ws.on("message", raw => {
    const msg = JSON.parse(raw.toString());
    listeners.forEach(fn => fn(msg));
  });
  return {
    ws,
    on: fn => listeners.push(fn),
    send: obj => ws.send(JSON.stringify(obj)),
    close: () => ws.close()
  };
}
const wait = ms => new Promise(r => setTimeout(r, ms));
const waitFor = (c, type, timeout = 3000) => new Promise((res, rej) => {
  const t = setTimeout(() => rej(new Error(`timeout waiting ${type}`)), timeout);
  c.on(msg => { if (msg.type === type) { clearTimeout(t); res(msg); } });
});

(async () => {
  const A = client(), B = client(), C = client();
  await wait(300);

  // 1) إنشاء غرفة
  const createdP = waitFor(A, "created");
  A.send({ type: "create" });
  const created = await createdP;
  check("create → room code 4 chars", /^[A-Z2-9]{4}$/.test(created.code));
  const code = created.code;

  // 2) انضمام صديق
  const joinedP = waitFor(B, "joined");
  const peerP = waitFor(A, "peer-joined");
  B.send({ type: "join", code });
  const joined = await joinedP; await peerP;
  check("join → joined", joined.code === code);

  // 3) غرفة ممتلئة للجهاز الثالث
  C.send({ type: "join", code });
  const full = await waitFor(C, "full");
  check("3rd device → full", full.type === "full");

  // 4) offer من A إلى B
  const offerP = waitFor(B, "offer");
  A.send({ type: "offer", code, sdp: "v=0-FAKE-OFFER" });
  check("offer relayed", (await offerP).sdp.includes("FAKE"));

  // 5) answer من B إلى A
  const answerP = waitFor(A, "answer");
  B.send({ type: "answer", code, sdp: "v=0-FAKE-ANSWER" });
  check("answer relayed", (await answerP).sdp.includes("FAKE"));

  // 6) مرشحات ICE
  const iceP = waitFor(B, "ice");
  A.send({ type: "ice", code, candidate: { candidate: "candidate:1 udp", sdpMid: "0" } });
  check("ice relayed", !!(await iceP).candidate);

  // 7) انقطاع B → إشعار A ثم انضمام مجدد لنفس الغرفة
  const leftP = waitFor(A, "peer-left");
  B.close(); await leftP;
  check("B disconnect → peer-left", true);
  const B2 = client(); await wait(200);
  const rejoinP = waitFor(B2, "joined");
  const peerAgainP = waitFor(A, "peer-joined");
  B2.send({ type: "join", code });
  await rejoinP; await peerAgainP;
  check("B rejoin same room", true);

  // 8) انقطاع A → استعادة نفس الرمز (reclaim)
  const leftP2 = waitFor(B2, "peer-left");
  A.close(); await leftP2;
  const A2 = client(); await wait(200);
  const reclaimP = waitFor(A2, "created");
  A2.send({ type: "create", code });
  const rec = await reclaimP;
  check("creator reconnect reclaims same code", rec.code === code);

  // 9) join لرمز غير موجود
  C.send({ type: "join", code: "ZZZZ" });
  const nf = await waitFor(C, "notfound");
  check("join unknown code → notfound", nf.type === "notfound");

  // 10) ping/pong
  A2.send({ type: "ping" });
  check("ping → pong", (await waitFor(A2, "pong")).type === "pong");

  [A2, B2, C].forEach(c => c.close());
  const failed = results.filter(r => !r[1]).length;
  console.log(`\n===== RESULT: ${results.length - failed}/${results.length} passed =====`);
  process.exit(failed ? 1 : 0);
})().catch(e => { console.error("TEST ERROR:", e.message); process.exit(2); });
