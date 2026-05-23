export function ViewsPanel() {
  return (
    <div className="p-4 overflow-auto devtools-body">
      <div className="space-y-6">
        {/* Reactive Graph */}
        <section>
          <h3 className="devtools-caption uppercase tracking-wide text-[var(--devtools-text-muted)] mb-3">
            Reactive Flow
          </h3>
          <div className="border border-[var(--devtools-border)] rounded-lg p-6 bg-background">
            <svg width="100%" height="350" className="devtools-body">
              {/* app-db node */}
              <g>
                <rect x="20" y="165" width="80" height="40" rx="4" fill="var(--devtools-chrome-bg)" stroke="var(--devtools-border)" strokeWidth="1.5" />
                <text x="60" y="190" textAnchor="middle" fill="var(--devtools-text)" fontSize="12" fontFamily="monospace">
                  app-db
                </text>
              </g>

              {/* "unchanged" label */}
              <g>
                <text x="225" y="25" textAnchor="middle" fill="var(--devtools-unchanged)" fontSize="10">
                  unchanged
                </text>
              </g>

              {/* Level-1 sub (unchanged) - top */}
              <g>
                <line x1="100" y1="175" x2="170" y2="50" stroke="var(--devtools-unchanged)" strokeWidth="1" strokeDasharray="4,2" />
                <rect x="170" y="35" width="110" height="30" rx="4" fill="transparent" stroke="var(--devtools-unchanged)" strokeWidth="1" strokeDasharray="4,2" />
                <text x="225" y="55" textAnchor="middle" fill="var(--devtools-unchanged)" fontSize="11" fontFamily="monospace">
                  ::title-state
                </text>
              </g>

              {/* Level-1 sub (unchanged) - second */}
              <g>
                <line x1="100" y1="180" x2="170" y2="95" stroke="var(--devtools-unchanged)" strokeWidth="1" strokeDasharray="4,2" />
                <rect x="170" y="80" width="100" height="30" rx="4" fill="transparent" stroke="var(--devtools-unchanged)" strokeWidth="1" strokeDasharray="4,2" />
                <text x="220" y="100" textAnchor="middle" fill="var(--devtools-unchanged)" fontSize="11" fontFamily="monospace">
                  ::settings
                </text>
              </g>

              {/* Level-1 sub (changed) */}
              <g>
                <line x1="100" y1="185" x2="170" y2="145" stroke="var(--devtools-changed)" strokeWidth="2" markerEnd="url(#arrowhead-changed)" />
                <rect x="170" y="130" width="100" height="30" rx="4" fill="var(--devtools-active)" fillOpacity="0.1" stroke="var(--devtools-changed)" strokeWidth="2" />
                <text x="220" y="150" textAnchor="middle" fill="var(--devtools-changed)" fontSize="11" fontFamily="monospace" fontWeight="600">
                  ::counter
                </text>
              </g>

              {/* Level-2 sub */}
              <g>
                <line x1="270" y1="145" x2="330" y2="145" stroke="var(--devtools-changed)" strokeWidth="2" markerEnd="url(#arrowhead-changed)" />
                <rect x="330" y="130" width="130" height="30" rx="4" fill="var(--devtools-active)" fillOpacity="0.1" stroke="var(--devtools-changed)" strokeWidth="2" />
                <text x="395" y="150" textAnchor="middle" fill="var(--devtools-changed)" fontSize="11" fontFamily="monospace" fontWeight="600">
                  ::counter-parity
                </text>
              </g>

              {/* View (reactive) */}
              <g>
                <line x1="460" y1="145" x2="520" y2="145" stroke="var(--devtools-changed)" strokeWidth="2" markerEnd="url(#arrowhead-changed)" />
                <rect x="520" y="125" width="140" height="40" rx="4" fill="var(--devtools-success)" fillOpacity="0.1" stroke="var(--devtools-success)" strokeWidth="2" />
                <text x="590" y="143" textAnchor="middle" fill="var(--devtools-success)" fontSize="11" fontFamily="monospace" fontWeight="600">
                  counter-view
                </text>
                <text x="590" y="155" textAnchor="middle" fill="var(--devtools-text-muted)" fontSize="9">
                  (rerendered)
                </text>
              </g>

              {/* Shared sub example */}
              <g>
                <line x1="100" y1="195" x2="170" y2="240" stroke="var(--devtools-changed)" strokeWidth="2" markerEnd="url(#arrowhead-changed)" />
                <rect x="170" y="225" width="100" height="30" rx="4" fill="var(--devtools-active)" fillOpacity="0.1" stroke="var(--devtools-changed)" strokeWidth="2" />
                <text x="220" y="245" textAnchor="middle" fill="var(--devtools-changed)" fontSize="11" fontFamily="monospace" fontWeight="600">
                  ::session
                </text>
              </g>

              {/* Shared sub to multiple views */}
              <g>
                <line x1="270" y1="235" x2="330" y2="220" stroke="var(--devtools-changed)" strokeWidth="2" markerEnd="url(#arrowhead-changed)" />
                <line x1="270" y1="245" x2="330" y2="260" stroke="var(--devtools-changed)" strokeWidth="2" markerEnd="url(#arrowhead-changed)" />

                <rect x="330" y="200" width="120" height="40" rx="4" fill="var(--devtools-success)" fillOpacity="0.1" stroke="var(--devtools-success)" strokeWidth="2" />
                <text x="390" y="218" textAnchor="middle" fill="var(--devtools-success)" fontSize="11" fontFamily="monospace" fontWeight="600">
                  header-view
                </text>
                <text x="390" y="230" textAnchor="middle" fill="var(--devtools-text-muted)" fontSize="9">
                  (rerendered)
                </text>

                <rect x="330" y="250" width="120" height="40" rx="4" fill="var(--devtools-success)" fillOpacity="0.1" stroke="var(--devtools-success)" strokeWidth="2" />
                <text x="390" y="268" textAnchor="middle" fill="var(--devtools-success)" fontSize="11" fontFamily="monospace" fontWeight="600">
                  sidebar-view
                </text>
                <text x="390" y="280" textAnchor="middle" fill="var(--devtools-text-muted)" fontSize="9">
                  (rerendered)
                </text>
              </g>

              {/* Arrow markers */}
              <defs>
                <marker id="arrowhead-changed" markerWidth="10" markerHeight="7" refX="9" refY="3.5" orient="auto">
                  <polygon points="0 0, 10 3.5, 0 7" fill="var(--devtools-changed)" />
                </marker>
              </defs>
            </svg>
          </div>
        </section>

        {/* Unmounted Views */}
        <section>
          <h3 className="devtools-caption uppercase tracking-wide text-[var(--devtools-text-muted)] mb-2">
            Unmounted Views
          </h3>
          <div className="border border-[var(--devtools-border)] rounded-lg bg-background">
            <div className="divide-y divide-[var(--devtools-border)]">
              <div className="px-4 py-2.5 flex items-center gap-3 devtools-mono">
                <span className="w-4 h-4 rounded bg-[var(--devtools-error)] opacity-20"></span>
                <span className="flex-1 text-[var(--devtools-text)]">modal-view</span>
                <span className="devtools-caption text-[var(--devtools-text-muted)]">unmounted</span>
              </div>
              <div className="px-4 py-2.5 flex items-center gap-3 devtools-mono">
                <span className="w-4 h-4 rounded bg-[var(--devtools-error)] opacity-20"></span>
                <span className="flex-1 text-[var(--devtools-text)]">tooltip-view</span>
                <span className="devtools-caption text-[var(--devtools-text-muted)]">unmounted</span>
              </div>
            </div>
          </div>
        </section>

        {/* Destroyed Subscriptions */}
        <section>
          <h3 className="devtools-caption uppercase tracking-wide text-[var(--devtools-text-muted)] mb-2">
            Destroyed Subscriptions
          </h3>
          <div className="border border-[var(--devtools-border)] rounded-lg bg-background">
            <div className="divide-y divide-[var(--devtools-border)]">
              <div className="px-4 py-2.5 flex items-center gap-3 devtools-mono">
                <span className="w-4 h-4 rounded bg-[var(--devtools-unchanged)] opacity-20"></span>
                <span className="flex-1 text-[var(--devtools-text)]">::modal-state</span>
                <span className="devtools-caption text-[var(--devtools-text-muted)]">no readers remaining</span>
              </div>
              <div className="px-4 py-2.5 flex items-center gap-3 devtools-mono">
                <span className="w-4 h-4 rounded bg-[var(--devtools-unchanged)] opacity-20"></span>
                <span className="flex-1 text-[var(--devtools-text)]">::tooltip-position</span>
                <span className="devtools-caption text-[var(--devtools-text-muted)]">no readers remaining</span>
              </div>
            </div>
          </div>
          <p className="mt-2 devtools-caption text-[var(--devtools-text-muted)]">
            Subscriptions cleaned up when their last reader unmounted
          </p>
        </section>

        {/* Legend */}
        <div className="devtools-caption text-[var(--devtools-text-muted)] space-y-1">
          <p>Views (right) are the focus — each: re-rendered + why (reactive vs parent re-render)</p>
          <p className="flex items-center gap-4 flex-wrap">
            <span className="flex items-center gap-2">
              <span className="inline-block w-3 h-3 rounded" style={{ backgroundColor: 'var(--devtools-changed)' }} />
              changed (propagates downstream)
            </span>
            <span className="flex items-center gap-2">
              <span className="inline-block w-3 h-3 rounded border border-dashed" style={{ borderColor: 'var(--devtools-unchanged)' }} />
              no change (short-circuits)
            </span>
            <span className="flex items-center gap-2">
              <span className="inline-block w-3 h-3 rounded opacity-20" style={{ backgroundColor: 'var(--devtools-error)' }} />
              unmounted / destroyed
            </span>
          </p>
        </div>
      </div>
    </div>
  );
}
