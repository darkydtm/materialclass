# MaterialClass

Нативный Android-клиент BilimClass. План проекта описывает Kotlin, Jetpack Compose и Material 3 Expressive; проверка протоколов API, выбор совместимых версий toolchain, сборка APK и CI ещё не выполнены. Не используйте неподтверждённые API или демонстрационные школьные данные.

## Структура

- `.github/workflows/` - CI после выбора и проверки Android toolchain.
- `app/src/main/java/org/materialclass/api/` - подтверждённый API-адаптер.
- `app/src/main/java/org/materialclass/data/` - доменные модели и репозитории.
- `app/src/main/java/org/materialclass/session/`, `security/`, `cache/` - сессия, защищённое хранилище и зашифрованный кэш.
- `app/src/main/java/org/materialclass/ui/` - Compose-экраны и тема Material 3 Expressive.
- `app/src/main/res/` - ресурсы, локализация и правила резервного копирования.
- `app/src/test/`, `app/src/androidTest/` - unit- и instrumented-тесты.
- `docs/` - зависимости и проверка сборки.

Подробные этапы и ограничения реализации описаны во внутреннем плане `internal/docs/design/android-client-plan.md`; этот файл не включён в Git. До выбора совместимого toolchain Gradle-проект не настроен, APK ещё не собирается.
