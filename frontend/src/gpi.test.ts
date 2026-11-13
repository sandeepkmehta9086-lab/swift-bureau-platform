import { describe, expect, it } from "vitest";
import {
  buildTimeline,
  formatElapsed,
  formatTime,
  GpiHop,
  humanize,
  STAGES,
  statusProgress,
  statusTone,
} from "./gpi";

const payment = (status: string) => ({
  status,
  uetr: "e2b4c7d0-1a2b-4c3d-8e9f-000000000001",
  instructingAgentBic: "MIDNGB2L",
  instructedAgentBic: "CHASUS33",
});

const hop = (hopStatus: string, occurredAt: string, extra: Partial<GpiHop> = {}): GpiHop => ({
  id: `h-${hopStatus}-${occurredAt}`,
  hopStatus,
  occurredAt,
  bic: null,
  detail: null,
  ...extra,
});

const INITIATED = hop("INITIATED", "2026-10-30T09:00:00Z", { bic: "MIDNGB2L", detail: "Created by maker" });
const SENT = hop("SENT_TO_SWIFT", "2026-10-30T09:05:00Z", { bic: "MIDNGB2L", detail: "NETREF-1" });
const RECEIVED = hop("RECEIVED_BY_CORRESPONDENT", "2026-10-30T09:05:02.500Z", { bic: "CHASUS33", detail: "pacs.002 ACSC" });
const CREDITED = hop("CREDITED", "2026-10-30T09:05:03Z", { bic: "CHASUS33", detail: "Stub tracker complete" });

const states = (status: string, hops: GpiHop[]) =>
  Object.fromEntries(buildTimeline(payment(status), hops).stages.map((s) => [s.key, s.state]));

describe("buildTimeline — happy path through every MessageStatus", () => {
  it("DRAFT: only initiated is done, nothing active yet", () => {
    const t = buildTimeline(payment("DRAFT"), [INITIATED]);
    expect(states("DRAFT", [INITIATED])).toEqual({
      INITIATED: "done",
      SCREENING: "pending",
      APPROVAL: "pending",
      SENT_TO_SWIFT: "pending",
      RECEIVED_BY_CORRESPONDENT: "pending",
      CREDITED: "pending",
    });
    expect(t.outcome).toBe("draft");
    expect(t.headline).toMatch(/submit for screening/i);
    expect(t.progress).toBe(17);
  });

  it("SCREENING: screening is the active step", () => {
    expect(states("SCREENING", [INITIATED]).SCREENING).toBe("active");
    expect(buildTimeline(payment("SCREENING"), [INITIATED]).headline).toBe("In progress: Screening");
  });

  it("PENDING_CHECK: screening passed, approval active", () => {
    const s = states("PENDING_CHECK", [INITIATED]);
    expect(s.SCREENING).toBe("done");
    expect(s.APPROVAL).toBe("active");
    expect(s.SENT_TO_SWIFT).toBe("pending");
  });

  it("APPROVED: waiting on network hand-off", () => {
    expect(states("APPROVED", [INITIATED]).SENT_TO_SWIFT).toBe("active");
  });

  it("SENT: correspondent is the active step", () => {
    const t = buildTimeline(payment("SENT"), [INITIATED, SENT]);
    expect(t.stages.find((s) => s.key === "RECEIVED_BY_CORRESPONDENT")?.state).toBe("active");
    expect(t.outcome).toBe("in_flight");
    expect(t.progress).toBe(67);
  });

  it("ACKED with all hops: complete at 100%", () => {
    const t = buildTimeline(payment("ACKED"), [INITIATED, SENT, RECEIVED, CREDITED]);
    expect(t.stages.every((s) => s.state === "done")).toBe(true);
    expect(t.progress).toBe(100);
    expect(t.outcome).toBe("complete");
    expect(t.headline).toMatch(/credited/i);
  });
});

describe("buildTimeline — tracker evidence", () => {
  it("attaches BIC, detail and time from hop events", () => {
    const t = buildTimeline(payment("ACKED"), [INITIATED, SENT, RECEIVED, CREDITED]);
    const recv = t.stages.find((s) => s.key === "RECEIVED_BY_CORRESPONDENT")!;
    expect(recv).toMatchObject({ bic: "CHASUS33", detail: "pacs.002 ACSC", fromTracker: true });
    expect(recv.occurredAt).toBe(RECEIVED.occurredAt);
  });

  it("marks screening/approval as inferred (not from tracker)", () => {
    const t = buildTimeline(payment("ACKED"), [INITIATED, SENT, RECEIVED, CREDITED]);
    expect(t.stages.find((s) => s.key === "SCREENING")!.fromTracker).toBe(false);
    expect(t.stages.find((s) => s.key === "APPROVAL")!.fromTracker).toBe(false);
  });

  it("computes elapsed time between consecutive timestamped hops", () => {
    const t = buildTimeline(payment("ACKED"), [INITIATED, SENT, RECEIVED, CREDITED]);
    const byKey = Object.fromEntries(t.stages.map((s) => [s.key, s.elapsedMs]));
    expect(byKey.INITIATED).toBeNull();
    expect(byKey.SCREENING).toBeNull();
    expect(byKey.SENT_TO_SWIFT).toBe(5 * 60 * 1000);
    expect(byKey.RECEIVED_BY_CORRESPONDENT).toBe(2500);
    expect(byKey.CREDITED).toBe(500);
  });

  it("sorts hops by time regardless of API order", () => {
    const t = buildTimeline(payment("ACKED"), [CREDITED, RECEIVED, SENT, INITIATED]);
    expect(t.stages.map((s) => s.key)).toEqual(STAGES.map((s) => s.key));
    expect(t.stages.find((s) => s.key === "CREDITED")!.elapsedMs).toBe(500);
  });

  it("never shows less progress than the tracker reports (stale status)", () => {
    const t = buildTimeline(payment("SENT"), [INITIATED, SENT, RECEIVED, CREDITED]);
    expect(t.outcome).toBe("complete");
    expect(t.progress).toBe(100);
  });

  it("uses the first event when a hop code repeats", () => {
    const dup = hop("SENT_TO_SWIFT", "2026-10-30T09:06:00Z", { detail: "retry" });
    const t = buildTimeline(payment("SENT"), [INITIATED, SENT, dup]);
    expect(t.stages.find((s) => s.key === "SENT_TO_SWIFT")!.detail).toBe("NETREF-1");
  });

  it("falls back to the payment's BICs when no hop exists yet", () => {
    const t = buildTimeline(payment("DRAFT"), []);
    expect(t.stages.find((s) => s.key === "INITIATED")!.bic).toBe("MIDNGB2L");
    expect(t.stages.find((s) => s.key === "CREDITED")!.bic).toBe("CHASUS33");
    expect(t.stages.find((s) => s.key === "SCREENING")!.bic).toBeNull();
  });

  it("keeps unknown future hop codes in time order instead of dropping them", () => {
    const extra = hop("FX_CONVERTED", "2026-10-30T09:05:01Z", { bic: "CHASUS33", detail: "USD→USD" });
    const t = buildTimeline(payment("ACKED"), [INITIATED, SENT, extra, RECEIVED, CREDITED]);
    const keys = t.stages.map((s) => s.key);
    const at = keys.indexOf(`EXTRA_${extra.id}`);
    expect(at).toBe(keys.indexOf("SENT_TO_SWIFT") + 1);
    expect(t.stages[at]).toMatchObject({ label: "Fx converted", state: "done", fromTracker: true });
    // extras do not change the core progress maths
    expect(t.progress).toBe(100);
  });

  it("appends untimed unknown hops at the end", () => {
    const extra: GpiHop = { id: "x", hopStatus: "MANUAL_NOTE", occurredAt: null };
    const t = buildTimeline(payment("SENT"), [INITIATED, SENT, extra]);
    expect(t.stages.at(-1)!.key).toBe("EXTRA_x");
  });

  it("tolerates unparseable timestamps", () => {
    const bad = hop("SENT_TO_SWIFT", "not-a-date");
    const t = buildTimeline(payment("SENT"), [INITIATED, bad]);
    expect(t.stages.find((s) => s.key === "SENT_TO_SWIFT")!.elapsedMs).toBeNull();
  });
});

describe("buildTimeline — failures", () => {
  it("SCREENING_HIT stops at screening and skips the rest", () => {
    const t = buildTimeline(payment("SCREENING_HIT"), [INITIATED]);
    expect(states("SCREENING_HIT", [INITIATED])).toEqual({
      INITIATED: "done",
      SCREENING: "failed",
      APPROVAL: "skipped",
      SENT_TO_SWIFT: "skipped",
      RECEIVED_BY_CORRESPONDENT: "skipped",
      CREDITED: "skipped",
    });
    expect(t.outcome).toBe("failed");
    expect(t.headline).toMatch(/screening hit/i);
  });

  it("REJECTED after sending fails at the correspondent", () => {
    const t = buildTimeline(payment("REJECTED"), [INITIATED, SENT]);
    expect(t.stages.find((s) => s.key === "RECEIVED_BY_CORRESPONDENT")!.state).toBe("failed");
    expect(t.headline).toBe("Payment rejected");
  });

  it("RETURNED after reaching the correspondent fails at credit", () => {
    const t = buildTimeline(payment("RETURNED"), [INITIATED, SENT, RECEIVED]);
    expect(t.stages.find((s) => s.key === "CREDITED")!.state).toBe("failed");
    expect(t.headline).toMatch(/returned/i);
  });

  it("RETURNED after full credit pins failure to the last stage", () => {
    const t = buildTimeline(payment("RETURNED"), [INITIATED, SENT, RECEIVED, CREDITED]);
    expect(t.stages.at(-1)!.state).toBe("failed");
  });

  it("REJECTED with no hops fails right after initiation", () => {
    expect(states("REJECTED", []).SCREENING).toBe("failed");
  });
});

describe("buildTimeline — scenario 1: customer channel and recall", () => {
  const INSTRUCTED = hop("CUSTOMER_INSTRUCTED", "2026-10-30T08:58:00Z", { bic: "MIDNGB2L", detail: "pain.001 received from Acme Ltd" });
  const ACCEPTED = hop("CUSTOMER_ACCEPTED", "2026-10-30T08:59:00Z", { bic: "MIDNGB2L", detail: "pain.002 ACCP" });
  const REJECTED_BY_BANK = hop("CUSTOMER_REJECTED", "2026-10-30T08:59:00Z", { bic: "MIDNGB2L", detail: "pain.002 RJCT BE04" });
  const RECALL_REQ = hop("RECALL_REQUESTED", "2026-10-30T10:00:00Z", { bic: "MIDNGB2L", detail: "camt.056 DUPL" });
  const RECALL_OK = hop("RECALL_ACCEPTED", "2026-10-30T10:00:05Z", { bic: "CHASUS33", detail: "camt.029 CNCL" });
  const RECALL_NO = hop("RECALL_REJECTED", "2026-10-30T10:00:05Z", { bic: "CHASUS33", detail: "camt.029 RJCR" });
  const RETURNED_HOP = hop("RETURNED", "2026-10-30T10:00:06Z", { bic: "CHASUS33", detail: "pacs.004 FOCR" });
  const labels = (hops: GpiHop[], status = "ACKED") => buildTimeline(payment(status), hops).stages.map((s) => s.label);

  it("shows pain.001 and pain.002 before initiation with readable labels", () => {
    const t = buildTimeline(payment("DRAFT"), [INSTRUCTED, ACCEPTED, INITIATED]);
    expect(t.stages.slice(0, 3).map((s) => s.label)).toEqual(["Customer instruction", "Instruction accepted", "Initiated"]);
    expect(t.stages[0].description).toMatch(/pain\.001/);
    expect(t.stages[1].description).toMatch(/pain\.002 ACCP/);
    expect(t.stages[0].fromTracker).toBe(true);
    expect(t.outcome).toBe("draft");
    // Extra events never change the main-lane progress.
    expect(t.progress).toBe(17);
  });

  it("a rejected pain.001 stops the journey at initiation", () => {
    const t = buildTimeline(payment("REJECTED"), [INSTRUCTED, REJECTED_BY_BANK]);
    expect(t.outcome).toBe("failed");
    expect(t.headline).toBe("Customer instruction rejected — pain.002 RJCT");
    const core = t.stages.filter((s) => !s.key.startsWith("EXTRA_"));
    expect(core[0].state).toBe("failed");
    expect(core.slice(1).every((s) => s.state === "skipped")).toBe(true);
    expect(labels([INSTRUCTED, REJECTED_BY_BANK], "REJECTED").slice(0, 2)).toEqual(["Customer instruction", "Instruction rejected"]);
  });

  it("an accepted recall reads as returned, not as a network failure", () => {
    const t = buildTimeline(payment("RETURNED"), [INITIATED, SENT, RECEIVED, CREDITED, RECALL_REQ, RECALL_OK, RETURNED_HOP]);
    expect(t.outcome).toBe("returned");
    expect(t.progress).toBe(100);
    expect(t.stages.filter((s) => !s.key.startsWith("EXTRA_")).every((s) => s.state === "done")).toBe(true);
    expect(t.stages.slice(-3).map((s) => s.label)).toEqual(["Recall requested", "Recall accepted", "Funds returned"]);
    expect(t.headline).toMatch(/pacs\.004/);
    expect(t.stages.at(-1)!.elapsedMs).toBe(1000);
  });

  it("a rejected recall keeps the credited outcome and says so", () => {
    const t = buildTimeline(payment("ACKED"), [INITIATED, SENT, RECEIVED, CREDITED, RECALL_REQ, RECALL_NO]);
    expect(t.outcome).toBe("complete");
    expect(t.progress).toBe(100);
    expect(t.headline).toBe("Credited to beneficiary — recall rejected (camt.029 RJCR)");
    expect(t.stages.at(-1)!.description).toMatch(/funds stay with the beneficiary/i);
  });

  it("RETURNED without a pacs.004 hop still reads as a network failure", () => {
    const t = buildTimeline(payment("RETURNED"), [INITIATED, SENT, RECEIVED, CREDITED]);
    expect(t.outcome).toBe("failed");
    expect(t.headline).toBe("Payment returned by the network");
  });
});

describe("buildTimeline — unknown status", () => {
  it("treats an unknown status as just initiated", () => {
    const t = buildTimeline(payment("SOMETHING_NEW"), [INITIATED]);
    expect(t.outcome).toBe("in_flight");
    expect(t.stages.find((s) => s.key === "SCREENING")!.state).toBe("active");
  });
});

describe("formatting helpers", () => {
  it.each([
    [0, "+0ms"],
    [420, "+420ms"],
    [2500, "+2.5s"],
    [42_000, "+42s"],
    [125_000, "+2m 5s"],
    [3_780_000, "+1h 3m"],
  ])("formatElapsed(%i) = %s", (ms, out) => {
    expect(formatElapsed(ms)).toBe(out);
  });

  it("formatTime renders ISO UTC without milliseconds", () => {
    expect(formatTime("2026-10-30T09:05:02.500Z")).toBe("2026-10-30 09:05:02Z");
    expect(formatTime(null)).toBe("");
    expect(formatTime("garbage")).toBe("");
  });

  it("humanize turns codes into sentence case", () => {
    expect(humanize("PENDING_CHECK")).toBe("Pending check");
    expect(humanize("SENT_TO_SWIFT")).toBe("Sent to swift");
    expect(humanize("ACKED")).toBe("Acked");
  });
});

describe("statusProgress / statusTone", () => {
  it.each([
    ["DRAFT", 17],
    ["SCREENING", 17],
    ["PENDING_CHECK", 33],
    ["APPROVED", 50],
    ["SENT", 67],
    ["ACKED", 100],
    ["SCREENING_HIT", 100],
    ["REJECTED", 100],
    ["UNKNOWN", 0],
  ])("statusProgress(%s) = %i", (status, pct) => {
    expect(statusProgress(status)).toBe(pct);
  });

  it.each([
    ["ACKED", "ok"],
    ["ACTIVE", "ok"],
    ["SENT", "info"],
    ["PENDING_CHECK", "warn"],
    ["DRAFT", "warn"],
    ["SCREENING_HIT", "danger"],
    ["RETURNED", "danger"],
    ["INACTIVE", "muted"],
  ])("statusTone(%s) = %s", (status, tone) => {
    expect(statusTone(status)).toBe(tone);
  });
});
