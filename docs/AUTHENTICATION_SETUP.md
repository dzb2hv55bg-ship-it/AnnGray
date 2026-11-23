# MOMENTO — подключение способов входа

## 1. Backend secrets

Скопировать `backend/.env.example` в `.env`. Сгенерировать независимые значения:

```bash
openssl rand -base64 48 # MOMENTO_JWT_SECRET
openssl rand -base64 48 # REFRESH_TOKEN_PEPPER
openssl rand -base64 32 # OTP_HMAC_SECRET
openssl rand -base64 32 # CONTACT_HASH_SECRET
openssl rand -base64 32 # CONTACT_ENCRYPTION_KEY
```

Каждый вызов генерирует новое значение. `CONTACT_ENCRYPTION_KEY` должен быть ровно 32 байта после Base64-декодирования. Не коммитить `.env`, provider secrets или ключи в Git/APK.

Обязательные backend параметры: `DATABASE_URL`, `PUBLIC_APP_URL`, `MOMENTO_JWT_SECRET`, `MOMENTO_JWT_ISSUER`, `MOMENTO_JWT_AUDIENCE`, `REFRESH_TOKEN_PEPPER`.

## 2. Google

1. Создать OAuth clients в Google Cloud Console: Android client для package `ru.momento.app` и server/Web client для ID token audience.
2. В Android build передать `-PgoogleServerClientId=<WEB_CLIENT_ID>`.
3. В backend задать `GOOGLE_SERVER_CLIENT_ID` тем же Web client ID.
4. Android использует Credential Manager, запрашивает ID token с одноразовым nonce; backend проверяет подпись, issuer, audience, expiry и nonce.

Official references:
- https://developer.android.com/identity/sign-in/credential-manager-siwg-implementation
- https://developers.google.com/identity/sign-in/android/backend-auth

## 3. Yandex ID

1. Создать приложение в Yandex OAuth, выбрать Android platform, указать package `ru.momento.app` и SHA-256 signing fingerprints для debug/release сертификатов.
2. В Android build передать `-PyandexClientId=<YANDEX_CLIENT_ID>`.
3. В backend задать `YANDEX_CLIENT_ID` тем же значением.
4. Android SDK получает OAuth token; MOMENTO backend проверяет его через Yandex ID user-info endpoint и сравнивает `client_id`.

Official references:
- https://yandex.ru/dev/id/doc/en/mobileauthsdk/android/3.1.3/sdk-android
- https://yandex.ru/dev/id/doc/en/mobileauthsdk/android/3.1.3/sdk-android-install
- https://yandex.ru/dev/id/doc/en/mobileauthsdk/android/3.1.3/sdk-android-use
- https://www.yandex.ru/dev/id/doc/en/user-information

## 4. VK ID

1. Создать приложение в кабинете VK ID.
2. Передать Android build параметры `-PvkClientId=<APP_ID>` и `-PvkClientSecret=<APP_PROTECTED_KEY>` из локальной Gradle-конфигурации/CI secrets; не сохранять реальные значения в репозитории.
3. В backend задать `VK_CLIENT_ID` равным app ID.
4. Android использует официальный VK ID Android SDK 2.7.3; backend валидирует access token запросом к `https://id.vk.ru/oauth2/user_info`.

Важно: VK SDK просит protected key через manifest placeholder. Значение, попавшее в APK, технически можно извлечь; его нельзя считать секретом, защищённым от пользователя устройства. Перед production release отдельно проверить доступный в VK ID SDK public-client/PKCE flow и требования VK к хранению client secret.

Official references:
- https://github.com/VKCOM/vkid-android-sdk
- https://github.com/VKCOM/vkid-android-sdk/releases
- https://vkcom.github.io/vkid-android-sdk/

## 5. Email и SMS

MOMENTO использует собственные OTP challenges. Нужно выбрать transactional email и SMS vendors, реализовать для каждого HTTPS endpoint, принимающий JSON:

```json
{
  "to": "recipient",
  "channel": "email or sms",
  "template": "momento-sign-in-code",
  "code": "123456",
  "expiresInMinutes": 5
}
```

Настроить `EMAIL_DELIVERY_URL`, `EMAIL_DELIVERY_API_KEY`, `SMS_DELIVERY_URL`, `SMS_DELIVERY_API_KEY`. Endpoint должен вернуть HTTP 2xx после принятия сообщения к доставке. Код не должен попадать в логи провайдера или приложения. В текущем MVP лимиты: 60 секунд между отправками на один контакт, до 5 отправок за час, 5 попыток проверки одного challenge, срок действия 5 минут.

## 6. Android build

Пример:

```bash
./gradlew :app:assembleDebug \
  -PmomentoApiBaseUrl=https://YOUR_API_HOST \
  -PgoogleServerClientId=YOUR_GOOGLE_WEB_CLIENT_ID \
  -PyandexClientId=YOUR_YANDEX_CLIENT_ID \
  -PvkClientId=YOUR_VK_APP_ID \
  -PvkClientSecret=YOUR_VK_PROTECTED_KEY
```

Реальные значения не вставлять в документацию или коммиты. Для CI использовать защищённые secrets.

## 7. Проверка перед выпуском

- Применить migrations 001, 002, 003, 004 на чистой PostgreSQL test DB.
- Запустить `npm ci`, `npm test`, `npm run check`.
- Проверить все пять способов на реальных тестовых аккаунтах.
- Проверить вход с одинаковым email в разных providers: аккаунты не должны автоматически объединяться.
- Проверить привязку нового метода из действующей MOMENTO-сессии и конфликт identity, уже принадлежащего другому пользователю.
- Проверить OTP rate limits, неправильный/истёкший/повторно использованный код, недоступность доставки.
- Проверить refresh rotation, replay, logout, повторный запуск приложения и восстановление сессии.
- Собрать debug/release APK, проверить signing fingerprints и deep links на физических Android устройствах.
- До production выпуска проверить политику обработки детских данных и legal/privacy документы.


## 8. Session refresh behavior in Android 0.13.0

The app stores MOMENTO access/refresh tokens encrypted using Android Keystore. While the process is alive, it schedules refresh 60 seconds before access-token expiry. Temporary network/5xx failures are retried every 15 seconds; a definitive HTTP 401 invalidates the local session. Process restart re-reads the encrypted session and recalculates the refresh time.

This client-side scheduling does not replace server-side session checks, refresh rotation, replay detection or logout revocation.
