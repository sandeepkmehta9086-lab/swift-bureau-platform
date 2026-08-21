const TOKEN_KEY = "sb.token";
const USER_KEY = "sb.user";

export type User = {
  id: string;
  loginId: string;
  role: string;
  tenantId: string;
  mfaEnabled?: boolean;
  email?: string;
  status?: string;
};

export function token(): string | null {
  return localStorage.getItem(TOKEN_KEY);
}

export function currentUser(): User | null {
  const raw = localStorage.getItem(USER_KEY);
  return raw ? (JSON.parse(raw) as User) : null;
}

export function setSession(accessToken: string, user: User) {
  localStorage.setItem(TOKEN_KEY, accessToken);
  localStorage.setItem(USER_KEY, JSON.stringify(user));
}

export function clearSession() {
  localStorage.removeItem(TOKEN_KEY);
  localStorage.removeItem(USER_KEY);
}

export async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers);
  headers.set("Content-Type", "application/json");
  const t = token();
  if (t) {
    headers.set("Authorization", `Bearer ${t}`);
  }
  const res = await fetch(path, { ...init, headers });
  const text = await res.text();
  let data: { message?: string; code?: string } | null = null;
  if (text) {
    try {
      data = JSON.parse(text);
    } catch {
      data = null;
    }
  }
    if (!res.ok) {
      throw new Error(data?.message || data?.code || res.statusText);
    }
    return data as T;
}
