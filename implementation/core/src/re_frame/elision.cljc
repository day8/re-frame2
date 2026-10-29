(ns re-frame.elision
  "Frame-owned wire-boundary elision.

  Durable app-db classification is static. `reg-classification` registrations
  declare app-db-absolute `:sensitive` / `:large` paths beside the feature, and
  each `reg-flow` a frame runs declares its output's. Both resolve through the
  frame's own view into an immutable classification POLICY (`frame-policy`)
  that egress reads; nothing about classification is written into frame state
  (Spec 015 §Static app-db classification). The policy also carries each
  runtime-db root's subsystem declarations — machines, resources, mutations,
  routes — which egress applies, through the root owner's late-bound hook, to
  the instances present in the runtime-db being projected (Spec 015
  §Subsystem projection-relative classification).

  Schemas describe shape, not egress policy (EP-0015 §8): a `reg-app-schema`
  slot's `:sensitive?` / `:large?` props redact that schema's own
  validation-failure trace and nothing else."
  (:require [re-frame.error :as rf.error]
            [re-frame.frame :as rf.frame]
            [re-frame.interop :as rf.interop]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.privacy :as rf.privacy]
            [re-frame.registrar :as rf.registrar]
            [re-frame.source-store :as rf.source-store]
            [re-frame.substrate.adapter :as rf.substrate.adapter]
            [re-frame.trace :as rf.trace]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- runtime size threshold ----------------------------------------------
;;
;; Per API.md §Size-elision wire-boundary walker and §Configure keys, the
;; runtime auto-detect threshold for the `:rf.warning/large-value-unschema'd`
;; advisory is configurable. Precedence (normative, API.md L507):
;;
;;   explicit `:rf.egress/threshold-bytes` opt  >  `(rf/configure! {:elision …})`  >  default
;;
;; A threshold of 0 disables runtime auto-detect entirely (only
;; frame-declared `:large` entries elide); the unschema'd-large warning
;; never fires.
;; The default mirrors the documented `{:rf.egress/threshold-bytes 16384}`.

(def ^:private default-threshold-bytes 16384)

(defonce ^:private config
  ;; Map shape so future :elision configure-keys land additively, matching
  ;; `re-frame.subs.cache/config`.
  (atom {:rf.egress/threshold-bytes default-threshold-bytes}))

(defn configure!
  "Update the elision configuration. Supports
  `{:rf.egress/threshold-bytes N}` — a non-negative integer runtime
  auto-detect size threshold for the `:rf.warning/large-value-unschema'd`
  advisory (0 disables runtime auto-detect; only frame-declared `:large`
  entries elide). Per API.md §Configure keys (`:elision`) and Spec 009
  §Size elision in traces. Routed from `re-frame.core/configure!`.

  ONE KEY, NOT TWO. This process-level knob and the per-call
  egress opt are the SAME keyword read out of two maps — the precedence
  ladder at the top of this namespace is `explicit opt > this config >
  default`, and `configured-threshold-bytes` below reads
  `:rf.egress/threshold-bytes` from `@config` exactly as `elide-against-frame`
  reads it from `opts`. So it shares the `:rf.egress/*` namespace with the
  rest of the vocabulary; API.md §Opts-key naming rule
  names this key as its worked example of a sub-key that earns a namespace
  precisely BECAUSE it is one contract with more than one reader.

  Unlike the egress opts maps, this one is NOT closed — `select-keys` drops
  an unrecognised key silently, so a caller passing a misspelled or
  unknown key configures nothing rather than throwing: the
  `merge` is a no-op, so whatever threshold is already in effect stays in
  effect. What that costs is a DIAGNOSTIC, never an egress: this number is
  read at exactly one place — the leaf arm that fires
  `:rf.warning/large-value-unschema'd` — and an over-threshold unschema'd
  value ships UNCHANGED at any threshold. Declared `:large` and
  `:sensitive` paths elide and redact without consulting it, 0 included.
  Closing this map is a separate question about `configure!` generally."
  [opts]
  (when (map? opts)
    (swap! config merge (select-keys opts [:rf.egress/threshold-bytes])))
  nil)

(defn current-config
  "Return the current elision configuration map.

  IMPLEMENTATION SEAM, not a public reader — the public door is
  `re-frame.core/current-config`, which reports this map under its
  `:elision` key. The facade
  calls straight through to here rather than via the late-bind table,
  because elision ships in core and is never an optional artefact."
  []
  @config)

(defn- configured-threshold-bytes
  "The configured runtime size threshold, falling back to the documented
  default when no `configure` call has set it."
  []
  (let [v (:rf.egress/threshold-bytes @config)]
    (if (some? v) v default-threshold-bytes)))

(defonce ^:private warned-unschema'd
  (atom #{}))

;; ---- the static classification policy -------------------------------------
;;
;; A frame's classification POLICY is an immutable value holding declarations
;; only, never instances, resolved through the frame's OWN view — the sealed
;; generation of a frame made with `:images`, the live registration store
;; otherwise. Under `:app-db` each path maps to the owners that claim it: a
;; `:classification` registration as `{:source :classification :id <id>}`, a
;; flow the frame runs as `{:source :flow :flow-id <id>}`. Under `:roots` it
;; holds, per runtime-db root, the declarations that root's hook collected
;; through the same view; egress applies them to the instances the projected
;; runtime-db holds (`with-runtime-classification`).
;;
;; The policy is a function of the view and of the frame's flow outputs, so it
;; is cached per frame against both: successive egresses, and every value
;; recorded while the declarations stand still, share one policy value. Spec
;; 015 §Static app-db classification, §Subsystem projection-relative
;; classification.

(def ^:private empty-policy
  {:app-db {:sensitive {} :large {}}})

(defonce ^:private policy-cache
  ;; {frame-id {:view <the view it was built from> :flows <the flow outputs> :policy <policy>}}
  (atom {}))

(def ^:private runtime-root-hooks
  "Each runtime-db root, the late-bound hook its owning artefact publishes for
  it, and the `:source` its claims carry. Each hook is a 0-arity collect of its
  registrations' declarations and a pure 2-arity apply of them to the value
  under the root. A root whose hook is unbound has no classification: without
  its artefact, a runtime-db holds no instances under that root. Spec 015
  §Subsystem projection-relative classification."
  {:rf.runtime/machines  [:machines/runtime-classification :machine]
   :rf.runtime/resources [:resources/runtime-classification :resource]
   :rf.runtime/mutations [:resources/mutation-runtime-classification :mutation]
   :rf.runtime/routing   [:routing/runtime-classification :route]})

(defn- resolved-classifications
  "The `[id descriptor]` entries of every `:classification` registration the
  bound resolution runs. An id more than one namespace registers is one a frame
  made without `:images` refuses to run; classification is a union, so every
  namespace's declaration of it is kept rather than the id dropping out."
  []
  (concat (rf.registrar/registrations :classification)
          (for [[id ns->desc] (rf.registrar/ambiguous-registrations :classification)
                desc          (vals ns->desc)]
            [id desc])))

(defn- collected-roots
  "`{root {registration-id decl}}`: what each bound root hook collects, for the
  roots whose registrations declare anything. Called inside the frame's
  resolution, so every hook collects through the frame's own view."
  []
  (reduce-kv (fn [acc root [hook _source]]
               (let [decls (when-let [collect (rf.late-bind/get-fn hook)] (collect))]
                 (cond-> acc (seq decls) (assoc root decls))))
             {}
             runtime-root-hooks))

(defn- flow-outputs
  "`{flow-id {:sensitive [abs-path …] :large [abs-path …]}}`: the output
  declarations of the flows frame `frame-id` runs, collected by the flows
  artefact's `:flows/output-classification` hook inside the frame's
  resolution. Unbound (flows absent) ⇒ none. Spec 013 §Flow tracing."
  [frame-id]
  (when-let [collect (rf.late-bind/get-fn :flows/output-classification)]
    (collect frame-id)))

(defn- build-policy
  [entries flows roots]
  (let [claim  (fn [m owner paths]
                 (reduce #(update %1 (vec %2) (fnil conj #{}) owner) m paths))
        add    (fn [acc owner decl]
                 (-> acc
                     (update :sensitive claim owner (:sensitive decl))
                     (update :large claim owner (:large decl))))
        app-db (as-> (:app-db empty-policy) acc
                 (reduce (fn [acc [id desc]] (add acc {:source :classification :id id} desc))
                         acc entries)
                 (reduce-kv (fn [acc id decl] (add acc {:source :flow :flow-id id} decl))
                            acc (or flows {})))]
    (cond-> (if (= app-db (:app-db empty-policy))
              empty-policy
              {:app-db app-db})
      (seq roots) (assoc :roots roots))))

(defn- policy-view
  "What the bound resolution's registrations are a function of: the sealed
  generation when one is bound, else the registration table."
  []
  (or rf.registrar/*generation*
      @rf.source-store/kind->id->ns->descriptor))

(defn ^:no-doc frame-policy
  "The classification policy of frame `frame-id`:
  `{:app-db {:sensitive {path #{owner}} :large {path #{owner}}}
    :roots  {runtime-db-root {registration-id decl}}}`, built from the
  `:classification` registrations, the outputs of the flows the frame runs,
  and the root hooks' collections the frame's own view resolves, reached
  through the late-bound `:live-frame/call-with-frame-resolution` seam — never
  an ambient lookup. A frame that does not exist has the empty policy."
  [frame-id]
  (if-not (and (some? frame-id) (some? (rf.frame/frame frame-id)))
    empty-policy
    (let [resolve (fn []
                    (let [view   (policy-view)
                          flows  (flow-outputs frame-id)
                          cached (get @policy-cache frame-id)]
                      (if (and cached
                               (identical? view (:view cached))
                               (= flows (:flows cached)))
                        (:policy cached)
                        (let [policy (build-policy (resolved-classifications)
                                                   flows
                                                   (collected-roots))]
                          (swap! policy-cache assoc frame-id
                                 {:view view :flows flows :policy policy})
                          policy))))]
      (if-let [with-resolution (rf.late-bind/get-fn :live-frame/call-with-frame-resolution)]
        (with-resolution frame-id resolve)
        (resolve)))))

(defn ^:no-doc with-policy
  "Classification value `reg` (nil to start from none) with `policy`'s app-db
  declarations unioned into its two axis slots — `:sensitive-declarations` and
  `:declarations`, each `{path #{owner}}` — the shape every egress walk reads.
  Pure: a read-time view."
  [reg policy]
  (let [{:keys [sensitive large]} (:app-db policy)]
    (cond-> reg
      (seq sensitive) (update :sensitive-declarations #(merge-with into % sensitive))
      (seq large)     (update :declarations #(merge-with into % large)))))

(defn ^:no-doc with-runtime-classification
  "Classification value `reg` with the subsystem claims each of `policies` makes on
  the instances `runtime-db` holds unioned in: each runtime-db root's hook
  applies that policy's declarations for the root to the value under it, and
  every absolute path it returns is claimed under the root's `:source`. A nil
  policy contributes nothing. Pure: a read-time view, never written back.
  Spec 015 §Subsystem projection-relative classification."
  [reg policies runtime-db]
  (reduce
    (fn [reg [root decls]]
      (let [[hook source] (get runtime-root-hooks root)
            apply-hook    (rf.late-bind/get-fn hook)
            root-value    (get runtime-db root)]
        (if (and apply-hook (some? root-value))
          (let [{:keys [sensitive large]} (apply-hook root-value decls)
                owner {:source source}
                claim (fn [reg slot paths]
                        (reduce #(update-in %1 [slot (vec %2)] (fnil conj #{}) owner)
                                reg paths))]
            (-> reg
                (claim :sensitive-declarations sensitive)
                (claim :declarations large)))
          reg)))
    reg
    (mapcat :roots policies)))

(defn ^:no-doc record-policy
  "`v`, a retained value, carrying `policy` as the classification it was
  recorded under. It rides in metadata, so it never egresses. Spec 015
  §Retained values keep their recorded classification."
  [v policy]
  (vary-meta v assoc ::recorded-policy policy))

(defn ^:no-doc recorded-policy
  "The policy `record-policy` attached to `v`, or nil."
  [v]
  (::recorded-policy (meta v)))

;; An event's flow pass publishes its memo before the event's epoch record is
;; assembled, so a flow output the pass vacates leaves the live policy while
;; the record's before snapshot still holds it. The policy that snapshot was
;; taken under is therefore retained beside it, and the record keeps both.

(defonce ^:private snapshot-policies
  ;; {frame-id [frame-state policy]}: the policy each frame's latest retained
  ;; snapshot was taken under.
  (atom {}))

(defn- union-policy
  "Every claim policy `a` or `b` makes."
  [a b]
  (if (or (nil? b) (identical? a b))
    a
    (let [axis #(merge-with into (get-in a [:app-db %]) (get-in b [:app-db %]))
          roots (merge-with merge (:roots a) (:roots b))]
      (cond-> {:app-db {:sensitive (or (axis :sensitive) {}) :large (or (axis :large) {})}}
        (seq roots) (assoc :roots roots)))))

(defn ^:no-doc retain-snapshot-policy!
  "Keep frame `frame-id`'s current policy as the one `frame-state`, the
  snapshot an in-flight event started from, was taken under. The first
  retention for a snapshot wins, because it precedes every change the event
  makes to the policy."
  [frame-id frame-state]
  (when (and (some? frame-state)
             (not (identical? frame-state (first (get @snapshot-policies frame-id)))))
    (let [policy (frame-policy frame-id)]
      (swap! snapshot-policies assoc frame-id [frame-state policy])))
  nil)

(defn ^:no-doc record-policy-for
  "The policy an epoch record of frame `frame-id` whose before snapshot is
  `frame-state-before` keeps: the frame's current policy, with the one that
  snapshot was retained under when it was. Spec 015 §Retained values keep their
  recorded classification."
  [frame-id frame-state-before]
  (let [[state retained] (get @snapshot-policies frame-id)]
    (cond-> (frame-policy frame-id)
      (and (some? state) (identical? state frame-state-before))
      (union-policy retained))))

(defn- registry-of
  "The frame's egress classification, read as a SNAPSHOT: its static policy's
  app-db declarations (`with-policy`), and the subsystem claims the policy
  makes on the instances its runtime-db holds. Its readers run inside the
  computations they describe —
  trace classification projects a `:rf.sub/run` inside the sub's compute and a
  `:rf.view/rendered` inside the view's render — and a capturing read there
  would make the whole runtime-db projection an input of that computation, so
  an unrelated runtime-db write would re-run it. The read therefore records no
  reactive dependency."
  [frame-id]
  (when-let [container (rf.frame/runtime-db-container frame-id)]
    (let [runtime-db (rf.substrate.adapter/read-container-untracked container)
          policy     (frame-policy frame-id)]
      (-> (with-policy nil policy)
          (with-runtime-classification [policy] runtime-db)))))

(def ^:private source-provenance-priority
  "Deterministic priority order for deriving a large marker's STABLE `:reason`
  provenance from a path's owner-set. A large path may be claimed by several
  independent surfaces; the marker carries ONE reproducible source so the wire
  shape is stable across runs and serialization. First match wins."
  [:classification :flow :machine :route :resource :mutation])

(defn ^:no-doc owners->provenance
  "Derive the STABLE `:reason` / `:source` provenance keyword for a large-
  classified path from its `owners` set. Picks the highest-priority `:source`
  present (`source-provenance-priority`); for a set carrying only unrecognised
  sources it picks the lexicographically-first source (still deterministic);
  falls back to `:classification` for an empty set (defensive — matches the
  `->marker` default)."
  [owners]
  (let [sources (into #{} (keep :source) owners)]
    (or (some sources source-provenance-priority)
        (first (sort sources))
        :classification)))

(defn declarations
  "Return the `:large` `:app-db` declarations for `frame-id`: the frame's
  `:classification` registrations under `:source :classification`, the outputs
  of the flows it runs under `:source :flow`, and the subsystem claims its
  policy makes on the instances its runtime-db holds.
  Returns a snapshot, not a reactive subscription: reading it inside a
  reactive computation does not make that computation depend on it.
  EP-0002 — the zero-arity ambient form resolves the frame through the
  carried-invariant scope chain (`rf.frame/require-current-frame!`); under no
  established scope it raises `:rf.error/no-frame-context` rather than
  reading a synthesised `:rf/default` classification."
  ([] (declarations (rf.frame/require-current-frame!
                      :elision-declarations
                      {:where 're-frame.elision/declarations})))
  ([frame-id]
   (or (get (registry-of frame-id) :declarations) {})))

(defn sensitive-declarations
  "Return the `:sensitive` `:app-db` declarations for `frame-id`: the frame's
  `:classification` registrations under `:source :classification`, the outputs
  of the flows it runs under `:source :flow`, and the subsystem claims its
  policy makes on the instances its runtime-db holds.
  Returns a snapshot, not a reactive subscription: reading it inside a
  reactive computation does not make that computation depend on it.
  EP-0002 — the zero-arity ambient form resolves the frame through the
  carried-invariant scope chain (`rf.frame/require-current-frame!`); under no
  established scope it raises `:rf.error/no-frame-context` rather than
  reading a synthesised `:rf/default` classification."
  ([] (sensitive-declarations (rf.frame/require-current-frame!
                                :elision-sensitive-declarations
                                {:where 're-frame.elision/sensitive-declarations})))
  ([frame-id]
   (or (get (registry-of frame-id) :sensitive-declarations) {})))

;; The policy is read the other way by schemas: an app-db schema's
;; validation-failure trace (`re-frame.schemas.validate/validate-app-schema!`)
;; is redacted by the frame's classification of the failing path, so an app-db
;; path is declared once (Spec 010 §`:sensitive?`). A transient value's schema
;; (event, coeffect, fx, sub) and a machine `[:schemas :data]` schema redact
;; their own validation failures from their per-slot props. Durable machine
;; `:data` is classified by top-level `:sensitive` / `:large` on the
;; `reg-machine` spec, applied at egress to each actor snapshot under
;; `:source :machine` (Spec 015 §Subsystem projection-relative
;; classification).

(defn clear-warning-cache!
  []
  (reset! warned-unschema'd #{})
  nil)

(defn ^:no-doc pr-str-bytes
  "UTF-8 BYTE count of `v`'s printed representation, on BOTH hosts. Used by the
  `:rf.size/large-elided` marker payload. `^:no-doc` public so
  `re-frame.classification` reuses it rather than re-inlining the same `pr-str`
  byte-count a second time.

  The figure is PUBLISHED — the marker's `:bytes` slot, which
  Spec-Schemas §`:rf/elision-marker` types as the `pr-str` BYTE count and
  009 §Size elision makes a MUST (\"lets an agent decide fetch-anyway vs
  skip\") — and it is READ as a threshold, against
  `:rf.egress/threshold-bytes`, to decide whether a string leaf at an undeclared
  path fires `:rf.warning/large-value-unschema'd`.

  Both arms count UTF-8 bytes. The obvious `:cljs` expression,
  `(count (pr-str v))`, would count UTF-16 CODE UNITS, since `count` on a
  CLJS string is `.-length`, so one figure would have two rulers: the same
  app-db leaf could warn on the JVM and pass silently in the browser, and
  every marker CLJS published would under-report `:bytes` by up to 3x (4x on
  astral). Code units agree with
  UTF-8 bytes only for ASCII, which is what makes that mistake fail OPEN — on an
  ASCII payload the wrong expression prints the right number and a green suite
  never notices, until a value grows an em-dash, an ellipsis or an emoji. An
  ASCII-only test cannot see it, hence the non-ASCII + ASTRAL fixtures in
  `elision_pr_str_bytes_cljs_test.cljc`.

  Bytes are the TIGHTER ruler: UTF-8 bytes are never fewer than UTF-16
  code units. Nothing is dropped
  or refused either way — per Privacy.md the threshold is **advisory, not a
  cap**, and 009 records the recovery as `:warned-and-replaced`: the
  over-threshold unschema'd value ships UNCHANGED. Only declared / schema-marked
  paths elide, and that decision never consults this number.

  `TextEncoder` and not `Buffer.byteLength`: this ns is `re-frame.core`
  framework code and compiles into the BROWSER bundle (and under `:advanced`),
  where `Buffer` is not there. `TextEncoder` is UTF-8 BY DEFINITION — no
  encoding argument a later edit can silently drop — and is present in every
  browser and in Node. The `^js` hints keep `:advanced` from renaming the
  interop call. Same helper shape as `re-frame.ssr.hash`, the fresco lane's
  `utf8-bytes` and xray's `format/pr-str-bytes`."
  [v]
  (let [s (pr-str v)]
    #?(:clj  (alength (.getBytes ^String s "UTF-8"))
       :cljs (let [^js enc (js/TextEncoder.)
                   ^js buf (.encode enc s)]
               (.-length buf)))))

(defn ^:no-doc value-type
  "Coarse value-type tag for the `:rf.size/large-elided` marker payload.
  `^:no-doc` public so `re-frame.classification` reuses it rather than
  re-inlining the same closed `cond` a second time."
  [v]
  (cond
    (map? v)    :map
    (vector? v) :vector
    (set? v)    :set
    (string? v) :string
    :else       :scalar))

(defn- sha256-hex
  "The `sha256:<hex>` content digest of `v`'s printed form, for the marker's
  optional `:digest` slot. JVM only: the browser build returns nil (no
  synchronous digest without pulling a crypto library into core), and
  `->marker` then OMITS the slot rather than shipping `:digest nil`, which
  the normative marker schema rejects."
  [v]
  #?(:clj
     (let [bytes (.getBytes ^String (pr-str v) "UTF-8")
           md    (doto (java.security.MessageDigest/getInstance "SHA-256")
                   (.update bytes))]
       (str "sha256:"
            (format "%064x" (java.math.BigInteger. 1 (.digest md)))))
     :cljs
     nil))

;; ---- a seq the app has not forced ------------------------------------------
;;
;; Projecting a value must not change it, and realising a lazy seq is a
;; change: it runs the app's code, and an endless one never returns. So no
;; walk here realises a seq, and neither does measuring a large marker
;; (Spec 015 §`elide-wire-value`).

(defn- uncomputed-cell?
  "True when seq cell `s` holds no element yet: a lazy cell whose body has not
  run, or a range or repeat, which compute each element as it is read."
  [s]
  #?(:clj  (or (and (instance? clojure.lang.IPending s) (not (realized? s)))
               (instance? clojure.lang.Range s)
               (instance? clojure.lang.LongRange s)
               (instance? clojure.lang.Repeat s))
     :cljs (or (and (satisfies? IPending s) (not (realized? s)))
               (instance? Range s)
               (instance? IntegerRange s))))

(defn- unforced-seq?
  "True when walking seq `s` would compute an element the app has not. Each
  cell is reached by `rest`, which on a computed cell hands back the next cell
  without running it, so the check itself forces nothing."
  [s]
  (loop [s s]
    (cond
      (uncomputed-cell? s) true
      (nil? (seq s))       false
      :else                (recur (rest s)))))

(defn- holds-unforced-seq?
  "True when printing `v` would realise a seq the app has not forced: `v` is
  one, or holds one at any depth. Visits only computed structure."
  [v]
  (cond
    (seq? v)  (or (unforced-seq? v) (boolean (some holds-unforced-seq? v)))
    (map? v)  (boolean (some (fn [[k vv]] (or (holds-unforced-seq? k) (holds-unforced-seq? vv))) v))
    (coll? v) (boolean (some holds-unforced-seq? v))
    :else     false))

(defn ^:no-doc ->marker
  "Build the `:rf.size/large-elided` marker map for value `v` at `path`.
  `^:no-doc` public so `re-frame.classification/large-marker` builds the marker
  here (with `{:reason :classification}`) rather than re-inlining the shape a
  second time.

  The `:handle` is always the two-element `[:rf.elision/at <path>]`, a
  live-path locator — the only shape Spec-Schemas `:rf/elision-marker` types
  (there is no `:as-of-epoch` variant; the schema rejects one).

  A value holding a seq the app has not forced projects as `:rf/redacted`
  instead, because its required `:bytes` cannot be measured without realising
  the seq; the sentinel itself passes through, so projecting twice equals
  projecting once (Spec 015 §`elide-wire-value`)."
  [v path {:keys [hint include-digests? reason]}]
  ;; `keyword?` first: `=` against a lazy seq realises its head.
  (if (or (and (keyword? v) (= rf.privacy/redacted-sentinel v))
          (holds-unforced-seq? v))
    rf.privacy/redacted-sentinel
    (let [;; `:digest` is associated only when a digest string
          ;; was actually computed; the browser build computes none, and an
          ;; `:digest nil` both fails the schema and reads as a false "unchanged".
          digest (when include-digests? (sha256-hex v))
          body (cond-> {:path   (vec path)
                        :bytes  (pr-str-bytes v)
                        :type   (value-type v)
                        ;; The declaring surface, defaulted to a
                        ;; `reg-classification` when the caller names none.
                        :reason (or reason :classification)
                        :hint   hint
                        :handle [:rf.elision/at (vec path)]}
                 (string? digest) (assoc :digest digest))]
      {:rf.size/large-elided body})))

(defn- marker-opts
  "The `->marker` option map for a declared-`:large` node — derived from the
  matched path's retained `large-owners` set and the walk `ctx` (its
  `:include-digests?`). The 3-key option map the path-based
  wire walker (`walk-decider`) passes to `->marker`. A large path may carry
  several independent owners; the marker's `:reason` picks the
  highest-priority source deterministically (`owners->provenance`) and its
  `:hint` rides from that SAME owner (when the declaration carried one), so the
  wire shape is reproducible."
  [large-owners ctx]
  (let [source (owners->provenance large-owners)
        hint   (some (fn [o] (when (= source (:source o)) (:hint o))) large-owners)]
    {:hint             hint
     :reason           source
     :include-digests? (:rf.egress/include-digests? ctx)}))

(defn- warn-large-unschema'd!
  [frame-id path bytes]
  (when rf.interop/debug-enabled?
    (let [k [frame-id (vec path)]]
      (when-not (contains? @warned-unschema'd k)
        (swap! warned-unschema'd conj k)
        (rf.trace/emit! :warning :rf.warning/large-value-unschema'd
                     {:frame    frame-id
                      :path     (vec path)
                      :bytes    bytes
                      :hint     "Classify this path large with `(rf/reg-classification <feature-id> {:large [[...]]})` beside the feature that writes it."
                      :recovery :no-recovery})))))

(defn- may-print-over?
  "Whether string `s` can print to more than `threshold` UTF-8 bytes. Each
  UTF-16 unit prints to at most 3 bytes — an escape is 2 ASCII bytes, a BMP
  character at most 3, a surrogate pair 4 for its 2 units — plus 2 quotes, so
  a string under that bound is settled without printing it. `^long` keeps the
  JVM comparison primitive: this runs once per string the walk visits."
  [s ^long threshold]
  (> (+ 2 (* 3 (count s))) threshold))

(defn- check-large-unschema'd!
  "The `:rf.warning/large-value-unschema'd` advisory for one undeclared string
  `s` at `path`: measure it only when it can exceed `threshold` and has not
  already warned at this `(frame, path)`, and warn when it does exceed it.
  Dev-only, and a threshold of 0 disables it — no `pr-str-bytes`, no warning
  (API.md §Configure keys, `:elision`)."
  [frame-id path s threshold]
  (when (and rf.interop/debug-enabled?
             (pos? threshold)
             (may-print-over? s threshold)
             (not (contains? @warned-unschema'd [frame-id path])))
    (let [bytes (pr-str-bytes s)]
      (when (> bytes threshold)
        (warn-large-unschema'd! frame-id path bytes)))))

(defn- queue?
  "True for a persistent queue, which every walk here descends as a positional
  container. On the JVM it is neither `vector?` nor `seq?`; `sequential?` would
  admit it but also an `Eduction`, whose traversal runs its transducer."
  [v]
  #?(:clj  (instance? clojure.lang.PersistentQueue v)
     :cljs (instance? PersistentQueue v)))

(defn- oversized-strings
  "`[relative-path string]` for every string under `v` that `may-print-over?`
  `threshold`, or nil. Visits what the wire walk visits — map values by key,
  vector, queue and seq elements by index, set elements at the set's own path —
  but rebuilds nothing, builds a relative path only for a hit, and skips a seq
  the app has not forced."
  [v threshold]
  (cond
    (string? v)
    (when (may-print-over? v threshold) (list [() v]))

    (or (map? v) (vector? v))
    (reduce-kv (fn [acc k vv]
                 (if-some [hits (oversized-strings vv threshold)]
                   (into acc (map (fn [[rel s]] [(cons k rel) s])) hits)
                   acc))
               nil v)

    (or (queue? v) (and (seq? v) (not (unforced-seq? v))))
    (loop [i 0 xs (seq v) acc nil]
      (if xs
        (recur (inc i) (next xs)
               (if-some [hits (oversized-strings (first xs) threshold)]
                 (into acc (map (fn [[rel s]] [(cons i rel) s])) hits)
                 acc))
        acc))

    (set? v)
    (reduce (fn [acc vv] (into acc (oversized-strings vv threshold))) nil v)))

(defn- probe-large-unschema'd!
  "The advisory over a subtree the wire walk returns unchanged: each string the
  walk would have visited there gets `check-large-unschema'd!` at its own
  path, so returning the subtree whole narrows nothing the advisory sees
  (Spec 009 §Size elision in traces)."
  [frame-id threshold path v]
  (when (and rf.interop/debug-enabled? (pos? threshold))
    (doseq [[rel s] (oversized-strings v threshold)]
      (check-large-unschema'd! frame-id (into path rel) s threshold))))

;; ---- collection-coordinate declaration matching --------------------------
;;
;; Schema-derived declarations are INDEX-FREE: the schema walker descends
;; positional/keyed containers (`:vector` / `:sequential` / `:set` /
;; `:map-of`) at the SAME base-path (walker.cljc ~L145), because a vector
;; index or a `:map-of` key is not a declarable app-db slot. So a schema
;; `[:items] [:vector [:map [:token {:sensitive? true} :string]]]` declares
;; the index-free path `[:items :token]`, while the runtime value lives at
;; the INDEXED path `[:items 0 :token]` (and a `:map-of` value lives at the
;; KEYED path `[:by-id "a" :secret]` against decl `[:by-id :secret]`).
;;
;; The wire-elision walker walks a RUNTIME value, so it sees the indexed /
;; keyed paths. To match the index-free declarations without re-walking the
;; schema (the walker doesn't carry it), we thread a SET of candidate
;; declaration-coordinate paths alongside the concrete runtime path:
;;
;;   - Descending a VECTOR / QUEUE / SEQ element at index `i`: the index is
;;     UNAMBIGUOUSLY a collection coordinate (never a named slot), so each
;;     candidate forks into the INDEX-FREE interpretation `c` (unchanged —
;;     matches schema decls that descend positional containers at the same
;;     base-path) AND the LITERAL-INDEX interpretation `(conj c i)` (matches
;;     a declaration that itself pins a concrete index, e.g. `[:tokens 0]`),
;;     the latter pruned by `decl-prefixes` (see `fork-index-paths`).
;;     Riding an index never floats a declaration past a NAMED slot, so the
;;     index-free interpretation is kept for ALL candidates (incl. the empty
;;     seed) without the map-key skip's position guard. A SET element has no
;;     stable index ⇒ index-free pass-through only.
;;   - Descending a MAP key `k`: a runtime map is structurally ambiguous —
;;     `k` may be a NAMED `:map` slot (a real segment ⇒ `(conj c k)`) OR a
;;     `:map-of` key (a collection coordinate ⇒ `c` unchanged). We can't tell
;;     which from the value alone, so we fork each candidate into BOTH
;;     interpretations and let the declaration table disambiguate at the leaf
;;     (only the interpretation that actually matches a declared path fires).
;;     The `:map-of`-key (skip) fork is POSITION-PRECISE: only a NON-EMPTY
;;     candidate — one that has already consumed ≥1 declared segment — may
;;     skip a key. The empty seed `[]` advances only via the named fork (see
;;     `fork-decl-paths`), so a declaration cannot FLOAT past leading named
;;     map slots and falsely match the same key-sequence at a deeper,
;;     non-declared position.
;;
;; The fork would grow combinatorially with map depth, so we PRUNE: a
;; candidate that is not a prefix of ANY declared path can never match and is
;; dropped. `decl-prefixes` (all prefixes of every sensitive ∪ large decl
;; path) bounds the live candidate set by that PREFIX vocabulary, not by the
;; declaration count — an index-free declaration can keep several of its own
;; prefixes alive at once — tiny in practice, and empty (so zero overhead)
;; when nothing is declared.
;;
;; Precision: the runtime path `[:by-id "a" :secret]` matches decl
;; `[:by-id :secret]` only because `:secret` is a real map slot under the
;; `:map-of` value (`[:by-id]` is a non-empty partial match, so it legally
;; skips the map-of key `"a"`); a sibling NON-sensitive leaf
;; `[:by-id "a" :other]` never matches (its decl-coordinate candidates
;; `[:by-id :other]` / `[:by-id "a" :other]` aren't declared). And decl
;; `[:auth :password]` does NOT match the deeper `[:tags :some-other-slot
;; :auth :password]`: the `[]` seed cannot skip the leading `:tags` /
;; `:some-other-slot` named slots, so it never reaches the same-named `:auth`
;; at that non-declared position. The concrete runtime `path` still drives
;; marker `:path` / `:handle` and the unschema'd-large warning, so an agent's
;; follow-up `get-path` lands on the exact indexed location.

(declare walk marker?)

;; ---- shared map/vec/set/seq path-walk skeleton ---------------------------
;;
;; Both the schema-first wire walker (`walk` / `walk-decider`) and the
;; ad-hoc-paths classification walker (`re-frame.classification/walk-with-paths`)
;; descend the SAME node domain — map → reduce-kv, vector|queue|seq → positional
;; reduce with an integer index, set → element-wise pass-through — and fork
;; their candidate sets through the SAME `fork-decl-paths` / `fork-index-paths`
;; (classification choosing the fork policy), so they differ ONLY in the
;; per-node DECIDER. `walk-tree` is that divergence-free kernel, living here
;; (classification requires this ns anyway, so this adds no coupling): one skeleton,
;; the two distinct deciders stay at their call sites.
;;
;; The skeleton is parameterized by an opaque per-walker `state` (the wire
;; walker threads `[path decl-paths]`, classification `[path candidates]`) and
;; four functions:
;;   :decide  (state v) -> a replacement value, OR the `::recur` sentinel to
;;            descend structurally. A matched node is substituted wholesale and
;;            NOT descended (sensitive → sentinel; large → marker).
;;   :map-key (state k)  -> the descended state for map key `k`.
;;   :index   (state i)  -> the descended state for positional index `i`.
;;   :leaf    (state v)  -> the `:else` (scalar) result. The wire walker runs
;;            its runtime-size-threshold warning side-effect here and returns
;;            `v`; classification returns `v` verbatim.
;; and one optional function:
;;   :unforced (state s) -> what a seq the app has not forced projects as, in
;;            place of the walk that would realise it. Absent, `s` is returned
;;            as found.
;; Set elements carry `state` UNCHANGED in BOTH walkers (a set element is a
;; nameless collection coordinate — neither the path nor the decl-paths
;; advance), so that arm is fixed in the skeleton, not parameterized.
;;
;; A forced seq is normalised to a persistent vector (via the transient
;; accumulator) for both walkers; a queue keeps its type.

(def ^:no-doc walk-recur
  "Distinguished `walk-tree` `:decide` sentinel meaning 'descend structurally'
  (vs return a replacement). `^:no-doc` public so the sibling
  `re-frame.classification` decider returns the SAME sentinel `walk-tree` tests
  with `identical?` — a replacement value can never collide with this namespaced
  keyword."
  ::recur)

(defn ^:no-doc walk-tree
  "The shared map/vec/set/seq path-walk skeleton (see the section comment).
  `state` is the per-walker traversal state; `decider` is the
  `{:decide :map-key :index :leaf}` map, plus an optional `:unforced`.
  `^:no-doc` public so the sibling `re-frame.classification` walker shares
  this one skeleton rather than re-inlining it."
  [v state {:keys [decide map-key index leaf unforced] :as decider}]
  (let [r (decide state v)]
    (if-not (identical? r walk-recur)
      r
      (cond
        ;; A record rebuilds as a plain map: `(empty <record>)` THROWS on the
        ;; JVM, which would make every walk over a value holding one throw —
        ;; out of the event pipeline's db projection and the error path
        ;; included. CLJS `empty` is nil for a record, so that host builds a
        ;; plain map too.
        (map? v)
        (reduce-kv (fn [acc k vv]
                     (assoc acc k (walk-tree vv (map-key state k) decider)))
                   (if (record? v) {} (empty v)) v)

        ;; A queue rebuilds as a queue. Ahead of the seq arms, because a CLJS
        ;; queue is also `seq?` and would otherwise rebuild as a vector.
        (queue? v)
        (into (empty v) (map-indexed #(walk-tree %2 (index state %1) decider)) v)

        (and (seq? v) (unforced-seq? v))
        (if unforced (unforced state v) v)

        (or (vector? v) (seq? v))
        (let [idx (volatile! -1)]
          (persistent!
            (reduce (fn [acc vv]
                      (conj! acc (walk-tree vv (index state (vswap! idx inc)) decider)))
                    (transient [])
                    v)))

        (set? v)
        (into #{} (map #(walk-tree % state decider)) v)

        :else
        (leaf state v)))))

(defn- prefixes
  "All non-empty prefixes (including the full path) of `path`, as a set.
  Used to seed `decl-prefixes` so the candidate decl-path fork can be
  pruned to paths that could still reach a declaration."
  [path]
  (into #{} (map #(subvec path 0 %)) (range 1 (inc (count path)))))

(defn ^:no-doc decl-prefix-set
  "Build the set of every prefix of every path in `decl-paths` (the declared
  sensitive ∪ large paths). A candidate declaration-coordinate path is kept
  alive during the walk only while it is a member of this set — anything
  outside it can never reach a declaration, so dropping it is matching-safe
  and keeps the forked candidate set bounded by this prefix vocabulary.
  `^:no-doc` public so `re-frame.classification`'s walker prunes against the
  same vocabulary."
  [decl-paths]
  (into #{} (mapcat prefixes) decl-paths))

(defn ^:no-doc fork-decl-paths
  "Descend the candidate declaration-coordinate set through a MAP key `k`.
  Each candidate forks into the NAMED-slot interpretation `(conj c k)` and
  the `:map-of`-key interpretation `c` (key is a collection coordinate, no
  segment). Both are retained only when they remain a prefix of some
  declared path (`decl-prefixes`), bounding the set.

  POSITION-PRECISE skip: the `:map-of`-key
  interpretation — keeping `c` unchanged so the key is treated as a
  collection coordinate — is only legitimate for a candidate that has
  ALREADY consumed at least one declared segment (`(seq c)`). A non-empty
  `c` is a partial declaration-prefix match in progress, so a map key at
  that position can plausibly be a `:map-of` value key inside that
  declared subtree (e.g. `[:by-id]` skips the map-of key `\"a\"` so the
  subsequent `:secret` forms `[:by-id :secret]`). The EMPTY seed `[]` has
  matched nothing yet: letting it skip a key would let a declaration
  FLOAT past arbitrary leading NAMED map slots — matching decl
  `[:auth :password]` against the deeper `[:tags :some-other-slot :auth
  :password]` where that `:auth`/`:password` is a DIFFERENT position, not
  the declared one. So `[]` advances only via the named-slot fork; it
  never survives as a free-floating skip. This keeps the headline goal
  green (`[:items 0 :token]` matches `[:items :token]`; `[:by-id \"a\"
  :secret]` matches `[:by-id :secret]`) while sealing the over-redaction
  of same-named slots at non-declared positions.

  `index-free?` (default true) is the fork policy: false drops the
  `:map-of`-key interpretation, leaving the exact path membership test
  `re-frame.classification/redact-with-paths` defaults to."
  ([decl-paths k decl-prefixes] (fork-decl-paths decl-paths k decl-prefixes true))
  ([decl-paths k decl-prefixes index-free?]
   (persistent!
     (reduce (fn [acc c]
               (let [named (conj c k)
                     acc   (if (contains? decl-prefixes named) (conj! acc named) acc)]
                 ;; `:map-of`-key interpretation: `c` stays a live candidate
                 ;; ONLY when it is a non-empty partial match — see the
                 ;; position-precise rationale in the docstring. `c` is
                 ;; already a known decl-prefix (it survived the round that
                 ;; produced it), so retaining it keeps the set bounded by
                 ;; `decl-prefixes`.
                 (if (and index-free? (seq c)) (conj! acc c) acc)))
             (transient #{})
             decl-paths))))

(defn ^:no-doc fork-index-paths
  "Descend the candidate declaration-coordinate set through a VECTOR / SEQ
  element at integer index `i`.

  An element index is UNAMBIGUOUSLY a collection coordinate — unlike a map
  key, it can never be a named app-db slot. So a vector/seq descent forks
  each candidate into:

    - the INDEX-FREE interpretation `c` (unchanged): a schema-derived decl
      descends positional containers at the same base-path, so the
      index-free `[:items :token]` matches the runtime `[:items 0 :token]`.
      Retained for ALL candidates (including the empty seed `[]`): riding
      an index through never floats a declaration past a NAMED slot, so it
      cannot over-redact the way the map-key skip could.

    - the LITERAL-INDEX interpretation `(conj c i)`: a declaration MAY
      itself carry a concrete index (`[:tokens 0]`, declared directly
      against the indexed runtime position rather than schema-derived).
      Retained only while it remains a prefix of some declared path
      (`decl-prefixes`), so it is dropped immediately when no decl pins
      that index — keeping the set bounded.

  Both interpretations are matching-safe: the index-free one matches
  schema decls, the literal-index one matches directly-declared indexed
  paths, and only the interpretation actually present in the declaration
  table fires at the leaf.

  `index-free?` (default true) is the fork policy: false drops the
  index-free interpretation, leaving the exact path membership test."
  ([decl-paths i decl-prefixes] (fork-index-paths decl-paths i decl-prefixes true))
  ([decl-paths i decl-prefixes index-free?]
   (persistent!
     (reduce (fn [acc c]
               (let [indexed (conj c i)
                     acc     (if (contains? decl-prefixes indexed)
                               (conj! acc indexed) acc)]
                 ;; Index-free interpretation: pass `c` through unchanged.
                 (if index-free? (conj! acc c) acc)))
             (transient #{})
             decl-paths))))

(defn- decl-match
  "Test the candidate declaration-coordinate set against a declaration
  table `tbl` (`:sensitive` or `:large`). Returns the matched declaration
  value (truthy) for the FIRST candidate present in `tbl`, or nil. For the
  sensitive table any present entry suffices (membership); for the large
  table the entry is the marker-hint declaration map."
  [decl-paths tbl]
  (some #(get tbl %) decl-paths))

(defn- decl-sensitive?
  [decl-paths sensitive-tbl]
  (boolean (some #(contains? sensitive-tbl %) decl-paths)))

(defn- strict-prefix?
  "True when declaration-coordinate path `a` is a STRICT prefix of `b` — `b`
  is `a` extended by at least one further segment. Both are full declaration
  coordinates in the SAME space (`:large` and `:sensitive` decls share it),
  so a strict-prefix test answers \"is `b` a descendant slot under `a`?\"."
  [a b]
  (let [na (count a)]
    (and (> (count b) na)
         (= a (subvec b 0 na)))))

(defn ^:no-doc large-paths-shadowing-sensitive
  "The subset of `large-paths` that have a path of `sensitive-paths` strictly
  BELOW them — a `:large`-marked subtree that contains a `:sensitive`
  descendant. Such a node still elides behind the ordinary
  `:rf.size/large-elided` marker, but the marker is measured over the subtree
  AFTER its sensitive descendants redact, so its `:bytes` and `:digest` never
  vary with a secret's content or length (Spec 015 §No propagation, 009 §Wire
  marker). The walker uses this set to tell such a COLLISION node from an
  ordinary large one. Pure function of the two declared path sets; computed
  once per walk. `^:no-doc` public so `re-frame.classification`'s walker tells
  the same collision nodes apart."
  [large-paths sensitive-paths]
  (into #{}
        (filter (fn [lp] (some #(strict-prefix? lp %) sensitive-paths)))
        large-paths))

(defn- collision-measure-ctx
  "The walk context that measures a collision node: large elision off,
  sensitive redaction as in `ctx`, and no unschema'd-large warning for leaves
  the marker hides."
  [ctx]
  (assoc ctx :rf.egress/include-large? true :threshold-bytes 0))

;; The wire walker's traversal state is the pair `[path decl-paths]` — the
;; concrete runtime path (drives marker `:path` / `:handle` + the unschema'd-
;; large warning) and the forked candidate declaration-coordinate set (drives
;; the per-node sensitive / large match). Both fork on descent: a map key
;; advances the path by the key and forks decl-paths via `fork-decl-paths`; a
;; positional index advances the path by the index and forks via
;; `fork-index-paths`. Set elements carry the state unchanged (handled by the
;; shared `walk-tree` skeleton).

(defn- walk-decider
  "Build the `walk-tree` decider for the schema-first wire walk against `ctx`.
  The per-node decision (no declared path reachable → the node itself,
  unchanged; sensitive → sentinel; declared-large → idempotent marker; else
  descend), the map-key / index state forks, what an unforced seq a
  declaration can reach projects as (`:rf/redacted`), and the scalar leaf (the
  runtime-size-threshold warning side-effect) — the elision-specific arms the
  shared skeleton parameterises over.

  Also carries `:collision-owners`, which `walk-tree` ignores: given a candidate
  set, the large owners of a COLLISION node (a large node holding a sensitive
  descendant, measured after redaction), else nil. The offset replay in
  `elide-against-registry` reads it."
  [ctx]
  (let [large-tbl     (:large ctx)
        sensitive-tbl (:sensitive ctx)
        shadow-set    (:large-shadows-s ctx)
        decl-prefixes (:decl-prefixes ctx)
        include-lg?   (:rf.egress/include-large? ctx)
        include-s?    (:rf.egress/include-sensitive? ctx)
        threshold     (:threshold-bytes ctx)
        frame-id      (:frame-id ctx)
        ;; A collision node's matched large coordinate holds a `:sensitive`
        ;; descendant. Only while sensitive redaction is in force: a caller
        ;; that opted out of it is measured over the raw subtree.
        collision?    (fn [decl-paths]
                        (and (not include-s?)
                             (seq shadow-set)
                             (some #(contains? shadow-set %) decl-paths)))
        ;; Whether this boundary substitutes anything at all.
        substitutes?  (boolean (or (and (not include-s?) (seq sensitive-tbl))
                                   (and (not include-lg?) (seq large-tbl))))
        measure       (delay (walk-decider (collision-measure-ctx ctx)))]
    {:decide
     (fn [[path decl-paths :as state] v]
       (let [large-owners (decl-match decl-paths large-tbl)
             sensitive? (decl-sensitive? decl-paths sensitive-tbl)]
         (cond
           ;; No declared path can match at or below this node — neither fork
           ;; revives an empty candidate set — so the subtree ships as it
           ;; stands, reference and type intact, rather than being rebuilt.
           ;; Only the dev size probe visits it.
           (empty? decl-paths)
           (do (probe-large-unschema'd! frame-id threshold path v)
               v)

           (and sensitive? (not include-s?))
           rf.privacy/redacted-sentinel

           (and large-owners (not include-lg?))
           (cond
             ;; Idempotence under double-projection: a value at a `:large?`-
             ;; declared path that already carries the `:rf.size/large-elided`
             ;; marker shape is passed through unchanged. A forwarder pipeline
             ;; that accidentally double-projects (middleware composition,
             ;; tool-then-watcher fan-out) MUST NOT re-mark the marker — the
             ;; second pass's `:bytes` would otherwise reflect the printed
             ;; length of the prior marker, not the original payload. Mirrors
             ;; the sensitive-case idempotence (the `:rf/redacted` scalar
             ;; sentinel is non-matchable so the walker descends into nothing
             ;; on a re-projection pass).
             (marker? v)
             v

             ;; A collision node elides behind the ORDINARY marker, measured
             ;; over the subtree after its sensitive descendants redact (Spec
             ;; 015 §No propagation, 009 §Wire marker): `:bytes` and `:digest`
             ;; never vary with a secret's content or length.
             (collision? decl-paths)
             (->marker (walk-tree v state @measure) path
                       (marker-opts large-owners ctx))

             :else
             (->marker v path (marker-opts large-owners ctx)))

           :else
           walk-recur)))

     :collision-owners
     (fn [decl-paths]
       (when (and (not include-lg?)
                  (collision? decl-paths)
                  (not (decl-sensitive? decl-paths sensitive-tbl)))
         (decl-match decl-paths large-tbl)))

     :map-key
     (fn [[path decl-paths] k]
       [(conj path k) (fork-decl-paths decl-paths k decl-prefixes)])

     :index
     (fn [[path decl-paths] i]
       [(conj path i) (fork-index-paths decl-paths i decl-prefixes)])

     ;; Reached only past `:decide`, so a declared path can reach inside the
     ;; seq, and nothing inside can be inspected without realising it: fail
     ;; closed, unless this boundary substitutes nothing anyway.
     :unforced
     (fn [_state s]
       (if substitutes? rf.privacy/redacted-sentinel s))

     :leaf
     (fn [[path decl-paths] v]
       (when (and (string? v)
                  (not (decl-match decl-paths large-tbl))
                  (not (decl-sensitive? decl-paths sensitive-tbl)))
         (check-large-unschema'd! frame-id path v threshold))
       v)}))

(defn- walk
  [v path decl-paths ctx]
  (walk-tree v [(vec path) decl-paths] (walk-decider ctx)))

(defn ^:no-doc declared-values
  "The nodes of `v`, an app-db value, that each path in `decl-paths` reaches
  under this walker's own collection matching (`fork-decl-paths`,
  `fork-index-paths`) — so `[:items :token]` reaches the `:token` of every
  `:items` element — as `{decl-path {concrete-path value}}`. A declaration that
  reaches nothing is absent, and an unforced seq is not looked inside. Reads
  only; nothing is substituted. The epoch record's sensitivity rollups read
  their leaves through it, so they see exactly the nodes the egress walk
  redacts."
  [v decl-paths]
  (let [decl-set (into #{} (map vec) decl-paths)
        decl-prefixes (decl-prefix-set decl-set)
        found    (volatile! {})]
    (when (seq decl-set)
      (walk-tree v [[] #{[]}]
                 {:decide   (fn [[path candidates] node]
                              (if (empty? candidates)
                                node
                                (do (doseq [candidate candidates
                                            :when (contains? decl-set candidate)]
                                      (vswap! found assoc-in [candidate path] node))
                                    walk-recur)))
                  :map-key  (fn [[path candidates] k]
                              [(conj path k) (fork-decl-paths candidates k decl-prefixes)])
                  :index    (fn [[path candidates] i]
                              [(conj path i) (fork-index-paths candidates i decl-prefixes)])
                  :unforced (fn [_state s] s)
                  :leaf     (fn [_state node] node)}))
    @found))

(defn- elide-against-registry
  "Inner walk for `elide-wire-value` against a KNOWN carried frame, over the
  classification value `reg` (normally `frame-id`'s own, via
  `elide-against-frame`). `frame-id` still names the frame for the large-value
  warnings. Pure walk — no frame resolution happens here (the caller has
  already resolved + validated the stamp)."
  [v opts frame-id reg]
  (let [;; Precedence (API.md L507): explicit opt > configured > default.
        threshold (let [opt (:rf.egress/threshold-bytes opts)]
                    (if (some? opt) opt (configured-threshold-bytes)))
        large     (or (:declarations reg) {})
        sensitive (or (:sensitive-declarations reg) {})
        ctx       {:frame-id           frame-id
                   :large              large
                   :sensitive          sensitive
                   ;; The subset of `:large` keys that shadow a `:sensitive`
                   ;; descendant — the collision nodes whose marker is measured
                   ;; after redaction. Empty in the common case (no nested
                   ;; classification).
                   :large-shadows-s    (large-paths-shadowing-sensitive (keys large) (keys sensitive))
                   ;; Prefix set of every declared path — bounds the forked
                   ;; candidate decl-path set as the walker descends maps.
                   ;; Empty when nothing is declared ⇒ every candidate set
                   ;; below the root is empty, and the walker returns each
                   ;; top-level value unchanged.
                   :decl-prefixes      (decl-prefix-set (concat (keys sensitive) (keys large)))
                   ;; One vocabulary all the way down: the walk
                   ;; context carries the SAME `:rf.egress/*` spelling the opts
                   ;; map does, so no reader has to learn a second one.
                   :rf.egress/include-large?     (true? (:rf.egress/include-large? opts))
                   :rf.egress/include-sensitive? (true? (:rf.egress/include-sensitive? opts))
                   :rf.egress/include-digests?   (true? (:rf.egress/include-digests? opts))
                   :threshold-bytes    threshold}
        seed-path (vec (:path opts))
        n         (count seed-path)
        decider   (walk-decider ctx)
        decide    (:decide decider)
        collision (:collision-owners decider)
        redact?   #(and (not (:rf.egress/include-sensitive? ctx))
                        (decl-sensitive? % sensitive))
        prefixes  (:decl-prefixes ctx)
        fork      (fn [cands seg]
                    (if (integer? seg)
                      (fork-index-paths cands seg prefixes)
                      (fork-decl-paths cands seg prefixes)))]
    ;; `:path` is the ABSOLUTE app-db offset of `v`, so a declaration AT or
    ;; ABOVE the offset governs `v` exactly as it would in a whole-db walk
    ;; Seeding the candidate set with the bare offset could
    ;; only ever match declarations EXTENDING it: an `[:auth]`-sensitive frame
    ;; would redact `[:auth]` whole-db while a direct read of `[:auth :token]`
    ;; shipped raw, and `[:items 0]` would escape the index-free `[:items :token]`.
    ;; So replay the walker's OWN descent from the root along the offset — the
    ;; same forks, the same per-node decision at every ancestor — and walk `v`
    ;; with the candidate set that descent arrives at. An ancestor matched
    ;; sensitive yields the sentinel; one matched large yields the marker,
    ;; built for `v` at the offset so its handle re-fetches exactly what was
    ;; asked for. A COLLISION ancestor (a large node holding a sensitive
    ;; descendant) cannot build its marker from its own candidates, because `v`
    ;; must be redacted against the candidates at the read's coordinate: the
    ;; replay continues to that coordinate, a sensitive coordinate on the way
    ;; still yields the sentinel, and `v` is then wrapped in a marker at the
    ;; offset, measured after its own sensitive descendants redact. The offset
    ;; carries no container types, so an integer segment takes the index fork —
    ;; the SUPERSET of the map-key fork for the same segment, so an
    ;; integer-keyed map can over-match but never leak. No offset (`[]`) walks
    ;; from `#{[]}`, the whole-db seed.
    (loop [i 0 cands #{[]} owners nil]
      (cond
        (and owners (redact? cands))
        rf.privacy/redacted-sentinel

        (and owners (< i n))
        (recur (inc i) (fork cands (nth seed-path i)) owners)

        owners
        (if (marker? v)
          v
          (->marker (walk v seed-path cands (collision-measure-ctx ctx))
                    seed-path
                    (marker-opts owners ctx)))

        (= i n)
        (walk v seed-path cands ctx)

        :else
        (if-let [collision-owners (collision cands)]
          (recur i cands collision-owners)
          (let [r (decide [seed-path cands] v)]
            (if (identical? r walk-recur)
              (recur (inc i) (fork cands (nth seed-path i)) nil)
              r)))))))

(defn- elide-against-frame
  "`elide-against-registry` over `frame-id`'s own classification."
  [v opts frame-id]
  (elide-against-registry v opts frame-id (registry-of frame-id)))

;; ---------------------------------------------------------------------------
;; The CLOSED egress-opts vocabulary.
;;
;; One policy vocabulary, spelled the same way at every door. In an open map a
;; RECOGNISED policy key that the reading door does not read would vanish
;; without a signal — `:rf.egress/profile` is resolved by `re-frame.projection`,
;; never here, so `(elide-wire-value v {:rf.egress/profile :rf.egress/off-box-tool})`
;; would walk under the DEFAULT policy while reading as though it had named a
;; boundary. The map is closed instead: an unknown key is a loud
;; `:rf.error/bad-egress-opts`, which can only ever make egress SAFER (an
;; unknown key would otherwise do nothing, so the throw never lets through a
;; value the walk would have redacted).
;; ---------------------------------------------------------------------------

(def walker-opt-keys
  "The CLOSED key set `elide-wire-value` accepts.

  `:rf.egress/profile` is deliberately ABSENT: profiles are a
  `re-frame.projection` concern (`project-egress` resolves one to a
  `:rf.egress/*` opt-set and passes THAT down here). The two shared
  inclusion axes' UNQUALIFIED spellings are absent for the same
  reason — `:rf.egress/*` is the one vocabulary. So are `:rf.size/*`
  spellings: the whole opts vocabulary lives under one namespace, and
  `:rf.size/*` reserves the wire MARKER
  `:rf.size/large-elided` and nothing else, so a marker and a policy
  are never spelled alike. `:as-of-epoch` is absent too: its only effect
  would be a four-element marker handle the
  normative schema rejects, so passing it is a loud
  `:rf.error/bad-egress-opts`."
  #{:frame
    :path
    :query-v
    :rf.egress/include-sensitive?
    :rf.egress/include-large?
    :rf.egress/include-digests?
    :rf.egress/threshold-bytes})

(defn bad-egress-opts-ex
  "Build the `:rf.error/bad-egress-opts` `ex-info` for a CLOSED egress-opts
  map that carried `unknown` keys. Shared by every closed egress door
  (`elide-wire-value` here and `rf/project-egress` in
  `re-frame.projection`) so the wording and the
  machine-readable token cannot drift; each passes its own `where-sym` and
  its own `accepted` set, because the sets differ by exactly the keys the
  door itself owns.

  Returns the `ex-info`; the caller throws."
  [where-sym unknown accepted]
  (let [unknown  (vec (sort unknown))
        accepted (vec (sort accepted))
        profile? (some #{:rf.egress/profile} unknown)]
    (rf.error/thrown-ex-info
      :rf.error/bad-egress-opts
      where-sym
      (str "unrecognised egress opts key(s) " (pr-str unknown)
           " — the egress opts map is CLOSED, so a key this door does not "
           "read is a loud error rather than a silent no-op; accepted keys "
           "are " (pr-str accepted) "."
           (when profile?
             (str " :rf.egress/profile names a BOUNDARY and is resolved by "
                  "rf/project-egress, which passes the resolved :rf.egress/* "
                  "opt-set down to the walker — pass the profile there.")))
      {:recovery (if profile?
                   :pass-the-profile-to-project-egress
                   :use-a-recognised-egress-opts-key)
       :extra    {:unknown-keys unknown
                  :accepted     accepted}})))

(defn assert-egress-opts!
  "Throw `:rf.error/bad-egress-opts` when `opts` carries a key outside
  `accepted`. A nil / empty opts map is always fine. Returns `opts` so the
  guard can sit in a threading position."
  [where-sym accepted opts]
  (when (map? opts)
    (when-let [unknown (seq (remove accepted (keys opts)))]
      (throw (bad-egress-opts-ex where-sym unknown accepted))))
  opts)

(def ^:no-doc machine-snapshot-prefix
  "The runtime-db prefix a machine actor's snapshot lives under (EP-0001; Spec
  005 §Where snapshots live): `[:rf.runtime/machines :snapshots <actor-id>]`. A
  machine's `:data` classification is claimed at the actor's ABSOLUTE snapshot
  path, so a snapshot-valued read is walked from here."
  [:rf.runtime/machines :snapshots])

(defn- sub-seed-path
  "The runtime-db position a framework read sub's VALUE projects onto, so the
  frame's absolute declarations match the bare value the sub returns; nil for
  any other sub. `[:rf/machine <id>]` returns actor `<id>`'s snapshot; a route
  read sub's slice position is routing-owned, resolved through the late-bound
  seed table (core stays decoupled from routing)."
  [query-v]
  (when (sequential? query-v)
    (let [sub-id (first query-v)]
      (if (= :rf/machine sub-id)
        (when-some [actor-id (second query-v)]
          (conj machine-snapshot-prefix actor-id))
        (when-let [seed-fn (rf.late-bind/get-fn :routing/route-sub-egress-path)]
          (seed-fn sub-id))))))

(defn elide-wire-value
  "Walk `v` and substitute the frame's declared sensitive or large paths for
  wire egress: the frame's static policy — its `reg-classification` and flow
  output declarations, and the subsystem claims it makes on the instances the
  frame's runtime-db holds. Sensitive wins over large when both declarations
  match.

  `opts` is a CLOSED map (`walker-opt-keys`) — one policy vocabulary,
  spelled the same way at every egress door:

      {:frame                      <frame-id>   ;; by KEY PRESENCE, see below
       :path                       [...]        ;; absolute app-db offset of `v`; a declaration at or above it governs `v`
       :query-v                    [...]        ;; framework read-sub re-seeding
       :rf.egress/include-sensitive? <bool>
       :rf.egress/include-large?     <bool>
       :rf.egress/include-digests?   <bool>
       :rf.egress/threshold-bytes    <int>}

  Any other key throws `:rf.error/bad-egress-opts` naming the offending
  keys. `:rf.egress/profile` is NOT one of them: a profile names a
  BOUNDARY and is resolved by `rf/project-egress`, which passes the
  resolved `:rf.egress/*` opt-set down here. In an open map, passing one to
  the walker would be a silent no-op — the call would read as though it had
  named a boundary while the walk ran under the default policy — and so
  would the two shared axes' unqualified spellings. Closing the map
  cannot widen egress: an unknown key would otherwise do nothing, and here
  it does nothing but throw.

  EP-0002 — the wire-egress frame resolves from the CARRIED
  stamp: the explicit `:frame` opt (*override*) wins, else the in-effect
  carried-invariant scope (`rf.frame/resolve-current-frame` — a `with-frame`
  binding, or the closest enclosing frame boundary: a `frame-provider`
  (SCOPE) or a `frame-root` (ENSURE)). There is NO `:rf/default` floor:
  a frame's elision policy may be applied only when that frame is KNOWN
  (Spec 002 §Frame target resolution; EP-0002 §Trace, Projection, And
  Elision / §Privacy And Egress).

  The `:frame` opt is read by KEY PRESENCE, not truthiness: an explicit
  `{:frame nil}` says *this value has no governing frame* and FAILS CLOSED.
  Only an ABSENT `:frame` key falls through to the carried scope. That
  distinction is the whole point — a caller projecting one frame's value
  from inside another (a tool rendering from its own chrome frame, a
  frameless record) must be able to SAY \"no frame\" and be believed. Were
  `nil` read as absence it would borrow the ambient frame, which for such a
  caller resolves, is live, and may classify nothing, so the value would
  ship RAW under no policy.

  A resolved frame-id is not enough — it must RESOLVE to a live frame.
  An explicit `:frame` opt (or a stale carried scope) may name a frame that
  was never registered or has since been destroyed. An
  unresolvable frame has no policy, so feeding it through the walk would be
  an EMPTY-policy identity transform — shipping every value verbatim under
  NO policy, a silent leak. An unknown / destroyed frame therefore FAILS
  CLOSED here too, identically to the frameless case: it is validated against
  the live frame table (`rf.frame/frame`) before being treated as
  policy-bearing.
  Spec 015
  §Direct reads and fail-closed frame resolution: an unresolved frame must
  fail closed — it must not fall through to a permissive walk, and never
  synthesizes `:rf/default`.

  Frameless / unresolvable-frame egress FAILS CLOSED. When no LIVE frame is
  carried, no frame policy is reachable, so a permissive
  identity walk would ship every value verbatim under NO policy. Rather than
  borrow another frame's classification, the whole value is conservatively redacted
  to the `:rf/redacted` sentinel. `:rf.egress/include-sensitive? true` is the
  deliberate opt-out: a caller that has explicitly waived sensitive
  redaction gets the value walked with an empty policy (the identity
  transform), so an inspector that genuinely wants the raw, policy-free
  value asks for it on purpose."
  ([v] (elide-wire-value v nil))
  ([v opts]
   ;; CLOSED opts — FIRST, before the `:query-v` re-seed below
   ;; can synthesise a `:path`, so the keys graded are exactly the caller's.
   (assert-egress-opts! 're-frame.elision/elide-wire-value walker-opt-keys opts)
   (let [;; Framework read-sub egress re-seeding. A direct-read off-box
         ;; surface (Pair MCP read-sub / list-subscriptions :include-values /
         ;; snapshot :sub-cache / Xray) walks a framework read sub's BARE value
         ;; — a machine snapshot, a route slice — but that value's
         ;; classification is claimed at its ABSOLUTE runtime-db path (under
         ;; `[:rf.runtime/machines :snapshots <id> …]` /
         ;; `[:rf.runtime/routing :current …]`). When the caller names the sub
         ;; via `:query-v`, OVERLAY the value's runtime-db storage position as
         ;; `:path` (`sub-seed-path`) so the candidate declaration-coordinate
         ;; set starts where those decls live (mirroring the SSR
         ;; `project-routing-egress` offset). Any other sub resolves nil and
         ;; `opts` is untouched.
         opts       (if-let [seed (sub-seed-path (:query-v opts))]
                      (assoc opts :path seed)
                      opts)
         ;; PRESENCE, not truthiness: an explicit `:frame` key OWNS the
         ;; resolution, `nil` included. `(or (:frame opts) …)` would make
         ;; `{:frame nil}` — "this value has no governing frame" —
         ;; indistinguishable from "no `:frame` key", so it would fall through
         ;; to the ambient scope and ship RAW under a frame that happened to
         ;; be live and classify nothing. Saying `nil` is sayable and
         ;; fails closed.
         frame-id   (if (contains? opts :frame)
                      (:frame opts)
                      (rf.frame/resolve-current-frame))
         ;; A frame-id alone is not policy-bearing — it must resolve to a
         ;; LIVE frame. `rf.frame/frame` returns nil for an unknown /
         ;; never-registered / destroyed id, so an explicit `:frame` opt or
         ;; a stale carried scope that names a dead frame is treated exactly
         ;; like the frameless case below (fail closed).
         live-frame? (and (some? frame-id) (some? (rf.frame/frame frame-id)))]
     (cond
       ;; Known + LIVE carried frame ⇒ apply that frame's elision policy.
       live-frame?
       (elide-against-frame v opts frame-id)

       ;; Frameless / unresolvable-frame + the caller waived sensitive
       ;; redaction ⇒ identity walk against an empty (no-frame) policy. The
       ;; walker is the identity transform when no declarations are
       ;; reachable.
       (true? (:rf.egress/include-sensitive? opts))
       (elide-against-frame v opts ::no-frame)

       ;; Frameless / unresolvable-frame egress, no opt-out ⇒ fail closed:
       ;; no policy is available, so conservatively redact the whole value
       ;; rather than borrow another frame's classification or pass it through under
       ;; an empty policy.
       :else
       rf.privacy/redacted-sentinel))))

(defn marker?
  [v]
  (and (map? v) (contains? v :rf.size/large-elided)))

;; ---------------------------------------------------------------------------
;; EP-0025: there is no derived-tree VALUE-MATCH redaction engine.
;;
;; A value-based dual of the path-based `elide-wire-value` above — collecting
;; the live values at a frame's classified `:sensitive` / `:large` app-db paths
;; and substituting any EQUAL leaf in a derived tree (rendered hiccup,
;; `:effective-args`, a snapshot body) — is what EP-0025 §"What is removed"
;; disclaims as "propagation / taint by another name": a universal "same value
;; redacted everywhere" backstop that a HYGIENE helper does not earn.
;; Classification does not propagate: you redact
;; exactly the app-db PATHS you classify (`elide-wire-value`), and a sensitive
;; value re-keyed into a derived tree ships raw unless its app-db PATH is
;; classified. This is INTENDED fail-open — hygiene, not a guarantee.
;;
;; `project-egress`'s `:rf.observe/derived-tree` record kind (B4) path-walks
;; the tree slot(s) through `elide-wire-value`
;; (a no-op for re-keyed values — the documented fail-open posture).
;; ---------------------------------------------------------------------------

;; The warn-once set is keyed by `[frame-id path]`, so a destroy drops that
;; frame's entries and no other's: a same-id successor warns afresh, every
;; other frame keeps its warned paths, and per-request frames cannot grow the
;; set without bound. Spec 002 §Lifecycle contributions.
(defn- forget-frame-warnings!
  [frame-id]
  (swap! warned-unschema'd
         (fn [warned] (into #{} (remove #(= frame-id (first %))) warned)))
  nil)

(rf.frame/contribute-lifecycle!
  {:id                 :elision
   :phase              #{:post-dead}
   :on-frame-destroyed (fn [frame-id _phase]
                         (swap! policy-cache dissoc frame-id)
                         (swap! snapshot-policies dissoc frame-id)
                         (forget-frame-warnings! frame-id))})
