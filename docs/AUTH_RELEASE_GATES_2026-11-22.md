# MOMENTO — аудит сборки и входа (22.11.2026)

Источник: `MOMENTO-0.54.3-auth-build-sources.zip`. Приложенный `MOMENTO-0.54.3-auth-ui-preview.apk` — предыдущая сборка, НЕ результат изменений ниже.

## Исправлено в исходниках

1. `app/build.gradle.kts`: fail-closed конфигурация API по умолчанию. Создание APK с пустым API требует **одновременно** явных флагов `-PmomentoAllowOfflinePreview=true -PmomentoRequireLiveAuth=false`; этот режим предназначен только для нефункционального локального UI-просмотра. Нормальная сборка требует публичный HTTPS API.
2. `.github/workflows/android-build.yml`: исправлено название Android SDK package `platforms;android-37` вместо `platforms;android-37.0`; до Android-тестов добавлена проверка URL, для offline unit/lint явно указаны оба preview-флага. Сборка APK использует `MOMENTO_API_URL` и `-PmomentoRequireLiveAuth=true`.
3. `scripts/check_api_url.py` + `scripts/test_api_url.py`: проверка HTTPS, отсутствия плейсхолдеров, приватных IP, credentials, query и fragment. Проверка синтаксиса URL НЕ является проверкой DNS, TLS или работы API.
4. `backend/src/server.js`: `/ready` использует существующую проверку PostgreSQL через `SELECT 1`; `/health` сохранён.
5. `AuthScreen.kt`: состояние email/телефона, OTP и challenge сохраняется при пересоздании Activity через `rememberSaveable`; секреты сессии остаются в защищённом vault, не в saveable state.

## Фактически выполнено

- `npm run check`: пройдено (проверка синтаксиса JS).
- `npm test`: 94 пройдены, 2 пропущены (PostgreSQL интеграция не запускалась).
- `python -m unittest discover -s scripts -p test_api_url.py`: 2 теста пройдены.
- ZIP/APK: структура архива проверена, присутствует DEX; это не запуск приложения.

## Не выполнено

- Android Gradle compilation, Android Lint, Android unit/instrumentation tests: нет локальной Android SDK и Gradle distribution; GitHub-репозитории, доступные подключённому аккаунту, не найдены.
- DNS, TLS, реальный сервер, PostgreSQL-интеграция, реальная email/SMS доставка, двухпользовательский сценарий: нет разрешённого публичного стенда и провайдеров.
- Сравнение screenshot-to-mockup: приложение с изменениями не запускалось; актуальные утверждённые макеты OTP/входа не были приложены к этому сообщению. Не выдавать концептуальные картинки за скриншоты.
- APK с изменениями: не собран. Предыдущий APK не перепаковывался и не выдаётся за новый.

## Внешние зависимости

Нужны разрешённый публичный HTTPS endpoint, PostgreSQL, применённые миграции, серверные переменные `EMAIL_DELIVERY_URL`, `EMAIL_DELIVERY_API_KEY`, `SMS_DELIVERY_URL`, `SMS_DELIVERY_API_KEY`, а также защищённые JWT/OTP secrets на сервере. Никогда не передавать секреты в чат или APK.

Для Android-компиляции нужен запуск подготовленного workflow во внешней среде с `MOMENTO_API_URL`; подключённый GitHub аккаунт сейчас не показывает репозиториев. Установка/обновление поверх старой сборки зависит от совпадения подписи; пока не проверено.

## Источники подходов

- https://developer.android.com/develop/ui/compose/system/insets-ui — `imePadding`, прокрутка формы при открытой клавиатуре; официальный Android Developers.
- https://github.com/android/compose-samples — образцы Compose UI и Material 3; Apache-2.0. Код и изображения из репозитория не копировались; текущий проект использует штатные Compose Material 3.
