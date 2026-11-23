# MOMENTO — гостевая навигация и состояние реализации

Исходная база: MOMENTO-guest-mode-source-update.zip (последний исходный проект с гостевым режимом).

## Реализовано
- GuestHomeScreen использует Scaffold и четыре согласованных вкладки: Камера, Комнаты, Подписки, Профиль.
- Камера/главная: кнопка перехода к авторизации для публикации, публичные комнаты из настоящего API.
- Комнаты: отдельный список публичных комнат; нет демонстрационных данных.
- Подписки и профиль: явное требование аккаунта без скрытой регистрации.
- Нет API: гостевой режим остаётся доступным; сетевое состояние отделено от пустого каталога.
- Возврат из входа в гостевой режим сохранён в MomentoApp.
- API-операции записи/публикации не открывались гостю.

## Реально выполненные проверки
- backend: `node --test` — 96 passed, 2 skipped, 0 failed.
- `python scripts/check_android_sources.py` — структурный аудит успешно.
- `python scripts/test_live_moments_policy.py` — структурная проверка успешно.
- `python scripts/test_rooms_schema.py` — SQLite migration smoke test успешно.
- `python scripts/test_api_url.py` — 2 теста успешно.

## Не выполнено
- Android компиляция, lint, unit/UI тесты и запуск на устройстве: в данной среде нет Gradle distribution и Android SDK.
- Реальная OTP-доставка и PostgreSQL интеграция: нет работающего тестового API и провайдеров.
- Публикация гостевого локального черновика после входа не завершена.

## Сборка
Для offline guest preview проект сохраняет намеренно строгий gate: 
`./gradlew :app:assembleDebug -PmomentoAllowOfflinePreview=true -PmomentoRequireLiveAuth=false`
Для сборки с реальной авторизацией: 
`./gradlew :app:testDebugUnitTest :app:assembleDebug -PmomentoApiBaseUrl=https://<ваш-домен> -PmomentoRequireLiveAuth=true`
Ни один из вариантов не запускался успешно в этой среде. Offline preview не проверяет OTP.

Не следует выдавать предыдущий APK за сборку с этим изменением.
