/**
 * GameTalk Signaling Server
 * ------------------------------------------------------------
 * سيرفر ربط خفيف جداً لمكالمات GameTalk الصوتية.
 * مهمته فقط تعارف الجهازين (تبادل SDP و ICE) ثم الصوت يسير
 * مباشرة P2P بين الهاتفين عبر WebRTC (لا يمر بالسيرفر إطلاقاً).
 *
 * البروتوكول (JSON):
 *   C->S  {type:"create", code?}      S->C  {type:"created", code}
 *   C->S  {type:"join", code}         S->C  {type:"joined"} | {type:"notfound"} | {type:"full"}
 *   S->C  {type:"peer-joined"}  | {type:"peer-left"}
 *   C->S  {type:"offer"|"answer"|"ice", ...}  (تُمرَّر للطرف الآخر كما هي)
 *   C->S  {type:"ping"}               S->C  {type:"pong"}
 */
const http = require("http");
const crypto = require("crypto");
const { WebSocketServer } = require("ws");

const PORT = process.env.PORT || 8080;
const ROOM_TTL_MS = 15 * 60 * 1000;      // حذف الغرفة بعد 15 دقيقة فارغة
const HEARTBEAT_MS = 30 * 1000;          // فحص الاتصالات الميتة

// أحرف رموز الغرف بدون حروف متشابهة (بدون I, O, 0, 1, L)
const ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
const genCode = () => Array.from(crypto.randomBytes(4))
  .map(b => ALPHABET[b % ALPHABET.length]).join("");

const rooms = new Map(); // code -> { a: ws|null, b: ws|null, emptySince }

function send(ws, obj) {
  if (ws && ws.readyState === 1) { ws.send(JSON.stringify(obj)); return true; }
  return false;
}
function relayPeer(room, me, obj) {
  const peer = room.a === me ? room.b : room.a;
  return send(peer, obj);
}
function slotOf(room, ws) { return room.a === ws ? "a" : (room.b === ws ? "b" : null); }
function roomEmpty(room) { return !alive(room.a) && !alive(room.b); }
function alive(ws) { return ws && ws.readyState === 1; }

const server = http.createServer((req, res) => {
  res.writeHead(200, { "Content-Type": "text/plain; charset=utf-8" });
  res.end("GameTalk signaling OK");
});

const wss = new WebSocketServer({ server });

wss.on("connection", (ws) => {
  ws.isAlive = true;
  ws.on("pong", () => { ws.isAlive = true; });

  ws.on("message", (raw) => {
    let msg;
    try { msg = JSON.parse(raw); } catch { return; }

    switch (msg.type) {
      case "ping": send(ws, { type: "pong" }); break;

      case "create": {
        let code = (typeof msg.code === "string" && /^[A-Z2-9]{4}$/.test(msg.code))
          ? msg.code : genCode();
        let room = rooms.get(code);
        if (room && alive(room.a)) { // الرمز مشغول بجهاز حي → ولّد غيره
          if (!msg.code) { while (rooms.has(code)) code = genCode(); room = undefined; }
          else { send(ws, { type: "error", reason: "code-taken" }); return; }
        }
        if (!room) { room = { a: null, b: null, emptySince: Date.now() }; rooms.set(code, room); }
        // استعادة نفس الغرفة بعد انقطاع (reclaim)
        room.a = ws; room.emptySince = null;
        send(ws, { type: "created", code });
        break;
      }

      case "join": {
        const code = String(msg.code || "").toUpperCase().trim();
        const room = rooms.get(code);
        if (!room) { send(ws, { type: "notfound" }); return; }
        if (alive(room.b)) { send(ws, { type: "full" }); return; }
        room.b = ws; room.emptySince = null;
        send(ws, { type: "joined", code });
        if (alive(room.a)) send(room.a, { type: "peer-joined" });
        break;
      }

      case "offer":
      case "answer":
      case "ice": {
        const room = rooms.get(String(msg.code || "").toUpperCase());
        if (!room) return;
        const out = { type: msg.type };
        if (msg.type === "ice") out.candidate = msg.candidate;
        else out.sdp = msg.sdp;
        relayPeer(room, ws, out);
        break;
      }
    }
  });

  ws.on("close", () => {
    for (const [code, room] of rooms) {
      const slot = slotOf(room, ws);
      if (!slot) continue;
      // نحدد الطرف الآخر قبل تصفير الخانة!
      const peer = room[slot === "a" ? "b" : "a"];
      room[slot] = null;
      send(peer, { type: "peer-left" });
      if (roomEmpty(room)) room.emptySince = Date.now();
    }
  });
});

// فحص الاتصالات الميتة + تنظيف الغرف القديمة
setInterval(() => {
  wss.clients.forEach(c => {
    if (c.isAlive === false) { c.terminate(); return; }
    c.isAlive = false; try { c.ping(); } catch {}
  });
  const now = Date.now();
  for (const [code, room] of rooms) {
    if (room.emptySince && now - room.emptySince > ROOM_TTL_MS) rooms.delete(code);
  }
}, HEARTBEAT_MS);

server.listen(PORT, () => console.log(`GameTalk signaling server running on port ${PORT}`));
