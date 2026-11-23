# MOMENTO 0.17.0 — проверка JSON-декодирования и документации

- Исправлено: недопустимые UTF-8 байты теперь отклоняются HTTP 400 вместо неявной подстановки символов Unicode replacement.
- Добавлен регрессионный тест; всего 36 backend unit tests.
- Проверены официальные источники: https://developer.android.com/identity/credential-manager ; https://developers.google.com/identity/sign-in/android/backend-auth ; https://github.com/VKCOM/vkid-android-sdk ; https://yandex.ru/dev/id/doc/ru/mobileauthsdk/about .
- VK ID 2.7.3 указан в официальном changelog от 18 сентября 2026: https://github.com/VKCOM/vkid-android-sdk/blob/master/CHANGELOG.md .
- Не подтверждено: Gradle APK build, реальные OAuth credentials, PostgreSQL migrations/integration, SMS/email delivery.
- Важно: backend принимает JSON даже без Content-Type; для production желательно строго ограничить тип тела после согласования Android API клиента.
- UI и продуктовые решения сохранены без изменений.
