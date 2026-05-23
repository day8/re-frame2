import { ExternalLink } from "lucide-react";

export function EventPanel() {
  // Mock data - in real implementation, determine which sections to show based on actual trace data
  const hasBeforeInterceptors = true;
  const hasAfterInterceptors = true;
  const hasFlows = true;
  const handlerType = "db"; // "db" or "fx"

  // Calculate step numbers dynamically based on what's present
  let stepNumber = 0;
  const getStepNumber = () => ++stepNumber;

  return (
    <div className="p-4 overflow-auto devtools-body text-[var(--devtools-text)]">
      <div className="relative ml-12">
        {/* Vertical pipeline line */}
        <div className="absolute w-0.5 bg-[var(--devtools-border)]" style={{ left: '-2rem', top: '0.75rem', bottom: 0 }}></div>

        <div className="space-y-6">
          {/* 1. Dispatch */}
          <section className="relative">
            <div className="absolute w-6 h-6 rounded-full bg-[var(--devtools-text-muted)] text-white flex items-center justify-center devtools-caption font-semibold z-10" style={{ left: '-2.75rem', top: 0 }}>
              {getStepNumber()}
            </div>
            <h3 className="devtools-caption uppercase tracking-wide text-[var(--devtools-text-muted)] mb-2">
              DISPATCH
            </h3>
            <div className="devtools-mono bg-muted p-3 rounded">
              [:counter-inc]
            </div>
            <div className="devtools-caption mt-1 text-[var(--devtools-text-muted)] flex items-center gap-2">
              FROM:
              <a href="#" className="text-[var(--devtools-active)] hover:underline flex items-center gap-1">
                view
                <ExternalLink className="w-3 h-3" />
              </a>
            </div>
          </section>

          {/* 2. Coeffects (optional) */}
          {hasBeforeInterceptors && (
            <section className="relative">
              <div className="absolute w-6 h-6 rounded-full bg-[var(--devtools-text-muted)] text-white flex items-center justify-center devtools-caption font-semibold z-10" style={{ left: '-2.75rem', top: 0 }}>
                {getStepNumber()}
              </div>
              <h3 className="devtools-caption uppercase tracking-wide text-[var(--devtools-text-muted)] mb-2">
                COEFFECTS
              </h3>
              <div className="space-y-3">
                {/* Coeffect 1 */}
                <div>
                  <div className="flex items-center gap-1 mb-1">
                    <a href="#" className="devtools-mono text-[var(--devtools-active)] hover:underline flex items-center gap-1">
                      :now
                      <ExternalLink className="w-3 h-3" />
                    </a>
                  </div>
                  <div className="devtools-mono">
                    <div className="flex items-center gap-2">
                      <span className="text-[var(--devtools-success)]">+</span>
                      <span className="text-[var(--devtools-text-muted)]">[:now]</span>
                      <span className="text-[var(--devtools-success)]">#inst "2026-05-23T12:30:05.123Z"</span>
                    </div>
                  </div>
                </div>

                {/* Coeffect 2 */}
                <div>
                  <div className="flex items-center gap-1 mb-1">
                    <a href="#" className="devtools-mono text-[var(--devtools-active)] hover:underline flex items-center gap-1">
                      :session
                      <ExternalLink className="w-3 h-3" />
                    </a>
                  </div>
                  <div className="devtools-mono">
                    <div className="flex items-center gap-2">
                      <span className="text-[var(--devtools-success)]">+</span>
                      <span className="text-[var(--devtools-text-muted)]">[:session]</span>
                      <span className="text-[var(--devtools-success)]">{`{:user-id 42, :token "..."}`}</span>
                    </div>
                  </div>
                </div>
              </div>
            </section>
          )}

          {/* 3. Event Handler */}
          <section className="relative">
            <div className="absolute w-6 h-6 rounded-full bg-[var(--devtools-text-muted)] text-white flex items-center justify-center devtools-caption font-semibold z-10" style={{ left: '-2.75rem', top: 0 }}>
              {getStepNumber()}
            </div>
            <a href="#" className="devtools-caption uppercase tracking-wide text-[var(--devtools-active)] hover:underline mb-2 inline-flex items-center gap-1">
              EVENT HANDLER
              <ExternalLink className="w-3 h-3" />
            </a>
            <div className="devtools-mono bg-muted p-3 rounded overflow-x-auto">
              <pre className="text-[var(--devtools-body)]">
                {handlerType === "db" ? (
                  <>
                    <span className="syntax-keyword">(rf/reg-event-db</span>{" "}
                    <span className="syntax-keyword">:counter-inc</span>
                    {"\n"}
                    {"  "}[<span className="syntax-keyword">:rf/db</span>]
                    {"\n"}
                    {"  "}(<span className="syntax-keyword">fn</span> [db _]
                    {"\n"}
                    {"    "}(<span className="syntax-keyword">update</span> db{" "}
                    <span className="syntax-keyword">:counter</span> inc)))
                  </>
                ) : (
                  <>
                    <span className="syntax-keyword">(rf/reg-event-fx</span>{" "}
                    <span className="syntax-keyword">:fetch-data</span>
                    {"\n"}
                    {"  "}(<span className="syntax-keyword">fn</span>{" "}
                    [{`{:keys [db]}`} _]
                    {"\n"}
                    {"    "}{`{:db (assoc db :loading? true)`}
                    {"\n"}
                    {"     "}
                    <span className="syntax-keyword">:http-xhrio</span>{" "}
                    {`{:method :get ...}`})
                  </>
                )}
              </pre>
            </div>
          </section>

          {/* 4. DB Changes */}
          <section className="relative">
            <div className="absolute w-6 h-6 rounded-full bg-[var(--devtools-text-muted)] text-white flex items-center justify-center devtools-caption font-semibold z-10" style={{ left: '-2.75rem', top: 0 }}>
              {getStepNumber()}
            </div>
            <h3 className="devtools-caption uppercase tracking-wide text-[var(--devtools-text-muted)] mb-2">
              DB CHANGES
            </h3>
            <div className="devtools-mono space-y-1">
              <div className="flex items-center gap-2">
                <span className="text-[var(--devtools-changed)]">~</span>
                <span className="text-[var(--devtools-text-muted)]">[:counter]</span>
                <span className="text-[var(--devtools-error)]">1</span>
                <span className="text-[var(--devtools-text-muted)]">→</span>
                <span className="text-[var(--devtools-success)]">2</span>
              </div>
              <div className="flex items-center gap-2">
                <span className="text-[var(--devtools-success)]">+</span>
                <span className="text-[var(--devtools-text-muted)]">[:last-updated]</span>
                <span className="text-[var(--devtools-success)]">#inst "2026-05-23T12:30:05"</span>
              </div>
            </div>
          </section>

          {/* 5. After Interceptors (optional) */}
          {hasAfterInterceptors && (
            <section className="relative">
              <div className="absolute w-6 h-6 rounded-full bg-[var(--devtools-text-muted)] text-white flex items-center justify-center devtools-caption font-semibold z-10" style={{ left: '-2.75rem', top: 0 }}>
                {getStepNumber()}
              </div>
              <h3 className="devtools-caption uppercase tracking-wide text-[var(--devtools-text-muted)] mb-2">
                AFTER INTERCEPTORS
              </h3>
              <div className="space-y-3">
                {/* Interceptor 1 */}
                <div>
                  <div className="flex items-center gap-1 mb-1">
                    <a href="#" className="devtools-mono text-[var(--devtools-active)] hover:underline flex items-center gap-1">
                      :persist-db
                      <ExternalLink className="w-3 h-3" />
                    </a>
                  </div>
                  <div className="devtools-mono">
                    <div className="flex items-center gap-2">
                      <span className="text-[var(--devtools-success)]">+</span>
                      <span className="text-[var(--devtools-text-muted)]">[:fx :local-storage]</span>
                      <span className="text-[var(--devtools-success)]">{`{:key "app-state" :value {...}}`}</span>
                    </div>
                  </div>
                </div>

                {/* Interceptor 2 */}
                <div>
                  <div className="flex items-center gap-1 mb-1">
                    <a href="#" className="devtools-mono text-[var(--devtools-active)] hover:underline flex items-center gap-1">
                      :analytics
                      <ExternalLink className="w-3 h-3" />
                    </a>
                  </div>
                  <div className="devtools-mono">
                    <div className="flex items-center gap-2">
                      <span className="text-[var(--devtools-success)]">+</span>
                      <span className="text-[var(--devtools-text-muted)]">[:fx :track]</span>
                      <span className="text-[var(--devtools-success)]">{`{:event "counter-inc"}`}</span>
                    </div>
                  </div>
                </div>
              </div>
            </section>
          )}

          {/* 6. Flows (optional) */}
          {hasFlows && (
            <section className="relative">
              <div className="absolute w-6 h-6 rounded-full bg-[var(--devtools-text-muted)] text-white flex items-center justify-center devtools-caption font-semibold z-10" style={{ left: '-2.75rem', top: 0 }}>
                {getStepNumber()}
              </div>
              <h3 className="devtools-caption uppercase tracking-wide text-[var(--devtools-text-muted)] mb-2">
                FLOWS
              </h3>
              <div className="space-y-2">
                <div className="devtools-mono">
                  <div className="flex items-center gap-2">
                    <a href="#" className="text-[var(--devtools-active)] hover:underline flex items-center gap-1">
                      :totals-flow
                      <ExternalLink className="w-3 h-3" />
                    </a>
                    <span className="text-[var(--devtools-text-muted)]">→</span>
                    <span className="text-[var(--devtools-text)]">[:totals] recomputed</span>
                  </div>
                  <div className="ml-6 mt-1 text-[var(--devtools-text-muted)]">
                    <div className="flex items-center gap-2">
                      <span className="text-[var(--devtools-success)]">+</span>
                      <span>[:totals :sum]</span>
                      <span className="text-[var(--devtools-success)]">42</span>
                    </div>
                  </div>
                </div>
              </div>
            </section>
          )}

          {/* 7. FX */}
          <section className="relative">
            <div className="absolute w-6 h-6 rounded-full bg-[var(--devtools-text-muted)] text-white flex items-center justify-center devtools-caption font-semibold z-10" style={{ left: '-2.75rem', top: 0 }}>
              {getStepNumber()}
            </div>
            <h3 className="devtools-caption uppercase tracking-wide text-[var(--devtools-text-muted)] mb-2">
              FX
            </h3>
            <div className="devtools-mono space-y-1">
              <div className="flex items-start gap-2">
                <span className="text-[var(--devtools-text-muted)]">:dispatch</span>
                <span className="text-[var(--devtools-text)]">→ [:title/flow [:rf/init]]</span>
              </div>
              <div className="flex items-start gap-2">
                <span className="text-[var(--devtools-text-muted)]">:http-xhrio</span>
                <span className="text-[var(--devtools-text)]">
                  → {`{:method :get, :uri "/api/data"}`}
                </span>
              </div>
            </div>
          </section>
        </div>
      </div>
    </div>
  );
}
