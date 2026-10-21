// Same-origin JSON transport shared by the Vue views; write requests carry the session CSRF token.
export class ApiError extends Error {
  constructor(public status: number, public code: string, message: string) { super(message); }
}
export async function request<T>(path: string, method = 'GET', body?: unknown, csrf?: string): Promise<T> {
  let response: Response;
  try {
    response = await fetch(`/api${path}`, {
      method, credentials: 'same-origin',
      headers: { ...(body === undefined ? {} : { 'Content-Type': 'application/json' }), ...(csrf ? { 'X-CSRF-Token': csrf } : {}) },
      ...(body === undefined ? {} : { body: JSON.stringify(body) }),
    });
  } catch { throw new ApiError(0, 'NETWORK_ERROR', '无法连接本机服务，请确认后端已启动后重试'); }
  const payload = response.status === 204 ? undefined : await response.json().catch(() => ({}));
  if (!response.ok) throw new ApiError(response.status, payload.code ?? 'REQUEST_FAILED', payload.message ?? '请求未完成，请稍后重试');
  return payload as T;
}
