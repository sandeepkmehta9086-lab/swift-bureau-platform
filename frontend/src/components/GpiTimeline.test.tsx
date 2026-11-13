import { render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { GpiHop } from "../gpi";
import { GpiTimeline } from "./GpiTimeline";

const payment = (status: string) => ({
  status,
  uetr: "e2b4c7d0-1a2b-4c3d-8e9f-000000000001",
  instructingAgentBic: "MIDNGB2L",
  instructedAgentBic: "CHASUS33",
});

const hops: GpiHop[] = [
  { id: "1", hopStatus: "INITIATED", bic: "MIDNGB2L", detail: "Created by maker", occurredAt: "2026-10-30T09:00:00Z" },
  { id: "2", hopStatus: "SENT_TO_SWIFT", bic: "MIDNGB2L", detail: "NETREF-1", occurredAt: "2026-10-30T09:00:04Z" },
  { id: "3", hopStatus: "RECEIVED_BY_CORRESPONDENT", bic: "CHASUS33", detail: "pacs.002 ACSC", occurredAt: "2026-10-30T09:00:05Z" },
  { id: "4", hopStatus: "CREDITED", bic: "CHASUS33", detail: "Stub tracker complete", occurredAt: "2026-10-30T09:00:06Z" },
];

describe("<GpiTimeline />", () => {
  it("renders every stage of the journey as an ordered list", () => {
    render(<GpiTimeline payment={payment("ACKED")} hops={hops} />);
    const region = screen.getByRole("region", { name: /gpi tracker timeline/i });
    const items = within(region).getAllByRole("listitem");
    expect(items.map((li) => li.querySelector(".tl__label")!.textContent)).toEqual([
      "Initiated",
      "Screening",
      "4-eyes approval",
      "Sent to SWIFT",
      "At correspondent",
      "Credited",
    ]);
  });

  it("shows a completed journey at 100% with tracker details", () => {
    render(<GpiTimeline payment={payment("ACKED")} hops={hops} />);
    expect(screen.getByTestId("gpi-progress")).toHaveTextContent("100%");
    expect(screen.getByTestId("gpi-headline")).toHaveTextContent(/credited to beneficiary/i);
    const credited = screen.getByTestId("stage-CREDITED");
    expect(credited).toHaveAttribute("data-state", "done");
    expect(within(credited).getByText("Stub tracker complete")).toBeInTheDocument();
    expect(within(credited).getByText("CHASUS33")).toBeInTheDocument();
    expect(within(credited).getByText("+1.0s")).toBeInTheDocument();
    expect(within(credited).getByText("2026-10-30 09:00:06Z").tagName).toBe("TIME");
    expect(screen.getByTestId("gpi-route")).toHaveClass("route--complete");
  });

  it("labels inferred steps so users know they are not tracker events", () => {
    render(<GpiTimeline payment={payment("ACKED")} hops={hops} />);
    expect(within(screen.getByTestId("stage-SCREENING")).getByText(/from message status/i)).toBeInTheDocument();
    expect(within(screen.getByTestId("stage-SENT_TO_SWIFT")).queryByText(/from message status/i)).toBeNull();
  });

  it("marks the in-progress step with aria-current", () => {
    render(<GpiTimeline payment={payment("PENDING_CHECK")} hops={hops.slice(0, 1)} />);
    const approval = screen.getByTestId("stage-APPROVAL");
    expect(approval).toHaveAttribute("aria-current", "step");
    expect(within(approval).getByText("In progress")).toBeInTheDocument();
    expect(screen.getByTestId("gpi-headline")).toHaveTextContent("In progress: 4-eyes approval");
    expect(screen.getByTestId("gpi-route")).toHaveClass("route--in_flight");
  });

  it("shows a failed journey with the stopping step highlighted", () => {
    render(<GpiTimeline payment={payment("SCREENING_HIT")} hops={hops.slice(0, 1)} />);
    const screening = screen.getByTestId("stage-SCREENING");
    expect(screening).toHaveAttribute("data-state", "failed");
    expect(within(screening).getByText("✕")).toBeInTheDocument();
    expect(within(screening).getByText(/held for compliance review/i)).toBeInTheDocument();
    expect(screen.getByTestId("stage-CREDITED")).toHaveAttribute("data-state", "skipped");
    expect(screen.getByRole("region", { name: /gpi tracker/i })).toHaveClass("gpi--failed");
  });

  it("renders a draft with numbered pending nodes and placeholder BICs", () => {
    render(<GpiTimeline payment={{ status: "DRAFT", uetr: "u" }} hops={[]} />);
    expect(within(screen.getByTestId("stage-CREDITED")).getByText("6")).toBeInTheDocument();
    expect(within(screen.getByTestId("gpi-route")).getAllByText("—")).toHaveLength(2);
  });

  it("exposes progress to assistive tech", () => {
    render(<GpiTimeline payment={payment("SENT")} hops={hops.slice(0, 2)} />);
    expect(screen.getByLabelText("Journey 67% complete")).toBeInTheDocument();
  });
});
