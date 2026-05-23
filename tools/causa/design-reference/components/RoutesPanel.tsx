import { ChevronRight } from "lucide-react";
import { useState } from "react";

export function RoutesPanel() {
  const [expanded, setExpanded] = useState<Record<string, boolean>>({
    users: true,
  });

  const routes = [
    { id: ":home", path: "/", level: 0, current: false },
    { id: ":dashboard", path: "/dashboard", level: 0, current: false },
    { id: ":users", path: "/users", level: 0, current: false, children: true },
    { id: ":user/profile", path: "/users/:id", level: 1, current: true },
    { id: ":settings", path: "/settings", level: 0, current: false },
  ];

  return (
    <div className="p-4 overflow-auto devtools-body space-y-4">
      {/* Current route */}
      <section>
        <h3 className="devtools-caption uppercase tracking-wide text-[var(--devtools-text-muted)] mb-2">
          CURRENT ROUTE
        </h3>
        <div className="flex items-center gap-3 devtools-mono text-[var(--devtools-text)]">
          <span className="text-[var(--devtools-active)] font-semibold">:user/profile</span>
          <span className="text-[var(--devtools-text-muted)]">params</span>
          <span>{`{:id 42}`}</span>
          <span className="text-[var(--devtools-text-muted)]">/users/42</span>
        </div>
      </section>

      <div className="border-t border-[var(--devtools-border)]" />

      {/* Navigation this epoch */}
      <section>
        <h3 className="devtools-caption uppercase tracking-wide text-[var(--devtools-text-muted)] mb-2">
          NAVIGATION THIS EPOCH
        </h3>
        <div className="flex items-center gap-2 devtools-mono text-[var(--devtools-text)]">
          <span className="text-[var(--devtools-text-muted)]">:dashboard</span>
          <span className="text-[var(--devtools-text-muted)]">──►</span>
          <span className="text-[var(--devtools-active)]">:user/profile</span>
          <span className="text-[var(--devtools-text-muted)] ml-4">params</span>
          <span>{`{:id 42}`}</span>
          <span className="text-[var(--devtools-text-muted)] ml-4">outcome:</span>
          <span className="text-[var(--devtools-success)]">transitioned</span>
        </div>
      </section>

      <div className="border-t border-[var(--devtools-border)]" />

      {/* Route table */}
      <section>
        <h3 className="devtools-caption uppercase tracking-wide text-[var(--devtools-text-muted)] mb-2">
          ROUTE TABLE
        </h3>
        <div className="space-y-1 devtools-mono">
          {routes.map((route, idx) => (
            <div
              key={idx}
              className={`flex items-center gap-3 px-2 py-1 rounded ${
                route.current
                  ? "bg-[var(--devtools-active)] bg-opacity-10 text-[var(--devtools-active)] font-semibold"
                  : "text-[var(--devtools-text)] hover:bg-[var(--devtools-hover)]"
              }`}
              style={{ paddingLeft: `${route.level * 2 + 0.5}rem` }}
            >
              {route.children && (
                <button
                  onClick={() =>
                    setExpanded((prev) => ({ ...prev, [route.id]: !prev[route.id] }))
                  }
                  className="flex-shrink-0"
                >
                  <ChevronRight
                    className={`w-3 h-3 transition-transform ${expanded[route.id] ? "rotate-90" : ""}`}
                  />
                </button>
              )}
              {!route.children && <span className="w-3" />}
              <span className="w-32">{route.id}</span>
              <span className="text-[var(--devtools-text-muted)]">{route.path}</span>
              {route.current && (
                <span className="ml-auto text-[var(--devtools-active)] devtools-caption">◀ current</span>
              )}
            </div>
          ))}
        </div>
      </section>
    </div>
  );
}
