import { formatDuration, formatTime, journeyStats, Timeline, trackLayout } from "../gpi";

/**
 * Horizontal view of the journey. Stages with tracker timestamps are spaced by
 * real elapsed time, so long waits show up as long stretches of wire.
 */
export function JourneyTrack({
  timeline,
  focusKey,
  onFocus,
}: {
  timeline: Timeline;
  focusKey?: string | null;
  onFocus?: (key: string) => void;
}) {
  const layout = trackLayout(timeline);
  return (
    <div
      className={`track track--${timeline.outcome}`}
      role="group"
      aria-label="Journey track"
      data-testid="journey-track"
      data-time-scaled={layout.timeScaled ? "true" : "false"}
    >
      <div className="track__wire" aria-hidden="true">
        <div className="track__lit" style={{ width: `${layout.packet}%` }} />
        <span className="track__packet" data-testid="track-packet" style={{ left: `${layout.packet}%` }} />
      </div>
      {layout.points.map((p) => (
        <button
          key={p.key}
          type="button"
          className={`track__pt track__pt--${p.state}${focusKey === p.key ? " is-focus" : ""}`}
          style={{ left: `${p.pos}%` }}
          data-testid={`track-${p.key}`}
          data-state={p.state}
          aria-pressed={focusKey === p.key}
          aria-label={`${p.label}: ${p.state}`}
          title={p.occurredAt ? formatTime(p.occurredAt) : p.label}
          onClick={() => onFocus?.(p.key)}
        >
          <span className="track__dot" aria-hidden="true" />
          <span className="track__label">{p.label}</span>
        </button>
      ))}
      <span className="track__scale">{layout.timeScaled ? "spaced by tracker time" : "evenly spaced"}</span>
    </div>
  );
}

export function JourneyStatsStrip({ timeline }: { timeline: Timeline }) {
  const s = journeyStats(timeline);
  return (
    <dl className="jstats" data-testid="journey-stats">
      <div>
        <dt>Current stage</dt>
        <dd data-testid="stat-current">{s.current}</dd>
      </div>
      <div>
        <dt>End-to-end</dt>
        <dd data-testid="stat-total">{s.totalMs != null ? formatDuration(s.totalMs) : "—"}</dd>
      </div>
      <div>
        <dt>Longest leg</dt>
        <dd data-testid="stat-slowest">{s.slowest ? `${s.slowest.label} · ${formatDuration(s.slowest.ms)}` : "—"}</dd>
      </div>
      <div>
        <dt>Tracker events</dt>
        <dd data-testid="stat-events">{s.trackerEvents}</dd>
      </div>
      <div>
        <dt>Last update</dt>
        <dd data-testid="stat-last">{s.lastUpdate ? formatTime(s.lastUpdate) : "—"}</dd>
      </div>
    </dl>
  );
}
