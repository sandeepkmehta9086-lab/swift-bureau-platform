import { describe, expect, it } from "vitest";
import {
  buildTimeline,
  formatDuration,
  GpiHop,
  journeyStats,
  MIN_TRACK_GAP,
  stageDots,
  STAGES,
  trackLayout,
} from "./gpi";

const payment = (status: string) => ({
  status,
  uetr: "e2b4c7d0-1a2b-4c3d-8e9f-000000000001",
  instructingAgentBic: "MIDNGB2L",
  instructedAgentBic: "CHASUS33",
});

const hop = (hopStatus: string, occurredAt: string): GpiHop => ({ id: `h-${hopStatus}-${occurredAt}`, hopStatus, occurredAt });

const credited = [
  hop("INITIATED", "2026-10-30T09:00:00Z"),
  hop("SENT_TO_SWIFT", "2026-10-30T09:50:00Z"),
  hop("RECEIVED_BY_CORRESPONDENT", "2026-10-30T09:55:00Z"),
  hop("CREDITED", "2026-10-30T10:00:00Z"),
];

const positions = (status: string, hops: GpiHop[]) => trackLayout(buildTimeline(payment(status), hops)).points.map((p) => p.pos);

describe("trackLayout", () => {
  it("spaces stages evenly when there are no timestamps", () => {
    const layout = trackLayout(buildTimeline(payment("PENDING_CHECK"), []));
    expect(layout.timeScaled).toBe(false);
    expect(layout.points.map((p) => p.pos)).toEqual([0, 20, 40, 60, 80, 100]);
    // Packet sits on the active stage
    expect(layout.points[2]).toMatchObject({ key: "APPROVAL", state: "active" });
    expect(layout.packet).toBe(40);
  });

  it("spaces stages by real elapsed time when hops carry timestamps", () => {
    const layout = trackLayout(buildTimeline(payment("ACKED"), credited));
    expect(layout.timeScaled).toBe(true);
    const pos = layout.points.map((p) => p.pos);
    expect(pos[0]).toBe(0);
    expect(pos[pos.length - 1]).toBe(100);
    // 50 of 60 minutes passed before SENT_TO_SWIFT: it sits far right of even spacing (60)
    expect(pos[3]).toBeGreaterThan(80);
    // Untimed screening/approval sit between INITIATED and SENT, in order
    expect(pos[1]).toBeGreaterThan(pos[0]);
    expect(pos[2]).toBeGreaterThan(pos[1]);
    expect(pos[3]).toBeGreaterThan(pos[2]);
    // Complete journey: packet on the final stage
    expect(layout.packet).toBe(100);
  });

  it("keeps a minimum gap so hops in the same instant stay visible", () => {
    const same = [
      hop("INITIATED", "2026-10-30T09:00:00Z"),
      hop("SENT_TO_SWIFT", "2026-10-30T09:00:00.100Z"),
      hop("RECEIVED_BY_CORRESPONDENT", "2026-10-30T09:00:00.100Z"),
      hop("CREDITED", "2026-10-30T10:00:00Z"),
    ];
    const pos = positions("ACKED", same);
    for (let i = 1; i < pos.length; i++) expect(pos[i] - pos[i - 1]).toBeGreaterThanOrEqual(MIN_TRACK_GAP - 0.1);
    expect(Math.min(...pos)).toBeGreaterThanOrEqual(0);
    expect(Math.max(...pos)).toBeLessThanOrEqual(100);
  });

  it("falls back to even spacing when all timestamps are equal", () => {
    const flat = [hop("INITIATED", "2026-10-30T09:00:00Z"), hop("SENT_TO_SWIFT", "2026-10-30T09:00:00Z")];
    const layout = trackLayout(buildTimeline(payment("SENT"), flat));
    expect(layout.timeScaled).toBe(false);
    expect(layout.points.map((p) => p.pos)).toEqual([0, 20, 40, 60, 80, 100]);
  });

  it("parks the packet on the failing stage", () => {
    const layout = trackLayout(buildTimeline(payment("SCREENING_HIT"), [hop("INITIATED", "2026-10-30T09:00:00Z")]));
    const failed = layout.points.find((p) => p.state === "failed")!;
    expect(failed.key).toBe("SCREENING");
    expect(layout.packet).toBe(failed.pos);
  });

  it("ignores extra tracker events on the main track", () => {
    const hops = [...credited, hop("FX_CONVERSION", "2026-10-30T09:56:00Z")];
    const layout = trackLayout(buildTimeline(payment("ACKED"), hops));
    expect(layout.points).toHaveLength(STAGES.length);
  });

  it("puts the packet at the start for a draft", () => {
    const layout = trackLayout(buildTimeline(payment("DRAFT"), []));
    // DRAFT: INITIATED is done, nothing active yet
    expect(layout.packet).toBe(0);
  });
});

describe("journeyStats", () => {
  it("summarises a completed journey", () => {
    const stats = journeyStats(buildTimeline(payment("ACKED"), credited));
    expect(stats.totalMs).toBe(60 * 60 * 1000);
    expect(stats.trackerEvents).toBe(4);
    expect(stats.lastUpdate).toBe("2026-10-30T10:00:00Z");
    expect(stats.slowest).toEqual({ label: "Sent to SWIFT", ms: 50 * 60 * 1000 });
    expect(stats.current).toBe("Credited");
  });

  it("reports the active stage while in flight", () => {
    const stats = journeyStats(buildTimeline(payment("SENT"), credited.slice(0, 2)));
    expect(stats.current).toBe("At correspondent");
    expect(stats.trackerEvents).toBe(2);
  });

  it("reports the stopping stage for a failure", () => {
    expect(journeyStats(buildTimeline(payment("SCREENING_HIT"), [])).current).toBe("Screening");
  });

  it("handles a journey with no tracker events", () => {
    const stats = journeyStats(buildTimeline(payment("DRAFT"), []));
    expect(stats).toMatchObject({ totalMs: null, trackerEvents: 0, lastUpdate: null, slowest: null, current: "Initiated" });
  });

  it("needs two timestamps for an end-to-end time", () => {
    const stats = journeyStats(buildTimeline(payment("PENDING_CHECK"), [credited[0]]));
    expect(stats.totalMs).toBeNull();
    expect(stats.lastUpdate).toBe(credited[0].occurredAt);
  });
});

describe("stageDots", () => {
  it.each([
    ["DRAFT", ["done", "pending", "pending", "pending", "pending", "pending"]],
    ["PENDING_CHECK", ["done", "done", "active", "pending", "pending", "pending"]],
    ["ACKED", ["done", "done", "done", "done", "done", "done"]],
    ["SCREENING_HIT", ["done", "failed", "skipped", "skipped", "skipped", "skipped"]],
  ])("%s → %j", (status, expected) => {
    expect(stageDots(status)).toEqual(expected);
  });
});

describe("formatDuration", () => {
  it("drops the leading plus", () => {
    expect(formatDuration(4500)).toBe("4.5s");
    expect(formatDuration(3_600_000)).toBe("1h 0m");
  });
});
