# TGU — Telegram Unlocker

Публичный репозиторий исходного кода TGU для Android и Windows.

RNMDTEAM · публичные сборки для тестирования

## Исходный код

| Платформа | Каталог | Технологии |
|---|---|---|
| Android | [android/](android/) | Kotlin, Jetpack Compose, Android SDK |
| Windows | [desktop/](desktop/) | Tauri 2, Rust, React, TypeScript |

## Загрузки

- [Все версии TGU](https://github.com/sergeyrenamed-coder/TGU-Public/releases)
- [Android 0.9.4](https://github.com/sergeyrenamed-coder/TGU-Public/releases/tag/android-v0.9.4)
- [Windows 0.3.0](https://github.com/sergeyrenamed-coder/TGU-Public/releases/tag/windows-v0.3.0)
- [RNMDSTORE](https://rnmdstore.netlify.app/#/tgu)
- [TGU в Telegram](https://t.me/RNMDSTOREbot)

Android APK публикуется как debug-сборка для тестирования. Для установки поверх другой подписанной версии может потребоваться удалить старую версию. Windows EXE пока не подписан сертификатом издателя.

## Возможности

TGU помогает диагностировать подключение Telegram и использовать локальный прокси-маршрут с поддержкой классических MTProto- и SOCKS5-upstream. Приложение не создаёт системный VPN и не перенаправляет трафик остальных программ.

Подробные сведения о возможностях и ограничениях находятся в README соответствующей платформы.

## Сборки

GitHub Actions проверяет Android-сборку и unit-тесты, TypeScript, Rust-тесты и Windows-сборку. После успешного завершения создаются отдельные prerelease-версии Android и Windows.

Для Android в CI можно настроить постоянную debug-подпись через секреты репозитория TGU_DEBUG_KEYSTORE_BASE64 и TGU_DEBUG_KEYSTORE_PASSWORD. Скрипт настройки находится в android/scripts/setup-tgu-signing.ps1.

## TGU+

TGU+ находится в разработке. Заявка на ранний доступ не является покупкой; оплата за неё не взимается.

## Лицензия

Основной проект распространяется по Apache License 2.0. Уведомления и лицензии сторонних компонентов сохранены в каталогах third_party/licenses.
