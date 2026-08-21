import { FormEvent, useEffect, useState } from "react";
import { NavLink, Navigate, Route, Routes, useNavigate, useParams } from "react-router-dom";
import { api, clearSession, currentUser, setSession, token, User } from "./api";

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
};

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
        <h1>SWIFT Bureau</h1>
        <p>Correspondent operations</p>
        <nav>
          <NavLink to="/">Overview</NavLink>
          {bureau && <NavLink to="/tenants">Tenants</NavLink>}
          <NavLink to="/users">Users</NavLink>
          {!bureau && <NavLink to="/payments">Payments</NavLink>}
          {!bureau && <NavLink to="/inbox">Inbox</NavLink>}
          {!bureau && <NavLink to="/liquidity">Liquidity</NavLink>}
          {!bureau && <NavLink to="/rma">RMA</NavLink>}
          {!bureau && <NavLink to="/charges">Charges</NavLink>}
          <NavLink to="/audit">Audit</NavLink>
          {bureau && <NavLink to="/csp">CSP pack</NavLink>}
        </nav>
        <p style={{ marginTop: "2rem", fontSize: "0.8rem" }}>
          {user.loginId}
          <br />
          {user.role}
        </p>
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
        <div className="banner">
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
    <div className="auth">
      <h2>Bank user sign-in</h2>
      <p>Invited login ID only. There is no public registration.</p>
      {enroll ? (
        <form
          onSubmit={(e) => {
            e.preventDefault();
            const fd = new FormData(e.currentTarget);
            confirmEnroll(String(fd.get("code")));
          }}
        >
          <p>Enroll authenticator with secret:</p>
          <pre>{enroll.secret}</pre>
          <label>TOTP</label>
          <input name="code" autoFocus />
          <button type="submit">Confirm MFA</button>
        </form>
      ) : (
        <form onSubmit={submit}>
          <label>Login ID</label>
          <input value={loginId} onChange={(e) => setLoginId(e.target.value)} />
          <label>Password</label>
          <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} />
          <label>Authenticator code</label>
          <input value={totp} onChange={(e) => setTotp(e.target.value)} placeholder="Required after MFA enrollment" />
          {error && <p className="err">{error}</p>}
          <button type="submit">Sign in</button>
        </form>
      )}
      <p style={{ fontSize: "0.8rem", color: "#5c6b7a" }}>
        Demo TOTP secret for seeded users: <code>JBSWY3DPEHPK3PXP</code>
        <br />
        Bureau: <code>bureau.ops</code> / <code>ChangeMe_Bureau1!</code>
        <br />
        Maker: <code>meridian.maker</code> / <code>ChangeMe_Bank1!</code>
      </p>
    </div>
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
    <div className="auth">
      <h2>Enroll authenticator</h2>
      <p>MFA is mandatory before using the messaging interface (CSCF 4.2).</p>
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
        <label>TOTP</label>
        <input value={code} onChange={(e) => setCode(e.target.value)} />
        {error && <p className="err">{error}</p>}
        <button type="submit">Confirm MFA</button>
      </form>
    </div>
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
    <div className="auth">
      <h2>Accept invitation</h2>
      <form onSubmit={submit}>
        <label>Invite token</label>
        <input value={tokenValue} onChange={(e) => setTokenValue(e.target.value)} />
        <label>New password</label>
        <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} />
        {error && <p className="err">{error}</p>}
        <button type="submit">Set password</button>
      </form>
    </div>
  );
}

function Overview() {
  const user = currentUser();
  const [stp, setStp] = useState<any>(null);
  useEffect(() => {
    if (user?.role !== "BUREAU_OPERATOR") {
      api("/api/reports/stp").then(setStp).catch(() => setStp(null));
    }
  }, [user]);
  return (
    <div>
      <h2>Overview</h2>
      <div className="cards">
        <div className="card">
          <h3>Role</h3>
          <strong>{user?.role}</strong>
        </div>
        <div className="card">
          <h3>STP rate</h3>
          <strong>{stp ? `${stp.stpRatePercent}%` : "—"}</strong>
        </div>
        <div className="card">
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
  useEffect(() => {
    api<any[]>("/api/tenants").then(setRows);
  }, []);
  return (
    <div>
      <h2>Bank tenants</h2>
      <form
        className="row"
        onSubmit={async (e) => {
          e.preventDefault();
          await api("/api/tenants", { method: "POST", body: JSON.stringify({ legalName, country, expectedVolumes: "pilot" }) });
          setRows(await api("/api/tenants"));
        }}
      >
        <div>
          <label>Legal name</label>
          <input value={legalName} onChange={(e) => setLegalName(e.target.value)} />
        </div>
        <div>
          <label>Country</label>
          <input value={country} onChange={(e) => setCountry(e.target.value)} />
        </div>
        <div>
          <label>&nbsp;</label>
          <button type="submit">Create draft tenant</button>
        </div>
      </form>
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
              <td>{t.status}</td>
            </tr>
          ))}
        </tbody>
      </table>
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
  useEffect(() => {
    api<any[]>("/api/users").then(setRows);
  }, []);
  const canInvite = user?.role === "BANK_SECURITY_OFFICER" || user?.role === "BUREAU_OPERATOR";
  return (
    <div>
      <h2>Users</h2>
      {canInvite && (
        <form
          className="row"
          onSubmit={async (e) => {
            e.preventDefault();
            const res = await api<any>("/api/users/invite", {
              method: "POST",
              body: JSON.stringify({ loginId, email, role }),
            });
            setInvite(res.inviteToken);
            setRows(await api("/api/users"));
          }}
        >
          <div>
            <label>Login ID</label>
            <input value={loginId} onChange={(e) => setLoginId(e.target.value)} />
          </div>
          <div>
            <label>Email</label>
            <input value={email} onChange={(e) => setEmail(e.target.value)} />
          </div>
          <div>
            <label>Role</label>
            <select value={role} onChange={(e) => setRole(e.target.value)}>
              <option>PAYMENT_MAKER</option>
              <option>PAYMENT_CHECKER</option>
              <option>LIQUIDITY</option>
              <option>AUDITOR</option>
              <option>BANK_SECURITY_OFFICER</option>
              {user?.role === "BUREAU_OPERATOR" && <option>BUREAU_OPERATOR</option>}
            </select>
          </div>
          <div>
            <label>&nbsp;</label>
            <button type="submit">Invite</button>
          </div>
        </form>
      )}
      {invite && (
        <p>
          Out-of-band invite token: <code>{invite}</code> — share on a recorded channel, then /invite
        </p>
      )}
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
              <td>{u.status}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function Payments() {
  const [rows, setRows] = useState<Payment[]>([]);
  const nav = useNavigate();
  useEffect(() => {
    api<Payment[]>("/api/payments?direction=OUT").then(setRows);
  }, []);
  return (
    <div>
      <h2>Outbound payments</h2>
      <button onClick={() => nav("/payments/new")}>New pacs.008</button>
      <table>
        <thead>
          <tr>
            <th>UETR</th>
            <th>Type</th>
            <th>Status</th>
            <th>Amount</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          {rows.map((p) => (
            <tr key={p.id}>
              <td>{p.uetr}</td>
              <td>{p.messageType}</td>
              <td>{p.status}</td>
              <td>
                {p.amount} {p.currency}
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
  );
}

function NewPayment() {
  const nav = useNavigate();
  const [error, setError] = useState("");
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
  return (
    <div>
      <h2>Create payment</h2>
      <p>Structured town and country are mandatory (CBPR+ November 2026).</p>
      <form
        onSubmit={async (e) => {
          e.preventDefault();
          try {
            const created = await api<Payment>("/api/payments", {
              method: "POST",
              body: JSON.stringify({ ...form, amount: Number(form.amount) }),
            });
            nav(`/payments/${created.id}`);
          } catch (err) {
            setError((err as Error).message);
          }
        }}
      >
        {Object.entries(form).map(([k, v]) => (
          <div key={k}>
            <label>{k}</label>
            <input value={v} onChange={(e) => setForm({ ...form, [k]: e.target.value })} />
          </div>
        ))}
        {error && <p className="err">{error}</p>}
        <button type="submit">Save draft</button>
      </form>
    </div>
  );
}

function PaymentDetail() {
  const { id } = useParams();
  const [p, setP] = useState<Payment | null>(null);
  const [totp, setTotp] = useState("");
  const [hops, setHops] = useState<any[]>([]);
  const [error, setError] = useState("");
  const user = currentUser();
  async function reload() {
    const row = await api<Payment>(`/api/payments/${id}`);
    setP(row);
    setHops(await api(`/api/gpi/${row.uetr}`));
  }
  useEffect(() => {
    reload().catch((e) => setError(e.message));
  }, [id]);
  if (!p) return <p>{error || "Loading…"}</p>;
  return (
    <div>
      <h2>
        {p.messageType} {p.status}
      </h2>
      <p>UETR {p.uetr} — never rewritten</p>
      {user?.role === "PAYMENT_MAKER" && (
        <button
          onClick={async () => {
            await api(`/api/payments/${p.id}/submit`, { method: "POST" });
            reload();
          }}
        >
          Submit for screening
        </button>
      )}
      {user?.role === "PAYMENT_CHECKER" && (
        <form
          onSubmit={async (e) => {
            e.preventDefault();
            try {
              await api(`/api/payments/${p.id}/approve`, {
                method: "POST",
                headers: { "X-Step-Up-Totp": totp },
              });
              reload();
            } catch (err) {
              setError((err as Error).message);
            }
          }}
        >
          <label>Step-up TOTP</label>
          <input value={totp} onChange={(e) => setTotp(e.target.value)} />
          <button type="submit">Approve and release</button>
        </form>
      )}
      <button
        className="secondary"
        onClick={async () => {
          await api(`/api/payments/${p.id}/recall`, { method: "POST" });
          reload();
        }}
      >
        camt.056 recall
      </button>
      {error && <p className="err">{error}</p>}
      <h3>gpi tracker</h3>
      <ul>
        {hops.map((h) => (
          <li key={h.id}>
            {h.hopStatus} · {h.bic} · {h.detail}
          </li>
        ))}
      </ul>
      <pre>{p.mxXml}</pre>
    </div>
  );
}

function Inbox() {
  const [rows, setRows] = useState<Payment[]>([]);
  useEffect(() => {
    api<Payment[]>("/api/inbox").then(setRows);
  }, []);
  return (
    <div>
      <h2>Inbound (routed by BIC)</h2>
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
              <td>{p.uetr}</td>
              <td>{p.status}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function Liquidity() {
  const [rows, setRows] = useState<any[]>([]);
  useEffect(() => {
    api<any[]>("/api/liquidity").then(setRows);
  }, []);
  return (
    <div>
      <h2>Nostro / vostro liquidity</h2>
      <p>Shadow books only — not bureau funds.</p>
      <table>
        <thead>
          <tr>
            <th>Type</th>
            <th>CCY</th>
            <th>Correspondent</th>
            <th>Booked</th>
            <th>Statement</th>
            <th>Variance</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((r) => (
            <tr key={r.account.id}>
              <td>{r.account.type}</td>
              <td>{r.account.currency}</td>
              <td>{r.account.correspondentBic}</td>
              <td>{r.bookedBalance}</td>
              <td>{r.statementBalance}</td>
              <td>{r.variance}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function Rma() {
  const [rows, setRows] = useState<any[]>([]);
  const [ourBic, setOurBic] = useState("MIDNGB2L");
  const [counterpartyBic, setCounterpartyBic] = useState("");
  useEffect(() => {
    api<any[]>("/api/rma").then(setRows);
  }, []);
  return (
    <div>
      <h2>RMA relationships</h2>
      <form
        className="row"
        onSubmit={async (e) => {
          e.preventDefault();
          await api("/api/rma", { method: "POST", body: JSON.stringify({ ourBic, counterpartyBic }) });
          setRows(await api("/api/rma"));
        }}
      >
        <div>
          <label>Our BIC</label>
          <input value={ourBic} onChange={(e) => setOurBic(e.target.value)} />
        </div>
        <div>
          <label>Counterparty BIC</label>
          <input value={counterpartyBic} onChange={(e) => setCounterpartyBic(e.target.value)} />
        </div>
        <div>
          <label>&nbsp;</label>
          <button type="submit">Create pending RMA</button>
        </div>
      </form>
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
              <td>{r.counterpartyBic}</td>
              <td>{r.status}</td>
              <td>
                {r.status === "PENDING" && (
                  <button
                    className="secondary"
                    onClick={async () => {
                      await api(`/api/rma/${r.id}/activate`, { method: "POST" });
                      setRows(await api("/api/rma"));
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
  );
}

function Charges() {
  const [quote, setQuote] = useState<any>(null);
  return (
    <div>
      <h2>Charge engine</h2>
      <button
        onClick={async () => {
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
        }}
      >
        Quote USD to CHASUS33
      </button>
      {quote && (
        <p>
          Fee {quote.fee} ({quote.legacyCode} / {quote.bearer}) instructed {quote.instructedAmount}
        </p>
      )}
    </div>
  );
}

function Audit() {
  const [rows, setRows] = useState<any[]>([]);
  useEffect(() => {
    api<any[]>("/api/audit").then(setRows);
  }, []);
  return (
    <div>
      <h2>Immutable audit log</h2>
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
              <td>{r.createdAt}</td>
              <td>{r.action}</td>
              <td>
                {r.entityType} {r.entityId}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function Csp() {
  const [rows, setRows] = useState<any[]>([]);
  useEffect(() => {
    api<any[]>("/api/csp/controls").then(setRows);
  }, []);
  return (
    <div>
      <h2>CSCF v2026 attestation</h2>
      <button
        onClick={async () => {
          const pack = await api<any>("/api/csp/pack");
          const blob = new Blob([JSON.stringify(pack, null, 2)], { type: "application/json" });
          const a = document.createElement("a");
          a.href = URL.createObjectURL(blob);
          a.download = "csp-customer-attestation-pack.json";
          a.click();
        }}
      >
        Download customer pack
      </button>
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
              <td>{c.id}</td>
              <td>{c.title}</td>
              <td>{c.status}</td>
              <td>
                <button
                  className="secondary"
                  onClick={async () => {
                    await api("/api/csp/attest", {
                      method: "POST",
                      body: JSON.stringify({
                        controlId: c.id,
                        status: "IMPLEMENTED",
                        evidenceNotes: "Recorded in secure-zone design",
                      }),
                    });
                    setRows(await api("/api/csp/controls"));
                  }}
                >
                  Attest
                </button>
                <button
                  className="secondary"
                  onClick={async () => {
                    await api(`/api/csp/confirm/${c.id}`, { method: "POST" });
                    setRows(await api("/api/csp/controls"));
                  }}
                >
                  Confirm (2nd ops)
                </button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
