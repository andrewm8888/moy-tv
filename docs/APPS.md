# Приложения

Что стоит поставить на телевизор с YaOS. Лаунчер работает и без них, но часть приложений он показывает на главном экране сразу после установки.

Телевизор 32-битный: если на странице загрузки есть выбор, берите APK для **armeabi-v7a** (или universal). Скачанный APK ставится так:

```
bash scripts/add-apk.sh ~/Downloads/файл.apk
```

На Windows перетащите APK-файл мышью на `windows\add-apk.bat`.

## Магазин

| Приложение | Зачем | Где взять |
|---|---|---|
| RuStore TV | Магазин приложений для ТВ. Через него ставятся и обновляются Кинопоиск, Okko, VK Видео, Rutube и другие | [rustore.ru](https://www.rustore.ru/) (прямая ссылка на APK: [RuStoreTV.apk](https://static.rustore.ru/release/RuStoreTV.apk)) |

## Видео

| Приложение | Зачем | Где взять |
|---|---|---|
| SmartTube | Клиент YouTube для Android TV: без рекламы, с пропуском спонсорских вставок, работает на слабых ТВ | [github.com/yuliskov/SmartTube](https://github.com/yuliskov/SmartTube/releases), версия stable |
| Кинопоиск, Okko, VK Видео, Rutube | Онлайн-кинотеатры и видео | RuStore TV |
| VLC | Плеер для любых файлов и сетевых потоков | [videolan.org](https://www.videolan.org/vlc/download-android.html) |
| Lampa | Каталог фильмов и сериалов с карточками из TMDB, подключает внешние источники и плагины | [github.com/lampa-app/LAMPA](https://github.com/lampa-app/LAMPA/releases) |
| Prisma TV | Каталог фильмов и сериалов на основе Lampa | [github.com/Sheinices/Prisma_TV](https://github.com/Sheinices/Prisma_TV/releases) |
| TorrServe | Сервер, который воспроизводит торрент-файлы без полного скачивания. Нужен Lampa для торрент-источников | [github.com/YouROK/TorrServe](https://github.com/YouROK/TorrServe/releases) |
| LostFilm New TV | Неофициальный клиент LostFilm для Android TV | [github.com/kraaton11/new_lostfilmatv](https://github.com/kraaton11/new_lostfilmatv/releases) |

Какой контент смотреть и из каких источников, решаете вы. Lampa, Prisma TV и TorrServe показывают то, что найдут подключённые вами источники.

**LostFilm New TV.** Вход в аккаунт идёт через сервер разработчика приложения: e-mail и пароль проходят через него. Код открыт, пароль по коду не сохраняется, но проверить, что на сервере работает тот же код, нельзя. Используйте для LostFilm пароль, которого нет больше нигде.

## Сеть

| Приложение | Зачем | Где взять |
|---|---|---|
| Happ | Клиент прокси для протоколов VLESS, VMess, Trojan, Shadowsocks. Нужен свой сервер или подписка. Если Happ установлен, на главном экране «Мой ТВ» появляется переключатель с текущей страной и задержкой | [happ.su](https://www.happ.su/main) |

## Что удалить

Если стоят, лучше удалить: они дублируют друг друга и занимают память.

- Aptoide TV: сторонний магазин без проверки приложений, есть RuStore.
- Несколько копий YouTube (SmartTube Beta и Stable одновременно): оставьте одну.
