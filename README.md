# AGENT-Z-AI 🤖

مساحة عمل **وكيل Z AI** (مبني على GLM من Z.ai) — كل المشاريع والتطبيقات التي أنشئها لصاحب الحساب **@latchidz** تُرفع هنا، بجانب عمل الوكيل الآخر ARENA.

---

## 📦 المشاريع

### 🎙️ GameTalk — مكالمات صوتية خفيفة أثناء اللعب (v1.0)

تطبيق أندرويد للمكالمات الصوتية منخفضة الاستهلاك مع الأصدقاء أثناء اللعب (eFootball وغيره)، مع **فقاعة عائمة** فوق أي تطبيق للتحكم السريع في الكتم.

| العنصر | الرابط |
|---|---|
| 📱 تحميل APK مباشرة | [`GameTalk/releases/GameTalk-v1.0.apk`](GameTalk/releases/GameTalk-v1.0.apk) |
| 📂 كود تطبيق الأندرويد | [`GameTalk/android-app/`](GameTalk/android-app/) — Kotlin + WebRTC (Opus ‏24kbps + DTX ≈ ‏12MB/ساعة) |
| 🖥️ خادم الإشارة (Signaling) | [`GameTalk/signaling-server/`](GameTalk/signaling-server/) — Node.js + WebSocket، جاهز للنشر على Render |
| 📖 دليل التشغيل السريع | [`GameTalk/docs/دليل-التشغيل-السريع.txt`](GameTalk/docs/دليل-التشغيل-السريع.txt) |

**الميزات:**
- اقتران بغرفة عبر كود من 4 أحرف (بدون أحرف ملتبسة I/O/0/1/L)
- فقاعة عائمة قابلة للسحب: نقرة = كتم/إلغاء، ضغطة طويلة = إنهاء المكالمة، وتتذكّر موضعها
- إعادة اتصال تلقائية عند انقطاع الشبكة
- مؤشر جودة الشبكة (نقطة ملونة داخل الفقاعة)
- زر كتم في شريط الإشعارات
- واجهة عربية كاملة بتصميم داكن يناسب اللاعبين

### 🔐 ملاحظة التوقيع
مفتاح توقيع التطبيق `gametalk.keystore` محفوظ داخل [`GameTalk/android-app/app/`](GameTalk/android-app/app/) حتى يتمكن المالك من إصدار تحديثات موقّعة بنفس التوقيع (كلمة المرور لدى المالك).

---

## ⚙️ بناء التطبيق من المصدر

```bash
cd GameTalk/android-app
./gradlew assembleRelease
# يتطلب: JDK 17 + Android SDK (بدون أي إعداد إضافي — مكتبة WebRTC مرفقة محلياً في app/libs/)
```

## 🖥️ تشغيل خادم الإشارة

```bash
cd GameTalk/signaling-server
npm install
npm start          # يفتح المنفذ 8080
```

جاهز أيضاً للنشر المجاني على Render — الخطوات في [`GameTalk/signaling-server/RENDER-DEPLOY-AR.md`](GameTalk/signaling-server/RENDER-DEPLOY-AR.md).

---

*يُدار ويُحدَّث بواسطة وكيل Z AI — آخر تحديث: 2026-11-11*
