# AnyStyle как четвёртый метод

Java-адаптер вызывает локальный Ruby API AnyStyle 1.6.0 со стандартной моделью и одним потоком, без обучения на нашем наборе. Внешнего поиска и обогащения нет. Ядро сервиса и оценщик остаются на Java; Ruby требуется официальному парсеру. Основание: [AnyStyle](https://github.com/inukshuk/anystyle) и [CLI](https://github.com/inukshuk/anystyle-cli).

## Установка

Нужны Ruby, RubyGems и инструменты сборки нативного расширения Wapiti. На данном Mac используется уже установленный Homebrew Ruby 4.0.1. Из корня проекта:

```sh
bash scripts/install-anystyle.sh
```

Установщик фиксирует AnyStyle 1.6.0, anystyle-data 1.3.0 и CLI 1.5.0. Gems устанавливаются отдельно в `$HOME/.local/share/bibliography-anystyle/gems`. Пути можно изменить через `ANYSTYLE_RUBY` и `ANYSTYLE_GEM_HOME`. Системные gems не изменяются; Ruby самостоятельно не устанавливается. Обёртка рассчитана на macOS/Linux, нативный Windows не проверялся.

## Вход и отображение полей

Процесс получает только `citation`, без ID, эталона, профиля и части набора. Внутренние переводы строк становятся пробелами, чтобы одна ссылка не превратилась в несколько. Вывод сохраняется целиком в `rawResponse`.

`author` отображается в упорядоченный список фамилий и инициалов. `title`, `container-title`, `date`, `volume`, `issue`, `pages`, `publisher`, `location`, `edition`, `isbn`, `url`, `accessed`, `doi`, `edn` соответствуют полям общей схемы. Год выделяется только из возвращённого `date`. Тип выводится из `type`; неизвестный тип остаётся пустым. Роль страниц — общий объём для книги с одним числом, иначе указатель страниц.

`note` и `editor` не используются для восстановления EDN/страниц/авторов. Ошибки AnyStyle, включая перестановку фамилии и инициалов, сохраняются. Регулярные выражения над исходной строкой не дополняют ответ. Пропущенные поля остаются пустыми. Нормализация, маска неоднозначностей и форматтер те же, что для остальных методов.

Процесс переиспользует модель между ссылками. Первичная загрузка модели и проверка файлов входят в подготовку среды, а не в длительность отдельного вызова. Сохраняются версии Ruby и зависимостей, SHA модели, файлов библиотек/словаря, мостика и команды запуска. Изменение среды между DEV и TEST отклоняется. Тайм-ауты и ошибки не исключаются из оценки.

## Запуск и фиксация

Ниже команды воспроизведения. **Уже полученный TEST повторять не требуется.** Все выходные папки должны быть новыми. Из `backend` после сборки `./mvnw package`:

```sh
java -jar target/bibliography-1.0.0.jar \
  --parse-dataset=../датасет.xlsx --method=anystyle --split=dev \
  --output=../research-runs/НОВЫЙ-ANYSTYLE-DEV

java -jar target/bibliography-1.0.0.jar \
  --evaluate-run=../research-runs/НОВЫЙ-ANYSTYLE-DEV --dataset=../датасет.xlsx

java -jar target/bibliography-1.0.0.jar \
  --freeze-experiment=../research-runs/НОВЫЙ-ANYSTYLE-DEV --dataset=../датасет.xlsx

java -jar ../research-runs/НОВЫЙ-ANYSTYLE-DEV/frozen-protocol/application.jar \
  --parse-dataset=../датасет.xlsx --method=anystyle --split=test \
  --protocol=../research-runs/НОВЫЙ-ANYSTYLE-DEV/frozen-protocol \
  --output=../research-runs/НОВЫЙ-ANYSTYLE-TEST

java -jar ../research-runs/НОВЫЙ-ANYSTYLE-DEV/frozen-protocol/application.jar \
  --evaluate-run=../research-runs/НОВЫЙ-ANYSTYLE-TEST --dataset=../датасет.xlsx

java -jar ../research-runs/НОВЫЙ-ANYSTYLE-DEV/frozen-protocol/application.jar \
  --format-run=../research-runs/НОВЫЙ-ANYSTYLE-TEST --dataset=../датасет.xlsx

java -jar ../research-runs/НОВЫЙ-ANYSTYLE-DEV/frozen-protocol/application.jar \
  --quality-run=../research-runs/НОВЫЙ-ANYSTYLE-TEST --dataset=../датасет.xlsx
```

`--anystyle-command` выбирает другой исполняемый мостик вместо `../scripts/anystyle-worker.sh`. Команда передаётся в ProcessBuilder без shell-интерполяции. `--method=all` запускает все четыре метода вместе. `--method=anystyle` позволяет проверить один метод. Для нового общего эксперимента сначала выполните полный DEV с `--method=all`, зафиксируйте протокол и только затем запускайте TEST с тем же параметром. Архивные JAR сохраняют поведение своего эксперимента.

## Объединение сравнения

AnyStyle добавлен после просмотра первого TEST трёх методов. Его конфигурация зафиксирована после полного DEV и до собственного TEST. Нельзя представлять это как один заранее зарегистрированный четырёхметодный запуск. Настройки на TEST не подбирались.

Из `backend`:

```sh
java -jar target/bibliography-1.0.0.jar \
  --compare-runs=../research-runs/final-test-20261029 \
  --additional-run=../research-runs/anystyle-test-20261029 \
  --dataset=../датасет.xlsx \
  --comparison-output=../research-runs/НОВОЕ-СРАВНЕНИЕ-4-МЕТОДОВ
```

Команда не запускает парсеры и не меняет архивы. Проверяются SHA ответов/построчных сравнений, протоколы и JAR, одинаковый набор и контекст оформления, неизменность общей оценки/нормализации/форматтера. Метрики заново вычисляются из сохранённых ответов и должны точно совпасть с архивными. Допускается изменение только класса составления таблицы, не методов или оценщика.

Результат содержит `evaluation/metrics.json`, `field_metrics.csv`, `comparison.csv`, `report.md`, полный `records.jsonl` на 1632 пары, исходные ответы и результаты форматирования. Это составное сравнение, не новый `experiment-run-v2`; оно не передаётся обычным `--evaluate-run`/`--quality-run`. Предметная проверка выполняется по двум исходным прогонам с их архивными JAR. Старый трёхметодный прогон также продолжает оцениваться только своим архивным JAR.

## Результаты 29 октября 2026 года

- DEV: 92 SUCCESS, Micro F1 0,4681, полное совпадение 0/91.
- TEST: 408 SUCCESS, Micro F1 0,4771, precision 0,5530, recall 0,4195; полное совпадение 2/403 (0,50%).
- TEST: TP 1663, FP 1344, FN 2301; 119 EXTRA и 1225 WRONG.
- Форматтер: 121 FORMATTED и 287 NEEDS_REVIEW. Это технические статусы, не доля ГОСТ-корректных ссылок. В опубликованном архиве сохранён исходный незаполненный бланк; последующая проверка оформления ИИ и сверка человеком учитываются отдельно.
- Архивы: `research-runs/anystyle-dev-20261029`, `anystyle-test-20261029`, `four-methods-test-20261029`. Локальные папки игнорируются Git. Их публикуемые копии — `results/anystyle-dev-20261029`, `results/anystyle-test-20261029` и `results/four-methods-test-20261029`; в каждой есть SHA256SUMS. Ранее опубликованный `results/final-test-20261029` сохраняет три исходных метода без изменений.
