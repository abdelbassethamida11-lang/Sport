# قريب منك (لأندرويد)

- **الرئيسية:** تبويبان، «المتابَعون» (إنستغرام الحقيقي بعد تسجيل دخولك في صفحته) و«القريبون» (من يستخدمون التطبيق ضمن 10 كم وفعّلوا ظهورهم).
- **الرسائل:** صندوق رسائل إنستغرام.
- **حسابي:** اسم حسابك، وتفعيل الظهور لمن حولك، وتسجيل الخروج.

## الخطوات
1. **Firebase (مجاني):** أنشئ مشروعاً من console.firebase.google.com، وأضف تطبيق Android باسم الحزمة `com.example.nearby`، ونزّل `google-services.json` لتقرأ منه القيم الثلاث.
2. فعّل **Authentication ← Anonymous**، وأنشئ **Firestore Database**، ثم الصق في Rules:
```
rules_version = '2';
service cloud.firestore {
  match /databases/{db}/documents {
    match /users/{id} {
      allow read: if request.auth != null;
      allow write: if request.auth != null && request.auth.uid == id;
    }
  }
}
```
3. افتح `app/src/main/java/com/example/nearby/Config.kt` وضع القيم: `current_key` و`mobilesdk_app_id` و`project_id`.
4. أنشئ مستودعاً على GitHub وارفع **كل** محتويات هذا المجلد، ومنها مجلد `.github`.
5. من تبويب **Actions ← Build APK** انتظر انتهاء البناء، ثم نزّل `app-debug` من قسم Artifacts، فك ضغطه وثبّت الـAPK.
