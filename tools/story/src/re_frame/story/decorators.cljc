(ns re-frame.story.decorators
  "Decorator composition. Per IMPL-SPEC §5.3 + spec/007 §Decorators.

  Decorators come in three kinds (each registered via `reg-decorator`):

  - `:hiccup` — `{:wrap (fn [body args] [:div ... body])}`. Wraps the
    rendered tree.
  - `:frame-setup` — `{:init [event-vec ...] :app-db-patch {...}}`.
    Runs at frame creation, before phase-1 loaders.
  - `:fx-override` — `{:fx-id ... :response ...}`. Stubs an fx for the
    lifetime of the variant's frame.

  ## Composition order

  Per IMPL-SPEC §5.3 the runtime walks `(concat story-decorators
  variant-decorators)` in declared order and groups by `:kind`:

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
  are NOT supported — Stage 2's schema forbids them. Story-level
  decorators live on the parent story's `:decorators` slot.

  Unknown decorator-ids surface as an entry in the returned `:errors`
  vector; the runtime then projects those into the variant's
  `:assertions` (per IMPL-SPEC §5.5)."
  (:require [re-frame.story.args      :as args]
            [re-frame.story.registrar :as registrar]))

;; ---- collection -----------------------------------------------------------

(defn- variant-decorator-refs
  "Return the variant body's `:decorators` vector, or `[]`."
  [variant-id]
  (or (:decorators (registrar/handler-meta :variant variant-id)) []))

(defn- story-decorator-refs
  "Return the parent story's `:decorators` vector, or `[]`."
  [variant-id]
  (let [story-id (args/parent-story-id variant-id)
        body     (when story-id (registrar/handler-meta :story story-id))]
    (or (:decorators body) [])))

(defn- collect-decorator-refs
  "Build the ordered `[decorator-ref ...]` list for the variant.

  Story decorators come first (outermost when applied as hiccup
  wrappers), variant decorators second. Active modes contribute no
  decorators at v1 — per IMPL-SPEC §3.1 modes carry `:args` only."
  [variant-id]
  (vec (concat (story-decorator-refs variant-id)
               (variant-decorator-refs variant-id))))

;; ---- resolution -----------------------------------------------------------

(defn- resolve-ref
  "Look up `[decorator-id & args]` against the decorator registry.
  Returns `{:id ... :args [...] :body <body-or-nil> :error nil|<map>}`.

  Stage 3 does not throw on an unregistered decorator — the runtime
  records it as an error so the variant pane can show it inline."
  [ref]
  (let [id          (first ref)
        decor-args  (vec (rest ref))
        body        (when (keyword? id) (registrar/handler-meta :decorator id))]
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
;; Per IMPL-SPEC §13.2 hot-reload, the runtime must be able to ask the
;; registrar 'has decorator X been re-registered since I cached it?' and
;; refresh if so. Stage 3 provides the freshness check; Stage 4 (UI
;; shell) wires the trigger.

(defn decorator-fingerprint
  "Per-decorator fingerprint suitable for hot-reload comparison. Two
  fingerprints are equal iff the decorator's registered body is equal
  (excluding `:source`-coord noise that varies across recompiles).

  Used by the UI shell to detect when a decorator changed and the
  cached resolution must be invalidated. Stage 4 calls this; Stage 3
  surfaces it."
  [decorator-id]
  (let [body (registrar/handler-meta :decorator decorator-id)
        body (dissoc body :source)]
    (hash body)))

(defn resolution-fingerprints
  "Return `{decorator-id → fingerprint}` for every decorator the variant
  will use. Stage 4 caches the resolved decorator stack alongside this
  map and re-resolves if any fingerprint diverges from the registry's
  current value."
  [variant-id]
  (let [refs (collect-decorator-refs variant-id)]
    (into {}
          (keep (fn [ref]
                  (let [id (first ref)]
                    (when (keyword? id)
                      [id (decorator-fingerprint id)]))))
          refs)))

;; ---- public surface -------------------------------------------------------

(defn resolve-decorators
  "Per IMPL-SPEC §5.3 — collect, classify, and order the decorator stack
  for the variant. Returns:

      {:hiccup       [<resolved-decorator> ...]
       :frame-setup  [<resolved-decorator> ...]
       :fx-override  [<resolved-decorator> ...]
       :errors       [<error-map> ...]
       :fingerprints {<decorator-id> <hash>}}

  Each `<resolved-decorator>` carries `{:id ... :args [...] :body
  <registered-body>}`. Unknown decorators land in `:errors` instead of
  their kind-vector — the runtime projects them as `:rf.error/decorator-*`
  assertions per IMPL-SPEC §5.5.

  Composition order (per IMPL-SPEC §5.3):
  - `:hiccup` — outermost wraps innermost. Story decorators come first
    (outermost); variant decorators last (innermost).
  - `:frame-setup` — declared order (story first, variant second).
  - `:fx-override` — declared order; last-wins on a key collision.

  `opts` accepts `:active-modes` for forward compatibility — v1 modes
  carry no decorators, but the slot exists for v2 (per IMPL-SPEC
  §13.2)."
  ([variant-id]
   (resolve-decorators variant-id nil))
  ([variant-id _opts]
   (let [refs       (collect-decorator-refs variant-id)
         resolved   (mapv resolve-ref refs)
         {:keys [hiccup frame-setup fx-override errors]}
         (reduce
           (fn [acc r]
             (cond
               (:error r)
               (update acc :errors conj (:error r))

               (= :hiccup (:kind (:body r)))
               (update acc :hiccup conj r)

               (= :frame-setup (:kind (:body r)))
               (update acc :frame-setup conj r)

               (= :fx-override (:kind (:body r)))
               (update acc :fx-override conj r)

               :else
               (update acc :errors conj
                       {:rf.error :rf.error/decorator-unknown-kind
                        :id       (:id r)
                        :kind     (:kind (:body r))
                        :reason   (str "decorator " (:id r)
                                       " has unrecognised :kind "
                                       (pr-str (:kind (:body r))))})))
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
                           resolved)})))

;; ---- hiccup application --------------------------------------------------

(defn apply-hiccup-decorators
  "Apply the `:hiccup`-kind decorators to a rendered tree. The first
  entry in `hiccup-decorators` is the outermost wrap; the last entry
  is the innermost wrap (adjacent to `body`).

  Per IMPL-SPEC §5.3 — 'outermost wraps innermost' means we walk the
  vector in *reverse*, calling each `:wrap` on the accumulating tree:
  the last decorator wraps `body`, then the second-to-last wraps the
  result, and so on. The final result is the outermost decorator's
  wrap of every inner wrap.

  `effective-args` is the resolved args map (per `args/resolve-args`);
  every `:wrap` fn receives `[body effective-args]`. Decorator-level
  ref-args (the `[& args]` tail of a `[:dec-id & args]` ref) are NOT
  passed in the variant-body model — per spec/007 §Three kinds of
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
            ;; per spec/007's example; the ref-args are accessible via
            ;; `(:decorator/args args-map)`.
            wrap-args (assoc effective-args :decorator/args (:args r))]
        (wrap-fn acc wrap-args)))
    body
    (reverse hiccup-decorators)))

;; ---- fx-override materialisation -----------------------------------------

(defn fx-overrides-map
  "Materialise the `:fx-overrides` map a `:fx-override`-decorator stack
  contributes to the variant frame's `:config`. The runtime threads
  this directly into `(rf/reg-frame variant-id {... :fx-overrides ...})`
  (per spec/002 §reg-frame).

  Each `:fx-override` decorator carries `{:fx-id <id> :response <data>}`.
  We synthesise a per-decorator replacement event-fx id of the form
  `:rf.story.fx-stub/<decorator-id>` and the runtime registers the
  replacement event-fx as `(reg-event-fx that-id (fn [_ _] ...))`
  before the variant frame's `reg-frame` call. Last-wins on `:fx-id`
  collision (the inner-most decorator wins).

  Returns `{:overrides {<fx-id> <stub-event-id>}
            :registrations [{:event-id <stub-event-id>
                             :response <data>} ...]}`.

  The Stage 3 runtime walks `:registrations` and calls `reg-event-fx`
  for each before `reg-frame`-ing the variant; then threads the
  `:overrides` map onto the frame's config."
  [fx-override-decorators]
  (let [pairs (mapv (fn [r]
                      (let [fx-id    (-> r :body :fx-id)
                            response (-> r :body :response)
                            stub-id  (keyword "rf.story.fx-stub"
                                              (str (name (:id r))))]
                        {:fx-id        fx-id
                         :stub-id      stub-id
                         :response     response
                         :decorator-id (:id r)}))
                    fx-override-decorators)
        ;; Last-wins on fx-id: dedupe pairs preserving the LAST entry
        ;; per fx-id.
        by-fx (reduce (fn [acc p] (assoc acc (:fx-id p) p)) {} pairs)
        finals (vals by-fx)]
    {:overrides     (into {}
                          (map (fn [p] [(:fx-id p) (:stub-id p)]))
                          finals)
     :registrations (vec finals)}))
