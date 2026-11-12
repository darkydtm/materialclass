# MaterialClass

Нативный Android-клиент BilimClass. Код не подставляет демонстрационные школьные данные: неподтверждённые серверные источники остаются типизированным `UnsupportedContract`.

## Структура

- `.github/workflows/` - read-only CI для unit tests, lint, debug APK и connected debug tests.
- `app/src/main/java/org/materialclass/api/` - подтверждённый API-адаптер и границы неподтверждённых контрактов.
- `app/src/main/java/org/materialclass/data/` - доменные модели и репозитории.
- `app/src/main/java/org/materialclass/session/`, `security/`, `cache/` - сессия, защищённое хранилище и зашифрованный кэш.
- `app/src/main/java/org/materialclass/ui/` - Compose-экраны и тема Material 3 Expressive.
- `app/src/main/res/` - ресурсы, локализация и правила резервного копирования.
- `app/src/test/`, `app/src/androidTest/` - unit- и instrumented-тесты.
- `docs/` - зависимости и release-проверка.

## Проверка

Release/integration evidence, known limitations, CI status and the separation between synthetic tests and real read-only API observations are recorded in [`docs/release-verification.md`](docs/release-verification.md).

Подробные этапы и ограничения реализации описаны во внутреннем плане `internal/docs/design/android-client-plan.md`; этот файл не включён в Git. Toolchain зафиксирован в `docs/dependencies.md`; локальная тяжёлая Android-сборка не выполняется, её проверяет CI. Не выполняйте release signing, публикацию или распространение APK из этого checkout без отдельного разрешения.
