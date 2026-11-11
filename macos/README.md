# macOS app — сборка

Приложение собирается скриптом `build.sh` напрямую через `swiftc`:

```bash
./build.sh          # -> dist/MyBooks.app
open dist/MyBooks.app
```

Требуется `Shared.framework` в этой директории — универсальный (arm64 + x86_64)
статический Kotlin/Native фреймворк из артефакта `shared-framework` workflow `macOS`.
