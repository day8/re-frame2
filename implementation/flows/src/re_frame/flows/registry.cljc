(ns re-frame.flows.registry
  "Flow registration, activation checks and each frame's evaluation memo.

  A flow definition is an ordinary registration: `reg-flow` writes it under
  the registrar's `:flow` kind and names no frame. A frame runs the flows its
  `:flows` config names, resolved through its own view of the registrations.
  The only per-frame state is the evaluation memo — one row per flow the frame
  has evaluated, recording the definition the evaluation used and the inputs
  and output it produced."
  (:require [re-frame.elision :as rf.elision]
            [re-frame.error :as rf.error]
            [re-frame.flows.topo :as rf.flows.topo]
            [re-frame.frame :as rf.frame]
            [re-frame.interop :as rf.interop]
            [re-frame.path :as rf.path]
            [re-frame.registrar :as rf.registrar]
            [re-frame.source-coords :as rf.source-coords]
            [re-frame.trace :as rf.trace]))

(def kind
  "The registrar kind flow definitions live under."
  :flow)

;; ---- the evaluation memo -------------------------------------------------
;;
;; A ROW is `{:flow definition :inputs input-vec :output value}`: the definition
;; the flow was last evaluated with, the inputs that evaluation read and the
;; output it produced. The definition carries its `:output-path`, so a row also
;; names the slot it wrote. A row holding only `:flow` is one a tool write left
;; untrusted (`distrust-memo!`): its flow re-claims and recomputes on the next
;; event, and the reconciler can still vacate the slot it names.
;;
;; The memo always describes the committed frame state: a pass proposes rows
;; and the router publishes them (`publish-memo!`) only once the candidate they
;; were computed for has installed.
;;
;; The outer map changes only when a frame's memo is created or dropped; a
;; drain swaps its frame's own inner atom, so frames draining on different
;; threads never contend.

(defonce
  ^{:doc     "frame-id → (atom {flow-id row})."
    :private true}
  frame-memo
  (atom {}))

(defn ^:no-doc frame-memo-atom
  "Return `frame-id`'s memo atom, or nil when the frame has none yet."
  [frame-id]
  (get @frame-memo frame-id))

(defn ^:no-doc ensure-frame-memo-atom!
  "Return `frame-id`'s memo atom, creating it atomically on first touch."
  [frame-id]
  (or (get @frame-memo frame-id)
      (get (swap! frame-memo
                  (fn [m]
                    (if (contains? m frame-id)
                      m
                      (assoc m frame-id (atom {})))))
           frame-id)))

(defn ^:no-doc frame-last-inputs-snapshot
  "Return `frame-id`'s memo rows as `{flow-id row}`: the rows the flow pass
  starts from."
  [frame-id]
  (if-let [a (frame-memo-atom frame-id)]
    @a
    {}))

(defn ^:no-doc publish-memo!
  "Make `rows` `frame-id`'s memo. The router calls the `:publish!` thunk a pass
  returns once the candidate the rows describe has installed, so an event that
  throws or is rejected leaves the memo as it was."
  [frame-id rows]
  (reset! (ensure-frame-memo-atom! frame-id) rows)
  nil)

(defn ^:no-doc distrust-memo!
  "Strip the inputs and output from every row of `frame-id`'s memo, keeping
  each row's definition. The `:flows/distrust-memo!` hook: epoch fires it after
  a tool write installs frame-state outside any event, so the next event
  re-claims and recomputes every flow against the installed state, while each
  row still names the output path a departure or a moved `:output-path`
  vacates."
  [frame-id]
  (when-let [a (frame-memo-atom frame-id)]
    (swap! a (fn [rows]
               (reduce-kv (fn [rows id row] (assoc rows id (dissoc row :inputs :output)))
                          rows rows))))
  nil)

(defn ^:no-doc last-inputs-snapshot
  "Return the cached inputs as `{flow-id {frame-id inputs}}`.

  A test accessor, not an egress boundary: values leaving the runtime go
  through the elided flow traces."
  []
  (reduce-kv
    (fn [acc frame-id memo]
      (reduce-kv
        (fn [acc flow-id row]
          (assoc-in acc [flow-id frame-id] (:inputs row)))
        acc
        @memo))
    {}
    @frame-memo))

;; ---- validation ----------------------------------------------------------
;;
;; Validate paths before topology or evaluation sees them. Classification
;; declarations fail closed so malformed safety metadata cannot silently
;; install an unprotected output.

(defn- valid-path-element?
  "True when `x` belongs to the shared concrete path-segment domain."
  [x]
  (rf.path/segment? x))

(defn- valid-path?
  "True for a non-empty vector of valid path segments."
  [x]
  (and (vector? x) (seq x) (every? valid-path-element? x)))

(defn- valid-output-subpath?
  "True for an output-relative path; `[]` denotes the whole output."
  [x]
  (and (vector? x) (every? valid-path-element? x)))

(defn- flow-error
  "Build a flow registration error with optional diagnostic data."
  ([error-kw reason flow] (flow-error error-kw reason flow nil))
  ([error-kw reason flow extras]
   (rf.error/thrown-ex-info
     error-kw 'rf/reg-flow reason
     {:recovery :fix-registration
      :extra    (merge {:flow flow} extras)})))

;; Defined with the other input partition primitives below.
(declare runtime-partition-key)

;; Ordered so callers receive the most fundamental shape error first.
(def ^:private validation-rules
  [{:pred     (fn [flow] (some? (:id flow)))
    :error-kw :rf.error/flow-missing-id
    :reason   ":id is required (flow registration must name an id)"}

   ;; Trace and tooling schemas carry flow ids as keywords unchanged.
   {:pred     (fn [flow] (keyword? (:id flow)))
    :error-kw :rf.error/flow-bad-id
    :reason   ":id must be a keyword (flow ids are namespaced feature identifiers; the public FlowMeta schema requires :keyword and the :flow-id trace/error slot carries it unchanged)"}

   {:pred     (fn [flow] (vector? (:inputs flow)))
    :error-kw :rf.error/flow-bad-inputs
    :reason   ":inputs must be a vector of paths"}

   {:pred     (fn [flow] (every? valid-path? (:inputs flow)))
    :error-kw :rf.error/flow-bad-inputs
    :reason   ":inputs entries must each be a non-empty vector of path segments (the shared EP-0012 segment domain: keyword / string / symbol / boolean / integer / UUID / instant / nil)"
    :extras   (fn [flow] {:bad-entries (vec (remove valid-path? (:inputs flow)))})}

   {:pred     (fn [flow] (fn? (:derive flow)))
    :error-kw :rf.error/flow-bad-output
    :reason   ":derive must be a fn"}

   {:pred     (fn [flow] (vector? (:output-path flow)))
    :error-kw :rf.error/flow-bad-path
    :reason   ":output-path must be a vector"}

   {:pred     (fn [flow] (seq (:output-path flow)))
    :error-kw :rf.error/flow-bad-path
    :reason   ":output-path must be non-empty (an empty :output-path would make this flow a depends-on prerequisite of every other flow per Spec 013 §Dependency rule)"}

   {:pred     (fn [flow] (every? valid-path-element? (:output-path flow)))
    :error-kw :rf.error/flow-bad-path
    :reason   ":output-path elements must each be a path segment (the shared EP-0012 segment domain: keyword / string / symbol / boolean / integer / UUID / instant / nil)"
    :extras   (fn [flow] {:bad-elements (vec (remove valid-path-element? (:output-path flow)))})}

   ;; The runtime qualifier is input syntax. Outputs always write app-db.
   {:pred     (fn [flow] (not= runtime-partition-key (first (:output-path flow))))
    :error-kw :rf.error/flow-reserved-output-path
    :reason   (str ":output-path may not be rooted at the reserved runtime-db partition key "
                   ":rf.db/runtime — a leading :rf.db/runtime is reserved for a runtime-db INPUT; "
                   "a flow output is always an app-db write, so a :rf.db/runtime-rooted output "
                   "would write the reserved partition key inside app-db")
    :extras   (fn [flow] {:bad-elements [(first (:output-path flow))]})}

   ;; Split collection-shape and entry-shape checks for precise diagnostics.
   {:pred     (fn [flow] (or (not (contains? flow :sensitive))
                             (vector? (:sensitive flow))))
    :error-kw :rf.error/flow-bad-marks
    :reason   ":sensitive, when present, must be a vector of output subpaths (each a vector of scalar keys; [] marks the whole output)"
    :extras   (fn [flow] {:bad-key :sensitive :bad-value (:sensitive flow)})}

   {:pred     (fn [flow] (or (not (contains? flow :sensitive))
                             (every? valid-output-subpath? (:sensitive flow))))
    :error-kw :rf.error/flow-bad-marks
    :reason   ":sensitive entries must each be a vector of path segments (the shared EP-0012 segment domain: keyword / string / symbol / boolean / integer / UUID / instant / nil); [] marks the whole output"
    :extras   (fn [flow] {:bad-key     :sensitive
                          :bad-entries (vec (remove valid-output-subpath? (:sensitive flow)))})}

   {:pred     (fn [flow] (or (not (contains? flow :large))
                             (vector? (:large flow))))
    :error-kw :rf.error/flow-bad-marks
    :reason   ":large, when present, must be a vector of output subpaths (each a vector of scalar keys; [] marks the whole output)"
    :extras   (fn [flow] {:bad-key :large :bad-value (:large flow)})}

   {:pred     (fn [flow] (or (not (contains? flow :large))
                             (every? valid-output-subpath? (:large flow))))
    :error-kw :rf.error/flow-bad-marks
    :reason   ":large entries must each be a vector of path segments (the shared EP-0012 segment domain: keyword / string / symbol / boolean / integer / UUID / instant / nil); [] marks the whole output"
    :extras   (fn [flow] {:bad-key     :large
                          :bad-entries (vec (remove valid-output-subpath? (:large flow)))})}

   ;; Reject these spellings rather than silently ignoring a safety mark.
   {:pred     (fn [flow] (not (contains? flow :sensitive?)))
    :error-kw :rf.error/flow-bad-marks
    :reason   (str "the boolean :sensitive? spelling is rejected on a flow output "
                   "(EP-0025) — classify the whole output with :sensitive [[]] (the "
                   "[[]] whole-value convention) or a per-path :sensitive [paths]")
    :extras   (fn [flow] {:bad-key :sensitive?
                          :bad-value (:sensitive? flow)
                          :use :sensitive})}

   {:pred     (fn [flow] (not (contains? flow :rf.egress/output-sensitivity)))
    :error-kw :rf.error/flow-bad-marks
    :reason   (str ":rf.egress/output-sensitivity is removed (EP-0025 — no flow "
                   "input→output propagation). Classify the flow's own output "
                   "directly with :sensitive [paths] / :large [paths] / :large?")
    :extras   (fn [flow] {:bad-key   :rf.egress/output-sensitivity
                          :bad-value (:rf.egress/output-sensitivity flow)})}

   {:pred     (fn [flow] (or (not (contains? flow :large?))
                             (boolean? (:large? flow))))
    :error-kw :rf.error/flow-bad-marks
    :reason   ":large?, when present, must be a boolean (true forces the whole output large)"
    :extras   (fn [flow] {:bad-key :large? :bad-value (:large? flow)})}])

(defn- validate-flow [flow]
  (some (fn [{:keys [pred error-kw reason extras]}]
          (when-not (pred flow)
            (throw (flow-error error-kw reason flow (when extras (extras flow))))))
        validation-rules))

;; ---- flow-output data classification ------------------------------------
;;
;; A flow classifies its own output; classifications do not propagate from its
;; inputs. Paths below are relative to `:output-path`:
;;
;;   :large?     true   — the whole output is large
;;   :sensitive  [paths]— per-output-path sensitive sub-slots ([[]] = whole)
;;   :large      [paths]— per-output-path large sub-slots ([[]] = whole)
;;
;; A frame claims a flow's output paths in its elision registry when it first
;; evaluates the definition, and drops the claims when the flow leaves the
;; frame. Before that evaluation emits anything, the pass ADDS the paths to the
;; live registry, so the first trace of the output is redacted; the exact set,
;; with the old definition's paths or a departed flow's claims removed, rides
;; the candidate runtime-db and installs with the outputs it describes. Adding
;; never un-redacts, so an event that does not commit needs no undo: at worst a
;; path no output occupies stays claimed. The registry governs flow traces and
;; observation of the materialized app-db slot. The owner identity names the
;; flow, so replacing or dropping one flow's claims leaves every other claim on
;; the same path standing.

(defn- explicit-flow-output-mark-paths
  "Return `[sensitive-paths large-paths]` as absolute app-db paths."
  [flow]
  (let [output-path        (vec (:output-path flow))
        absolute-path      (fn [subpath] (into output-path subpath))
        sensitive-paths    (->> (:sensitive flow)
                                (filter vector?)
                                (map absolute-path))
        large-paths        (->> (:large flow)
                                (filter vector?)
                                (map absolute-path))
        whole-output-large (when (true? (:large? flow)) [output-path])]
    [(vec sensitive-paths)
     (vec (concat whole-output-large large-paths))]))

(defn ^:no-doc flow-declares-marks?
  "True when the flow declares output classification."
  [flow]
  (or (contains? flow :sensitive)
      (contains? flow :large)
      (contains? flow :large?)))

(defn- flow-owner
  "The elision-registry owner identity a flow's output claims sit under."
  [flow-id]
  {:source :flow :flow-id flow-id})

(defn ^:no-doc add-output-marks!
  "Add the paths `flow`'s definition declares to its claims in `frame-id`'s
  live elision registry, keeping the claims it already holds."
  [frame-id flow]
  (let [owner (flow-owner (:id flow))
        [explicit-s explicit-l] (explicit-flow-output-mark-paths flow)]
    (rf.elision/swap-elision-slot!
      frame-id
      (fn [reg]
        (-> (or reg {})
            (rf.elision/add-claims :sensitive-declarations owner explicit-s)
            (rf.elision/add-claims :declarations owner explicit-l))))))

(defn ^:no-doc exact-output-marks
  "A fn of runtime-db that sets each flow's claims to exactly what it declares.
  `claims` maps a flow id to the definition the pass evaluated, or to nil for
  a flow that left the frame, whose claims go."
  [claims]
  (fn [runtime-db]
    (let [runtime-db (or runtime-db {})]
      (rf.elision/write-elision-slot
        runtime-db
        (reduce-kv
          (fn [reg id flow]
            (let [owner   (flow-owner id)
                  [s l]   (if flow (explicit-flow-output-mark-paths flow) [[] []])]
              (-> reg
                  (rf.elision/replace-owner-claims :sensitive-declarations owner s)
                  (rf.elision/replace-owner-claims :declarations owner l))))
          (or (:rf.runtime/elision runtime-db) {})
          claims)))))

;; ---- partition-qualified input primitives -------------------------------
;;
;; Runtime evaluation, trace elision, and tooling projection share this syntax.

(def ^:no-doc runtime-partition-key
  "The leading input-path key that selects runtime-db."
  :rf.db/runtime)

(defn ^:no-doc runtime-input?
  "True when `path` is qualified to read runtime-db."
  [path]
  (= runtime-partition-key (first path)))

(defn ^:no-doc partition-relative-input-path
  "Strip the runtime qualifier, leaving the path within its partition."
  [input-path]
  (if (runtime-input? input-path)
    (subvec (vec input-path) 1)
    (vec input-path)))

;; ---- registration --------------------------------------------------------

(defn- invalid-metadata!
  [flow-id reason value]
  (throw (rf.error/thrown-ex-info
           :rf.error/invalid-flow-metadata
           'rf/reg-flow
           reason
           {:recovery :fix-registration
            :extra    {:id flow-id :value value}})))

(defn- reconstruct-flow
  "Build the stored definition from `(reg-flow id metadata derive-fn)`,
  refusing a non-map metadata slot, a `:derive` left in the metadata map, and a
  `:frame` or a `:schema` in it, before any map operation runs."
  [flow-id metadata derive-fn]
  (when-not (map? metadata)
    (invalid-metadata!
      flow-id
      (str "flow " flow-id "'s metadata (the MIDDLE slot) must be a map, "
           "got " (pr-str (type metadata)) ". The grammar "
           "is (reg-flow " flow-id " {…} derive-fn): the :inputs / "
           ":output-path / :doc reflection-config metadata map "
           "is the SECOND slot, the pure :derive fn is the THIRD.")
      metadata))
  (when (contains? metadata :derive)
    (invalid-metadata!
      flow-id
      (str "flow " flow-id " declares :derive inside its metadata map — "
           "the pure derivation is the THIRD slot: "
           "(reg-flow " flow-id " {…} derive-fn). Move the derive fn out "
           "of the metadata map into the value slot.")
      (:derive metadata)))
  (when (contains? metadata :frame)
    (invalid-metadata!
      flow-id
      (str "flow " flow-id " declares :frame " (pr-str (:frame metadata))
           ", but a flow definition names no frame. Register it at the top "
           "level without :frame, and name it in the :flows config of each "
           "frame that runs it, e.g. (rf/make-frame {:id :my/app :flows ["
           flow-id "]}).")
      (:frame metadata)))
  ;; A flow's output shape has one declaration: the app-db schema at its
  ;; `:output-path`, which the commit boundary checks with the event.
  (when (contains? metadata :schema)
    (invalid-metadata!
      flow-id
      (str "flow " flow-id " declares :schema, but a flow carries no schema of "
           "its own. Declare the output's shape once, with reg-app-schema at "
           "the flow's :output-path, e.g. (rf/reg-app-schema "
           (pr-str (:output-path metadata)) " " (pr-str (:schema metadata)) ").")
      (:schema metadata)))
  ;; `:executable-key` names `:derive` as the definition's executable identity,
  ;; so a re-registration's `:rf.registry/handler-replaced` reports
  ;; `:different-fn?` from the derive rather than from an absent `:handler-fn`.
  (assoc metadata :id flow-id :derive derive-fn :executable-key :derive))

(defn reg-flow
  "Register the flow definition `flow-id`:

      (rf/reg-flow :rectangle/area
        {:inputs      [[:width] [:height]]
         :output-path [:area]
         :doc         \"Rectangle area from :width × :height.\"}
        (fn [w h] (* w h)))

  The derive function is the third slot. Metadata carries the required
  `:inputs` and `:output-path` plus optional documentation and output
  classification. The definition is a top-level registration under the
  registrar's `:flow` kind: it reads no frame, and runs in each frame whose
  `:flows` config names it. Re-registering an id replaces the definition, and
  each frame running it recomputes on its next event. Returns `flow-id`."
  [flow-id metadata derive-fn]
  (let [flow (reconstruct-flow flow-id metadata derive-fn)]
    (validate-flow flow)
    (let [{:keys [was]} (rf.registrar/register! kind flow-id
                                                (rf.source-coords/merge-coords flow))]
      ;; A replacement rides the registrar's `:rf.registry/handler-replaced`.
      (when (and rf.interop/debug-enabled? (nil? was))
        (rf.trace/emit! :flow :rf.flow/registered
                        {:flow-id flow-id
                         :inputs  (:inputs flow)
                         :path    (:output-path flow)}))
      flow-id)))

(defn clear-flow
  "Remove the flow definition `id` — the `:flow` arm of `(rf/clear :flow id)`.
  Each frame whose `:flows` names it vacates the output on its next event.
  Emits `:rf.flow/cleared` when a definition existed. Returns nil."
  [id]
  (let [prior (get (rf.registrar/registrations kind) id)]
    (rf.registrar/unregister! kind id)
    (when (and rf.interop/debug-enabled? (some? prior))
      (rf.trace/emit! :flow :rf.flow/cleared
                      {:flow-id id
                       :path    (:output-path prior)}))
    nil))

;; ---- activation ------------------------------------------------------------

(defn- unregistered-flow!
  [frame-id ids id]
  (rf.error/throw-error!
    :rf.error/unregistered-flow
    'rf/make-frame
    (str "frame " (pr-str frame-id) " names flow " (pr-str id) " in its :flows "
         (pr-str ids) ", but no reg-flow defines it in the frame's view. "
         "Register the flow, or remove the id from the frame's :flows.")
    {:recovery :fix-registration
     :extra    {:frame frame-id :flow-id id :flows ids}}))

(defn ^:no-doc check-activation!
  "Run the activation checks over a frame's `:flows` ids: each resolves through
  the registration view the caller has bound, and the resolved set has no two
  overlapping output paths and no dependency cycle. Throws
  `:rf.error/unregistered-flow`, `:rf.error/flow-path-overlap` or
  `:rf.error/flow-cycle`. The `:flows/check-activation!` hook `make-frame`
  calls before any frame state is written."
  [frame-id ids]
  (let [active (reduce (fn [m id]
                         (if-let [flow (rf.registrar/lookup kind id)]
                           (assoc m id flow)
                           (unregistered-flow! frame-id ids id)))
                       {}
                       ids)]
    (rf.flows.topo/detect-output-path-overlap! active 'rf/make-frame)
    (rf.flows.topo/topo-sort active 'rf/make-frame)
    nil))

;; ---- path vacation ---------------------------------------------------------

(defn- dissoc-in-safe
  "Remove a nested leaf without creating nil parents. A MAP parent has the leaf
  `dissoc`'d; a VECTOR parent with an in-range non-negative integer index is
  vacated by `assoc`-ing `nil` at that index — the ONLY local vacation a vector
  admits (a `dissoc` would shift every later element and silently corrupt
  sibling outputs). Integer segments are valid path elements
  (`re-frame.path/segment?`), and the evaluator writes a vector-index output
  via `assoc-in`, so vacation must be symmetric with that write — matching
  `re-frame.path/container-for`'s vector-index semantics. Returns
  `db` unchanged when the path is absent or the parent is a scalar / set / seq
  (or an out-of-range vector index) that cannot hold the leaf."
  [db path]
  (let [parent-path (vec (butlast path))
        leaf        (last path)
        parent      (get-in db parent-path ::missing)]
    (cond
      (or (= ::missing parent) (nil? parent)) db
      (map? parent) (update-in db parent-path dissoc leaf)
      (and (vector? parent) (integer? leaf) (<= 0 leaf) (< leaf (count parent)))
      (assoc-in db path nil)
      :else db)))

(defn ^:no-doc vacate-path-in-db
  "Remove the leaf at `path` from `db`. Leaf-only: empty ancestors remain,
  because the flow does not own them.

  Single-element paths require direct `dissoc`; `update-in` at `[]` does not
  mean removal from the root."
  [db path]
  (if (= 1 (count path))
    (dissoc db (first path))
    (dissoc-in-safe db path)))

;; ---- frame-destroy teardown ---------------------------------------------

(defn teardown-on-frame-destroy!
  "Drop the destroyed frame's evaluation memo. Definitions live in the
  registrar and the frame's elision registry dies with its frame-state
  container, so nothing else is frame-owned. Idempotent."
  [frame-id]
  (when frame-id
    (swap! frame-memo dissoc frame-id))
  nil)

;; ---- test-only resets ----------------------------------------------------

(defn reset-last-inputs!
  "Test-only: drop every frame's evaluation memo. Registrations are the
  registrar's, which a test fixture snapshots and restores."
  []
  (reset! frame-memo {})
  nil)

(defn reset-flows!
  "Test-only: drop every frame's evaluation memo — the `:flows/reset-flows!`
  hook the runtime-reset fixture fires."
  []
  (reset-last-inputs!))
