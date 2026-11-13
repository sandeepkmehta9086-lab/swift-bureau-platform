// In-memory stand-in for the Spring Boot API, used by integration tests.
// It mirrors the contract the UI relies on today (paths, headers, JSON
// shapes, MessageStatus values and gpi hop codes from PaymentService), so the
// tests fail if the UI stops calling the backend the way it does now.
import { vi } from "vitest";

export const VALID_TOTP = "123456";

type Role =
  | "BUREAU_OPERATOR"
  | "BANK_SECURITY_OFFICER"
  | "PAYMENT_MAKER"
  | "PAYMENT_CHECKER"
  | "LIQUIDITY"
  | "AUDITOR";

export type FakeUser = {
  id: string;
  loginId: string;
  password: string;
  role: Role;
  tenantId: string;
  mfaEnabled: boolean;
  status: string;
};

export type FakePayment = {
  id: string;
  tenantId: string;
  messageType: string;
  uetr: string;
  direction: "OUT" | "IN";
  status: string;
  amount: number | null;
  currency: string;
  instructingAgentBic: string;
  instructedAgentBic: string;
  debtorName: string;
  creditorName: string;
  mxXml?: string;
  makerId?: string;
  checkerId?: string;
  createdAt: string;
  [k: string]: unknown;
};

export type FakeHop = {
  id: string;
  tenantId: string;
  uetr: string;
  hopStatus: string;
  bic: string;
  detail: string;
  occurredAt: string;
};

export type RecordedCall = { method: string; path: string; body: any; headers: Headers };

const TENANT = "t-meridian";

function json(status: number, body: unknown) {
  return new Response(body === undefined ? "" : JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

function apiError(status: number, code: string, message: string) {
  return json(status, { code, message });
}

export class FakeBackend {
  users: FakeUser[] = [
    { id: "u-ops", loginId: "bureau.ops", password: "ChangeMe_Bureau1!", role: "BUREAU_OPERATOR", tenantId: "", mfaEnabled: true, status: "ACTIVE" },
    { id: "u-so", loginId: "meridian.so", password: "ChangeMe_Bank1!", role: "BANK_SECURITY_OFFICER", tenantId: TENANT, mfaEnabled: true, status: "ACTIVE" },
    { id: "u-maker", loginId: "meridian.maker", password: "ChangeMe_Bank1!", role: "PAYMENT_MAKER", tenantId: TENANT, mfaEnabled: true, status: "ACTIVE" },
    { id: "u-checker", loginId: "meridian.checker", password: "ChangeMe_Bank1!", role: "PAYMENT_CHECKER", tenantId: TENANT, mfaEnabled: true, status: "ACTIVE" },
    { id: "u-liq", loginId: "meridian.liq", password: "ChangeMe_Bank1!", role: "LIQUIDITY", tenantId: TENANT, mfaEnabled: true, status: "ACTIVE" },
    { id: "u-new", loginId: "meridian.new", password: "ChangeMe_Bank1!", role: "PAYMENT_MAKER", tenantId: TENANT, mfaEnabled: false, status: "ACTIVE" },
  ];
  payments: FakePayment[] = [];
  hops: FakeHop[] = [];
  templates: any[] = [
    {
      id: "tpl-1",
      name: "EUR to Deutsche",
      messageType: "pacs.008",
      instructingAgentBic: "MIDNGB2L",
      instructedAgentBic: "DEUTDEFF",
      amount: 250,
      currency: "EUR",
      debtorName: "Acme Ltd",
      debtorStreet: "1 King Street",
      debtorTown: "London",
      debtorCountry: "GB",
      creditorName: "Kunde GmbH",
      creditorStreet: "Hauptstr 1",
      creditorTown: "Frankfurt",
      creditorCountry: "DE",
      chargeBearer: "SHAR",
      active: true,
    },
  ];
  calls: RecordedCall[] = [];
  /** When set, approve leaves the payment SENT; call deliverInbound() to finish. */
  holdInbound = false;
  private tokens = new Map<string, string>();
  private seq = 0;
  private clock = Date.parse("2026-10-30T09:00:00Z");

  private nextId(prefix: string) {
    this.seq += 1;
    return `${prefix}-${this.seq}`;
  }

  private now() {
    this.clock += 1500;
    return new Date(this.clock).toISOString();
  }

  private uetr() {
    this.seq += 1;
    const n = String(this.seq).padStart(12, "0");
    return `e2b4c7d0-1a2b-4c3d-8e9f-${n}`;
  }

  private publicUser(u: FakeUser) {
    const { password: _pw, ...rest } = u;
    return rest;
  }

  private addHop(p: FakePayment, hopStatus: string, bic: string, detail: string) {
    this.hops.push({ id: this.nextId("hop"), tenantId: p.tenantId, uetr: p.uetr, hopStatus, bic, detail, occurredAt: this.now() });
  }

  /** Seed a payment directly (bypasses the UI). */
  seedPayment(overrides: Partial<FakePayment> = {}): FakePayment {
    const p: FakePayment = {
      id: this.nextId("pay"),
      tenantId: TENANT,
      messageType: "pacs.008",
      uetr: this.uetr(),
      direction: "OUT",
      status: "DRAFT",
      amount: 1500,
      currency: "USD",
      instructingAgentBic: "MIDNGB2L",
      instructedAgentBic: "CHASUS33",
      debtorName: "Acme Ltd",
      creditorName: "Widget LLC",
      makerId: "u-maker",
      createdAt: this.now(),
      ...overrides,
    };
    this.payments.push(p);
    this.addHop(p, "INITIATED", p.instructingAgentBic, "Created by maker");
    return p;
  }

  /** Simulates the stub gateway's inbound pacs.002 for a SENT payment. */
  deliverInbound(paymentId: string) {
    const p = this.payments.find((x) => x.id === paymentId);
    if (!p || p.status !== "SENT") return;
    p.status = "ACKED";
    this.payments.push({
      ...p,
      id: this.nextId("pay"),
      messageType: "pacs.002",
      direction: "IN",
      status: "ACKED",
      instructingAgentBic: p.instructedAgentBic,
      instructedAgentBic: p.instructingAgentBic,
    });
    this.addHop(p, "RECEIVED_BY_CORRESPONDENT", p.instructedAgentBic, "pacs.002 ACSC");
    this.addHop(p, "CREDITED", p.instructedAgentBic, "Stub tracker complete");
  }

  /** Install as global fetch. Returns the spy. */
  install() {
    const spy = vi.fn((input: RequestInfo | URL, init?: RequestInit) => this.handle(input, init));
    vi.stubGlobal("fetch", spy);
    return spy;
  }

  callsTo(method: string, pathPrefix: string) {
    return this.calls.filter((c) => c.method === method && c.path.startsWith(pathPrefix));
  }

  async handle(input: RequestInfo | URL, init: RequestInit = {}): Promise<Response> {
    const url = new URL(String(input), "http://localhost");
    const method = (init.method || "GET").toUpperCase();
    const headers = new Headers(init.headers);
    const body = init.body ? JSON.parse(String(init.body)) : undefined;
    const path = url.pathname;
    this.calls.push({ method, path: path + url.search, body, headers });

    // ---- public auth ----
    if (method === "POST" && path === "/api/auth/login") {
      const u = this.users.find((x) => x.loginId === body.loginId && x.password === body.password);
      if (!u) return apiError(401, "BAD_CREDENTIALS", "Invalid login ID or password");
      const tok = this.nextId("tok");
      this.tokens.set(tok, u.id);
      if (!u.mfaEnabled) return json(200, { status: "MFA_ENROLLMENT_REQUIRED", enrollmentToken: tok });
      if (body.totp !== VALID_TOTP) return apiError(401, "MFA_REQUIRED", "Authenticator code required");
      return json(200, { status: "OK", accessToken: tok, user: this.publicUser(u) });
    }

    const auth = headers.get("Authorization");
    const me = auth ? this.users.find((u) => u.id === this.tokens.get(auth.replace("Bearer ", ""))) : undefined;
    if (!me) return apiError(401, "UNAUTHENTICATED", "Sign in required");

    if (method === "POST" && path === "/api/auth/mfa/enroll") {
      return json(200, { secret: "JBSWY3DPEHPK3PXP", otpauth: "otpauth://totp/x" });
    }
    if (method === "POST" && path === "/api/auth/mfa/confirm") {
      if (body.code !== VALID_TOTP) return apiError(400, "BAD_TOTP", "Invalid authenticator code");
      me.mfaEnabled = true;
      const tok = this.nextId("tok");
      this.tokens.set(tok, me.id);
      return json(200, { accessToken: tok, user: this.publicUser(me) });
    }

    // ---- reports ----
    if (method === "GET" && path === "/api/reports/stp") {
      const out = this.payments.filter((p) => p.tenantId === me.tenantId && p.direction === "OUT");
      const acked = out.filter((p) => p.status === "ACKED").length;
      return json(200, { total: out.length, stpRatePercent: out.length ? Math.round((acked / out.length) * 10000) / 100 : 0 });
    }

    // ---- payments ----
    if (method === "GET" && path === "/api/payments") {
      const dir = url.searchParams.get("direction");
      return json(
        200,
        this.payments.filter((p) => p.tenantId === me.tenantId && (!dir || p.direction === dir)).slice().reverse()
      );
    }
    if (method === "POST" && path === "/api/payments") {
      if (me.role !== "PAYMENT_MAKER" && me.role !== "BANK_SECURITY_OFFICER") {
        return apiError(403, "FORBIDDEN", "Role cannot create payments");
      }
      if (!body.debtorTown || !body.debtorCountry || !body.creditorTown || !body.creditorCountry) {
        return apiError(400, "STRUCTURED_ADDRESS", "Structured town and country are mandatory");
      }
      const { templateId: _tpl, ...fields } = body;
      const p = this.seedPayment({ ...fields, tenantId: me.tenantId, makerId: me.id, status: "DRAFT" });
      p.mxXml = `<Document><FIToFICstmrCdtTrf><UETR>${p.uetr}</UETR></FIToFICstmrCdtTrf></Document>`;
      return json(200, p);
    }
    // Stage 1 of the life cycle: pain.001 in, pain.002 out, pacs.008 drafted only when accepted.
    if (method === "POST" && path === "/api/payments/initiate") {
      if (me.role !== "PAYMENT_MAKER" && me.role !== "BANK_SECURITY_OFFICER") {
        return apiError(403, "FORBIDDEN", "Role cannot initiate payments");
      }
      const accepted = Boolean(body.debtorTown && body.debtorCountry && body.creditorTown && body.creditorCountry);
      const reasonCode = accepted ? "ACCP" : "BE04";
      const rejectionDetail = accepted ? null : "Structured postal address (town and country) is mandatory on CBPR+ payments";
      const base = {
        tenantId: me.tenantId,
        uetr: this.uetr(),
        amount: body.amount,
        currency: body.currency,
        instructingAgentBic: body.debtorAgentBic,
        instructedAgentBic: body.creditorAgentBic,
        debtorName: body.debtorName,
        creditorName: body.creditorName,
        makerId: me.id,
        reasonCode,
      };
      const instruction: FakePayment = {
        ...base,
        id: this.nextId("pay"),
        messageType: "pain.001",
        direction: "IN",
        status: accepted ? "ACKED" : "REJECTED",
        mxXml: `<Document><CstmrCdtTrfInitn><UETR>${base.uetr}</UETR></CstmrCdtTrfInitn></Document>`,
        createdAt: this.now(),
      };
      this.payments.push(instruction);
      this.addHop(instruction, "CUSTOMER_INSTRUCTED", base.instructingAgentBic, `pain.001 received from ${body.debtorName}`);
      const statusReport: FakePayment = {
        ...base,
        id: this.nextId("pay"),
        messageType: "pain.002",
        direction: "OUT",
        status: accepted ? "ACKED" : "REJECTED",
        mxXml: accepted ? "<GrpSts>ACCP</GrpSts>" : `<TxSts>RJCT</TxSts><Cd>${reasonCode}</Cd>`,
        createdAt: this.now(),
      };
      this.payments.push(statusReport);
      let payment: FakePayment | null = null;
      if (accepted) {
        this.addHop(instruction, "CUSTOMER_ACCEPTED", base.instructingAgentBic, "pain.002 ACCP");
        const { reasonCode: _rc, ...draftFields } = base;
        payment = this.seedPayment({ ...draftFields, messageType: "pacs.008", status: "DRAFT" });
        payment.mxXml = `<Document><FIToFICstmrCdtTrf><UETR>${payment.uetr}</UETR></FIToFICstmrCdtTrf></Document>`;
      } else {
        this.addHop(instruction, "CUSTOMER_REJECTED", base.instructingAgentBic, `pain.002 RJCT ${reasonCode}: ${rejectionDetail}`);
      }
      return json(200, { instruction, statusReport, payment, accepted, reasonCode, rejectionDetail });
    }
    const payMatch = path.match(/^\/api\/payments\/([^/]+)(?:\/(submit|approve|recall))?$/);
    if (payMatch) {
      const p = this.payments.find((x) => x.id === payMatch[1] && x.tenantId === me.tenantId);
      if (!p) return apiError(404, "NOT_FOUND", "Payment not found");
      const action = payMatch[2];
      if (method === "GET" && !action) return json(200, p);
      if (method === "POST" && action === "submit") {
        if (p.status !== "DRAFT" && p.status !== "SCREENING_HIT") return apiError(409, "STATE", `Cannot submit from ${p.status}`);
        p.status = p.debtorName === "SANCTIONED PERSON" ? "SCREENING_HIT" : "PENDING_CHECK";
        return json(200, p);
      }
      if (method === "POST" && action === "approve") {
        if (me.role !== "PAYMENT_CHECKER") return apiError(403, "FORBIDDEN", "Only a checker can approve");
        if (p.makerId === me.id) return apiError(403, "FOUR_EYES", "Maker cannot approve their own payment");
        if (headers.get("X-Step-Up-Totp") !== VALID_TOTP) return apiError(401, "STEP_UP", "Step-up TOTP invalid");
        if (p.status !== "PENDING_CHECK") return apiError(409, "STATE", `Cannot approve from ${p.status}`);
        p.checkerId = me.id;
        p.status = "SENT";
        this.addHop(p, "SENT_TO_SWIFT", p.instructingAgentBic, "STUB-NETREF-0001");
        if (!this.holdInbound) this.deliverInbound(p.id);
        return json(200, p);
      }
      // Stage 4: camt.056 → camt.029, and pacs.004 when the stub correspondent honours the reason.
      if (method === "POST" && action === "recall") {
        if (p.direction !== "OUT") return apiError(400, "DIRECTION", "Recall applies to outbound payments");
        if (p.status === "RETURNED") return apiError(409, "STATE", "Payment was already returned");
        if (p.status !== "SENT" && p.status !== "ACKED") return apiError(409, "STATE", "Only a sent payment can be recalled");
        const reason = String(body?.reasonCode || "CUST").toUpperCase();
        const accepted = ["DUPL", "FRAD", "TECH", "AM09", "AC03"].includes(reason);
        const inboundOf = (messageType: string, reasonCode: string): FakePayment => ({
          ...p,
          id: this.nextId("pay"),
          messageType,
          direction: "IN",
          status: "ACKED",
          reasonCode,
          instructingAgentBic: p.instructedAgentBic,
          instructedAgentBic: p.instructingAgentBic,
          createdAt: this.now(),
        });
        const recall: FakePayment = { ...p, id: this.nextId("pay"), messageType: "camt.056", status: "ACKED", reasonCode: reason, createdAt: this.now() };
        this.payments.push(recall);
        this.addHop(p, "RECALL_REQUESTED", p.instructingAgentBic, `camt.056 ${reason}`);
        this.payments.push(inboundOf("camt.029", accepted ? "CNCL" : "RJCR"));
        this.addHop(p, accepted ? "RECALL_ACCEPTED" : "RECALL_REJECTED", p.instructedAgentBic, `camt.029 ${accepted ? "CNCL" : "RJCR"}`);
        if (accepted) {
          this.payments.push(inboundOf("pacs.004", "FOCR"));
          p.status = "RETURNED";
          this.addHop(p, "RETURNED", p.instructedAgentBic, "pacs.004 FOCR funds returned to nostro");
        }
        return json(200, recall);
      }
    }

    // ---- gpi ----
    const gpiMatch = path.match(/^\/api\/gpi\/(.+)$/);
    if (method === "GET" && gpiMatch) {
      return json(
        200,
        this.hops.filter((h) => h.uetr === gpiMatch[1] && h.tenantId === me.tenantId)
      );
    }

    // ---- other tenant screens ----
    if (method === "GET" && path === "/api/templates") {
      const all = url.searchParams.get("includeInactive") === "true";
      return json(200, this.templates.filter((t) => all || t.active));
    }
    if (method === "POST" && path === "/api/templates") {
      const t = { ...body, id: this.nextId("tpl"), active: true };
      this.templates.push(t);
      return json(200, t);
    }
    const tplMatch = path.match(/^\/api\/templates\/([^/]+)$/);
    if (method === "PATCH" && tplMatch) {
      const t = this.templates.find((x) => x.id === tplMatch[1]);
      Object.assign(t, body);
      return json(200, t);
    }
    if (method === "GET" && path === "/api/inbox") {
      return json(200, this.payments.filter((p) => p.tenantId === me.tenantId && p.direction === "IN"));
    }
    if (method === "GET" && path === "/api/users") {
      return json(200, this.users.filter((u) => me.role === "BUREAU_OPERATOR" || u.tenantId === me.tenantId).map((u) => this.publicUser(u)));
    }
    if (method === "POST" && path === "/api/users/invite") {
      this.users.push({ id: this.nextId("u"), loginId: body.loginId, password: "", role: body.role, tenantId: me.tenantId, mfaEnabled: false, status: "INVITED" });
      return json(200, { inviteToken: "INV-TOKEN-1" });
    }
    if (method === "GET" && path === "/api/tenants") return json(200, [{ id: TENANT, legalName: "Meridian Bank", country: "GB", status: "ACTIVE" }]);
    if (method === "GET" && path === "/api/liquidity") {
      return json(200, [
        { account: { id: "acc-1", type: "NOSTRO", currency: "USD", correspondentBic: "CHASUS33" }, bookedBalance: 98500, statementBalance: 100000, variance: 1500 },
      ]);
    }
    if (method === "GET" && path === "/api/rma") return json(200, [{ id: "rma-1", counterpartyBic: "CHASUS33", status: "ACTIVE" }]);
    if (method === "POST" && path === "/api/charges/quote") {
      return json(200, { fee: 25, legacyCode: "SHA", bearer: "SHAR", instructedAmount: 1500 });
    }
    if (method === "GET" && path === "/api/audit") {
      return json(200, [{ id: "a-1", createdAt: "2026-10-30T09:00:00Z", action: "PAYMENT_CREATED", entityType: "PAYMENT", entityId: "pay-1" }]);
    }
    if (method === "GET" && path === "/api/csp/controls") return json(200, [{ id: "1.1", title: "SWIFT environment protection", status: "NOT_STARTED" }]);

    return apiError(404, "NO_ROUTE", `Fake backend has no route for ${method} ${path}`);
  }
}
