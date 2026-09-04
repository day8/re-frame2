(ns re-frame.story.decorators
  "Decorator composition. Per `002-Runtime.md` §Decorator composition order + /spec/007-Stories.md §Decorators.

  Decorators come in three kinds (each registered via `reg-decorator`):

  - `:hiccup` — `{:wrap (fn [body args] [:div ... body])}`. Wraps the
    rendered tree.
  - `:frame-setup` — `{:init [event-vec ...] :app-db-patch {...}}`.
    Runs at frame creation, before phase-1 loaders.
  - `:fx-override` — `{:fx-id ... :response ...}`. Stubs an fx for the
    lifetime of the variant's frame.

  ## Composition order

  Per `002-Runtime.md` §Decorator composition order + rf2-835ey the runtime walks
  `(concat global-decorators story-decorators variant-decorators)` in
  declared order and groups by `:kind`. Global decorators are the
  Storybook-`preview.ts`-parity layer (Finding F-1 — every variant
  inherits the project-wide stack):

  - `:hiccup` decorators — outermost wraps innermost. The first
    decorator's `:wrap` is the outermost element in the rendered tree;
    the last decorator's `:wrap` is the innermost wrapper of the bare
    rendered view.
  - `:frame-setup` decorators — declared order, run sequentially.
  - `:fx-override` decorators — declared order; collisions on the same
    `:fx-id` resolve last-wins (the inner-most decorator wins).

  ## Resolution

  Variant `:decorators` is a vector of `[decorator-id & args]` vectors.
  Inline decorator forms (where the first element is itself a map)
  are NOT supported; the schema requires registered ids. Story-level
  decorators live on the parent story's `:decorators` slot.

  Unknown decorator-ids surface as an entry in the returned `:errors`
  vector; the runtime then projects those into the variant's
  `:assertions` (per `002-Runtime.md` §Error projection)."
  (:require [re-frame.story.args      :as rf.story.args]
            [re-frame.story.plan      :as rf.story.plan]
            [re-frame.story.registrar :as rf.story.registrar]))

;; ---- collection -----------------------------------------------------------

(defn- collect-decorator-refs
  "Return the variant's FULL ordered `[decorator-ref ...]` list — globals
  (outermost), then the parent story's `:decorators`, then the variant
  chain — by reading the COMPILED plan's `[:world :decorators]`, NOT by
  re-assembling the three sources here.

  The plan compiler (`re-frame.story.plan`) is the single source of
  the full stack: it folds `(concat globals story variant-chain)` into
  `[:world :decorators]`. Reading it off the plan means the registered
  front-door path (`resolve-decorators`, which the canvas calls), the
  inline-plan runtime path (`resolve-decorator-refs` over the plan's
  refs), and `render-variant` (which reads `render-inputs`' `:decorators`,
  also off `[:world :decorators]`) all resolve the identical stack.

  Globals + story are AMBIENT (project config + parent-story body), not
  part of the variant body — the plan resolves them through
  `config/get-global-decorators` + the `:story` side-table; this fn just
  consumes the result. Active modes contribute no decorators at v1 — per
  `001-Authoring.md` §Registration macros modes carry `:args` only.

  `decorators → plan` is acyclic (plan does NOT require decorators). The
  per-variant cost is one plan compile per resolution, which the single-
  source invariant pays for (no second merge engine to diverge).

  `run-args` carries the ambient + per-run arg layers
  (`rf.story.args/run-arg-layers`'s `{:pre … :post …}` shape) threaded into the
  plan compile so a `[:arg key]` resolvable ONLY through a mode / cell /
  global / story layer (never the variant chain) substitutes cleanly
  rather than throwing `:rf.error/story-missing-arg`. The runtime's
  `prepare-context` already compiles WITH `:run-args` and reads
  `[:world :decorators]` off that plan; the canvas/controls/docs paths
  flow through `resolve-decorators` → here, so they must pass the SAME
  run layers to recompile the identical plan. Absent (a bare front-door
  call) ⇒ the variant arg layer alone, exactly as before."
  ([variant-id] (collect-decorator-refs variant-id nil))
  ([variant-id run-args]
   (or (get-in (rf.story.plan/variant-plan variant-id
                                  (when run-args {:run-args run-args}))
               [:world :decorators])
       [])))

;; ---- resolution -----------------------------------------------------------

(defn- expand-ref-args-body
  "Some decorators (e.g. `:rf.story/force-fx-stub`) take their fx-id +
  response from the *ref-args* rather than the registered body. When
  the registered body declares `:ref-args? true`, expand the ref-args
  into a per-reference body so downstream classification + fx-override
  materialisation see the user-supplied data.

  Currently the only `:ref-args? true` shape we recognise is
  `:fx-override` — `[<id> <fx-id> <response>]`. Future shapes (e.g. a
  ref-args-driven `:hiccup` decorator) can plug in here without
  touching the rest of decorator resolution.

  Returns the merged body."
  [body decor-args]
  (cond
    (not (:ref-args? body))
    body

    (= :fx-override (:kind body))
    (let [[fx-id response] decor-args]
      (-> body
          (dissoc :ref-args?)
          (assoc :fx-id    fx-id
                 :response response)))

    :else
    (dissoc body :ref-args?)))

(defn- resolve-ref
  "Look up `[decorator-id & args]` against the decorator registry.
  Returns `{:id ... :args [...] :body <body-or-nil> :error nil|<map>}`.

  Resolution does not throw on an unregistered decorator; the runtime
  records it as an error so the variant pane can show it inline.

  The `:ref-args?` path expands decorators that take
  their config from the *ref* (e.g. `:rf.story/force-fx-stub`) get a
  synthesised per-reference body so downstream classification sees the
  user-supplied data."
  [ref]
  (let [id          (first ref)
        decor-args  (vec (rest ref))
        body-raw    (when (keyword? id) (rf.story.registrar/handler-meta :decorator id))
        body        (when body-raw (expand-ref-args-body body-raw decor-args))]
    (cond
      (not (keyword? id))
      {:id    id
       :args  decor-args
       :body  nil
       :error {:rf.error :rf.error/decorator-bad-ref
               :ref      ref
               :reason   "decorator reference must start with a keyword id"}}

      (nil? body)
      {:id    id
       :args  decor-args
       :body  nil
       :error {:rf.error :rf.error/decorator-unknown
               :id       id
               :reason   (str "no decorator registered under " id)}}

      :else
      {:id    id
       :args  decor-args
       :body  body
       :error nil})))

;; ---- cycle / re-registration detection -----------------------------------
;;
;; The hot-reload path must be able to ask the registrar whether a decorator
;; has changed since it was cached. These fingerprints provide that pure
;; comparison; the UI shell owns polling and rerun orchestration.

(defn decorator-fingerprint
  "Per-decorator fingerprint suitable for hot-reload comparison. Two
  fingerprints are equal iff the decorator's registered body is equal
  (excluding `:source`-coord noise that varies across recompiles).

  Used by the UI shell to detect when a decorator changed and the
  cached resolution must be invalidated."
  [decorator-id]
  (let [body (rf.story.registrar/handler-meta :decorator decorator-id)
        body (dissoc body :source)]
    (hash body)))

(defn resolution-fingerprints
  "Return `{decorator-id → fingerprint}` for every decorator the variant
  will use. The UI shell caches the resolved decorator stack alongside this
  map and re-resolves if any fingerprint diverges from the registry's
  current value.

  `opts` is the per-run `{:active-modes … :cell-overrides …}`
  (same shape `resolve-decorators` takes), folded into the plan compile
  that yields the decorator REF list so a `[:arg key]` resolvable only
  through a mode / cell layer does not throw `:rf.error/story-missing-arg`
  during the hot-reload poll. The fingerprints themselves depend only on
  the decorator BODIES (registry), invariant under run layers; `opts`
  serves solely to let the ref collection's plan compile succeed."
  ([variant-id] (resolution-fingerprints variant-id nil))
  ([variant-id opts]
   (let [run-args (when opts (rf.story.args/run-arg-layers variant-id opts))
         refs     (collect-decorator-refs variant-id run-args)]
     (into {}
           (keep (fn [ref]
                   (let [id (first ref)]
                     (when (keyword? id)
                       [id (decorator-fingerprint id)]))))
           refs))))

;; ---- public surface -------------------------------------------------------

(def ^:private kind->bucket
  "Map a decorator body's `:kind` to the result bucket it lands in.
  Used by `resolve-decorators`'s reducer to dispatch via `case` rather
  than a multi-arm `cond` cascade."
  {:hiccup       :hiccup
   :frame-setup  :frame-setup
   :fx-override  :fx-override})

(defn- classify-kind
  "Return the bucket keyword for a resolved-decorator's `:body :kind`,
  or `:unknown-kind` if it isn't one of the three canonical kinds."
  [r]
  (or (kind->bucket (:kind (:body r))) :unknown-kind))

(declare resolve-decorator-refs)

(defn resolve-decorators
  "Per `002-Runtime.md` §Decorator composition order — collect, classify, and order the decorator stack
  for the variant. Returns:

      {:hiccup       [<resolved-decorator> ...]
       :frame-setup  [<resolved-decorator> ...]
       :fx-override  [<resolved-decorator> ...]
       :errors       [<error-map> ...]
       :fingerprints {<decorator-id> <hash>}}

  Each `<resolved-decorator>` carries `{:id ... :args [...] :body
  <registered-body>}`. Unknown decorators land in `:errors` instead of
  their kind-vector — the runtime projects them as `:rf.error/decorator-*`
  assertions per `002-Runtime.md` §Error projection.

  Composition order (per `002-Runtime.md` §Decorator composition order + rf2-835ey global decorators):
  - `:hiccup` — outermost wraps innermost. Global decorators come first
    (outermost), story decorators second, variant decorators last
    (innermost).
  - `:frame-setup` — declared order (global first, then story, then
    variant).
  - `:fx-override` — declared order; last-wins on a key collision.

  `opts` accepts `:active-modes` + `:cell-overrides` — the SAME per-run
  shape `rf.story.args/resolve-args` takes. v1 modes carry no decorators (per
  `001-Authoring.md` §Registration macros modes are `:args`-only, so the active modes never
  perturb the decorator REFS), but the run layers must still be threaded
  into the plan compile (rf2-eyrpr): a `[:arg key]` resolvable ONLY
  through a mode / cell / global / story layer would otherwise throw
  `:rf.error/story-missing-arg` when this front-door recompiles the plan
  WITHOUT `:run-args` — the gap rf2-2cpoo (#3248) closed for the runtime
  `prepare-context` path but not this canvas/controls/docs path."
  ([variant-id]
   (resolve-decorators variant-id nil))
  ([variant-id opts]
   (let [run-args (when opts (rf.story.args/run-arg-layers variant-id opts))]
     (resolve-decorator-refs (collect-decorator-refs variant-id run-args)))))

(defn resolve-decorator-refs
  "Resolve, classify, and order a supplied vector of `[decorator-id & args]`
  refs into the same `{:hiccup :frame-setup :fx-override :errors
  :fingerprints}` shape `resolve-decorators` returns — but WITHOUT reading
  the variant/story side-table for the ref collection.

  `resolve-decorators` is the registered-variant front door: it reads the
  FULL `[:world :decorators]` stack (globals + story + variant chain) off
  the compiled plan (rf2-5fibj), then delegates here. The inline-plan
  runtime path (rf2-5x1wt.20) and `render-variant` (via `render-inputs`'
  `:decorators`) call this directly with the SAME `[:world :decorators]`
  refs, so an inline plan absent from the side-table — and the live canvas
  + render-variant alike — all classify the identical stack. The decorator
  BODIES are still looked up against the `:decorator` registry (a decorator
  is a registered artefact; only the variant's ref LIST is sourced from the
  plan)."
  [refs]
  (let [resolved   (mapv resolve-ref refs)
        {:keys [hiccup frame-setup fx-override errors]}
        (reduce
          (fn [acc r]
            (if (:error r)
              (update acc :errors conj (:error r))
              (case (classify-kind r)
                :hiccup       (update acc :hiccup       conj r)
                :frame-setup  (update acc :frame-setup  conj r)
                :fx-override  (update acc :fx-override  conj r)
                :unknown-kind (update acc :errors conj
                                      {:rf.error :rf.error/decorator-unknown-kind
                                       :id       (:id r)
                                       :kind     (:kind (:body r))
                                       :reason   (str "decorator " (:id r)
                                                      " has unrecognised :kind "
                                                      (pr-str (:kind (:body r))))}))))
          {:hiccup [] :frame-setup [] :fx-override [] :errors []}
          resolved)]
    {:hiccup        hiccup
     :frame-setup   frame-setup
     :fx-override   fx-override
     :errors        errors
     :fingerprints  (into {}
                          (keep (fn [r] (when (nil? (:error r))
                                          [(:id r)
                                           (decorator-fingerprint (:id r))])))
                          resolved)}))

;; ---- hiccup application --------------------------------------------------

(defn apply-hiccup-decorators
  "Apply the `:hiccup`-kind decorators to a rendered tree. The first
  entry in `hiccup-decorators` is the outermost wrap; the last entry
  is the innermost wrap (adjacent to `body`).

  Per `002-Runtime.md` §Decorator composition order — 'outermost wraps innermost' means we walk the
  vector in *reverse*, calling each `:wrap` on the accumulating tree:
  the last decorator wraps `body`, then the second-to-last wraps the
  result, and so on. The final result is the outermost decorator's
  wrap of every inner wrap.

  `effective-args` is the resolved args map (per `rf.story.args/resolve-args`);
  every `:wrap` fn receives `[body effective-args]`. Decorator-level
  ref-args (the `[& args]` tail of a `[:dec-id & args]` ref) are NOT
  passed in the variant-body model — per /spec/007-Stories.md §Three kinds of
  decorator, decorator ref-args are static configuration of the
  decorator, not call-time args."
  [hiccup-decorators body effective-args]
  (reduce
    (fn [acc r]
      (let [wrap-fn (-> r :body :wrap)
            ;; Decorator ref-args (`[:dec-id arg1 arg2]`) get merged into
            ;; the effective args as a `:decorator/args` slot so the
            ;; wrap fn can pick them out without losing access to the
            ;; user's args. Two-arg wrap-fns receive `(body args-map)`
            ;; per /spec/007-Stories.md's example; the ref-args are accessible via
            ;; `(:decorator/args args-map)`.
            wrap-args (assoc effective-args :decorator/args (:args r))]
        (wrap-fn acc wrap-args)))
    body
    (reverse hiccup-decorators)))

;; ---- fx-override materialisation -----------------------------------------

(defn synthesise-stub-id
  "Build the deterministic stub-fx id for a `:fx-override` decorator
  resolution. Returns a keyword in the reserved
  `:rf.story.fx-stub/<dec-id>[+ns.name]` namespace per Conventions.md.

  When `fx-id` is set, the stub-id carries it as a `+ns.name` suffix —
  so the ref-args-driven `:rf.story/force-fx-stub` decorator can
  register distinct stubs for distinct fx-ids referenced from the same
  decorator id. When `fx-id` is nil (a body-supplied `:fx-id` is
  unset — unusual but legal), only the decorator id is encoded.

  Pure data → keyword; JVM-testable."
  [decorator-id fx-id]
  (let [base (name decorator-id)
        suffix (when fx-id
                 (str "+" (when-let [ns (namespace fx-id)] (str ns "."))
                      (name fx-id)))]
    (keyword "rf.story.fx-stub" (str base suffix))))

(defn fx-overrides-map
  "Materialise the `:fx-overrides` map a `:fx-override`-decorator stack
  contributes to the variant frame's `:config`. The runtime threads
  this directly into `(rf/make-frame {:id variant-id ... :fx-overrides ...})`
  (per spec/002 §Frame lifecycle).

  Each `:fx-override` decorator carries `{:fx-id <id> :response <data>}`.
  We synthesise a per-decorator replacement fx id of the form
  `:rf.story.fx-stub/<decorator-id>` and the runtime registers the
  replacement handler with `reg-fx` before allocating the variant frame.
  Last-wins on `:fx-id`
  collision (the inner-most decorator wins).

  Returns `{:overrides {<fx-id> <stub-id>}
            :registrations [{:fx-id <fx-id>
                             :stub-id <stub-id>
                             :response <data>
                             :decorator-id <decorator-id>} ...]}`.

  The frames runtime walks `:registrations` and installs each stub
  before frame allocation, then threads the `:overrides` map onto the
  frame's config."
  [fx-override-decorators]
  (let [pairs (mapv (fn [r]
                      (let [fx-id    (-> r :body :fx-id)
                            response (-> r :body :response)
                            stub-id  (synthesise-stub-id (:id r) fx-id)]
                        {:fx-id        fx-id
                         :stub-id      stub-id
                         :response     response
                         :decorator-id (:id r)}))
                    fx-override-decorators)
        ;; Last-wins on fx-id: index pairs by :fx-id so a later entry
        ;; overwrites an earlier one for the same fx-id.
        by-fx (into {} (map (juxt :fx-id identity)) pairs)
        finals (vals by-fx)]
    {:overrides     (into {}
                          (map (fn [p] [(:fx-id p) (:stub-id p)]))
                          finals)
     :registrations (vec finals)}))
