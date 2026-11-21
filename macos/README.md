# macOS app — сборка

Приложение собирается скриптом `build.sh` напрямую через `swiftc`:

```bash
./build.sh          # -> dist/MyBooks.app
open dist/MyBooks.app
```

Требуется `Shared.framework` в этой директории — универсальный (arm64 + x86_64)
статический Kotlin/Native фреймворк из артефакта `shared-framework` workflow `macOS`.

## Хранилища в ветках GitHub

Кнопка «Хранилища» в шапке библиотеки открывает экран шифрованных хранилищ:

- нужен fine-grained token с правом **Contents: Read and write** на репозиторий;
- хранилище — это ветка; в корне `vault.enc` (шифрованный манифест), в `books/<id>.enc` — книги;
- формат конверта и манифеста совпадает с Kotlin-версией (PBKDF2-HMAC-SHA256, 200 000
  итераций → AES-256-GCM), поэтому ветку можно открыть на Windows и Android;
- токен и пароли хранятся в связке ключей macOS, service `io.github.evgenykon.mybooks.vault`;
- изменения библиотеки уезжают в активную ветку автоматически через ~1.5 с после правки.

Крипто использует `CommonCrypto` (PBKDF2) и `CryptoKit` (AES-GCM); при отсутствии модуля
`CommonCrypto` включается резервная реализация PBKDF2 на HMAC-SHA256 из CryptoKit.
