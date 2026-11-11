# 🚀 نشر سيرفر GameTalk مجاناً على Render (5 دقائق)

السيرفر مسؤوليته الوحيدة "التعارف" بين الهاتفين. الصوت نفسه لا يمر عبره —
بل يذهب مباشرة بين الهاتفين (P2P) عبر WebRTC، لذا الاستهلاك على السيرفر شبه معدوم.

## الطريقة: Render.com (مجاني)

1. ارفع مجلد `signaling-server` إلى مستودع على **GitHub** حسابك.
2. سجّل دخول إلى [render.com](https://render.com) بحساب GitHub.
3. اختر **New +** ← **Web Service** ← اربط المستودع.
4. الإعدادات:
   - **Runtime**: Node
   - **Build Command**: `npm install`
   - **Start Command**: `node server.js`
   - **Instance Type**: Free
5. اضغط **Create Web Service** وانتظر دقيقة.
6. ستحصل على رابط مثل: `https://gametalk-signaling.onrender.com`
7. حوّله لرابط WebSocket بجعل البروتوكول `wss`:
   `wss://gametalk-signaling.onrender.com`
8. ضع الرابط في حقل «عنوان سيرفر الربط» داخل تطبيق GameTalk على الجهازين.

## ملاحظات مهمة

- النسخة المجانية على Render "تنام" بعد 15 دقيقة بدون استخدام؛ أول مكالمة
  بعد النوم قد تتأخر ~30 ثانية حتى يستيقظ السيرفر (أو افتح رابط السيرفر في
  المتصفح قبل اللعب ليستيقظ).
- يمكنك بدلاً من Render استخدام أي استضافة Node تدعم WebSocket
  (Railway, Fly.io, VPS...).
- للتشغيل المحلي للتجربة: `npm install && npm start` ثم `ws://IP-جهازك:8080`.

## اختبار السيرفر

```bash
npm install
node server.js          # نافذة 1
node test-client.js     # نافذة 2 → يجب أن ترى 11/11 passed
```
