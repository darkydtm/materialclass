# MaterialClass

Нативный Android-клиент BilimClass. Этап 04 содержит минимальный Kotlin/Compose launcher на Material 3 Expressive и безопасный CI; доменные модели, API и продуктовые экраны добавляются последующими этапами. Не используйте демонстрационные школьные данные.

## Структура

- `.github/workflows/` - read-only CI для unit tests, lint и debug APK.
- `app/src/main/java/org/materialclass/api/` - подтверждённый API-адаптер.
- `app/src/main/java/org/materialclass/data/` - доменные модели и репозитории.
- `app/src/main/java/org/materialclass/session/`, `security/`, `cache/` - сессия, защищённое хранилище и зашифрованный кэш.
- `app/src/main/java/org/materialclass/ui/` - Compose-экраны и тема Material 3 Expressive.
- `app/src/main/res/` - ресурсы, локализация и правила резервного копирования.
- `app/src/test/`, `app/src/androidTest/` - unit- и instrumented-тесты.
- `docs/` - зависимости и проверка сборки.

Подробные этапы и ограничения реализации описаны во внутреннем плане `internal/docs/design/android-client-plan.md`; этот файл не включён в Git. Toolchain зафиксирован в `docs/dependencies.md`; локальная тяжёлая Android-сборка не выполняется, её проверяет CI.
