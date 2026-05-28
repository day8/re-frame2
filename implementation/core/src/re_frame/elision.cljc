(ns re-frame.elision
  "Schema-first wire-boundary elision.

  Canonical declarations come from app-schema slot metadata:
  `{:large? true}` hydrates `[:rf/runtime :elision :declarations]`, and
  `{:sensitive? true}` hydrates
  `[:rf/runtime :elision :sensitive-declarations]`. Handler metadata
  `:sensitive?` remains the coarse escape hatch for cross-cutting
  handlers. There are no imperative large-path APIs."
  (:require [re-frame.frame :as frame]
            [re-frame.interop :as interop]
            [re-frame.late-bind :as late-bind]
            [re-frame.privacy :as privacy]
            [re-frame.substrate.adapter :as adapter]
            [re-frame.trace :as trace]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- runtime size threshold ----------------------------------------------
;;
;; Per API.md §Size-elision wire-boundary walker and §Configure keys, the
;; runtime auto-detect threshold for the `:rf.warning/large-value-unschema'd`
;; advisory is configurable. Precedence (normative, API.md L507):
;;
;;   explicit `:rf.size/threshold-bytes` opt  >  `(rf/configure :elision …)`  >  default
;;
;; A threshold of 0 disables runtime auto-detect entirely (only declared /
;; schema-marked entries elide); the unschema'd-large warning never fires.
;; The default mirrors the documented `{:rf.size/threshold-bytes 16384}`.

(def ^:private default-threshold-bytes 16384)

(defonce ^:private config
  ;; Map shape so future :elision configure-keys land additively, matching
  ;; `re-frame.subs.cache/config`.
  (atom {:rf.size/threshold-bytes default-threshold-bytes}))

(defn configure!
  "Update the elision configuration. Supports
  `{:rf.size/threshold-bytes N}` — a non-negative integer runtime
  auto-detect size threshold for the `:rf.warning/large-value-unschema'd`
  advisory (0 disables runtime auto-detect; only declared / schema-marked
  entries elide). Per API.md §Configure keys (`:elision`) and Spec 009
  §Size elision in traces. Routed from `re-frame.core/configure`."
  [opts]
  (when (map? opts)
    (swap! config merge (select-keys opts [:rf.size/threshold-bytes])))
  nil)

(defn current-config
  "Return the current elision configuration map. Public for tests and
  tools that want to display the configured runtime size threshold."
  []
  @config)

(defn- configured-threshold-bytes
  "The configured runtime size threshold, falling back to the documented
  default when no `configure` call has set it."
  []
  (let [v (:rf.size/threshold-bytes @config)]
    (if (some? v) v default-threshold-bytes)))

(defonce ^:private warned-unschema'd
  (atom #{}))

(defn- registry-of
  [frame-id]
  (when-let [container (frame/app-db-container frame-id)]
    (get-in (adapter/read-container container) [:rf/runtime :elision])))

(defn ^:no-doc write-elision-slot
  "Set or clear the per-frame elision registry inside `db`. When `new-reg`
  is non-empty it lands at `[:rf/runtime :elision]`. When empty, the
  slot is removed — and if the resulting `:rf/runtime` becomes empty,
  the `:rf/runtime` key itself is dissoc'd so apps that never used
  framework state don't manifest a stray nil container.

  Internal helper; exposed (with `^:no-doc`) so the sibling
  `re-frame.marks` ns — which writes through the SAME slot via
  `add-marks` / `set-marks` / `clear-app-db-marks!` — can share a
  single source of truth for the runtime-prune logic. Not part of the
  public API."
  [db new-reg]
  (cond
    (seq new-reg)
    (assoc-in db [:rf/runtime :elision] new-reg)

    ;; Clearing — only mutate when there's actually an :elision slot to
    ;; clear, so apps that never used elision don't get a stray
    ;; `:rf/runtime nil` entry.
    (and (contains? db :rf/runtime)
         (contains? (get db :rf/runtime) :elision))
    (let [next-runtime (dissoc (get db :rf/runtime) :elision)]
      (if (seq next-runtime)
        (assoc db :rf/runtime next-runtime)
        (dissoc db :rf/runtime)))

    :else
    db))

(defn ^:no-doc swap-elision-slot!
  "Read-transform-write helper for the per-frame elision registry.

  Reads the registry at `[:rf/runtime :elision]` from `frame-id`'s
  app-db, applies `(f reg) -> new-reg`, and writes the result back
  through `write-elision-slot` (which prunes a stranded `:rf/runtime`
  when the slot clears). No-op when the frame's container does not
  exist. Returns nil.

  Internal helper; exposed (with `^:no-doc`) so the sibling
  `re-frame.marks` ns — which mutates the SAME slot from its
  `add-marks` / `set-marks` / `clear-app-db-marks!` / `write-marks!`
  paths — can share a single source of truth for the read-transform-
  write skeleton. Not part of the public API."
  [frame-id f]
  (when-let [container (frame/app-db-container frame-id)]
    (let [old-db  (adapter/read-container container)
          old-reg (get-in old-db [:rf/runtime :elision])
          new-reg (f old-reg)
          new-db  (write-elision-slot old-db new-reg)]
      (adapter/replace-container! container new-db)))
  nil)

(defn declarations
  "Return schema-derived `:large?` declarations for `frame-id`."
  ([] (declarations :rf/default))
  ([frame-id]
   (or (get (registry-of frame-id) :declarations) {})))

(defn sensitive-declarations
  "Return schema-derived `:sensitive?` declarations for `frame-id`."
  ([] (sensitive-declarations :rf/default))
  ([frame-id]
   (or (get (registry-of frame-id) :sensitive-declarations) {})))

(defn- schema-declarations
  [frame-id extract-hook]
  (let [entries-fn (late-bind/get-fn :schemas/frame-schema-entries)
        extract-fn (late-bind/get-fn extract-hook)]
    (if (and entries-fn extract-fn)
      (reduce-kv
        (fn [acc base-path entry]
          (merge acc (extract-fn (:schema entry) base-path)))
        {}
        (entries-fn frame-id))
      {})))

(defn- install-schema-declarations!
  [frame-id registry-key schema-decls]
  (swap-elision-slot! frame-id
    (fn [reg]
      (let [without-schema (reduce-kv
                             (fn [acc path decl]
                               (if (= :schema (:source decl))
                                 acc
                                 (assoc acc path decl)))
                             {}
                             (get reg registry-key))
            merged         (merge without-schema schema-decls)
            reg'           (if (seq merged)
                             (assoc (or reg {}) registry-key merged)
                             (dissoc (or reg {}) registry-key))]
        reg')))
  (vec (keys schema-decls)))

(defn populate-elision-from-schemas!
  "Populate `[:rf/runtime :elision :declarations]` from `{:large? true}`
  schema slot metadata. Returns the populated paths."
  ([] (populate-elision-from-schemas! (frame/current-frame)))
  ([frame-id]
   (install-schema-declarations!
     frame-id
     :declarations
     (schema-declarations frame-id :schemas/extract-large-paths-from-schema))))

(defn populate-sensitive-from-schemas!
  "Populate `[:rf/runtime :elision :sensitive-declarations]` from
  `{:sensitive? true}` schema slot metadata. Returns the populated
  paths."
  ([] (populate-sensitive-from-schemas! (frame/current-frame)))
  ([frame-id]
   (install-schema-declarations!
     frame-id
     :sensitive-declarations
     (schema-declarations frame-id :schemas/extract-sensitive-paths-from-schema))))

(defn populate-from-schemas!
  "Refresh both schema-owned declaration registries for `frame-id`."
  ([] (populate-from-schemas! (frame/current-frame)))
  ([frame-id]
   {:large     (populate-elision-from-schemas! frame-id)
    :sensitive (populate-sensitive-from-schemas! frame-id)}))

(defn clear-warning-cache!
  []
  (reset! warned-unschema'd #{})
  nil)

(defn- pr-str-bytes
  [v]
  #?(:clj  (count (.getBytes ^String (pr-str v) "UTF-8"))
     :cljs (count (pr-str v))))

(defn- value-type
  [v]
  (cond
    (map? v)    :map
    (vector? v) :vector
    (set? v)    :set
    (string? v) :string
    :else       :scalar))

(defn- sha256-hex
  [v]
  #?(:clj
     (let [bytes (.getBytes ^String (pr-str v) "UTF-8")
           md    (doto (java.security.MessageDigest/getInstance "SHA-256")
                   (.update bytes))]
       (str "sha256:"
            (format "%064x" (java.math.BigInteger. 1 (.digest md)))))
     :cljs
     nil))

(defn- handle-of
  [path as-of-epoch]
  (if as-of-epoch
    [:rf.elision/at path :as-of-epoch as-of-epoch]
    [:rf.elision/at path]))

(defn- ->marker
  [v path {:keys [hint as-of-epoch include-digests?]}]
  (let [body (cond-> {:path   (vec path)
                      :bytes  (pr-str-bytes v)
                      :type   (value-type v)
                      :reason :schema
                      :hint   hint
                      :handle (handle-of (vec path) as-of-epoch)}
               include-digests? (assoc :digest (sha256-hex v)))]
    {:rf.size/large-elided body}))

(defn- warn-large-unschema'd!
  [frame-id path bytes]
  (when interop/debug-enabled?
    (let [k [frame-id (vec path)]]
      (when-not (contains? @warned-unschema'd k)
        (swap! warned-unschema'd conj k)
        (trace/emit! :warning :rf.warning/large-value-unschema'd
                     {:frame    frame-id
                      :path     (vec path)
                      :bytes    bytes
                      :hint     "Add `{:large? true}` to the schema slot for this path."
                      :recovery :no-recovery})))))

(declare walk marker?)

(defn- walk-map
  [m path ctx]
  (reduce-kv
    (fn [acc k v]
      (assoc acc k (walk v (conj path k) ctx)))
    (empty m)
    m))

(defn- walk-indexed
  [v path ctx]
  (let [n (count v)]
    (loop [i 0 acc (transient [])]
      (if (< i n)
        (recur (inc i)
               (conj! acc (walk (nth v i) (conj path i) ctx)))
        (persistent! acc)))))

(defn- walk-seq
  [xs path ctx]
  (let [idx (volatile! -1)]
    (persistent!
      (reduce (fn [acc v]
                (vswap! idx inc)
                (conj! acc (walk v (conj path @idx) ctx)))
              (transient [])
              xs))))

(defn- walk
  [v path ctx]
  (let [path        (vec path)
        large-decl  (get-in ctx [:large path])
        sensitive?  (contains? (:sensitive ctx) path)
        include-lg? (:include-large? ctx)
        include-s?  (:include-sensitive? ctx)]
    (cond
      (and sensitive? (not include-s?))
      privacy/redacted-sentinel

      (and large-decl (not include-lg?))
      (if (marker? v)
        ;; Idempotence under double-projection: a value at a `:large?`-
        ;; declared path that already carries the `:rf.size/large-elided`
        ;; marker shape is passed through unchanged. A forwarder pipeline
        ;; that accidentally double-projects (middleware composition,
        ;; tool-then-watcher fan-out) MUST NOT re-mark the marker — the
        ;; second pass's `:bytes` would otherwise reflect the printed
        ;; length of the prior marker, not the original payload. Mirrors
        ;; the sensitive-case idempotence (the `:rf/redacted` scalar
        ;; sentinel is non-matchable so the walker descends into nothing
        ;; on a re-projection pass). Per rf2-fq8ep.
        v
        (->marker v path {:hint             (:hint large-decl)
                          :as-of-epoch      (:as-of-epoch ctx)
                          :include-digests? (:include-digests? ctx)}))

      (map? v)
      (walk-map v path ctx)

      (vector? v)
      (walk-indexed v path ctx)

      (set? v)
      (into #{} (map #(walk % path ctx)) v)

      (seq? v)
      (walk-seq v path ctx)

      :else
      (do
        (when (and (string? v)
                   (not large-decl)
                   (not sensitive?))
          (let [threshold (:threshold-bytes ctx)]
            ;; A threshold of 0 disables runtime auto-detect entirely —
            ;; no `pr-str-bytes` walk, no warning. Per API.md §Configure
            ;; keys (`:elision` — "0 disables runtime auto-detect").
            (when (pos? threshold)
              (let [bytes (pr-str-bytes v)]
                (when (> bytes threshold)
                  (warn-large-unschema'd! (:frame-id ctx) path bytes))))))
        v))))

(defn elide-wire-value
  "Walk `v` and substitute schema-declared sensitive or large paths for
  wire egress. Sensitive wins over large when both declarations match."
  ([v] (elide-wire-value v nil))
  ([v opts]
   (let [frame-id  (or (:frame opts) (frame/current-frame) :rf/default)
         reg       (registry-of frame-id)
         ;; Precedence (API.md L507): explicit opt > configured > default.
         threshold (let [opt (:rf.size/threshold-bytes opts)]
                     (if (some? opt) opt (configured-threshold-bytes)))
         ctx       {:frame-id           frame-id
                    :large              (or (:declarations reg) {})
                    :sensitive          (or (:sensitive-declarations reg) {})
                    :include-large?     (true? (:rf.size/include-large? opts))
                    :include-sensitive? (true? (:rf.size/include-sensitive? opts))
                    :include-digests?   (true? (:rf.size/include-digests? opts))
                    :threshold-bytes    threshold
                    :as-of-epoch        (:as-of-epoch opts)}]
     (walk v (vec (:path opts)) ctx))))

(defn marker?
  [v]
  (and (map? v) (contains? v :rf.size/large-elided)))

(defn handle?
  [v]
  (and (vector? v) (= :rf.elision/at (first v))))

(late-bind/set-fn! :elision/populate-from-schemas! populate-from-schemas!)
(late-bind/set-fn! :elision/sensitive-declarations sensitive-declarations)
(late-bind/set-fn! :elision/clear-warning-cache! clear-warning-cache!)
