import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { LifecycleBar, StatusBadge } from "./StatusBadge";

describe("<StatusBadge />", () => {
  it("renders a human label with the raw status as title", () => {
    render(<StatusBadge status="PENDING_CHECK" />);
    const badge = screen.getByText("Pending check");
    expect(badge).toHaveAttribute("title", "PENDING_CHECK");
    expect(badge).toHaveClass("badge--warn");
  });

  it.each([
    ["ACKED", "badge--ok"],
    ["SENT", "badge--info"],
    ["SCREENING_HIT", "badge--danger"],
    ["WHATEVER", "badge--muted"],
  ])("%s uses %s", (status, cls) => {
    render(<StatusBadge status={status} />);
    expect(screen.getByTitle(status)).toHaveClass(cls);
  });

  it("renders a dash for missing status", () => {
    render(<StatusBadge status={null} />);
    expect(screen.getByText("—")).toBeInTheDocument();
  });
});

describe("<LifecycleBar />", () => {
  it("exposes lifecycle progress as a progressbar", () => {
    render(<LifecycleBar status="SENT" />);
    const bar = screen.getByRole("progressbar");
    expect(bar).toHaveAttribute("aria-valuenow", "67");
    expect(bar).toHaveClass("meter--info");
  });
});
