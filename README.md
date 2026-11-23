# Hgram

**Hgram** — неофициальный клиент Telegram для Android: форк [Nagram](https://github.com/NextAlone/Nagram),
который, в свою очередь, основан на Telegram for Android и NekoX. Hgram добавляет собственные функции
(суммаризация сообщений, склейка коротких сообщений, анти-подглядывание, офлайн-расшифровка голосовых
и другое) и развивает приватность и повседневные мелочи интерфейса.

> **Актуальная версия:** 13.0.2 (сборка 1311) — 23 ноября 2026 г.

- Релизы и APK: <https://github.com/hottabbe/Hgram/releases>
- Канал обновлений: <https://t.me/hgram_updates>
- Исходный код: <https://github.com/hottabbe/Hgram>

Hgram не связан с Telegram Messenger LLP и не является официальным клиентом. Используйте на свой риск:
сторонние клиенты могут привести к ограничениям аккаунта со стороны Telegram.

## Установка

1. Скачайте APK из [последнего релиза](https://github.com/hottabbe/Hgram/releases/latest).
2. Разрешите установку из неизвестных источников для файлового менеджера или браузера.
3. Установите APK и войдите в свой аккаунт Telegram.

Параметры текущей сборки:

| Параметр | Значение |
|---|---|
| Версия | 13.0.2 (1307) |
| ABI | `arm64-v8a` (64-битный ARM) |
| Размер APK | 89 652 629 байт (~85,5 МиБ) |
| Минимальная версия Android | 6.0 (API 23) |
| Целевая версия Android | 16 (API 36) |

APK подписан ключом проекта, поэтому обновления ставятся поверх предыдущих версий Hgram. Поверх
официального Telegram или других форков установка не пройдёт — потребуется удалить прежний клиент
(история чатов при этом останется на сервере, локальный кеш будет потерян).

## Что внутри

### Суммаризация сообщений

- Кнопка «пересказать» у пузыря появляется, если в сообщении не меньше заданного числа слов.
- Пересказ делает выбранная модель [OpenRouter](https://openrouter.ai/): по умолчанию бесплатная,
  при желании можно включить платные модели — в списке видна цена за 1 млн токенов.
- Настройки: **Hgram → Сообщения** — включение, порог появления кнопки, ключ OpenRouter, выбор модели
  (список загружается по вашему ключу) и редактируемый системный промпт.

### Склеенные цепочки сообщений

- Несколько коротких сообщений подряд рисуются одним пузырём с общей рамкой и градиентом у своих
  сообщений; раскрытую цепочку можно свернуть обратно тапом по верхней части.
- Три состояния галочек: не прочитано, частично прочитано, прочитано.
- Это только отображение: идентификаторы, авторы и хранение сообщений не меняются.

### Приватность

- **Анти-подглядывание** — жест «перевернуть телефон экраном вниз» прячет содержимое: список чатов,
  шапку и текст чата, закреплённое сообщение, профиль и звонки. Есть настройка чувствительности,
  уведомление и вибрация при срабатывании, а также «Показать на 5 секунд» из меню чата.
- **Скрытое пространство** — чаты можно спрятать во второе пространство, вход в него — по отдельному
  decoy-PIN; медиа-кеш скрытых чатов шифруется ключом Android Keystore, скрытые чаты не появляются
  в поиске, счётчиках, уведомлениях, звонках и виджетах.
- **Ghost mode** (наследие NekoX) — не отправлять «прочитано», online, прогресс загрузки и просмотр
  историй, с разовым разрешением «прочитать» по запросу.
- Пароль на каждый аккаунт, паник-код, скрытие аккаунта и самого раздела настроек.
- OpenPGP-шифрование сообщений через OpenKeychain и regex-фильтры сообщений.

### Уведомления

- Превью медиа в уведомлениях: корректные кадры GIF и видео, без обрезки, самая свежая картинка из серии.
  Картинка берётся только из кеша — в сеть за превью приложение не ходит.
- Группировка повторных уведомлений от одного отправителя, выбор стиля превью (системный или свой),
  стабильные каналы уведомлений.
- Сообщение из push сразу появляется в чате и списке — ещё до полной синхронизации, затем заменяется
  настоящим без дубля.
- Кнопки «Слушать» и «Текст» прямо в уведомлении голосового сообщения.

### Голосовые сообщения и расшифровка

- Офлайн-расшифровка голосовых и видео-кружков: аудио не покидает устройство. Движки — Vosk (RU/EN),
  sherpa-onnx (Zipformer RU) и GigaAM v3; модели скачиваются по запросу из настроек.
- Расшифровка выполняется в отдельном процессе, поэтому большие модели не раздувают память основного.
- Выравнивание громкости голосовых (компрессор + лимитер) без перекодирования файла.

### Распознавание текста на фото

- Долгое нажатие на снимок в просмотрщике включает режим выделения: текст распознаётся на устройстве
  и сразу выделяется целиком, работает системное «Копировать».
- Языки — русский и английский. Движок и языковые модели не входят в APK: при первом использовании
  модели скачиваются по запросу (≈ 8 МБ), скачивание можно отменить и продолжить позже.
- Настройка: **Hgram → Сообщения** — включение, статус моделей и кнопки «Скачать» / «Отменить» / «Удалить».
- Защищённые фото (запрет пересылки, секретные чаты, защищённые аватары) не отдают текст в буфер обмена.

### Интерфейс и повседневные мелочи

- Закрепление сообщения «поверх экрана» в списке чатов.
- Компактные альбомы: стопка со свайпом и счётчиком.
- Лента: свёртка похожих постов, скрытие рекламы и кликбейта, свои ключевые слова.
- Единые настройки силы и длительности вибрации для переключателей, свайпов, анти-подглядывания и др.
- Десять вариантов иконки приложения, стили вкладок, Material 3-шапка чата, liquid glass, Monet.
- Ограничение анимаций до 60 fps и слабый режим сети для экономии заряда и трафика.

### Наследие NekoX/Nagram

Перевод сообщений (12 провайдеров, включая LLM через OpenAI, Gemini, DeepSeek, OpenRouter и другие),
авто-перевод по чату, двойной тап по сообщению с настраиваемым действием, компактное меню сообщения,
размер стикеров, свои стили текста, экспорт сессии (QR / Pyrogram / Telethon), вход по passkey,
UnifiedPush, системные AI-сервисы HyperOS/ColorOS, импорт и экспорт настроек.

### Настройки Hgram

Отдельный экран **Hgram settings** (вход из настроек Neko) с разделами «Чаты», «Лента», «Аудио»,
«Транскрипция», «Приватность», «Сеть», «Прочее», поиском по строкам и сбросом только Hgram-настроек.

## Сборка из исходников

Требования:

- JDK 21 (подойдёт JetBrains Runtime 21, например `jbr-21`);
- Android SDK: platform 36 и Build Tools 36.0.0;
- Android NDK `29.0.14206865`, CMake `3.22.1`;
- Git с поддержкой submodules и доступ в сеть для Gradle/Maven и моделей распознавания речи.

Gradle wrapper сам скачает нужную версию Gradle.

```sh
git clone --recurse-submodules https://github.com/hottabbe/Hgram.git
cd Hgram
git submodule update --init --recursive
```

Создайте в корне `local.properties` со своим путём к SDK и **своими** API-ключами Telegram
(получаются на <https://my.telegram.org>):

```properties
sdk.dir=/path/to/Android/Sdk
TELEGRAM_APP_ID=your_app_id
TELEGRAM_APP_HASH=your_app_hash
```

`local.properties`, ключи API и хранилища подписи в репозиторий не коммитятся.

Сборка релизного APK под arm64-v8a:

```sh
export JAVA_HOME=/path/to/jbr-21
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew :TMessagesProj:assembleRelease -PnativeTarget=arm64-v8a
```

Отладочная сборка и быстрая проверка компиляции Java:

```sh
./gradlew :TMessagesProj:assembleDebug
./gradlew :TMessagesProj:compileDebugJavaWithJavac
```

Для релиза нужен собственный keystore: не используйте подпись другого издателя и не публикуйте
ключи. Готовые APK появляются в `TMessagesProj/build/outputs/apk/`.

## Структура проекта

- `TMessagesProj/src/main/java/org/telegram/` — клиент Telegram и MTProto (`tgnet`);
- `TMessagesProj/src/main/java/xyz/nextalone/nagram/hgram/` — функции Hgram;
- `TMessagesProj/src/main/java/tw/nekomimi/nekogram/` — настройки и функции NekoX/Nagram;
- `TMessagesProj/jni/` — нативные модули (включая подавление шума и распознавание речи);
- `TMessagesProj/src/main/res/` — ресурсы и строки локализации.

## Лицензия и авторы

Проект распространяется по лицензии [GNU GPL v3](LICENSE), как и его upstream-проекты. При
распространении изменённых сборок сохраняйте уведомления об авторских правах и текст лицензии.

Hgram основан на работе других проектов:

- **Telegram for Android** — <https://github.com/DrKLO/Telegram> (Telegram Messenger LLP);
- **Nagram** — <https://github.com/NextAlone/Nagram> ([@NextAlone](https://github.com/NextAlone));
- **NekoX** — <https://github.com/NekoX-Dev/NekoX>.

Имя, логотипы и иконки Nagram — бренд-ассеты их владельцев (иконки — © MaitungTM). Hgram использует
собственное имя и собственные иконки; см. [BRANDING.md](BRANDING.md). Упоминания upstream-проектов
носят информационный характер и не означают их одобрения или поддержки Hgram.

Текст распознавания не покидает устройство и не сохраняется: результат только показывается и, по желанию,
копируется в буфер обмена.

### Сторонние компоненты OCR

Распознавание текста на фото использует [Tesseract4Android](https://github.com/adaptech-cz/Tesseract4Android)
4.8.0 (Apache License 2.0) и языковые модели `rus`/`eng` из
[tesseract-ocr/tessdata_fast](https://github.com/tesseract-ocr/tessdata_fast) (Apache License 2.0).

Документация предыдущих версий сохранена в [README.legacy.md](README.legacy.md),
[README.Nagram.md](README.Nagram.md) и [README.NekoX.md](README.NekoX.md).

## English summary

Hgram is an unofficial open-source Telegram client for Android, forked from Nagram (which builds on
Telegram for Android and NekoX). Current release: **13.0.2 (1311)**, 23 November 2026, arm64-v8a,
Android 6.0+. Highlights: on-device photo text recognition (long-press a photo, models downloaded on
demand), OpenRouter-powered message summarization with selectable models and pricing, merged
short-message chains, anti-peek masking triggered by flipping the phone, a hidden space with a decoy
PIN and encrypted media cache, media previews in notifications, offline voice transcription
(Vosk / sherpa-onnx / GigaAM), haptic effect types, and the usual NekoX/Nagram customization.

Downloads: <https://github.com/hottabbe/Hgram/releases> · Channel: <https://t.me/hgram_updates>

Build with JDK 21: `./gradlew :TMessagesProj:assembleRelease -PnativeTarget=arm64-v8a`.
Licensed under GPLv3. Not affiliated with Telegram Messenger LLP.
