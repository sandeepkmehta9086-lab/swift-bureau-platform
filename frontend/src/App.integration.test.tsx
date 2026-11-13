// End-to-end UI flows against an in-memory backend that speaks the same API
// contract as the Spring Boot service. These guard the golden path in
// AGENTS.md: maker creates pacs.008 -> submit -> checker approves with
// step-up TOTP -> stub sends MX -> gpi hops -> credited.
import { act, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { GPI_POLL_MS } from "./App";
import { FakeBackend, renderApp, signIn, signOut, VALID_TOTP } from "./test/renderApp";

let backend: FakeBackend;

beforeEach(() => {
  backend = new FakeBackend();
  backend.install();
});

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

const stage = (key: string) => screen.getByTestId(`stage-${key}`);
const stageState = (key: string) => stage(key).getAttribute("data-state");
const trackState = (key: string) => screen.getByTestId(`track-${key}`).getAttribute("data-state");
const packetAt = () => parseFloat(screen.getByTestId("track-packet").style.left);
const pointAt = (key: string) => parseFloat(screen.getByTestId(`track-${key}`).style.left);

describe("golden path: maker → checker → gpi credited", () => {
  it("moves the payment through every timeline stage", async () => {
    const { user } = renderApp("/");
    await signIn(user, "meridian.maker");

    // Maker: payments list → new draft
    await user.click(screen.getByRole("link", { name: "Payments" }));
    expect(await screen.findByText("No outbound payments yet.")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "New pacs.008" }));
    await screen.findByRole("heading", { name: "Create payment" });
    const amount = screen.getByLabelText("Amount");
    await user.clear(amount);
    await user.type(amount, "2500.50");
    await user.click(screen.getByRole("button", { name: "Save draft" }));

    // Draft detail
    await screen.findByTestId("uetr");
    expect(screen.getByText("2,500.50")).toBeInTheDocument();
    expect(screen.getByTestId("gpi-headline")).toHaveTextContent(/draft/i);
    expect(stageState("INITIATED")).toBe("done");
    expect(within(stage("INITIATED")).getByText("Created by maker")).toBeInTheDocument();
    expect(stageState("SCREENING")).toBe("pending");
    expect(packetAt()).toBe(0);
    expect(screen.getByTestId("stat-current")).toHaveTextContent("Initiated");
    expect(screen.getByTestId("stat-events")).toHaveTextContent("1");

    // The create call kept the original contract
    const [create] = backend.callsTo("POST", "/api/payments");
    expect(create.body).toMatchObject({
      messageType: "pacs.008",
      amount: 2500.5,
      debtorTown: "London",
      creditorCountry: "US",
      templateId: null,
    });

    // Submit for screening
    await user.click(screen.getByRole("button", { name: "Submit for screening" }));
    await waitFor(() => expect(stageState("APPROVAL")).toBe("active"));
    expect(stageState("SCREENING")).toBe("done");
    expect(screen.getByTestId("gpi-headline")).toHaveTextContent("In progress: 4-eyes approval");
    // Track and timeline agree; the packet moved forward to approval
    expect(trackState("APPROVAL")).toBe("active");
    expect(packetAt()).toBe(pointAt("APPROVAL"));
    expect(packetAt()).toBeGreaterThan(0);
    expect(screen.getByTestId("stat-current")).toHaveTextContent("4-eyes approval");
    expect(screen.queryByRole("button", { name: "Approve and release" })).toBeNull();
    const uetr = screen.getByTestId("uetr").textContent;

    // Checker signs in on the same page and releases with step-up TOTP
    await signOut(user);
    await signIn(user, "meridian.checker");
    await screen.findByTestId("uetr");
    expect(screen.getByTestId("uetr")).toHaveTextContent(uetr!);
    expect(screen.queryByRole("button", { name: "Submit for screening" })).toBeNull();
    await user.type(screen.getByLabelText("Step-up TOTP"), VALID_TOTP);
    await user.click(screen.getByRole("button", { name: "Approve and release" }));

    await waitFor(() => expect(screen.getByTestId("gpi-progress")).toHaveTextContent("100%"));
    for (const key of ["INITIATED", "SCREENING", "APPROVAL", "SENT_TO_SWIFT", "RECEIVED_BY_CORRESPONDENT", "CREDITED"]) {
      expect(stageState(key)).toBe("done");
    }
    expect(within(stage("SENT_TO_SWIFT")).getByText("STUB-NETREF-0001")).toBeInTheDocument();
    expect(within(stage("RECEIVED_BY_CORRESPONDENT")).getByText("pacs.002 ACSC")).toBeInTheDocument();
    expect(within(stage("CREDITED")).getByText("CHASUS33")).toBeInTheDocument();
    expect(screen.getByTestId("gpi-headline")).toHaveTextContent(/credited to beneficiary/i);
    expect(screen.getByTitle("ACKED")).toBeInTheDocument();
    // Track complete: every point done, packet at the end, tracker stats filled in
    for (const key of ["INITIATED", "SCREENING", "APPROVAL", "SENT_TO_SWIFT", "RECEIVED_BY_CORRESPONDENT", "CREDITED"]) {
      expect(trackState(key)).toBe("done");
    }
    expect(packetAt()).toBe(100);
    expect(screen.getByTestId("journey-track")).toHaveAttribute("data-time-scaled", "true");
    expect(screen.getByTestId("stat-events")).toHaveTextContent("4");
    expect(screen.getByTestId("stat-total")).not.toHaveTextContent("—");
    expect(screen.getByTestId("stat-current")).toHaveTextContent("Credited");
    // UETR never changes across the lifecycle
    expect(screen.getByTestId("uetr")).toHaveTextContent(uetr!);

    // Approve went out with the step-up header and the checker's token
    const approveCall = backend.calls.find((c) => c.path.endsWith("/approve"))!;
    expect(approveCall.headers.get("X-Step-Up-Totp")).toBe(VALID_TOTP);
    expect(approveCall.headers.get("Authorization")).toMatch(/^Bearer /);

    // Inbound pacs.002 lands in the inbox
    await user.click(screen.getByRole("link", { name: "Inbox" }));
    expect(await screen.findByText("pacs.002")).toBeInTheDocument();
  });
});

describe("payment detail", () => {
  async function openSeeded(loginId: string, overrides = {}) {
    const p = backend.seedPayment(overrides);
    const { user } = renderApp(`/payments/${p.id}`);
    await signIn(user, loginId);
    await screen.findByTestId("uetr");
    return { user, p };
  }

  it("shows a screening hit as a stopped journey", async () => {
    const { user } = await openSeeded("meridian.maker", { debtorName: "SANCTIONED PERSON" });
    await user.click(screen.getByRole("button", { name: "Submit for screening" }));
    await waitFor(() => expect(stageState("SCREENING")).toBe("failed"));
    expect(stageState("CREDITED")).toBe("skipped");
    expect(trackState("SCREENING")).toBe("failed");
    expect(packetAt()).toBe(pointAt("SCREENING"));
    expect(screen.getByTestId("journey-track")).toHaveClass("track--failed");
    expect(screen.getByTestId("stat-current")).toHaveTextContent("Screening");
    expect(screen.getByTestId("gpi-headline")).toHaveTextContent(/screening hit/i);
    expect(screen.getByTitle("SCREENING_HIT")).toBeInTheDocument();
  });

  it("surfaces a bad step-up TOTP and leaves the timeline unchanged", async () => {
    const { user } = await openSeeded("meridian.checker", { status: "PENDING_CHECK" });
    await user.type(screen.getByLabelText("Step-up TOTP"), "000000");
    await user.click(screen.getByRole("button", { name: "Approve and release" }));
    expect(await screen.findByText("Step-up TOTP invalid")).toBeInTheDocument();
    expect(stageState("APPROVAL")).toBe("active");
    expect(stageState("SENT_TO_SWIFT")).toBe("pending");
  });

  it("blocks a maker from approving their own payment (four eyes)", async () => {
    // Checker is the maker of this one.
    const { user } = await openSeeded("meridian.checker", { status: "PENDING_CHECK", makerId: "u-checker" });
    await user.type(screen.getByLabelText("Step-up TOTP"), VALID_TOTP);
    await user.click(screen.getByRole("button", { name: "Approve and release" }));
    expect(await screen.findByText("Maker cannot approve their own payment")).toBeInTheDocument();
  });

  it("shows an error when submitting from a state that does not allow it", async () => {
    const { user } = await openSeeded("meridian.maker", { status: "PENDING_CHECK" });
    await user.click(screen.getByRole("button", { name: "Submit for screening" }));
    expect(await screen.findByText("Cannot submit from PENDING_CHECK")).toBeInTheDocument();
  });

  it("sends a camt.056 recall and keeps the page usable", async () => {
    const { user, p } = await openSeeded("meridian.maker", { status: "ACKED" });
    await user.click(screen.getByRole("button", { name: "camt.056 recall" }));
    await waitFor(() => expect(backend.calls.some((c) => c.path === `/api/payments/${p.id}/recall`)).toBe(true));
    expect(backend.payments.some((x) => x.messageType === "camt.056" && x.uetr === p.uetr)).toBe(true);
    expect(screen.queryByRole("alert")).toBeNull();
    // Default reason CUST: the correspondent refuses, funds stay credited and the tracker says so.
    const recall = backend.calls.find((c) => c.path === `/api/payments/${p.id}/recall`)!;
    expect(recall.body).toEqual({ reasonCode: "CUST" });
    await waitFor(() => expect(screen.getByTestId("gpi-headline")).toHaveTextContent(/recall rejected/i));
    expect(screen.getByText("Recall rejected")).toBeInTheDocument();
    expect(screen.getByTitle("ACKED")).toBeInTheDocument();
  });

  it("recalls with a reason the correspondent honours and shows the pacs.004 return", async () => {
    const { user, p } = await openSeeded("meridian.maker", { status: "ACKED" });
    await user.selectOptions(screen.getByLabelText("Recall reason"), "DUPL");
    await user.click(screen.getByRole("button", { name: "camt.056 recall" }));
    await waitFor(() => expect(screen.getByTestId("gpi-headline")).toHaveTextContent(/funds returned/i));
    const recall = backend.calls.find((c) => c.path === `/api/payments/${p.id}/recall`)!;
    expect(recall.body).toEqual({ reasonCode: "DUPL" });
    expect(screen.getByTitle("RETURNED")).toBeInTheDocument();
    for (const label of ["Recall requested", "Recall accepted", "Funds returned"]) {
      expect(screen.getByText(label)).toBeInTheDocument();
    }
    expect(within(screen.getByText("Funds returned").closest("li")!).getByText("pacs.004 FOCR funds returned to nostro")).toBeInTheDocument();
    expect(screen.getByTestId("gpi-progress")).toHaveTextContent("100%");
    expect(backend.payments.some((x) => x.messageType === "pacs.004" && x.uetr === p.uetr)).toBe(true);
    // A returned payment cannot be recalled again.
    expect(screen.queryByRole("button", { name: "camt.056 recall" })).toBeNull();
  });

  it("offers the recall only for sent interbank payments", async () => {
    await openSeeded("meridian.maker", { status: "DRAFT" });
    expect(screen.queryByRole("button", { name: "camt.056 recall" })).toBeNull();
    // ...and says why, instead of leaving the user hunting for the button.
    expect(screen.getByTestId("recall-hint")).toHaveTextContent(/offered once the payment has been sent.*This one is DRAFT/);
  });

  it("explains a refused recall and lets the user retry with a reason that is honoured", async () => {
    const { user } = await openSeeded("meridian.maker", { status: "ACKED" });
    expect(screen.getByTestId("recall-hint")).toHaveTextContent(/refuses CUST/);
    await user.click(screen.getByRole("button", { name: "camt.056 recall" }));
    await waitFor(() => expect(screen.getByTestId("recall-hint")).toHaveTextContent(/refused by the correspondent \(camt\.029 RJCR\)/));
    // Still ACKED, so the form is still there for a second attempt.
    await user.selectOptions(screen.getByLabelText("Recall reason"), "FRAD");
    await user.click(screen.getByRole("button", { name: "camt.056 recall" }));
    await waitFor(() => expect(screen.getByTitle("RETURNED")).toBeInTheDocument());
    expect(screen.getByTestId("recall-hint")).toHaveTextContent(/cannot be recalled again/);
    expect(backend.hops.map((h) => h.hopStatus).slice(-5)).toEqual([
      "RECALL_REQUESTED",
      "RECALL_REJECTED",
      "RECALL_REQUESTED",
      "RECALL_ACCEPTED",
      "RETURNED",
    ]);
  });

  it("lets a checker recall but not a role the backend would refuse", async () => {
    const { user, p } = await openSeeded("meridian.checker", { status: "SENT" });
    expect(screen.getByRole("button", { name: "camt.056 recall" })).toBeInTheDocument();
    await signOut(user);
    await signIn(user, "meridian.liq");
    await screen.findByTestId("uetr");
    expect(screen.queryByRole("button", { name: "camt.056 recall" })).toBeNull();
    expect(screen.getByTestId("recall-hint")).toHaveTextContent(/Only a payment maker, checker or bank security officer/);
    expect(backend.calls.some((c) => c.path === `/api/payments/${p.id}/recall`)).toBe(false);
  });

  it("a camt.056 is not a payment: no actions on it, and it links back to the payment it recalls", async () => {
    const original = backend.seedPayment({ status: "ACKED" });
    const recall = backend.seedPayment({ uetr: original.uetr, messageType: "camt.056", status: "ACKED", reasonCode: "CUST" });
    const { user } = renderApp(`/payments/${recall.id}`);
    await signIn(user, "meridian.maker");
    await screen.findByTestId("uetr");
    expect(screen.queryByRole("button", { name: "camt.056 recall" })).toBeNull();
    expect(screen.queryByRole("button", { name: "Submit for screening" })).toBeNull();
    expect(screen.getByTestId("recall-hint")).toHaveTextContent(/camt\.056 is a message about a payment/);
    await user.click(await screen.findByRole("link", { name: "Open the original pacs.008 (ACKED)" }));
    // Now on the pacs.008, where the recall is offered.
    expect(await screen.findByRole("button", { name: "camt.056 recall" })).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: /Open the original/ })).toBeNull();
  });

  it("shows the frozen MX in a collapsible panel", async () => {
    await openSeeded("meridian.maker", { mxXml: "<Document>frozen</Document>" });
    const summary = screen.getByText("MX message (ISO 20022 XML)");
    expect(summary.closest("details")).not.toHaveAttribute("open");
    expect(screen.getByText("<Document>frozen</Document>")).toBeInTheDocument();
  });

  it("shows a not-found error for an unknown payment", async () => {
    const { user } = renderApp("/payments/nope");
    await signIn(user, "meridian.maker");
    expect(await screen.findByText("Payment not found")).toBeInTheDocument();
  });

  it("copies the UETR to the clipboard", async () => {
    const { user, p } = await openSeeded("meridian.maker");
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, "clipboard", { value: { writeText }, configurable: true });
    await user.click(screen.getByRole("button", { name: "Copy UETR" }));
    expect(writeText).toHaveBeenCalledWith(p.uetr);
    expect(await screen.findByText("Copied")).toBeInTheDocument();
  });
});

describe("live gpi tracking", () => {
  it("polls while in flight and stops once credited", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    backend.holdInbound = true;
    const p = backend.seedPayment({ status: "PENDING_CHECK" });
    const { user } = renderApp(`/payments/${p.id}`, { advanceTimers: vi.advanceTimersByTime });
    await signIn(user, "meridian.checker");
    await screen.findByTestId("uetr");

    await user.type(screen.getByLabelText("Step-up TOTP"), VALID_TOTP);
    await user.click(screen.getByRole("button", { name: "Approve and release" }));

    // Sent but not yet acknowledged: correspondent hop is pending, tracker is live
    await waitFor(() => expect(stageState("RECEIVED_BY_CORRESPONDENT")).toBe("active"));
    expect(stageState("SENT_TO_SWIFT")).toBe("done");
    expect(screen.getByText("Live")).toBeInTheDocument();
    const packetWhileSent = packetAt();
    expect(packetWhileSent).toBe(pointAt("RECEIVED_BY_CORRESPONDENT"));
    const eventsWhileSent = Number(screen.getByTestId("stat-events").textContent);

    // The correspondent answers; the next poll picks it up without a click
    backend.deliverInbound(p.id);
    const gpiCallsBefore = backend.callsTo("GET", "/api/gpi/").length;
    await act(async () => {
      vi.advanceTimersByTime(GPI_POLL_MS);
    });
    await waitFor(() => expect(stageState("CREDITED")).toBe("done"));
    expect(backend.callsTo("GET", "/api/gpi/").length).toBeGreaterThan(gpiCallsBefore);
    expect(screen.queryByText("Live")).toBeNull();
    // The packet travelled to the end of the track and new tracker events were counted
    expect(packetAt()).toBe(100);
    expect(packetAt()).toBeGreaterThan(packetWhileSent);
    expect(Number(screen.getByTestId("stat-events").textContent)).toBeGreaterThan(eventsWhileSent);

    // Polling stops once the journey is complete
    const settled = backend.callsTo("GET", "/api/gpi/").length;
    await act(async () => {
      vi.advanceTimersByTime(GPI_POLL_MS * 3);
    });
    expect(backend.callsTo("GET", "/api/gpi/").length).toBe(settled);
  });

  it("manual refresh reloads the tracker", async () => {
    backend.holdInbound = true;
    const p = backend.seedPayment({ status: "PENDING_CHECK" });
    const { user } = renderApp(`/payments/${p.id}`);
    await signIn(user, "meridian.so");
    await screen.findByTestId("uetr");
    expect(stageState("APPROVAL")).toBe("active");
    // Approve happens elsewhere
    p.status = "SENT";
    backend.hops.push({ id: "h-x", tenantId: p.tenantId, uetr: p.uetr, hopStatus: "SENT_TO_SWIFT", bic: "MIDNGB2L", detail: "NET", occurredAt: "2026-10-30T10:00:00Z" });
    await user.click(screen.getByRole("button", { name: "Refresh tracker" }));
    await waitFor(() => expect(stageState("SENT_TO_SWIFT")).toBe("done"));
  });
});

describe("journey track interaction", () => {
  it("clicking a point on the track highlights that step in the timeline", async () => {
    const p = backend.seedPayment({ status: "PENDING_CHECK" });
    const { user } = renderApp(`/payments/${p.id}`);
    await signIn(user, "meridian.so");
    await screen.findByTestId("uetr");
    const track = screen.getByRole("group", { name: "Journey track" });
    await user.click(within(track).getByRole("button", { name: "4-eyes approval: active" }));
    expect(stage("APPROVAL")).toHaveClass("tl--focus");
    // Focus survives a tracker refresh
    await user.click(screen.getByRole("button", { name: "Refresh tracker" }));
    await waitFor(() => expect(backend.callsTo("GET", "/api/gpi/").length).toBeGreaterThan(1));
    expect(stage("APPROVAL")).toHaveClass("tl--focus");
  });
});

describe("payments list", () => {
  it("lists payments with status badges, journey bars and opens detail", async () => {
    backend.seedPayment({ status: "SENT", amount: 1000 });
    backend.seedPayment({ status: "ACKED", amount: 99.5, currency: "EUR" });
    const { user } = renderApp("/payments");
    await signIn(user, "meridian.maker");
    const table = await screen.findByRole("table");
    await within(table).findByText("99.50");
    const bars = within(table).getAllByRole("progressbar");
    expect(bars.map((b) => b.getAttribute("aria-valuenow"))).toEqual(["100", "67"]);
    expect(within(table).getByTitle("SENT")).toBeInTheDocument();
    const dotStates = bars.map((b) => [...b.querySelectorAll(".jdot")].map((d) => d.getAttribute("data-state")));
    expect(dotStates[0].every((st) => st === "done")).toBe(true);
    expect(dotStates[1]).toEqual(["done", "done", "done", "done", "active", "pending"]);
    await user.click(within(table).getAllByRole("button", { name: "Open" })[0]);
    expect(await screen.findByTestId("gpi-progress")).toHaveTextContent("100%");
  });

  it("lists payments only, with their recall state; pain.002 / camt.056 rows are opt-in", async () => {
    const fresh = backend.seedPayment({ status: "ACKED", amount: 100 });
    const refused = backend.seedPayment({ status: "ACKED", amount: 200 });
    const returned = backend.seedPayment({ status: "RETURNED", amount: 300 });
    backend.seedPayment({ status: "DRAFT", amount: 400 });
    backend.seedPayment({ uetr: refused.uetr, messageType: "camt.056", status: "ACKED", reasonCode: "CUST", amount: 200 });
    backend.seedPayment({ uetr: returned.uetr, messageType: "camt.056", status: "ACKED", reasonCode: "DUPL", amount: 300 });
    backend.seedPayment({ uetr: fresh.uetr, messageType: "pain.002", status: "ACKED", reasonCode: "ACCP", amount: 100 });
    const { user } = renderApp("/payments");
    await signIn(user, "meridian.maker");
    const table = await screen.findByRole("table");
    await within(table).findByText("400.00");

    const cells = () => within(table).getAllByRole("row").slice(1).map((r) => within(r).getAllByRole("cell").map((c) => c.textContent));
    // Newest first: draft, returned, refused, fresh. Columns: UETR, Type, Status, Journey, Recall, Amount.
    expect(cells().map((c) => c[1])).toEqual(["pacs.008", "pacs.008", "pacs.008", "pacs.008"]);
    expect(cells().map((c) => c[4])).toEqual(["—", "DUPL · returned", "CUST · rejected", "Can be recalled"]);

    await user.click(screen.getByLabelText(/Show related messages \(3 pain\.002 \/ camt\.056\)/));
    expect(cells().map((c) => c[1])).toEqual([
      "pain.002 · ACCP",
      "camt.056 · DUPL",
      "camt.056 · CUST",
      "pacs.008",
      "pacs.008",
      "pacs.008",
      "pacs.008",
    ]);
    // A message row carries no journey and no recall state of its own.
    expect(cells()[1].slice(3, 5)).toEqual(["—", "—"]);
  });

  it("opens a recallable payment from the list and recalls it", async () => {
    const p = backend.seedPayment({ status: "ACKED" });
    backend.seedPayment({ uetr: p.uetr, messageType: "camt.056", status: "ACKED", reasonCode: "CUST" });
    const { user } = renderApp("/payments");
    await signIn(user, "meridian.maker");
    const table = await screen.findByRole("table");
    // The only row is the payment itself, so "Open" cannot land on the camt.056.
    await user.click(await within(table).findByRole("button", { name: "Open" }));
    await user.selectOptions(await screen.findByLabelText("Recall reason"), "DUPL");
    await user.click(screen.getByRole("button", { name: "camt.056 recall" }));
    await waitFor(() => expect(screen.getByTestId("gpi-headline")).toHaveTextContent(/funds returned/i));
    expect(backend.calls.find((c) => c.path === `/api/payments/${p.id}/recall`)!.body).toEqual({ reasonCode: "DUPL" });
  });

  it("hides creation from checkers", async () => {
    const { user } = renderApp("/payments");
    await signIn(user, "meridian.checker");
    expect(await screen.findByText(/Only a payment maker or bank security officer/)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "New pacs.008" })).toBeNull();
  });
});

describe("new payment form", () => {
  it("applies a template and sends its id", async () => {
    const { user } = renderApp("/payments/new");
    await signIn(user, "meridian.maker");
    await screen.findByRole("option", { name: "EUR to Deutsche" });
    await user.selectOptions(screen.getByLabelText("Use template"), "tpl-1");
    expect(screen.getByLabelText("Instructed agent BIC")).toHaveValue("DEUTDEFF");
    expect(screen.getByLabelText("Currency")).toHaveValue("EUR");
    await user.click(screen.getByRole("button", { name: "Save draft" }));
    await screen.findByTestId("uetr");
    const [create] = backend.callsTo("POST", "/api/payments");
    expect(create.body).toMatchObject({ templateId: "tpl-1", instructedAgentBic: "DEUTDEFF", amount: 250 });
    expect(within(screen.getByTestId("gpi-route")).getByText("DEUTDEFF")).toBeInTheDocument();
  });

  it("shows backend validation errors (structured address)", async () => {
    const { user } = renderApp("/payments/new");
    await signIn(user, "meridian.maker");
    await user.clear(await screen.findByLabelText("Debtor town"));
    await user.click(screen.getByRole("button", { name: "Save draft" }));
    expect(await screen.findByText("Structured town and country are mandatory")).toBeInTheDocument();
  });

  it("sends a null amount when left blank", async () => {
    const { user } = renderApp("/payments/new");
    await signIn(user, "meridian.maker");
    await user.clear(await screen.findByLabelText("Amount"));
    await user.click(screen.getByRole("button", { name: "Save draft" }));
    await screen.findByTestId("uetr");
    expect(backend.callsTo("POST", "/api/payments")[0].body.amount).toBeNull();
  });
});

describe("scenario 1: customer instruction (pain.001 → pain.002)", () => {
  async function openCustomerChannel() {
    const { user } = renderApp("/payments/new");
    await signIn(user, "meridian.maker");
    await screen.findByRole("heading", { name: "Create payment" });
    await user.selectOptions(screen.getByLabelText("Channel"), "customer");
    expect(screen.getByRole("button", { name: "Send pain.001" })).toBeInTheDocument();
    return user;
  }

  it("an accepted pain.001 opens the drafted pacs.008 with the customer hops on the tracker", async () => {
    const user = await openCustomerChannel();
    await user.click(screen.getByRole("button", { name: "Send pain.001" }));
    await screen.findByTestId("uetr");

    const [call] = backend.callsTo("POST", "/api/payments/initiate");
    expect(call.body).toMatchObject({
      debtorAgentBic: "MIDNGB2L",
      creditorAgentBic: "CHASUS33",
      amount: 1500,
      currency: "USD",
      debtorName: "Acme Ltd",
      debtorTown: "London",
      creditorCountry: "US",
    });
    expect(call.body).not.toHaveProperty("messageType");

    // The draft shares the UETR minted on the pain.001 and the tracker starts with the customer leg.
    const instruction = backend.payments.find((x) => x.messageType === "pain.001")!;
    expect(screen.getByTestId("uetr")).toHaveTextContent(instruction.uetr);
    expect(screen.getByText("Customer instruction")).toBeInTheDocument();
    expect(screen.getByText("Instruction accepted")).toBeInTheDocument();
    expect(stageState("INITIATED")).toBe("done");
    expect(screen.getByTestId("gpi-headline")).toHaveTextContent(/draft/i);
    expect(backend.payments.map((x) => x.messageType).sort()).toEqual(["pacs.008", "pain.001", "pain.002"]);
  });

  it("a rejected pain.001 shows the pain.002 reason and never drafts a pacs.008", async () => {
    const user = await openCustomerChannel();
    await user.clear(screen.getByLabelText("Debtor town"));
    await user.click(screen.getByRole("button", { name: "Send pain.001" }));

    const panel = await screen.findByTestId("pain002");
    expect(panel).toHaveTextContent("pain.002 RJCT · BE04");
    expect(panel).toHaveTextContent(/town and country/i);
    expect(backend.payments.some((x) => x.messageType === "pacs.008")).toBe(false);
    expect(backend.payments.filter((x) => x.messageType.startsWith("pain.")).length).toBe(2);
    expect(backend.hops.map((h) => h.hopStatus)).toEqual(["CUSTOMER_INSTRUCTED", "CUSTOMER_REJECTED"]);

    // The pain.002 itself opens as a stopped journey.
    await user.click(screen.getByRole("button", { name: "View pain.002" }));
    await screen.findByTestId("uetr");
    expect(screen.getByTestId("gpi-headline")).toHaveTextContent(/instruction rejected/i);
    expect(stageState("INITIATED")).toBe("failed");
    expect(screen.queryByRole("button", { name: "camt.056 recall" })).toBeNull();
  });

  it("portal channel still saves a plain draft", async () => {
    const { user } = renderApp("/payments/new");
    await signIn(user, "meridian.maker");
    await screen.findByRole("heading", { name: "Create payment" });
    expect(screen.getByRole("button", { name: "Save draft" })).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Save draft" }));
    await screen.findByTestId("uetr");
    expect(backend.callsTo("POST", "/api/payments/initiate")).toHaveLength(0);
    expect(backend.callsTo("POST", "/api/payments")).toHaveLength(1);
  });
});

describe("authentication", () => {
  it("rejects bad credentials", async () => {
    const { user } = renderApp("/");
    await user.clear(screen.getByLabelText("Password"));
    await user.type(screen.getByLabelText("Password"), "wrong");
    await user.click(screen.getByRole("button", { name: "Sign in" }));
    expect(await screen.findByText("Invalid login ID or password")).toBeInTheDocument();
  });

  it("enrolls MFA for a first-time user then enters the app", async () => {
    const { user } = renderApp("/");
    await user.clear(screen.getByLabelText("Login ID"));
    await user.type(screen.getByLabelText("Login ID"), "meridian.new");
    await user.click(screen.getByRole("button", { name: "Sign in" }));
    expect(await screen.findByText("JBSWY3DPEHPK3PXP", { selector: "pre" })).toBeInTheDocument();
    await user.type(screen.getByLabelText("TOTP"), VALID_TOTP);
    await user.click(screen.getByRole("button", { name: "Confirm MFA" }));
    expect(await screen.findByRole("heading", { name: "Overview" })).toBeInTheDocument();
  });

  it("shows an error for a wrong enrollment code", async () => {
    const { user } = renderApp("/");
    await user.clear(screen.getByLabelText("Login ID"));
    await user.type(screen.getByLabelText("Login ID"), "meridian.new");
    await user.click(screen.getByRole("button", { name: "Sign in" }));
    await user.type(await screen.findByLabelText("TOTP"), "999999");
    await user.click(screen.getByRole("button", { name: "Confirm MFA" }));
    expect(await screen.findByText("Invalid authenticator code")).toBeInTheDocument();
  });

  it("gates a session without MFA behind enrollment", async () => {
    localStorage.setItem("sb.token", "tok-x");
    localStorage.setItem("sb.user", JSON.stringify({ id: "u", loginId: "x", role: "PAYMENT_MAKER", tenantId: "t", mfaEnabled: false }));
    renderApp("/");
    expect(await screen.findByRole("heading", { name: "Enroll authenticator" })).toBeInTheDocument();
    // Fake backend rejects the unknown token — the error is shown, not swallowed
    expect(await screen.findByText("Sign in required")).toBeInTheDocument();
  });

  it("renders the invite acceptance page", async () => {
    renderApp("/invite");
    expect(screen.getByRole("heading", { name: "Accept invitation" })).toBeInTheDocument();
    expect(screen.getByLabelText("Invite token")).toBeInTheDocument();
  });
});

describe("navigation by role", () => {
  it("bureau operators see tenant/CSP tools but not payments", async () => {
    const { user } = renderApp("/");
    await signIn(user, "bureau.ops");
    const nav = screen.getByRole("navigation", { name: "Main" });
    expect(within(nav).getByRole("link", { name: "Tenants" })).toBeInTheDocument();
    expect(within(nav).getByRole("link", { name: "CSP pack" })).toBeInTheDocument();
    expect(within(nav).queryByRole("link", { name: "Payments" })).toBeNull();
    // STP report is bank-only
    expect(backend.callsTo("GET", "/api/reports/stp")).toHaveLength(0);
    await user.click(within(nav).getByRole("link", { name: "Tenants" }));
    expect(await screen.findByText("Meridian Bank")).toBeInTheDocument();
    await user.click(within(nav).getByRole("link", { name: "CSP pack" }));
    expect(await screen.findByText("SWIFT environment protection")).toBeInTheDocument();
  });

  it("bank users see the overview STP rate and every tenant screen", async () => {
    backend.seedPayment({ status: "ACKED" });
    backend.seedPayment({ status: "SENT" });
    const { user } = renderApp("/");
    await signIn(user, "meridian.so");
    expect(await screen.findByTestId("stp-rate")).toHaveTextContent("50%");
    const nav = screen.getByRole("navigation", { name: "Main" });

    await user.click(within(nav).getByRole("link", { name: "Liquidity" }));
    expect(await screen.findByText("98500")).toBeInTheDocument();
    await user.click(within(nav).getByRole("link", { name: "RMA" }));
    expect(await screen.findByTitle("ACTIVE")).toBeInTheDocument();
    await user.click(within(nav).getByRole("link", { name: "Audit" }));
    expect(await screen.findByText("PAYMENT_CREATED")).toBeInTheDocument();
    await user.click(within(nav).getByRole("link", { name: "Charges" }));
    await user.click(screen.getByRole("button", { name: "Quote USD to CHASUS33" }));
    expect(await screen.findByText("SHA / SHAR")).toBeInTheDocument();
    await user.click(within(nav).getByRole("link", { name: "Templates" }));
    expect(await screen.findByText("EUR to Deutsche")).toBeInTheDocument();
    await user.click(within(nav).getByRole("link", { name: "Users" }));
    await user.type(await screen.findByLabelText("Login ID"), "meridian.new2");
    await user.click(screen.getByRole("button", { name: "Invite" }));
    expect(await screen.findByText("INV-TOKEN-1")).toBeInTheDocument();
  });

  it("security officer can deactivate a template", async () => {
    const { user } = renderApp("/templates");
    await signIn(user, "meridian.so");
    await user.click(await screen.findByRole("button", { name: "Deactivate" }));
    expect(await screen.findByRole("button", { name: "Activate" })).toBeInTheDocument();
    expect(screen.getByTitle("INACTIVE")).toBeInTheDocument();
    const patch = backend.calls.find((c) => c.method === "PATCH")!;
    expect(patch.body).toMatchObject({ id: "tpl-1", active: false });
  });
});
