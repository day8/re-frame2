(ns re-frame.elision
  "Schema-first wire-boundary elision.

  Canonical declarations come from app-schema slot metadata:
  `{:large? true}` hydrates `[:rf/elision :declarations]`, and
  `{:sensitive? true}` hydrates `[:rf/elision :sensitive-declarations]`.
  Handler metadata `:sensitive?` remains the coarse escape hatch for
  cross-cutting handlers. There are no imperative large-path APIs."
  (:require [re-frame.frame :as frame]
            [re-frame.interop :as interop]
            [re-frame.late-bind :as late-bind]
            [re-frame.privacy :as privacy]
            [re-frame.substrate.adapter :as adapter]
            [re-frame.trace :as trace]))

#?(:clj (set! *warn-on-reflection* true))

(def ^:private large-warning-bytes 16384)

(defonce ^:private warned-unschema'd
  (atom #{}))

(defn- registry-of
  [frame-id]
  (when-let [container (frame/get-frame-db frame-id)]
    (get (adapter/read-container container) :rf/elision)))

(defn- swap-registry!
  [frame-id f]
  (when-let [container (frame/get-frame-db frame-id)]
    (let [old-db  (adapter/read-container container)
          old-reg (get old-db :rf/elision)
          new-reg (f old-reg)
          new-db  (if (seq new-reg)
                    (assoc old-db :rf/elision new-reg)
                    (dissoc old-db :rf/elision))]
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
  (swap-registry! frame-id
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
  "Populate `[:rf/elision :declarations]` from `{:large? true}` schema
  slot metadata. Returns the populated paths."
  ([] (populate-elision-from-schemas! (frame/current-frame)))
  ([frame-id]
   (install-schema-declarations!
     frame-id
     :declarations
     (schema-declarations frame-id :schemas/extract-large-paths-from-schema))))

(defn populate-sensitive-from-schemas!
  "Populate `[:rf/elision :sensitive-declarations]` from
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

(declare walk)

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
      (->marker v path {:hint             (:hint large-decl)
                        :as-of-epoch      (:as-of-epoch ctx)
                        :include-digests? (:include-digests? ctx)})

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
          (let [bytes (pr-str-bytes v)]
            (when (> bytes large-warning-bytes)
              (warn-large-unschema'd! (:frame-id ctx) path bytes))))
        v))))

(defn elide-wire-value
  "Walk `v` and substitute schema-declared sensitive or large paths for
  wire egress. Sensitive wins over large when both declarations match."
  ([v] (elide-wire-value v nil))
  ([v opts]
   (let [frame-id (or (:frame opts) (frame/current-frame) :rf/default)
         reg      (registry-of frame-id)
         ctx      {:frame-id           frame-id
                   :large              (or (:declarations reg) {})
                   :sensitive          (or (:sensitive-declarations reg) {})
                   :include-large?     (true? (:rf.size/include-large? opts))
                   :include-sensitive? (true? (:rf.size/include-sensitive? opts))
                   :include-digests?   (true? (:rf.size/include-digests? opts))
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
