import { ChevronLeft, ChevronRight } from "lucide-react";

export function MachinePanel() {
  return (
    <div className="p-4 overflow-auto devtools-body h-full flex flex-col">
      <div className="flex items-center justify-between mb-4">
        <h3 className="devtools-body text-[var(--devtools-text)] devtools-mono">
          machine :title/flow
        </h3>
        <div className="flex items-center gap-2">
          <button className="devtools-caption px-2 py-1 rounded hover:bg-[var(--devtools-hover)] text-[var(--devtools-text-muted)] flex items-center gap-1">
            <ChevronLeft className="w-3 h-3" />
            prev
          </button>
          <button className="devtools-caption px-2 py-1 rounded hover:bg-[var(--devtools-hover)] text-[var(--devtools-text-muted)] flex items-center gap-1">
            next
            <ChevronRight className="w-3 h-3" />
          </button>
        </div>
      </div>

      {/* State machine topology */}
      <div className="flex-1 border border-[var(--devtools-border)] rounded-lg p-6 bg-background">
        <svg width="100%" height="100%" viewBox="0 0 700 350" className="devtools-body">
          {/* Compound state container */}
          <g>
            <rect x="50" y="50" width="500" height="250" rx="8" fill="transparent" stroke="var(--devtools-border)" strokeWidth="2" strokeDasharray="5,5" />
            <text x="60" y="35" fill="var(--devtools-text-muted)" fontSize="11">
              active (compound)
            </text>
          </g>

          {/* States */}
          {/* idle (FROM) */}
          <g>
            <circle cx="120" cy="175" r="35" fill="transparent" stroke="var(--devtools-text-muted)" strokeWidth="2" strokeDasharray="4,2" />
            <text x="120" y="180" textAnchor="middle" fill="var(--devtools-text-muted)" fontSize="12" fontFamily="monospace">
              idle
            </text>
            <text x="120" y="195" textAnchor="middle" fill="var(--devtools-text-muted)" fontSize="9">
              FROM
            </text>
          </g>

          {/* loading (TO) - double circle for active */}
          <g>
            <circle cx="280" cy="175" r="35" fill="var(--devtools-active)" fillOpacity="0.1" stroke="var(--devtools-active)" strokeWidth="3" />
            <circle cx="280" cy="175" r="30" fill="transparent" stroke="var(--devtools-active)" strokeWidth="2" />
            <text x="280" y="178" textAnchor="middle" fill="var(--devtools-active)" fontSize="12" fontFamily="monospace" fontWeight="600">
              loading
            </text>
            <text x="280" y="193" textAnchor="middle" fill="var(--devtools-active)" fontSize="9" fontWeight="600">
              TO
            </text>
          </g>

          {/* loaded */}
          <g>
            <circle cx="440" cy="175" r="35" fill="transparent" stroke="var(--devtools-border)" strokeWidth="2" />
            <text x="440" y="180" textAnchor="middle" fill="var(--devtools-text)" fontSize="12" fontFamily="monospace">
              loaded
            </text>
          </g>

          {/* error state */}
          <g>
            <circle cx="600" cy="175" r="35" fill="transparent" stroke="var(--devtools-error)" strokeWidth="2" />
            <text x="600" y="180" textAnchor="middle" fill="var(--devtools-error)" fontSize="12" fontFamily="monospace">
              error
            </text>
          </g>

          {/* Transitions */}
          {/* idle -> loading (highlighted) */}
          <g>
            <line x1="155" y1="175" x2="245" y2="175" stroke="var(--devtools-active)" strokeWidth="3" markerEnd="url(#arrowhead-active)" />
            <text x="200" y="165" textAnchor="middle" fill="var(--devtools-text)" fontSize="10">
              [:rf/init]
            </text>
            <text x="200" y="195" textAnchor="middle" fill="var(--devtools-success)" fontSize="9">
              guard: token? [pass]
            </text>
            <text x="200" y="207" textAnchor="middle" fill="var(--devtools-text-muted)" fontSize="9">
              do: fetch!
            </text>
          </g>

          {/* loading -> loaded */}
          <g>
            <line x1="315" y1="175" x2="405" y2="175" stroke="var(--devtools-border)" strokeWidth="2" markerEnd="url(#arrowhead)" />
          </g>

          {/* loading -> error */}
          <g>
            <path d="M 310 160 Q 455 100 575 160" fill="transparent" stroke="var(--devtools-error)" strokeWidth="2" markerEnd="url(#arrowhead-error)" />
          </g>

          {/* Arrow markers */}
          <defs>
            <marker id="arrowhead-active" markerWidth="10" markerHeight="7" refX="9" refY="3.5" orient="auto">
              <polygon points="0 0, 10 3.5, 0 7" fill="var(--devtools-active)" />
            </marker>
            <marker id="arrowhead" markerWidth="10" markerHeight="7" refX="9" refY="3.5" orient="auto">
              <polygon points="0 0, 10 3.5, 0 7" fill="var(--devtools-border)" />
            </marker>
            <marker id="arrowhead-error" markerWidth="10" markerHeight="7" refX="9" refY="3.5" orient="auto">
              <polygon points="0 0, 10 3.5, 0 7" fill="var(--devtools-error)" />
            </marker>
          </defs>
        </svg>
      </div>

      {/* Details below */}
      <div className="mt-3 devtools-caption text-[var(--devtools-text-muted)] flex gap-4">
        <span>guards: <span className="text-[var(--devtools-success)]">token? [pass]</span></span>
        <span>actions: <span className="text-[var(--devtools-text)]">[fetch!]</span></span>
        <span>after: <span className="text-[var(--devtools-text)]">◴ 5s → :timeout</span></span>
      </div>
    </div>
  );
}
