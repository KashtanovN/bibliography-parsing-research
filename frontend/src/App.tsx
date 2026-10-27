import {
  useEffect,
  useRef,
  useState,
  type FormEvent,
  type KeyboardEvent,
} from "react";
import {
  Menu,
  CalendarDays,
  Eye,
  Globe,
  UserCircle,
  Search,
  Home,
  ChevronRight,
  Copy,
  Check,
  BookOpen,
  ArrowDownToLine,
  Info,
  LoaderCircle,
  ExternalLink,
  RotateCcw,
  X,
} from "lucide-react";
import {
  api,
  ApiError,
  type Schema,
  type Values,
  type Errors,
  type SourceConfig,
  type Imported,
} from "./api";
import styles from "./App.module.css";

const links = [
  "УНИВЕРСИТЕТ",
  "НОВОСТИ И СОБЫТИЯ",
  "ОБРАЗОВАНИЕ",
  "НАУКА",
  "РАЗВИТИЕ",
  "КОНТАКТЫ",
];
const today = () => new Date().toLocaleDateString("ru-RU");

function Header({
  contrast,
  setContrast,
}: {
  contrast: boolean;
  setContrast: (v: boolean) => void;
}) {
  const [menu, setMenu] = useState(false);
  return (
    <header>
      <div className={styles.utility}>
        <div className={styles.container}>
          <button
            className={styles.iconButton}
            aria-label={menu ? "Закрыть меню" : "Открыть меню"}
            aria-expanded={menu}
            aria-controls="university-menu"
            onClick={() => setMenu(!menu)}
          >
            {menu ? <X size={23} /> : <Menu size={23} />}
          </button>
          <span className={styles.date}>
            <CalendarDays size={19} />
            {today()}
          </span>
          <div className={styles.utilityRight}>
            <button
              onClick={() => setContrast(!contrast)}
              aria-pressed={contrast}
            >
              <Eye size={21} />
              <span>
                {contrast ? "Обычная версия" : "Версия для слабовидящих"}
              </span>
            </button>
            <span className={styles.language}>
              <Globe size={20} /> RU
            </span>
            <span>
              <UserCircle size={21} />
              <span>Личный кабинет</span>
            </span>
          </div>
        </div>
      </div>
      <div className={styles.brandHeader}>
        <div className={styles.container}>
          <div className={styles.brandRow}>
            <span className={styles.logo} aria-label="Самарский университет">
              <img
                src={`${import.meta.env.BASE_URL}brand/logo-white-ru.svg`}
                alt="Самарский университет"
                width="353"
                height="105"
              />
            </span>
            <div className={styles.university}>
              <p>
                федеральное государственное автономное образовательное
                учреждение высшего образования
                <br />
                «Самарский национальный исследовательский университет имени
                академика С.П. Королева»
              </p>
              <div className={styles.audiences}>
                {[
                  "Абитуриенту",
                  "Студенту",
                  "Выпускнику",
                  "Сотруднику",
                  "Партнеру",
                ].map((label) => (
                  <span key={label}>{label}</span>
                ))}
              </div>
            </div>
          </div>
          <nav className={styles.mainNav} aria-label="Разделы университета">
            {links.map((label) => (
              <span
                key={label}
                className={label === "НАУКА" ? styles.navActive : undefined}
              >
                {label}
              </span>
            ))}
            <span aria-label="Поиск по сайту пока недоступен">
              <Search size={26} />
            </span>
          </nav>
        </div>
      </div>
      {menu && (
        <nav
          id="university-menu"
          className={styles.expandedMenu}
          aria-label="Меню университета"
        >
          {links.map((label) => (
            <span key={label}>{label}</span>
          ))}
        </nav>
      )}
    </header>
  );
}

function Editor({
  schema,
  initial,
  type: initialType = "book",
  imported = false,
}: {
  schema: Schema;
  initial?: Imported;
  type?: string;
  imported?: boolean;
}) {
  const [type, setType] = useState(initial?.source_type || initialType);
  const [values, setValues] = useState<Values>(initial?.fields || {});
  const [result, setResult] = useState(initial?.formatted || "");
  const [errors, setErrors] = useState<Errors>(initial?.errors || {});
  const [message, setMessage] = useState("");
  const [loading, setLoading] = useState(false);
  const [copied, setCopied] = useState(false);
  const [copyFailure, setCopyFailure] = useState(false);
  const controller = useRef<AbortController | null>(null);
  const active = useRef(0);
  const formRef = useRef<HTMLFormElement>(null);
  const textRef = useRef<HTMLTextAreaElement>(null);
  const config: SourceConfig = schema.types[type];
  useEffect(() => () => controller.current?.abort(), []);
  function invalidate() {
    active.current++;
    controller.current?.abort();
    setLoading(false);
    setResult("");
    setCopied(false);
    setCopyFailure(false);
    setMessage("");
  }
  function update(name: string, value: string) {
    invalidate();
    setValues((v) => ({ ...v, [name]: value }));
    setErrors((e) => {
      const next = { ...e };
      delete next[name];
      return next;
    });
  }
  function reset() {
    invalidate();
    setValues({});
    setErrors({});
  }
  async function generate(event: FormEvent) {
    event.preventDefault();
    setCopied(false);
    setCopyFailure(false);
    const missing = Object.fromEntries(
      config.fields
        .filter((f) => f.required && !values[f.name]?.trim())
        .map((f) => [f.name, "Заполните обязательное поле"]),
    );
    if (Object.keys(missing).length) {
      setErrors(missing);
      setResult("");
      setMessage("Заполните обязательные поля.");
      setTimeout(
        () =>
          formRef.current
            ?.querySelector<HTMLElement>('[aria-invalid="true"]')
            ?.focus(),
        0,
      );
      return;
    }
    controller.current?.abort();
    const abort = new AbortController();
    controller.current = abort;
    const request = ++active.current;
    setLoading(true);
    setErrors({});
    setMessage("");
    setResult("");
    try {
      const data = await api<{ formatted: string }>(
        "format",
        { source_type: type, fields: values },
        abort.signal,
      );
      if (request === active.current) setResult(data.formatted);
    } catch (error) {
      if (!abort.signal.aborted && request === active.current) {
        setMessage(
          error instanceof Error ? error.message : "Ошибка оформления.",
        );
        if (error instanceof ApiError) {
          setErrors(error.fields);
          setTimeout(
            () =>
              formRef.current
                ?.querySelector<HTMLElement>('[aria-invalid="true"]')
                ?.focus(),
            0,
          );
        }
      }
    } finally {
      if (request === active.current) setLoading(false);
    }
  }
  async function copy() {
    try {
      await navigator.clipboard.writeText(result);
      setCopied(true);
      setCopyFailure(false);
    } catch {
      setCopyFailure(true);
      textRef.current?.focus();
      textRef.current?.select();
    }
  }
  return (
    <div className={styles.workspace}>
      <section
        className={styles.formCard}
        aria-label={imported ? "Сведения из источника" : "Данные источника"}
      >
        <div className={styles.cardHeading}>
          <h2>{imported ? "Сведения об источнике" : "Данные источника"}</h2>
          <span>* — обязательные поля</span>
        </div>
        <form ref={formRef} onSubmit={generate} noValidate>
          <div className={styles.typeField}>
            <label htmlFor={`${imported ? "import" : "manual"}-source-type`}>
              Тип источника
            </label>
            <select
              id={`${imported ? "import" : "manual"}-source-type`}
              value={type}
              onChange={(e) => {
                invalidate();
                setType(e.target.value);
                setValues({});
                setErrors({});
              }}
            >
              {Object.entries(schema.types).map(([id, t]) => (
                <option key={id} value={id}>
                  {t.label}
                </option>
              ))}
            </select>
          </div>
          <div className={styles.fields}>
            {config.fields.map((field) => {
              const id = `${imported ? "import" : "manual"}-${field.name}`;
              const wide = [
                "authors",
                "author",
                "authorFull",
                "title",
                "url",
                "journal",
                "collection",
              ].includes(field.name);
              return (
                <div
                  key={id}
                  className={`${styles.field} ${wide ? styles.full : ""}`}
                >
                  <label htmlFor={id}>
                    {field.label}
                    {field.required && (
                      <span className={styles.required}> *</span>
                    )}
                  </label>
                  {field.type === "select" ? (
                    <select
                      id={id}
                      value={values[field.name] || ""}
                      aria-invalid={!!errors[field.name]}
                      aria-describedby={
                        errors[field.name]
                          ? `${id}-error`
                          : field.hint
                            ? `${id}-hint`
                            : undefined
                      }
                      required={field.required}
                      onChange={(e) => update(field.name, e.target.value)}
                    >
                      <option value="">Выберите степень</option>
                      {field.options?.map((o) => (
                        <option key={o} value={o}>
                          {o}
                        </option>
                      ))}
                    </select>
                  ) : (
                    <input
                      id={id}
                      name={field.name}
                      type={field.type === "number" ? "text" : field.type}
                      inputMode={
                        field.type === "number" ? "numeric" : undefined
                      }
                      value={values[field.name] || ""}
                      maxLength={2000}
                      required={field.required}
                      placeholder={
                        field.name === "title"
                          ? "Введите название работы"
                          : field.name === "authors"
                            ? "Иванов И. И.; Петров П. П."
                            : field.name === "year"
                              ? "2026"
                              : undefined
                      }
                      max={
                        field.name === "accessDate"
                          ? new Date().toLocaleDateString("en-CA")
                          : undefined
                      }
                      title={field.hint}
                      aria-invalid={!!errors[field.name]}
                      aria-describedby={
                        errors[field.name]
                          ? `${id}-error`
                          : field.hint
                            ? `${id}-hint`
                            : undefined
                      }
                      onChange={(e) => update(field.name, e.target.value)}
                    />
                  )}
                  {errors[field.name] ? (
                    <span id={`${id}-error`} className={styles.fieldError}>
                      {errors[field.name]}
                    </span>
                  ) : field.hint ? (
                    <span id={`${id}-hint`} className={styles.hint}>
                      {field.hint}
                    </span>
                  ) : null}
                </div>
              );
            })}
          </div>
          {message && (
            <div className={styles.error} role="alert">
              {message}
            </div>
          )}
          <div className={styles.formActions}>
            <button className={styles.primary} disabled={loading} type="submit">
              {loading ? (
                <LoaderCircle className={styles.spin} size={18} />
              ) : (
                <BookOpen size={18} />
              )}{" "}
              {loading ? "Оформляем…" : "Сгенерировать ссылку"}
            </button>
            <button type="button" className={styles.textButton} onClick={reset}>
              <RotateCcw size={16} /> Очистить
            </button>
          </div>
        </form>
      </section>
      <aside className={styles.resultColumn}>
        <section
          className={styles.resultCard}
          aria-label="Результат оформления"
        >
          <div className={styles.resultTitle}>
            <h2>Готовая ссылка</h2>
            <span className={styles.standardBadge}>ГОСТ 2008</span>
          </div>
          <p className={styles.resultIntro}>
            Скопируйте ссылку и вставьте
            <br className={styles.desktopBreak} /> в свою работу.
          </p>
          <div
            className={styles.resultBox}
            aria-live="polite"
            aria-atomic="true"
          >
            {result ? (
              <textarea
                ref={textRef}
                aria-label="Оформленная библиографическая ссылка"
                value={result}
                readOnly
              />
            ) : (
              <div className={styles.emptyResult}>
                <BookOpen size={29} strokeWidth={1.4} />
                <p>
                  Здесь появится
                  <br />
                  библиографическая ссылка
                </p>
                <span>
                  {imported
                    ? "Проверьте сведения и нажмите «Сгенерировать ссылку»"
                    : "Заполните поля и нажмите «Сгенерировать ссылку»"}
                </span>
              </div>
            )}
          </div>
          <button
            type="button"
            className={styles.copyButton}
            disabled={!result || loading}
            onClick={copy}
          >
            {copied ? <Check size={18} /> : <Copy size={18} />}{" "}
            {copied ? "Ссылка скопирована" : "Скопировать ссылку"}
          </button>
          <span className={styles.srOnly} role="status">
            {copied ? "Ссылка скопирована в буфер обмена" : ""}
          </span>
          {copyFailure && (
            <p role="status" className={styles.hint}>
              Текст выделен. Нажмите Ctrl+C или ⌘C для копирования.
            </p>
          )}
        </section>
        <div className={styles.help}>
          <Info size={20} />
          <div>
            <h3>Об оформлении</h3>
            <p>
              Ссылки оформляются по ГОСТ Р 7.0.5–2008. Проверьте сведения об
              источнике перед использованием.
            </p>
            <span>Рекомендации библиотеки</span>
          </div>
        </div>
      </aside>
    </div>
  );
}

function ImportPanel({ schema }: { schema: Schema }) {
  const [identifier, setIdentifier] = useState("");
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const [result, setResult] = useState<Imported | null>(null);
  const [revision, setRevision] = useState(0);
  const controller = useRef<AbortController | null>(null);
  useEffect(() => () => controller.current?.abort(), []);
  function change(value: string) {
    controller.current?.abort();
    setLoading(false);
    setIdentifier(value);
    setResult(null);
    setError("");
  }
  async function find(event: FormEvent) {
    event.preventDefault();
    if (!identifier.trim()) {
      setError("Введите DOI или ISBN источника.");
      return;
    }
    controller.current?.abort();
    const abort = new AbortController();
    controller.current = abort;
    setError("");
    setLoading(true);
    setResult(null);
    try {
      const data = await api<Imported>(
        "import",
        { identifier: identifier.trim() },
        abort.signal,
      );
      if (!abort.signal.aborted) {
        setResult(data);
        setRevision((v) => v + 1);
      }
    } catch (error) {
      if (!abort.signal.aborted)
        setError(
          error instanceof Error ? error.message : "Не удалось найти источник.",
        );
    } finally {
      if (!abort.signal.aborted) setLoading(false);
    }
  }
  return (
    <div>
      <section className={styles.importCard}>
        <div className={styles.cardHeading}>
          <h2>Найдите источник по идентификатору</h2>
        </div>
        <p>
          Введите DOI статьи или ISBN книги — сервис найдёт сведения и оформит
          ссылку.
        </p>
        <form onSubmit={find}>
          <label htmlFor="identifier">DOI или ISBN</label>
          <div className={styles.searchRow}>
            <input
              id="identifier"
              value={identifier}
              maxLength={512}
              autoComplete="off"
              spellCheck={false}
              placeholder="Например: 10.1038/nphys1170"
              aria-invalid={!!error}
              aria-describedby="import-hint"
              onChange={(e) => change(e.target.value)}
            />
            <button className={styles.primary} disabled={loading} type="submit">
              {loading ? (
                <LoaderCircle size={18} className={styles.spin} />
              ) : (
                <Search size={18} />
              )}{" "}
              {loading ? "Ищем источник…" : "Найти и оформить"}
            </button>
          </div>
          <div id="import-hint" className={styles.examples}>
            Примеры:{" "}
            <button type="button" onClick={() => change("10.1038/nphys1170")}>
              DOI статьи
            </button>
            <span>·</span>
            <button type="button" onClick={() => change("9780262033848")}>
              ISBN книги
            </button>
          </div>
        </form>
        {error && (
          <div role="alert" className={styles.error}>
            {error}
          </div>
        )}
        <div className={styles.importNote}>
          <Info size={16} />
          <span>
            Данные: Crossref и Open Library. Импорт eLIBRARY ID / EDN пока
            недоступен; используйте DOI или ручной ввод.
          </span>
        </div>
      </section>
      {result ? (
        <>
          <div className={styles.importStatus} role="status">
            <Check size={21} />
            <div>
              <strong>Источник найден</strong>
              <p>
                {result.warnings.join(" ")}{" "}
                <a href={result.source_url} target="_blank" rel="noreferrer">
                  {result.provider} <ExternalLink size={12} />
                </a>
              </p>
            </div>
          </div>
          <Editor key={revision} schema={schema} initial={result} imported />
        </>
      ) : (
        <div className={styles.importEmpty}>
          <ArrowDownToLine size={33} strokeWidth={1.3} />
          <h2>От идентификатора — к готовой ссылке</h2>
          <p>
            Найденные сведения можно проверить и дополнить.
            <br />
            Если данных не хватит, мы отметим нужные поля.
          </p>
        </div>
      )}
    </div>
  );
}

export default function App() {
  const [schema, setSchema] = useState<Schema | null>(null);
  const [error, setError] = useState("");
  const [reload, setReload] = useState(0);
  const [tab, setTab] = useState<"manual" | "import">("manual");
  const [contrast, setContrast] = useState(false);
  const embedded =
    new URLSearchParams(window.location.search).get("embedded") === "1";
  useEffect(() => {
    const abort = new AbortController();
    setError("");
    api<Schema>("schema", undefined, abort.signal)
      .then(setSchema)
      .catch((e) => {
        if (!abort.signal.aborted) setError(e.message);
      });
    return () => abort.abort();
  }, [reload]);
  function tabKeys(event: KeyboardEvent<HTMLButtonElement>) {
    if (["ArrowLeft", "ArrowRight", "Home", "End"].includes(event.key)) {
      event.preventDefault();
      const next =
        event.key === "Home"
          ? "manual"
          : event.key === "End"
            ? "import"
            : tab === "manual"
              ? "import"
              : "manual";
      setTab(next);
      document.getElementById(`tab-${next}`)?.focus();
    }
  }
  return (
    <div
      className={`${styles.app} ${contrast ? styles.contrast : ""} ${embedded ? styles.embedded : ""}`}
    >
      <a href="#main" className={styles.skip}>
        Перейти к содержимому
      </a>
      {!embedded && <Header contrast={contrast} setContrast={setContrast} />}
      <main id="main" className={`${styles.container} ${styles.main}`}>
        {!embedded && (
          <nav className={styles.breadcrumbs} aria-label="Хлебные крошки">
            <span aria-label="Главная">
              <Home size={16} fill="currentColor" />
            </span>
            <ChevronRight size={15} />
            <span>Библиотека</span>
            <ChevronRight size={15} />
            <span aria-current="page">Библиографические ссылки</span>
          </nav>
        )}
        <div className={styles.pageHeading}>
          <h1>Оформление библиографических ссылок</h1>
        </div>
        <p className={styles.lead}>
          Создайте ссылку по ГОСТ Р 7.0.5–2008 — вручную или по DOI и ISBN.
        </p>
        <div
          className={styles.tabs}
          role="tablist"
          aria-label="Способ создания ссылки"
        >
          {(["manual", "import"] as const).map((id) => (
            <button
              key={id}
              id={`tab-${id}`}
              role="tab"
              aria-selected={tab === id}
              aria-controls={`panel-${id}`}
              tabIndex={tab === id ? 0 : -1}
              onKeyDown={tabKeys}
              onClick={() => setTab(id)}
              className={tab === id ? styles.tabActive : ""}
            >
              {id === "manual" ? (
                <BookOpen size={18} />
              ) : (
                <ArrowDownToLine size={18} />
              )}{" "}
              {id === "manual" ? "Ручное создание" : "Автоматическое создание"}
            </button>
          ))}
        </div>
        {error ? (
          <div className={styles.error} role="alert">
            <p>{error}</p>
            <button
              className={styles.outline}
              onClick={() => setReload((v) => v + 1)}
            >
              Повторить загрузку
            </button>
          </div>
        ) : !schema ? (
          <div role="status" className={styles.loading}>
            <LoaderCircle className={styles.spin} /> Загружаем формы…
          </div>
        ) : (
          <>
            <div
              id="panel-manual"
              role="tabpanel"
              aria-labelledby="tab-manual"
              hidden={tab !== "manual"}
            >
              <Editor schema={schema} />
            </div>
            <div
              id="panel-import"
              role="tabpanel"
              aria-labelledby="tab-import"
              hidden={tab !== "import"}
            >
              <ImportPanel schema={schema} />
            </div>
          </>
        )}
      </main>
      {!embedded && (
        <footer className={styles.footer}>
          <div className={styles.container}>
            <div>
              <span>Самарский университет</span>
              <p>Сервис оформления библиографических ссылок</p>
            </div>
            <span>Научно-техническая библиотека</span>
          </div>
        </footer>
      )}
    </div>
  );
}
