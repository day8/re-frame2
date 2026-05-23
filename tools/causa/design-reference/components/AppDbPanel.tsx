export function AppDbPanel() {
  // Mock data - in real implementation, determine what sections exist
  const hasMachines = true;
  const hasRoute = true;
  const hasSystemIds = true;

  return (
    <div className="p-4 space-y-4 overflow-auto devtools-body">
      {/* APP STATE - always shown */}
      <section>
        <h3 className="devtools-caption uppercase tracking-wide text-[var(--devtools-text-muted)] mb-2">
          APP STATE
        </h3>
        <div className="devtools-mono bg-muted p-3 rounded text-[var(--devtools-text)]">
          {`{:counter 2, :user {:name "Alice" :role :admin}}`}
        </div>
      </section>

      <div className="border-t border-[var(--devtools-border)]" />

      {/* MACHINES - only show if there are machines */}
      {hasMachines && (
        <>
          <section>
            <h3 className="devtools-caption uppercase tracking-wide text-[var(--devtools-text-muted)] mb-2">
              MACHINE :title/flow
            </h3>
            <div className="devtools-mono bg-muted p-3 rounded text-[var(--devtools-text)]">
              {`{:state :loaded, :context {:data [...]}}`}
            </div>
          </section>

          <div className="border-t border-[var(--devtools-border)]" />

          <section>
            <h3 className="devtools-caption uppercase tracking-wide text-[var(--devtools-text-muted)] mb-2">
              MACHINE :other/flow
            </h3>
            <div className="devtools-mono bg-muted p-3 rounded text-[var(--devtools-text)]">
              {`{:state :idle}`}
            </div>
          </section>

          <div className="border-t border-[var(--devtools-border)]" />
        </>
      )}

      {/* ROUTE - only show if there's a route */}
      {hasRoute && (
        <>
          <section>
            <h3 className="devtools-caption uppercase tracking-wide text-[var(--devtools-text-muted)] mb-2">
              ROUTE
            </h3>
            <div className="devtools-mono bg-muted p-3 rounded text-[var(--devtools-text)]">
              {`{:id :home, :params {}}`}
            </div>
          </section>

          <div className="border-t border-[var(--devtools-border)]" />
        </>
      )}

      {/* SYSTEM-IDS - only show if exists */}
      {hasSystemIds && (
        <section>
          <h3 className="devtools-caption uppercase tracking-wide text-[var(--devtools-text-muted)] mb-2">
            SYSTEM-IDS
          </h3>
          <div className="devtools-mono bg-muted p-3 rounded text-[var(--devtools-text)]">
            {`#{:app :causa}`}
          </div>
        </section>
      )}
    </div>
  );
}
