(ns re-frame.flows.registry
  "Frame-scoped flow registration and lifecycle state.

  The authoritative registry is `{frame-id {flow-id flow-map}}`; the same id
  may have a different definition in each frame. Dirty-check values and pending
  output-path vacations also use per-frame containers so drain rollback cannot
  interfere with a sibling frame. External consumers use the snapshot and
  lifecycle functions rather than the private atoms."
  (:require [re-frame.elision :as elision]
            [re-frame.error :as error]
            [re-frame.flows.topo :as topo]
            [re-frame.frame :as frame]
            [re-frame.interop :as interop]
            [re-frame.path :as path]
            [re-frame.source-coords :as source-coords]
            [re-frame.trace :as trace]))

;; ---- state ---------------------------------------------------------------
;;
(defonce
  ^{:doc     "frame-id → flow-id → flow-map. Per-frame so undo / time-travel
              / clear semantics are unambiguous."
    :private true}
  flows
  (atom {}))

;; The outer registry changes only when a frame's cache is created or removed.
;; Drains mutate the frame's stable inner atom and never contend with sibling
;; frames on cache updates.
(defonce
  ^{:doc     "frame-id → (atom {flow-id last-seen-input-vec})."
    :private true}
  frame-last-inputs
  (atom {}))

(defn- ^:no-doc ensure-frame-last-inputs-atom!
  "Return a frame's cache atom, creating it atomically on first touch."
  [frame-id]
  (or (get @frame-last-inputs frame-id)
      (get (swap! frame-last-inputs
                  (fn [m]
                    (if (contains? m frame-id)
                      m
                      (assoc m frame-id (atom {})))))
           frame-id)))

;; ---- public read accessors -----------------------------------------------
;;
(defn flows-snapshot
  "Return `{frame-id {flow-id flow-map}}`."
  []
  @flows)

;; ---- per-frame flow introspection ----------------------------------------

(defn- coerce-flow-opts
  "Coerce a frame target or opts map to the `flow-meta-at` opts shape.

  Test frame values before `map?` because frame values are maps."
  [opts-or-frame-id]
  (cond
    (nil? opts-or-frame-id) {}
    (or (keyword? opts-or-frame-id)
        (frame/frame-value? opts-or-frame-id)) {:frame opts-or-frame-id}
    (map? opts-or-frame-id) opts-or-frame-id
    :else {:frame opts-or-frame-id}))

(defn- resolve-read-frame
  "Resolve an explicit frame target or require an ambient frame."
  [opts]
  (let [override (:frame opts)]
    (if (some? override)
      (frame/frame-target->id override)
      (frame/require-current-frame!
        :flow-meta-at {:where 'rf/flow-meta-at}))))

(defn flow-meta-at
  "Return a flow's registration map in the selected frame, or nil.

  With one argument, use the ambient frame. The second argument accepts either
  `{:frame target}` or a frame target directly."
  ([flow-id] (flow-meta-at flow-id {}))
  ([flow-id opts-or-frame-id]
   (let [opts     (coerce-flow-opts opts-or-frame-id)
         frame-id (resolve-read-frame opts)]
     (get-in @flows [frame-id flow-id]))))

(defn ^:no-doc last-inputs-snapshot
  "Return raw cached inputs as `{flow-id {frame-id inputs}}`.

  This internal/test accessor is not an egress boundary; use projected flow
  traces when values leave the owning runtime."
  []
  (reduce-kv
    (fn [acc frame-id inner-atom]
      (reduce-kv
        (fn [acc flow-id inputs]
          (assoc-in acc [flow-id frame-id] inputs))
        acc
        @inner-atom))
    {}
    @frame-last-inputs))

;; ---- intra-artefact-only mutation helpers --------------------------------
;;
(defn ^:no-doc frame-last-inputs-snapshot
  "Return a frame's dirty-check rows as `{flow-id inputs}`."
  ([frame-id]
   (if-let [a (get @frame-last-inputs frame-id)]
     @a
     {}))
  ([frame-id owner-token]
   (when (frame/event-continuation-live? frame-id owner-token)
     (frame-last-inputs-snapshot frame-id))))

(defn ^:no-doc get-frame-flow-last-inputs
  "Return the cached inputs for a flow in a frame, or nil."
  [frame-id flow-id]
  (when-let [a (get @frame-last-inputs frame-id)]
    (get @a flow-id)))

(defn ^:no-doc set-frame-flow-last-inputs!
  "Store a flow's current inputs in its frame-local cache."
  [frame-id flow-id inputs]
  (swap! (ensure-frame-last-inputs-atom! frame-id) assoc flow-id inputs))

(defn ^:no-doc reset-frame-last-inputs-to!
  "Restore one frame's dirty-check cache from a prior snapshot."
  ([frame-id prior]
   (reset! (ensure-frame-last-inputs-atom! frame-id) prior))
  ([frame-id owner-token prior]
   (when (frame/event-continuation-live? frame-id owner-token)
     (when-let [a (get @frame-last-inputs frame-id)]
       (reset! a prior)))))

;; ---- pending abandoned output paths --------------------------------------
;;
;; Moving or clearing a flow must vacate its old app-db path. Outside a drain we
;; can write app-db directly. Inside a drain that write would be overwritten by
;; the pending commit, so lifecycle operations queue the path here and
;; `run-flows-on-db` removes it from the pending db instead.

(defonce
  ^{:doc     "frame-id → (atom #{output-path ...}) pending in-drain vacation."
    :private true}
  frame-abandoned-output-paths
  (atom {}))

(defn- ensure-frame-abandoned-paths-atom!
  "Return a frame's pending-path atom, creating it atomically."
  [frame-id]
  (or (get @frame-abandoned-output-paths frame-id)
      (get (swap! frame-abandoned-output-paths
                  (fn [m]
                    (if (contains? m frame-id)
                      m
                      (assoc m frame-id (atom #{})))))
           frame-id)))

(defn ^:no-doc record-abandoned-output-path!
  "Queue an output path for removal from this frame's pending db."
  [frame-id path]
  (swap! (ensure-frame-abandoned-paths-atom! frame-id) conj path))

(defn ^:no-doc abandoned-output-paths-snapshot
  "Return a frame's pending output-path vacations as a set."
  ([frame-id]
   (if-let [a (get @frame-abandoned-output-paths frame-id)]
     @a
     #{}))
  ([frame-id owner-token]
   (when (frame/event-continuation-live? frame-id owner-token)
     (abandoned-output-paths-snapshot frame-id))))

(defn ^:no-doc drain-abandoned-output-paths!
  "Atomically read-and-clear `frame-id`'s pending abandoned output paths,
  returning the set that was pending. The current drain's `run-flows-on-db`
  consumes these once — dissocing each from the pending
  `:db` BEFORE the flow walk — and a throw / post-commit rollback re-records
  them via `restore-abandoned-output-paths!` so the move re-attempts cleanly
  next drain. Frame-local: returns an empty set when the frame has no
  container."
  [frame-id]
  (when-let [a (get @frame-abandoned-output-paths frame-id)]
    (let [drained (volatile! #{})]
      (swap! a (fn [s] (vreset! drained s) #{}))
      @drained)))

(defn ^:no-doc restore-abandoned-output-paths!
  "Restore one frame's pending output-path vacations from a prior snapshot."
  ([frame-id prior]
   (reset! (ensure-frame-abandoned-paths-atom! frame-id) (set prior)))
  ([frame-id owner-token prior]
   (when (frame/event-continuation-live? frame-id owner-token)
     (when-let [a (get @frame-abandoned-output-paths frame-id)]
       (reset! a (set prior))))))

;; ---- exact-incarnation flow-pass ownership -------------------------------

(defn ^:no-doc capture-flow-pass-state
  "Capture the registry values and private cache cells owned by one flow pass.

  The cells, not the bare frame id, are the mutation authority.  If a derive
  callback destroys incarnation A, teardown dissociates these cells and a
  same-id B creates fresh ones; the already-running A pass can therefore
  neither advance nor restore B's dirty-check/vacation state.  Returns nil
  when `owner-token` no longer names the live incarnation."
  [frame-id owner-token]
  (when (frame/event-continuation-live? frame-id owner-token)
    (let [state {:flow-map        (get @flows frame-id)
                 :last-inputs     (ensure-frame-last-inputs-atom! frame-id)
                 :abandoned-paths (ensure-frame-abandoned-paths-atom! frame-id)}]
      (when (frame/event-continuation-live? frame-id owner-token)
        state))))

(defn ^:no-doc legacy-flow-pass-state
  "Capture flow-pass state without an exact-owner fence (legacy/test arity)."
  [frame-id]
  {:flow-map        (get @flows frame-id)
   :last-inputs     (ensure-frame-last-inputs-atom! frame-id)
   :abandoned-paths (ensure-frame-abandoned-paths-atom! frame-id)})

(defn ^:no-doc pass-last-inputs-snapshot [pass]
  @(get pass :last-inputs))

(defn ^:no-doc pass-flow-last-inputs [pass flow-id]
  (get @(get pass :last-inputs) flow-id))

(defn ^:no-doc pass-set-flow-last-inputs! [pass flow-id inputs]
  (swap! (get pass :last-inputs) assoc flow-id inputs))

(defn ^:no-doc pass-reset-last-inputs! [pass prior]
  (reset! (get pass :last-inputs) prior))

(defn ^:no-doc pass-abandoned-paths-snapshot [pass]
  @(get pass :abandoned-paths))

(defn ^:no-doc pass-drain-abandoned-paths!
  [pass]
  (let [drained (volatile! #{})]
    (swap! (get pass :abandoned-paths)
           (fn [paths]
             (vreset! drained paths)
             #{}))
    @drained))

(defn ^:no-doc pass-restore-abandoned-paths! [pass prior]
  (reset! (get pass :abandoned-paths) (set prior)))

;; ---- last-inputs row maintenance -----------------------------------------
;;
(defn- drop-frame-flow-row!
  "Drop a flow's frame-local dirty-check row, if present."
  [frame-id flow-id]
  (when-let [a (get @frame-last-inputs frame-id)]
    (swap! a dissoc flow-id)))

;; ---- validation ----------------------------------------------------------
;;
;; Validate paths before topology or evaluation sees them. Classification
;; declarations fail closed so malformed safety metadata cannot silently
;; install an unprotected output.

(defn- valid-path-element?
  "True when `x` belongs to the shared concrete path-segment domain."
  [x]
  (path/segment? x))

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
   (error/thrown-ex-info
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

   ;; Reject legacy spellings rather than silently ignoring a safety mark.
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
;; Registration translates them to absolute app-db paths in the frame's elision
;; registry. That one registry governs flow traces and observation of the
;; materialized app-db slot. Source stamps let re-registration and clear remove
;; only this flow's declarations.

(defn- explicit-flow-output-mark-paths
  "Return `[sensitive-paths large-paths]` as absolute app-db paths."
  [flow]
  (let [base       (vec (:output-path flow))
        abs        (fn [sub] (into base sub))
        per-sens   (->> (:sensitive flow) (filter vector?) (map abs))
        per-large  (->> (:large flow)     (filter vector?) (map abs))
        whole-lg   (when (true? (:large? flow))     [base])]
    [(vec per-sens)
     (vec (concat whole-lg per-large))]))

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

(defn ^:no-doc input-resolve-path
  "Strip the runtime qualifier, leaving the path within its partition."
  [input-path]
  (if (runtime-input? input-path)
    (subvec (vec input-path) 1)
    (vec input-path)))

(defn- flow-declares-marks?
  "True when the flow declares output classification."
  [flow]
  (or (contains? flow :sensitive)
      (contains? flow :large)
      (contains? flow :large?)))

(defn- flow-owner
  "The multi-owner elision-registry owner identity a `reg-flow` output claims
  under. The `:flow-id` is carried so re-registration / clear scope to THIS
  flow (`replace-owner-claims` / `remove-owner`) without disturbing a sibling
  flow's — or any other source's — claim on the same absolute path."
  [flow-id]
  {:source :flow :flow-id flow-id})

(defn- fold-flow-declarations
  "Reconcile one flow's output declarations in an elision registry value:
  replace THIS flow's owner claims (both axes) with its current explicit
  sensitive / large absolute output paths, delegating to the core multi-owner
  op (`elision/replace-owner-claims`). A foreign owner (effect / route /
  machine / resource) — or a sibling flow — on the same absolute path survives
  untouched, and the flow's claim unions with theirs (rf2-wdm1vg)."
  [reg flow]
  (let [owner (flow-owner (:id flow))
        [explicit-s explicit-l] (explicit-flow-output-mark-paths flow)]
    (-> (or reg {})
        (elision/replace-owner-claims :sensitive-declarations owner explicit-s)
        (elision/replace-owner-claims :declarations owner explicit-l))))

(defn- write-flow-output-marks!
  "Install or refresh one flow's output declarations in its frame.

  rf2-vxgfnd.155 — the 3-arity threads A's `owner-token` through the exact-
  incarnation elision write so a synchronous container watch that destroys A
  mid-write cannot bump same-id B's commit epoch or write B's runtime-db."
  ([frame-id flow] (write-flow-output-marks! frame-id nil flow))
  ([frame-id owner-token flow]
   (let [xf (fn [reg] (fold-flow-declarations reg flow))]
     (if owner-token
       (elision/swap-elision-slot! frame-id owner-token xf)
       (elision/swap-elision-slot! frame-id xf)))))

(defn- clear-flow-output-marks!
  "Remove one flow's declarations while preserving every other declaration
  owner (and any sibling flow's) via the core `elision/remove-owner` op per
  axis.

  Frame teardown needs no per-flow scrub because the elision registry belongs
  to the frame-state container that destruction removes.

  rf2-vxgfnd.155 — the 3-arity threads A's `owner-token` through the exact-
  incarnation elision write so a synchronous container watch that destroys A
  mid-write cannot bump same-id B's commit epoch or write B's runtime-db."
  ([frame-id flow-id] (clear-flow-output-marks! frame-id nil flow-id))
  ([frame-id owner-token flow-id]
   (let [owner (flow-owner flow-id)
         xf    (fn [reg]
                 (-> (or reg {})
                     (elision/remove-owner :sensitive-declarations owner)
                     (elision/remove-owner :declarations owner)))]
     (if owner-token
       (elision/swap-elision-slot! frame-id owner-token xf)
       (elision/swap-elision-slot! frame-id xf)))))

;; ---- registration --------------------------------------------------------

;; Defined with the shared path-vacation helpers below.
(declare vacate-output-path!)

(defn- reconstruct-flow
  "Build the stored flow map from `(reg-flow id metadata derive-fn)`.

  Return `[flow frame-target]`; `:frame` controls mounting and is not stored as
  flow metadata. Reject a misplaced `:derive` key before reconstruction."
  [flow-id metadata derive-fn]
  ;; Validate before using map operations so callers receive a typed error.
  (when-not (map? metadata)
    (throw (error/thrown-ex-info
             :rf.error/invalid-flow-metadata
             'rf/reg-flow
             (str "flow " flow-id "'s metadata (the MIDDLE slot) must be a map, "
                  "got " (pr-str (type metadata)) ". Per rf2-bqstzr the grammar "
                  "is (reg-flow " flow-id " {…} derive-fn): the :inputs / "
                  ":output-path / :doc / :schema reflection-config metadata map "
                  "is the SECOND slot, the pure :derive fn is the THIRD.")
             {:recovery :fix-registration
              :extra    {:id flow-id :value metadata}})))
  (when (contains? metadata :derive)
    (throw (error/thrown-ex-info
             :rf.error/invalid-flow-metadata
             'rf/reg-flow
             (str "flow " flow-id " declares :derive inside its metadata map — "
                  "per rf2-bqstzr the pure derivation is the THIRD slot: "
                  "(reg-flow " flow-id " {…} derive-fn). Move the derive fn out "
                  "of the metadata map into the value slot.")
             {:recovery :fix-registration
              :extra    {:id flow-id :value (:derive metadata)}})))
  [(-> metadata (dissoc :frame) (assoc :id flow-id :derive derive-fn))
   (:frame metadata)])

(defn- flow-reload-shape
  "The observable shape of a stored flow definition, for deciding whether a
  re-registration is a genuine hot-reload edit or an idempotent replay.

  rf2-soyqfn: flow replacement evidence must be deduped from THIS frame's
  authoritative slot — the prior/new stored flow values — NOT the generic
  process-global `[:flow flow-id]` registrar dedup table, which is frame-blind.
  Two live frames replacing the same flow-id are two independent definitions
  (Spec 013 §Frame-scoping); a frame-blind key lets one frame's recorded shape
  suppress another frame's genuine replacement, and lets a destroyed frame's
  shape bleed into its same-id successor. Comparing the prior and new stored
  values of the SAME frame slot dedups each frame independently.

  Strips the source-coord keys (`:ns`/`:file`/`:line`/`:column`), which drift on
  every re-eval and are not a user-visible change — mirroring the generic
  registrar `handler-shape`. The remaining fields (`:derive` fn identity,
  `:inputs`, `:output-path`, plus any documentation/classification metadata)
  compare by value; a fresh `:derive` instance from a namespace re-eval, a
  changed body, or moved inputs/path all differ and emit, while a same-object
  identical replay is suppressed."
  [flow]
  (dissoc flow :ns :file :line :column))

(defn reg-flow
  "Register a flow against a frame:

      (rf/reg-flow :rectangle/area
        {:inputs      [[:width] [:height]]
         :output-path [:area]
         :doc         \"Rectangle area from :width × :height.\"}
        (fn [w h] (* w h)))

  The derive function is the third slot. Metadata contains the required
  `:inputs` and `:output-path`, optional documentation/classification, and an
  optional `:frame` target. Without a target, registration requires an ambient
  frame. Returns `flow-id`."
  ([flow-id metadata derive-fn]
   (let [[flow frame] (reconstruct-flow flow-id metadata derive-fn)]
   (validate-flow flow)
   (let [;; Normalize frame values before liveness checks and registry access.
         frame-id (or (some-> frame frame/frame-target->id)
                      (frame/require-current-frame!
                        :reg-flow
                        {:where    'rf/reg-flow
                         :event-id (:id flow)}))
         flow-id  (:id flow)
         ;; Store coordinates with the frame-scoped definition so tooling reads
         ;; them from the same authoritative value.
         flow     (source-coords/merge-coords flow)]
     ;; PIN the incarnation this call selected. The serialization helper below
     ;; intentionally tolerates absent frames (so `clear-flow` stays idempotent
     ;; and mid-drain effects run reentrantly), so a bare pre-serializer
     ;; liveness check is a check-then-act: a concurrent `destroy-frame!` can
     ;; complete in the window between that check and the winning registry
     ;; mutation, leaving a GHOST flow row on a dead frame — or, if the id is
     ;; re-registered to a NEW incarnation, the mutation could clobber it. So
     ;; instead of trusting a bare nil-check, capture the live incarnation TOKEN
     ;; here and REVALIDATE it INSIDE the serialized mutation (below), under the
     ;; frame's `:drain-lock` — the SAME lock `destroy-frame!` flips liveness
     ;; under (`frame/call-serialized-with-drain!`). Registration and
     ;; destruction therefore linearize on one frame-owned gate: a destroy that
     ;; wins makes the revalidation observe a nil / different token and refuse
     ;; (no ghost row); a registration that wins publishes its row, which the
     ;; destroy's flows-teardown hook then removes. See
     ;; re-frame.frame/frame-incarnation-token.
     (let [pinned-incarnation (frame/frame-incarnation-token frame-id)]
       (when (nil? pinned-incarnation)
         (error/throw-error!
           :rf.error/flow-frame-not-live
           'rf/reg-flow
           (str "cannot register a flow against frame "
                frame-id
                " — the frame is not live (absent / never registered, "
                "or torn down by destroy-frame!). Register the flow "
                "against a live frame; a destroyed frame must not "
                "acquire flow state (Spec 013 §destroy-frame!).")
           {:recovery :fix-registration
            :extra    {:frame frame-id
                       :flow  flow}}))
     ;; Capture the prior value inside the retrying swap so lifecycle decisions
     ;; use the state observed by the winning CAS.
     (let [prior-on-frame (volatile! nil)]
       ;; Publish, path vacation, mark replacement, and cache invalidation are
       ;; serialized with the frame drain. The registry swap keeps topology
       ;; validation inside the CAS retry, preventing concurrent registrations
       ;; from jointly admitting a cycle or output overlap.
       (frame/call-serialized-with-drain!
         frame-id
         (fn []
           ;; LINEARIZATION GATE. Admit this mutation ONLY while the pinned
           ;; incarnation is still live. `destroy-frame!` flips liveness under
           ;; the same `:drain-lock` this thunk runs under, so if it linearized
           ;; first the current token is nil (destroyed / dissoc'd) or a
           ;; DIFFERENT incarnation's (id re-registered). Refuse in either case:
           ;; no ghost row survives the destroyed frame, and a newer
           ;; re-registration is never clobbered. A bare second `frame/frame`
           ;; nil-check is insufficient — it would still admit a write into a
           ;; re-registered NEW incarnation's slot; identity of the pinned token
           ;; is what rejects that.
           (when-not (identical? pinned-incarnation
                                 (frame/frame-incarnation-token frame-id))
             (error/throw-error!
               :rf.error/flow-frame-not-live
               'rf/reg-flow
               (str "cannot register a flow against frame "
                    frame-id
                    " — the frame was destroyed (or re-registered to a new "
                    "incarnation) concurrently with this registration. "
                    "Register the flow against a live frame; a destroyed frame "
                    "must not acquire flow state (Spec 013 §destroy-frame!).")
               {:recovery :fix-registration
                :extra    {:frame frame-id
                           :flow  flow}}))
           (swap! flows
                  (fn [m]
                    (let [prior-frame (get m frame-id)]
                      (vreset! prior-on-frame (get prior-frame flow-id))
                      (let [prospective (assoc prior-frame flow-id flow)]
                        ;; Prefer the specific output-overlap diagnostic before
                        ;; the dependency-cycle check.
                        (topo/detect-output-path-overlap! prospective)
                        (topo/topo-sort prospective)
                        (assoc m frame-id prospective)))))
           ;; A moved output must vacate the old path. Queue the vacation during
           ;; a drain so it is applied to the pending db; otherwise write now.
           ;; rf2-vxgfnd.155: the direct-path vacation is a callback-bearing
           ;; app-db write — thread A's pinned incarnation so a watch that loses
           ;; A cannot bump same-id B's commit epoch or write B's app-db.
           (when-let [prior @prior-on-frame]
             (let [old-path (:output-path prior)]
               (when (not= old-path (:output-path flow))
                 (if (frame/in-drain? frame-id)
                   (record-abandoned-output-path! frame-id old-path)
                   (vacate-output-path! frame-id pinned-incarnation old-path)))))
           ;; rf2-rxsldx: the output-mark write below is itself exact-
           ;; incarnation — it threads `pinned-incarnation` through
           ;; `swap-elision-slot!` → `swap-runtime-db-exact!`, whose
           ;; `identical? owner-token` guard makes it a TRUE no-op once A is
           ;; lost (the transform is never called, no epoch bump). A bare
           ;; post-vacation liveness pre-check guarding it therefore had NO
           ;; call-boundary effect the exact-owner postcheck below does not
           ;; already provide (the merged loss fixtures stay green with it
           ;; removed — the later exact mark and second check stop every visible
           ;; write), so the redundant check and its claim are dropped. The
           ;; downstream exact fences stay explicit: the mark write's internal
           ;; owner-token guard, and the postcheck below.
           (when (or (flow-declares-marks? flow)
                     (some? @prior-on-frame))
             (write-flow-output-marks! frame-id pinned-incarnation flow))
           ;; Exact-owner postcheck after the (callback-bearing) output-mark
           ;; container write: if a synchronous container watch destroyed A and
           ;; published a same-id B, abort before any bare-id dirty-cache drop,
           ;; registry mutation, or dedup-and-trace pipeline reaches B.
           (when (frame/event-continuation-live? frame-id pinned-incarnation)
             ;; A replacement invalidates the cache even when inputs are equal.
             ;; Hot-reload trace dedup compares the prior and new stored flow
             ;; shapes of THIS frame slot (`flow-reload-shape`, rf2-soyqfn).
             (when (some? @prior-on-frame)
               (drop-frame-flow-row! frame-id flow-id)
               ;; rf2-rxsldx: the replacement dedup consultation and the
               ;; :rf.registry/handler-replaced emit form ONE synchronous,
               ;; callback-bearing pipeline — dedup-by-shape projection, then,
               ;; inside `emit!`, classification projection → epoch capture →
               ;; ordered tooling listeners. The postcheck above only proves A is
               ;; live at the instant the block STARTS; each trace-internal stage
               ;; rechecks ownership ONLY while a continuation predicate is
               ;; installed (`trace/continuation-live?` reads the always-true
               ;; default otherwise). A DIRECT cold `reg-flow` replacement — unlike
               ;; the reserved-effect `:rf.fx/reg-flow` route, which inherits the
               ;; router's exact-owner predicate — installs none, so absent this
               ;; wrap an epoch-capture callback or an already-entered listener
               ;; could destroy A and publish same-id B, and later listeners would
               ;; still receive A's incarnation-less :rf.registry/handler-replaced
               ;; after B owns the id (and later policy/capture could observe B).
               ;; Wrap the whole dedup+emit in the pinned incarnation's exact-owner
               ;; continuation, mirroring the first-registration fence (rf2-pwum1g):
               ;; already-entered delivery may stand once, every later framework-
               ;; owned trace stage is fenced. AND-composes with any parent (router)
               ;; predicate.
               (when interop/debug-enabled?
                 (trace/call-with-continuation-predicate
                   #(frame/event-continuation-live? frame-id pinned-incarnation)
                   (fn []
                     (let [prior          @prior-on-frame
                           different?     (not= (:derive prior) (:derive flow))
                           ;; rf2-soyqfn: decide replacement suppression from THIS
                           ;; frame's authoritative slot — the prior/new stored
                           ;; values — not the generic process-global
                           ;; `[:flow flow-id]` dedup table. The generic table is
                           ;; frame-blind: two live frames replacing the same
                           ;; flow-id to the same shape collide on one key, so the
                           ;; second frame's genuine replacement was suppressed and
                           ;; left unattributable, and a same-id frame
                           ;; reincarnation could inherit its predecessor's recorded
                           ;; shape. A per-frame prior/new shape compare suppresses
                           ;; only a true idempotent hot reload WITHIN this frame,
                           ;; and lets each frame emit its own event (Spec 013
                           ;; §independent frame ownership). Generic registrar dedup
                           ;; for process-scoped kinds is untouched; no parallel
                           ;; flow registry is introduced.
                           shape-changed? (not= (flow-reload-shape prior)
                                                (flow-reload-shape flow))]
                       (when shape-changed?
                         (trace/emit! :rf.registry :rf.registry/handler-replaced
                                      {:kind          :flow
                                       :id            flow-id
                                       :frame         frame-id
                                       :different-fn? different?})))))))
             ;; rf2-ytpeqf: first-registration evidence, kept INSIDE the exact-
             ;; owner postcheck. Registration is first-time per frame; replacements
             ;; use the hot-reload dedup trace above. A first-time flow with output
             ;; marks reaches a callback-bearing runtime-db write
             ;; (`write-flow-output-marks!` above); a synchronous container watch
             ;; can destroy A there and publish a same-id B. Because the postcheck
             ;; guarding this block then fails, A's :rf.flow/registered is never
             ;; delivered against B and B's trace policy is never consulted for A.
             ;; (`clear-flow` fences its :rf.flow/cleared the same way — rf2-rxsldx.)
             ;;
             ;; rf2-pwum1g: the postcheck above only proves A is live at the instant
             ;; emission STARTS. `trace/emit!` is itself a synchronous, callback-
             ;; bearing pipeline — classification projection, then epoch capture,
             ;; then the ordered tooling listeners — and each stage rechecks
             ;; ownership ONLY while a continuation predicate is installed. The event
             ;; router installs its own exact-owner predicate for the reserved-effect
             ;; `:rf.fx/reg-flow` route, but a DIRECT cold `reg-flow` does not — so
             ;; absent this wrap, an epoch-capture callback or the first listener
             ;; could destroy A and publish same-id B, and later listeners would
             ;; still receive A's incarnation-less :rf.flow/registered after B owns
             ;; the id. Wrap the emit in the pinned incarnation's exact-owner
             ;; continuation so every trace-internal stage is fenced: already-entered
             ;; delivery may stand once, but no later framework-owned trace stage
             ;; starts after A's exact ownership is lost. AND-composes with any parent
             ;; predicate, so the reserved-effect route's router predicate is
             ;; preserved.
             (when (and interop/debug-enabled? (nil? @prior-on-frame))
               (trace/call-with-continuation-predicate
                 #(frame/event-continuation-live? frame-id pinned-incarnation)
                 (fn []
                   (trace/emit! :flow :rf.flow/registered
                                {:flow-id flow-id
                                 :inputs  (:inputs flow)
                                 :path    (:output-path flow)
                                 :frame   frame-id})))))))))
     flow-id))))

(defn- dissoc-in-safe
  "Remove a nested leaf without creating nil parents. A MAP parent has the leaf
  `dissoc`'d; a VECTOR parent with an in-range non-negative integer index is
  vacated by `assoc`-ing `nil` at that index — the ONLY local vacation a vector
  admits (a `dissoc` would shift every later element and silently corrupt
  sibling outputs). Integer segments are valid path elements
  (`re-frame.path/segment?`), and `evaluate-flow!` writes a vector-index output
  via `assoc-in`, so vacation must be symmetric with that write — matching
  `re-frame.path/container-for`'s vector-index semantics (rf2-vx1ps6). Returns
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
  "Pure leaf removal shared by immediate and in-drain path vacation.

  Single-element paths require direct `dissoc`; `update-in` at `[]` does not
  mean removal from the root."
  [db path]
  (if (= 1 (count path))
    (dissoc db (first path))
    (dissoc-in-safe db path)))

(defn- vacate-output-path!
  "Remove an output path from a frame's app-db, skipping no-op writes.

  rf2-vxgfnd.155 — the 3-arity threads A's `owner-token` through the exact-
  incarnation app-db write so a synchronous container watch that destroys A
  mid-vacation cannot bump same-id B's commit epoch or write B's app-db."
  ([frame-id path] (vacate-output-path! frame-id nil path))
  ([frame-id owner-token path]
   (when-let [db (frame/frame-app-db-value frame-id)]
     (let [new-db (vacate-path-in-db db path)]
       (when-not (identical? new-db db)
         (if owner-token
           (frame/swap-frame-db-exact! frame-id owner-token (constantly new-db))
           (frame/swap-frame-db! frame-id (constantly new-db))))))))

(defn clear-flow
  "Deregister a flow and remove its output leaf from the selected frame.

  Without an explicit `:frame`, the ambient frame is required. Vacation is
  leaf-only: empty ancestors remain because the flow does not own them."
  ([id] (clear-flow id {}))
  ([id {:keys [frame] :as _opts}]
   (let [;; Normalize frame values before registry access.
         frame-id (or (some-> frame frame/frame-target->id)
                      (frame/require-current-frame!
                        :clear-flow
                        {:where    'rf/clear-flow
                         :event-id id}))]
     ;; Read the path, deregister, and EMIT the :rf.flow/cleared evidence all
     ;; INSIDE the drain lock: a concurrent replacement cannot make us vacate
     ;; stale metadata while clearing the new row, and — rf2-rxsldx — the clear
     ;; trace is initiated while the pinned incarnation is still authoritative
     ;; (previously it emitted after the serialized section released, carrying no
     ;; token). A no-op clear (no flow / lost owner) emits nothing.
     (frame/call-serialized-with-drain!
       frame-id
       (fn []
         (when-let [flow (get-in @flows [frame-id id])]
           ;; rf2-vxgfnd.155: PIN A's incarnation so the callback-bearing
           ;; output-mark / path-vacation writes below cannot let a stale A
           ;; tail dissociate a same-id B's flow row, drop B's dirty-check
           ;; cache, or bump B's commit epoch. A synchronous container watch
           ;; may destroy A and publish B mid-write; only A's write that
           ;; linearized before that loss stands, and every later registry /
           ;; cache / trace action is fenced by the exact-owner postcheck /
           ;; continuation below.
           (let [pinned (frame/frame-incarnation-token frame-id)
                 path   (:output-path flow)]
             ;; In-drain vacation must modify the pending db, not the live
             ;; app-db that the deferred commit will replace.
             (if (frame/in-drain? frame-id)
               (record-abandoned-output-path! frame-id path)
               (vacate-output-path! frame-id pinned path))
             ;; rf2-rxsldx: the output-mark clear below is itself exact-
             ;; incarnation (it threads `pinned` through `swap-elision-slot!` →
             ;; `swap-runtime-db-exact!`, a TRUE no-op once A is lost — the
             ;; transform is never called, no epoch bump). A bare post-vacation
             ;; liveness pre-check guarding it added no call-boundary effect the
             ;; exact-owner postcheck below does not already provide (the merged
             ;; loss fixtures stay green with it removed), so the redundant check
             ;; and its claim are dropped; the downstream exact fences (the mark
             ;; clear's internal owner-token guard, and the postcheck below) stay
             ;; explicit.
             (clear-flow-output-marks! frame-id pinned id)
             ;; Exact-owner postcheck after the (callback-bearing) output-mark
             ;; container write: if the watch lost A, abort before the bare-id
             ;; flow-row dissoc, dirty-cache drop, and clear-trace pipeline reach B.
             (when (frame/event-continuation-live? frame-id pinned)
               ;; Prune an empty frame row rather than expose `{frame-id {}}`.
               (swap! flows (fn [m]
                              (let [m' (update m frame-id dissoc id)]
                                (cond-> m'
                                  (empty? (get m' frame-id)) (dissoc frame-id)))))
               (drop-frame-flow-row! frame-id id)
               ;; rf2-rxsldx: emit :rf.flow/cleared HERE — inside the exact-owner
               ;; serialization, while `pinned` is authoritative. `trace/emit!` is
               ;; a synchronous, callback-bearing pipeline (classification
               ;; projection → epoch capture → ordered tooling listeners) whose
               ;; stages recheck ownership ONLY while a continuation predicate is
               ;; installed (`trace/continuation-live?` reads the always-true
               ;; default otherwise). Absent the wrap, a mid-emit destroy of A +
               ;; same-id B lets later listeners receive A's incarnation-less
               ;; :rf.flow/cleared after B owns the id (and later policy/capture
               ;; could observe B). Wrap the emit in the pinned incarnation's
               ;; exact-owner continuation, mirroring the first-registration fence
               ;; (rf2-pwum1g): already-entered delivery may stand once, every
               ;; later framework-owned trace stage is fenced. AND-composes with
               ;; any parent (router) predicate.
               (when interop/debug-enabled?
                 (trace/call-with-continuation-predicate
                   #(frame/event-continuation-live? frame-id pinned)
                   (fn []
                     (trace/emit! :flow :rf.flow/cleared
                                  {:flow-id id
                                   :path    path
                                   :frame   frame-id})))))))))
     nil)))

;; ---- frame-destroy teardown ---------------------------------------------

(defn teardown-on-frame-destroy!
  "Release registry, dirty-check, and pending-vacation state for a frame.

  The frame owns its elision registry, so destroying the frame-state container
  also removes flow declarations without a separate scrub. Idempotent."
  [frame-id]
  (when frame-id
    (swap! flows dissoc frame-id)
    (swap! frame-last-inputs dissoc frame-id)
    (swap! frame-abandoned-output-paths dissoc frame-id))
  nil)

;; ---- test-only resets ----------------------------------------------------

(defn reset-last-inputs!
  "Test-only: clear all dirty-check caches without clearing registrations."
  []
  (reset! frame-last-inputs {})
  nil)

(defn reset-flows!
  "Test-only: clear registrations, dirty-check caches, and pending vacations."
  []
  (reset! flows {})
  (reset! frame-last-inputs {})
  (reset! frame-abandoned-output-paths {})
  nil)
