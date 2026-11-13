import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { buildTimeline, GpiHop } from "../gpi";
import { GpiTimeline } from "./GpiTimeline";
import { JourneyStatsStrip, JourneyTrack } from "./JourneyTrack";
import { JourneyDots } from "./StatusBadge";

const payment = (status: string) => ({
  status,
  uetr: "e2b4c7d0-1a2b-4c3d-8e9f-000000000001",
  instructingAgentBic: "MIDNGB2L",
  instructedAgentBic: "CHASUS33",
});

const hops: GpiHop[] = [
  { id: "1", hopStatus: "INITIATED", bic: "MIDNGB2L", occurredAt: "2026-10-30T09:00:00Z" },
  { id: "2", hopStatus: "SENT_TO_SWIFT", bic: "MIDNGB2L", occurredAt: "2026-10-30T09:00:04Z" },
  { id: "3", hopStatus: "RECEIVED_BY_CORRESPONDENT", bic: "CHASUS33", occurredAt: "2026-10-30T09:00:05Z" },
  { id: "4", hopStatus: "CREDITED", bic: "CHASUS33", occurredAt: "2026-10-30T09:00:06Z" },
];

const leftOf = (el: HTMLElement) => parseFloat(el.style.left);

describe("<JourneyTrack />", () => {
  it("places one point per stage, left to right, with the packet on the active stage", () => {
    render(<JourneyTrack timeline={buildTimeline(payment("PENDING_CHECK"), hops.slice(0, 1))} />);
    const track = screen.getByRole("group", { name: "Journey track" });
    const points = within(track).getAllByRole("button");
    expect(points.map((b) => b.textContent)).toEqual([
      "Initiated",
      "Screening",
      "4-eyes approval",
      "Sent to SWIFT",
      "At correspondent",
      "Credited",
    ]);
    const lefts = points.map(leftOf);
    expect([...lefts].sort((a, b) => a - b)).toEqual(lefts);
    expect(screen.getByTestId("track-APPROVAL")).toHaveAttribute("data-state", "active");
    expect(leftOf(screen.getByTestId("track-packet"))).toBe(leftOf(screen.getByTestId("track-APPROVAL")));
    expect(track).toHaveAttribute("data-time-scaled", "false");
    expect(screen.getByText("evenly spaced")).toBeInTheDocument();
  });

  it("is time scaled once the tracker has timestamps", () => {
    render(<JourneyTrack timeline={buildTimeline(payment("ACKED"), hops)} />);
    expect(screen.getByTestId("journey-track")).toHaveAttribute("data-time-scaled", "true");
    expect(screen.getByText("spaced by tracker time")).toBeInTheDocument();
    expect(screen.getByTestId("journey-track")).toHaveClass("track--complete");
    expect(leftOf(screen.getByTestId("track-packet"))).toBe(100);
    // Hover title shows the tracker time
    expect(screen.getByTestId("track-CREDITED")).toHaveAttribute("title", "2026-10-30 09:00:06Z");
    expect(screen.getByTestId("track-SCREENING")).toHaveAttribute("title", "Screening");
  });

  it("reports clicks and marks the focused stage", async () => {
    const onFocus = vi.fn();
    render(<JourneyTrack timeline={buildTimeline(payment("SENT"), hops.slice(0, 2))} focusKey="SENT_TO_SWIFT" onFocus={onFocus} />);
    expect(screen.getByTestId("track-SENT_TO_SWIFT")).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByTestId("track-SENT_TO_SWIFT")).toHaveClass("is-focus");
    await userEvent.click(screen.getByRole("button", { name: "Credited: pending" }));
    expect(onFocus).toHaveBeenCalledWith("CREDITED");
  });

  it("works without a focus handler", async () => {
    render(<JourneyTrack timeline={buildTimeline(payment("SENT"), [])} />);
    await userEvent.click(screen.getByTestId("track-CREDITED"));
    expect(screen.getByTestId("track-CREDITED")).toHaveAttribute("aria-pressed", "false");
  });
});

describe("<JourneyStatsStrip />", () => {
  it("shows end-to-end time, longest leg and event count", () => {
    render(<JourneyStatsStrip timeline={buildTimeline(payment("ACKED"), hops)} />);
    expect(screen.getByTestId("stat-current")).toHaveTextContent("Credited");
    expect(screen.getByTestId("stat-total")).toHaveTextContent("6.0s");
    expect(screen.getByTestId("stat-slowest")).toHaveTextContent("Sent to SWIFT · 4.0s");
    expect(screen.getByTestId("stat-events")).toHaveTextContent("4");
    expect(screen.getByTestId("stat-last")).toHaveTextContent("2026-10-30 09:00:06Z");
  });

  it("shows dashes before the tracker has data", () => {
    render(<JourneyStatsStrip timeline={buildTimeline(payment("DRAFT"), [])} />);
    expect(screen.getByTestId("stat-total")).toHaveTextContent("—");
    expect(screen.getByTestId("stat-slowest")).toHaveTextContent("—");
    expect(screen.getByTestId("stat-last")).toHaveTextContent("—");
    expect(screen.getByTestId("stat-events")).toHaveTextContent("0");
  });
});

describe("<GpiTimeline /> track ↔ timeline focus", () => {
  it("clicking a track point highlights the matching timeline step, clicking again clears it", async () => {
    const user = userEvent.setup();
    render(<GpiTimeline payment={payment("ACKED")} hops={hops} />);
    const point = screen.getByTestId("track-RECEIVED_BY_CORRESPONDENT");
    await user.click(point);
    expect(screen.getByTestId("stage-RECEIVED_BY_CORRESPONDENT")).toHaveClass("tl--focus");
    expect(point).toHaveAttribute("aria-pressed", "true");
    // Switching focus moves the highlight
    await user.click(screen.getByTestId("track-CREDITED"));
    expect(screen.getByTestId("stage-RECEIVED_BY_CORRESPONDENT")).not.toHaveClass("tl--focus");
    expect(screen.getByTestId("stage-CREDITED")).toHaveClass("tl--focus");
    await user.click(screen.getByTestId("track-CREDITED"));
    expect(screen.getByTestId("stage-CREDITED")).not.toHaveClass("tl--focus");
  });
});

describe("<JourneyDots />", () => {
  it("renders one dot per stage with the lifecycle as a progressbar", () => {
    render(<JourneyDots status="PENDING_CHECK" />);
    const bar = screen.getByRole("progressbar", { name: "Lifecycle 33%" });
    expect(bar).toHaveAttribute("aria-valuenow", "33");
    const dots = [...bar.querySelectorAll(".jdot")].map((d) => d.getAttribute("data-state"));
    expect(dots).toEqual(["done", "done", "active", "pending", "pending", "pending"]);
  });

  it("uses the status tone and shows failures", () => {
    render(<JourneyDots status="SCREENING_HIT" />);
    const bar = screen.getByRole("progressbar");
    expect(bar).toHaveClass("jdots--danger");
    expect(bar.querySelector(".jdot--failed")).not.toBeNull();
  });
});
