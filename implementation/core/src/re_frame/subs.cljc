(ns re-frame.subs
  "Subscriptions: registration, the per-frame sub-cache, and lookup.

  Per Spec 002 §Subscriptions composing across the signal graph and
  Spec 006 §Subscription cache — contract and operational semantics.

  Layer-1 sub: reads app-db directly via (fn [db query]).
  Layer-2 sub: reads other subs via :<- chain; (fn [inputs query]).
  Layer-3+: same shape as Layer-2 with deeper chains.

  The cache is per-frame, keyed by query-vector. Each entry holds:
    {:reaction r :inputs [...] :ref-count n}
  The cached value is NOT a stored slot — it lives on the reaction and is
  read via deref. Disposal is wired on the reaction itself
  (interop/add-on-dispose!), not an entry-level callback slot.

  Invalidation runs as part of replace-container! — when app-db changes,
  the substrate adapter's reaction graph fires; layer-1 subs recompute
  if their reader's value changed by =, layer-2+ subs cascade
  topologically.

  Disposal is **synchronous on derefer-count → 0** (rf2-cmfln, per
  Spec 006 §Reference counting and disposal). When the last subscriber
  drops (`unsubscribe` drives the 1 → 0 transition), the cache entry is
  evicted IN-TICK: the reaction is disposed, the on-dispose callback
  releases input refs (cascading down a layer-2+ chain), and the slot is
  dissoc'd. A subsequent subscribe arriving after the drop is treated as
  a fresh cache miss and rebuilds the entry; the recomputed value is `=`
  to the disposed one, so the React-render-churn case (component briefly
  unmounts then remounts with the same subscription) observes no value
  change on the new mount.

  This is the only disposal algorithm — there are no pluggable lifecycle
  policies, no deferred-grace timer, no batched dispose."
  (:require [re-frame.registrar :as registrar]
            [re-frame.frame :as frame]
            [re-frame.substrate.adapter :as adapter]
            [re-frame.interop :as interop]
            [re-frame.late-bind :as late-bind]
            [re-frame.source-coords :as source-coords]
            [re-frame.subs.cache :as subs-cache]
            [re-frame.subs.memo :as subs-memo]
            [re-frame.trace :as trace
             #?@(:cljs [:include-macros true])]
            ;; JVM autoload (rf2-bmzq0): the tooling sibling has zero
            ;; artefact cost on JVM and we keep the legacy
            ;; `re-frame.subs/<name>` shape working for JVM test
            ;; fixtures via the alias block at the bottom of the
            ;; file. CLJS deliberately omits this require so the
            ;; tooling sibling stays out of production bundles. The
            ;; reciprocal — `subs.tooling` requires `subs` to drive
            ;; the alias chain — is avoided because subs.tooling
            ;; only needs `registrar` / `frame` / `interop` and a
            ;; cyclic require would break the JVM autoload.
            #?@(:clj [[re-frame.subs.tooling :as subs-tooling]])))

#?(:clj (set! *warn-on-reflection* true))

;; ---- registration ---------------------------------------------------------
;;
;; A sub registration carries:
;;   :handler-fn     the body fn — (fn [inputs query]) for layer-2+,
;;                                  (fn [db query]) for layer-1.
;;   :input-signals  for layer-2+, a vector of [query-id arg ...] forms
;;                   that resolve to other registered subs. Empty for
;;                   layer-1 (which reads app-db directly).

(defn- parse-reg-sub-args
  "Accept the :<- shorthand and the fn-tail forms.

  Forms supported:
    (reg-sub :id (fn [db query] ...))                         ;; layer-1
    (reg-sub :id :<- [:other-sub] (fn [other-val q] ...))     ;; layer-2 single
    (reg-sub :id :<- [:a] :<- [:b] (fn [[a b] q] ...))         ;; layer-2 multi
  "
  [id args]
  (let [meta? (and (map? (first args)) (not (vector? (first args))))
        meta  (if meta? (first args) {})
        rest-args (if meta? (next args) args)]
    (loop [chain []
           remaining rest-args]
      (cond
        (and (= :<- (first remaining))
             (vector? (second remaining)))
        (recur (conj chain (second remaining))
               (drop 2 remaining))

        (= 1 (count remaining))
        {:id            id
         :meta          meta
         :input-signals chain
         :handler-fn    (first remaining)}

        :else
        (throw (ex-info
                 ":rf.error/reg-sub-bad-args"
                 {:rf.error/id :rf.error/reg-sub-bad-args
                  :where       'rf/reg-sub
                  :recovery    :fix-registration
                  :reason      "reg-sub expects layer-1 (handler-fn), layer-2 single (:<- [:upstream] handler-fn), or layer-2 multi (:<- [:a] :<- [:b] handler-fn)"
                  :id          id
                  :remaining   remaining}))))))

(defn reg-sub
  "Register a subscription under `id`. The only sub-registration form
  in v2 — `reg-sub-raw` is dropped (per Spec 002 §Subscriptions
  composing).

  Three shapes:

      ;; Layer-1 — reads `app-db` directly.
      (reg-sub :id
        (fn [db query-v] ...derived-value...))

      ;; Layer-2, single input — chains off one upstream sub.
      (reg-sub :id
        :<- [:upstream-id]
        (fn [upstream-val query-v] ...derived-value...))

      ;; Layer-2, multi input — chains off N upstream subs.
      (reg-sub :id
        :<- [:a-sub]
        :<- [:b-sub]
        (fn [[a-val b-val] query-v] ...derived-value...))

  An optional metadata-map may precede the `:<-` chain / handler:
  `(reg-sub :id {:doc \"...\" :schema ...} ...)`. The `query-v` arg the
  handler receives is the full `[sub-id & args]` subscription vector
  the caller passed to `subscribe`.

  Returns `id`. Re-registering an existing `id` replaces the prior
  registration; cached entries for the affected sub are invalidated
  (hot-reload-safe).

  Example:

      (rf/reg-sub :user/name (fn [db _] (get-in db [:user :name])))

      (rf/reg-sub :user/initials
        :<- [:user/name]
        (fn [name _]
          (clojure.string/join (map first (clojure.string/split name #\"\\s+\")))))

  See also: `subscribe` (reactive form), `subscribe-once` (one-shot
  read), `compute-sub` (pure compute against a db value), `clear-sub`."
  [id & args]
  (let [{:keys [meta handler-fn input-signals]} (parse-reg-sub-args id args)]
    (registrar/register! :sub id
      (assoc (source-coords/merge-coords meta)
             :handler-fn    handler-fn
             :input-signals input-signals))
    ;; Per Spec 015 §3. Subscriptions — stash declarations:
    ;;   :sensitive / :large — per-output-path marks
    ;;   :sensitive? / :large? — whole-output override
    ;; The propagation table (`re-frame.marks/mark-sub-output!`) is
    ;; updated on each sub-cache compute pass — see `compute-and-cache!`.
    (when-let [register! (late-bind/get-fn :marks/register-marks!)]
      (register! :sub id meta))
    ;; Per Spec 009 §:op-type vocabulary: :sub/create marks subscription
    ;; materialisation — emitted at registration time so tools see when
    ;; the sub becomes available in the registry.
    (trace/emit! :rf.sub :rf.sub/create
                 {:rf.sub/id            id
                  :rf.sub/input-signals input-signals})
    id))

(defn clear-sub
  "Unregister a subscription. Zero-arity clears every registered sub
  in the registrar; one-arity clears the named one. Hot-reload tools
  and test fixtures call this between rebuilds; production code rarely
  needs it.

  Returns nil. See also: `reg-sub`, `clear-sub-cache!`
  (the runtime-cache counterpart)."
  ([] (registrar/clear-kind! :sub))
  ([id] (registrar/unregister! :sub id)))

;; ---- the cache ------------------------------------------------------------

(defn- cache-key
  "Identity now; reserved as the chokepoint if cache-key shape changes
  (per Spec 006 §Cache shape — currently the query-vector itself)."
  [query-v]
  query-v)

;; Ref-counting, synchronous disposal, hot-reload invalidation, and
;; `clear-sub-cache!` live in `re-frame.subs.cache` — extracted per
;; rf2-0ytl4 Phase-2 seam S-A (fold-in of seam S-E). The public surface
;; (`clear-sub-cache!`) is reached through `re-frame.core`'s defalias
;; pointing at `re-frame.subs.cache/*` directly (no facade re-export).

(declare subscribe unsubscribe)

;; The memo wrappers (`make-layer-1-memoised-body`,
;; `make-layer-n-1-memoised-body`, `make-layer-n-memoised-body`) and
;; the trace/perf/validate/recover bracket (`validate-and-trace`,
;; `maybe-validate-sub!`) live in `re-frame.subs.memo` — extracted
;; per rf2-0ytl4 Phase-2 seam S-B. Per-recompute hot path is the closure
;; body (in-process); only the per-miss constructor call from
;; `compute-and-cache!` below crosses the ns boundary.

(defn- compute-and-cache!
  "Build the reaction for query-v and cache it. Per Spec 006 §Lookup
  algorithm: recursively resolve :<- chain, build the reaction, attach
  on-dispose to evict the cache slot.

  The compute fn handed to the substrate adapter is built in two
  layers, each named:

    - `make-layer-1-memoised-body` / `make-layer-n-1-memoised-body` /
      `make-layer-n-memoised-body` — Spec 006 §No-op via value
      equality (rf2-719e). Wraps the user's body in a `=`-skipping
      memo. The layer-1 form is fixed-arity-1 and compares the db
      scalar directly (avoids per-recompute varargs-seq allocation).
      Layer-2 with a single `:<-` input gets the same fixed-arity-1
      treatment (rf2-0y2bp — the dominant layer-2 shape per
      rf2-v1nu0). Layer-2+ with ≥2 inputs keeps the vec-of-inputs
      varargs shape.
    - `validate-and-trace`  — Spec 009 :sub/run trace emit, perf bracket,
      Spec 010 step 6 validation, error contract
      (`:replaced-with-default` on throw).

  Per Spec 006 §What happens when a sub references an unknown sub: when
  the registrar lookup misses, emit `:rf.error/no-such-sub` and build a
  nil-yielding reaction, but DO NOT store it in the cache. The miss is
  transient — a later registration (boot order, lazy load) must let the
  next subscribe build a fresh reaction against the real body. We
  achieve this by branching here on nil meta."
  [frame-id query-v]
  (let [query-id      (first query-v)
        sub-meta      (registrar/lookup :sub query-id)
        _             (when (nil? sub-meta)
                        (trace/emit-error! :rf.error/no-such-sub
                                           {:rf.sub/query-v query-v :frame frame-id}))
        body-fn       (:handler-fn sub-meta)
        input-signals (:input-signals sub-meta)
        layer-1?      (empty? input-signals)
        ;; Resolve inputs: layer-1 → frame's app-db; layer-2+ → recursive subs.
        inputs        (if layer-1?
                        [(frame/app-db-container frame-id)]
                        (mapv (fn [input-q] (subscribe frame-id input-q)) input-signals))
        memoised-body (cond
                        layer-1?
                        (subs-memo/make-layer-1-memoised-body
                          body-fn query-id query-v frame-id sub-meta)
                        ;; Layer-2 with a single `:<-` input — dominant
                        ;; shape per rf2-v1nu0; specialise to fixed-arity-1
                        ;; for parity with layer-1 (rf2-0y2bp).
                        (= 1 (count input-signals))
                        (subs-memo/make-layer-n-1-memoised-body
                          body-fn query-id query-v frame-id input-signals sub-meta)
                        :else
                        (subs-memo/make-layer-n-memoised-body
                          body-fn query-id query-v frame-id input-signals sub-meta))
        reaction      (adapter/make-derived-value inputs memoised-body)
        cache         (:sub-cache (frame/frame frame-id))
        k             (cache-key query-v)]
    ;; Per Spec 015 §App-db → subs / §Subs → fx propagation: when this
    ;; sub is being built, resolve whether its output should be marked
    ;; sensitive/large for downstream emit-time consultation. Honours
    ;; the `:sensitive? true/false` and `:large? true/false` overrides
    ;; on the sub's registration meta. Late-bound — when the marks
    ;; artefact is absent, this is a silent no-op. Gated by debug so
    ;; production builds DCE the lookup.
    (when interop/debug-enabled?
      (when sub-meta
        (when-let [resolve (late-bind/get-fn :marks/resolve-sub-output-marks)]
          (when-let [mark! (late-bind/get-fn :marks/mark-sub-output!)]
            (let [[s? l?] (resolve frame-id query-id input-signals layer-1?)]
              (mark! frame-id query-id s? l?))))))
    ;; Skip caching the no-such-sub miss — see the rf2-l9u5 note in the
    ;; docstring. The reaction is built so callers that hold a reference
    ;; deref to nil (per Spec 009 §Error contract recovery
    ;; :replaced-with-default), but the cache slot stays empty so a later
    ;; registration is observed by the next subscribe.
    (when (and cache sub-meta)
      (swap! cache assoc k {:reaction   reaction
                            :inputs     input-signals
                            :ref-count  1})
      (interop/add-on-dispose! reaction
        (fn []
          ;; A layer-2+ sub's construction called `subscribe` once per
          ;; `:<-` input, each incrementing the input's `:ref-count`.
          ;; The disposal must release those refs symmetrically —
          ;; without this, input ref-counts leak after Reagent auto-
          ;; disposes the parent. Decrement inputs BEFORE clearing the
          ;; parent slot so the cache invariant ("ref-count reflects
          ;; live refs") holds at every observable moment.
          (doseq [input-q input-signals]
            (try (unsubscribe frame-id input-q)
                 (catch #?(:clj Throwable :cljs :default) _ nil)))
          (swap! cache (fn [m]
                         (if (identical? reaction (get-in m [k :reaction]))
                           (dissoc m k)
                           m))))))
    reaction))

;; ---- the sub-override subscribe seam (rf2-7pgiz; CLJS, dev-only) ----------
;;
;; See the block comment inside `subscribe` for the full rationale. These
;; two helpers are CLJS-only and consulted ONLY inside the
;; `interop/debug-enabled?` gate, so the whole seam DCEs under `:advanced`
;; + `goog.DEBUG=false`. The resolver fn + the React-context reader are
;; published from Story / the CLJS-only carriage ns
;; (`re-frame.adapter.sub-override-context`) via the
;; `:subs/resolve-sub-override` late-bind hook, so core never `:require`s
;; a tools ns (bundle-isolation holds).

#?(:cljs
   (defn- maybe-validate-sub-override!
     "FOLD-IN (rf2-7pgiz). When a `:sub-overrides` HIT targets a sub that
     declares an output `:schema`, validate the pinned `value` against it
     the SAME way Spec 010 §step 6 validates a `:sub-return` — through the
     registered validator reached via the `:schemas/validate-with-registered-fn`
     late-bind hook (NOT a second validation mechanism). On a mismatch,
     emit `:rf.error/schema-validation-failure` with a NEW `:where
     :sub-override` discriminator and return nil, mirroring `:sub-return`'s
     `:replaced-with-default` recovery (observational, dev-only — the
     failure is surfaced, the violating value is replaced with the
     default). On a pass / no `:schema` / no registered validator, returns
     `value` unchanged.

     Rationale: an override that violates the sub's own output contract is
     exactly the 'pin a state the real derivation could never produce'
     anti-pattern; schema-validating it closes that honesty gap. Reuses
     the registered validator so a substituted (non-Malli) validator
     covers this surface identically to `:sub-return`."
     [value query-v sub-meta]
     (let [schema (:schema sub-meta)]
       (if (and schema (some? sub-meta))
         (if-let [validate (late-bind/get-fn :schemas/validate-with-registered-fn)]
           (if (try (validate schema value)
                    ;; A throwing validator must not crash the render; treat
                    ;; it as a pass (mirrors `subs.memo/maybe-validate-sub!`).
                    (catch :default _ true))
             value
             (let [sub-id  (first query-v)
                   explain (when-let [exp (late-bind/get-fn
                                            :schemas/explain-with-registered-fn)]
                             (try (exp schema value) (catch :default _ nil)))]
               (trace/emit-error! :rf.error/schema-validation-failure
                                  {:where          :sub-override
                                   :rf.sub/id      sub-id
                                   :failing-id     sub-id
                                   :schema-id      sub-id
                                   :rf.sub/query-v query-v
                                   :received       value
                                   :value          value
                                   :explain        explain
                                   :reason         (str "Subscription " sub-id
                                                        " :sub-override value failed schema "
                                                        (pr-str schema) ".")
                                   :recovery       :replaced-with-default})
               nil))
           value)
         value))))

#?(:cljs
   (defn- resolve-sub-override
     "Consult the `:subs/resolve-sub-override` late-bind hook for an
     exact-query-vector `:sub-overrides` HIT (rf2-7pgiz). The hook (Story-
     published) reads the closest enclosing override-context Provider and
     returns `[value]` on a hit (a one-element vector so a nil-valued
     override is honoured) or nil on a miss / unbound.

     On a HIT, schema-validate the pinned value (the FOLD-IN — see
     `maybe-validate-sub-override!`) and return a CONSTANT reaction
     `(adapter/make-derived-value [] (constantly v))` — no inputs, never
     recomputes, never cached, never touches app-db / `compute-sub`. On a
     miss / unbound / unpublished hook, return nil so `subscribe` falls
     through to the normal build-and-cache path.

     CLJS-only and called ONLY inside `subscribe`'s `interop/debug-enabled?`
     gate, so this DCEs in production."
     [_frame-id query-v]
     (when-let [resolve-override (late-bind/get-fn :subs/resolve-sub-override)]
       (when-let [hit (resolve-override query-v)]
         (let [v        (first hit)
               sub-meta (registrar/lookup :sub (first query-v))
               v*       (maybe-validate-sub-override! v query-v sub-meta)]
           (adapter/make-derived-value [] (constantly v*)))))))

(defn subscribe
  "Per Spec 006 §Lookup algorithm. Returns the reaction for query-v;
  build-and-cache on miss; reuse on hit. The 1-arity form resolves
  the active frame via the `:adapter/current-frame` late-bind hook
  (rf2-d4sf) so subscribe inside a with-frame or under a
  frame-provider auto-routes through the 3-tier chain (dynamic var
  → React context → :rf/default).

  Per Spec 006 §Plain-fn-under-non-default-frame warning (rf2-d3k3):
  the 1-arity form runs the plain-fn detection check — if the
  surrounding React-context Provider names a non-default frame and the
  rendering component is NOT reg-view-wrapped (so its subscribe call
  has fallen through to :rf/default), `:rf.warning/plain-fn-under-
  non-default-frame-once` fires once per (component-id, frame-id)
  pair. The check is late-bound through re-frame.views (CLJS-only) so
  the JVM build never loads it; production (`:advanced` +
  `goog.DEBUG=false`) elides via `interop/debug-enabled?`.

  The 2-arity `(subscribe frame-id query-v)` form **deliberately
  skips** the plain-fn detection check (rf2-r0zf2). Supplying an
  explicit `frame-id` IS the opt-out — the caller has told the runtime
  exactly which frame to target, so a fall-through-to-`:rf/default`
  diagnostic doesn't apply. Use the 2-arity form from a plain
  Reagent fn body when you want to subscribe against a known frame
  without triggering the warning surface.

  Per Spec 006 §The sub-override subscribe seam (rf2-7pgiz, CLJS /
  dev-only): when a Story render wraps the variant view in the
  override-context Provider, an exact-query-vector `:sub-overrides` HIT
  short-circuits build-and-cache and returns a constant reaction holding
  the pinned value (schema-validated against the sub's declared
  `:schema` when present). The whole consult sits inside
  `interop/debug-enabled?` so it DCEs in production, and the override
  feeds ONLY the derefed reaction the view sees — never app-db, never
  `compute-sub` — so `:rf.assert/sub-equals` stays unsatisfiable by an
  override.

  This is the runtime-callable fn form. The macro form
  `re-frame.core/subscribe` captures `(meta &form)` and delegates here
  through `re-frame.core/subscribe*`, wrapping the call in
  `trace/with-call-site` so any error emitted inside the synchronous
  miss path (`:rf.error/no-such-sub`, `:rf.error/frame-destroyed`)
  carries the invocation coord."
  ([query-v]
   #?(:cljs
      (let [frame-id (frame/resolve-current-frame)]
        (when interop/debug-enabled?
          (when-let [warn! (late-bind/get-fn
                            :views/maybe-warn-plain-fn-under-non-default-frame!)]
            (warn! frame-id query-v)))
        (subscribe frame-id query-v))
      :clj
      (subscribe (frame/resolve-current-frame) query-v)))
  ([frame-id query-v]
   ;; rf2-9hoos (CLJS, dev-only): record the view→sub edge — push this
   ;; query-v into the in-flight render's deref sink so `:rf.view/rendered`
   ;; can carry the view's OWN read-set (`:deref-subs`). No-op outside a
   ;; view render (the sink is unbound) and on the JVM. Routed through
   ;; late-bind so this .cljc layer stays free of a static require on the
   ;; CLJS-only views ns; the whole call sits inside `interop/debug-enabled?`
   ;; so production DCEs it. Fires for every subscribe (hit AND miss) so
   ;; the read-set is complete even for memo-hit re-derefs.
   #?(:cljs
      (when interop/debug-enabled?
        (when-let [record! (late-bind/get-fn :views/record-view-deref!)]
          (record! query-v))))
   ;; rf2-7pgiz (CLJS, dev-only): the SUBSTITUTIVE override seam. Story's
   ;; lowest-fidelity ladder rung (`:sub-overrides`) pins a view into an
   ;; `:error`/`:loading`/`:empty` state by naming subscription
   ;; query-vectors and the values they should surface — no events, no
   ;; app-db (`tools/story/spec/017-Testing-Story.md` §View-state
   ;; subscription overrides). When a Story render wraps the variant view
   ;; in the override-context Provider (`re-frame.adapter.sub-override-
   ;; context`), the resolver published under `:subs/resolve-sub-override`
   ;; reads the closest enclosing override map and returns `[value]` on an
   ;; exact-query-vector HIT (a one-element vector so a nil-valued
   ;; override is still honoured) or nil on a miss / unbound / production.
   ;;
   ;; On a HIT we short-circuit build-and-cache and hand back a CONSTANT
   ;; reaction `(adapter/make-derived-value [] (constantly v))`: it has no
   ;; inputs, so it never recomputes, is never cached, and feeds ONLY the
   ;; derefed reaction the view sees. It NEVER touches app-db and NEVER
   ;; reaches `compute-sub`, so `:rf.assert/sub-equals` (which evaluates a
   ;; sub through `compute-sub` against the real app-db) still cannot be
   ;; satisfied by an override — the load-bearing honesty boundary.
   ;;
   ;; FOLD-IN (rf2-7pgiz): an override that violates the sub's own
   ;; declared output `:schema` is exactly the "pin a state the real
   ;; derivation could never produce" anti-pattern, so we schema-validate
   ;; the pinned value the SAME way Spec 010 §step 6 validates a
   ;; `:sub-return` — through the registered validator
   ;; (`:schemas/validate-with-registered-fn`), dev-only. On a mismatch we
   ;; emit `:rf.error/schema-validation-failure` with a `:where
   ;; :sub-override` discriminator and surface nil, mirroring
   ;; `:sub-return`'s `:replaced-with-default` posture (observational —
   ;; the failure is emitted, the violating value is not surfaced).
   ;;
   ;; This whole block is the same `interop/debug-enabled?` +
   ;; `late-bind/get-fn` envelope the `:views/*` subscribe hooks above use,
   ;; so it DCEs under `:advanced` + `goog.DEBUG=false`; the resolver +
   ;; the context reader live in Story / the CLJS-only carriage ns, so
   ;; core stays tools-free (bundle-isolation holds). `resolve-sub-override`
   ;; (CLJS, dev-only) returns the constant override reaction on a HIT, or
   ;; nil to fall through to the normal build-and-cache path. On the JVM
   ;; and in production it is always nil (the gate / reader DCE / no-op).
   (or
     #?(:cljs
        (when interop/debug-enabled?
          (resolve-sub-override frame-id query-v)))
     (let [frame-record (frame/frame frame-id)]
       (cond
         ;; Missing or destroyed frame: trace and return nil rather than
         ;; deref-ing nil and exploding. Per Spec 009 §Error contract:
         ;; recovery is :replaced-with-default — the sub resolves to nil.
         (nil? frame-record)
         (do (trace/emit-error! :rf.error/frame-destroyed
                                {:frame    frame-id
                                 :query-v  query-v
                                 :recovery :replaced-with-default})
             nil)

         :else
         (let [cache (:sub-cache frame-record)
               k     (cache-key query-v)]
           (if-let [entry (get @cache k)]
             ;; Hit. Bump ref-count under CAS-after-snapshot discipline so a
             ;; concurrent evictor (hot-reload re-registration, `clear-sub-
             ;; cache!`) that won the race cannot resurrect a phantom entry
             ;; with no `:reaction` AND hand back the now-disposed reaction.
             ;; The pure-swap-fn only bumps when the slot is still present
             ;; holding the SAME reaction; reading `[old new]` from the
             ;; snapshot pair tells us whether the bump landed. If the slot
             ;; was concurrently evicted (or rebuilt under a different
             ;; reaction), fall through to a fresh build — the same
             ;; CAS-after-snapshot discipline `re-frame.subs.cache` uses.
             ;; On single-threaded CLJS the re-check always succeeds (no
             ;; concurrent evictor can interleave), so the rebuild branch
             ;; is concurrency-host-only.
             (let [reaction (:reaction entry)
                   [_old new]
                   (swap-vals! cache
                               (fn [m]
                                 (if (identical? reaction
                                                 (get-in m [k :reaction]))
                                   (update-in m [k :ref-count] (fnil inc 0))
                                   m)))]
               (if (identical? reaction (get-in new [k :reaction]))
                 reaction
                 (compute-and-cache! frame-id query-v)))
             (compute-and-cache! frame-id query-v))))))))

(defn subscribe-once
  "One-shot read of a sub's current value. Subscribes, derefs, then
  unsubscribes — does NOT retain a reference on the cache entry and
  does NOT register the caller for reactive re-render.

  Use in tests, REPL sessions, machine-action bodies, SSR builders,
  or any non-reactive consumer that wants the value right now. For
  reactive consumers (Reagent views, tools holding the reaction) use
  `subscribe`. For event handlers prefer `(inject-cofx :sub-as-cofx)`
  so the read is part of the cofx contract rather than a side-effect
  inside the handler body.

  Per rf2-cmfln (Spec 006 §Reference counting and disposal): the
  teardown `unsubscribe` runs synchronously on the 1 → 0 transition,
  so the one-shot read's whole lifetime — subscribe, deref, dispose —
  completes in the calling tick. Concurrent reactive subscribers keep
  the slot alive via ref-count and are unaffected — `subscribe-once`'s
  decrement only drives the eviction when it owned the last reference.

  See also: `subscribe`, `unsubscribe`, `compute-sub`, `inject-cofx`."
  ([query-v] (subscribe-once (frame/resolve-current-frame) query-v))
  ([frame-id query-v]
   (let [reaction (subscribe frame-id query-v)
         v        (when reaction @reaction)]
     (unsubscribe frame-id query-v)
     v)))

(defn- compute-sub*
  "Recursive worker for `compute-sub`. Threads a per-call `memo` atom
  (`{query-v -> value}`) through the `:<-` recursion so each DISTINCT
  sub in the dependency graph computes — and emits its `:rf.sub/run`
  trace — at most once per top-level `compute-sub` call (rf2-gyxm3).

  A memo HIT short-circuits to the pinned value: no body re-run, no
  duplicate `:rf.sub/run` emission. Memoising by the full `query-v`
  (id + args) is value-identical to re-computing because, for a fixed
  `db`, a sub's value is a pure function of `db` + its inputs."
  [query-v db memo]
  ;; `contains?` (not a sentinel + `identical?`) so a memoised nil value
  ;; is honoured as a HIT — unregistered subs and recovery-to-nil both
  ;; memoise nil, and a keyword sentinel is not reliably reference-equal
  ;; under `identical?` on CLJS.
  (if (contains? @memo query-v)
    (get @memo query-v)
    (let [query-id (first query-v)
          meta     (registrar/lookup :sub query-id)]
      (if-not meta
        ;; Unregistered sub computes to nil; memoise so a repeated
        ;; reference within the same call is also a single resolution.
        (do (swap! memo assoc query-v nil) nil)
        (do
          ;; Per Spec 009 §:op-type vocabulary: :sub/run marks a sub recompute.
          ;; The pure compute-sub form fires the same op-type as the reactive
          ;; recompute path so tools can observe both call sites uniformly.
          ;;
          ;; Per rf2-l1jz8 — the reactive recompute path (subs.memo/validate-
          ;; and-trace) enriches its `:sub/run` tag with value-change +
          ;; cascade attribution (`:value-changed?` / `:prev-value` /
          ;; `:value` / `:cascade?` / `:cause-sub`). `compute-sub` deliberately
          ;; bypasses the per-frame reactive cache (it's the pure-snapshot
          ;; form per Spec 008 §Testing), so it has NO prior cached value to
          ;; diff for `:value-changed?` and NO reactive context to attribute a
          ;; cascade against — each DISTINCT `:<-` input is re-resolved fresh
          ;; against the supplied `db`, not observed as a changed upstream
          ;; signal. It therefore emits the BASE `:sub/run` shape only;
          ;; attribution is a reactive-path concern. Consumers (Xray) read
          ;; attribution off the reactive epoch records, never off
          ;; compute-sub emissions.
          (trace/emit! :rf.sub :rf.sub/run
                       {:rf.sub/id      query-id
                        :rf.sub/query-v query-v})
          (let [body-fn (:handler-fn meta)
                inputs  (:input-signals meta)
                ;; Bind n once — `(empty? inputs)` then `(= 1 (count inputs))`
                ;; counted twice on the multi-input path (rf2-r1rma).
                n       (count inputs)
                ;; Per Spec 009 §Error contract — body throws emit
                ;; :rf.error/sub-exception and recover to nil. Mirrors
                ;; `subs.memo/validate-and-trace` (the reactive sibling), so
                ;; SSR + JVM-runnable consumers driving subs through
                ;; `compute-sub` get the same debuggable signal the reactive
                ;; path produces. The `:where :compute-sub` tag distinguishes
                ;; this emission site from the reactive memo path; the rest of
                ;; the envelope mirrors the sibling exactly (rf2-cos61).
                v       (try
                          (let [raw (cond
                                      (zero? n)
                                      (body-fn db query-v)

                                      (= 1 n)
                                      (body-fn (compute-sub* (first inputs) db memo) query-v)

                                      :else
                                      (body-fn (mapv #(compute-sub* % db memo) inputs) query-v))]
                            ;; rf2-9cm27 — `compute-sub` is the pure testing form
                            ;; (Spec 008 §Testing): a compute against a SUPPLIED db,
                            ;; outside any reactive cascade. No in-flight reaction
                            ;; frame to attribute to, so `frame-id` is nil — the
                            ;; `:where :sub-return` trace from this path is not tied
                            ;; to a per-frame epoch (mirrors the direct-caller
                            ;; 4-arity contract).
                            (subs-memo/maybe-validate-sub! raw query-v query-id meta nil))
                          (catch #?(:clj Throwable :cljs :default) e
                            (let [msg #?(:clj (.getMessage ^Throwable e) :cljs (.-message e))]
                              (trace/emit-error!
                                :rf.error/sub-exception
                                {:failing-id        query-id
                                 :rf.sub/id         query-id
                                 :sub-query         query-v
                                 :where             :compute-sub
                                 :exception         e
                                 :exception-message msg
                                 :reason            (str "Subscription `" query-id
                                                         "` threw while computing: "
                                                         msg ". Returning nil.")
                                 :recovery          :replaced-with-default}))
                            nil))]
            (swap! memo assoc query-v v)
            v))))))

(defn compute-sub
  "Compute a subscription's value against a supplied db, bypassing the
  reactive cache. Useful in tests that want to inspect what a sub
  WOULD compute given a snapshot of state without going through the
  per-frame cache. Supports the same :<- chain shape as subscribe.

  Per Spec 008 §Testing — pure compute-sub form. Per Spec 010 §step 6
  (rf2-wcam): the return value is validated against any :schema on the
  sub's meta — failures emit :rf.error/schema-validation-failure and
  yield nil (default :replaced-with-default recovery).

  ## Cost — linear in distinct subs per call (rf2-gyxm3 / rf2-r0zf2)

  A per-call memo `{query-v -> value}` is threaded through the `:<-`
  recursion (see `compute-sub*`) so each DISTINCT sub in the dependency
  graph computes at most ONCE per top-level `compute-sub` call. A
  diamond dependency (`:c` depends on `:a` and `:b`; both depend on
  `:root`) computes `:root` exactly once; a reused-leaf chain no longer
  compounds multiplicatively. The memo is sound because for a fixed
  `db` a sub's value is a pure function of `db` + its inputs, so
  memoising by the full `query-v` (id + args) is value-identical to
  re-computing.

  This stays cache-free at the frame level — the memo is a per-call
  internal accumulator scoped to one top-level `compute-sub`, NOT the
  reactive per-frame sub-cache `subscribe` uses. The pure-snapshot
  contract is unchanged: still purely a function of the supplied `db`,
  no cross-call state, no reactive context.

  Trace note: the memo elides duplicate `:rf.sub/run` emissions for a
  shared input within one call — the sub runs (and emits) once; a
  second reference is a memo hit. This matches the reactive path, where
  the per-frame cache likewise computes a shared layer-2 input once."
  [query-v db]
  ;; Seed a fresh per-call memo for the recursion. The public arity is
  ;; unchanged; the memo is purely an internal accumulator.
  (compute-sub* query-v db (atom {})))

(defn unsubscribe
  "Decrement the ref-count on the cached subscription for query-v.
  When ref-count reaches 0, dispose the entry **synchronously** —
  evict the cache slot, run the reaction's on-dispose callback (which
  releases input refs symmetrically), and emit `:rf.sub/dispose` with
  reason `:no-more-derefers`. Per Spec 006 §Reference counting and
  disposal (rf2-cmfln).

  Reagent views auto-dispose via the reaction lifecycle and don't
  need to call this explicitly. Tests, REPL sessions, and tools that
  subscribe imperatively should call unsubscribe when they're done
  to release the cache slot.

  Per rf2-0ytl4 seam S-A: ref-counting and synchronous dispose live in
  `re-frame.subs.cache`; this facade fn holds the public API shape and
  delegates to `subs-cache/unsubscribe!` after resolving the cache + key."
  ([query-v]
   (unsubscribe (frame/resolve-current-frame) query-v))
  ([frame-id query-v]
   (when-let [cache (:sub-cache (frame/frame frame-id))]
     ;; rf2-mrnur — thread `frame-id` through so the `:rf.sub/dispose`
     ;; trace emit at the eviction site carries the canonical `:frame`
     ;; tag.
     (subs-cache/unsubscribe! cache (cache-key query-v) frame-id))))

;; ---- tooling sibling --------------------------------------------------
;;
;; Per rf2-bmzq0: the static-topology query (`sub-topology`) and the
;; reactive-cache snapshot (`sub-cache-snapshot`) moved to
;; `re-frame.subs.tooling` so production counter bundles DCE their
;; bodies. CLJS consumers needing the introspection surface load the
;; tooling sibling explicitly; JVM consumers reach the legacy
;; `re-frame.subs/<name>` shape via the convenience aliases in the
;; JVM-only block at the bottom of this file.

;; ---- late-bind hook registration ------------------------------------------
;;
;; re-frame.routing needs to call subscribe-once but cannot `:require`
;; this namespace without a cyclic load order. Publish entry point
;; through the late-bind hook registry. See re-frame.late-bind.

(late-bind/set-fn! :subs/subscribe-once subscribe-once)

;; ---- JVM-side convenience aliases (rf2-bmzq0) ----------------------------
;;
;; On the JVM we preserve the legacy `re-frame.subs/<name>` shape for
;; the tooling surface so the cascade of `.clj` test fixtures stays
;; unchanged. The aliases are gated under `#?(:clj ...)` so they never
;; appear in CLJS compilation — production counter bundles still DCE
;; the tooling sibling wholesale because `re-frame.subs` on CLJS has
;; no static reference to it. Mirror of the trace/tooling pattern
;; per rf2-qwm0a.

#?(:clj
   (do
     (def sub-topology       subs-tooling/sub-topology)
     (def sub-cache-snapshot subs-tooling/sub-cache-snapshot)))
