(ns re-frame.resources.classification
  "Owner classification for resource entries and mutation instances. A
  `reg-resource` / `reg-mutation` spec declares projection-relative
  `:sensitive` / `:large` paths; the runtime lowers them per instance into the
  frame elision registry, and every egress boundary reads that registry
  through the shared `re-frame.projection/project-egress` walker, never a
  resource-private elider. Coarse whole-entry `:sensitive?` / `:large?` claims
  are the root case. Schema props redact validation failures only; they do not
  classify durable state. Sensitive wins over large, enforced once by the
  shared elision walker's ordering.
  Spec 015 §Subsystem projection-relative classification.
  Spec 016 §SSR and hydration."
  (:require [re-frame.late-bind :as rf.late-bind]
            [re-frame.classification :as rf.classification]
            [re-frame.elision :as rf.elision]
            [re-frame.path :as rf.path]
            [re-frame.privacy :as rf.privacy]
            [re-frame.projection :as rf.projection]
            [re-frame.resources.mutation-runtime :as rf.resources.mutation-runtime]
            [re-frame.resources.state :as rf.resources.state]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- whole-entry disposition ----------------------------------------------

(defn whole-entry-disposition
  "How a resource / mutation's whole durable data may ride a wire, from the
  owner spec's coarse `:sensitive?` / `:large?` claims: `:redact`
  (metadata-only, redaction sentinel), `:omit` (metadata-only, data key
  dropped) or `:serialize` (still registry-projected per slot). Sensitive wins
  over large because the sentinel still announces the entry exists. A nil
  spec is `:serialize`. Spec 015 §Subsystem projection-relative classification."
  [spec]
  (cond
    (:sensitive? spec) :redact
    (:large? spec)     :omit
    :else              :serialize))

;; Classification does not flow from a scope resolver's inputs to its output:
;; a derived scope tokenises off-box by the standing scope rule.
;; Spec 015 §No propagation, no taint.

;; ---- projection-relative declarations -------------------------------------
;; Declaration paths are rooted at the instance projection: `[:data …]` for a
;; resource's data; `[:params …]` / `[:scope …]` on a mutation. A bare-rooted
;; path is the `[[:ssn]]` data shorthand. A resource's params and scope are its
;; identity, so `reg-resource` refuses a path rooted there.

(def ^:private declaration-axis-keys
  "The two declaration axes a spec may carry."
  #{:sensitive :large})

(defn- decl-defect
  "Reason string for the first malformed entry in axis `k`'s `payload` (a
  vector of concrete `:rf/path` vectors), or nil when well-shaped."
  [k payload]
  (cond
    (not (vector? payload))
    (str "the `" k "` classification declaration must be a vector of "
         "projection-relative paths (e.g. [[:data :ssn]] / [[:params :account-id]]); "
         "got a " (pr-str (type payload)))
    :else
    (some (fn [p]
            (cond
              (not (sequential? p))
              (str "each path in the `" k "` classification declaration must be a "
                   "path vector; got " (pr-str p))
              :else
              (try (rf.path/normalize-concrete p) nil
                   (catch #?(:clj Throwable :cljs :default) e
                     (str "an invalid path in the `" k "` classification "
                          "declaration: " (or (ex-message e) (str e)))))))
          payload)))

(defn classification-declaration-defect
  "`{:axis k :reason <string>}` for the first malformed `:sensitive` /
  `:large` declaration in `spec`, or nil. `reg-resource` / `reg-mutation`
  throw `:rf.error/resource-bad-spec` on a defect."
  [spec]
  (some (fn [k]
          (when (contains? spec k)
            (when-let [reason (decl-defect k (get spec k))]
              {:axis k :reason reason})))
        declaration-axis-keys))

(defn identity-rooted-declaration
  "The first `[axis path]` in a resource `spec` rooted at `:params` or
  `:scope`, or nil. `reg-resource` refuses such a path because params and
  scope are the resource's identity: the scope always tokenises off-box, and
  sensitive params call for the coarse `:sensitive?` claim. A mutation's
  params are an event payload, so `reg-mutation` keeps these paths. Assumes
  the spec passed `classification-declaration-defect`."
  [spec]
  (first (for [axis  [:sensitive :large]
               path  (get spec axis)
               :when (#{:params :scope} (first (rf.path/normalize-concrete path)))]
           [axis path])))

(defn- split-projection-paths
  "Splits axis `k` of `spec` into `{:data [paths] :params [paths]}` with the
  projection head stripped; bare-rooted paths go to `:data`. A `:scope` path
  joins `:params` WITH its head, because the mutation carriers hold params and
  scope as siblings."
  [spec k]
  (reduce
    (fn [acc p]
      (let [p (vec (rf.path/normalize-concrete p))]
        (case (first p)
          :data   (update acc :data conj (subvec p 1))
          :params (update acc :params conj (subvec p 1))
          :scope  (update acc :params conj p)
          (update acc :data conj p))))
    {:data [] :params []}
    (get spec k)))

(defn spec-declaration-marks
  "`spec`'s declarations as `{:data {:sensitive {path decl} :large {…}}
  :params {…}}`, the same mark shape schema props use. Assumes the spec
  passed `classification-declaration-defect`."
  [spec]
  (let [s (split-projection-paths spec :sensitive)
        l (split-projection-paths spec :large)
        ->decl (fn [paths] (into {} (map (fn [p] [p {:source :owner-declaration}])) paths))]
    {:data   {:sensitive (->decl (:data s))   :large (->decl (:data l))}
     :params {:sensitive (->decl (:params s)) :large (->decl (:params l))}}))

;; ---- carrier projection (continuation reply, execute event) ---------------
;; The owner declaration is the single source: a carrier is redacted from the
;; spec's paths, never a second classification language. The causal write
;; reads the raw params; only the echo is projected.
;; Spec 016 §Mutation completion continuations — call-site `:reply-to`.

(defn- carrier-decl-paths
  "Re-roots axis `k` of `spec` onto a `{:params … :scope …}` carrier map:
  params paths under `[:params …]`, scope paths as-is, and data paths under
  `data-slot` (dropped when `data-slot` is nil, as on the execute payload,
  which has no result yet)."
  [spec k data-slot]
  (let [{:keys [data params]} (split-projection-paths spec k)]
    (into (if data-slot (mapv #(into [data-slot] %) data) [])
          (mapv (fn [p] (if (= :scope (first p)) p (into [:params] p)))
                params))))

(defn redact-continuation-reply
  "Redacts `reply`'s owner-declared `:params` / `:scope` subpaths and its
  `:value` (the data projection), from `spec`'s declarations. A nil spec or
  one declaring neither axis returns `reply` unchanged.

  Mutations call it at the source; reads call it only at off-box egress
  (`trace_egress/redact-reply-declarations`), because the app's own
  `:reply-to` handler is entitled to the decoded body. Matching is
  index-free, so a declaration such as `[:data :email]` covers every item of
  an infinite feed's merged list, as it does on the durable side."
  [reply spec]
  (let [sens  (carrier-decl-paths spec :sensitive :value)
        large (carrier-decl-paths spec :large :value)]
    (if (or (seq sens) (seq large))
      (rf.classification/redact-with-paths reply sens large {:index-free? true})
      reply)))

(defn project-execute-event-args
  "Redacts a `[:rf.mutation/execute <args>]` args map for the event-bearing
  trace slots, from the owning mutation's declarations (`spec-of` resolves
  the `:mutation` id). A payload-carrying `:reply-to` event vector is also
  redacted by its target registration through the
  `:classification/redact-event-by-registration` hook. An unregistered id, an
  undeclared spec or a non-map `args` passes through unchanged. Core reaches
  this through the `:resources/project-execute-event-args` late-bind hook;
  `spec-of` is injected because a static require would cycle."
  [args spec-of]
  (if-not (map? args)
    args
    (let [spec  (when-let [mid (:mutation args)] (spec-of mid))
          sens  (when spec (carrier-decl-paths spec :sensitive nil))
          large (when spec (carrier-decl-paths spec :large nil))
          args  (if (or (seq sens) (seq large))
                  (rf.classification/redact-with-paths args sens large)
                  args)
          redact-event (rf.late-bind/get-fn :classification/redact-event-by-registration)
          reply-to     (:reply-to args)]
      (if (and redact-event (vector? reply-to))
        (let [rt' (redact-event reply-to)]
          (if (identical? reply-to rt') args (assoc args :reply-to rt')))
        args))))

;; ---- egress projection ----------------------------------------------------
;; The registry is the single source at egress: declarations are lowered by
;; `reconcile-registry` and read back here, never re-derived from the spec.
;; A frame-less read rides verbatim; the coarse whole-entry disposition is the
;; separate frame-independent authority.

(defn- registry-classifies-under?
  "True when `frame-id`'s elision registry holds any declaration under
  `prefix`. It gates the egress walk, because the walker rebuilds collections
  and would break the list/vector byte-identity of an unclassified value.
  Spec 016 §Resource identity."
  [frame-id prefix]
  (let [n     (count prefix)
        under (fn [decls]
                (some (fn [p]
                        (and (>= (count p) n) (= prefix (subvec (vec p) 0 n))))
                      (keys decls)))]
    (boolean (or (under (rf.elision/sensitive-declarations frame-id))
                 (under (rf.elision/declarations frame-id))))))

(defn project-entry-data
  "Projects an entry's `data` for egress under `boundary-profile`, walking it
  through `project-egress` seeded at the entry's absolute `:data` offset so
  the lowered declarations match. Infinite feeds need no special case: the
  index-free walker matches every page. Rides `data` verbatim when `frame-id`
  is nil or nothing is declared under the offset. Spec 016 §SSR and hydration."
  [data key-id frame-id boundary-profile]
  (let [data-prefix (conj (rf.resources.state/entry-path-by-id key-id) :data)]
    (if (and frame-id (registry-classifies-under? frame-id data-prefix))
      (rf.projection/project-egress
        data
        {:frame             frame-id
         :path              data-prefix
         :rf.egress/profile boundary-profile})
      data)))

;; ---- invalid-params error projection --------------------------------------
;; An invalid-params error carries the failing params and the explainer output
;; on public error data, so a sensitive sibling would leak when a different
;; field failed. Schema props govern this validation-failure record (not
;; durable state): the `:schemas/check!` seam redacts the explanation, and the
;; params are redacted here, slot by slot. Spec 015 §Failure posture.

(defn- validation-failure-params-marks
  "The `:sensitive?` / `:large?` marks `schema` declares, as
  `{:sensitive {path decl} :large {path decl}}`; empty maps when the schema is
  nil or the walker hooks are unbound."
  [schema]
  (let [extract (fn [hook]
                  (if-let [f (and schema (rf.late-bind/get-fn hook))]
                    (or (f schema []) {})
                    {}))]
    {:sensitive (extract :schemas/extract-sensitive-paths-from-schema)
     :large     (extract :schemas/extract-large-paths-from-schema)}))

(defn- walker-sees-every-mark?
  "True when the schemas walker reports `schema` free of opaque descendants
  and of unanchored `:map-of` marks, so the extracted marks reach every marked
  slot. False when the hook is unbound."
  [schema]
  (if-let [opaque-child? (rf.late-bind/get-fn :schemas/schema-has-opaque-child?)]
    (not (opaque-child? schema))
    false))

(defn redact-invalid-params
  "`params` projected for an invalid-params failure, against `spec`'s
  `:params-schema` props: redacted per slot (index-free), keeping a
  non-sensitive failing field diagnostic, and redacted whole when the walker
  cannot see every mark. Spec 015 §Failure posture."
  [params spec]
  (let [schema (:params-schema spec)
        {:keys [sensitive large]} (validation-failure-params-marks schema)]
    (cond
      (and (some? schema) (not (walker-sees-every-mark? schema)))
      rf.privacy/redacted-sentinel

      (or (seq sensitive) (seq large))
      (rf.classification/redact-with-paths params (keys sensitive) (keys large)
                                           {:index-free? true})

      :else params)))

;; ---- per-instance lowering into the elision registry ----------------------
;; Pure runtime-db transforms, so the handler that writes or evicts an entry
;; folds the registry update into the same `:rf.db/runtime` commit. Each
;; rebuilds its owner's whole claim set from the live instances, so an evicted
;; instance's claims drop with no separate hook, and other owners' claims are
;; untouched. Spec 015 §Subsystem projection-relative classification.

(def ^:private resource-source
  "The elision-registry `:source` tag of resource-lowered declarations."
  :resource)

(def ^:private elision-registry-key :rf.runtime/elision)

(defn- entry-data-prefix
  "`[:rf.runtime/resources :entries <key-id> :data]`."
  [key-id]
  (conj (rf.resources.state/entry-path-by-id key-id) :data))

(defn- instance-declaration-paths
  "The absolute `{:sensitive [paths] :large [paths]}` one entry contributes:
  `spec`'s `:data` declarations re-rooted under the entry's `key-id`."
  [key-id spec]
  (let [data-prefix (entry-data-prefix key-id)
        ->abs       (fn [k] (mapv #(into data-prefix %) (:data (split-projection-paths spec k))))]
    {:sensitive (->abs :sensitive)
     :large     (->abs :large)}))

(def ^:private resource-owner
  "The registry owner identity every resource entry's claims share."
  {:source resource-source})

(defn reconcile-registry
  "`runtime-db` with the elision registry's resource-owned claims replaced by
  the declarations of every current cache entry. `spec-of` maps a resource id
  to its spec (injected: a static require would cycle). Emits an explicit,
  possibly empty, `:rf.runtime/elision` key once the registry existed, so a
  whole-value runtime-db commit honours an eviction's drop instead of
  resurrecting the prior registry; never-classified state gets no key."
  [runtime-db spec-of]
  (let [base    (or runtime-db {})
        entries (get-in base (rf.resources.state/entries-path))
        reg     (get base elision-registry-key)
        {:keys [sensitive large]}
        (reduce-kv
          (fn [acc key-id entry]
            (let [resource-id (second (:resource/key entry))
                  spec        (when resource-id (spec-of resource-id))]
              (if-not spec
                acc
                (let [{:keys [sensitive large]} (instance-declaration-paths key-id spec)]
                  (-> acc
                      (update :sensitive into sensitive)
                      (update :large into large))))))
          {:sensitive [] :large []}
          (or entries {}))
        new-reg (-> (or reg {})
                    (rf.elision/replace-owner-claims :sensitive-declarations resource-owner sensitive)
                    (rf.elision/replace-owner-claims :declarations resource-owner large))]
    (cond
      (seq new-reg)
      (assoc base elision-registry-key new-reg)

      (contains? base elision-registry-key)
      (assoc base elision-registry-key {})

      :else base)))

;; A mutation instance keeps the raw values (success-path `:invalidates` /
;; `:patches` / `:populates` read them); only registry-reading egress redacts.

(defn- mutation-instance-declaration-paths
  "The absolute `{:sensitive [paths] :large [paths]}` one mutation instance
  contributes: params paths under its `:params`, scope paths under `:scope`,
  data paths under `:result`."
  [key-id spec]
  (let [s            (split-projection-paths spec :sensitive)
        l            (split-projection-paths spec :large)
        inst-prefix  (conj (rf.resources.mutation-runtime/instances-path) key-id)
        result-pre   (conj inst-prefix :result)
        params-pre   (conj inst-prefix :params)
        scope-pre    (conj inst-prefix :scope)
        ->key-abs    (fn [p]
                       (if (= :scope (first p))
                         (into scope-pre (subvec (vec p) 1))
                         (into params-pre p)))
        ->abs        (fn [axis]
                       (into (mapv #(into result-pre %) (:data axis))
                             (mapv ->key-abs (:params axis))))]
    {:sensitive (->abs s)
     :large     (->abs l)}))

(def ^:private mutation-source
  "The elision-registry `:source` tag of mutation-lowered declarations."
  :mutation)

(def ^:private mutation-owner
  "The registry owner identity every mutation instance's claims share."
  {:source mutation-source})

(defn reconcile-mutation-registry
  "The mutation peer of `reconcile-registry`: replaces the `:source :mutation`
  claims with the declarations of every current mutation instance. `spec-of`
  maps a mutation id to its spec."
  [runtime-db spec-of]
  (let [base      (or runtime-db {})
        instances (get-in base (rf.resources.mutation-runtime/instances-path))
        reg       (get base elision-registry-key)
        {:keys [sensitive large]}
        (reduce-kv
          (fn [acc key-id inst]
            (let [mid  (:mutation/id inst)
                  spec (when mid (spec-of mid))]
              (if-not spec
                acc
                (let [{:keys [sensitive large]} (mutation-instance-declaration-paths key-id spec)]
                  (-> acc
                      (update :sensitive into sensitive)
                      (update :large into large))))))
          {:sensitive [] :large []}
          (or instances {}))
        new-reg   (-> (or reg {})
                      (rf.elision/replace-owner-claims :sensitive-declarations mutation-owner sensitive)
                      (rf.elision/replace-owner-claims :declarations mutation-owner large))]
    (cond
      (seq new-reg)
      (assoc base elision-registry-key new-reg)

      (contains? base elision-registry-key)
      (assoc base elision-registry-key {})

      :else base)))
