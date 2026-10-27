export type Field = {
  name: string;
  label: string;
  type: string;
  required: boolean;
  hint?: string;
  options?: string[];
};
export type SourceConfig = { label: string; fields: Field[] };
export type Schema = {
  id: string;
  label: string;
  version: string;
  types: Record<string, SourceConfig>;
};
export type Values = Record<string, string>;
export type Errors = Record<string, string>;
export type Imported = {
  source_type: string;
  fields: Values;
  provider: string;
  source_url: string;
  identifier: string;
  formatted: string | null;
  errors: Errors;
  warnings: string[];
};
export class ApiError extends Error {
  constructor(
    message: string,
    public fields: Errors = {},
  ) {
    super(message);
  }
}
export async function api<T>(
  path: string,
  body?: unknown,
  signal?: AbortSignal,
): Promise<T> {
  const timeout = AbortSignal.timeout(45000);
  let response: Response;
  try {
    response = await fetch(
      `${import.meta.env.VITE_API_BASE || "/api"}/${path}/`,
      {
        method: body ? "POST" : "GET",
        headers: body ? { "Content-Type": "application/json" } : undefined,
        body: body ? JSON.stringify(body) : undefined,
        signal: signal ? AbortSignal.any([signal, timeout]) : timeout,
      },
    );
  } catch (error) {
    if (signal?.aborted) throw error;
    throw new ApiError(
      "Не удалось связаться с сервером. Проверьте соединение и повторите запрос.",
    );
  }
  const data = await response.json().catch(() => null);
  if (!response.ok) {
    const fields =
      data?.fields && typeof data.fields === "object"
        ? Object.fromEntries(
            Object.entries(data.fields).map(([k, v]) => [
              k,
              Array.isArray(v) ? v.join(" ") : String(v),
            ]),
          )
        : {};
    const detail =
      typeof data?.detail === "string"
        ? data.detail
        : response.status === 429
          ? "Слишком много запросов. Подождите минуту и попробуйте снова."
          : Object.keys(fields).length
            ? "Проверьте отмеченные поля."
            : "Не удалось выполнить запрос. Попробуйте позже.";
    throw new ApiError(detail, fields);
  }
  return data as T;
}
