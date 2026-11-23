# MOMENTO — единая система авторизации (проектное решение)

## Поддерживаемые способы входа

MOMENTO предусматривает пять каналов входа:

1. Яндекс ID (OAuth 2.0; mobile SDK / authorization-code flow, PKCE и проверка результата сервером).
2. Google (OpenID Connect; сервер проверяет подпись ID token, `iss`, `aud`, `exp` и использует `sub` как внешний ключ).
3. VK ID (официальный VK ID SDK / OAuth 2.1; обмен и проверка результата на сервере по актуальному контракту VK).
4. Электронная почта (одноразовый код; доставка через подключённый SMTP/transactional email provider).
5. Номер телефона (одноразовый код; формат E.164, доставка через подключённый SMS provider).

## Единый аккаунт

- Внутренний `users.id` MOMENTO — единственный идентификатор владельца комнат, моментов и настроек.
- Внешние идентификаторы хранятся в `auth_identities` как пара `(issuer, subject)`. `subject` — непрозрачная строка; нельзя полагаться на email или телефон как на постоянный идентификатор внешнего аккаунта.
- Один `user_id` может иметь несколько `auth_identities` и подтверждённых контактов.
- Нельзя автоматически объединять аккаунты по совпадающему email/телефону. Привязка нового способа входа выполняется только из активной сессии и после повторного подтверждения владения новым способом.
- При конфликте, когда внешний identity уже принадлежит другому `user_id`, привязка отклоняется и направляется в безопасный recovery flow.

## Email и телефон

- Email приводится к lower-case после trim; телефон — к E.164. Нельзя молча угадывать код страны.
- OTP генерируется криптографическим генератором, имеет короткий срок действия, ограниченное число попыток и ограничение повторной отправки.
- В БД хранится только HMAC-хэш OTP, а не сам код.
- Контакты хранятся в зашифрованном виде; для поиска/уникальности используется отдельный keyed hash. Ключи шифрования и HMAC хранятся вне БД и ротируются с версиями ключей.
- OTP не является долговременной сессией. После проверки сервер создаёт внутреннюю сессию MOMENTO с access token и rotating refresh token; в БД хранится только хэш refresh token.
- Доставка требует выбранного SMTP/email и SMS-провайдера. Без настроенных учётных данных отправка кодов не включается.

## OAuth/OIDC потоки

- Приложение запускает системный браузер/официальный SDK; используются `state`, PKCE и точные redirect URI.
- Client secrets никогда не встраиваются в APK.
- Сервер обменивает authorization code там, где это предусмотрено протоколом, проверяет issuer/audience/nonce/expiry/signature и получает стабильный provider subject.
- Google: проверка ID token по официальным ключам Google; проверяются `iss`, `aud`, `exp`, подпись; идентификатор — `sub`.
- Yandex и VK: использовать актуальные официальные мобильные SDK и server-side code exchange. Не считать их access token автоматически JWT и не проверять их через произвольный JWKS.
- Точные SDK-версии, параметры redirect URI и контракт получения профиля фиксируются после регистрации приложений в кабинетах провайдеров.

## Новая миграция

`backend/db/migrations/002_auth_channels.sql` добавляет:
- `user_contacts` — подтверждённые email/phone с шифротекстом и keyed hash;
- `auth_challenges` — OTP challenge, сроки и счётчики попыток/отправок;
- `auth_sessions` — хэши refresh token и управление сессиями;
- `auth_link_attempts` — одноразовое состояние привязки внешнего identity.

`backend/db/migrations/003_auth_session_hardening.sql` добавляет lineage для refresh rotation и индекс активных сессий. `004_auth_provider_nonce.sql` добавляет одноразовые серверные nonce для Google ID tokens, чтобы предотвратить повторное использование уже принятого ID token. Миграции не должны применяться повторно к базе, где уже применены. Для production требуется migration runner и проверка схемы на PostgreSQL.

## Что требуется для реального подключения

- Зарегистрировать приложения MOMENTO в кабинетах Yandex OAuth, Google Cloud Console и VK ID; указать package name, SHA fingerprints, redirect/deep links и разрешённые домены.
- Получить SMTP/transactional email credentials и SMS provider credentials.
- Серверная основа MOMENTO access tokens, refresh rotation и logout/revoke реализована в 0.11.0; требуется production key management, интеграционные проверки и восстановление аккаунта.
- Утвердить тексты согласий, privacy policy, минимизацию данных и возрастные правила.
- Провести интеграционные тесты на реальных provider sandbox/test accounts и Android devices.

## Источники

- Yandex ID OAuth: https://yandex.ru/dev/id/doc/ru/
- Yandex authorization code / PKCE: https://www.yandex.ru/dev/id/doc/ru/codes/code-url
- Google OpenID Connect: https://developers.google.com/identity/openid-connect/openid-connect
- Google server-side ID token validation: https://developers.google.com/identity/gsi/web/guides/verify-google-id-token
- VK ID SDK docs: https://vkcom.github.io/vkid-web-sdk/docs/
- VK ID SDK repository: https://github.com/VKCOM/vkid-web-sdk
- Android Credential Manager: https://developer.android.com/identity/sign-in/credential-manager
- OIDC Core: https://openid.net/specs/openid-connect-core-1_0.html
- OAuth 2.0 Security BCP: https://www.rfc-editor.org/rfc/rfc9700

## Текущий статус

Это архитектура и фундамент хранения для пяти каналов, не заявление о завершённой интеграции. В 0.11.0 добавлен MOMENTO-owned session lifecycle и временный bridge для одного внешнего OIDC issuer. Реальные Yandex/Google/VK адаптеры требуют app credentials и точных redirect URI; email/SMS отправка невозможна без выбранных и настроенных delivery providers. Android sign-in UI и PostgreSQL integration tests ещё не реализованы.


## Реализация 0.12.0 — серверные адаптеры входа

Добавлены серверные маршруты:
- `POST /v1/auth/providers/google/nonce` — выдаёт случайный одноразовый nonce с пятиминутным сроком действия.
- `POST /v1/auth/providers/google` — принимает Google ID token; сервер проверяет подпись по Google JWKS, `iss`, `aud`, `exp`, алгоритм RS256, `sub` и одноразовый nonce.
- `POST /v1/auth/providers/yandex` — принимает OAuth access token; сервер получает профиль через `https://login.yandex.ru/info` с `Authorization: OAuth ...`, проверяет `client_id` и использует provider ID как subject.
- `POST /v1/auth/providers/vk` — принимает VK ID access token; сервер запрашивает профиль через `https://id.vk.ru/oauth2/user_info`, используя только серверный `VK_CLIENT_ID`.
- `POST /v1/auth/otp/request` — нормализует email/E.164 phone, ограничивает отправки, создаёт пятиминутный challenge и передаёт код настроенному HTTPS delivery endpoint.
- `POST /v1/auth/otp/verify` — проверяет одноразовый код, ограничивает число попыток, создаёт/находит пользователя по keyed contact hash и выдаёт MOMENTO session.
- `POST /v1/auth/link/otp/request` и `/verify` — подтверждённая привязка email/телефона к текущему аккаунту.
- `POST /v1/auth/link/providers/{google|yandex|vk}` — привязка внешней учётной записи только из действующей MOMENTO-сессии после проверки токена провайдера; чужой identity нельзя перехватить.

Контакты шифруются AES-256-GCM; для поиска используется отдельный HMAC key; OTP хранится только как HMAC. Ошибочный OTP не раскрывает, существует ли аккаунт. Совпадающий email из social profile не связывает аккаунты автоматически. Вход по email и телефону создаёт/использует отдельный подтверждённый contact identity. Все пять каналов можно привязать к текущему аккаунту через профиль; совпадающие email claims сами по себе аккаунты не объединяют.

### Важные ограничения 0.12.0

- Android Compose экран входа подключён к Google Credential Manager, Yandex ID Android SDK и официальному VK ID Android SDK; email/phone OTP подключены к backend.
- Добавлено encrypted token storage через Android Keystore, refresh при старте приложения и logout.
- Добавлена UI-привязка всех пяти каналов из профиля: Google, Yandex ID, VK ID, email и телефон. Для контактов требуется OTP; для социальных провайдеров — свежая provider authorization и активная MOMENTO-сессия.
- Для реального входа всё ещё требуются зарегистрированные provider apps, client IDs и server credentials; без них live login не проверен.
- Email/SMS отправка работает через HTTPS JSON adapter contract, но конкретный transactional email/SMS provider не выбран и не настроен.
- Реальная PostgreSQL миграция и integration tests, Android build/device tests, live provider sandbox tests не запускались.
