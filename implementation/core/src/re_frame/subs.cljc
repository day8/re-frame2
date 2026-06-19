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
            [re-frame.error :as error]
            [re-frame.frame :as frame]
            [re-frame.live-frame :as live-frame]
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
;;   :handler-fn     the body (computation) fn — (fn [inputs query]) for
;;                   layer-2+, (fn [db query]) for layer-1.
;;   :input-kind     the input-producer discriminator (Spec 006
;;                   §Subscription input producers) — one of:
;;                     :db          layer-1 app-db reader (no producer);
;;                     :static      literal `:<-` query-vectors known at
;;                                  registration;
;;                     :parametric  an `input-fn` that computes the input
;;                                  query-vectors from the outer query-v.
;;   :input-signals  for the `:static` kind, the vector of literal
;;                   [query-id arg ...] `:<-` query-vectors. Empty `[]`
;;                   for `:db` and `:parametric`.
;;   :input-fn       for the `:parametric` kind, the pure
;;                   (fn [query-v] -> [query-vector*]) input producer.
;;                   Absent for `:db` / `:static`.

(defn- parse-reg-sub-args
  "Accept the `:<-` shorthand, the layer-1 app-db-reader form, and the
  two-function parametric `input-fn` form.

  Forms supported (Spec 006 §Subscription input producers, API
  §`reg-sub` input-production modes):
    (reg-sub :id (fn [db query] ...))                         ;; :db (layer-1)
    (reg-sub :id :<- [:other-sub] (fn [other-val q] ...))     ;; :static single
    (reg-sub :id :<- [:a] :<- [:b] (fn [[a b] q] ...))         ;; :static multi
    (reg-sub :id (fn input [q] [[:a] [:b]]) (fn comp [in q] ...)) ;; :parametric

  An optional metadata-map may precede any of these. The two-function
  parametric form is recognised by two trailing fns with NO `:<-` chain
  — the first is the `input-fn`, the second the computation fn.

  Returns a parsed map carrying `:input-kind` plus the kind-specific
  slots. Signals `:rf.error/reg-sub-bad-args` (a thrown, tagged ex-info
  — registration-time / dev-only per Spec 009) on an unaccepted shape."
  [id args]
  (let [;; A handler / input-fn must be a genuine function — a plain `fn`
        ;; OR a Var (callable IFn; `requiring-resolve` / HoF call sites
        ;; register with a Var, which is not `fn?`). Deliberately NOT
        ;; `ifn?`: a keyword / map / set / vector / symbol is `ifn?` but is
        ;; never a sub handler, and treating one as a handler would silently
        ;; accept a malformed tail like `(reg-sub :id (fn …) :stray-kw)`.
        handler? (fn [x] (or (fn? x) (var? x)))
        meta? (and (map? (first args)) (not (vector? (first args))))
        meta  (if meta? (first args) {})
        rest-args (if meta? (next args) args)
        bad!  (fn [reason received]
                (error/throw-error!
                  :rf.error/reg-sub-bad-args
                  'rf/reg-sub
                  reason
                  {:recovery :fix-registration
                   :extra    {:id       id
                              :received received}}))]
    (loop [chain []
           remaining rest-args]
      (cond
        (and (= :<- (first remaining))
             (vector? (second remaining)))
        (recur (conj chain (second remaining))
               (drop 2 remaining))

        ;; A leading `:<-` without a following query-vector is malformed.
        (= :<- (first remaining))
        (bad! "reg-sub `:<-` must be followed by an input query-vector, e.g. (:<- [:upstream] ...)"
              (vec remaining))

        ;; Two-function parametric form — ONLY when no `:<-` chain was
        ;; consumed (a `:<-` chain plus a trailing pair would be an
        ;; over-specified, ambiguous registration). The first fn is the
        ;; `input-fn`; the second is the computation fn. `handler?`
        ;; (fn-or-Var) accepts a Var-form registration (`(reg-sub id
        ;; #'input-fn #'comp-fn)`, e.g. `requiring-resolve` / HoF call
        ;; sites) while rejecting a stray keyword/map/vector tail.
        (and (empty? chain)
             (= 2 (count remaining))
             (handler? (first remaining))
             (handler? (second remaining)))
        {:id         id
         :meta       meta
         :input-kind :parametric
         :input-fn   (first remaining)
         :handler-fn (second remaining)}

        ;; Single trailing fn — layer-1 app-db reader (`:db`) when no
        ;; `:<-` chain, else the `:static` `:<-` computation fn.
        (and (= 1 (count remaining))
             (handler? (first remaining)))
        {:id            id
         :meta          meta
         :input-kind    (if (empty? chain) :db :static)
         :input-signals chain
         :handler-fn    (first remaining)}

        :else
        (bad! (str "reg-sub expects one of: layer-1 app-db reader "
                   "(computation-fn), static inputs "
                   "(:<- [:a] :<- [:b] computation-fn), or parametric "
                   "inputs (input-fn computation-fn). The trailing arg(s) "
                   "must be the required function(s).")
              (vec remaining))))))

(defn reg-sub
  "Register a subscription under `id`. The only sub-registration form
  in v2 — `reg-sub-raw` is dropped (per Spec 002 §Subscriptions
  composing).

  Four shapes — one per input-producer kind (Spec 006 §Subscription
  input producers):

      ;; :db — Layer-1: reads `app-db` directly (no input producer).
      (reg-sub :id
        (fn [db query-v] ...derived-value...))

      ;; :static, single input — the literal `:<-` producer chains off
      ;; one upstream sub.
      (reg-sub :id
        :<- [:upstream-id]
        (fn [upstream-val query-v] ...derived-value...))

      ;; :static, multi input — the literal `:<-` producer chains off N
      ;; upstream subs.
      (reg-sub :id
        :<- [:a-sub]
        :<- [:b-sub]
        (fn [[a-val b-val] query-v] ...derived-value...))

      ;; :parametric — an `input-fn` producer (two trailing fns, NO `:<-`
      ;; chain). The first fn is a pure `query-v -> vector-of-query-vectors`
      ;; that computes the inputs PER concrete query; the second is the
      ;; computation fn over those realized inputs.
      (reg-sub :id
        (fn [query-v] [[:a (second query-v)] [:b]])   ;; input-fn
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
  (let [parsed (try
                 (parse-reg-sub-args id args)
                 (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
                   ;; Per Spec 009 §Error catalogue (`:rf.error/reg-sub-bad-args`):
                   ;; a malformed registration shape. Registration-time /
                   ;; dev-only — it does NOT ride the always-on production
                   ;; error-emit listener (the registration path is never
                   ;; re-run in production). Emit a structured dev trace so
                   ;; tools surface the bad registration, then RE-THROW
                   ;; (`:no-recovery` — the registration is rejected; the
                   ;; malformed `reg-sub` is a programming error to fix).
                   (let [{:keys [reason received]} (ex-data e)]
                     (trace/emit-error! :rf.error/reg-sub-bad-args
                                        {:rf.sub/id id
                                         :received  received
                                         :reason    reason
                                         :recovery  :no-recovery}))
                   (throw e)))
        {:keys [meta handler-fn input-kind input-signals input-fn]} parsed]
    ;; Per Spec 015 §Derived sensitivity — VALIDATE declarations fail-loud
    ;; BEFORE the registrar write (rf2-ehexnw):
    ;;   :sensitive / :large — per-output-path marks
    ;;   :rf.egress/output-sensitivity — the derived-output declassification
    ;;     enum (:rf.egress/inherit | :rf.egress/sensitive | :rf.egress/public,
    ;;     EP-0015 issue 9; the rejected :sensitive? overload throws here for
    ;;     the :sub kind)
    ;;   :large? — whole-output size override
    ;; The marks themselves are DERIVED from the registrar meta at `marks-for`
    ;; read time (no imperative stash). The propagation table
    ;; (`re-frame.marks/mark-sub-output!`) is updated on each sub-cache compute
    ;; pass — see `compute-and-cache!`.
    (when-let [validate! (late-bind/get-fn :marks/validate-marks!)]
      (validate! :sub meta))
    (registrar/register! :sub id
      (cond-> (assoc (source-coords/merge-coords meta)
                     :handler-fn    handler-fn
                     :input-kind    input-kind
                     :input-signals (or input-signals []))
        input-fn (assoc :input-fn input-fn)))
    ;; Per Spec 009 §:op-type vocabulary: :sub/create marks subscription
    ;; materialisation — emitted at registration time so tools see when
    ;; the sub becomes available in the registry.
    (trace/emit! :rf.sub :rf.sub/create
                 {:rf.sub/id            id
                  :rf.sub/input-kind    input-kind
                  :rf.sub/input-signals (or input-signals [])})
    id))

(defn reg-runtime-sub
  "Register a FRAMEWORK subscription whose single layer-1-shaped signal
  source is the frame's **runtime-db** projection rather than app-db
  (EP-0001 rf2-vzld77; Spec 002 §Subscriptions read the partition they
  belong to). The handler shape is identical to a layer-1 `reg-sub`
  computation fn — `(fn [runtime-db query-v] …)` — but the `db`-position
  argument is the runtime-db partition value, so framework subsystem subs
  (`:rf/machine`, `:rf/machine-has-tag?`, `[:rf.route/*]`) read their
  durable runtime-db state directly.

  INTERNAL / framework-only: this is NOT a public app-author surface (app
  code reads app-db via `reg-sub` and reaches subsystem state through these
  framework subs). Routed from the subsystem façades (`re-frame.machines`,
  `re-frame.routing`). Accepts an optional leading metadata map, same as
  `reg-sub`. Returns `id`."
  ([id handler-fn] (reg-runtime-sub id {} handler-fn))
  ([id meta handler-fn]
   ;; rf2-ehexnw — VALIDATE marks fail-loud before the registrar write; marks
   ;; are DERIVED from the registrar meta at read time, no imperative stash.
   (when-let [validate! (late-bind/get-fn :marks/validate-marks!)]
     (validate! :sub meta))
   (registrar/register! :sub id
     (assoc (source-coords/merge-coords meta)
            :handler-fn    handler-fn
            :input-kind    :runtime-db
            :input-signals []))
   (trace/emit! :rf.sub :rf.sub/create
                {:rf.sub/id            id
                 :rf.sub/input-kind    :runtime-db
                 :rf.sub/input-signals []})
   id))

(defn reg-frame-state-sub
  "Register a FRAMEWORK subscription whose single layer-1-shaped signal
  source is the frame's WHOLE frame-state container — BOTH partitions
  `{:rf.db/app <app-db> :rf.db/runtime <runtime-db>}` (EP-0016 D3 slice 3,
  rf2-616xa6). The handler shape is identical to a layer-1 `reg-sub`
  computation fn — `(fn [frame-state query-v] …)` — but the `db`-position
  argument is the FULL frame-state value, so the body can read durable
  runtime-db state AND derive over app-db in one coherent snapshot.

  Unlike `reg-runtime-sub` (which reads only the runtime-db projection and
  is therefore inert to an app-db-only commit), a `:frame-state` sub
  re-runs its body on a change to EITHER partition. That is exactly what a
  resource subscription whose scope is a `{:from-db …}` resolver needs: it
  must re-key reactively when the resolver's declared app-db inputs change
  mid-session (account switch / impersonation / login), while still
  reacting to runtime-db cache writes. Output `=` memoisation keeps
  downstream reactions quiet when neither relevant slice changed, so the
  whole-frame-state signal does not over-notify.

  INTERNAL / framework-only — NOT a public app-author surface (app code
  reads app-db via `reg-sub`; subsystem state via the framework
  `reg-runtime-sub`). Accepts an optional leading metadata map, same as
  `reg-sub` / `reg-runtime-sub`. Returns `id`."
  ([id handler-fn] (reg-frame-state-sub id {} handler-fn))
  ([id meta handler-fn]
   ;; rf2-ehexnw — VALIDATE marks fail-loud before the registrar write; marks
   ;; are DERIVED from the registrar meta at read time, no imperative stash.
   (when-let [validate! (late-bind/get-fn :marks/validate-marks!)]
     (validate! :sub meta))
   (registrar/register! :sub id
     (assoc (source-coords/merge-coords meta)
            :handler-fn    handler-fn
            :input-kind    :frame-state
            :input-signals []))
   (trace/emit! :rf.sub :rf.sub/create
                {:rf.sub/id            id
                 :rf.sub/input-kind    :frame-state
                 :rf.sub/input-signals []})
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

;; ---- parametric input production ------------------------------------------
;;
;; Per Spec 006 §Subscription input producers + the EP §Reference
;; Implementation. Every subscription has ONE input query-vector
;; producer; `produce-input-queries` resolves it to the realized input
;; query-vectors for a concrete outer query-v, and `normalize-sub-inputs`
;; validates the `input-fn` return shape against the narrow grammar.

(defn- query-vector?
  "A query vector is a vector whose first element is a keyword (Spec 006
  §Input grammar / Conventions §`reg-sub` input grammar). Anything else
  — a bare keyword, a scalar, a map, a reaction/derefable, a vector with
  a non-keyword head — is NOT a query vector."
  [x]
  (and (vector? x)
       (keyword? (first x))))

(defn normalize-sub-inputs
  "PURE validator for a parametric `input-fn` return value (Spec 006
  §Input grammar; the EP §Reference Implementation `normalize-sub-inputs`).

  The ONLY legal shape is a vector whose every element is a query vector
  (a vector with a keyword head):

      input-return := [query-vector*]      ;; query-vector := [<keyword> & args]

  On success returns `{:queries [query-v ...]}` — the realized input
  query-vectors in producer order (empty `[]` for a `[]` return, which is
  unusual but valid). On ANY other shape — a scalar query vector
  (`[:x :y]`), a bare keyword (`:x`), a map, a reaction / derefable, a
  mixed vector, a vector with a non-keyword head, or a non-vector — it
  REJECTS LOUDLY by throwing a tagged `:rf.error/sub-input-fn-bad-return`
  ex-info (the `:returned` slot carries the offending value). The throw
  keeps this fn pure (no trace emit, no IO); each call site catches it,
  stamps `:where` (`:reactive` / `:compute-sub`), and routes the error
  through the loud emission path. A bad return is NEVER silently treated
  as no inputs.

  This is JVM-runnable and side-effect-free — usable from both the
  reactive cache path and the pure `compute-sub` path."
  [input-return]
  (let [bad! (fn [reason]
               (error/throw-error!
                 :rf.error/sub-input-fn-bad-return
                 'rf/subscribe
                 reason
                 {:extra {:returned input-return}}))]
    (cond
      (not (vector? input-return))
      (bad! (str "input-fn must return a vector of query vectors; got "
                 (pr-str (type input-return))
                 ". A bare keyword, scalar, map, or reaction is not accepted "
                 "— the single-input spelling is [[:x :y]], not [:x :y]."))

      (not (every? query-vector? input-return))
      (bad! (str "every element of an input-fn return must be a query vector "
                 "(a vector with a keyword head). The scalar form [:x :y] is "
                 "ambiguous and rejected; spell a single input as [[:x :y]]."))

      :else
      {:queries input-return})))

(defn- produce-input-queries
  "Resolve a sub's realized input query-vectors for a concrete outer
  `query-v` from its input producer (Spec 006 §Subscription input
  producers). Returns a vector of query-vectors:

    :db          → []                                   (layer-1 reader)
    :static      → (:input-signals sub-meta)            (literal `:<-` list)
    :parametric  → (normalize-sub-inputs ((:input-fn sub-meta) query-v))

  PURE — no trace emit, no IO. The parametric branch may throw the
  `input-fn`'s own exception (the input-fn body threw) OR the tagged
  `:rf.error/sub-input-fn-bad-return` ex-info from `normalize-sub-inputs`
  (the input-fn returned a bad shape). Call sites catch both, discriminate
  them, and route through the loud emission path with the right `:where`.
  Legacy registrations (pre-`:input-kind`, theoretical) fall back to the
  `:input-signals` list."
  [sub-meta query-v]
  (case (:input-kind sub-meta)
    :db          []
    :runtime-db  []
    ;; EP-0016 D3 slice 3: a `:frame-state` sub is a single-source reader
    ;; over the WHOLE frame-state value (both partitions); no input producer.
    :frame-state []
    :static     (vec (:input-signals sub-meta))
    :parametric (:queries (normalize-sub-inputs ((:input-fn sub-meta) query-v)))
    ;; Fallback for any registration that predates the discriminator —
    ;; treat its `:input-signals` as the literal input list (the prior
    ;; behaviour). Defensive; all `reg-sub` paths now stamp `:input-kind`.
    (vec (:input-signals sub-meta))))

;; ---- single-source (layer-1-shaped) input-kinds (rf2-6zfzxy) --------------
;;
;; `:db` / `:runtime-db` / `:frame-state` are the layer-1-SHAPED single-source
;; reader kinds: each reads ONE frame-state container directly (no `:<-` /
;; `input-fn` producer, so `produce-input-queries` returns `[]` for all three),
;; and each runs the same fixed-arity-1 memoised body — only the container the
;; reaction watches differs. That membership was re-derived three ways (the
;; reactive `(or layer-1? runtime-db? frame-state?)` flag union + the
;; `compute-sub` `#{:db :runtime-db :frame-state}` inline set + the reactive
;; `inputs` cond's three explicit container branches). The set + container map
;; below are the single source those collapse onto.

(def ^:private single-source-input-kinds
  "The layer-1-shaped single-source reader kinds (EP-0001 / EP-0016 D3): each
  reads ONE frame-state container directly via the same fixed-arity-1 body."
  #{:db :runtime-db :frame-state})

(def ^:private single-source-container-for
  "`input-kind → (fn [frame-id] container)` for the single-source reader kinds.
  The reactive build watches the resolved container as the reaction's lone
  signal source: `:db` the app-db projection, `:runtime-db` the runtime-db
  projection, `:frame-state` the WHOLE frame-state value (both partitions, so
  the body re-runs on a change to EITHER — EP-0016 D3 slice 3, rf2-616xa6)."
  {:db          frame/app-db-container
   :runtime-db  frame/runtime-db-container
   :frame-state frame/frame-state-container})

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
;; `make-layer-n-single-input-memoised-body`, `make-layer-n-memoised-body`) and
;; the trace/perf/validate/recover bracket (`validate-and-trace`,
;; `maybe-validate-sub!`) live in `re-frame.subs.memo` — extracted
;; per rf2-0ytl4 Phase-2 seam S-B. Per-recompute hot path is the closure
;; body (in-process); only the per-miss constructor call from
;; `compute-and-cache!` below crosses the ns boundary.

;; ---- parametric input-fn error emission (rf2-7brl74) ----------------------
;;
;; Per Spec 009 §Error catalogue: `:rf.error/sub-input-fn-exception` (the
;; `input-fn` threw while materializing a node) and
;; `:rf.error/sub-input-fn-bad-return` (the `input-fn` returned a value
;; other than a vector of query vectors) are PRODUCTION-SURVIVABLE runtime
;; categories — they ride the always-on error-emit listener (surface #4)
;; so a bad parametric input under `:advanced` + `goog.DEBUG=false`
;; reaches off-box shippers, AND the dev trace surface. The recovery is
;; the framework's built-in nil-yielding reaction. A bad input return is
;; NEVER silently treated as no inputs — the structured error always
;; carries the outer `query-v` + sub id. `:where` (`:reactive` /
;; `:compute-sub`) discriminates the resolution path.

(defn- emit-sub-input-fn-error!
  "Emit a parametric `input-fn` failure loudly along BOTH the always-on
  error-emit listener (axis 1) and the dev trace surface. `error-kw` is
  `:rf.error/sub-input-fn-exception` or `:rf.error/sub-input-fn-bad-return`;
  `where` is `:reactive` or `:compute-sub`. `e` is the thrown exception —
  for the bad-return case it is the tagged `:rf.error/sub-input-fn-bad-return`
  ex-info whose `ex-data` carries `:returned` / `:reason`."
  [error-kw query-id query-v frame-id e where]
  (let [data        (ex-data e)
        bad-return? (= :rf.error/sub-input-fn-bad-return error-kw)
        msg         #?(:clj (.getMessage ^Throwable e) :cljs (.-message e))]
    ;; Both channels via the shared helper (rf2-c4oycd): axis 1 the always-on
    ;; listener (survives prod elision), axis 2 the dev trace (DCEs under
    ;; `:advanced` + `goog.DEBUG=false`). For the bad-return case there is no
    ;; genuine exception to ship (the throw is just our tagged carrier), so the
    ;; listener gets nil; the exception is meaningful only for the input-fn-threw
    ;; case. Reached via the `:error-emit/emit-error-both` hook (subs cannot
    ;; static-require error-emit — load cycle). `elapsed-ms 0`.
    (when-let [emit-error-both!
               (late-bind/get-fn-cached :error-emit/emit-error-both)]
      (emit-error-both!
        error-kw
        query-v                       ;; attempted query-vector (as :event)
        query-id                      ;; sub-id (as :event-id)
        frame-id
        (when-not bad-return? e)      ;; exception (only the input-fn-threw case)
        0                             ;; elapsed-ms
        (interop/now-ms)             ;; time
        (if bad-return?
          {:rf.sub/id      query-id
           :rf.sub/query-v query-v
           :where          where
           :returned       (:returned data)
           :reason         (:reason data)
           :frame          frame-id
           :recovery       :replaced-with-default}
          {:rf.sub/id         query-id
           :rf.sub/query-v    query-v
           :where             where
           :exception         e
           :exception-message msg
           :reason            (str "Subscription `" query-id
                                   "` input-fn threw while "
                                   "materializing: " msg
                                   ". Recovering to nil.")
           :frame             frame-id
           :recovery          :replaced-with-default})))))

(defn- produce-input-queries-or-emit!
  "Run [[produce-input-queries]] inside the shared try/produce/catch/
  discriminate-bad-return wrapper both the reactive cache path and the
  `compute-sub` path used identically (rf2-6zfzxy). Returns `[input-qs
  input-error?]`: `[(produce-input-queries sub-meta query-v) false]` on
  success, or — when the parametric `input-fn` throws OR returns a bad shape
  (the tagged `:rf.error/sub-input-fn-bad-return` ex-info from
  `normalize-sub-inputs`) — emits LOUDLY via [[emit-sub-input-fn-error!]] and
  returns `[recovery-qs true]`. The exception is discriminated into
  `:rf.error/sub-input-fn-bad-return` vs `:rf.error/sub-input-fn-exception` by
  its `:rf.error/id`, exactly as both call sites did.

  `where` (`:reactive` / `:compute-sub`) tags the emission site; `frame-id` is
  the reactive path's owning frame (nil on the pure `compute-sub` path).
  `recovery-qs` is the input-qs the caller wants on failure (`[]` reactive / nil
  compute-sub — both yield `(count …) 0`, and the `input-error?` flag
  short-circuits every downstream use, so the choice is cosmetic; preserved per
  call site to keep the collapse byte-identical). Layer-1 / `:static` never
  throw here — only the parametric `input-fn` can."
  [sub-meta query-v query-id frame-id where recovery-qs]
  (try
    [(produce-input-queries sub-meta query-v) false]
    (catch #?(:clj Throwable :cljs :default) e
      (let [bad-return? (= :rf.error/sub-input-fn-bad-return
                           (:rf.error/id (ex-data e)))]
        (emit-sub-input-fn-error! (if bad-return?
                                    :rf.error/sub-input-fn-bad-return
                                    :rf.error/sub-input-fn-exception)
                                  query-id query-v frame-id e where)
        [recovery-qs true]))))

(defn- compute-and-cache!
  "Build the reaction for query-v and cache it. Per Spec 006 §Lookup
  algorithm: recursively resolve the input query-vectors (the literal
  `:<-` list for `:static`, or the realized `(input-fn query-v)` result
  for `:parametric`), build the reaction, attach on-dispose to evict the
  cache slot.

  The compute fn handed to the substrate adapter is built in two
  layers, each named:

    - `make-layer-1-memoised-body` / `make-layer-n-single-input-memoised-body` /
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
                        ;; Per Spec 009 §Error catalogue (`:rf.error/no-such-sub`
                        ;; tags `:rf.sub/id` / `:unresolved-input` /
                        ;; `:resolved-inputs`): the unregistered sub is the one
                        ;; being subscribed here (`query-id`); `:unresolved-input`
                        ;; carries the full query-vector that failed to resolve
                        ;; and `:resolved-inputs` is empty — the miss is detected
                        ;; on `sub-meta` lookup, before any `:<-` input is
                        ;; resolved. (rf2-agpv2.3 — aligns the emit tag-shape to
                        ;; Spec 009; recovery is unchanged: nil-yielding reaction,
                        ;; not cached.)
                        ;;
                        ;; Per rf2-2hvga (= B / widen): fan out through the
                        ;; always-on error-emit listener (surface #4) so a
                        ;; subscribe to a never-registered sub survives
                        ;; `:advanced` + `goog.DEBUG=false` and reaches off-box
                        ;; shippers — a production-meaningful runtime error.
                        ;; An invalid op whose recovery is the built-in
                        ;; `:replaced-with-default`. The `:frame`-stampable
                        ;; record carries `frame-id` + the attempted `query-v`
                        ;; for 7d30s + shipper attribution.
                        ;;
                        ;; Both channels via the shared helper (rf2-c4oycd):
                        ;; axis 1 the always-on listener (survives prod elision),
                        ;; axis 2 the dev trace (DCEs under `:advanced` +
                        ;; `goog.DEBUG=false`). No exception — invalid op;
                        ;; `elapsed-ms 0`. Reached via the
                        ;; `:error-emit/emit-error-both` hook (subs cannot
                        ;; static-require error-emit — load cycle).
                        (when-let [emit-error-both!
                                   (late-bind/get-fn-cached :error-emit/emit-error-both)]
                          (emit-error-both!
                            :rf.error/no-such-sub
                            query-v               ;; attempted query-vector (as :event)
                            query-id              ;; sub-id (as :event-id)
                            frame-id
                            nil                   ;; no exception — invalid op
                            0                     ;; elapsed-ms
                            (interop/now-ms)      ;; time
                            {:rf.sub/id        query-id
                             :unresolved-input query-v
                             :resolved-inputs  []
                             :frame            frame-id})))
        body-fn       (:handler-fn sub-meta)
        ;; A `:db` layer-1 sub specifically (the narrow single-source kind whose
        ;; input is the app-db container) — used by the dev-only output-marks
        ;; resolve + the not-cached symmetric-input-release guard below.
        ;; `:runtime-db` / `:frame-state` are the OTHER single-source kinds (the
        ;; `single-source-input-kinds` set drives the shared memoised-body +
        ;; container-lookup decisions); EP-0001 (rf2-vzld77) made `:runtime-db`
        ;; a layer-1-shaped framework reader over the frame's runtime-db
        ;; projection, and EP-0016 D3 slice 3 (rf2-616xa6) made `:frame-state`
        ;; a single-source reader over the WHOLE frame-state value (both
        ;; partitions, so the body re-runs on EITHER an app-db or a runtime-db
        ;; change).
        layer-1?      (= :db (:input-kind sub-meta))
        ;; Produce the realized input query-vectors for THIS concrete
        ;; cache entry from the sub's input producer (Spec 006
        ;; §Subscription input producers): `[]` for layer-1, the literal
        ;; `:<-` list for `:static`, or `(input-fn query-v)` (validated by
        ;; `normalize-sub-inputs`) for `:parametric`. The `input-fn` runs
        ;; ONCE here at materialization — NOT on the hot recompute path —
        ;; so the entry's topology is FIXED for its lifetime (the
        ;; fixed-topology-per-cache-entry invariant). On a parametric
        ;; failure (`input-fn` throws, or returns a non-vector-of-query-
        ;; vectors) we emit LOUDLY and recover to a nil-yielding reaction
        ;; that is NOT cached (so a later registration fix re-materializes
        ;; cleanly) — the same recovery posture as a no-such-sub miss.
        ;; `input-error?` flags that recovery so the cache + dispose wiring
        ;; below treats the entry like an uncached miss. (Layer-1 / static
        ;; never throw here — only the parametric `input-fn` can.)
        [input-qs input-error?]
        (when sub-meta
          (produce-input-queries-or-emit! sub-meta query-v query-id frame-id :reactive []))
        ;; Resolve inputs: layer-1 → frame's app-db; layer-2+ → recursive
        ;; subs over the realized input query-vectors. A failed parametric
        ;; production yields an empty `input-qs` and a constant-nil body.
        ;; Single-source readers (`:db` / `:runtime-db` / `:frame-state`) watch
        ;; ONE resolved container; the `single-source-container-for` lookup
        ;; (rf2-6zfzxy) picks the app-db / runtime-db / whole-frame-state
        ;; container — the `:frame-state` container propagates on a change to
        ;; EITHER partition (EP-0016 D3 slice 3). Layer-2+ subscribes each
        ;; realized input.
        inputs        (cond
                        (single-source-container-for (:input-kind sub-meta))
                        [((single-source-container-for (:input-kind sub-meta)) frame-id)]
                        input-error? []
                        :else       (mapv (fn [input-q] (subscribe frame-id input-q)) input-qs))
        parametric?   (= :parametric (:input-kind sub-meta))
        memoised-body (cond
                        input-error?
                        ;; Recovery body: a constant nil reaction (Spec 009
                        ;; §Error contract `:replaced-with-default`). Never
                        ;; cached (see `input-error?` branches below).
                        (constantly nil)

                        ;; Layer-1 (`:db`), `:runtime-db`, and `:frame-state`
                        ;; are all single-source readers — same fixed-arity-1
                        ;; memoised body; the signal source resolved above
                        ;; (`inputs`) is the only difference (app-db projection
                        ;; vs runtime-db projection vs the whole frame-state
                        ;; container). A `:frame-state` body receives the full
                        ;; frame-state value `{:rf.db/app … :rf.db/runtime …}`.
                        (single-source-input-kinds (:input-kind sub-meta))
                        (subs-memo/make-layer-1-memoised-body
                          body-fn query-id query-v frame-id sub-meta)

                        ;; PARAMETRIC subs (any realized input count,
                        ;; including 1) deliver a VECTOR of input values to
                        ;; the computation fn — `(fn [[a b] q] ...)` — per the
                        ;; EP §Single input contract. Route through the
                        ;; varargs layer-n wrapper with `vector-inputs? true`
                        ;; so even a single realized parametric input is
                        ;; delivered as `[value]`, NOT the bare-value `:<-`
                        ;; convention.
                        parametric?
                        (subs-memo/make-layer-n-memoised-body
                          body-fn query-id query-v frame-id input-qs sub-meta true)

                        ;; Static `:<-` with a single input — dominant shape
                        ;; per rf2-v1nu0; specialise to fixed-arity-1 for
                        ;; parity with layer-1 (rf2-0y2bp). Delivers the bare
                        ;; value (the v1 `:<-` single-input convention).
                        (= 1 (count input-qs))
                        (subs-memo/make-layer-n-single-input-memoised-body
                          body-fn query-id query-v frame-id input-qs sub-meta)
                        :else
                        (subs-memo/make-layer-n-memoised-body
                          body-fn query-id query-v frame-id input-qs sub-meta))
        reaction      (adapter/make-derived-value inputs memoised-body)
        ;; A parametric input-production failure recovers to a nil-yielding
        ;; reaction that is NOT cached (mirroring the no-such-sub miss):
        ;; suppress the cache store + dispose wiring so a later fix
        ;; re-materializes cleanly on the next subscribe.
        sub-meta      (when-not input-error? sub-meta)
        input-signals input-qs
        cache         (:sub-cache (frame/frame frame-id))
        k             (cache-key query-v)]
    ;; Per Spec 015 §App-db → subs / §Subs → fx propagation: when this
    ;; sub is being built, resolve whether its output should be marked
    ;; sensitive/large for downstream emit-time consultation. Honours
    ;; the `:rf.egress/output-sensitivity` declassification enum (sensitive
    ;; axis) and the `:large?` whole-output override on the sub's
    ;; registration meta. Late-bound — when the marks
    ;; artefact is absent, this is a silent no-op. Gated by debug so
    ;; production builds DCE the lookup.
    ;; INVARIANT: this `mark!` write only happens under `debug-enabled?`, so the
    ;; sub-output marks table is EMPTY in production — any future egress consumer
    ;; reading it MUST be dev-gated too, or it reads empty and fails open.
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
    ;; rf2-agpv2.2 — symmetric input release on the not-cached path.
    ;; A layer-2+ build already subscribed each `:<-` input above (bumping
    ;; their ref-counts), but the dispose-wiring that releases them lives
    ;; ONLY inside the `(when (and cache sub-meta) …)` branch. If the frame
    ;; was destroyed (or its container torn down) BETWEEN `subscribe`'s
    ;; frame-record resolution and this re-resolution, `cache` is now nil:
    ;; the reaction is built and returned but never cached and never
    ;; dispose-wired, so without this branch nothing would ever call
    ;; `unsubscribe` for those inputs — a monotonic ref-count leak until
    ;; `clear-sub-cache!`. Release them here so the input ref-count stays
    ;; symmetric with the bumps. Layer-1 has no subscribed inputs (its
    ;; input is the app-db container, not a subscribe), and the
    ;; no-such-sub miss (`sub-meta` nil) has no `:<-` inputs, so this
    ;; only fires for a layer-2+ reaction that escaped caching.
    (when (and (not (and cache sub-meta))
               (not layer-1?)
               (seq input-signals))
      (doseq [input-q input-signals]
        (try (unsubscribe frame-id input-q)
             (catch #?(:clj Throwable :cljs :default) _ nil))))
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
     covers this surface identically to `:sub-return`.

     `frame-id` (rf2-7d30s) is the subscribing frame — stamped onto the
     failure trace so `re-frame.epoch.capture/capture-event!` (which
     buffers only frame-tagged traces) attributes the override-validation
     failure to that frame's epoch, mirroring `:sub-return`'s `:frame`
     stamp (validate.cljc `validate-sub!` 5-arity). Without it the trace
     is invisible to the per-frame epoch / Xray Schema-timeline lens."
     [value query-v sub-meta frame-id]
     (let [schema (:schema sub-meta)]
       (if (and schema (some? sub-meta))
         (if-let [validate (late-bind/get-fn-cached :schemas/validate-with-registered-fn)]
           (if (try (validate schema value)
                    ;; A throwing validator must not crash the render; treat
                    ;; it as a pass (mirrors `subs.memo/maybe-validate-sub!`).
                    (catch :default _ true))
             value
             (let [sub-id  (first query-v)
                   explain (when-let [exp (late-bind/get-fn-cached
                                            :schemas/explain-with-registered-fn)]
                             (try (exp schema value) (catch :default _ nil)))
                   ;; rf2-o69h5 — route the value-bearing slots (`:value` /
                   ;; `:received` / `:explain` / `:rf.sub/query-v`) through the
                   ;; SHARED schema-aware redaction seam so a `:sub-override`
                   ;; on a `:sensitive?`-marked sub schema scrubs identically
                   ;; to the regular `:sub-return` path (which redacts via
                   ;; `validate-sub!`). This override path bypasses
                   ;; `validate-sub!`, so without the seam it leaked the
                   ;; failing value verbatim — the a5kzs#1 class on the
                   ;; `:sub-override` surface.
                   redact  (late-bind/get-fn-cached :schemas/redact-validation-tags)
                   tags    (cond-> {:where          :sub-override
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
                                    :recovery       :replaced-with-default}
                             frame-id (assoc :frame frame-id))]
               (trace/emit-error! :rf.error/schema-validation-failure
                                  (cond-> tags
                                    redact (->> (redact schema))))
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
     [frame-id query-v]
     (when-let [resolve-override (late-bind/get-fn :subs/resolve-sub-override)]
       (when-let [hit (resolve-override query-v)]
         (let [v        (first hit)
               sub-meta (registrar/lookup :sub (first query-v))
               v*       (maybe-validate-sub-override! v query-v sub-meta frame-id)]
           (adapter/make-derived-value [] (constantly v*)))))))

(defn subscribe
  "Per Spec 006 §Lookup algorithm. Returns the reaction for query-v;
  build-and-cache on miss; reuse on hit. The 1-arity ambient form
  resolves the active frame through the carried-invariant scope/hold
  chain via `frame/require-current-frame!` (EP-0002): a `with-frame` /
  frame-provider scope (the `:adapter/current-frame` late-bind hook,
  rf2-d4sf) or a captured `*current-frame*` stamp. There is NO
  `:rf/default` floor — a subscribe issued under no established scope
  raises `:rf.error/no-frame-context` rather than silently reading the
  wrong frame. Pass the 2-arity `(subscribe frame-id query-v)` to read a
  named frame from outside any scope (async callbacks, tools, tests, SSR).

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
   ;; EP-0002 §Subscriptions And Read Helpers — the carried-invariant
   ;; read. The 1-arity ambient form resolves the frame through the
   ;; scope/hold chain via `require-current-frame!`: a `with-frame` /
   ;; frame-provider scope (`resolve-current-frame`) or a captured
   ;; `*current-frame*` stamp. There is NO `:rf/default` floor — a
   ;; subscribe issued under no established scope (no with-frame, no
   ;; enclosing provider, no carried stamp) raises the always-on
   ;; `:rf.error/no-frame-context` (with capture-site ancestry) rather
   ;; than silently reading the wrong frame's app-db. The `extra` threads
   ;; the sub-id into the error payload's `:event-id` slot so a frameless
   ;; subscribe's error is attributed to the query it carried.
   (subscribe (frame/require-current-frame!
                :subscribe
                {:where    're-frame.subs/subscribe
                 :event-id (first query-v)})
              query-v))
  ([frame-id query-v]
   ;; EP-0023 (rf2-32siq3.32): the 2-arity target may be a frame-id KEYWORD or a
   ;; live frame OBJECT (`(rf/subscribe frame query-v)` — `rf/make-frame`'s
   ;; return value). Normalize an object to its runnable-id ADDRESS so the
   ;; sub-cache lookup (`(frame/frame frame-id)`), the realm-registrar binding,
   ;; the override seam, and the error payloads all key the backing record
   ;; unchanged; a keyword passes through. The generation-resolution seam
   ;; (`frame-resolution-target`) then re-resolves the frame's image from this id
   ;; via the live-frame registry — so an object target builds against its OWN
   ;; image, byte-identical to the keyword form. Mirrors the dispatch-side
   ;; normalization in `re-frame.router/build-envelope`.
   (let [frame-id (frame/frame-target->id frame-id)]
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
   ;; EP-0013 step 4 (rf2-a15n62): route subscription resolution through the
   ;; owning frame's realm registrar. `compute-and-cache!` resolves the sub
   ;; handler via `registrar/lookup :sub` (and `resolve-sub-override` reads sub
   ;; meta), so the realm-registrar binding must cover the BUILD path. The
   ;; binding is established ONCE here and covers the recursive layer-2+ input
   ;; `subscribe` calls inside `compute-and-cache!` (each re-derives the SAME
   ;; realm registrar from the same frame-id — idempotent). The reaction's body
   ;; is closed over the resolved handler at materialization, so a later
   ;; reactive recompute needs no binding. A non-default-realm frame pays one
   ;; binding per build (cache MISS); a cache HIT skips this entirely. The
   ;; default-realm frame takes the no-binding fast path
   ;; (`frame/realm-registrar-for-frame` returns nil) — byte-identical to the
   ;; pre-realm subscribe path. DERIVED from the carried frame-id, never ambient
   ;; (EP-0002).
   ;;
   ;; EP-0023 (rf2-uejnt3): ALSO route the BUILD path through the target frame's
   ;; resolved IMAGE generation when `frame-id` names an image-loaded frame —
   ;; NESTED inside the realm binding so `registrar/lookup :sub` (and the layer-2+
   ;; input `subscribe` calls inside `compute-and-cache!`) resolve the sub handler
   ;; through the frame's OWN image (rf2-32siq3.9's seam, invoked at the live
   ;; subscribe entry). Two frames running DIFFERENT images thus build the same
   ;; sub-id against their own image's descriptor. A target naming no image-loaded
   ;; frame (every realm-only / single-realm frame) derives no generation, so this
   ;; binds nothing and the build resolves through the registrar atom exactly as
   ;; before (absence-is-default). DERIVED from the carried target (EP-0002).
   (frame/call-with-frame-realm-registrar
     (frame/frame frame-id)
     (fn []
   (live-frame/call-with-frame-resolution
     (live-frame/frame-resolution-target frame-id)
     (fn []
   (or
     #?(:cljs
        (when interop/debug-enabled?
          (resolve-sub-override frame-id query-v)))
     (let [frame-record (frame/frame frame-id)]
       (cond
         ;; Missing or destroyed frame: emit + return nil rather than
         ;; deref-ing nil and exploding. Per rf2-2hvga (= B + recover-but-
         ;; emit): subscribe RECOVERS (returns nil) AND emits a
         ;; production-survivable `:rf.error/frame-destroyed` through the
         ;; always-on error-emit listener (surface #4) so a subscribe
         ;; during a teardown / hot-reload race recovers safely while a
         ;; real use-after-destroy bug stays observable on the
         ;; production-watched stream. Reached via the
         ;; `:error-emit/dispatch-on-error` late-bind hook (subs cannot
         ;; static-require `re-frame.error-emit` — load cycle). The
         ;; `:frame`-stampable record carries `frame-id` + the attempted
         ;; `query-v` (as `:event`) for 7d30s + shipper attribution.
         (nil? frame-record)
         (do
           ;; Both channels via the shared helper (rf2-c4oycd): axis 1 the
           ;; always-on listener (survives prod elision), axis 2 the dev trace
           ;; (DCEs under `:advanced` + `goog.DEBUG=false`). No exception —
           ;; invalid op; `elapsed-ms 0`. Reached via the
           ;; `:error-emit/emit-error-both` hook (subs cannot static-require
           ;; error-emit — load cycle).
           (when-let [emit-error-both!
                      (late-bind/get-fn-cached :error-emit/emit-error-both)]
             (emit-error-both!
               :rf.error/frame-destroyed
               query-v                       ;; attempted query-vector (as :event)
               (first query-v)               ;; sub-id (as :event-id)
               frame-id
               nil                           ;; no exception — invalid op
               0                             ;; elapsed-ms
               (interop/now-ms)             ;; time
               {:frame    frame-id
                :query-v  query-v
                :recovery :replaced-with-default}))
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
             (compute-and-cache! frame-id query-v))))))))))))) ;; close fn + call-with-frame-resolution (rf2-uejnt3) + fn + call-with-frame-realm-registrar (rf2-a15n62) + normalize-target let (rf2-32siq3.32)

(defn subscribe-once
  "One-shot read of a sub's current value. Subscribes, derefs, then
  unsubscribes — does NOT retain a reference on the cache entry and
  does NOT register the caller for reactive re-render.

  Use in tests, REPL sessions, machine-action bodies, SSR builders,
  or any non-reactive consumer that wants the value right now. For
  reactive consumers (Reagent views, tools holding the reaction) use
  `subscribe`. For event handlers prefer declaring a sub-reading cofx via
  `:rf.cofx/requires` (EP-0017) so the read is part of the cofx contract
  rather than a side-effect inside the handler body.

  Per rf2-cmfln (Spec 006 §Reference counting and disposal): the
  teardown `unsubscribe` runs synchronously on the 1 → 0 transition,
  so the one-shot read's whole lifetime — subscribe, deref, dispose —
  completes in the calling tick. Concurrent reactive subscribers keep
  the slot alive via ref-count and are unaffected — `subscribe-once`'s
  decrement only drives the eviction when it owned the last reference.

  See also: `subscribe`, `unsubscribe`, `compute-sub`, `reg-cofx`.

  EP-0002: the 1-arity ambient form resolves the frame through the
  scope/hold chain via `frame/require-current-frame!` — a one-shot read
  under no established scope raises `:rf.error/no-frame-context`, never a
  `:rf/default` floor. Pass the 2-arity form to read a named frame from
  outside any scope."
  ([query-v]
   (subscribe-once (frame/require-current-frame!
                     :subscribe-once
                     {:where    're-frame.subs/subscribe-once
                      :event-id (first query-v)})
                   query-v))
  ([frame-id query-v]
   (let [reaction (subscribe frame-id query-v)
         v        (when reaction @reaction)]
     (unsubscribe frame-id query-v)
     v)))

(defn- frame-state-value?
  "True when `v` is a frame-state projection map carrying at least one
  partition key (`:rf.db/app` / `:rf.db/runtime`). EP-0001 (rf2-vzld77):
  `compute-sub` accepts EITHER a bare app-db map (the historical form) or a
  full frame-state value, so a single call can resolve both `:db` and
  `:runtime-db` subs in one dependency graph against the coherent snapshot."
  [v]
  (and (map? v)
       (or (contains? v :rf.db/app)
           (contains? v :rf.db/runtime))))

(defn- partition-value-for-sub
  "Resolve the single-source value a layer-1-shaped sub's body should
  receive from the value supplied to `compute-sub`. When `supplied` is a
  frame-state value (`{:rf.db/app … :rf.db/runtime …}`), extract the
  partition the sub-kind reads (`:runtime-db` → `:rf.db/runtime`, `:db` →
  `:rf.db/app`). Otherwise `supplied` is a bare partition map and is passed
  through unchanged — a `:db` sub gets the app-db it was always handed, and a
  `:runtime-db` sub gets whatever the caller supplied (which, for a
  runtime-db sub, should be the runtime-db value)."
  [supplied input-kind]
  (if (frame-state-value? supplied)
    (case input-kind
      :runtime-db (get supplied :rf.db/runtime)
      ;; EP-0016 D3 slice 3: a `:frame-state` sub's body wants the WHOLE
      ;; frame-state value (both partitions) — pass it through unextracted.
      :frame-state supplied
      (get supplied :rf.db/app))
    supplied))

(defn- compute-sub*
  "Recursive worker for `compute-sub`. Threads a per-call `memo` atom
  (`{query-v -> value}`) through the `:<-` recursion so each DISTINCT
  sub in the dependency graph computes — and emits its `:rf.sub/run`
  trace — at most once per top-level `compute-sub` call (rf2-gyxm3).

  A memo HIT short-circuits to the pinned value: no body re-run, no
  duplicate `:rf.sub/run` emission. Memoising by the full `query-v`
  (id + args) is value-identical to re-computing because, for a fixed
  `db`, a sub's value is a pure function of `db` + its inputs.

  EP-0001 (rf2-vzld77): `db` may be a bare app-db map OR a full frame-state
  value. A `:runtime-db` sub's body receives the runtime-db partition (Spec
  002 §Subscriptions read the partition they belong to); the partition is
  resolved per-sub via `partition-value-for-sub`."
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
          (let [body-fn  (:handler-fn meta)
                ;; EP-0001 (rf2-vzld77): `:db` and `:runtime-db` are both
                ;; single-source readers — `compute-sub` passes the supplied
                ;; value straight to the body for either. For a `:runtime-db`
                ;; sub the caller supplies the runtime-db value (or a
                ;; frame-state value, from which `compute-sub` extracts the
                ;; right partition — see below).
                layer-1? (single-source-input-kinds (:input-kind meta))
                ;; Produce the realized input query-vectors from the sub's
                ;; input producer — the SAME three-mode model as the
                ;; reactive cache path (Spec 006 §Subscription input
                ;; producers / Spec 008 §`compute-sub` algorithm). For a
                ;; parametric sub the `input-fn` runs here (pure over
                ;; `query-v`); `normalize-sub-inputs` enforces the
                ;; vector-of-query-vectors grammar. This keeps `compute-sub`
                ;; pure + JVM-runnable: the `input-fn` returns query-vectors
                ;; (data), never live reactions. On a parametric production
                ;; failure (`input-fn` throws / bad return) we emit LOUDLY
                ;; with `:where :compute-sub` and recover this sub to nil
                ;; (a bad return is NEVER silently treated as no inputs).
                [input-qs input-error?]
                (produce-input-queries-or-emit! meta query-v query-id nil :compute-sub nil)
                ;; Bind n once — `(empty? input-qs)` then `(= 1 (count input-qs))`
                ;; counted twice on the multi-input path (rf2-r1rma).
                n       (count input-qs)
                ;; Per Spec 009 §Error contract — body throws emit
                ;; :rf.error/sub-exception and recover to nil. Mirrors
                ;; `subs.memo/validate-and-trace` (the reactive sibling), so
                ;; SSR + JVM-runnable consumers driving subs through
                ;; `compute-sub` get the same debuggable signal the reactive
                ;; path produces. The `:where :compute-sub` tag distinguishes
                ;; this emission site from the reactive memo path; the rest of
                ;; the envelope mirrors the sibling exactly (rf2-cos61).
                v       (if input-error?
                          ;; Parametric input production failed — recover the
                          ;; whole sub to nil (already emitted above).
                          nil
                          (try
                          (let [parametric? (= :parametric (:input-kind meta))
                                raw (cond
                                      ;; Layer-1-shaped (`:db` / `:runtime-db` /
                                      ;; `:frame-state` — see the `layer-1?` set
                                      ;; above): `:db` reads app-db directly,
                                      ;; `:runtime-db` reads the runtime-db
                                      ;; partition, `:frame-state` reads the
                                      ;; whole frame-state value (both
                                      ;; partitions, EP-0016 D3).
                                      ;; `partition-value-for-sub` extracts the
                                      ;; right slice when `db` is a frame-state
                                      ;; value (rf2-vzld77).
                                      layer-1?
                                      (body-fn (partition-value-for-sub db (:input-kind meta)) query-v)

                                      ;; PARAMETRIC subs deliver a VECTOR of
                                      ;; resolved input values (producer
                                      ;; order) to the computation fn at ANY
                                      ;; count — `(fn [[a] q] ...)` even for a
                                      ;; single input (EP §Single input). This
                                      ;; mirrors the reactive path's
                                      ;; `vector-inputs?` delivery so
                                      ;; `compute-sub` and `subscribe-once`
                                      ;; AGREE for parametric subs.
                                      parametric?
                                      (body-fn (mapv #(compute-sub* % db memo) input-qs) query-v)

                                      ;; Static `:<-` with zero realized
                                      ;; inputs delivers `(body-fn nil
                                      ;; query-v)` — matching the reactive
                                      ;; path's `(empty? input-signals)`
                                      ;; delivery in
                                      ;; `subs.memo/validate-and-trace`.
                                      (zero? n)
                                      (body-fn nil query-v)

                                      ;; Static single `:<-` — bare value.
                                      (= 1 n)
                                      (body-fn (compute-sub* (first input-qs) db memo) query-v)

                                      ;; Static multi `:<-` — vector.
                                      :else
                                      (body-fn (mapv #(compute-sub* % db memo) input-qs) query-v))]
                            ;; rf2-9cm27 — `compute-sub` is the pure testing form
                            ;; (Spec 008 §Testing): a compute against a SUPPLIED db,
                            ;; outside any reactive cascade. No in-flight reaction
                            ;; frame to attribute to, so `frame-id` is nil — the
                            ;; `:where :sub-return` trace from this path is not tied
                            ;; to a per-frame epoch (mirrors the direct-caller
                            ;; 4-arity contract).
                            (subs-memo/maybe-validate-sub! raw query-v query-id meta nil))
                          (catch #?(:clj Throwable :cljs :default) e
                            (let [msg #?(:clj (.getMessage ^Throwable e) :cljs (.-message e))
                                  reason (str "Subscription `" query-id
                                              "` threw while computing: "
                                              msg ". Returning nil.")
                                  ;; The pure `compute-sub` path has no in-flight
                                  ;; reaction frame to attribute to (it computes
                                  ;; against a SUPPLIED db, outside any reactive
                                  ;; cascade — Spec 008 §Testing), so `:frame` is
                                  ;; nil. A `compute-sub`-driven SSR harness that
                                  ;; wants the per-frame 500 projection must use
                                  ;; the reactive `subscribe` path (which knows
                                  ;; its frame) — see rf2-kjf3m.3 notes.
                                  tags  {:failing-id        query-id
                                         :rf.sub/id         query-id
                                         :sub-query         query-v
                                         :frame             nil
                                         :where             :compute-sub
                                         :exception         e
                                         :exception-message msg
                                         :reason            reason
                                         :recovery          :replaced-with-default}]
                              ;; Per rf2-2hvga (= B / widen) — SETTLES rf2-kjf3m.3.
                              ;; The pure `compute-sub` path was previously
                              ;; trace-ONLY: under `interop/debug-enabled? = false`
                              ;; (CLJS `:advanced` + `goog.DEBUG=false`; JVM
                              ;; `-Dre-frame.debug=false`) the `trace/emit-error!`
                              ;; below DCEs / no-ops, so a sub that threw via
                              ;; `compute-sub` recovered to nil with NO always-on
                              ;; emission — the exact fail-open class rf2-vvwmi
                              ;; closed for the REACTIVE path, still open for the
                              ;; compute-sub path. A head fn / JVM render harness
                              ;; that resolves subs via `compute-sub` during SSR
                              ;; could ship a silent 200 with recovered-to-nil
                              ;; broken HTML. Routing through the always-on
                              ;; listener (axis 1 / surface #4) — corpus-wide
                              ;; shippers (Sentry / Datadog) now see the
                              ;; compute-sub throw under production hardening,
                              ;; symmetric with `subs/memo.cljc`'s reactive
                              ;; sibling. NOTE: the per-frame epoch capture +
                              ;; SSR per-frame 500 projection are frame-tagged;
                              ;; the pure `compute-sub` path has no reactive frame
                              ;; (`:frame` nil), so a `compute-sub`-driven SSR
                              ;; harness that wants the per-frame projection MUST
                              ;; resolve subs via the reactive `subscribe` path
                              ;; (which knows its frame). The always-on
                              ;; corpus-wide stream fires regardless — closing the
                              ;; silent-recovery fail-open for off-box monitors.
                              ;;
                              ;; A sub-exception's recovery is the framework's
                              ;; built-in 'return nil'; there is no app-steering
                              ;; recovery policy (rf2-hiqtk8). Reached via the
                              ;; `:error-emit/dispatch-on-error` late-bind hook
                              ;; (subs cannot static-require `re-frame.error-emit`
                              ;; — load cycle). A pure `compute-sub` has no
                              ;; triggering event vector OR reactive frame, so
                              ;; `:frame` is nil; per rf2-bxud9v the failing sub's
                              ;; `query-v` / `query-id` ride `:event` / `:event-id`
                              ;; (mirroring the sub-input-fn path) so the kind-aware
                              ;; error-emit lookup resolves the sub's `:source-coord`
                              ;; under `[:sub query-id]` for off-box shippers.
                              ;; Both channels via the shared helper
                              ;; (rf2-c4oycd): axis 1 the always-on listener
                              ;; (survives prod elision), axis 2 the dev trace
                              ;; (DCEs under `:advanced` + `goog.DEBUG=false`).
                              ;; Reached via the `:error-emit/emit-error-both`
                              ;; hook (subs cannot static-require error-emit —
                              ;; load cycle). `elapsed-ms 0`.
                              (when-let [emit-error-both!
                                         (late-bind/get-fn-cached :error-emit/emit-error-both)]
                                (emit-error-both!
                                  :rf.error/sub-exception
                                  query-v                   ;; failing query-vector (as :event)
                                  query-id                  ;; sub-id (as :event-id) — drives [:sub …] coord lookup
                                  nil                       ;; frame (pure compute — no reactive frame)
                                  e
                                  0                         ;; elapsed-ms
                                  (interop/now-ms)          ;; time
                                  tags)))
                            nil)))]
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

  EP-0001 (rf2-vzld77): `db` may be a bare app-db map (the historical form)
  OR a full frame-state value (`{:rf.db/app … :rf.db/runtime …}`). When a
  frame-state value is supplied, a `:db` sub reads the `:rf.db/app`
  partition and a framework `:runtime-db` sub (e.g. `:rf/machine`) reads the
  `:rf.db/runtime` partition — so a mixed dependency graph computes
  coherently in one call. To compute a framework runtime-db sub on its own,
  pass either the runtime-db value or the frame-state value (use
  `rf/frame-state-value` / `rf/runtime-db-value`).

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
  delegates to `subs-cache/unsubscribe!` after resolving the cache + key.

  EP-0002: the 1-arity ambient form resolves the frame through the
  scope/hold chain via `frame/require-current-frame!` — an unsubscribe
  issued under no established scope raises `:rf.error/no-frame-context`,
  never a `:rf/default` floor. Pass the 2-arity form to release a slot in
  a named frame from outside any scope."
  ([query-v]
   (unsubscribe (frame/require-current-frame!
                  :unsubscribe
                  {:where    're-frame.subs/unsubscribe
                   :event-id (first query-v)})
                query-v))
  ([frame-id query-v]
   ;; EP-0023 (rf2-32siq3.32) / rf2-ts3fuk — frame-target SYMMETRY: the
   ;; 2-arity target may be a frame-id KEYWORD or a live frame OBJECT
   ;; (`rf/make-frame`'s return value), exactly as `subscribe` accepts. A
   ;; subscribe with an object target normalizes through
   ;; `frame/frame-target->id` before keying the sub-cache, so the matching
   ;; teardown MUST normalize through the SAME path or the cache lookup keys
   ;; an unregistered object instead of the runnable-id ADDRESS, silently
   ;; misses the live entry, and the ref-count is never released (the
   ;; asymmetric-targeting bug). Normalizing here makes subscribe-then-
   ;; unsubscribe target the same frame for every supported spelling; a
   ;; keyword passes through unchanged (byte-identical for keyword callers).
   ;; Mirrors `subscribe` (this ns) and `re-frame.router/build-envelope`.
   (let [frame-id (frame/frame-target->id frame-id)]
     (when-let [cache (:sub-cache (frame/frame frame-id))]
       ;; rf2-mrnur — thread `frame-id` through so the `:rf.sub/dispose`
       ;; trace emit at the eviction site carries the canonical `:frame`
       ;; tag.
       (subs-cache/unsubscribe! cache (cache-key query-v) frame-id)))))

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

;; ---- EP-0023 inline-registration lowering (rf2-ffc6s0) --------------------
;;
;; An image's inline `:registrations` `:reg-sub` entry carries the raw
;; computation fn under `:impl`. For the inline sub to COMPUTE through a
;; frame-targeted subscribe, the assembled generation's resolver descriptor
;; must carry the SAME runnable slots `reg-sub` installs — `:handler-fn` +
;; the `:input-kind` / `:input-signals` discriminators the sub-cache reads.
;; The inline tuple `[id metadata body]` carries exactly ONE body fn and no
;; `:<-` chain, so the lowered shape is the layer-1 app-db reader (`:input-kind
;; :db`), the only sub shape expressible inline (a `:<-` static / parametric
;; sub needs the chain / two-fn form `reg-sub` parses, which the inline tuple
;; cannot carry). Closes the EP-0023 §Image Fragments "same runtime descriptor
;; shape" contract for subs. Published via late-bind (image-assembly cannot
;; static-require this ns — subs requires live-frame requires image-assembly).

(defn lower-inline-sub
  "Lower an inline `:reg-sub` descriptor's raw computation fn into the runnable
  layer-1 (`:input-kind :db`) sub slots `reg-sub` installs (`:handler-fn` +
  `:input-kind :db` + empty `:input-signals`). `_meta` is the inline entry's
  metadata map (unused — the descriptor already carries the inline `:metadata`,
  and an inline tuple cannot express the `:<-` chain that would change the
  input kind); `impl` is the raw `(fn [db query-v] …)` computation. Returns
  ONLY the runnable slots so image-assembly merges them onto the descriptor,
  preserving `:impl` + provenance."
  [_meta impl]
  {:handler-fn    impl
   :input-kind    :db
   :input-signals []})

(late-bind/set-fn! :image/lower-inline-sub lower-inline-sub)

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
     (def sub-cache-snapshot subs-tooling/sub-cache-snapshot)
     ;; EP-0014 slice-2: the derivation/process algebra views. The static
     ;; view is JVM-runnable (registrar-derived, partition-agnostic); the
     ;; live cache view is CLJS-only (returns nil on the JVM, like
     ;; `sub-cache-snapshot`). Both live in the tooling sibling so
     ;; production CLJS bundles DCE the bodies.
     (def sub-algebra-view       subs-tooling/sub-algebra-view)
     (def sub-cache-algebra-view subs-tooling/sub-cache-algebra-view)))
