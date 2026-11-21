# My Books для Windows

Клиент на Compose Multiplatform (JVM). Использует тот же парсер FB2 и ту же модель
книги, что Android и macOS: `shared` собран в jvm-таргет `desktop`.

## Возможности

- Библиотека: импорт `.fb2` перетаскиванием в окно, через диалог (`Ctrl+O`) и из
  командной строки (`MyBooks.exe book.fb2`), поиск, удаление вместе с заметками.
- Читалка: пагинация по реальному layout текста (`TextMeasurer`), сохранение позиции
  по первому блоку страницы, процент прочитанного из ядра.
- Навигация: `←`/`→`, `PgUp`/`PgDn`, пробел, `Home`/`End`, колесо мыши;
  `Ctrl` + `+`/`-` — размер шрифта.
- Панели: содержание, заметки, настройки чтения (шрифт, размер, интервал, поля,
  тема, выравнивание).
- Выделение: `SelectionContainer` + `Ctrl+C`; правый клик по абзацу — «Копировать
  абзац», «Добавить заметку».

## Хранилища

Раздел «Хранилища» (`Ctrl+H`): токен GitHub, список ветвей-хранилищ, создание/открытие/удаление.
Активное хранилище синхронизирует библиотеку после каждого изменения. Токен и пароли
хранятся в `%LOCALAPPDATA%\MyBooks\secrets.dat`, зашифрованном DPAPI текущего пользователя.

## Данные

`%LOCALAPPDATA%\MyBooks\library`: `books.json`, `notes.json`, `reader-settings.json`
и по одному `.fb2` на книгу.

## Сборка и запуск

Сборки выполняются в GitHub Actions (workflow `Windows`), локально нужен JDK 17:

```bash
gradle :desktopApp:run                                  # запуск из исходников
gradle :desktopApp:createDistributable                  # MyBooks.exe со встроенной JRE
gradle :desktopApp:packageMsi                           # установщик MyBooks-1.0.0.msi
gradle :desktopApp:snapshot                             # PNG интерфейса в build/snapshots
```

MSI собирается через WiX, который скачивает плагин Compose; метаданные пакета
(`description`, `copyright`) должны быть в ASCII, иначе `light.exe` падает с кодом 311.
