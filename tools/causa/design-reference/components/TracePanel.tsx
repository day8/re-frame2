import { ChevronRight } from "lucide-react";
import { useState } from "react";

interface TraceEvent {
  time: string;
  type: "dispatch" | "db" | "fx" | "reactive" | "machine";
  description: string;
  duration?: string;
  children?: TraceEvent[];
}

const traceEvents: TraceEvent[] = [
  {
    time: "t+0.0ms",
    type: "dispatch",
    description: "dispatched [:counter-inc]",
    duration: "0.4 ms",
  },
  {
    time: "t+0.1ms",
    type: "db",
    description: "db changed [:counter] 1 → 2",
  },
  {
    time: "t+0.2ms",
    type: "fx",
    description: "fx :dispatch → [:title/flow [:rf/init]]",
  },
  {
    time: "t+0.3ms",
    type: "reactive",
    description: "▸ reactive aftermath (2 subs, 1 render)",
  },
  {
    time: "t+0.5ms",
    type: "machine",
    description: "machine :title/flow idle → loading",
    children: [
      {
        time: "t+0.6ms",
        type: "dispatch",
        description: "└─ dispatched [:title/loaded]",
      },
    ],
  },
];

const typeColors: Record<string, string> = {
  dispatch: "var(--devtools-active)",
  db: "var(--devtools-changed)",
  fx: "var(--devtools-warning)",
  reactive: "var(--devtools-text-muted)",
  machine: "var(--chart-1)",
};

export function TracePanel() {
  const [expanded, setExpanded] = useState<Record<number, boolean>>({});

  return (
    <div className="p-4 overflow-auto devtools-body">
      <div className="space-y-0.5">
        {traceEvents.map((event, idx) => (
          <div key={idx}>
            <div
              className="flex items-center gap-3 px-3 py-1.5 rounded hover:bg-[var(--devtools-hover)] cursor-pointer devtools-mono"
              style={{ borderLeft: `3px solid ${typeColors[event.type]}` }}
              onClick={() => event.children && setExpanded((prev) => ({ ...prev, [idx]: !prev[idx] }))}
            >
              <span className="devtools-caption text-[var(--devtools-text-muted)] w-16">{event.time}</span>
              <span className="flex-1 text-[var(--devtools-text)]">{event.description}</span>
              {event.duration && (
                <span className="devtools-caption text-[var(--devtools-text-muted)]">{event.duration}</span>
              )}
            </div>

            {event.children && expanded[idx] && (
              <div className="ml-6 mt-0.5 space-y-0.5">
                {event.children.map((child, childIdx) => (
                  <div
                    key={childIdx}
                    className="flex items-center gap-3 px-3 py-1.5 rounded hover:bg-[var(--devtools-hover)] devtools-mono"
                    style={{ borderLeft: `3px solid ${typeColors[child.type]}` }}
                  >
                    <span className="devtools-caption text-[var(--devtools-text-muted)] w-16">{child.time}</span>
                    <span className="flex-1 text-[var(--devtools-text)]">{child.description}</span>
                  </div>
                ))}
              </div>
            )}
          </div>
        ))}
      </div>

      <div className="mt-4 pt-4 border-t border-[var(--devtools-border)] devtools-caption text-[var(--devtools-text-muted)]">
        colour-banded by op-family · click a row → expand raw :operation + tags
      </div>
    </div>
  );
}
