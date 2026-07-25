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

  Disposal is **synchronous on derefer-count → 0** (per
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
            [re-frame.reg-meta :as reg-meta]
            [re-frame.classification :as classification]
            [re-frame.substrate.adapter :as adapter]
            [re-frame.interop :as interop]
            [re-frame.late-bind :as late-bind]
            [re-frame.source-coords :as source-coords]
            [re-frame.subs.cache :as subs-cache]
            [re-frame.subs.memo :as subs-memo]
            ;; The ONE shared Story-override schema-validation primitive
            ;; (rf2-vxgfnd.21) — reused verbatim by the compiled-view path
            ;; (`re-frame.ui.reactive`) so both tiers reject a schema-invalid
            ;; override identically. Reached ONLY inside the CLJS
            ;; `interop/debug-enabled?` gate below, so it DCEs in production.
            [re-frame.subs.override-schema :as override-schema]
            [re-frame.trace :as trace
             #?@(:cljs [:include-macros true])]
            ;; JVM autoload: the tooling sibling has zero
            ;; artefact cost on JVM and the `re-frame.subs/<name>`
            ;; shape works for JVM test fixtures via the alias block
            ;; at the bottom of the file. CLJS deliberately omits this
            ;; require so the tooling sibling stays out of production
            ;; bundles. The reciprocal — `subs.tooling` requires `subs`
            ;; to drive the alias chain — is avoided because subs.tooling
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

(defn normalize-sub-metadata
  "The ONE side-effect-free (with respect to the global registrar) subscription-
  metadata normalization seam, shared by public `reg-sub` and inline-image
  `lower-inline-sub` (EP-0026 — inline registration follows the SAME registrar
  contract, neither looser nor stricter). Given the user `meta` map it:

    - validates retired / unknown registration KEYS under the dev/prod policy
      (`reg-meta/validate-registration-metadata!` — a retired bare `:spec` HARD-
      errors in dev AND prod naming `:schema`; an unknown bare key warns in dev;
      namespaced extension keys pass);
    - validates the `:sensitive` / `:large` CLASSIFICATION declarations fail-loud
      (`classification/validate-classification!` — a malformed declaration raises
      `:rf.error/bad-classification`);
    - strips the production-only pure-documentation fields (`:doc`) exactly as the
      public registrar does (`registrar/strip-pure-documentation` — a no-op in dev).

  Throws on a retired key or malformed classification; otherwise returns the
  normalized meta (doc-stripped in production, omitted-vs-explicit-nil and
  namespaced extension keys preserved). It NEVER writes the global registrar —
  the runtime-owned runnable slots (`:handler-fn`, `:input-kind`,
  `:input-signals`) are installed by the caller AFTER normalization, so metadata
  can never override them. `where-sym` / `id` name the registration in the
  diagnostics."
  [where-sym id meta]
  (reg-meta/validate-registration-metadata! :sub where-sym id meta)
  (classification/validate-classification! :sub meta)
  (registrar/strip-pure-documentation meta))

(defn reg-sub
  "Register a subscription under `id`. The sole sub-registration form
  (per Spec 002 §Subscriptions composing).

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
    ;; rf2-vxgfnd.219 — one shared normalization seam validates the retired /
    ;; unknown registration KEYS (rf2-x68lzo — a retired `:spec` hard-errors,
    ;; unknown bare keys warn) AND the EP-0025 `:sensitive` / `:large`
    ;; classification declarations (fail-loud) AND strips production-only `:doc`,
    ;; so public `reg-sub` and inline-image `lower-inline-sub` accept, reject,
    ;; and elide identical metadata. Classification is DERIVED from the registrar
    ;; meta at read time (no imperative stash); the runtime-owned slots below are
    ;; installed AFTER normalization so metadata cannot override them.
    (registrar/register! :sub id
      (cond-> (assoc (source-coords/merge-coords
                       (normalize-sub-metadata 'rf/reg-sub id meta))
                     :handler-fn    handler-fn
                     :input-kind    input-kind
                     :input-signals (or input-signals []))
        input-fn (assoc :input-fn input-fn)))
    ;; Per Spec 009 §:op-type vocabulary: :rf.sub/create marks subscription
    ;; materialisation — emitted at registration time so tools see when
    ;; the sub becomes available in the registry.
    (trace/emit! :rf.sub :rf.sub/create
                 {:rf.sub/id            id
                  :rf.sub/input-kind    input-kind
                  :rf.sub/input-signals (or input-signals [])})
    id))

(defn- register-single-source-sub!
  "Shared registrar write for the layer-1-SHAPED single-source FRAMEWORK
  reader kinds (`:runtime-db`, `:frame-state`) — the body `reg-runtime-sub`
  and `reg-frame-state-sub` differ on ONLY by the `input-kind` keyword. Each
  reads ONE frame-state container directly (no `:<-` / `input-fn` producer,
  so `:input-signals` is empty `[]`).

  VALIDATE classification fail-loud BEFORE the registrar write; classification
  is DERIVED from the registrar meta at read time, no imperative stash. Emits the
  Spec 009 §`:rf.sub/create` materialisation trace. Returns `id`. The
  always-on, same-artefact validator is called directly, not via a
  late-bind hop."
  [id meta handler-fn input-kind]
  (classification/validate-classification! :sub meta)
  (registrar/register! :sub id
    (assoc (source-coords/merge-coords meta)
           :handler-fn    handler-fn
           :input-kind    input-kind
           :input-signals []))
  (trace/emit! :rf.sub :rf.sub/create
               {:rf.sub/id            id
                :rf.sub/input-kind    input-kind
                :rf.sub/input-signals []})
  id)

(defn reg-runtime-sub
  "Register a FRAMEWORK subscription whose single layer-1-shaped signal
  source is the frame's **runtime-db** projection rather than app-db
  (Spec 002 §Subscriptions read the partition they
  belong to). The handler shape is identical to a layer-1 `reg-sub`
  computation fn — `(fn [runtime-db query-v] …)` — but the `db`-position
  argument is the runtime-db partition value, so framework subsystem subs
  (`:rf/machine`, `:rf.machine/has-tag?`, `[:rf.route/*]`) read their
  durable runtime-db state directly.

  INTERNAL / framework-only: this is NOT a public app-author surface (app
  code reads app-db via `reg-sub` and reaches subsystem state through these
  framework subs). Routed from the subsystem façades (`re-frame.machines`,
  `re-frame.routing`). Accepts an optional leading metadata map, same as
  `reg-sub`. Returns `id`."
  ([id handler-fn] (reg-runtime-sub id {} handler-fn))
  ([id meta handler-fn]
   (register-single-source-sub! id meta handler-fn :runtime-db)))

(defn reg-frame-state-sub
  "Register a FRAMEWORK subscription whose single layer-1-shaped signal
  source is the frame's WHOLE frame-state container — BOTH partitions
  `{:rf.db/app <app-db> :rf.db/runtime <runtime-db>}`. The handler shape
  is identical to a layer-1 `reg-sub`
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
   (register-single-source-sub! id meta handler-fn :frame-state)))

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
  A registration without an `:input-kind` discriminator (defensive
  fallback) resolves its `:input-signals` list as the literal inputs."
  [sub-meta query-v]
  (case (:input-kind sub-meta)
    :db          []
    :runtime-db  []
    ;; A `:frame-state` sub is a single-source reader over the WHOLE
    ;; frame-state value (both partitions); no input producer.
    :frame-state []
    :static     (vec (:input-signals sub-meta))
    :parametric (:queries (normalize-sub-inputs ((:input-fn sub-meta) query-v)))
    ;; Defensive fallback for a registration with no `:input-kind`
    ;; discriminator — treat its `:input-signals` as the literal input
    ;; list. Every `reg-sub` path stamps `:input-kind`.
    (vec (:input-signals sub-meta))))

;; ---- single-source (layer-1-shaped) input-kinds ---------------------------
;;
;; `:db` / `:runtime-db` / `:frame-state` are the layer-1-SHAPED single-source
;; reader kinds: each reads ONE frame-state container directly (no `:<-` /
;; `input-fn` producer, so `produce-input-queries` returns `[]` for all three),
;; and each runs the same fixed-arity-1 memoised body — only the container the
;; reaction watches differs. The set + container map below are the single
;; source of this membership, consulted by the reactive build and the
;; `compute-sub` path alike.

(def ^:private single-source-input-kinds
  "The layer-1-shaped single-source reader kinds: each
  reads ONE frame-state container directly via the same fixed-arity-1 body."
  #{:db :runtime-db :frame-state})

(def ^:private single-source-container-for
  "`input-kind → (fn [frame-id] container)` for the single-source reader kinds.
  The reactive build watches the resolved container as the reaction's lone
  signal source: `:db` the app-db projection, `:runtime-db` the runtime-db
  projection, `:frame-state` the WHOLE frame-state value (both partitions, so
  the body re-runs on a change to EITHER)."
  {:db          frame/app-db-container
   :runtime-db  frame/runtime-db-container
   :frame-state frame/frame-state-container})

(def ^:private single-source-container-slot-for
  "`input-kind → frame-RECORD slot` for the single-source container. Mirrors
  `single-source-container-for`, whose fns re-resolve `frame-id` through the
  registry (`(k (frame id))`) — this reads the slot off an ALREADY-resolved
  record instead. A CAPTURED build (rf2-7w1im) reads its lone signal source off
  the exact incarnation record the outer fence validated, never a same-id
  successor re-resolved by bare id in the post-comparison window."
  {:db          :app-db
   :runtime-db  :runtime-db
   :frame-state :frame-state})

;; ---- the cache ------------------------------------------------------------

(defn- cache-key
  "Identity now; reserved as the chokepoint if cache-key shape changes
  (per Spec 006 §Cache shape — currently the query-vector itself)."
  [query-v]
  query-v)

;; ---- dev-only cache-fragmentation guardrail (rf2-re5a98) ------------------
;;
;; `cache-key` keys the per-frame sub-cache by query-vector identity (`=`),
;; the reserved chokepoint. That is correct — BUT it exposes the standard
;; footgun: an app that subscribes the SAME sub-id every render while passing
;; a query-vector arg that is value-EQUAL but freshly REBUILT each render
;; (a `{…}` map literal, a record, a collection assembled in the render body)
;; mints a DISTINCT cache key every render. The cache fills with value-equal
;; siblings that never reuse — unbounded growth, zero hit-rate — and the bug
;; is silent (the sub still computes the right value, just never from cache).
;;
;; The React-hook `use-subscribe` path already defends its deps-array
;; identity (spine.cljs); subscribe's cache keying did not. This is the
;; missing dev tripwire: a one-shot warning per sub-id when a repeated
;; subscribe carries a non-primitive arg that is `=` to a previously-seen
;; arg but NOT `identical?` to it — the unambiguous "fresh object, same
;; value" signature of cache fragmentation.
;;
;; FALSE-POSITIVE-AVERSE by construction:
;;   - never warns on FIRST use of a sub-id (no prior arg to compare);
;;   - never warns when the arg is `identical?` to the prior one (the GOOD
;;     case — a hoisted / value-stable arg reuses the cache slot);
;;   - never warns when the arg is genuinely `not=` to every prior arg
;;     (legitimately-varying parameters — `[:item 1]`, `[:item 2]`, … —
;;     SHOULD fragment; that is correct keying, not a footgun);
;;   - only inspects NON-PRIMITIVE args (maps / sets / vectors / records /
;;     fns); a keyword / string / number / boolean / nil / symbol arg is
;;     value-stable enough that a re-mint is not the fragmentation hazard.
;; The fresh-but-`=` discriminator is the load-bearing one: only a value-
;; equal-yet-non-identical non-primitive is the clear, actionable signal.
;; (A freshly-built CLOSURE is never `=` to a prior closure, so it reads as
;; a genuinely-varying arg and is intentionally NOT flagged — there is no
;; reliable value-equality signal to distinguish a churned closure from a
;; legitimately-changing one without false positives.)
;;
;; DEV-ONLY: the whole seam is behind `interop/debug-enabled?` so Closure DCE
;; elides it in `:advanced` + `goog.DEBUG=false` (and JVM `-Dre-frame.debug=
;; false`), and the emit rides `trace/emit-error!` (same gate). The dedupe
;; set + last-seen-arg table are dev-only host-side transient state.

(defonce ^:private
  ^{:doc "Dev-only fragmentation-guardrail state. `:last-arg` maps a sub-id
   to the LAST non-primitive arg seen for it (so a repeat subscribe can
   compare `=` / `identical?` against it); `:warned` is the one-shot dedupe
   set of sub-ids already warned about, so a per-render re-subscribe emits
   the warning ONCE per sub-id rather than flooding. Process-wide `defonce`
   (the user-facing warn-once UX is unchanged in production); wiped per-test
   via the canonical `:adapter/clear-warn-once-caches!` chain (enrolled
   below through `late-bind/register-warn-once-clear-fn!`, rf2-re5a98)."}
  fragmenting-arg-state
  (atom {:last-arg {} :warned #{}}))

(defn- clear-fragmenting-arg-warnings!
  "Reset the cache-fragmentation guardrail state (test isolation). Cleared
  per-test by the standard reset-runtime fixture via the chained
  `:adapter/clear-warn-once-caches!` hook so a sibling test's first-
  encounter warning cannot silently swallow a later same-sub-id warning.
  The state is a process-wide `defonce` so production UX is unchanged;
  test-time clearing is the only effect. Returns nil."
  []
  (reset! fragmenting-arg-state {:last-arg {} :warned #{}})
  nil)

;; Enrol the guardrail state into the canonical chained
;; `:adapter/clear-warn-once-caches!` fixture-reset hook via the governance
;; chokepoint `register-warn-once-clear-fn!` (rf2-z79p8 / rf2-re5a98) — NEVER
;; a bare `chain-fn!` (the JVM single-chokepoint governance gate forbids it).
;; The `:arm` / `:armed?` probes seed + detect a sentinel sub-id so the
;; warn-once-clear governance assertion can drive this cache through its
;; arm/fire/assert-empty proof alongside the adapter/views caches.
(late-bind/register-warn-once-clear-fn!
  {:label    :subs/fragmenting-arg-warnings
   :clear-fn clear-fragmenting-arg-warnings!
   :arm      (fn [] (swap! fragmenting-arg-state update :warned conj
                           ::governance-sentinel))
   :armed?   (fn [] (contains? (:warned @fragmenting-arg-state)
                               ::governance-sentinel))})

(defn- fragmentation-candidate-arg
  "The single inspectable arg of `query-v` IFF it is a NON-PRIMITIVE the
  fragmentation guardrail should track — i.e. the query-vector has exactly
  one arg beyond the sub-id (`[:sub-id arg]`) and that arg is a map / set /
  vector / record / fn (a value whose fresh re-mint each render is the
  cache-key-fragmentation hazard). Returns the arg, or nil when there is no
  arg, more than one arg (a multi-arg query-vector is a rarer shape and the
  signal is muddier — keep the guardrail crisp), or the arg is a primitive
  (keyword / string / number / boolean / nil / symbol). nil ⇒ skip."
  [query-v]
  ;; Exactly `[:sub-id arg]`: head + one arg. `(count …)` on a vector is O(1).
  (when (= 2 (count query-v))
    (let [arg (nth query-v 1)]
      (when (and (some? arg)
                 (or (map? arg) (set? arg) (vector? arg) (seq? arg)
                     (record? arg) (fn? arg)))
        arg))))

(defn- maybe-warn-fragmenting-arg!
  "Dev-only heuristic (rf2-re5a98): emit a one-shot `:rf.warning/sub-arg-
  cache-fragmentation` per sub-id when a REPEATED subscribe of the same
  sub-id carries a non-primitive query-vector arg that is value-EQUAL (`=`)
  to the arg seen on the PREVIOUS subscribe of that sub-id but NOT
  `identical?` to it — the unambiguous fresh-object-same-value signature of
  cache-key fragmentation. See the block comment above for the
  false-positive-aversion rationale (first-use, identical-arg, and
  genuinely-varying-arg cases all pass silently). DEV-ONLY: the whole body
  is `interop/debug-enabled?`-gated so it DCEs in production. Returns nil."
  [query-v]
  (when interop/debug-enabled?
    (when-let [arg (fragmentation-candidate-arg query-v)]
      (let [sub-id (first query-v)
            {:keys [last-arg warned]} @fragmenting-arg-state
            prev   (get last-arg sub-id ::absent)
            ;; The clear signal: same logical value, fresh identity. A
            ;; first-use (`::absent`), an `identical?` reuse, or a genuinely
            ;; different value (`not=`) is NOT the footgun.
            fresh-fragment? (and (not= ::absent prev)
                                 (not (identical? prev arg))
                                 (= prev arg))]
        ;; Always record the latest arg so the NEXT subscribe of this sub-id
        ;; compares against the most recent render's arg (the per-render
        ;; churn cadence the footgun runs at).
        (swap! fragmenting-arg-state assoc-in [:last-arg sub-id] arg)
        (when (and fresh-fragment? (not (contains? warned sub-id)))
          (swap! fragmenting-arg-state update :warned conj sub-id)
          (trace/emit-error! :rf.warning/sub-arg-cache-fragmentation
                             {:category       :rf.warning/sub-arg-cache-fragmentation
                              :rf.sub/id      sub-id
                              :rf.sub/query-v query-v
                              :recovery       :ignored
                              :hint
                              (str "subscription " (pr-str sub-id)
                                   " was re-subscribed with a query-vector arg "
                                   "that is value-equal but a FRESH object each "
                                   "time (e.g. a map / collection / record built "
                                   "inline in the render). The sub-cache keys by "
                                   "identity, so every render mints a NEW cache "
                                   "entry: the cache grows unbounded and never "
                                   "reuses. Hoist the arg to a value-stable "
                                   "reference (a let-bound / subscribed / "
                                   "memoised value) so repeated subscribes share "
                                   "one cache slot.")}))))
    nil))

;; Ref-counting, synchronous disposal, hot-reload invalidation, and
;; `clear-sub-cache!` live in `re-frame.subs.cache`. The public surface
;; (`clear-sub-cache!`) is reached through `re-frame.core`'s defalias
;; pointing at `re-frame.subs.cache/*` directly (no facade re-export).

(declare subscribe subscribe-in-frame unsubscribe compute-and-cache!)

;; ---- observation-port acquire recovery channel (rf2-vxgfnd.27) -----------
;;
;; The observation port's `acquire!` is the cache's ref-count ATTACH (Spec 006
;; §The internal observation port). Three build outcomes hand back a NON-NIL but
;; NEVER-CACHED, zero-ref recovery reaction instead of a canonical node: a
;; cyclic entry sub (`compute-and-cache!`'s outermost catch), a parametric
;; `input-fn` failure (`build-and-cache!*`'s `input-error?` escaped-caching
;; branch), and a frame destroyed mid-build (the same branch with `cache` nil).
;; There is no cache node to attach to, so the port must NOT acquire a lying
;; `owned?`-true zero-ref reaction — it re-derives WHICH recovery happened and
;; throws the matching typed error. This out-channel carries that classification
;; from the build sites up to `acquire-cache-reaction!`. It is bound ONLY by the
;; port's acquire path; on the public `subscribe` / `compute-sub` paths it is
;; nil and every recorder call short-circuits, so those paths are byte-identical.

(def ^:dynamic ^:no-doc *acquire-recovery*
  "Port-internal recovery out-channel (rf2-vxgfnd.27) — a `volatile!` bound by
  `acquire-cache-reaction!` around the reactive build, or nil on the public
  subscribe / compute-sub paths. The never-cached recovery sites record their
  classification into it via [[record-acquire-recovery!]]; the port reads it to
  throw the matching typed error rather than acquire a zero-ref recovery reaction."
  nil)

(defn- record-acquire-recovery!
  "Record a never-cached recovery classification for the observation-port
  acquire path — `kind` ∈ `:cycle` / `:input-fn-exception` /
  `:input-fn-bad-return`; `data` carries the typed error id + context the port
  re-throws with. A no-op (channel nil-bound) on the public subscribe /
  compute-sub paths, so this is invisible there. rf2-vxgfnd.27."
  [kind data]
  (when-some [sink *acquire-recovery*]
    (vreset! sink (assoc data :recovery kind)))
  nil)

;; The memo wrappers (`make-layer-1-memoised-body`,
;; `make-layer-n-single-input-memoised-body`, `make-layer-n-memoised-body`) and
;; the trace/perf/validate/recover bracket (`validate-and-trace`,
;; `maybe-validate-sub!`) live in `re-frame.subs.memo`. Per-recompute hot
;; path is the closure body (in-process); only the per-miss constructor
;; call from `compute-and-cache!` below crosses the ns boundary.

;; ---- parametric input-fn error emission -----------------------------------
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
        ;; nil-safe extractor (a thrown non-Error value has no message).
        msg         (error/ex-message-safe e)]
    ;; Both channels via the shared helper: axis 1 the always-on
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
  discriminate-bad-return wrapper that both the reactive cache path and the
  `compute-sub` path use identically. Returns `[input-qs
  input-error?]`: `[(produce-input-queries sub-meta query-v) false]` on
  success, or — when the parametric `input-fn` throws OR returns a bad shape
  (the tagged `:rf.error/sub-input-fn-bad-return` ex-info from
  `normalize-sub-inputs`) — emits LOUDLY via [[emit-sub-input-fn-error!]] and
  returns `[recovery-qs true]`. The exception is discriminated into
  `:rf.error/sub-input-fn-bad-return` vs `:rf.error/sub-input-fn-exception` by
  its `:rf.error/id`.

  `where` (`:reactive` / `:compute-sub`) tags the emission site; `frame-id` is
  the reactive path's owning frame (nil on the pure `compute-sub` path).
  `recovery-qs` is the input-qs the caller wants on failure (`[]` reactive / nil
  compute-sub — both yield `(count …) 0`, and the `input-error?` flag
  short-circuits every downstream use, so the choice is cosmetic). Layer-1 /
  `:static` never throw here — only the parametric `input-fn` can."
  [sub-meta query-v query-id frame-id where recovery-qs]
  (try
    [(produce-input-queries sub-meta query-v) false]
    (catch #?(:clj Throwable :cljs :default) e
      (let [bad-return? (= :rf.error/sub-input-fn-bad-return
                           (:rf.error/id (ex-data e)))
            error-kw    (if bad-return?
                          :rf.error/sub-input-fn-bad-return
                          :rf.error/sub-input-fn-exception)]
        (emit-sub-input-fn-error! error-kw query-id query-v frame-id e where)
        ;; Observation-port acquire path only (rf2-vxgfnd.27): record the
        ;; parametric-failure classification so `acquire!` throws the matching
        ;; typed error instead of acquiring this never-cached recovery reaction.
        ;; `emit-sub-input-fn-error!` ALREADY fanned the always-on record above,
        ;; so the port re-throws the same id WITHOUT a second fan (one record,
        ;; one throw). No-op on the subscribe / compute-sub paths.
        (record-acquire-recovery!
          (if bad-return? :input-fn-bad-return :input-fn-exception)
          {:error-kw error-kw
           :query-v  query-v
           :reason   (str "parametric subscription " (pr-str query-id)
                          " input-fn "
                          (if bad-return?
                            "returned a value outside the input grammar"
                            "threw")
                          " while materializing.")})
        [recovery-qs true]))))

(defn- release-input-ref!
  "Release ONE `:<-` input ref by calling `unsubscribe`, surfacing a
  throw as a dev breadcrumb instead of discarding it silently.

  A layer-2+ reaction's disposal walks `input-signals` and `unsubscribe`s
  each — symmetric with the per-input `subscribe` bumps taken at build
  time. The walk is BEST-EFFORT: one input's `unsubscribe` throwing must
  NOT skip the remaining inputs (a leaked sibling ref-count would compound),
  so the caller keeps looping. Before rf2-is8ov5 the throw was caught and
  dropped (`(catch … _ nil)`), leaving no trace — a ref-count leak from a
  buggy custom-substrate `-dispose` was invisible.

  Per Spec 009 §Observability channels (the `:rf.warning/teardown-hook-
  exception` precedent in `frame/safe-call-hook!`): the throw is swallowed
  to keep teardown best-effort AND a per-input `:rf.warning/sub-input-
  dispose-exception` trace is emitted at its CAUSAL position so the leaked
  release is traceable in long-lived SSR / test / tooling processes. It
  rides the DIAGNOSTIC channel — `trace/emit-error!` sits inside
  `interop/debug-enabled?`, so production CLJS bundles DCE it (the reference
  substrate adapters do not throw here, so the production case is theoretical;
  the gap is observability, not recovery). `:recovery :ignored` — the input
  release is best-effort, exactly as the parent dispose walk continues.

  `where` distinguishes the release sites (`:on-dispose` for the cached
  reaction's on-dispose callback, `:not-cached-release` for the symmetric
  release on the escaped-caching path, `:sub-cycle-unwind` for the earlier
  inputs released when a `:<-` cycle in a non-first input abandons the build —
  rf2-t3cpn3). Returns nil."
  [frame-id input-q where]
  (try
    (unsubscribe frame-id input-q)
    (catch #?(:clj Throwable :cljs :default) ex
      (trace/emit-error! :rf.warning/sub-input-dispose-exception
                         {:category         :rf.warning/sub-input-dispose-exception
                          :frame            frame-id
                          :rf.sub/query-v   input-q
                          :exception        ex
                          :where            where
                          :recovery         :ignored})
      nil)))

;; ---- :<- dependency-cycle guard (rf2-x76af2.24) --------------------------
;;
;; A `:<-` input graph that closes a cycle (`:a :<- [:b]`, `:b :<- [:a]`, or a
;; self-edge `:self :<- [:self]`) recurses `compute-and-cache!` →
;; `subscribe-in-frame` (input) → `compute-and-cache!` … with no build-in-
;; progress marker, and each reaction is cached only AFTER its inputs resolve,
;; so the first subscribe/compute blew the host stack with a RAW
;; StackOverflowError instead of a structured `:rf.error/*` — a fail-loud
;; violation (`subs/cache.cljc`'s `transitive-dependent-closure` already treats
;; cyclic `:<-` graphs as an acknowledged input class, and flows ship a typed
;; `:rf.error/flow-cycle`). The guard tracks a per-thread stack of the query-
;; vectors currently resolving their inputs; a re-entry for a key already on the
;; stack is a cycle, reported as the structured `:rf.error/sub-cycle`
;; (diagnostic channel, mirroring flows' typed cycle) and RECOVERED to a nil-
;; yielding reaction. The marker is held ONLY across input resolution (not the
;; cache-install / collision-retry phase), so a legitimate post-race rebuild of
;; the SAME query-v is never mis-read as a cycle.

(def ^:dynamic *subs-under-construction*
  "Per-thread stack of `[frame-id query-v]` build keys currently resolving their
  `:<-` inputs in `build-and-cache!*` — the reactive `:<-` cycle guard
  (rf2-x76af2.24). A re-entry for a key already on the stack is a dependency
  cycle. Bound only across input resolution, so it is empty at the outermost
  subscribe entry AND during the cache-install phase (the collision-retry
  rebuild therefore never trips the guard)."
  [])

(defn- sub-cycle-path
  "Closing-repeat cycle-id path for a detected reactive `:<-` cycle: the sub-ids
  on the build `stack` from the first occurrence of the re-entered
  `construction-key`, closed by re-entering `query-v`'s id — e.g. `[:a :b :a]`
  for `:a :<- [:b]` / `:b :<- [:a]`, or `[:self :self]` for a self-edge."
  [stack construction-key query-v]
  (-> (into [] (comp (drop-while #(not= construction-key %))
                     (map (fn [[_frame qv]] (first qv))))
            stack)
      (conj (first query-v))))

(defn- sub-cycle-ex
  "The canonical `:rf.error/sub-cycle` sentinel thrown at the detection point to
  unwind the WHOLE partial build (so no half-wired cyclic reaction is cached);
  the outermost `compute-and-cache!` catches it, emits the diagnostic error and
  recovers to nil. Routed through `error/thrown-ex-info` so the message is
  conformant (Spec 009 §The thrown-error shape) even on the unlikely path where
  it escapes the guard."
  [frame-id query-v cycle-path]
  (error/thrown-ex-info
    :rf.error/sub-cycle 're-frame.subs/subscribe
    (str "Subscription `" (first query-v) "` sits on a :<- dependency cycle "
         (pr-str cycle-path) "; a subscription's :<- inputs must form a DAG. "
         "Break the cycle so no sub (transitively) lists itself among its inputs.")
    {:recovery :replaced-with-default
     :extra    {:cycle          cycle-path
                :frame          frame-id
                :rf.sub/query-v query-v}}))

(defn- emit-sub-cycle!
  "Emit the structured `:rf.error/sub-cycle` on the DIAGNOSTIC trace channel
  (mirroring flows' typed `:rf.error/flow-cycle` classification — dev-only,
  DCEs under `:advanced` + `goog.DEBUG=false`). `where` distinguishes the
  reactive `:subscribe` boundary from the pure `:compute-sub` path. `frame-id`
  is nil on the `compute-sub` path (no reactive frame)."
  [frame-id query-v cycle-path where]
  (trace/emit-error! :rf.error/sub-cycle
                     {:category       :rf.error/sub-cycle
                      :frame          frame-id
                      :rf.sub/id      (first query-v)
                      :rf.sub/query-v query-v
                      :cycle          cycle-path
                      :where          where
                      :recovery       :replaced-with-default}))

(defn- compute-sub-cycle-path
  "Closing-repeat cycle-id path from the `compute-sub` per-call `building` stack
  (a vector of query-vectors) for a re-entered `query-v`."
  [building query-v]
  (-> (into [] (comp (drop-while #(not= query-v %)) (map first)) building)
      (conj (first query-v))))

(defn- emit-frame-destroyed-recovery!
  "Fan the always-on + dev-trace `:rf.error/frame-destroyed` recovery signal for a
  subscribe that resolved a missing/destroyed frame — or, for a CAPTURED read, a
  same-id successor whose incarnation differs from the pinned
  `expected-incarnation` (rf2-dlld6 / rf2-7w1im). No exception (an invalid op);
  recovery is `:replaced-with-default` (nil), `elapsed-ms 0`. Reached via the
  `:error-emit/emit-error-both` late-bind hook (subs cannot static-require
  error-emit — load cycle). Shared by BOTH seams of the one exact-incarnation
  captured subscribe operation — the outer `subscribe-in-frame` fence and the
  durable `build-and-cache!*` fence — so a stale capture emits identically
  wherever the supersession is detected.

  This fn is SUBSCRIBE-realm by construction — every call site is a subscribe
  operation (the captured/superseded `build-and-cache!*` fence and the ordinary
  address-directed subscribe to a missing/destroyed frame), so it stamps
  `:op :subscribe` UNCONDITIONALLY (rf2-a2x2w / rf2-alk8a). Two upgrades ride the
  stamp: `error-emit/error-source-coord` resolves the `:source-coord` under the
  EXACT `[:sub id]` realm and NEVER the realm-ambiguous `[:sub]`-then-`[:event]`
  fallback (which, for a sub-id registered only as a same-keyword EVENT, would
  steal that event's coord instead of OMITTING the slot — the mechanism 7xlvt
  fixed for the pre-check seam, extended here to every subscribe fence); and
  `error-emit/raw-identity-query-vector-event?` routes the attempted query vector
  on the `:event` slot VERBATIM as raw IDENTITY (rf2-zwgqe / rf2-alk8a — a query
  vector is identity, not payload) instead of failing closed to `:rf/redacted`
  under the unresolvable frame (rf2-t55hxg.18's fail-closed guards policy-walked
  VALUE slots, which identity slots never consult). `:op` rides BOTH the
  dev-trace tags (axis 2) and the ratified-public always-on record-attrs
  (axis 1), exactly like `router/emit-frame-destroyed!`.

  `route-frame?` (rf2-qjfrw) gates ONLY the EP-0015 frame-owned sink route (the
  capture-realm extension of the rf2-bf0io UI seam). A CAPTURED subscribe whose
  pinned incarnation was SUPERSEDED passes false: its bare `frame-id` may now
  name a live same-id SUCCESSOR B, and A's dead-incarnation failure must not
  land in B's OWN `:observability :errors` sink (frame isolation;
  exact-incarnation attribution). The corpus fan-out (axis 1) and dev trace
  (axis 2) still fire exactly once. An ORDINARY address-directed subscribe to a
  missing/destroyed frame passes true — its bare id names no captured
  incarnation, so it keeps the default route (and, its frame being absent,
  resolves to no sink anyway)."
  [frame-id query-v route-frame?]
  (when-let [emit-error-both!
             (late-bind/get-fn-cached :error-emit/emit-error-both)]
    (emit-error-both!
      :rf.error/frame-destroyed
      query-v                       ;; attempted query-vector (as :event)
      (first query-v)               ;; sub-id (as :event-id)
      frame-id
      nil                           ;; no exception — invalid op
      0                             ;; elapsed-ms
      (interop/now-ms)              ;; time
      {:frame    frame-id
       :query-v  query-v
       :recovery :replaced-with-default
       :op       :subscribe}        ;; dev-trace tags (axis 2)
      {:op :subscribe}              ;; always-on record realm attribution (axis 1)
      route-frame?))                ;; suppress the frame-owned route for a dead captured incarnation (rf2-qjfrw)
  ;; RECOVER to nil (the `:replaced-with-default` value the subscribe surfaces),
  ;; independent of the emit hook's own return.
  nil)

(defn- build-and-cache!*
  "Build the reaction for query-v and cache it. Per Spec 006 §Lookup
  algorithm: recursively resolve the input query-vectors (the literal
  `:<-` list for `:static`, or the realized `(input-fn query-v)` result
  for `:parametric`), build the reaction, attach on-dispose to evict the
  cache slot.

  The materialisation worker behind the cycle-guarding `compute-and-cache!`
  entry (rf2-x76af2.24): it pushes the per-thread under-construction marker
  ONLY across its input resolution, so a re-entrant `:<-` cycle is detected by
  the entry while the legitimate collision-retry rebuild (which runs after the
  marker is popped) is not.

  The compute fn handed to the substrate adapter is built in two
  layers, each named:

    - `make-layer-1-memoised-body` / `make-layer-n-single-input-memoised-body` /
      `make-layer-n-memoised-body` — Spec 006 §No-op via value
      equality. Wraps the user's body in a `=`-skipping
      memo. The layer-1 form is fixed-arity-1 and compares the db
      scalar directly (avoids per-recompute varargs-seq allocation).
      Layer-2 with a single `:<-` input gets the same fixed-arity-1
      treatment (the dominant layer-2 shape). Layer-2+ with ≥2 inputs
      uses the vec-of-inputs varargs shape.
    - `validate-and-trace`  — Spec 009 :rf.sub/run trace emit, perf bracket,
      Spec 010 step 6 validation, error contract
      (`:replaced-with-default` on throw).

  Per Spec 006 §What happens when a sub references an unknown sub: when
  the registrar lookup misses, emit `:rf.error/no-such-sub` and build a
  nil-yielding reaction, but DO NOT store it in the cache. The miss is
  transient — a later registration (boot order, lazy load) must let the
  next subscribe build a fresh reaction against the real body. We
  achieve this by branching here on nil meta.

  `expected-incarnation` (rf2-7w1im) is the EXACT incarnation token a captured
  subscribe pinned, threaded through from `subscribe-in-frame`. It makes the
  DURABLE build one exact-incarnation operation: the frame record is resolved
  ONCE here and, when the token is non-nil, VALIDATED against it up front — a
  same-id successor installed in the window between the outer `subscribe-in-frame`
  comparison and this build recover-but-emits `:rf.error/frame-destroyed` and
  returns nil rather than reading the successor's container, recursively
  resolving inputs in it, or installing/adopting a reaction in its sub-cache. The
  validated record then feeds the container read, the recursive input subscribes,
  and the cache write — so nothing re-resolves the bare id back to the successor.
  A nil `expected-incarnation` (ambient / address-directed) re-resolves by id
  exactly as before — unchanged."
  [frame-id query-v expected-incarnation]
  (let [frame-record (frame/frame frame-id)]
   (if (and (some? expected-incarnation)
            (or (nil? frame-record)
                (not (identical? expected-incarnation
                                 (:drain-lock frame-record)))))
     ;; rf2-7w1im: the captured incarnation was superseded between the outer
     ;; subscribe-in-frame comparison and this durable build — recover-but-emit
     ;; and DO NOT read/build/cache into the same-id successor (identical posture
     ;; to the outer fence; one exact-incarnation operation). rf2-a2x2w /
     ;; rf2-alk8a: `emit-frame-destroyed-recovery!` is subscribe-realm by
     ;; construction and stamps `:op :subscribe`, so the resolved `:source-coord`
     ;; names the EXACT `[:sub id]` realm, never the realm-ambiguous fallback.
     ;; rf2-qjfrw: this branch is reached ONLY for a CAPTURED subscribe (guarded
     ;; by `some? expected-incarnation`) whose pinned incarnation is dead, so the
     ;; bare `frame-id` may name a live same-id successor B — pass `route-frame?`
     ;; false to keep A's failure out of B's frame-owned `:observability :errors`
     ;; sink (corpus record + dev trace still fire).
     (do (emit-frame-destroyed-recovery! frame-id query-v false) nil)
     ;; else — the existing build, against the validated `frame-record` for a
     ;; captured read (never a bare-id re-resolve), or re-resolved by id for the
     ;; unchanged ambient/address-directed path.
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
                        ;; resolved. The emit tag-shape follows Spec 009;
                        ;; recovery is a nil-yielding reaction, not cached.
                        ;;
                        ;; Fan out through the
                        ;; always-on error-emit listener (surface #4) so a
                        ;; subscribe to a never-registered sub survives
                        ;; `:advanced` + `goog.DEBUG=false` and reaches off-box
                        ;; shippers — a production-meaningful runtime error.
                        ;; An invalid op whose recovery is the built-in
                        ;; `:replaced-with-default`. The `:frame`-stampable
                        ;; record carries `frame-id` + the attempted `query-v`
                        ;; for frame + shipper attribution.
                        ;;
                        ;; Both channels via the shared helper:
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
        ;; Read the discriminator ONCE — it drives the single-source detection,
        ;; the container resolution, and the memoised-body dispatch below.
        input-kind    (:input-kind sub-meta)
        ;; A `:db` layer-1 sub specifically (the narrow single-source kind whose
        ;; input is the app-db container) — used by the dev-only output-marks
        ;; resolve + the not-cached symmetric-input-release guard below.
        ;; `:runtime-db` / `:frame-state` are the OTHER single-source kinds (the
        ;; `single-source-input-kinds` set drives the shared memoised-body +
        ;; container-lookup decisions). `:runtime-db` is a layer-1-shaped
        ;; framework reader over the frame's runtime-db projection, and
        ;; `:frame-state` is a single-source reader over the WHOLE frame-state
        ;; value (both partitions, so the body re-runs on EITHER an app-db or a
        ;; runtime-db change).
        layer-1?      (= :db input-kind)
        ;; The single-source container resolver for this kind (`:db` → app-db,
        ;; `:runtime-db` → runtime-db, `:frame-state` → whole frame-state), or
        ;; nil for the producer kinds (`:static` / `:parametric` / a miss). One
        ;; lookup shared by the `inputs` and `memoised-body` cond branches below.
        container-fn  (single-source-container-for input-kind)
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
        ;; picks the app-db / runtime-db / whole-frame-state container — the
        ;; `:frame-state` container propagates on a change to EITHER partition.
        ;; Layer-2+ subscribes each realized input.
        inputs        (cond
                        ;; rf2-7w1im: a CAPTURED build reads its lone single-source
                        ;; signal off the VALIDATED record (never a bare-id
                        ;; re-resolve that could land on a same-id successor); the
                        ;; ambient/address-directed build re-resolves by id as before.
                        container-fn [(if (some? expected-incarnation)
                                        (get frame-record
                                             (single-source-container-slot-for input-kind))
                                        (container-fn frame-id))]
                        input-error? []
                        ;; Push this query-v onto the per-thread build stack for
                        ;; the duration of input resolution ONLY (rf2-x76af2.24):
                        ;; a nested input `subscribe-in-frame` that recurses back
                        ;; to this query-v re-enters `compute-and-cache!` with the
                        ;; key still on the stack → the cycle is detected there.
                        :else        (binding [*subs-under-construction*
                                               (conj *subs-under-construction*
                                                     [frame-id (cache-key query-v)])]
                                       ;; Track the inputs successfully subscribed
                                       ;; so far so a `:<-` cycle detected in a
                                       ;; NON-FIRST input can release the earlier
                                       ;; inputs it already ref-bumped
                                       ;; (rf2-t3cpn3). On a cycle, `subscribe-
                                       ;; in-frame` for input N throws the
                                       ;; sub-cycle sentinel and the WHOLE partial
                                       ;; build unwinds to the outermost
                                       ;; `compute-and-cache!` recovery WITHOUT
                                       ;; ever wiring this reaction's on-dispose —
                                       ;; so nothing else would release inputs
                                       ;; 1..N-1 (a bounded dev-only ref-count
                                       ;; leak). `subscribe-in-frame` bumps the
                                       ;; input's ref-count before it returns, so
                                       ;; `acquired` holds exactly the inputs
                                       ;; whose ref must be undone; the cyclic
                                       ;; input threw before its own bump, so it
                                       ;; is (correctly) absent. Release is
                                       ;; scoped to the sub-cycle sentinel only —
                                       ;; the minimal precise fix; any other
                                       ;; (theoretical) throw re-propagates
                                       ;; unchanged.
                                       (let [acquired (volatile! [])]
                                         (try
                                           ;; rf2-7w1im: fence the recursive input
                                           ;; subscribes to the SAME captured
                                           ;; incarnation — a same-id successor
                                           ;; installed mid-build cannot have this
                                           ;; layer-2+ sub recursively resolve its
                                           ;; inputs in it. nil (ambient) is the
                                           ;; unchanged 2-arity input read.
                                           (mapv (fn [input-q]
                                                   (let [r (subscribe-in-frame
                                                             frame-id input-q
                                                             expected-incarnation)]
                                                     (vswap! acquired conj input-q)
                                                     r))
                                                 input-qs)
                                           (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
                                             (when (= :rf.error/sub-cycle (:rf.error/id (ex-data e)))
                                               (doseq [input-q @acquired]
                                                 (release-input-ref! frame-id input-q :sub-cycle-unwind)))
                                             (throw e))))))
        parametric?   (= :parametric input-kind)
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
                        ;; `container-fn` is exactly the single-source membership
                        ;; (its map keys ARE `single-source-input-kinds`).
                        container-fn
                        (subs-memo/make-layer-1-memoised-body
                          body-fn query-id query-v frame-id sub-meta)

                        ;; PARAMETRIC subs (any realized input count,
                        ;; including 1) deliver a VECTOR of input values to
                        ;; the computation fn — `(fn [[a b] q] ...)` — per
                        ;; Spec 006 §Single input contract. Route through the
                        ;; varargs layer-n wrapper with `vector-inputs? true`
                        ;; so even a single realized parametric input is
                        ;; delivered as `[value]`, NOT the bare-value `:<-`
                        ;; convention.
                        parametric?
                        (subs-memo/make-layer-n-memoised-body
                          body-fn query-id query-v frame-id input-qs sub-meta true)

                        ;; Static `:<-` with a single input — the dominant
                        ;; shape; specialise to fixed-arity-1 for
                        ;; parity with layer-1. Delivers the bare
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
        ;; rf2-7w1im: a CAPTURED build installs/adopts into the VALIDATED
        ;; incarnation's sub-cache (never a bare-id re-resolve that could adopt a
        ;; same-id successor's cache); the ambient/address-directed build
        ;; re-resolves by id as before (nil when the frame was torn down mid-build,
        ;; driving the not-cached symmetric-release branch unchanged).
        cache         (if (some? expected-incarnation)
                        (:sub-cache frame-record)
                        (:sub-cache (frame/frame frame-id)))
        k             (cache-key query-v)]
    ;; EP-0025 — sub-output sensitivity PROPAGATION is removed. A sub no longer
    ;; inherits its inputs' (or its layer-1 app-db's) sensitivity; there is no
    ;; propagation table and the build no longer resolves/records one. A sub's
    ;; trace output is redacted ONLY against its own registration's declared
    ;; `:sensitive` / `:large` paths (`classification/project-sub-tags`). If you
    ;; derive a secret into a sub's output, classify that sub's output path.
    ;; Skip caching the no-such-sub miss — see the docstring's
    ;; unknown-sub note. The reaction is built so callers that hold a reference
    ;; deref to nil (per Spec 009 §Error contract recovery
    ;; :replaced-with-default), but the cache slot stays empty so a later
    ;; registration is observed by the next subscribe.
    (if (and cache sub-meta)
      ;; Per rf2-x76af2.23: the cache-miss install must be ATOMIC / idempotent.
      ;; Two threads that both observed a miss for the SAME query-v both reach
      ;; here and build a reaction; a plain `(swap! cache assoc k …)` lets the
      ;; second STOMP the first — the first reaction is orphaned (its on-dispose
      ;; never fires → leaked layer-2 input refs) and `:ref-count` is reset to 1
      ;; while TWO callers hold refs (a phantom unsubscribe then drives the
      ;; CACHED reaction 1→0 and disposes it out from under the live holder).
      ;; Install-if-absent instead, mirroring the subscribe hit path's
      ;; CAS-after-snapshot discipline: `swap!` installs ONLY when the slot is
      ;; still empty, so a double-build resolves to ONE cached reaction.
      ;;
      ;; Wire the orphan-safe on-dispose closure onto the reaction BEFORE the
      ;; install attempt so a LOSING racer can dispose its just-built reaction
      ;; through the same closure (releasing the input refs its build bumped) —
      ;; the cache-dissoc step is identity-guarded, so firing it on an
      ;; un-cached orphan is a no-op.
      (let [entry {:reaction   reaction
                   :inputs     input-signals
                   :ref-count  1}]
        (interop/add-on-dispose! reaction
          (fn []
            ;; A layer-2+ sub's construction called `subscribe` once per
            ;; `:<-` input, each incrementing the input's `:ref-count`.
            ;; The disposal must release those refs symmetrically —
            ;; without this, input ref-counts leak after Reagent auto-
            ;; disposes the parent. Decrement inputs BEFORE clearing the
            ;; parent slot so the cache invariant ("ref-count reflects
            ;; live refs") holds at every observable moment.
            ;; Best-effort per-input release: a throw from one input's
            ;; `unsubscribe` surfaces a dev breadcrumb (rf2-is8ov5) and the
            ;; loop continues so the remaining inputs still release.
            (doseq [input-q input-signals]
              (release-input-ref! frame-id input-q :on-dispose))
            (swap! cache (fn [m]
                           (if (identical? reaction (:reaction (get m k)))
                             (dissoc m k)
                             m)))))
        (let [installed (swap! cache (fn [m]
                                       (if (contains? m k)
                                         m
                                         (assoc m k entry))))]
          (if (identical? entry (get installed k))
            ;; Uncontended, or we won the install race: our reaction is cached.
            reaction
            ;; Collision: a concurrent build already installed for this
            ;; query-v. Dispose OUR just-built orphan (fires the on-dispose
            ;; wired above → releases the input refs our build bumped; the
            ;; cache-dissoc no-ops since our reaction was never cached), then
            ;; ADOPT the winner as a HIT — bump its ref-count under the same
            ;; CAS-after-snapshot discipline the `subscribe` hit path uses, so
            ;; both callers share ONE reaction with a correct ref-count. If the
            ;; winner was evicted in the race window, fall through to a fresh
            ;; build (mirroring the hit path's rebuild fall-through).
            (do
              (interop/dispose! reaction)
              (let [winner-reaction (:reaction (get installed k))
                    [_old post]
                    (swap-vals! cache
                                (fn [m]
                                  (if (identical? winner-reaction
                                                  (:reaction (get m k)))
                                    (update-in m [k :ref-count] (fnil inc 0))
                                    m)))]
                (if (identical? winner-reaction (:reaction (get post k)))
                  winner-reaction
                  ;; rf2-7w1im: the collision-retry rebuild stays fenced to the
                  ;; SAME captured incarnation.
                  (compute-and-cache! frame-id query-v expected-incarnation)))))))
      ;; Not cached (frame torn down mid-build, or no-such-sub miss).
      ;; Symmetric input release on the escaped-caching path: a layer-2+ build
      ;; already subscribed each `:<-` input above (bumping their ref-counts),
      ;; but the dispose-wiring that releases them lives ONLY inside the cached
      ;; branch. If the frame was destroyed (or its container torn down)
      ;; BETWEEN `subscribe`'s frame-record resolution and this re-resolution,
      ;; `cache` is now nil: the reaction is built and returned but never cached
      ;; and never dispose-wired, so without this branch nothing would ever call
      ;; `unsubscribe` for those inputs — a monotonic ref-count leak until
      ;; `clear-sub-cache!`. Release them here so the input ref-count stays
      ;; symmetric with the bumps. Layer-1 has no subscribed inputs (its input
      ;; is the app-db container, not a subscribe), and the no-such-sub miss
      ;; (`sub-meta` nil) has no `:<-` inputs, so this only fires for a layer-2+
      ;; reaction that escaped caching.
      (do
        (when (and (not layer-1?)
                   (seq input-signals))
          (doseq [input-q input-signals]
            (release-input-ref! frame-id input-q :not-cached-release)))
        reaction))))))

(defn- compute-and-cache!
  "Cycle-guarding entry to the reactive sub build (rf2-x76af2.24). Detects a
  `:<-` dependency cycle — a re-entry for a query-v already mid-build on this
  thread's `*subs-under-construction*` stack — and, at the OUTERMOST build,
  recovers it to a structured `:rf.error/sub-cycle` + nil-yielding reaction
  instead of a raw host StackOverflowError. Delegates the actual
  materialisation to `build-and-cache!*`, which pushes the per-thread marker
  across its input resolution.

  `expected-incarnation` (rf2-7w1im, 3-arity) is the captured incarnation token
  threaded straight through to `build-and-cache!*`'s exact-incarnation fence; nil
  (the observation-port acquire path + the ambient/address-directed miss) is the
  unchanged bare-id build."
  ([frame-id query-v] (compute-and-cache! frame-id query-v nil))
  ([frame-id query-v expected-incarnation]
   (let [construction-key [frame-id (cache-key query-v)]
         stack            *subs-under-construction*]
     (when (some #(= construction-key %) stack)
       ;; This query-v is already resolving its inputs higher on the stack — a
       ;; `:<-` cycle. Throw the sentinel to unwind the WHOLE partial build so no
       ;; half-wired cyclic reaction is cached; the outermost build (below)
       ;; catches it, emits the diagnostic error and recovers to nil.
       (throw (sub-cycle-ex frame-id query-v
                (sub-cycle-path stack construction-key query-v))))
     (if (empty? stack)
       ;; Outermost build: a cycle thrown anywhere in the input recursion unwinds
       ;; to here. Emit the structured error (diagnostic, mirroring flow-cycle)
       ;; and recover to a nil-yielding reaction — NOT cached, mirroring the
       ;; no-such-sub miss so a later registration fix rebuilds cleanly.
       (try
         (build-and-cache!* frame-id query-v expected-incarnation)
         (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
           (if (= :rf.error/sub-cycle (:rf.error/id (ex-data e)))
             (do
               (emit-sub-cycle! frame-id query-v (:cycle (ex-data e)) :subscribe)
               ;; Observation-port acquire path only (rf2-vxgfnd.27): record the
               ;; cycle so `acquire!` throws typed `:rf.error/sub-cycle`
               ;; (fail-loud → the ViewCell error boundary) rather than acquire
               ;; this never-cached nil reaction. sub-cycle stays DIAGNOSTIC (009
               ;; catalogue) — the port throws the typed carrier but does NOT
               ;; promote it to the always-on axis. No-op on the subscribe path.
               (record-acquire-recovery! :cycle {:cycle   (:cycle (ex-data e))
                                                 :query-v query-v})
               (adapter/make-derived-value [] (constantly nil)))
             (throw e))))
       (build-and-cache!* frame-id query-v expected-incarnation)))))

;; ---- the sub-override subscribe seam (CLJS, dev-only) --------------------
;;
;; See the block comment inside `subscribe` for the full rationale. This
;; helper is CLJS-only and consulted ONLY inside the
;; `interop/debug-enabled?` gate, so the whole seam DCEs under `:advanced`
;; + `goog.DEBUG=false`. The resolver fn + the React-context reader are
;; published from Story / the CLJS-only carriage ns
;; (`re-frame.adapter.sub-override-context`) via the
;; `:subs/resolve-sub-override` late-bind hook, so core never `:require`s
;; a tools ns (bundle-isolation holds).
;;
;; The schema-validation fold-in (`:where :sub-override` — an override
;; that violates the sub's own declared `:schema` is the 'pin a state the
;; real derivation could never produce' anti-pattern) lives in the ONE
;; shared `re-frame.subs.override-schema/validate-sub-override!` primitive
;; (rf2-vxgfnd.21) so the compiled-view path applies byte-identical
;; validation + `:replaced-with-default` recovery.

#?(:cljs
   (defn- resolve-sub-override
     "Consult the `:subs/resolve-sub-override` late-bind hook for an
     exact-query-vector `:sub-overrides` HIT. The hook (Story-
     published) reads the closest enclosing override-context Provider and
     returns `[value]` on a hit (a one-element vector so a nil-valued
     override is honoured) or nil on a miss / unbound.

     On a HIT, schema-validate the pinned value (the shared
     `override-schema/validate-sub-override!` primitive) and return a
     CONSTANT reaction `(adapter/make-derived-value [] (constantly v))` —
     no inputs, never recomputes, never cached, never touches app-db /
     `compute-sub`. On a miss / unbound / unpublished hook, return nil so
     `subscribe` falls through to the normal build-and-cache path.

     CLJS-only and called ONLY inside `subscribe`'s `interop/debug-enabled?`
     gate, so this DCEs in production."
     [frame-id query-v]
     (when-let [resolve-override (late-bind/get-fn :subs/resolve-sub-override)]
       (when-let [hit (resolve-override query-v)]
         (let [v        (first hit)
               sub-meta (registrar/lookup :sub (first query-v))
               v*       (override-schema/validate-sub-override! v query-v sub-meta frame-id)]
           (adapter/make-derived-value [] (constantly v*)))))))

(defn- subscribe-in-frame
  "INTERNAL worker for `subscribe` (and the layer-2+ recursive input
  resolution inside `compute-and-cache!`). `target` may be a frame-id
  KEYWORD or a live frame VALUE (`rf/make-frame`'s return value);
  normalized here to its runnable-id ADDRESS via `frame/frame-target->id`
  so the sub-cache lookup (`(frame/frame frame-id)`), the override seam,
  and the error payloads all key the backing record unchanged — a keyword
  passes through. The generation-resolution seam (`frame-resolution-target`)
  then re-resolves the frame's image from this id via the live-frame
  registry — so a value target builds against its OWN image, byte-identical
  to the keyword form. Mirrors the dispatch-side normalization in
  `re-frame.router/build-envelope`.

  Per Spec 006 §The sub-override subscribe seam (CLJS /
  dev-only): when a Story render wraps the variant view in the
  override-context Provider, an exact-query-vector `:sub-overrides` HIT
  short-circuits build-and-cache and returns a constant reaction holding
  the pinned value (schema-validated against the sub's declared
  `:schema` when present). The whole consult sits inside
  `interop/debug-enabled?` so it DCEs in production, and the override
  feeds ONLY the derefed reaction the view sees — never app-db, never
  `compute-sub` — so `:rf.assert/sub-equals` stays unsatisfiable by an
  override.

  `expected-incarnation` (rf2-dlld6, 3-arity) is the EXACT incarnation token a
  `capture-frame` `:subscribe` op pinned at capture. When non-nil the read is
  FENCED to that incarnation: a same-id successor resolved here (the capture's
  frame was destroyed and a successor reseated under the id after the capture's
  liveness pre-check) recover-but-emits `:rf.error/frame-destroyed` and returns
  nil rather than reading the successor's app-db or caching a reaction in its
  sub-cache. The token is compared against the SAME record resolved for the read
  (`:drain-lock`), so validation and consumption are one exact-incarnation
  operation. nil `expected-incarnation` — the ambient / explicit address-directed
  read, and every layer-2+ recursive input resolution — is unfenced."
  ([target query-v] (subscribe-in-frame target query-v nil))
  ([target query-v expected-incarnation]
   (let [frame-id (frame/frame-target->id target)]
   ;; (CLJS, dev-only): record the view→sub edge — push this
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
   ;; (dev-only, rf2-re5a98): the cache-fragmentation guardrail. The
   ;; sub-cache keys by query-vector identity (`cache-key` = `=`); an app
   ;; that re-subscribes the SAME sub-id every render with a value-equal but
   ;; freshly-REBUILT non-primitive arg mints a distinct cache key each
   ;; render — unbounded growth, zero reuse, silently. This fires (hit AND
   ;; miss, like the deref-sink hook above) the dev tripwire ONCE per sub-id
   ;; on the unambiguous fresh-object-same-value signature. The whole helper
   ;; is `interop/debug-enabled?`-gated so it DCEs in production.
   (maybe-warn-fragmenting-arg! query-v)
   ;; (CLJS, dev-only): the SUBSTITUTIVE override seam. Story's
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
   ;; An override that violates the sub's own
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
   ;;
   ;; Route the BUILD path through the target frame's
   ;; resolved IMAGE generation when `frame-id` names an image-loaded frame, so
   ;; `registrar/lookup :sub` (and the layer-2+ input `subscribe` calls inside
   ;; `compute-and-cache!`) resolve the sub handler through the frame's OWN
   ;; image (the image-resolution seam, invoked at the live subscribe entry).
   ;; Two frames running DIFFERENT images thus build the same sub-id against
   ;; their own image's descriptor. A target naming no image-loaded frame (a
   ;; single-realm default frame) derives no generation, so this binds nothing
   ;; and the build resolves through the registrar atom directly
   ;; (absence-is-default). DERIVED from the carried target (EP-0002).
   (live-frame/call-with-frame-resolution
     (live-frame/frame-resolution-target frame-id)
     (fn []
   (let [frame-record (frame/frame frame-id)
         ;; rf2-7w1im: resolve the record ONCE and decide supersession up front,
         ;; so a CAPTURED read consumes EXACTLY the validated incarnation across
         ;; the override, hit, and miss seams — one exact-incarnation operation.
         ;; `expected-incarnation` nil (the ambient / explicit address-directed
         ;; read, and every layer-2+ recursive input resolution) → never
         ;; superseded, so every clause below stays byte-identical to the
         ;; pre-fence behaviour.
         superseded? (and (some? expected-incarnation)
                          (not (identical? expected-incarnation
                                           (:drain-lock frame-record))))]
     (or
       ;; rf2-7w1im: the CLJS dev sub-override seam now sits INSIDE the
       ;; incarnation fence — a stale captured subscribe must NOT surface an
       ;; override for a superseded incarnation (the override short-circuits
       ;; build-and-cache, so consulted ahead of the fence it would escape it
       ;; entirely). An UNFENCED read (`superseded?` is false whenever
       ;; `expected-incarnation` is nil) still consults it exactly as before —
       ;; INCLUDING ahead of the missing-frame branch — and a LIVE captured read
       ;; (incarnation still valid) keeps its override too.
       #?(:cljs
          (when (and interop/debug-enabled? (not superseded?))
            (resolve-sub-override frame-id query-v)))
       (cond
         ;; Missing or destroyed frame, OR a superseded captured read (rf2-dlld6 /
         ;; rf2-7w1im): recover-but-emit `:rf.error/frame-destroyed` and return
         ;; nil rather than deref-ing nil, reading a same-id successor's app-db,
         ;; or caching a reaction in its sub-cache. Production-survivable
         ;; (surface #4) so a subscribe during a teardown / hot-reload race
         ;; recovers safely while a real use-after-destroy bug stays observable on
         ;; the production-watched stream.
         ;;
         ;; rf2-dlld6: the `expected-incarnation` comparison (folded into
         ;; `superseded?`) is made against the record we JUST resolved for the
         ;; read — A destroyed + a same-id successor B installed after the
         ;; capture's liveness pre-check resolves B here, whose `:drain-lock`
         ;; differs from the pinned token — so validation and consumption are one
         ;; exact-incarnation operation. rf2-7w1im: the DURABLE build/read past
         ;; this cond (a miss, or a hit's concurrent-eviction rebuild) carries the
         ;; SAME token into `build-and-cache!*`, which re-fences it, so a
         ;; supersession in the post-comparison window cannot retarget B either. A
         ;; nil `expected-incarnation` leaves the clause a pure
         ;; `(nil? frame-record)` test.
         (or (nil? frame-record) superseded?)
         ;; rf2-a2x2w / rf2-alk8a: `emit-frame-destroyed-recovery!` is
         ;; subscribe-realm by construction and stamps `:op :subscribe`
         ;; UNCONDITIONALLY — for a CAPTURED subscribe (superseded or a captured
         ;; pin whose frame is now missing) AND for an ORDINARY address-directed
         ;; subscribe to a missing frame alike. The resolved `:source-coord` names
         ;; the EXACT `[:sub id]` realm (omitting when the sub-id is genuinely
         ;; unregistered), and the attempted query vector egresses on the `:event`
         ;; slot as raw IDENTITY (rf2-zwgqe) rather than fail-closed to redacted.
         ;; rf2-qjfrw: suppress the EP-0015 frame-owned sink route ONLY for a dead
         ;; CAPTURED incarnation (`superseded?` — a captured pin whose frame was
         ;; destroyed/reseated, so the bare `frame-id` may name a live same-id
         ;; successor B). An ORDINARY address-directed subscribe (`superseded?`
         ;; false, `expected-incarnation` nil, frame simply missing) keeps its
         ;; default route — its bare id names no captured incarnation.
         (emit-frame-destroyed-recovery! frame-id query-v (not superseded?)) ;; emits, returns nil

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
                                                 (:reaction (get m k)))
                                   (update-in m [k :ref-count] (fnil inc 0))
                                   m)))]
               (if (identical? reaction (:reaction (get new k)))
                 reaction
                 ;; rf2-7w1im: the hit's concurrent-eviction rebuild carries the
                 ;; captured token so the rebuild is fenced to the SAME
                 ;; incarnation (never a bare-id retarget to a same-id successor).
                 (compute-and-cache! frame-id query-v expected-incarnation)))
             ;; Miss: the durable build carries the captured token (rf2-7w1im).
             (compute-and-cache! frame-id query-v expected-incarnation))))))))))) ;; close or + let[frame-record superseded?] + fn + call-with-frame-resolution + normalize-target let + 3-arity + subscribe-in-frame

(declare subscribe)

(defn- subscribe-with-opts
  "INTERNAL body of `subscribe`'s 2-arity, factored out so the
  `trace/with-call-site` scope push can be applied CONDITIONALLY around it
  (rf2-i3dvj). `with-call-site` sets `:call-site` unconditionally, so
  pushing it with a nil coord would CLOBBER an inherited one — exactly what
  `make-capture-frame`'s `:subscribe` op relies on, since it wraps its own
  view coord around a call that carries no `:rf.trace/call-site` in `opts`."
  [query-v opts]
  (if-some [target (:frame opts)]
    (subscribe-in-frame target query-v (:rf.frame/expected-incarnation opts))
    (subscribe query-v)))

(defn subscribe
  "Per Spec 006 §Lookup algorithm. Returns the reaction for query-v;
  build-and-cache on miss; reuse on hit. The 1-arity ambient form
  resolves the active frame through the carried-invariant scope/hold
  chain via `frame/require-current-frame!` (EP-0002): a `with-frame`
  scope, or the closest enclosing frame boundary — a `frame-provider`
  (SCOPE) or a `frame-root` (ENSURE), via the `:adapter/current-frame`
  late-bind hook — or a captured `*current-frame*` stamp. There is NO
  `:rf/default` floor — a subscribe issued under no established scope
  raises `:rf.error/no-frame-context` rather than silently reading the
  wrong frame. Pass the public opts form `(subscribe query-v {:frame
  target})` to read a named frame from outside any scope (async callbacks,
  tools, tests, SSR); `target` is a frame-id keyword or a live frame value.

  Per Spec 006 §Plain-fn footgun is `:rf.error/no-frame-context`: a
  plain (non-`reg-view`) Reagent fn carries no `:contextType` wiring, so
  it cannot read the surrounding frame boundary's frame from React
  context and its ambient 1-arity `subscribe` raises
  `:rf.error/no-frame-context`. Use the opts form from a plain Reagent
  fn body to subscribe against a known frame: naming the target
  explicitly is what carries the frame the ambient chain cannot find.

  This is the runtime-callable fn form. The macro form
  `re-frame.core/subscribe` captures `(meta &form)` and calls straight
  through to THIS fn (rf2-m90brg — no `re-frame.core/subscribe*` facade
  indirection), stamping the coord onto `opts` as `:rf.trace/call-site`.
  THIS BODY then establishes the `trace/with-call-site` scope from it, so
  any error emitted inside the synchronous miss path
  (`:rf.error/no-such-sub`, `:rf.error/frame-destroyed`) carries the
  invocation coord.

  Per rf2-i3dvj the scope push lives HERE, in the callee, and not in the
  macro expansion: `with-call-site` expands to a `binding`, and a `binding`
  spliced into the CALLER's context compiles to `await (async
  function(){...})()` inside a CLJS async context — a hidden microtask yield
  before a call documented as same-stack synchronous. Mirrors
  `router/dispatch!`, which reads the identical opts key in its own body."
  ([query-v]
   ;; EP-0002 §Subscriptions And Read Helpers — the carried-invariant
   ;; read. The 1-arity ambient form resolves the frame through the
   ;; scope/hold chain via `require-current-frame!`: a `with-frame` scope,
   ;; the closest enclosing frame boundary — `frame-provider` (SCOPE) or
   ;; `frame-root` (ENSURE) — (`resolve-current-frame`), or a captured
   ;; `*current-frame*` stamp. There is NO `:rf/default` floor — a
   ;; subscribe issued under no established scope (no with-frame, no
   ;; frame boundary, no carried stamp) raises the always-on
   ;; `:rf.error/no-frame-context` (with capture-site ancestry) rather
   ;; than silently reading the wrong frame's app-db. The `extra` threads
   ;; the sub-id into the error payload's `:event-id` slot so a frameless
   ;; subscribe's error is attributed to the query it carried.
   (subscribe-in-frame (frame/require-current-frame!
                         :subscribe
                         {:where    're-frame.subs/subscribe
                          :event-id (first query-v)})
                       query-v))
  ([query-v opts]
   ;; API-shrink #1 (rf2-csbbwu): the 2-arity is `[query-v opts]` ONLY — no
   ;; `vector?` shape-discrimination on the first arg, no internal frame-first
   ;; reach. `opts` may carry `{:frame target}` (a frame-id keyword or a live
   ;; frame value) — the explicit OVERRIDE intent; ambient (the carried
   ;; scope/hold stamp) when absent.
   ;;
   ;; rf2-dlld6: `:rf.frame/expected-incarnation` (a `capture-frame`
   ;; `:subscribe` op's pinned token — INTERNAL, reserved `:rf.frame/` ns)
   ;; rides alongside `:frame` so the read is fenced to the exact captured
   ;; incarnation. nil for every ordinary explicit-frame read.
   ;;
   ;; rf2-i3dvj: `:rf.trace/call-site` is the macro-stamped invocation coord
   ;; (dev-only — the whole stamped branch DCEs under `:advanced` +
   ;; `goog.DEBUG=false`, so this reads nil in production). The scope push is
   ;; INSIDE this body by contract; see the docstring. Conditional, because a
   ;; nil push would clobber an inherited coord — see `subscribe-with-opts`.
   (if-some [cs (when interop/debug-enabled? (:rf.trace/call-site opts))]
     (trace/with-call-site cs (subscribe-with-opts query-v opts))
     (subscribe-with-opts query-v opts))))

(defn- subscribe-once-in-frame
  "INTERNAL worker for `subscribe-once`. `target` may be a frame-id
  keyword or a live frame value; `subscribe-in-frame` / `unsubscribe` each
  normalize it to its runnable-id ADDRESS, so subscribe-then-unsubscribe
  here target the same frame for every supported spelling."
  [target query-v]
  (let [reaction (subscribe-in-frame target query-v)
        v        (when reaction @reaction)]
    (unsubscribe target query-v)
    v))

(defn subscribe-once
  "One-shot read of a sub's current value. Subscribes, derefs, then
  unsubscribes — does NOT retain a reference on the cache entry and
  does NOT register the caller for reactive re-render.

  The **advanced** live one-shot read: use in tests, REPL sessions, SSR
  builders, tools, or any non-reactive consumer that wants the value
  right now. For reactive consumers (Reagent views, tools holding the
  reaction) use `subscribe`. For event handlers prefer declaring a
  sub-reading cofx via `:rf.cofx/requires` (EP-0017) so the read is part
  of the cofx contract rather than a side-effect inside the handler body.
  **Never from inside a machine callback** (`:guard` / `:action` /
  `:entry` / `:exit`): an in-callback ambient read is unrecorded and
  breaks 005's token-grain replay contract — a machine takes external
  facts by payload threading or a declared recordable coeffect
  (machines-only `{:rf/sub query-v :as fact-id}`). Per
  Cross-Spec-Interactions §2 / Spec 006 §subscribe-once.

  Per Spec 006 §Reference counting and disposal: the
  teardown `unsubscribe` runs synchronously on the 1 → 0 transition,
  so the one-shot read's whole lifetime — subscribe, deref, dispose —
  completes in the calling tick. Concurrent reactive subscribers keep
  the slot alive via ref-count and are unaffected — `subscribe-once`'s
  decrement only drives the eviction when it owned the last reference.

  See also: `subscribe`, `unsubscribe`, `compute-sub`, `reg-cofx`.

  EP-0002: the 1-arity ambient form resolves the frame through the
  scope/hold chain via `frame/require-current-frame!` — a one-shot read
  under no established scope raises `:rf.error/no-frame-context`, never a
  `:rf/default` floor. Pass the public opts form `(subscribe-once query-v
  {:frame target})` to read a named frame from outside any scope; `target`
  is a frame-id keyword or a live frame value."
  ([query-v]
   (subscribe-once-in-frame
     (frame/require-current-frame!
       :subscribe-once
       {:where    're-frame.subs/subscribe-once
        :event-id (first query-v)})
     query-v))
  ([query-v opts]
   ;; API-shrink #1 (rf2-csbbwu): `[query-v opts]` ONLY — no `vector?`
   ;; shape-discrimination, no internal frame-first reach. `opts` may carry
   ;; `{:frame target}` (a frame-id keyword or a live frame value); ambient
   ;; when absent.
   (if-some [target (:frame opts)]
     (subscribe-once-in-frame target query-v)
     (subscribe-once query-v))))

(defn- frame-state-value?
  "True when `v` is a frame-state projection map carrying at least one
  partition key (`:rf.db/app` / `:rf.db/runtime`).
  `compute-sub` accepts EITHER a bare app-db map or a
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
      ;; A `:frame-state` sub's body wants the WHOLE frame-state value
      ;; (both partitions) — pass it through unextracted.
      :frame-state supplied
      (get supplied :rf.db/app))
    supplied))

(def ^:no-doc observation-opts-key
  "INTERNAL (observation port, Spec 006 §The internal observation port).
  Memo-atom slot key the port's COLD-PROBE path seeds before handing the
  memo to [[compute-sub-with-memo]]. Value shape: `{:frame <frame-id>}`.

  When present, an UNREGISTERED sub encountered MID-GRAPH during the pure
  compute (a `:<-` / parametric input naming a sub that has no
  registration) emits the always-on `:rf.error/no-such-sub` — the same
  one-error-event / nil-substituted / body-still-runs contract the
  reactive graph gives (Spec 006 §What happens when a sub references an
  unknown sub) — so a cold probe and a live probe report the unknown
  mid-graph input IDENTICALLY. The public `compute-sub` never sets this
  key, so the pure testing form's documented silent-nil behaviour is
  byte-for-byte unchanged."
  ::observation-opts)

(defn- maybe-emit-cold-probe-no-such-sub!
  "Emit the always-on `:rf.error/no-such-sub` for an unregistered sub hit
  MID-GRAPH under an observation-port cold probe (the `memo` carries
  [[observation-opts-key]]). No-op for every other `compute-sub` caller.
  Mirrors the reactive build's emission shape (`build-and-cache!*`) with a
  `:where :observation-cold-probe` discriminator. Returns nil."
  [memo query-v]
  (when-let [{:keys [frame]} (get @memo observation-opts-key)]
    (when-let [emit-error-both!
               (late-bind/get-fn-cached :error-emit/emit-error-both)]
      (emit-error-both!
        :rf.error/no-such-sub
        query-v               ;; attempted query-vector (as :event)
        (first query-v)       ;; sub-id (as :event-id)
        frame
        nil                   ;; no exception — invalid op
        0                     ;; elapsed-ms
        (interop/now-ms)      ;; time
        {:rf.sub/id        (first query-v)
         :unresolved-input query-v
         :resolved-inputs  []
         :where            :observation-cold-probe
         :frame            frame})))
  nil)

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
  (cond
    ;; :<- dependency cycle (rf2-x76af2.24): query-v is already mid-computation
    ;; on THIS call's recursion — a re-entry while it sits on the per-call
    ;; `::building` stack. Emit the structured `:rf.error/sub-cycle`
    ;; (diagnostic) and recover to nil, memoising the recovery so the rest of
    ;; the call sees nil rather than re-detecting. The per-call memo doubles as
    ;; the under-construction marker here (there is no reactive frame / cache).
    (some #(= % query-v) (::building @memo))
    (do (emit-sub-cycle! nil query-v (compute-sub-cycle-path (::building @memo) query-v)
                         :compute-sub)
        (swap! memo assoc query-v nil)
        nil)

    (contains? @memo query-v)
    (get @memo query-v)

    :else
    (let [query-id (first query-v)
          meta     (registrar/lookup :sub query-id)]
      (if-not meta
        ;; Unregistered sub computes to nil; memoise so a repeated
        ;; reference within the same call is also a single resolution.
        ;; Under an observation-port cold probe (and ONLY there — see
        ;; `observation-opts-key`) the miss additionally emits the
        ;; always-on `:rf.error/no-such-sub`, matching the reactive
        ;; graph's one-error-event / nil-substituted contract. The memo
        ;; dedupes: a repeated reference is a memo HIT and never reaches
        ;; this branch again, so the error event fires once per distinct
        ;; unknown input per slice.
        (do (maybe-emit-cold-probe-no-such-sub! memo query-v)
            (swap! memo assoc query-v nil)
            nil)
        (do
          ;; Mark query-v mid-computation for the cycle guard above; popped
          ;; after its inputs resolve + its body runs (rf2-x76af2.24).
          (swap! memo update ::building (fnil conj []) query-v)
          ;; Per Spec 009 §:op-type vocabulary: :rf.sub/run marks a sub recompute.
          ;; The pure compute-sub form fires the same op-type as the reactive
          ;; recompute path so tools can observe both call sites uniformly.
          ;;
          ;; Per rf2-l1jz8 — the reactive recompute path (subs.memo/validate-
          ;; and-trace) enriches its `:rf.sub/run` tag with value-change +
          ;; cascade attribution (`:value-changed?` / `:prev-value` /
          ;; `:value` / `:cascade?` / `:cause-sub`). `compute-sub` deliberately
          ;; bypasses the per-frame reactive cache (it's the pure-snapshot
          ;; form per Spec 008 §Testing), so it has NO prior cached value to
          ;; diff for `:value-changed?` and NO reactive context to attribute a
          ;; cascade against — each DISTINCT `:<-` input is re-resolved fresh
          ;; against the supplied `db`, not observed as a changed upstream
          ;; signal. It therefore emits the BASE `:rf.sub/run` shape only;
          ;; attribution is a reactive-path concern. Consumers (Xray) read
          ;; attribution off the reactive epoch records, never off
          ;; compute-sub emissions.
          (trace/emit! :rf.sub :rf.sub/run
                       {:rf.sub/id      query-id
                        :rf.sub/query-v query-v})
          (let [body-fn    (:handler-fn meta)
                input-kind (:input-kind meta)
                ;; EP-0001 (rf2-vzld77): `:db` and `:runtime-db` are both
                ;; single-source readers — `compute-sub` passes the supplied
                ;; value straight to the body for either. For a `:runtime-db`
                ;; sub the caller supplies the runtime-db value (or a
                ;; frame-state value, from which `compute-sub` extracts the
                ;; right partition — see below).
                layer-1?   (single-source-input-kinds input-kind)
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
                          (let [parametric? (= :parametric input-kind)
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
                                      (body-fn (partition-value-for-sub db input-kind) query-v)

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
                            (let [msg (error/ex-message-safe e) ; rf2-vzrxp3: nil-safe
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
            ;; Pop the under-construction marker now that this sub's inputs +
            ;; body have resolved (LIFO — our own query-v is last; rf2-x76af2.24).
            (swap! memo update ::building pop)
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
  `rf/frame-state-value`, or `(:rf.db/runtime (rf/frame-state-value id))`
  for the runtime-db value alone — rf2-t3lftq API-shrink #3 retired the
  dedicated `rf/runtime-db-value` reader).

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

;; ---- observation-port internal entry points (Spec 006 §The internal ------
;; observation port). Two `^:no-doc` seams `re-frame.substrate.observation`
;; consumes; NOT public API and NOT for adapters or apps.

(defn ^:no-doc compute-sub-with-memo
  "INTERNAL (observation port). [[compute-sub]] with a CALLER-SUPPLIED memo
  atom — the slice-scoped pure memo table behind the port's cold `probe`
  (Spec 006 §The slice-scoped probe memo): N sibling cold probes within one
  synchronous slice thread ONE memo atom, so shared derivation parents
  compute once per slice instead of once per probe. The memo may carry
  [[observation-opts-key]] (seeded by the port) to opt the unregistered-
  mid-graph-input miss into the always-on `:rf.error/no-such-sub` emission.
  Pure apart from that emission; creates no cache entry, no watch, no
  disposal obligation. Same value contract as `compute-sub`."
  [query-v db memo]
  (compute-sub* query-v db memo))

(defn- canonical-cache-reaction?
  "True when `reaction` is still exactly the frame's live cache entry for `k`
  — an identical cached node, so a real ref-count attach happened. False for a
  never-cached recovery reaction (nothing at `k`, or a different node), and for
  the single-thread-unreachable case of a node evicted in the build→check
  window. rf2-vxgfnd.27."
  [frame-id k reaction]
  (boolean
    (when-let [cache (:sub-cache (frame/frame frame-id))]
      (identical? reaction (:reaction (get @cache k))))))

(defn- build-and-classify!
  "Drive `compute-and-cache!` for the observation-port acquire path and
  DISCRIMINATE its result (rf2-vxgfnd.27). Returns `{:reaction r}` for a
  CANONICAL cached node (a real ref-count attach) or `{:recovery kind …}` for a
  never-cached, zero-ref recovery reaction:

    - `:frame-destroyed` — the frame's cache vanished DURING the build (the race
      that used to slip through the caller's nil→guard).
    - `:cycle` / `:input-fn-exception` / `:input-fn-bad-return` — the entry
      node's OWN build recovered, recording its classification through
      `*acquire-recovery*`.

  The canonical check is AUTHORITATIVE: a real cached node is owned even when a
  mid-graph INPUT recovered (the graph's own recover-to-nil), so a nested
  recovery recorded on the channel is IGNORED once the entry node itself cached."
  [frame-id query-v k]
  (let [sink     (volatile! nil)
        reaction (binding [*acquire-recovery* sink]
                   (compute-and-cache! frame-id query-v))]
    (cond
      ;; Frame torn down during the build — the reaction escaped caching (the
      ;; `build-and-cache!*` else-branch). Map it to the typed
      ;; `:rf.error/frame-destroyed`, closing the nil→guard bypass the race used.
      (nil? (:sub-cache (frame/frame frame-id)))
      {:recovery :frame-destroyed}

      ;; A real cached node — the build installed or adopted it and took a +1
      ;; reference. Own it (ignore any nested-input recovery on the channel).
      (canonical-cache-reaction? frame-id k reaction)
      {:reaction reaction}

      ;; Non-canonical: the entry node's OWN build produced a never-cached
      ;; recovery reaction. Use the recorded classification; fall back to
      ;; `:frame-destroyed` for the (single-thread-unreachable) evicted-in-window
      ;; case where nothing recorded.
      :else
      (or @sink {:recovery :frame-destroyed}))))

(defn ^:no-doc acquire-cache-reaction!
  "INTERNAL (observation port). Resolve-or-build the canonical sub-cache
  node for `query-v` in `frame-id` and take ONE reference on it — the
  ref-count attach of Spec 006 §Lookup algorithm, exactly the CAS-after-
  snapshot hit/bump/build discipline `subscribe` uses — WITHOUT the public
  subscribe path's render-context machinery: no sub-override consult
  (invariant 2 — commit must not re-resolve context), no dev deref-sink /
  fragmentation hooks, no recover-to-nil frame handling (the PORT is the
  fail-loud surface; it checks frame liveness + registration BEFORE calling
  here and throws typed). Runs under the target frame's image-resolution
  seam so an image-loaded frame's build resolves the sub through its own
  generation, byte-identical to `subscribe`.

  Returns a DISCRIMINATED result (rf2-vxgfnd.27):

    {:reaction <r>}          — a CANONICAL cached node holding a real +1
                               reference: the ONLY result the caller wraps in an
                               owning handle.
    {:recovery <kind> …}     — the build produced a NON-NIL but NEVER-CACHED,
                               zero-ref recovery reaction instead of a canonical
                               node. `<kind>` is `:cycle` (cyclic entry sub),
                               `:input-fn-exception` / `:input-fn-bad-return`
                               (parametric `input-fn` failure), or
                               `:frame-destroyed` (the frame's cache vanished
                               before or during the build). `acquire` IS the
                               ref-count attach — there is no node to own — so
                               the caller (the fail-loud observation port) throws
                               the matching typed error rather than acquire a
                               lying `owned?`-true zero-ref reaction.

  Callers release via the identity-guarded decrement they own (the handle
  `release!`), or `unsubscribe`."
  [frame-id query-v]
  (live-frame/call-with-frame-resolution
    (live-frame/frame-resolution-target frame-id)
    (fn []
      (if-let [cache (:sub-cache (frame/frame frame-id))]
        (let [k (cache-key query-v)]
          (if-let [entry (get @cache k)]
            ;; Hit — bump ref-count under the same CAS-after-snapshot
            ;; discipline as the subscribe hit path; on a lost race
            ;; (concurrent eviction / rebuild) fall through to a fresh
            ;; build.
            (let [reaction (:reaction entry)
                  [_old new]
                  (swap-vals! cache
                              (fn [m]
                                (if (identical? reaction
                                                (:reaction (get m k)))
                                  (update-in m [k :ref-count] (fnil inc 0))
                                  m)))]
              (if (identical? reaction (:reaction (get new k)))
                {:reaction reaction}
                (build-and-classify! frame-id query-v k)))
            (build-and-classify! frame-id query-v k)))
        ;; The frame's cache is gone (destroyed before the build began).
        {:recovery :frame-destroyed}))))

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
  `:input-kind :db` + empty `:input-signals`). `metadata` is NORMALIZED ONCE
  through the SAME `normalize-sub-metadata` seam public `reg-sub` runs
  (rf2-vxgfnd.219), so an inline registration accepts, rejects, and elides
  identical metadata: a retired bare `:spec` hard-errors with
  `:rf.error/retired-registration-key`, a malformed `:sensitive` / `:large`
  declaration raises `:rf.error/bad-classification`, and production strips
  `:doc` — neither looser nor stricter than the public path. Normalization is
  side-effect-free with respect to the global registrar; the runtime-owned
  runnable slots are installed AFTER it, so metadata can never override them.

  `id` is the AUTHORED descriptor id (e.g. `:counter/value`) threaded from the
  image-assembly lowering boundary (rf2-vxgfnd.257), so a retired/unknown-key or
  bad-classification diagnostic names the author's subscription — never a
  synthetic fallback.

  The normalized meta is projected onto the runnable descriptor's top level,
  matching the shape `reg-sub` installs so runtime consumers (return-schema
  validation, classification projection) read the same slots regardless of
  registration path. It is ALSO carried under `:metadata` (when non-empty) — the
  ONE normalized representation — so image assembly threads the same doc-stripped
  map into the descriptor's nested `:metadata` for inspection/dedupe rather than
  retaining a second, raw copy that would leak dev-only `:doc` into production."
  [id metadata impl]
  (let [normalized (normalize-sub-metadata 'rf/reg-sub id metadata)]
    (cond-> (assoc normalized
                   :handler-fn    impl
                   :input-kind    :db
                   :input-signals [])
      (seq normalized) (assoc :metadata normalized))))

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
