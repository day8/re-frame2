(ns re-frame.adapter.resource-lease
  "Shared React-hook resource-lease helper for the React-shaped adapters
  (UIx / Helix). Delivers the adapter-ergonomics helper EP-0020 §Open Issue #1
  promised (rf2-cxozh4): a view-layer wrapper that mints a resource
  liveness LEASE on mount and RELEASES it on unmount, so a view can
  declaratively own a polled / cached resource for its lifetime without
  hand-wiring a Form-3 / `useEffect` per app.

  ## Why here (core, CLJS-only)

  The React-hook body is substrate-agnostic — it uses `React/useRef` /
  `React/useEffect` directly (exactly like the spine's unmount sentinel),
  not any UIx- or Helix-specific hook — so the UIx and Helix
  `use-resource-lease` surfaces re-export this ONE implementation with zero
  drift (mirroring how `use-subscribe` lives once in the spine). It lives in
  the core artefact because core already `:require`s React (via
  `re-frame.views` / `re-frame.adapter.context`), so this file adds no new
  transitive dep, and both adapters depend on core.

  ## Bundle isolation

  This ns does NOT `:require` the resources artefact — resource ownership
  is expressed purely as DATA (the two public resource events dispatched by
  keyword id, `:rf.resource/ensure` and `:rf.resource/release-owner`). An
  app that omits `day8/re-frame2-resources` simply never registers those
  events, so the helper is inert there — matching the EP framing that this
  is an adapter concern, not a runtime-contract concern.

  ## The lease contract (Spec 016 §The scoped-cache lease lifecycle)

  ACQUIRE — `[:rf.resource/ensure {:resource … :scope … :params …
             :owner [:lease …] :cause …}]` attaches the lease owner to the
             resolved scoped entry (+ owner-index), keeping it alive and, if
             the resource declares `:poll-interval-ms`, polling for the
             view's lifetime.
  RELEASE — `[:rf.resource/release-owner {:owner [:lease …]}]` drops that
             lease. When it was the last owner the entry becomes GC-eligible
             and (per EP-0020) polling stops immediately.

  ## Frame resolution + carry

  The frame is resolved at RENDER time through the same carried-invariant
  chain `use-subscribe` uses (`frame/require-current-frame!`: dynamic-var →
  React-context → loud `:rf.error/no-frame-context`), or pinned explicitly
  via the `:frame` opt. Because the `useEffect` acquire/release runs AFTER
  the render scope has unwound (an async boundary), the resolved frame is
  CLOSED OVER and both dispatches carry it as an explicit `{:frame …}` opt —
  the standard `capture-frame` carry (Spec 002 §capture-frame).

  ## Idempotency (SSR / React StrictMode double-mount)

  - SSR / JVM has no `useEffect` execution (effects never run on the
    server), so the acquire/release is a natural no-op under
    `render-to-string` — the lease is a client-lifetime concern.
  - React 18 StrictMode dev double-invokes an effect (mount → cleanup →
    mount). The lease token is minted ONCE per instance (via `useRef`,
    stable across the double-invoke), and ensure is idempotent for a
    re-attached owner (a second ensure of an already-owned entry re-attaches
    the SAME lease — no duplicate durable owner; Spec 016 §Active owners).
    release-owner drops that one lease. So the mount→cleanup→mount cycle
    settles to exactly one held lease, matching the committed instance."
  (:require ["react" :as React]
            [re-frame.frame  :as frame]
            [re-frame.router :as router]))

(defonce ^:private lease-token-counter
  ;; Monotone per-runtime counter minting a UNIQUE lease token per mounted
  ;; helper instance, so two components leasing the same resource+scope hold
  ;; INDEPENDENT leases (releasing one never drops the other). Mirrors the
  ;; spine's `unmount-instance-counter`.
  (atom 0))

(defn use-resource-lease
  "React hook (UIx / Helix): take a resource liveness LEASE for the calling
  component's mounted lifetime. On mount it dispatches `:rf.resource/ensure`
  with an app-minted `[:lease …]` owner; on unmount it dispatches
  `:rf.resource/release-owner` for that same lease. Returns nil (a lifecycle
  hook, not a read — pair it with `use-subscribe` on a `[:rf.resource/*]`
  query to read the data).

  Call shapes:

      (use-resource-lease {:resource :my/feed
                           :scope    :rf.scope/global
                           :params   {:page 0}})

      (use-resource-lease {:resource :my/feed :scope … :params …}
                          {:cause :dashboard-widget   ;; recorded on the ensure
                           :frame :some-frame})       ;; explicit-frame pin

  `descriptor` is the resource-instance identity — `{:resource :scope
  :params}` — exactly the ensure payload's read keys (Spec 016 §Events).

  `opts`:
    :cause  — the `:cause` recorded on the ensure (observability; a
              free-form data value). Defaults to `[:lease :mount]`.
    :frame  — pin the lease to an explicit frame id, bypassing the ambient
              frame-provider / dynamic-var resolution.

  Frame resolution (1-arg / no `:frame`): the surrounding `frame-provider`'s
  keyword via the carried-invariant chain (`frame/require-current-frame!`),
  raising `:rf.error/no-frame-context` when no frame is in scope — the same
  contract as `use-subscribe`.

  Re-lease semantics: changing the resolved frame, the descriptor, or the
  cause tears down the old lease (release-owner) and takes a fresh one
  (ensure) — the effect is keyed on those values. A stable descriptor across
  re-renders holds ONE lease with no churn."
  ([descriptor] (use-resource-lease descriptor nil))
  ([descriptor {:keys [cause frame] :as _opts}]
   ;; Resolve the frame through the full carried-invariant chain at RENDER
   ;; time — an explicit `:frame` opt wins; otherwise dynamic-var →
   ;; React-context → loud `:rf.error/no-frame-context`. Resolving here (not
   ;; inside the effect) captures the render-time frame to close over, since
   ;; the effect runs after the render scope unwinds.
   (let [frame-id (or frame
                      (frame/require-current-frame!
                        :use-resource-lease
                        {:where 're-frame.adapter.resource-lease/use-resource-lease}))
         ;; One stable lease token per mounted instance (survives re-renders
         ;; AND React StrictMode's mount→cleanup→mount double-invoke).
         token-ref (React/useRef nil)
         token     (or (.-current token-ref)
                       (let [t (swap! lease-token-counter inc)]
                         (set! (.-current token-ref) t)
                         t))
         lease     [:lease token]
         cause     (or cause [:lease :mount])
         {:keys [resource scope params]} descriptor
         ;; Stable primitive deps for React's `Object.is` comparison: the
         ;; descriptor / cause are value-equal but fresh JS objects per
         ;; render, so key the effect on a printed digest rather than the
         ;; live objects (the same stable-key discipline the spine's
         ;; use-subscribe uses for its query vector).
         deps-key  (pr-str [frame-id token resource scope params cause])]
     (React/useEffect
       (fn arm-lease []
         ;; Direct owning-ns dispatch (not the `rf/dispatch` macro) —
         ;; this is framework-internal plumbing, not an application call
         ;; site, so it deliberately carries no `:rf.trace/call-site`.
         (router/dispatch! [:rf.resource/ensure
                            {:resource resource
                             :scope    scope
                             :params   params
                             :owner    lease
                             :cause    cause}]
                           {:frame frame-id})
         (fn release-lease []
           (router/dispatch! [:rf.resource/release-owner {:owner lease}]
                             {:frame frame-id})))
       #js [deps-key])
     nil)))
