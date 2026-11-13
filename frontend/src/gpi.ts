// Builds the gpi tracker timeline from what the backend returns today:
// the payment's MessageStatus plus the gpi hop events for its UETR.
// Pure functions only, so the UI and tests share one source of truth.

export type GpiHop = {
  id: string;
  uetr?: string;
  hopStatus: string;
  bic?: string | null;
  detail?: string | null;
  occurredAt?: string | null;
};

export type PaymentLike = {
  status: string;
  uetr: string;
  instructingAgentBic?: string;
  instructedAgentBic?: string;
};

export type StageState = "done" | "active" | "pending" | "failed" | "skipped";

export type TimelineStage = {
  key: string;
  label: string;
  description: string;
  state: StageState;
  bic?: string | null;
  detail?: string | null;
  occurredAt?: string | null;
  /** Milliseconds since the previous timestamped stage, when both are known. */
  elapsedMs?: number | null;
  /** True when this step comes from a hop event (not inferred from status). */
  fromTracker: boolean;
};

export type Timeline = {
  stages: TimelineStage[];
  /** 0..100, share of stages finished. */
  progress: number;
  /** "returned": the payment completed and then came back via an accepted recall (pacs.004). */
  outcome: "complete" | "in_flight" | "failed" | "draft" | "returned";
  headline: string;
};

type StageDef = {
  key: string;
  label: string;
  description: string;
  hop?: string;
};

export const STAGES: StageDef[] = [
  { key: "INITIATED", label: "Initiated", description: "Draft created by maker, UETR assigned", hop: "INITIATED" },
  { key: "SCREENING", label: "Screening", description: "Sanctions screening and RMA check" },
  { key: "APPROVAL", label: "4-eyes approval", description: "Checker release with step-up TOTP" },
  { key: "SENT_TO_SWIFT", label: "Sent to SWIFT", description: "MX frozen and handed to the network", hop: "SENT_TO_SWIFT" },
  {
    key: "RECEIVED_BY_CORRESPONDENT",
    label: "At correspondent",
    description: "Instructed agent confirmed with pacs.002",
    hop: "RECEIVED_BY_CORRESPONDENT",
  },
  { key: "CREDITED", label: "Credited", description: "Beneficiary account credited", hop: "CREDITED" },
];

/** How far each MessageStatus has moved through STAGES (index of first unfinished stage). */
const STATUS_REACH: Record<string, number> = {
  DRAFT: 1,
  SCREENING: 1,
  SCREENING_HIT: 1,
  PENDING_CHECK: 2,
  APPROVED: 3,
  SENT: 4,
  ACKED: 6,
  REJECTED: -1,
  RETURNED: -1,
};

const KNOWN_HOPS = new Set(STAGES.filter((s) => s.hop).map((s) => s.hop as string));

/**
 * Hops outside the main lane that the backend appends for the customer channel (stage 1, pain.001/pain.002)
 * and the recall path (stage 4, camt.056/camt.029/pacs.004). They are shown as extra tracker events.
 */
export const EXTRA_HOPS: Record<string, { label: string; description: string }> = {
  CUSTOMER_INSTRUCTED: { label: "Customer instruction", description: "pain.001 received from the customer" },
  CUSTOMER_ACCEPTED: { label: "Instruction accepted", description: "pain.002 ACCP sent to the customer" },
  CUSTOMER_REJECTED: { label: "Instruction rejected", description: "pain.002 RJCT sent to the customer" },
  RECALL_REQUESTED: { label: "Recall requested", description: "camt.056 sent to the correspondent" },
  RECALL_ACCEPTED: { label: "Recall accepted", description: "camt.029 CNCL — funds are coming back" },
  RECALL_REJECTED: { label: "Recall rejected", description: "camt.029 RJCR — funds stay with the beneficiary" },
  RETURNED: { label: "Funds returned", description: "pacs.004 return credited to the shadow nostro" },
};

export const FAILED_STATUSES = new Set(["SCREENING_HIT", "REJECTED", "RETURNED"]);
export const IN_FLIGHT_STATUSES = new Set(["SCREENING", "APPROVED", "SENT"]);

export function humanize(code: string): string {
  return code
    .toLowerCase()
    .split("_")
    .filter(Boolean)
    .map((w, i) => (i === 0 ? w[0].toUpperCase() + w.slice(1) : w))
    .join(" ");
}

function parseTime(value?: string | null): number | null {
  if (!value) return null;
  const t = Date.parse(value);
  return Number.isNaN(t) ? null : t;
}

export function formatElapsed(ms: number): string {
  if (ms < 1000) return `+${Math.max(0, Math.round(ms))}ms`;
  const s = ms / 1000;
  if (s < 60) return `+${s.toFixed(s < 10 ? 1 : 0)}s`;
  const m = Math.floor(s / 60);
  if (m < 60) return `+${m}m ${Math.round(s % 60)}s`;
  const h = Math.floor(m / 60);
  return `+${h}h ${m % 60}m`;
}

export function formatTime(value?: string | null): string {
  const t = parseTime(value);
  if (t == null) return "";
  return new Date(t).toISOString().replace("T", " ").replace(/\.\d+Z$/, "Z");
}

export function buildTimeline(payment: PaymentLike, hops: GpiHop[]): Timeline {
  const sorted = [...hops].sort((a, b) => (parseTime(a.occurredAt) ?? 0) - (parseTime(b.occurredAt) ?? 0));
  const byHop = new Map<string, GpiHop>();
  for (const h of sorted) {
    if (!byHop.has(h.hopStatus)) byHop.set(h.hopStatus, h);
  }

  // A recall the correspondent honoured: the payment completed, then came back with a pacs.004.
  const returnedByRecall = payment.status === "RETURNED" && byHop.has("RETURNED");
  // The bank refused the customer's pain.001, so the interbank leg never started.
  const rejectedAtCustomer = payment.status === "REJECTED" && byHop.has("CUSTOMER_REJECTED");

  let reach = returnedByRecall ? STAGES.length : (STATUS_REACH[payment.status] ?? 1);
  // Hop events are evidence too: never show less progress than the tracker reports.
  STAGES.forEach((s, i) => {
    if (s.hop && byHop.has(s.hop) && reach >= 0) reach = Math.max(reach, i + 1);
  });

  const failed = FAILED_STATUSES.has(payment.status) && !returnedByRecall;
  // For a failure, mark the stage after the last evidenced one as the failing step.
  let failAt = -1;
  if (failed) {
    if (payment.status === "SCREENING_HIT") {
      failAt = 1;
    } else if (rejectedAtCustomer) {
      failAt = 0;
    } else {
      let last = 0;
      STAGES.forEach((s, i) => {
        if (s.hop && byHop.has(s.hop)) last = i;
      });
      failAt = Math.min(last + 1, STAGES.length - 1);
    }
  }

  const stages: TimelineStage[] = STAGES.map((def, i) => {
    const hop = def.hop ? byHop.get(def.hop) : undefined;
    let state: StageState;
    if (failed) {
      state = i < failAt ? "done" : i === failAt ? "failed" : "skipped";
    } else if (payment.status === "DRAFT" && i === 1) {
      state = "pending";
    } else if (i < reach) {
      state = "done";
    } else if (i === reach) {
      state = "active";
    } else {
      state = "pending";
    }
    const fallbackBic =
      def.key === "INITIATED" || def.key === "SENT_TO_SWIFT"
        ? payment.instructingAgentBic
        : def.key === "RECEIVED_BY_CORRESPONDENT" || def.key === "CREDITED"
          ? payment.instructedAgentBic
          : undefined;
    let description = def.description;
    if (state === "failed") {
      description =
        payment.status === "SCREENING_HIT"
          ? "Screening hit — held for compliance review"
          : rejectedAtCustomer
            ? "Customer instruction rejected — pain.002 RJCT"
            : payment.status === "RETURNED"
              ? "Payment returned by the network"
              : "Payment rejected";
    }
    return {
      key: def.key,
      label: def.label,
      description,
      state,
      bic: hop?.bic ?? fallbackBic ?? null,
      detail: hop?.detail ?? null,
      occurredAt: hop?.occurredAt ?? null,
      elapsedMs: null,
      fromTracker: Boolean(hop),
    };
  });

  // Hops outside the main lane (customer channel, recall path, future backend additions) are shown, not dropped.
  for (const h of sorted) {
    if (!KNOWN_HOPS.has(h.hopStatus)) {
      const known = EXTRA_HOPS[h.hopStatus];
      stages.push({
        key: `EXTRA_${h.id}`,
        label: known?.label ?? humanize(h.hopStatus),
        description: known?.description ?? "Additional gpi tracker event",
        state: "done",
        bic: h.bic ?? null,
        detail: h.detail ?? null,
        occurredAt: h.occurredAt ?? null,
        elapsedMs: null,
        fromTracker: true,
      });
    }
  }

  // Keep timestamped extras in time order relative to the main lane.
  const extras = stages.filter((s) => s.key.startsWith("EXTRA_"));
  if (extras.length) {
    const main = stages.filter((s) => !s.key.startsWith("EXTRA_"));
    for (const extra of extras) {
      const t = parseTime(extra.occurredAt);
      let insertAt = main.length;
      if (t != null) {
        // After the last main stage whose time is <= the extra's time.
        let lastBefore = -1;
        main.forEach((s, idx) => {
          const st = parseTime(s.occurredAt);
          if (st != null && st <= t) lastBefore = idx;
        });
        insertAt = lastBefore + 1;
      }
      main.splice(insertAt, 0, extra);
    }
    stages.splice(0, stages.length, ...main);
  }

  let prev: number | null = null;
  for (const s of stages) {
    const t = parseTime(s.occurredAt);
    if (t != null) {
      s.elapsedMs = prev != null ? t - prev : null;
      prev = t;
    }
  }

  const core = stages.filter((s) => !s.key.startsWith("EXTRA_"));
  const doneCount = core.filter((s) => s.state === "done").length;
  const progress = Math.round((doneCount / core.length) * 100);

  let outcome: Timeline["outcome"];
  let headline: string;
  if (failed) {
    outcome = "failed";
    headline = core.find((s) => s.state === "failed")?.description ?? "Payment stopped";
  } else if (returnedByRecall) {
    outcome = "returned";
    headline = "Funds returned to debtor — recall accepted (pacs.004)";
  } else if (doneCount === core.length) {
    outcome = "complete";
    headline = byHop.has("RECALL_REJECTED")
      ? "Credited to beneficiary — recall rejected (camt.029 RJCR)"
      : "Credited to beneficiary — gpi tracker complete";
  } else if (payment.status === "DRAFT") {
    outcome = "draft";
    headline = "Draft — submit for screening to start the journey";
  } else {
    outcome = "in_flight";
    const active = core.find((s) => s.state === "active");
    headline = active ? `In progress: ${active.label}` : "In progress";
  }

  return { stages, progress, outcome, headline };
}

/** Lightweight progress for list views where hops are not loaded. */
export function statusProgress(status: string): number {
  if (FAILED_STATUSES.has(status)) return 100;
  const reach = STATUS_REACH[status];
  if (reach == null || reach < 0) return 0;
  if (status === "DRAFT") return Math.round((1 / STAGES.length) * 100);
  return Math.round((Math.min(reach, STAGES.length) / STAGES.length) * 100);
}

export type Tone = "ok" | "info" | "warn" | "danger" | "muted";

export function statusTone(status: string): Tone {
  switch (status) {
    case "ACKED":
    case "ACTIVE":
    case "IMPLEMENTED":
    case "CONFIRMED":
    case "CLEAR":
      return "ok";
    case "SENT":
    case "APPROVED":
    case "SCREENING":
      return "info";
    case "PENDING_CHECK":
    case "PENDING":
    case "DRAFT":
      return "warn";
    case "SCREENING_HIT":
    case "REJECTED":
    case "RETURNED":
    case "SUSPENDED":
    case "REVOKED":
      return "danger";
    default:
      return "muted";
  }
}

// ---------- Time-scaled journey track ----------

export type TrackPoint = {
  key: string;
  label: string;
  state: StageState;
  /** 0..100 position along the track. */
  pos: number;
  occurredAt?: string | null;
};

export type TrackLayout = {
  points: TrackPoint[];
  /** 0..100 position of the moving packet (current or stopping stage). */
  packet: number;
  /** True when positions follow real timestamps rather than even spacing. */
  timeScaled: boolean;
};

/** Smallest gap between two points so labels stay readable. */
export const MIN_TRACK_GAP = 8;

/**
 * Places the main stages on a 0..100 track. Stages with timestamps are spaced
 * by real elapsed time; stages without one sit between their neighbours.
 * A minimum gap keeps every stage visible even when hops land in the same second.
 */
export function trackLayout(timeline: Timeline): TrackLayout {
  const core = timeline.stages.filter((s) => !s.key.startsWith("EXTRA_"));
  const n = core.length;
  const even = core.map((_, i) => (n > 1 ? (i / (n - 1)) * 100 : 0));
  const times = core.map((s) => parseTime(s.occurredAt));
  const timed = times.map((t, i) => (t != null ? i : -1)).filter((i) => i >= 0);

  let pos = [...even];
  let timeScaled = false;
  if (timed.length >= 2) {
    const first = timed[0];
    const last = timed[timed.length - 1];
    const t0 = times[first] as number;
    const span = (times[last] as number) - t0;
    if (span > 0) {
      timeScaled = true;
      const lo = even[first];
      const hi = even[last];
      for (const i of timed) pos[i] = lo + (((times[i] as number) - t0) / span) * (hi - lo);
      // Untimed stages inside the timed range: interpolate by index.
      for (let k = 0; k < timed.length - 1; k++) {
        const a = timed[k];
        const b = timed[k + 1];
        for (let i = a + 1; i < b; i++) pos[i] = pos[a] + ((pos[b] - pos[a]) * (i - a)) / (b - a);
      }
    }
  }
  pos = enforceGap(pos, MIN_TRACK_GAP);

  const points = core.map((s, i) => ({
    key: s.key,
    label: s.label,
    state: s.state,
    pos: Math.round(pos[i] * 10) / 10,
    occurredAt: s.occurredAt,
  }));

  let packetIdx = points.findIndex((p) => p.state === "active" || p.state === "failed");
  if (packetIdx < 0) {
    packetIdx = -1;
    points.forEach((p, i) => {
      if (p.state === "done") packetIdx = i;
    });
  }
  const packet = packetIdx >= 0 ? points[packetIdx].pos : 0;
  return { points, packet, timeScaled };
}

function enforceGap(pos: number[], gap: number): number[] {
  const out = [...pos];
  const n = out.length;
  if (n < 2) return out;
  const g = Math.min(gap, 100 / (n - 1));
  for (let i = 1; i < n; i++) out[i] = Math.max(out[i], out[i - 1] + g);
  out[n - 1] = Math.min(out[n - 1], 100);
  for (let i = n - 2; i >= 0; i--) out[i] = Math.min(out[i], out[i + 1] - g);
  out[0] = Math.max(out[0], 0);
  return out;
}

// ---------- Journey stats ----------

export type JourneyStats = {
  /** First to last tracker timestamp, when there are at least two. */
  totalMs: number | null;
  trackerEvents: number;
  lastUpdate: string | null;
  /** Stage that took the longest, by elapsed time before it. */
  slowest: { label: string; ms: number } | null;
  current: string;
};

export function journeyStats(timeline: Timeline): JourneyStats {
  const timedStages = timeline.stages.filter((s) => parseTime(s.occurredAt) != null);
  const ts = timedStages.map((s) => parseTime(s.occurredAt) as number);
  const totalMs = ts.length >= 2 ? Math.max(...ts) - Math.min(...ts) : null;
  let lastUpdate: string | null = null;
  let lastT = -Infinity;
  timedStages.forEach((s, i) => {
    if (ts[i] > lastT) {
      lastT = ts[i];
      lastUpdate = s.occurredAt ?? null;
    }
  });
  let slowest: JourneyStats["slowest"] = null;
  for (const s of timeline.stages) {
    if (s.elapsedMs != null && s.elapsedMs > 0 && (!slowest || s.elapsedMs > slowest.ms)) {
      slowest = { label: s.label, ms: s.elapsedMs };
    }
  }
  const core = timeline.stages.filter((s) => !s.key.startsWith("EXTRA_"));
  const cur =
    core.find((s) => s.state === "failed") ??
    core.find((s) => s.state === "active") ??
    [...core].reverse().find((s) => s.state === "done");
  return {
    totalMs,
    trackerEvents: timeline.stages.filter((s) => s.fromTracker).length,
    lastUpdate,
    slowest,
    current: cur?.label ?? "—",
  };
}

/** Compact per-stage states for list rows, inferred from status alone. */
export function stageDots(status: string): StageState[] {
  return buildTimeline({ status, uetr: "" }, []).stages.map((s) => s.state);
}

/** Duration without the leading "+", e.g. "4.5s". */
export function formatDuration(ms: number): string {
  return formatElapsed(ms).slice(1);
}
