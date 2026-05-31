interface ApiErrorBody {
  detail?: string | null
  message?: string | null
}

function tryParseJson(s: string): ApiErrorBody | null {
  try { return JSON.parse(s) } catch { return null }
}

// Strings matching this leak server internals or are raw axios noise — they must
// never be shown to the user. Callers fall back to a friendly i18n string instead.
const LEAK_PATTERN = /Exception|\.java\b|\bjava\.|\borg\.|com\.picsou|Request failed with status code|stack ?trace/i

function isSafeMessage(s: string): boolean {
  return s.trim().length > 0 && !LEAK_PATTERN.test(s)
}

/**
 * Returns a user-safe message the backend explicitly sent (ProblemDetail `detail`,
 * a `message` field, or a JSON-embedded `message`), or `null` when nothing safe is
 * available. Anything that looks like a stack trace / class name / axios default is
 * rejected so it can be replaced by a friendly fallback.
 */
export function safeBackendMessage(err: unknown): string | null {
  const axErr = err as { response?: { data?: ApiErrorBody }; message?: string }

  const detail = axErr?.response?.data?.detail
  if (detail && typeof detail === 'string') {
    const jsonStart = detail.indexOf('{')
    if (jsonStart >= 0) {
      const parsed = tryParseJson(detail.slice(jsonStart))
      if (parsed?.message && isSafeMessage(parsed.message)) return parsed.message
    }
    if (isSafeMessage(detail)) return detail
  }

  const bodyMessage = axErr?.response?.data?.message
  if (bodyMessage && typeof bodyMessage === 'string' && isSafeMessage(bodyMessage)) {
    return bodyMessage
  }

  const msg = axErr?.message
  if (msg && typeof msg === 'string') {
    if (msg.startsWith('{')) {
      const parsed = tryParseJson(msg)
      if (parsed?.message && isSafeMessage(parsed.message)) return parsed.message
    }
    if (isSafeMessage(msg)) return msg
  }

  return null
}

export function extractErrorMessage(err: unknown, fallback = 'Une erreur est survenue'): string {
  return safeBackendMessage(err) ?? fallback
}

type TFunc = (key: string, fallback?: string) => string

/**
 * Maps any API error to a friendly, translated string.
 *
 * - 401 / 429 / 5xx → a generic translated message (the backend detail here is
 *   either absent or the deliberately-vague "An unexpected error occurred").
 * - 4xx → the backend's *specific* reason when it's user-safe (e.g. "Cannot delete
 *   the last administrator", "Username already taken"); otherwise a translated
 *   fallback (403 → "forbidden", others → `fallbackKey`).
 *
 * Never returns raw axios text or leaked internals — those are filtered by
 * {@link safeBackendMessage}.
 */
export function formatApiError(err: unknown, t: TFunc, fallbackKey = 'common.error'): string {
  const status = (err as { response?: { status?: number } })?.response?.status

  if (status === 401) return t('common.errors.unauthorized')
  if (status === 429) return t('common.errors.tooManyRequests')
  if (status && status >= 500) return t('common.errors.serverError')

  const safe = safeBackendMessage(err)
  if (safe) return safe

  if (status === 403) return t('common.errors.forbidden')
  return t(fallbackKey)
}
