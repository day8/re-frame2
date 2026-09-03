(ns re-frame.conformance
  "DSL interpreter + shared harness primitives for the conformance fixtures.

  The conformance corpus represents handler bodies as data — a small DSL
  the harness interprets into native fns. Per
  spec/conformance/README.md §Handler-body DSL ops. This namespace ALSO
  owns the stable, PURE primitives every conformance runner shares — the
  handler/cofx realisation collapse (`normalize-event-handler`,
  `collect-cofx-keys`, `realise-cofx-supplier`) and the expectation matchers
  (`submap?`, `check-trace-emissions`, `resolve-sub`). It lives in `core/src`
  (not `core/test`) precisely so per-feature artefacts' test suites reach it
  cross-classpath without pulling core's test tree — a runner's fixture
  discovery, capability claims, execution loop, and reporting stay LOCAL
  (rf2-wy414k).

  Operator set:

  Data ops:
    [:set path value]               assoc-in db at path with value
    [:update path fn-form]          update-in db at path with fn-form
    [:get path]                     (sub bodies) read db at path

  Effect ops:
    [:fx fx-id args]                emit a single fx
    [:fx [[fx-id args] ...]]        emit multiple
    [:dispatch event-vec]           sugar for [:fx :dispatch event-vec]
    [:make-frame-capture path frame-id config]
                                    (fx bodies) make-frame mid-cascade, capture
                                    the thrown :rf.error/id into app-db at path
                                    (EP-0027 handler-time construction guard)

  Control:
    [:throw msg]                    throw
    [:noop]                         no-op
    [:return-raw value]             (event-fx) return `value` VERBATIM as
                                    the effect-map — bypasses the well-
                                    shaped builder so a fixture can author
                                    a MALFORMED effect-map (the proactive
                                    fx shape-policing categories)

  Reflection (used as args to data ops):
    [:event-arg n]                  the n-th element of event (0-based)
    [:event-arg n default-val]      n-th element of event; default-val if nil
    [:get-event-arg n :key]         (get (nth event n) :key)  -- key-access
    [:get-event-arg n :key default] key-access with default if missing/nil
    [:db-get path]                  read db at path
    [:fn :keyword]                  reference a builtin
    [:fn :keyword arg1 ...]         partial application of a builtin

  Builtins (the reserved `:fn` set, grouped by purpose — the canonical
  fixture-spec-1.0 vocabulary; spec/conformance/README.md §Handler-body DSL
  builtins and spec/Spec-Schemas.md carry the same set):
    numeric     :inc :dec :+ :- :* :/
    comparison  :>= :<= :> :<
    equality    :=  :not=
    boolean     :and :or :not
    collection  :conj :assoc :dissoc :count
    identity    :identity
    fixture     :item-amount"
  (:require [re-frame.error :as rf.error]
            [re-frame.late-bind :as rf.late-bind]
            ;; rf2-j81hs — `[:view-ref id]` in a fixture view body resolves
            ;; to the registered handler-fn, so the DSL reads the registry.
            [re-frame.registrar :as rf.registrar]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- builtins -------------------------------------------------------------

(defn- builtin [k]
  (case k
    ;; Tolerant numeric ops — nil starting state is implicit-zero for the
    ;; fixtures (mirrors how re-frame app code typically uses (fnil inc 0)).
    :inc       (fnil inc 0)
    :dec       (fnil dec 0)
    :+         +
    :-         -
    :*         *
    :/         /
    :>=        >=
    :<=        <=
    :>         >
    :<         <
    :=         =
    :not=      not=
    :and       (fn [& xs] (every? identity xs))
    :or        (fn [& xs] (boolean (some identity xs)))
    :not       not
    :identity  identity
    :conj      conj
    :assoc     assoc
    :dissoc    dissoc
    :count     (fn [x]
                 ;; Fixtures use [:fn :count] to assert sub-exception
                 ;; recovery; raising a recognisable message lets the
                 ;; error trace expose a stable :exception-message.
                 ;; The rf.error/sub-exception fixture sets :items to a
                 ;; string ("broken") and expects counting to fail with
                 ;; "cannot count a string" — we honour that intent by
                 ;; refusing strings AND Characters (a string element
                 ;; landed as a char during seq iteration).
                 ;; The :count builtin's MESSAGE is deliberately surfaced
                 ;; downstream as the sub-exception trace's
                 ;; :exception-message (the conformance corpus pins it),
                 ;; so — like the :throw DSL op — the message string stays
                 ;; the human prose rather than the discriminator kw. The
                 ;; canonical :rf.error/id rides on ex-data for structured
                 ;; branching.
                 (cond
                   ;; rf2:builder-bypass-ok — :count's message is pinned by the
                   ;; conformance corpus as the sub-exception :exception-message;
                   ;; it stays bare prose without the [:rf.<id>] token by design.
                   (string? x)    (throw (ex-info "cannot count a string"
                                                   {:rf.error/id :rf.error/conformance-cannot-count-string
                                                    :where    'rf/conformance-eval
                                                    :recovery :no-recovery}))
                   ;; rf2:builder-bypass-ok — see :count message-pinning note above.
                   (char? x)      (throw (ex-info "cannot count a string"
                                                   {:rf.error/id :rf.error/conformance-cannot-count-string
                                                    :where    'rf/conformance-eval
                                                    :recovery :no-recovery}))
                   ;; rf2:builder-bypass-ok — see :count message-pinning note above.
                   (nil? x)       (throw (ex-info "cannot count nil"
                                                   {:rf.error/id :rf.error/conformance-cannot-count-nil
                                                    :where    'rf/conformance-eval
                                                    :recovery :no-recovery}))
                   :else          (count x)))
    :item-amount (fn [item] (* (:qty item) (:price item)))
    (rf.error/throw-error!
      :rf.error/conformance-unknown-fn-builtin
      'rf/conformance-eval
      (str "unknown :fn builtin " k)
      {:recovery :no-recovery
       :extra    {:builtin k}})))

;; ---- value resolver -------------------------------------------------------

(declare resolve-value)

(defn- resolve-fn-form
  "[:fn :keyword arg1 ...] → fn. With extra args, returns a partially-
  applied fn (one-arg-from-the-runtime, the partial-args bound)."
  [form ctx]
  (let [[_ k & extra-args] form
        f (builtin k)
        resolved (mapv #(resolve-value % ctx) extra-args)]
    (if (seq resolved)
      ;; The runtime calls the fn with one arg (the slot value); we apply
      ;; with the runtime-arg first, then our partial args. e.g.
      ;; [:update [:log] [:fn :conj] :a]  →  (update db [:log] (fn [coll] (conj coll :a)))
      (fn [x] (apply f x resolved))
      f)))

(defn- resolve-value
  "Resolve a value form against the runtime context.
  ctx = {:db <db> :event <event-vec> :cofx <cofx-map>}.

  Walks recursively into maps and literal vectors so reflection forms
  inside compound values are resolved (e.g. {:id [:event-arg 1]})."
  [form ctx]
  (cond
    (vector? form)
    (case (first form)
      :event-arg    (let [[_ idx default-val] form
                          v (get (:event ctx) idx)]
                      ;; The 3rd element is unconditionally a default-for-nil
                      ;; — no type-dispatch. For map-value key-access, use
                      ;; the explicit [:get-event-arg n :key] form below.
                      (if (and (>= (count form) 3) (nil? v))
                        default-val
                        v))
      :get-event-arg (let [[_ idx k default-val] form
                           m (get (:event ctx) idx)
                           v (get m k)]
                       ;; [:get-event-arg n :key]            -- (get (nth event n) :key)
                       ;; [:get-event-arg n :key default]    -- with default if missing/nil
                       (if (and (>= (count form) 4) (nil? v))
                         default-val
                         v))
      :db-get       (let [[_ path default] form
                          v (get-in (:db ctx) path)]
                      (if (and (nil? v) (>= (count form) 3)) default v))
      :get          (let [[_ path] form
                          runtime-path? (and (vector? path)
                                             (keyword? (first path))
                                             (= "rf.runtime" (namespace (first path))))]
                      ;; Read from :data when present (machine bodies),
                      ;; else from :db (event bodies). The two contexts
                      ;; share the same shorthand.
                      ;; EP-0001 (rf2-vzld77): a `[:rf.runtime/… …]` path in an
                      ;; event body reads the RUNTIME-DB partition (the
                      ;; `:rf.db/runtime` coeffect), not app-db — durable
                      ;; machine / routing / SSR state lives there.
                      (cond
                        (contains? ctx :data) (get-in (:data ctx) path)
                        runtime-path?         (get-in (get-in ctx [:cofx :rf.db/runtime]) path)
                        :else                 (get-in (:db ctx) path)))
      :fn           (resolve-fn-form form ctx)
      :cofx-key     (get (:cofx ctx) (second form))
      :cofx-without (let [excluded (set (rest form))]
                      (apply dissoc (:cofx ctx) excluded))
      ;; otherwise it's a literal vector — walk into elements so any
      ;; reflection forms nested inside still resolve.
      (mapv #(resolve-value % ctx) form))

    (map? form)
    (reduce-kv (fn [m k v] (assoc m k (resolve-value v ctx))) {} form)

    :else form))

(defn resolve-value*
  "Public alias for the private resolve-value, used by the conformance
  test runner for machine-handler realisation."
  [form ctx]
  (resolve-value form ctx))

(defn eval-value*
  "For machine action/guard bodies: evaluate [:fn :k a b ...] as
  '(f a b ...)' rather than as a partial fn awaiting one runtime arg.

  Resolves nested forms and then applies the builtin to the resolved
  args. For non-:fn forms, falls back to resolve-value."
  [form ctx]
  (cond
    (and (vector? form) (= :fn (first form)))
    (let [[_ k & extra-args] form
          f (#'re-frame.conformance/builtin k)
          resolved (mapv #(resolve-value % ctx) extra-args)]
      (apply f resolved))

    :else (resolve-value form ctx)))

;; ---- flow-body realisation -----------------------------------------------
;;
;; Per Spec 013, a flow's :derive is a positional fn — `(fn [in1 in2 ...] ...)`.
;; The conformance corpus describes flow bodies as DSL (e.g. `[[:fn :* [:event-arg 0] [:event-arg 1]]]`)
;; so the same fixture is portable across implementations. The harness
;; realises the body into a real fn whose positional args bind to
;; `[:event-arg n]` references inside the DSL.
;;
;; This is the same evaluation shape as `eval-value*` (eager :fn application)
;; lifted over a vector of body steps; the LAST step's resolved value is the
;; output. Every step is evaluated for its side-effect-free value; non-final
;; steps' values are discarded (matches the fixture corpus, where bodies are
;; one-step expressions).

(defn realise-flow-output-fn
  "DSL body steps → flow :derive fn taking positional inputs.

  Each `[:event-arg n]` in the body resolves to the n-th positional input.
  Returns a fn `(fn [& inputs] ...)` ready for `reg-flow`'s :derive slot."
  [steps]
  (fn [& inputs]
    (let [ctx       {:event (vec inputs) :db nil}
          last-step (last steps)]
      (cond
        ;; Terminal :fn step — eager apply (mirrors eval-value*).
        (and (vector? last-step) (= :fn (first last-step)))
        (let [[_ k & extra-args] last-step
              f         (builtin k)
              resolved  (mapv #(resolve-value % ctx) extra-args)]
          (apply f resolved))

        :else
        (resolve-value last-step ctx)))))

;; ---- :rf.fx/reg-http-interceptor — DSL `:before` body --------------------
;;
;; Per rf2-yhfgf — the conformance corpus drives `:rf.fx/reg-http-interceptor`
;; via pure-data EDN, so an interceptor's `:before` slot is a DSL body
;; rather than a fn literal. The harness realises that DSL into a real
;; `(fn [ctx] ctx')` at fx-resolution time so the chain runner (which
;; lives in `re-frame.http.middleware`, unaware of the DSL) can invoke
;; it the same way it invokes a hand-written `:before`.
;;
;; Body operator set is minimal — the chain's contract is "transform the
;; request map and return the modified ctx", so the DSL covers the pieces
;; that touch ctx.:request plus the dispatch escape hatch fixtures use to
;; record "the interceptor fired" observably:
;;
;;   [:assoc-in-request path value]   — `(assoc-in ctx [:request & path] value)`
;;   [:dispatch event-vec]            — enqueue `event-vec` on the originating
;;                                       frame (lands in the next drain cycle)
;;
;; The realised fn looks up the router's dispatch entry point via the
;; `:router/dispatch!` late-bind hook so the conformance ns stays free
;; of a static require on `re-frame.router` (mirrors how `frame.cljc`
;; reaches dispatch from the lifecycle path). The dispatched event is
;; the deterministic seam fixtures use to write "interceptor fired"
;; markers into app-db.

(defn realise-interceptor-before-fn
  "DSL `:before` body → `(fn [chain-ctx] chain-ctx')`.

  Operators:
    [:assoc-in-request path v]   — `(assoc-in ctx [:request & path] v)`
    [:dispatch event-vec]        — enqueue `event-vec` on `(:frame ctx)`;
                                    the dispatch lands in the next drain
                                    cycle, after the current request
                                    settles.
    [:noop]                       — explicit no-op

  Value forms inside `path` / `v` / `event-vec` resolve via `resolve-value`
  against a ctx exposing `:request` (the live request slot at body time)
  and `:event` (the originating event vector)."
  [steps]
  (fn [chain-ctx]
    (let [dispatch! (rf.late-bind/get-fn :router/dispatch!)
          frame-id  (:frame chain-ctx)]
      (reduce
        (fn [acc step]
          (let [resolver-ctx {:request (:request acc)
                              :event   (:event acc)
                              :db      (:request acc)}]
            (case (first step)
              :assoc-in-request
              (let [[_ path v] step
                    rv (resolve-value v resolver-ctx)]
                (update acc :request #(assoc-in % path rv)))

              :dispatch
              (let [[_ event-form] step
                    ev (resolve-value event-form resolver-ctx)]
                (when dispatch!
                  (dispatch! ev {:frame frame-id}))
                acc)

              :noop acc

              (rf.error/throw-error!
                :rf.error/conformance-unknown-before-op
                'rf/conformance-eval
                "unknown :before DSL op"
                {:recovery :no-recovery
                 :extra    {:op      step
                            :allowed #{:assoc-in-request
                                       :dispatch :noop}}}))))
        chain-ctx
        steps))))

(defn- resolve-fx-args
  "Resolve fx args, leaving DSL fields that the conformance harness owns
  alone. `:rf.fx/reg-flow`'s `:body` is itself a DSL body — it must NOT
  be walked through resolve-value (which would treat `[:fn :k ...]` as
  a value form and partially-apply it). Pull `:body` aside, resolve the
  rest of the map normally, then realise `:body` into `:derive`.

  Per rf2-yhfgf — `:rf.fx/reg-http-interceptor`'s `:before` is also a
  DSL body. Same shape, different realisation: the body becomes a
  `(fn [chain-ctx] chain-ctx')` for the request-side middleware chain."
  [fx-id args ctx]
  (case fx-id
    ;; rf2-bqstzr — `:rf.fx/reg-flow` now carries the 3-slot triple
    ;; `[flow-id metadata derive-fn]` (matching the `reg-flow` macro / fn). The
    ;; fixture DSL still describes a flow with a single map `{:id … :inputs …
    ;; :body … :output-path …}` (a data description, not the API call), so this
    ;; interpreter LOWERS that map into the triple: pull the `:id` out as the
    ;; first slot, realise `:body` → the pure `derive-fn` third slot, and leave
    ;; the remaining reflection keys (`:inputs` / `:output-path` / `:doc` /
    ;; `:schema`) as the metadata middle slot. A fixture that already supplies a
    ;; literal triple vector passes through resolved element-wise.
    :rf.fx/reg-flow
    (cond
      (and (map? args) (contains? args :body))
      (let [body           (:body args)
            derive-fn      (realise-flow-output-fn body)
            flow-id        (:id args)
            metadata       (resolve-value (dissoc args :id :body) ctx)]
        [flow-id metadata derive-fn])

      (vector? args)
      (mapv #(resolve-value % ctx) args)

      :else
      (resolve-value args ctx))

    :rf.fx/reg-http-interceptor
    (if (and (map? args) (contains? args :before))
      (let [before-body    (:before args)
            other-resolved (resolve-value (dissoc args :before) ctx)]
        (assoc other-resolved :before
               (realise-interceptor-before-fn before-body)))
      (resolve-value args ctx))

    (resolve-value args ctx)))

;; ---- event-db / event-fx interpreter -------------------------------------

(defn- apply-step
  "Apply one DSL step. Returns a map with :db (possibly updated) and :fx
  (possibly extended). ctx contains the current :db and :event."
  [{:keys [db fx event] :as ctx} step]
  (case (first step)
    :noop      ctx

    ;; :return-raw makes the realised event-fx handler return a LITERAL
    ;; value verbatim (reflection forms inside it resolved), bypassing the
    ;; well-shaped `{:db .. :fx ..}` builder. This is the ONLY way the
    ;; corpus can author a handler that returns a MALFORMED effect-map —
    ;; the effect ops (`:set` / `:update` / `:fx`) always produce a
    ;; well-shaped map, so the proactive fx shape-policing categories
    ;; (`:rf.error/effect-map-shape` cases a/b/c and
    ;; `:rf.error/effect-handler-bad-return`, Spec 009 §Error contract)
    ;; were previously unreachable from a fixture. A body carrying a
    ;; `:return-raw` step is always realised as event-fx (see
    ;; `needs-fx-handler?`) so the raw return reaches `commit-fx-effects`,
    ;; the policing site. Stashed under a private sentinel key the
    ;; realiser reads after the reduction; siblings before / after it are
    ;; ignored (the raw return is the whole story). The value is resolved
    ;; through `resolve-value` so `[:event-arg n]` etc. still work inside
    ;; a malformed map.
    :return-raw (let [[_ value] step]
                  (assoc ctx ::return-raw (resolve-value value ctx)))

    :set       (let [[_ path value] step
                     v (resolve-value value ctx)]
                 ;; assoc-in with an empty path would associate at key nil
                 ;; (Clojure's destructuring quirk). Treat empty path as
                 ;; "replace whole db" — used by hydrate handlers.
                 (assoc ctx :db
                        (if (empty? path) v (assoc-in db path v))))

    ;; EP-0001 (rf2-vzld77): seed / mutate the frame's RUNTIME-DB partition.
    ;; `:set-runtime [path value]` assoc-in's into the runtime-db value
    ;; threaded from the `:rf.db/runtime` coeffect; the realised event-fx
    ;; handler emits the accumulated value under `:rf.db/runtime`. Used by
    ;; fixtures to seed / assert machine snapshots / route slice / etc. now
    ;; that durable framework runtime state lives in runtime-db.
    :set-runtime (let [[_ path value] step
                       v   (resolve-value value ctx)
                       rdb (or (:runtime-db ctx)
                               (get-in ctx [:cofx :rf.db/runtime])
                               {})]
                   (assoc ctx :runtime-db
                          (if (empty? path) v (assoc-in rdb path v))))

    :update    (let [[_ path fn-form & extra-args] step
                     f             (resolve-value fn-form ctx)
                     resolved-args (mapv #(resolve-value % ctx) extra-args)]
                 (assoc ctx :db (apply update-in db path f resolved-args)))

    :merge-into-db
    (let [[_ value-form] step
          payload (resolve-value value-form ctx)]
      (assoc ctx :db (merge db payload)))

    :fx        (let [[_ a b] step]
                 (cond
                   ;; Multi-form: [:fx [[fx-id args] ...]]
                   (and (vector? a) (every? vector? a))
                   (assoc ctx :fx (into (or fx [])
                                        (mapv (fn [[fx-id fx-args]]
                                                [fx-id (resolve-fx-args fx-id fx-args ctx)])
                                              a)))

                   ;; Single form: [:fx fx-id args]
                   :else
                   (assoc ctx :fx
                          (conj (or fx [])
                                [a (resolve-fx-args a b ctx)]))))

    :dispatch  (let [ev (resolve-value (second step) ctx)]
                 (assoc ctx :fx (conj (or fx []) [:dispatch ev])))

    ;; Per Cross-Spec Interaction §14 (rf2-60szl): a fixture may emit a
    ;; `[:dispatch-sync event-vec]` step from an fx handler body. The
    ;; realise-fx-handler invokes the runner's dispatch-sync! helper for
    ;; each such pair, which calls rf/dispatch-sync while mid-drain so
    ;; the router's in-drain guard surfaces :rf.error/dispatch-sync-in-handler.
    :dispatch-sync (let [ev (resolve-value (second step) ctx)]
                     (assoc ctx :fx (conj (or fx []) [:dispatch-sync ev])))

    ;; Per EP-0027 §Handler-time guard (rf2-emqiqk): an fx body may invoke
    ;; `make-frame` while the originating handler cascade is in
    ;; flight (the `do-fx` walk runs under the router's `*handler-scope*`
    ;; binding). The construction engine rejects it LOUD —
    ;; `:rf.error/frame-construction-in-handler`.
    ;; A user fx handler throw is CAUGHT by `do-fx` (re-emitted as
    ;; `:rf.error/fx-handler-exception`, NOT the guard's own discriminator), so
    ;; the op CAPTURES the thrown `:rf.error/id` directly and the runner writes
    ;; it into the frame's app-db at `path` — making the guard discriminator a
    ;; pure-data `:final-app-db` observable (the host-agnostic lift of the
    ;; capture-into-atom shape the JVM/CLJS unit tests use). `:rf/no-error` is
    ;; written when the construction did NOT throw (a regression that re-enabled
    ;; mid-cascade construction).
    :make-frame-capture (let [[_ path child-id child-config] step
                              cid (resolve-value child-id ctx)
                              cfg (resolve-value child-config ctx)]
                          (assoc ctx :fx (conj (or fx [])
                                               [:make-frame-capture path cid cfg])))

    ;; The :throw DSL op exists to surface a FIXTURE-SUPPLIED message
    ;; downstream (the runtime re-emits it as :exception-message), so —
    ;; uniquely among conformance throws — the message string stays the
    ;; fixture's text rather than the discriminator kw. The canonical
    ;; :rf.error/id slot still rides on ex-data for structured branching.
    ;; rf2:builder-bypass-ok — the :throw op's message IS the fixture-supplied
    ;; text, re-emitted downstream as :exception-message; it stays the prose
    ;; (no [:rf.<id>] token) by design. The :rf.error/id rides on ex-data.
    :throw     (throw (ex-info (str (second step))
                               {:rf.error/id   :rf.error/conformance-throw-step
                                :where         'rf/conformance-eval
                                :recovery      :no-recovery
                                :from-fixture? true}))

    ;; :get and :reduce-input are sub-body ops; `realise-sub` reads them
    ;; separately. Treated as no-op here.
    :get       ctx
    :reduce-input ctx
    :db-get    ctx

    (rf.error/throw-error!
      :rf.error/conformance-unknown-dsl-op
      'rf/conformance-eval
      "unknown DSL op"
      {:recovery :no-recovery
       :extra    {:op step}})))

(defn realise-event-db-handler
  "DSL → an event-db handler fn (db, event) → new-db.
  Steps run in order; only :db is observed from the result."
  [steps]
  (fn [db event]
    (let [final (reduce apply-step
                        {:db db :event event :fx [] :cofx {:db db :event event}}
                        steps)]
      (:db final))))

(defn realise-event-fx-handler
  "DSL → an event-fx handler fn (cofx, event) → effects-map.
  Steps run in order; both :db and :fx are observed.

  cofx is threaded into ctx so [:cofx-key k] / [:cofx-without ...] forms
  resolve against the actual coeffect map (envelope keys included).

  Note: handlers that do not change :db still need to commit a :db effect
  if they wrote to it — we always include :db when the body emitted any
  :set/:update steps, since that's how the fixtures observe captures of
  cofx data into the db."
  [steps]
  (fn [cofx event]
    (let [db    (:db cofx)
          ;; EP-0001 (rf2-vzld77): thread the runtime-db partition value so a
          ;; `:set-runtime` step can seed / mutate it and the handler emits a
          ;; `:rf.db/runtime` effect.
          rdb   (:rf.db/runtime cofx)
          final (reduce apply-step
                        {:db db :event event :fx [] :cofx cofx}
                        steps)
          db-changed?      (not= (:db final) db)
          runtime-changed? (and (contains? final :runtime-db)
                                (not= (:runtime-db final) rdb))]
      ;; A `:return-raw` step short-circuits the well-shaped builder —
      ;; the handler returns the stashed literal verbatim (which may be a
      ;; malformed effect-map, or a non-map for the bad-return path). This
      ;; is the only way the corpus reaches the proactive fx shape-
      ;; policing categories (Spec 009 §Error contract).
      (if (contains? final ::return-raw)
        (::return-raw final)
        (cond-> {}
          db-changed?       (assoc :db (:db final))
          runtime-changed?  (assoc :rf.db/runtime (:runtime-db final))
          (seq (:fx final)) (assoc :fx (:fx final)))))))

(defn- resolve-view-ref
  "Resolve one `[:view-ref <id> & args]` marker to `[<handler-fn> & args]`,
  mapping `walk-arg` over the args."
  [form walk-arg]
  (let [id      (second form)
        args    (mapv walk-arg (drop 2 form))
        view-fn (:handler-fn (rf.registrar/lookup :view id))]
    (when-not view-fn
      (throw (ex-info (str "conformance fixture: [:view-ref " id "] names "
                           "no registered view — check :fixture/handlers "
                           ":view and the registration order")
                      {:view-ref id})))
    (into [view-fn] args)))

(defn realise-view-refs
  "Recursively replace every `[:view-ref <id> & args]` marker in `form`
  with `[(rf/view id) & args]` — a CALLABLE head this host can render.

  rf2-j81hs. A conformance fixture is language-neutral EDN, so it cannot
  spell a callable head; and a keyword head is now a DOM / custom element
  on every host, so a fixture can no longer name a view by writing its id
  as the head (`[:greeting \"world\"]` renders `<greeting>world</greeting>`).
  `[:view-ref :greeting \"world\"]` is the portable spelling — each port
  resolves it to whatever ITS substrate spells as \"callable head + args\".

  The marker is deliberately EXPLICIT rather than an implicit \"a keyword
  head here means a view\" rule: an implicit rule would recreate, at the
  fixture layer, the exact server/client ambiguity this bead removed —
  and fixtures are the artefact other implementations learn the grammar
  from, so they must not model a rule the grammar rejects.

  Used by the fixture RUNNERS for call inputs (`:input` / `:subtree`).
  View BODIES go through `walk-hiccup`, which resolves the same marker
  alongside the other reflection forms, so fixtures use one convention
  everywhere."
  [form]
  (cond
    (and (vector? form) (= :view-ref (first form)))
    (resolve-view-ref form realise-view-refs)

    (vector? form) (mapv realise-view-refs form)
    (map? form)    (reduce-kv (fn [m k v] (assoc m k (realise-view-refs v))) {} form)
    :else          form))

(defn- walk-hiccup
  "Recursively walk a hiccup tree, replacing reflection forms with their
  resolved values. Used by realise-view-handler so view bodies can
  embed [:event-arg n] / [:db-get path] / [:fn ...] / [:view-ref id]
  inside hiccup."
  [form ctx]
  (cond
    (and (vector? form)
         (#{:event-arg :db-get :fn :get} (first form)))
    (resolve-value form ctx)

    ;; `[:view-ref <id> & args]` — invoke a registered view HERE.
    ;;
    ;; rf2-j81hs. Fixtures used to compose views by writing the view's id
    ;; as a hiccup head (`[:streaming.test/comments-section]`) and letting
    ;; the JVM SSR emitter resolve it through the registry. That
    ;; resolution is gone: a keyword head is a DOM / custom element on
    ;; every host, so the old spelling now renders
    ;; `<comments-section></comments-section>`.
    ;;
    ;; A fixture is language-neutral EDN read by every port, so it cannot
    ;; carry a Clojure `(rf/view :id)` form. This marker is the portable
    ;; equivalent: a port resolves `[:view-ref id & args]` to whatever
    ;; ITS substrate spells as "callable head + args". Resolving to the
    ;; handler-fn here yields exactly `[(rf/view id) & args]`.
    ;;
    ;; Deliberately EXPLICIT rather than re-teaching the runner that a
    ;; keyword head means a view: an implicit rule at the fixture layer
    ;; would recreate, one level down, the very server/client ambiguity
    ;; this bead removed — and the fixtures are the artefact that OTHER
    ;; implementations learn the grammar from, so they must not model a
    ;; rule the grammar rejects.
    (and (vector? form) (= :view-ref (first form)))
    (resolve-view-ref form #(walk-hiccup % ctx))

    (vector? form)
    (mapv #(walk-hiccup % ctx) form)

    (map? form)
    (reduce-kv (fn [m k v] (assoc m k (walk-hiccup v ctx))) {} form)

    :else form))

(defn realise-view-handler
  "DSL → a view handler fn that, given the args passed to the view (e.g.
  [\"world\"] for [:greeting \"world\"]), returns a hiccup tree with
  reflection forms resolved.

  Conventions:
    [:hiccup <tree>] — the body is the hiccup tree.
    [:event-arg n]   — indexes args (no event-id offset).
    [:db-get path]   — reads from the implicit db (currently nil)."
  [steps]
  (fn [& args]
    (let [hiccup-step (some (fn [s] (when (= :hiccup (first s)) s)) steps)
          tree        (when hiccup-step (second hiccup-step))
          ctx         {:event (vec args) :db nil}]
      (when tree (walk-hiccup tree ctx)))))

(defn realise-on-spawn-handler
  "DSL → an on-spawn callback fn. Signature `(fn [{:keys [data id]}] _)`
  per Spec 005 §Declarative :spawn (rf2-grw4i / rf2-v0rrr — single
  context-map arg, advisory return).

  The on-spawn callback receives the parent machine's `:data` and the
  just-allocated actor id; the return value is advisory only — the
  runtime tracks the spawned id at `[:rf.runtime/machines :spawned <parent> <invoke-id>]`
  regardless. The DSL body's `:set` ops are realised here for body
  inspection / trace symmetry with regular actions; the resulting map is
  RETURNED for compatibility with corpus authors who want to observe
  `:set`-effects via the conformance harness's structural diff, but the
  RUNTIME ignores the return value entirely."
  [steps]
  (fn [{:keys [data id]}]
    (let [synthetic-event [::on-spawn id]]
      (reduce
        (fn [d step]
          (case (first step)
            :set (let [[_ path v] step
                       resolved (resolve-value v {:event synthetic-event
                                                  :data d
                                                  :db   d})]
                   (if (empty? path) resolved (assoc-in d path resolved)))
            d))
        data
        steps))))

(defn realise-fx-handler
  "DSL → an fx handler fn. fx handlers receive ({:frame frame-id} args).

  A fixture's fx body may :throw, :noop, mutate the frame's app-db via
  :set/:update, or :dispatch a follow-up event (used to model
  http-stub-style fx that synthesise a result). The args is exposed
  to the body as if it were an 'event' — i.e. [:event-arg 1] resolves
  to the args value (the synthetic event is [fx-id args]).

  Per Cross-Spec Interaction §14 (rf2-60szl) the body may also carry
  `[:dispatch-sync event-vec]` — used by fixtures that pin the
  framework's `dispatch-sync-in-handler` ban. The op invokes the
  runner's `dispatch-sync!` helper, which calls `rf/dispatch-sync` while
  the surrounding handler is mid-drain; the router's `:in-drain?` guard
  surfaces `:rf.error/dispatch-sync-in-handler`.

  read-db!/write-db!/dispatch!/dispatch-sync!/make-frame! are wired
  by the runner so this namespace stays free of internal substrate / router
  deps."
  [fx-id steps {:keys [read-db! write-db! dispatch! dispatch-sync!
                       make-frame!]}]
  (fn [{:keys [frame]} args]
    (let [db              (read-db! frame)
          synthetic-event [fx-id args]
          final (reduce apply-step
                        {:db db :event synthetic-event :fx []
                         :cofx {:frame frame}}
                        steps)]
      (when (not= db (:db final))
        (write-db! frame (:db final)))
      ;; Any :dispatch fx the body produced are enqueued on the same frame.
      ;; Per rf2-60szl, :dispatch-sync forms are invoked synchronously
      ;; through the helper — the router's in-drain guard surfaces the
      ;; structured error when this fires inside a handler cascade.
      ;;
      ;; Per rf2-emqiqk the :make-frame-capture pair
      ;; invokes `make-frame` mid-cascade and CAPTURES the thrown
      ;; `:rf.error/id` into the originating frame's app-db at `path` (the guard
      ;; throw would otherwise be swallowed into `:rf.error/fx-handler-exception`
      ;; by `do-fx`). `:rf/no-error` records a no-throw — a regression that
      ;; re-enabled mid-cascade construction.
      (doseq [pair (:fx final)]
        (cond
          (and (vector? pair) (= :dispatch (first pair)))
          (dispatch! (second pair) frame)

          (and (vector? pair) (= :dispatch-sync (first pair)) dispatch-sync!)
          (dispatch-sync! (second pair) frame)

          (and (vector? pair) (= :make-frame-capture (first pair)) make-frame!)
          (let [[_ path child-id child-config] pair
                err-id (try (make-frame! child-id child-config) nil
                            (catch #?(:clj clojure.lang.ExceptionInfo
                                      :cljs cljs.core/ExceptionInfo) e
                              (:rf.error/id (ex-data e))))]
            (write-db! frame
                       (assoc-in (read-db! frame) path (or err-id :rf/no-error))))))
      nil)))

(defn- needs-fx-handler?
  "Returns true if the body uses any op or value form that requires the
  full coeffect map (and thus must be wrapped as event-fx, not event-db).
  Detects :fx, :dispatch ops and :cofx-key / :cofx-without value forms."
  [steps]
  (letfn [(uses-cofx? [v]
            (and (vector? v)
                 (or (#{:cofx-key :cofx-without} (first v))
                     ;; EP-0001 (rf2-vzld77): a `[:get [:rf.runtime/… …]]`
                     ;; value form reads the runtime-db coeffect, so the body
                     ;; needs the full cofx map (event-fx, not event-db).
                     (and (= :get (first v))
                          (vector? (second v))
                          (keyword? (first (second v)))
                          (= "rf.runtime" (namespace (first (second v))))))))]
    (some (fn [step]
            (or (= :fx (first step))
                (= :dispatch (first step))
                ;; EP-0001 (rf2-vzld77): a `:set-runtime` body writes the
                ;; runtime-db partition (a `:rf.db/runtime` effect), which
                ;; only the event-fx shape can return — so force event-fx.
                (= :set-runtime (first step))
                ;; A `:return-raw` body must be event-fx so the literal
                ;; return reaches `commit-fx-effects` — the proactive
                ;; fx shape-policing site. An event-db handler would
                ;; treat the return as the new db, never policing it.
                (= :return-raw (first step))
                (some uses-cofx? (tree-seq coll? seq step))))
          steps)))

(defn realise-event-handler
  "Pick the right handler shape based on whether the body emits any fx
  or reads cofx beyond db/event. If it does, wrap as event-fx; else event-db."
  [steps]
  (if (needs-fx-handler? steps)
    [:fx (realise-event-fx-handler steps)]
    [:db (realise-event-db-handler steps)]))

(defn normalize-event-handler
  "Collapse the `[body-shape handler]` pair `realise-event-handler` returns into
  the single EP-0018 `reg-event` shape — a `(cofx-in → effects-map-or-nil)` fn —
  so a registration site never branches on the DSL-internal body-shape.

  `body-shape` is an interpreter distinction (does the body read cofx / emit fx),
  NOT a public `:event/kind` (EP-0018 removed the public event sub-kind model). A
  `:db` body `(fn [db event] new-db)` is lifted to `(fn [cofx event] {:db …})` —
  read db from the coeffects, lower the returned db into a `{:db …}` effect (same
  observable behaviour); an `:fx` body is already the single form and passes
  through.

  Shared harness primitive (rf2-wy414k) — the pair→single-form collapse lives
  in exactly one place so every conformance runner registers events identically."
  [[body-shape handler]]
  (case body-shape
    :db (fn [{:keys [db]} event] {:db (handler db event)})
    :fx handler))

(defn collect-cofx-keys
  "Walk DSL body `steps` and return the SET of every cofx-id referenced via a
  `[:cofx-key K]` form. A runner uses the result to auto-wire a consuming
  event's `:rf.cofx/requires` declaration (EP-0017 model — rf2-mrp8jg / rf2-g25p).

  Shared harness primitive (rf2-wy414k)."
  [steps]
  ;; `tree-seq` flattens the step tree; the transducer picks the `[:cofx-key K]`
  ;; nodes and takes their `K` (rf2-b8goi — was a scratch atom + `doseq` walk).
  ;; `tree-seq` also descends INTO a matched node where the hand-rolled walk
  ;; stopped, so a `[:cofx-key K]` whose K nested a further `[:cofx-key …]`
  ;; would now also be collected. Unreachable under the DSL grammar (a cofx-id
  ;; is a keyword), and a superset either way — never a miss.
  (into #{}
        (comp (filter #(and (vector? %) (= :cofx-key (first %))))
              (map second))
        (tree-seq coll? seq steps)))

(defn realise-cofx-supplier
  "DSL body `steps` → a value-returning cofx supplier `(fn [] value)` (EP-0017
  model — rf2-mrp8jg). Each `:set` step declares the value the supplier returns;
  the runtime delivers it FLAT under the cofx-id when a handler declares it via
  `:rf.cofx/requires`. The `:set` value passes through `eval-value*` (rf2-g25p)
  so reflection forms resolve; multiple `:set` steps run in order and the last
  wins (single-delivery convention).

  Shared harness primitive (rf2-wy414k)."
  [steps]
  (fn []
    (reduce (fn [v step]
              (case (first step)
                :set  (let [[_ _path value] step]
                        (eval-value* value {}))
                :noop v
                v))
            nil
            steps)))

;; ---- sub interpreter ------------------------------------------------------
;;
;; Sub bodies in the corpus take a few shapes:
;;
;;   [[:get [:path]]]                                      ;; layer-1
;;   [[:reduce-input :other-sub [:fn :+] [:fn :item-am.]]] ;; layer-2 fold
;;   [[:reduce-input :other-sub [:fn :+]]]                 ;; layer-2 sum
;;
;; realise-sub returns a map describing the registration the runner should
;; perform: {:kind :layer-1 :body fn}
;;          {:kind :layer-2 :inputs [[:other-sub]] :body fn}

(defn- runtime-db-sub-steps?
  "EP-0001 (rf2-vzld77): a fixture sub body whose FIRST step is a
  `[:get [:rf.runtime/… …]]` read against a reserved runtime-db key is a
  framework runtime-db reader — the durable machine / routing / SSR state
  now lives in the runtime-db partition. Such a sub registers via
  `reg-runtime-sub` so its `db`-position arg is the runtime-db value."
  [steps]
  (let [first-step (first steps)]
    (and (vector? first-step)
         (= :get (first first-step))
         (let [path (second first-step)]
           (and (vector? path)
                (keyword? (first path))
                (= "rf.runtime" (namespace (first path))))))))

(defn realise-sub
  [steps]
  (let [first-step (first steps)]
    (cond
      ;; EP-0001 (rf2-vzld77): a `[:get [:rf.runtime/… …]]` body reads the
      ;; runtime-db partition (machine snapshots / route slice / etc.). Same
      ;; layer-1 pipeline shape, but `:kind :runtime-db` so the runner
      ;; registers it via `reg-runtime-sub` (the `db`-position arg is the
      ;; runtime-db value).
      (runtime-db-sub-steps? steps)
      {:kind :runtime-db
       :body (fn [runtime-db _query]
               (reduce
                 (fn [v step]
                   (case (first step)
                     :get  (get-in runtime-db (second step))
                     :fn   (let [[_ k & extra] step
                                 f (builtin k)
                                 args (mapv #(resolve-value % {:db runtime-db}) extra)]
                             (apply f v args))
                     v))
                 nil
                 steps))}

      ;; layer-2 reduce-input form
      (and (vector? first-step) (= :reduce-input (first first-step)))
      (let [[_ input-sub-id reducer-form mapper-form] first-step
            reducer (resolve-value reducer-form {})
            mapper  (when mapper-form (resolve-value mapper-form {}))]
        {:kind   :layer-2
         :inputs [[input-sub-id]]
         :body   (fn [input-val _query]
                   (reduce reducer
                           (if mapper (map mapper input-val) input-val)))})

      :else
      ;; layer-1 pipeline: reduce over steps, transforming the value at
      ;; each step. :get reads from db; :fn applies a builtin (with the
      ;; current value as the first arg). Other ops are passed through.
      {:kind :layer-1
       :body (fn [db _query]
               (reduce
                 (fn [v step]
                   (case (first step)
                     :get  (get-in db (second step))
                     :fn   (let [[_ k & extra] step
                                 f (builtin k)
                                 args (mapv #(resolve-value % {:db db}) extra)]
                             (apply f v args))
                     v))
                 nil
                 steps))})))

;; ---- expectation-matcher primitives --------------------------------------
;;
;; Pure, host-neutral matchers shared by every conformance runner (rf2-wy414k).
;; A runner's fixture selection, capability claims, execution loop, and
;; reporting stay LOCAL; only these stable comparison primitives are shared.

(defn submap?
  "True if every key of `expected` appears in `actual` with a matching value.
  Recurses into nested maps so partial expectations on nested slices work (e.g.
  a fixture asserting only a subset of an app-db slice or a trace-tag map).

  Shared harness primitive (rf2-wy414k)."
  [expected actual]
  (cond
    (and (map? expected) (map? actual))
    (every? (fn [[k v]]
              (let [a (get actual k)]
                (cond
                  (and (map? v) (map? a)) (submap? v a)
                  :else                   (= v a))))
            expected)

    :else (= expected actual)))

(defn check-trace-emissions
  "Order-preserving SUBSET match of `expected-traces` against `actual-traces`
  per spec/conformance/README.md §Fixture lifecycle: each expected trace must
  appear in `actual-traces` in declaration order, matched partially by its
  specified keys (absent keys ignored; nested-map keys matched submap-wise).
  Extras between matches are tolerated (the runtime may emit bookkeeping traces
  the fixture doesn't care about). Returns a vector of failure-message strings
  (empty on full match).

  Shared harness primitive (rf2-wy414k)."
  [actual-traces expected-traces]
  (loop [actual   actual-traces
         expected expected-traces
         failures []]
    (cond
      (empty? expected)
      failures

      (empty? actual)
      (conj failures (str "expected trace not seen: " (pr-str (first expected))))

      :else
      (let [exp (first expected)
            match-idx (->> actual
                           (map-indexed vector)
                           (some (fn [[i a]]
                                   (when (every? (fn [[k v]]
                                                   (let [actual-v (get a k)]
                                                     (cond
                                                       (map? v)
                                                       (every? (fn [[kk vv]]
                                                                 (= vv (get actual-v kk)))
                                                               v)
                                                       :else (= v actual-v))))
                                                 exp)
                                     i))))]
        (if match-idx
          (recur (drop (inc match-idx) actual) (rest expected) failures)
          (recur actual (rest expected)
                 (conj failures (str "expected trace not seen: " (pr-str exp)))))))))

(defn resolve-sub
  "Normalise a `:sub-values` query entry to `[frame-id query-v]`. An entry may
  be `[query-v]` (an IMPLICIT-frame query, targeting `default-frame`) or
  `[frame-id [query-v]]` (an explicit frame).

  `default-frame` is a PARAMETER, not a baked-in frame keyword: this shared
  owner sits in `core/src`, under the EP-0002 frame-floor lint (which exempts
  `test/` but not `src/` — see `re-frame.no-rf-default-floor-lint-test`). The
  implicit-frame default is a TEST-HARNESS query-normalisation convention, so
  each runner passes its own default from its test tree — keeping this src/
  primitive free of a positional frame-floor shape while still sharing the
  query-shape normalisation itself (rf2-wy414k)."
  [default-frame entry]
  (if (and (vector? entry)
           (= 2 (count entry))
           (vector? (second entry)))
    [(first entry) (second entry)]
    [default-frame entry]))
