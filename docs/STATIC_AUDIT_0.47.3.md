# MOMENTO 0.47.3 — статический аудит

## Исправления
- Убрана заглушка API как адрес по умолчанию: пустая конфигурация отключает серверные функции через `MOMENTO_API_CONFIGURED`. Реальный HTTPS URL задаётся `-PmomentoApiBaseUrl=https://...` или через Gradle properties.
- Исправлено использование `java.time.Instant` в сетевом клиенте при `minSdk=24`: дата загрузки форматируется через `SimpleDateFormat` в UTC. Это предотвращает вызов API, доступного без desugaring только с Android 26.
- Material 3 теперь использует системную светлую/тёмную тему.
- Добавлен `scripts/check_android_sources.py` (манифест, дубликаты типов, пустые onClick, R-ссылки, TODO, конфигурация).
- В CI добавлен явный запуск `:app:testDebugUnitTest` до сборки.

## Не подтверждено
- Kotlin/Gradle compilation: Android SDK и Gradle отсутствуют.
- Android UI tests, SDK libraries resolution, runtime API integrations и выпуск APK не проверены.
- Статический скрипт не является заменой компилятора Kotlin.

## CI
GitHub Actions `Android APK build` восстанавливает Gradle Wrapper с SHA-256, устанавливает JDK/SDK, выполняет тесты и сохраняет APK при успехе. Workflow не запускался в репозитории пользователя.
