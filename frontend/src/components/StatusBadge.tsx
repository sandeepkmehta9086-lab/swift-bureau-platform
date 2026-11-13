import { humanize, stageDots, statusProgress, statusTone } from "../gpi";

export function StatusBadge({ status }: { status?: string | null }) {
  if (!status) return <span className="badge badge--muted">—</span>;
  return (
    <span className={`badge badge--${statusTone(status)}`} title={status} data-status={status}>
      <span className="badge__dot" aria-hidden="true" />
      {humanize(status)}
    </span>
  );
}

export function LifecycleBar({ status }: { status: string }) {
  const pct = statusProgress(status);
  return (
    <div className={`meter meter--sm meter--${statusTone(status)}`} role="progressbar" aria-valuenow={pct} aria-valuemin={0} aria-valuemax={100} aria-label={`Lifecycle ${pct}%`}>
      <div className="meter__fill" style={{ width: `${pct}%` }} />
    </div>
  );
}

/** One dot per journey stage, for list rows. Same progress semantics as LifecycleBar. */
export function JourneyDots({ status }: { status: string }) {
  const pct = statusProgress(status);
  const dots = stageDots(status);
  return (
    <div
      className={`jdots jdots--${statusTone(status)}`}
      role="progressbar"
      aria-valuenow={pct}
      aria-valuemin={0}
      aria-valuemax={100}
      aria-label={`Lifecycle ${pct}%`}
    >
      {dots.map((state, i) => (
        <span key={i} className={`jdot jdot--${state}`} data-state={state} />
      ))}
    </div>
  );
}
