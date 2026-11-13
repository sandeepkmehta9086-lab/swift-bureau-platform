import { useState } from "react";
import { JourneyStatsStrip, JourneyTrack } from "./JourneyTrack";
import { buildTimeline, formatElapsed, formatTime, GpiHop, PaymentLike, TimelineStage } from "../gpi";

const STATE_LABEL: Record<TimelineStage["state"], string> = {
  done: "Completed",
  active: "In progress",
  pending: "Waiting",
  failed: "Stopped",
  skipped: "Not reached",
};

export function GpiTimeline({ payment, hops }: { payment: PaymentLike; hops: GpiHop[] }) {
  const timeline = buildTimeline(payment, hops);
  const [focusKey, setFocusKey] = useState<string | null>(null);
  const toggleFocus = (key: string) => setFocusKey((k) => (k === key ? null : key));
  return (
    <section className={`gpi gpi--${timeline.outcome}`} aria-label="gpi tracker timeline">
      <header className="gpi__head">
        <div>
          <span className="eyebrow">gpi tracker</span>
          <h3 className="gpi__headline" data-testid="gpi-headline">
            {timeline.headline}
          </h3>
        </div>
        <div className="gpi__meter" aria-label={`Journey ${timeline.progress}% complete`}>
          <span className="gpi__pct" data-testid="gpi-progress">
            {timeline.progress}%
          </span>
          <div className="meter">
            <div className="meter__fill" style={{ width: `${timeline.progress}%` }} />
          </div>
        </div>
      </header>

      <RouteStrip payment={payment} outcome={timeline.outcome} />

      <JourneyTrack timeline={timeline} focusKey={focusKey} onFocus={toggleFocus} />

      <JourneyStatsStrip timeline={timeline} />

      <ol className="timeline">
        {timeline.stages.map((s, i) => (
          <li
            key={s.key}
            className={`tl tl--${s.state}${focusKey === s.key ? " tl--focus" : ""}`}
            data-testid={`stage-${s.key}`}
            data-state={s.state}
            aria-current={s.state === "active" ? "step" : undefined}
            style={{ animationDelay: `${i * 70}ms` }}
          >
            <div className="tl__rail" aria-hidden="true">
              <span className="tl__node">{nodeGlyph(s.state, i)}</span>
            </div>
            <div className="tl__body">
              <div className="tl__top">
                <strong className="tl__label">{s.label}</strong>
                <span className={`tl__state tl__state--${s.state}`}>{STATE_LABEL[s.state]}</span>
                {s.elapsedMs != null && <span className="tl__elapsed">{formatElapsed(s.elapsedMs)}</span>}
              </div>
              <p className="tl__desc">{s.description}</p>
              <div className="tl__meta">
                {s.bic && <code className="chip chip--bic">{s.bic}</code>}
                {s.detail && <span className="tl__detail">{s.detail}</span>}
                {s.occurredAt && (
                  <time className="tl__time" dateTime={s.occurredAt}>
                    {formatTime(s.occurredAt)}
                  </time>
                )}
                {!s.fromTracker && s.state === "done" && <span className="tl__inferred">from message status</span>}
              </div>
            </div>
          </li>
        ))}
      </ol>
    </section>
  );
}

function nodeGlyph(state: TimelineStage["state"], i: number) {
  if (state === "done") return "✓";
  if (state === "failed") return "✕";
  return String(i + 1);
}

function RouteStrip({ payment, outcome }: { payment: PaymentLike; outcome: string }) {
  return (
    <div className={`route route--${outcome}`} data-testid="gpi-route">
      <div className="route__end">
        <span className="eyebrow">Instructing agent</span>
        <code>{payment.instructingAgentBic || "—"}</code>
      </div>
      <div className="route__wire" aria-hidden="true">
        <span className="route__pulse" />
        <span className="route__hub">SWIFT</span>
      </div>
      <div className="route__end route__end--right">
        <span className="eyebrow">Instructed agent</span>
        <code>{payment.instructedAgentBic || "—"}</code>
      </div>
    </div>
  );
}
