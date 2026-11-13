import { FormEvent, ReactNode, useEffect, useId, useState } from "react";
import { NavLink, Navigate, Route, Routes, useNavigate, useParams } from "react-router-dom";
import { api, clearSession, currentUser, setSession, token, User } from "./api";
import { GpiTimeline } from "./components/GpiTimeline";
import { JourneyDots, StatusBadge } from "./components/StatusBadge";
import { GpiHop, IN_FLIGHT_STATUSES } from "./gpi";

type Payment = {
  id: string;
  messageType: string;
  uetr: string;
  status: string;
  amount: number;
  currency: string;
  instructingAgentBic: string;
  instructedAgentBic: string;
  debtorName: string;
  creditorName: string;
  mxXml?: string;
  direction?: string;
  createdAt?: string;
  sentAt?: string;
  chargeBearer?: string;
  screeningStatus?: string;
  /** ISO status/reason code on pain.002, camt.056, camt.029 and pacs.004 (e.g. ACCP, RJCT, CNCL, FOCR). */
  reasonCode?: string | null;
};

/** Outcome of POST /api/payments/initiate (stage 1: pain.001 → pain.002 → pacs.008 draft when accepted). */
type InitiationResult = {
  instruction: Payment;
  statusReport: Payment;
  payment: Payment | null;
  accepted: boolean;
  reasonCode: string;
  rejectionDetail: string | null;
};

export const GPI_POLL_MS = 4000;

/** ISO 20022 cancellation reasons offered on camt.056. The stub correspondent returns funds for all but CUST. */
export const RECALL_REASONS = [
  { code: "CUST", label: "CUST · customer request" },
  { code: "DUPL", label: "DUPL · duplicate payment" },
  { code: "FRAD", label: "FRAD · fraudulent origin" },
  { code: "TECH", label: "TECH · technical problem" },
  { code: "AM09", label: "AM09 · wrong amount" },
  { code: "AC03", label: "AC03 · wrong creditor account" },
];

const RECALLABLE_STATUSES = new Set(["SENT", "ACKED"]);
/** Roles the backend lets raise a camt.056 (PaymentService.recall). */
const RECALL_ROLES = new Set(["PAYMENT_MAKER", "PAYMENT_CHECKER", "BANK_SECURITY_OFFICER"]);
/** Reasons the stub correspondent honours with a pacs.004 return; anything else gets camt.029 RJCR. */
const STUB_RETURNABLE_REASONS = "DUPL, FRAD, TECH, AM09 or AC03";

/** Only the interbank payment itself can be submitted, approved or recalled; pain/camt rows are messages about it. */
export function isInterbankPayment(p: { messageType: string }): boolean {
  return p.messageType === "pacs.008" || p.messageType === "pacs.009";
}

/**
 * What the payments list says about recalls of one payment. `related` is every outbound message
 * sharing the payment's UETR; the newest camt.056 decides the wording.
 */
export function recallSummary(payment: Payment, related: Payment[]): string {
  const recalls = related
    .filter((m) => m.messageType === "camt.056" && m.uetr === payment.uetr)
    .sort((a, b) => (b.createdAt ?? "").localeCompare(a.createdAt ?? ""));
  const last = recalls[0];
  if (payment.status === "RETURNED") return `${last?.reasonCode ?? "Recall"} · returned`;
  if (last) return `${last.reasonCode ?? "Recall"} · ${last.status === "ACKED" ? "rejected" : "awaiting answer"}`;
  return RECALLABLE_STATUSES.has(payment.status) ? "Can be recalled" : "—";
}

function Field({ label, children }: { label: string; children: (id: string) => ReactNode }) {
  const id = useId();
  return (
    <div className="field">
      <label htmlFor={id}>{label}</label>
      {children(id)}
    </div>
  );
}

function PageHead({ eyebrow, title, children }: { eyebrow?: string; title: string; children?: ReactNode }) {
  return (
    <header className="page-head">
      <div>
        {eyebrow && <span className="eyebrow">{eyebrow}</span>}
        <h2>{title}</h2>
      </div>
      {children && <div className="page-head__actions">{children}</div>}
    </header>
  );
}

export default function App() {
  const [user, setUser] = useState<User | null>(currentUser());

  if (!token() || !user) {
    return (
      <Routes>
        <Route path="/invite" element={<InvitePage onAuth={setUser} />} />
        <Route path="*" element={<LoginPage onAuth={setUser} />} />
      </Routes>
    );
  }

  if (!user.mfaEnabled) {
    return <MfaEnrollGate onAuth={setUser} />;
  }

  const bureau = user.role === "BUREAU_OPERATOR";
  return (
    <div className="layout">
      <aside className="side">
        <div className="brand">
          <span className="brand__mark" aria-hidden="true" />
          <div>
            <h1>SWIFT Bureau</h1>
            <p>Correspondent operations</p>
          </div>
        </div>
        <nav aria-label="Main">
          <NavLink to="/" end>Overview</NavLink>
          {bureau && <NavLink to="/tenants">Tenants</NavLink>}
          <NavLink to="/users">Users</NavLink>
          {!bureau && <NavLink to="/payments">Payments</NavLink>}
          {!bureau && <NavLink to="/templates">Templates</NavLink>}
          {!bureau && <NavLink to="/inbox">Inbox</NavLink>}
          {!bureau && <NavLink to="/liquidity">Liquidity</NavLink>}
          {!bureau && <NavLink to="/rma">RMA</NavLink>}
          {!bureau && <NavLink to="/charges">Charges</NavLink>}
          <NavLink to="/audit">Audit</NavLink>
          {bureau && <NavLink to="/csp">CSP pack</NavLink>}
        </nav>
        <div className="whoami">
          <span className="whoami__avatar" aria-hidden="true">
            {user.loginId.slice(0, 2).toUpperCase()}
          </span>
          <div>
            <strong>{user.loginId}</strong>
            <span>{user.role}</span>
          </div>
        </div>
        <button
          className="ghost"
          onClick={() => {
            clearSession();
            setUser(null);
          }}
        >
          Sign out
        </button>
      </aside>
      <main className="main">
        <div className="banner" role="note">
          <span className="banner__tag">SIMULATOR</span>
          Simulator mode by default. Live SWIFT requires SIP, Alliance Cloud, and vendor screening. Do not market this
          runtime as SWIFT-connected.
        </div>
        <Routes>
          <Route path="/" element={<Overview />} />
          <Route path="/tenants" element={<Tenants />} />
          <Route path="/users" element={<Users />} />
          <Route path="/payments" element={<Payments />} />
          <Route path="/payments/new" element={<NewPayment />} />
          <Route path="/payments/:id" element={<PaymentDetail />} />
          <Route path="/templates" element={<Templates />} />
          <Route path="/inbox" element={<Inbox />} />
          <Route path="/liquidity" element={<Liquidity />} />
          <Route path="/rma" element={<Rma />} />
          <Route path="/charges" element={<Charges />} />
          <Route path="/audit" element={<Audit />} />
          <Route path="/csp" element={<Csp />} />
          <Route path="/invite" element={<Navigate to="/" />} />
          <Route path="*" element={<Navigate to="/" />} />
        </Routes>
      </main>
    </div>
  );
}

function AuthShell({ title, subtitle, children }: { title: string; subtitle?: string; children: ReactNode }) {
  return (
    <div className="auth-wrap">
      <div className="auth">
        <div className="brand brand--auth">
          <span className="brand__mark" aria-hidden="true" />
          <span className="eyebrow">SWIFT Bureau · ISO 20022 CBPR+</span>
        </div>
        <h2>{title}</h2>
        {subtitle && <p className="muted">{subtitle}</p>}
        {children}
      </div>
    </div>
  );
}

function LoginPage({ onAuth }: { onAuth: (u: User) => void }) {
  const [loginId, setLoginId] = useState("meridian.maker");
  const [password, setPassword] = useState("ChangeMe_Bank1!");
  const [totp, setTotp] = useState("");
  const [error, setError] = useState("");
  const [enroll, setEnroll] = useState<{ secret: string; otpauth: string } | null>(null);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setError("");
    try {
      const res = await api<any>("/api/auth/login", {
        method: "POST",
        body: JSON.stringify({ loginId, password, totp }),
      });
      if (res.status === "MFA_ENROLLMENT_REQUIRED") {
        setSession(res.enrollmentToken, { id: "", loginId, role: "", tenantId: "", mfaEnabled: false });
        const start = await api<any>("/api/auth/mfa/enroll", { method: "POST" });
        setEnroll(start);
        return;
      }
      setSession(res.accessToken, res.user);
      onAuth(res.user);
    } catch (err) {
      setError((err as Error).message);
    }
  }

  async function confirmEnroll(code: string) {
    const res = await api<any>("/api/auth/mfa/confirm", { method: "POST", body: JSON.stringify({ code }) });
    setSession(res.accessToken, res.user);
    onAuth(res.user);
  }

  return (
    <AuthShell title="Bank user sign-in" subtitle="Invited login ID only. There is no public registration.">
      {enroll ? (
        <form
          key="enroll"
          onSubmit={(e) => {
            e.preventDefault();
            const fd = new FormData(e.currentTarget);
            confirmEnroll(String(fd.get("code"))).catch((err) => setError((err as Error).message));
          }}
        >
          <p>Enroll authenticator with secret:</p>
          <pre>{enroll.secret}</pre>
          <Field label="TOTP">{(id) => <input id={id} name="code" autoFocus inputMode="numeric" />}</Field>
          {error && <p className="err">{error}</p>}
          <button type="submit">Confirm MFA</button>
        </form>
      ) : (
        <form key="login" onSubmit={submit}>
          <Field label="Login ID">
            {(id) => <input id={id} value={loginId} onChange={(e) => setLoginId(e.target.value)} autoComplete="username" />}
          </Field>
          <Field label="Password">
            {(id) => (
              <input
                id={id}
                type="password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                autoComplete="current-password"
              />
            )}
          </Field>
          <Field label="Authenticator code">
            {(id) => (
              <input
                id={id}
                value={totp}
                onChange={(e) => setTotp(e.target.value)}
                placeholder="Required after MFA enrollment"
                inputMode="numeric"
              />
            )}
          </Field>
          {error && <p className="err">{error}</p>}
          <button type="submit" className="wide">Sign in</button>
        </form>
      )}
      <p className="hint">
        Demo TOTP secret for seeded users: <code>JBSWY3DPEHPK3PXP</code>
        <br />
        Bureau: <code>bureau.ops</code> / <code>ChangeMe_Bureau1!</code>
        <br />
        Maker: <code>meridian.maker</code> / <code>ChangeMe_Bank1!</code>
      </p>
    </AuthShell>
  );
}

function MfaEnrollGate({ onAuth }: { onAuth: (u: User) => void }) {
  const [secret, setSecret] = useState("");
  const [code, setCode] = useState("");
  const [error, setError] = useState("");
  useEffect(() => {
    api<any>("/api/auth/mfa/enroll", { method: "POST" })
      .then((res) => setSecret(res.secret))
      .catch((e) => setError((e as Error).message));
  }, []);
  return (
    <AuthShell title="Enroll authenticator" subtitle="MFA is mandatory before using the messaging interface (CSCF 4.2).">
      <pre>{secret}</pre>
      <form
        onSubmit={async (e) => {
          e.preventDefault();
          try {
            const res = await api<any>("/api/auth/mfa/confirm", {
              method: "POST",
              body: JSON.stringify({ code }),
            });
            setSession(res.accessToken, res.user);
            onAuth(res.user);
          } catch (err) {
            setError((err as Error).message);
          }
        }}
      >
        <Field label="TOTP">{(id) => <input id={id} value={code} onChange={(e) => setCode(e.target.value)} />}</Field>
        {error && <p className="err">{error}</p>}
        <button type="submit">Confirm MFA</button>
      </form>
    </AuthShell>
  );
}

function InvitePage({ onAuth }: { onAuth: (u: User) => void }) {
  const [tokenValue, setTokenValue] = useState(new URLSearchParams(window.location.search).get("token") || "");
  const [password, setPassword] = useState("");
  const [error, setError] = useState("");

  async function submit(e: FormEvent) {
    e.preventDefault();
    try {
      const res = await api<any>("/api/auth/accept-invite", {
        method: "POST",
        body: JSON.stringify({ token: tokenValue, password }),
      });
      setSession(res.enrollmentToken, res.user);
      onAuth({ ...res.user, mfaEnabled: false });
    } catch (err) {
      setError((err as Error).message);
    }
  }

  return (
    <AuthShell title="Accept invitation">
      <form onSubmit={submit}>
        <Field label="Invite token">
          {(id) => <input id={id} value={tokenValue} onChange={(e) => setTokenValue(e.target.value)} />}
        </Field>
        <Field label="New password">
          {(id) => <input id={id} type="password" value={password} onChange={(e) => setPassword(e.target.value)} />}
        </Field>
        {error && <p className="err">{error}</p>}
        <button type="submit">Set password</button>
      </form>
    </AuthShell>
  );
}

function Overview() {
  const user = currentUser();
  const [stp, setStp] = useState<any>(null);
  useEffect(() => {
    if (user?.role !== "BUREAU_OPERATOR") {
      api("/api/reports/stp").then(setStp).catch(() => setStp(null));
    }
  }, [user?.role]);
  const rate = stp ? Number(stp.stpRatePercent) : null;
  return (
    <div>
      <PageHead eyebrow="Command centre" title="Overview" />
      <div className="cards">
        <div className="card stat">
          <h3>Role</h3>
          <strong>{user?.role}</strong>
        </div>
        <div className="card stat">
          <h3>STP rate</h3>
          <strong data-testid="stp-rate">{stp ? `${stp.stpRatePercent}%` : "—"}</strong>
          {rate != null && !Number.isNaN(rate) && (
            <div className="meter meter--sm">
              <div className="meter__fill" style={{ width: `${Math.min(100, Math.max(0, rate))}%` }} />
            </div>
          )}
        </div>
        <div className="card stat">
          <h3>Messages</h3>
          <strong>{stp ? stp.total : "—"}</strong>
        </div>
      </div>
    </div>
  );
}

function Tenants() {
  const [rows, setRows] = useState<any[]>([]);
  const [legalName, setLegalName] = useState("");
  const [country, setCountry] = useState("GB");
  const [error, setError] = useState("");
  useEffect(() => {
    api<any[]>("/api/tenants").then(setRows).catch((e) => setError((e as Error).message));
  }, []);
  return (
    <div>
      <PageHead eyebrow="Onboarding" title="Bank tenants" />
      <form
        className="row row--3 panel"
        onSubmit={async (e) => {
          e.preventDefault();
          try {
            await api("/api/tenants", { method: "POST", body: JSON.stringify({ legalName, country, expectedVolumes: "pilot" }) });
            setRows(await api("/api/tenants"));
          } catch (err) {
            setError((err as Error).message);
          }
        }}
      >
        <Field label="Legal name">{(id) => <input id={id} value={legalName} onChange={(e) => setLegalName(e.target.value)} />}</Field>
        <Field label="Country">{(id) => <input id={id} value={country} onChange={(e) => setCountry(e.target.value)} />}</Field>
        <div className="field field--action">
          <button type="submit">Create draft tenant</button>
        </div>
      </form>
      {error && <p className="err">{error}</p>}
      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th>Name</th>
              <th>Country</th>
              <th>Status</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((t) => (
              <tr key={t.id}>
                <td>{t.legalName}</td>
                <td>{t.country}</td>
                <td>
                  <StatusBadge status={t.status} />
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}

function Users() {
  const user = currentUser();
  const [rows, setRows] = useState<any[]>([]);
  const [loginId, setLoginId] = useState("");
  const [email, setEmail] = useState("");
  const [role, setRole] = useState("PAYMENT_MAKER");
  const [invite, setInvite] = useState("");
  const [error, setError] = useState("");
  useEffect(() => {
    api<any[]>("/api/users").then(setRows).catch((e) => setError((e as Error).message));
  }, []);
  const canInvite = user?.role === "BANK_SECURITY_OFFICER" || user?.role === "BUREAU_OPERATOR";
  return (
    <div>
      <PageHead eyebrow="Access" title="Users" />
      {canInvite && (
        <form
          className="row row--4 panel"
          onSubmit={async (e) => {
            e.preventDefault();
            try {
              const res = await api<any>("/api/users/invite", {
                method: "POST",
                body: JSON.stringify({ loginId, email, role }),
              });
              setInvite(res.inviteToken);
              setRows(await api("/api/users"));
            } catch (err) {
              setError((err as Error).message);
            }
          }}
        >
          <Field label="Login ID">{(id) => <input id={id} value={loginId} onChange={(e) => setLoginId(e.target.value)} />}</Field>
          <Field label="Email">{(id) => <input id={id} value={email} onChange={(e) => setEmail(e.target.value)} />}</Field>
          <Field label="Role">
            {(id) => (
              <select id={id} value={role} onChange={(e) => setRole(e.target.value)}>
                <option>PAYMENT_MAKER</option>
                <option>PAYMENT_CHECKER</option>
                <option>LIQUIDITY</option>
                <option>AUDITOR</option>
                <option>BANK_SECURITY_OFFICER</option>
                {user?.role === "BUREAU_OPERATOR" && <option>BUREAU_OPERATOR</option>}
              </select>
            )}
          </Field>
          <div className="field field--action">
            <button type="submit">Invite</button>
          </div>
        </form>
      )}
      {error && <p className="err">{error}</p>}
      {invite && (
        <p className="callout">
          Out-of-band invite token: <code>{invite}</code> — share on a recorded channel, then /invite
        </p>
      )}
      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th>Login</th>
              <th>Role</th>
              <th>Status</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((u) => (
              <tr key={u.id}>
                <td>{u.loginId}</td>
                <td>{u.role}</td>
                <td>
                  <StatusBadge status={u.status} />
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}

function Payments() {
  const [rows, setRows] = useState<Payment[]>([]);
  const [error, setError] = useState("");
  const [loaded, setLoaded] = useState(false);
  const nav = useNavigate();
  const user = currentUser();
  const canCreate = user?.role === "PAYMENT_MAKER" || user?.role === "BANK_SECURITY_OFFICER";
  useEffect(() => {
    api<Payment[]>("/api/payments?direction=OUT")
      .then(setRows)
      .catch((e) => setError((e as Error).message))
      .finally(() => setLoaded(true));
  }, []);
  // The API returns every outbound message. pain.002 and camt.056 are messages about a payment,
  // not payments, so they stay out of the list unless asked for: none of them can be recalled.
  const [showRelated, setShowRelated] = useState(false);
  const relatedCount = rows.filter((p) => !isInterbankPayment(p)).length;
  const visible = showRelated ? rows : rows.filter(isInterbankPayment);
  return (
    <div>
      <PageHead eyebrow="pacs.008 · outbound" title="Outbound payments">
        {canCreate && <button onClick={() => nav("/payments/new")}>New pacs.008</button>}
      </PageHead>
      {relatedCount > 0 && (
        <label className="muted small toggle">
          <input type="checkbox" checked={showRelated} onChange={(e) => setShowRelated(e.target.checked)} /> Show related
          messages ({relatedCount} pain.002 / camt.056)
        </label>
      )}
      {!canCreate && (
        <p className="muted">
          Only a payment maker or bank security officer can create drafts. Sign in as <code>meridian.maker</code>.
        </p>
      )}
      {error && <p className="err">{error}</p>}
      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th>UETR</th>
              <th>Type</th>
              <th>Status</th>
              <th>Journey</th>
              <th>Recall</th>
              <th className="num">Amount</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            {loaded && visible.length === 0 && !error && (
              <tr>
                <td colSpan={7} className="empty">
                  No outbound payments yet.
                </td>
              </tr>
            )}
            {visible.map((p) => (
              <tr key={p.id}>
                <td>
                  <code className="mono">{p.uetr}</code>
                </td>
                <td>
                  {p.messageType}
                  {!isInterbankPayment(p) && p.reasonCode ? ` · ${p.reasonCode}` : ""}
                </td>
                <td>
                  <StatusBadge status={p.status} />
                </td>
                <td className="journey">{isInterbankPayment(p) ? <JourneyDots status={p.status} /> : "—"}</td>
                <td className="small">{isInterbankPayment(p) ? recallSummary(p, rows) : "—"}</td>
                <td className="num">
                  {formatAmount(p.amount)} <span className="ccy">{p.currency}</span>
                </td>
                <td>
                  <button className="secondary" onClick={() => nav(`/payments/${p.id}`)}>
                    Open
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}

export function formatAmount(amount: number | string | null | undefined): string {
  if (amount == null || amount === "") return "—";
  const n = Number(amount);
  if (Number.isNaN(n)) return String(amount);
  return n.toLocaleString("en-US", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

const FIELD_LABELS: Record<string, string> = {
  name: "Template name",
  description: "Description",
  messageType: "Message type",
  instructingAgentBic: "Instructing agent BIC",
  instructedAgentBic: "Instructed agent BIC",
  amount: "Amount",
  currency: "Currency",
  debtorName: "Debtor name",
  debtorStreet: "Debtor street",
  debtorTown: "Debtor town",
  debtorCountry: "Debtor country",
  creditorName: "Creditor name",
  creditorStreet: "Creditor street",
  creditorTown: "Creditor town",
  creditorCountry: "Creditor country",
  chargeBearer: "Charge bearer",
};

function fieldLabel(key: string) {
  return FIELD_LABELS[key] ?? key;
}

function NewPayment() {
  const nav = useNavigate();
  const [error, setError] = useState("");
  const [templates, setTemplates] = useState<any[]>([]);
  const [selected, setSelected] = useState("");
  // "portal": maker drafts the pacs.008 directly. "customer": the bank receives a pain.001 and answers with pain.002.
  const [channel, setChannel] = useState<"portal" | "customer">("portal");
  const [rejection, setRejection] = useState<InitiationResult | null>(null);
  const [form, setForm] = useState({
    messageType: "pacs.008",
    instructingAgentBic: "MIDNGB2L",
    instructedAgentBic: "CHASUS33",
    amount: "1500.00",
    currency: "USD",
    debtorName: "Acme Ltd",
    debtorStreet: "1 King Street",
    debtorTown: "London",
    debtorCountry: "GB",
    creditorName: "Widget LLC",
    creditorStreet: "200 West Street",
    creditorTown: "New York",
    creditorCountry: "US",
    chargeBearer: "SHAR",
  });
  useEffect(() => {
    api<any[]>("/api/templates").then(setTemplates).catch(() => setTemplates([]));
  }, []);
  function applyTemplate(id: string) {
    setSelected(id);
    const t = templates.find((row) => row.id === id);
    if (!t) {
      return;
    }
    setForm({
      messageType: t.messageType || "pacs.008",
      instructingAgentBic: t.instructingAgentBic || "",
      instructedAgentBic: t.instructedAgentBic || "",
      amount: t.amount != null ? String(t.amount) : "",
      currency: t.currency || "USD",
      debtorName: t.debtorName || "",
      debtorStreet: t.debtorStreet || "",
      debtorTown: t.debtorTown || "",
      debtorCountry: t.debtorCountry || "",
      creditorName: t.creditorName || "",
      creditorStreet: t.creditorStreet || "",
      creditorTown: t.creditorTown || "",
      creditorCountry: t.creditorCountry || "",
      chargeBearer: t.chargeBearer || "SHAR",
    });
  }
  const groups: { title: string; keys: (keyof typeof form)[] }[] = [
    { title: "Message", keys: ["messageType", "instructingAgentBic", "instructedAgentBic", "amount", "currency", "chargeBearer"] },
    { title: "Debtor", keys: ["debtorName", "debtorStreet", "debtorTown", "debtorCountry"] },
    { title: "Creditor", keys: ["creditorName", "creditorStreet", "creditorTown", "creditorCountry"] },
  ];
  return (
    <div>
      <PageHead eyebrow={channel === "customer" ? "pain.001 · customer instruction" : "pacs.008 · draft"} title="Create payment" />
      <p className="muted">
        Structured town and country are mandatory (CBPR+ November 2026). Sign in as meridian.maker to save a draft.
      </p>
      <div className="panel">
        <Field label="Channel">
          {(id) => (
            <select
              id={id}
              value={channel}
              onChange={(e) => {
                setChannel(e.target.value as "portal" | "customer");
                setRejection(null);
              }}
            >
              <option value="portal">Portal draft (pacs.008)</option>
              <option value="customer">Customer instruction (pain.001 → pain.002)</option>
            </select>
          )}
        </Field>
        <Field label="Use template">
          {(id) => (
            <select id={id} value={selected} onChange={(e) => applyTemplate(e.target.value)}>
              <option value="">Blank form</option>
              {templates.map((t) => (
                <option key={t.id} value={t.id}>
                  {t.name}
                </option>
              ))}
            </select>
          )}
        </Field>
      </div>
      <form
        onSubmit={async (e) => {
          e.preventDefault();
          setError("");
          setRejection(null);
          const amount = form.amount === "" ? null : Number(form.amount);
          try {
            if (channel === "customer") {
              const { messageType: _mt, instructingAgentBic, instructedAgentBic, ...parties } = form;
              const result = await api<InitiationResult>("/api/payments/initiate", {
                method: "POST",
                body: JSON.stringify({
                  ...parties,
                  amount,
                  debtorAgentBic: instructingAgentBic,
                  creditorAgentBic: instructedAgentBic,
                }),
              });
              if (result.accepted && result.payment) {
                nav(`/payments/${result.payment.id}`);
              } else {
                setRejection(result);
              }
              return;
            }
            const created = await api<Payment>("/api/payments", {
              method: "POST",
              body: JSON.stringify({
                ...form,
                amount,
                templateId: selected || null,
              }),
            });
            nav(`/payments/${created.id}`);
          } catch (err) {
            setError((err as Error).message);
          }
        }}
      >
        <div className="form-groups">
          {groups.map((g) => (
            <fieldset key={g.title} className="panel">
              <legend>{g.title}</legend>
              {g.keys.map((k) => (
                <Field key={k} label={fieldLabel(k)}>
                  {(id) => <input id={id} value={form[k]} onChange={(e) => setForm({ ...form, [k]: e.target.value })} />}
                </Field>
              ))}
            </fieldset>
          ))}
        </div>
        {error && <p className="err">{error}</p>}
        {rejection && (
          <div className="panel" role="status" data-testid="pain002">
            <strong>pain.002 RJCT · {rejection.reasonCode}</strong>
            <p className="muted">{rejection.rejectionDetail || "The bank rejected the customer instruction."}</p>
            <p className="muted small">No pacs.008 was drafted. UETR {rejection.instruction.uetr}</p>
            <button type="button" className="secondary" onClick={() => nav(`/payments/${rejection.statusReport.id}`)}>
              View pain.002
            </button>
          </div>
        )}
        <button type="submit">{channel === "customer" ? "Send pain.001" : "Save draft"}</button>
      </form>
    </div>
  );
}

function Templates() {
  const user = currentUser();
  const canEdit = user?.role === "BANK_SECURITY_OFFICER";
  const [rows, setRows] = useState<any[]>([]);
  const [error, setError] = useState("");
  const blank = {
    name: "",
    description: "",
    messageType: "pacs.008",
    instructingAgentBic: "MIDNGB2L",
    instructedAgentBic: "CHASUS33",
    amount: "",
    currency: "USD",
    debtorName: "Acme Ltd",
    debtorStreet: "1 King Street",
    debtorTown: "London",
    debtorCountry: "GB",
    creditorName: "Widget LLC",
    creditorStreet: "200 West Street",
    creditorTown: "New York",
    creditorCountry: "US",
    chargeBearer: "SHAR",
    active: true,
  };
  const [form, setForm] = useState({ ...blank });
  async function reload() {
    const path = canEdit ? "/api/templates?includeInactive=true" : "/api/templates";
    setRows(await api(path));
  }
  useEffect(() => {
    reload().catch((e) => setError((e as Error).message));
  }, []);
  return (
    <div>
      <PageHead eyebrow="CBPR+ presets" title="Message templates" />
      <p className="muted">Reusable CBPR+ fields. Applying a template creates a new draft and a new UETR — it does not send SWIFT.</p>
      {canEdit && (
        <form
          className="panel"
          onSubmit={async (e) => {
            e.preventDefault();
            try {
              await api("/api/templates", {
                method: "POST",
                body: JSON.stringify({
                  ...form,
                  amount: form.amount === "" ? null : Number(form.amount),
                }),
              });
              setForm({ ...blank });
              await reload();
            } catch (err) {
              setError((err as Error).message);
            }
          }}
        >
          <div className="grid-fields">
            {Object.entries(form)
              .filter(([k]) => k !== "active")
              .map(([k, v]) => (
                <Field key={k} label={fieldLabel(k)}>
                  {(id) => <input id={id} value={String(v)} onChange={(e) => setForm({ ...form, [k]: e.target.value })} />}
                </Field>
              ))}
          </div>
          <button type="submit">Save template</button>
        </form>
      )}
      {error && <p className="err">{error}</p>}
      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th>Name</th>
              <th>Type</th>
              <th>To BIC</th>
              <th>CCY</th>
              <th>Status</th>
              {canEdit && <th></th>}
            </tr>
          </thead>
          <tbody>
            {rows.map((t) => (
              <tr key={t.id}>
                <td>{t.name}</td>
                <td>{t.messageType}</td>
                <td>
                  <code className="mono">{t.instructedAgentBic}</code>
                </td>
                <td>{t.currency}</td>
                <td>
                  <StatusBadge status={t.active ? "ACTIVE" : "INACTIVE"} />
                </td>
                {canEdit && (
                  <td>
                    <button
                      className="secondary"
                      onClick={async () => {
                        try {
                          await api(`/api/templates/${t.id}`, {
                            method: "PATCH",
                            body: JSON.stringify({ ...t, active: !t.active }),
                          });
                          await reload();
                        } catch (err) {
                          setError((err as Error).message);
                        }
                      }}
                    >
                      {t.active ? "Deactivate" : "Activate"}
                    </button>
                  </td>
                )}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}

function PaymentDetail() {
  const { id } = useParams();
  const [p, setP] = useState<Payment | null>(null);
  const [totp, setTotp] = useState("");
  const [hops, setHops] = useState<GpiHop[]>([]);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [copied, setCopied] = useState(false);
  const [recallReason, setRecallReason] = useState("CUST");
  const user = currentUser();
  const [original, setOriginal] = useState<Payment | null>(null);
  async function reload() {
    const row = await api<Payment>(`/api/payments/${id}`);
    setP(row);
    setHops((await api<GpiHop[]>(`/api/gpi/${row.uetr}`)) ?? []);
    // A pain.002 / camt.056 / camt.029 is about a payment: find that payment so the user can get to it.
    if (isInterbankPayment(row)) {
      setOriginal(null);
    } else {
      const outbound = (await api<Payment[]>("/api/payments?direction=OUT").catch(() => [])) ?? [];
      setOriginal(outbound.find((m) => m.uetr === row.uetr && isInterbankPayment(m)) ?? null);
    }
  }
  async function act(fn: () => Promise<unknown>) {
    setError("");
    setBusy(true);
    try {
      await fn();
      await reload();
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setBusy(false);
    }
  }
  useEffect(() => {
    reload().catch((e) => setError(e.message));
  }, [id]);

  // Keep the tracker live while the payment is moving through the network.
  const inFlight = p != null && IN_FLIGHT_STATUSES.has(p.status);
  useEffect(() => {
    if (!inFlight) return;
    const timer = window.setInterval(() => {
      reload().catch(() => undefined);
    }, GPI_POLL_MS);
    return () => window.clearInterval(timer);
  }, [inFlight, id]);

  if (!p) return <p className={error ? "err" : "muted loading"}>{error || "Loading…"}</p>;
  // Only a sent interbank payment can be recalled; status/notification messages and drafts cannot.
  const isPayment = isInterbankPayment(p);
  const mayRecall = RECALL_ROLES.has(user?.role ?? "");
  const canRecall = (p.direction ?? "OUT") === "OUT" && isPayment && RECALLABLE_STATUSES.has(p.status) && mayRecall;
  // Say why the recall form is or is not there, and what the last recall came to.
  const lastRecallHop = [...hops].reverse().find((h) => h.hopStatus.startsWith("RECALL_"));
  let recallHint: string;
  if (!isPayment) {
    recallHint = `This ${p.messageType} is a message about a payment, not a payment, so it cannot be submitted, approved or recalled. A recall is raised on the original pacs.008.`;
  } else if (p.status === "RETURNED") {
    recallHint = "Funds were returned with a pacs.004, so this payment cannot be recalled again.";
  } else if (!RECALLABLE_STATUSES.has(p.status)) {
    recallHint = `A camt.056 recall is offered once the payment has been sent (status SENT or ACKED). This one is ${p.status}.`;
  } else if (!mayRecall) {
    recallHint = "Only a payment maker, checker or bank security officer can raise a camt.056 recall.";
  } else if (lastRecallHop?.hopStatus === "RECALL_REJECTED") {
    recallHint = `The last recall was refused by the correspondent (camt.029 RJCR), so the funds stay with the beneficiary. The stub correspondent only returns funds for ${STUB_RETURNABLE_REASONS}.`;
  } else {
    recallHint = `Simulator: the stub correspondent returns the funds (pacs.004) for ${STUB_RETURNABLE_REASONS} and refuses CUST.`;
  }
  return (
    <div className="detail">
      <section className="hero" aria-label="Payment summary">
        <div className="hero__main">
          <span className="eyebrow">
            {p.messageType} · {p.direction ?? "OUT"}
            {p.reasonCode ? ` · ${p.reasonCode}` : ""}
          </span>
          <h2>
            {p.messageType} <StatusBadge status={p.status} />
          </h2>
          <div className="hero__amount">
            {formatAmount(p.amount)} <span className="ccy">{p.currency}</span>
          </div>
          <div className="uetr">
            <span className="eyebrow">UETR</span>
            <code data-testid="uetr">{p.uetr}</code>
            <button
              type="button"
              className="icon"
              aria-label="Copy UETR"
              onClick={() => {
                navigator.clipboard?.writeText(p.uetr).then(() => setCopied(true), () => undefined);
              }}
            >
              {copied ? "Copied" : "Copy"}
            </button>
            <span className="muted small">never rewritten</span>
          </div>
        </div>
        <dl className="parties">
          <div>
            <dt>Debtor</dt>
            <dd>{p.debtorName}</dd>
          </div>
          <div>
            <dt>Creditor</dt>
            <dd>{p.creditorName}</dd>
          </div>
          <div>
            <dt>From</dt>
            <dd>
              <code>{p.instructingAgentBic}</code>
            </dd>
          </div>
          <div>
            <dt>To</dt>
            <dd>
              <code>{p.instructedAgentBic}</code>
            </dd>
          </div>
        </dl>
      </section>

      <div className="actions">
        {isPayment && user?.role === "PAYMENT_MAKER" && (
          <button disabled={busy} onClick={() => act(() => api(`/api/payments/${p.id}/submit`, { method: "POST" }))}>
            Submit for screening
          </button>
        )}
        {isPayment && user?.role === "PAYMENT_CHECKER" && (
          <form
            className="approve"
            onSubmit={(e) => {
              e.preventDefault();
              act(() =>
                api(`/api/payments/${p.id}/approve`, {
                  method: "POST",
                  headers: { "X-Step-Up-Totp": totp },
                })
              );
            }}
          >
            <Field label="Step-up TOTP">
              {(fid) => (
                <input id={fid} value={totp} onChange={(e) => setTotp(e.target.value)} inputMode="numeric" autoComplete="one-time-code" />
              )}
            </Field>
            <button type="submit" disabled={busy}>
              Approve and release
            </button>
          </form>
        )}
        {canRecall && (
          <form
            className="approve recall"
            onSubmit={(e) => {
              e.preventDefault();
              act(() =>
                api(`/api/payments/${p.id}/recall`, {
                  method: "POST",
                  body: JSON.stringify({ reasonCode: recallReason }),
                })
              );
            }}
          >
            <Field label="Recall reason">
              {(fid) => (
                <select id={fid} value={recallReason} onChange={(e) => setRecallReason(e.target.value)}>
                  {RECALL_REASONS.map((r) => (
                    <option key={r.code} value={r.code}>
                      {r.label}
                    </option>
                  ))}
                </select>
              )}
            </Field>
            <button type="submit" className="secondary" disabled={busy}>
              camt.056 recall
            </button>
          </form>
        )}
        <button className="ghost-dark" disabled={busy} onClick={() => act(async () => undefined)}>
          Refresh tracker
        </button>
        {inFlight && <span className="live">Live</span>}
      </div>
      <p className="muted small" data-testid="recall-hint">
        {recallHint}{" "}
        {original && (
          <NavLink to={`/payments/${original.id}`}>
            Open the original {original.messageType} ({original.status})
          </NavLink>
        )}
      </p>
      {error && <p className="err">{error}</p>}

      <GpiTimeline payment={p} hops={hops} />

      <details className="mx">
        <summary>MX message (ISO 20022 XML)</summary>
        <pre>{p.mxXml || "No MX generated yet."}</pre>
      </details>
    </div>
  );
}

function Inbox() {
  const [rows, setRows] = useState<Payment[]>([]);
  const [error, setError] = useState("");
  useEffect(() => {
    api<Payment[]>("/api/inbox").then(setRows).catch((e) => setError((e as Error).message));
  }, []);
  return (
    <div>
      <PageHead eyebrow="pacs.002 · camt" title="Inbound (routed by BIC)" />
      {error && <p className="err">{error}</p>}
      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th>Type</th>
              <th>UETR</th>
              <th>Status</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((p) => (
              <tr key={p.id}>
                <td>{p.messageType}</td>
                <td>
                  <code className="mono">{p.uetr}</code>
                </td>
                <td>
                  <StatusBadge status={p.status} />
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}

function Liquidity() {
  const [rows, setRows] = useState<any[]>([]);
  const [error, setError] = useState("");
  useEffect(() => {
    api<any[]>("/api/liquidity").then(setRows).catch((e) => setError((e as Error).message));
  }, []);
  return (
    <div>
      <PageHead eyebrow="Shadow books" title="Nostro / vostro liquidity" />
      <p className="muted">Shadow books only — not bureau funds.</p>
      {error && <p className="err">{error}</p>}
      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th>Type</th>
              <th>CCY</th>
              <th>Correspondent</th>
              <th className="num">Booked</th>
              <th className="num">Statement</th>
              <th className="num">Variance</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r) => (
              <tr key={r.account.id}>
                <td>{r.account.type}</td>
                <td>{r.account.currency}</td>
                <td>
                  <code className="mono">{r.account.correspondentBic}</code>
                </td>
                <td className="num">{r.bookedBalance}</td>
                <td className="num">{r.statementBalance}</td>
                <td className={`num ${Number(r.variance) !== 0 ? "warn-text" : ""}`}>{r.variance}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}

function Rma() {
  const [rows, setRows] = useState<any[]>([]);
  const [ourBic, setOurBic] = useState("MIDNGB2L");
  const [counterpartyBic, setCounterpartyBic] = useState("");
  const [error, setError] = useState("");
  useEffect(() => {
    api<any[]>("/api/rma").then(setRows).catch((e) => setError((e as Error).message));
  }, []);
  return (
    <div>
      <PageHead eyebrow="Relationship management" title="RMA relationships" />
      <form
        className="row row--3 panel"
        onSubmit={async (e) => {
          e.preventDefault();
          try {
            await api("/api/rma", { method: "POST", body: JSON.stringify({ ourBic, counterpartyBic }) });
            setRows(await api("/api/rma"));
          } catch (err) {
            setError((err as Error).message);
          }
        }}
      >
        <Field label="Our BIC">{(id) => <input id={id} value={ourBic} onChange={(e) => setOurBic(e.target.value)} />}</Field>
        <Field label="Counterparty BIC">
          {(id) => <input id={id} value={counterpartyBic} onChange={(e) => setCounterpartyBic(e.target.value)} />}
        </Field>
        <div className="field field--action">
          <button type="submit">Create pending RMA</button>
        </div>
      </form>
      {error && <p className="err">{error}</p>}
      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th>Counterparty</th>
              <th>Status</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r) => (
              <tr key={r.id}>
                <td>
                  <code className="mono">{r.counterpartyBic}</code>
                </td>
                <td>
                  <StatusBadge status={r.status} />
                </td>
                <td>
                  {r.status === "PENDING" && (
                    <button
                      className="secondary"
                      onClick={async () => {
                        try {
                          await api(`/api/rma/${r.id}/activate`, { method: "POST" });
                          setRows(await api("/api/rma"));
                        } catch (err) {
                          setError((err as Error).message);
                        }
                      }}
                    >
                      Activate (second officer)
                    </button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}

function Charges() {
  const [quote, setQuote] = useState<any>(null);
  const [error, setError] = useState("");
  return (
    <div>
      <PageHead eyebrow="Fees" title="Charge engine" />
      <button
        onClick={async () => {
          try {
            setQuote(
              await api("/api/charges/quote", {
                method: "POST",
                body: JSON.stringify({
                  fromBic: "MIDNGB2L",
                  toBic: "CHASUS33",
                  currency: "USD",
                  bearer: "SHAR",
                  amount: 1500,
                }),
              })
            );
          } catch (err) {
            setError((err as Error).message);
          }
        }}
      >
        Quote USD to CHASUS33
      </button>
      {error && <p className="err">{error}</p>}
      {quote && (
        <div className="cards" style={{ marginTop: "1rem" }}>
          <div className="card stat">
            <h3>Fee</h3>
            <strong>{quote.fee}</strong>
          </div>
          <div className="card stat">
            <h3>Code / bearer</h3>
            <strong>
              {quote.legacyCode} / {quote.bearer}
            </strong>
          </div>
          <div className="card stat">
            <h3>Instructed</h3>
            <strong>{quote.instructedAmount}</strong>
          </div>
        </div>
      )}
    </div>
  );
}

function Audit() {
  const [rows, setRows] = useState<any[]>([]);
  const [error, setError] = useState("");
  useEffect(() => {
    api<any[]>("/api/audit").then(setRows).catch((e) => setError((e as Error).message));
  }, []);
  return (
    <div>
      <PageHead eyebrow="Append-only" title="Immutable audit log" />
      {error && <p className="err">{error}</p>}
      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th>When</th>
              <th>Action</th>
              <th>Entity</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r) => (
              <tr key={r.id}>
                <td>
                  <time className="mono">{r.createdAt}</time>
                </td>
                <td>{r.action}</td>
                <td>
                  {r.entityType} <code className="mono">{r.entityId}</code>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}

function Csp() {
  const [rows, setRows] = useState<any[]>([]);
  const [error, setError] = useState("");
  useEffect(() => {
    api<any[]>("/api/csp/controls").then(setRows).catch((e) => setError((e as Error).message));
  }, []);
  async function run(fn: () => Promise<unknown>) {
    try {
      await fn();
      setRows(await api("/api/csp/controls"));
    } catch (err) {
      setError((err as Error).message);
    }
  }
  return (
    <div>
      <PageHead eyebrow="Customer Security Programme" title="CSCF v2026 attestation">
        <button
          onClick={async () => {
            try {
              const pack = await api<any>("/api/csp/pack");
              const blob = new Blob([JSON.stringify(pack, null, 2)], { type: "application/json" });
              const a = document.createElement("a");
              a.href = URL.createObjectURL(blob);
              a.download = "csp-customer-attestation-pack.json";
              a.click();
            } catch (err) {
              setError((err as Error).message);
            }
          }}
        >
          Download customer pack
        </button>
      </PageHead>
      {error && <p className="err">{error}</p>}
      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th>Control</th>
              <th>Title</th>
              <th>Status</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            {rows.map((c) => (
              <tr key={c.id}>
                <td>
                  <code className="mono">{c.id}</code>
                </td>
                <td>{c.title}</td>
                <td>
                  <StatusBadge status={c.status} />
                </td>
                <td className="btn-row">
                  <button
                    className="secondary"
                    onClick={() =>
                      run(() =>
                        api("/api/csp/attest", {
                          method: "POST",
                          body: JSON.stringify({
                            controlId: c.id,
                            status: "IMPLEMENTED",
                            evidenceNotes: "Recorded in secure-zone design",
                          }),
                        })
                      )
                    }
                  >
                    Attest
                  </button>
                  <button className="secondary" onClick={() => run(() => api(`/api/csp/confirm/${c.id}`, { method: "POST" }))}>
                    Confirm (2nd ops)
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
