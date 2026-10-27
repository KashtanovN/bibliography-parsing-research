# Развёртывание Java-сервера

## Новый сервер

1. Установите Docker Engine и Compose.
2. Создайте `.env` из `.env.example`, задайте случайный `POSTGRES_PASSWORD`, например из `openssl rand -hex 32`.
3. Выполните `docker compose up --build -d`.
4. Проверьте `docker compose ps` и `curl --fail http://127.0.0.1:8080/api/health/`.
5. Направьте университетский HTTPS-прокси на локальный порт 8080.

Backend запускает Java 17 и встроенный Tomcat на порту 8000. PostgreSQL и backend не публикуют порты на хосте. JAR запускается от отдельного непривилегированного пользователя. Flyway применяет SQL-миграции, Hibernate проверяет структуру таблицы; стартовые правила добавляются только при отсутствии записи.

Пример блока внутри настроенного университетского HTTPS virtual host:

```nginx
location / {
    proxy_pass http://127.0.0.1:8080;
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_set_header X-Real-IP $remote_addr;
}
```

Сертификат и домен предоставляет университет. Пример предполагает корневой путь `/`. Для подпути нужно согласовать `VITE_BASE_PATH`, `VITE_API_BASE` и конфигурацию Nginx.

## Переменные окружения

| Переменная | Значение / назначение |
|---|---|
| `POSTGRES_HOST`, `POSTGRES_PORT` | По умолчанию localhost:5432, в Compose db:5432 |
| `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD` | Доступ к PostgreSQL |
| `DATABASE_URL` | Необязательная замена URL целиком: `jdbc:postgresql://host:5432/database` |
| `SERVER_PORT`, `SERVER_ADDRESS` | По умолчанию 8000, 127.0.0.1; в контейнере 0.0.0.0 |
| `CROSSREF_MAILTO` | Необязательный технический контакт университета |
| `TRUST_PROXY_HEADERS` | По умолчанию false; в закрытой сети Compose true |

Java ограничивает запросы до 120 в минуту на клиента и импорт до 20 в минуту, тело запроса до 32 КиБ. Эти же лимиты применяет Nginx. Кеш и серверные счётчики локальны для JVM; при нескольких экземплярах общий лимит должен обеспечивать входной прокси. По умолчанию Java использует адрес соединения; в изолированной сети Compose доверяет `X-Real-IP`, который Nginx перезаписывает. При прямом открытии backend наружу оставьте `TRUST_PROXY_HEADERS=false`.

За университетским прокси Nginx видит его адрес. Для индивидуальных лимитов настройте real_ip только для известных доверенных прокси университета.

## Обновление правил

Сначала сделайте резервную копию. Подготовьте проверенный JSON с тем же `id`:

```sh
docker compose cp ./backend/src/main/resources/gost705.json backend:/tmp/standard.json
docker compose exec backend java -jar /app/app.jar --load-standard=/tmp/standard.json
```

Команда запускается без HTTP-сервера, проверяет схему, заменяет запись и завершается. Ошибочная схема не записывается. Сервер читает правила из БД при каждом запросе; перезапуск не нужен. Для новой схемы полей обновите страницу браузера.

Без Docker:

```sh
cd backend
./mvnw package
java -jar target/bibliography-1.0.0.jar --load-standard=/absolute/path/to/standard.json
```

## Резервное копирование

```sh
docker compose exec -T db pg_dump -U bibliography bibliography > bibliography-backup.sql
```

Сохраняйте дампы отдельно от репозитория. `docker compose down` сохраняет том. Параметр `down -v` удаляет данные и для обновления не нужен.

## Внешние данные и встраивание

Нужен исходящий HTTPS к api.crossref.org и openlibrary.org. Внешние запросы имеют тайм-аут подключения 5 секунд, ожидания ответа 20 секунд и общий предел 25 секунд, размер ответа до 2 МБ. Редиректы не выполняются; успешные метаданные кешируются на 15 минут, до 1000 записей. Произвольные пользовательские URL сервер не загружает. eLIBRARY/EDN требуют отдельного согласованного адаптера.

`/?embedded=1` скрывает университетскую шапку и подвал. В CSP разрешён родитель `https://ssau.ru`; другие домены требуют настройки. Clipboard API требует HTTPS или localhost. Авторизация и интеграция LDAP/SSAU_ID пока отсутствуют.

Логотип и шрифты в `frontend/public/brand/` сохранены; тексты лицензий находятся рядом. Само размещение приложения не означает официального одобрения университетом или экспертизы правил библиотекой.
