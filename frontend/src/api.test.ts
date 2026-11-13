import { afterEach, describe, expect, it, vi } from "vitest";
import { api, clearSession, currentUser, setSession, token } from "./api";

const user = { id: "u1", loginId: "meridian.maker", role: "PAYMENT_MAKER", tenantId: "t1", mfaEnabled: true };

function mockFetch(status: number, body: string) {
  const spy = vi.fn().mockResolvedValue(new Response(body, { status, statusText: status === 500 ? "Server Error" : "OK" }));
  vi.stubGlobal("fetch", spy);
  return spy;
}

afterEach(() => vi.unstubAllGlobals());

describe("session storage", () => {
  it("round-trips token and user", () => {
    expect(token()).toBeNull();
    expect(currentUser()).toBeNull();
    setSession("tok-1", user);
    expect(token()).toBe("tok-1");
    expect(currentUser()).toEqual(user);
    clearSession();
    expect(token()).toBeNull();
    expect(currentUser()).toBeNull();
  });
});

describe("api()", () => {
  it("sends JSON and the bearer token", async () => {
    setSession("tok-2", user);
    const spy = mockFetch(200, JSON.stringify({ ok: true }));
    await expect(
      api("/api/x", { method: "POST", body: "{}", headers: { "X-Step-Up-Totp": "123456" } })
    ).resolves.toEqual({ ok: true });
    const [path, init] = spy.mock.calls[0];
    const headers = new Headers(init.headers);
    expect(path).toBe("/api/x");
    expect(headers.get("Authorization")).toBe("Bearer tok-2");
    expect(headers.get("Content-Type")).toBe("application/json");
    expect(headers.get("X-Step-Up-Totp")).toBe("123456");
  });

  it("omits Authorization when signed out", async () => {
    const spy = mockFetch(200, "[]");
    await api("/api/y");
    expect(new Headers(spy.mock.calls[0][1].headers).has("Authorization")).toBe(false);
  });

  it("returns null for empty bodies", async () => {
    mockFetch(200, "");
    await expect(api("/api/z")).resolves.toBeNull();
  });

  it("throws the backend message on error", async () => {
    mockFetch(403, JSON.stringify({ code: "FOUR_EYES", message: "Maker cannot approve" }));
    await expect(api("/api/a")).rejects.toThrow("Maker cannot approve");
  });

  it("falls back to code, then status text", async () => {
    mockFetch(409, JSON.stringify({ code: "STATE" }));
    await expect(api("/api/b")).rejects.toThrow("STATE");
    mockFetch(500, "<html>oops</html>");
    await expect(api("/api/c")).rejects.toThrow("Server Error");
  });
});
