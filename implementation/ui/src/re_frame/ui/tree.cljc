(ns re-frame.ui.tree
  "The JVM-emitter runtime substrate: builders that assemble the VERSIONED
  PUBLIC structural tree (node schema v1) in canonical form, per the
  owning contract ai/findings drafts jvm-tree-and-conversion-contract.md.

  Five node variants, a CLOSED set:

    element        {:tag kw, :ns :svg|:mathml?, :attrs {..}, :events {..},
                    :children [..], :key any, + reserved :rf.ui/* keys}
    fragment       {:children [..], :key any}
    view-boundary  {:view-id kw, :props {..}, :children [..], :key any}
    trusted-HTML   {:html str, :key any}
    text           the host string itself

  Canonical-form rules enforced HERE (once, at build): absent-when-empty
  for :attrs/:events/:children; no nil attr entries; :ns absent for HTML;
  nil/false children dropped; numeric children -> JS ToString text;
  adjacent text runs coalesced; empty strings dropped after coalescing;
  keyed runs flattened into the parent's single children vector in
  document order.

  This namespace is .cljc so its pure canonicalization helpers are
  testable on both hosts, but only the JVM emitter consumes it — the
  client emitter targets react/jsx-runtime directly and never builds
  these nodes."
  (:require [clojure.string :as str]
            [re-frame.error :as error]
            [re-frame.registrar :as registrar]
            [re-frame.ui.rules :as rules]))

(declare classify-event sanitize-prop)

;; ---------------------------------------------------------------------------
;; Namespace context threading (SVG / MathML)
;;
;; A view compiled standalone cannot know it will be mounted under <svg>,
;; so the JVM emitter derives :ns at RENDER time: element builders run
;; inside their parent's children-thunk with this dynamic context bound.
;; ---------------------------------------------------------------------------

(def ^:dynamic *ns-context* nil)

;; ---------------------------------------------------------------------------
;; Children canonicalization
;; ---------------------------------------------------------------------------

(defn- spliceable-run?
  "The JVM emitters tag their own child vectors (keyed `for` runs and
  forwarded :children vectors) so the children builder can distinguish a
  compiled run (spliced) from a raw user vector/seq (rejected)."
  [x]
  (and (vector? x)
       (let [m (meta x)]
         (or (:rf.ui.tree/run m) (:rf.ui.tree/children m)))))

(defn child-vec
  "Tag a compiled children vector (view-call children forwarding) as
  spliceable."
  [v]
  (with-meta (vec v) {:rf.ui.tree/children true}))

(defn node?
  "Is x a (map-form) tree node? (Text nodes are strings and handled
  separately; this is the dynamic-child validation gate.)"
  [x]
  (and (map? x)
       (or (contains? x :tag)
           (contains? x :view-id)
           (contains? x :html)
           (contains? x :children))))

(defn- reject-child! [x]
  (error/throw-error!
   :rf.error/ui-tree-malformed 're-frame.ui/render
   (cond
     (or (seq? x) (and (coll? x) (not (map? x)) (not (vector? x))))
     (str "a dynamic child produced a raw seq — hiccup is compiled, not "
          "interpreted; list markup with (for ...) and a :key, or extract "
          "a child view")
     (vector? x)
     (str "a dynamic child produced a raw vector — hiccup is compiled, "
          "not interpreted; a runtime value cannot be a template. Extract "
          "a child view (or ui/raw for a host element)")
     (keyword? x)
     "a dynamic child produced a keyword — keywords are not renderable content"
     :else
     (str "a dynamic child produced an unrenderable value of type "
          (type x)))
   {:extra {:value x}}))

(defn children
  "Canonicalize evaluated child values into the single children vector:
  drop nil/false/true; numbers -> JS-ToString text; splice keyed runs in
  document order; coalesce adjacent text; drop empty strings; validate
  everything else is a node. Returns nil when empty (absent-when-empty)."
  [& xs]
  (let [flat  (fn flat [acc x]
                (cond
                  (nil? x)   acc
                  (false? x) acc
                  (true? x)  acc
                  (string? x) (conj acc x)
                  (number? x) (conj acc (rules/js-number-str x))
                  (spliceable-run? x) (reduce flat acc x)
                  (node? x)  (conj acc x)
                  :else      (reject-child! x)))
        vs    (reduce flat [] xs)
        ;; coalesce adjacent text runs, then drop empties
        out   (reduce (fn [acc v]
                        (if (and (string? v) (string? (peek acc)))
                          (conj (pop acc) (str (peek acc) v))
                          (conj acc v)))
                      [] vs)
        out   (into [] (remove #(and (string? %) (= "" %))) out)]
    (when (seq out) out)))

;; ---------------------------------------------------------------------------
;; Keyed runs
;; ---------------------------------------------------------------------------

(defn keyed-run
  "Wrap a compiled list site's row vector; enforces presence and
  uniqueness of keys under React's string coercion ([S1-CONFIRM] row 11:
  key 1 collides with key \"1\" — react-dom/server is silent but React's
  client dev warns; we diagnose upstream at the compile-indexed list
  site, earlier and loudly)."
  [rows]
  (let [rows (vec rows)]
    (loop [seen (transient {}) i 0]
      (if (< i (count rows))
        (let [row (nth rows i)]
          (when-not (and (map? row) (contains? row :key))
            (error/throw-error!
             :rf.error/ui-tree-malformed 're-frame.ui/render
             "a list row lost its :key — compiled keyed runs must carry keys"
             {:extra {:row row}}))
          (let [ks (rules/js-string-coerce (:key row))]
            (if-let [prev (get seen ks)]
              (error/throw-error!
               :rf.error/ui-duplicate-key 're-frame.ui/render
               (str "duplicate key " (pr-str (:key row)) " in a keyed list "
                    "(keys compare after React's string coercion: key 1 "
                    "collides with key \"1\"). Keys must be unique per list site")
               {:extra {:key (:key row) :collides-with prev}})
              (recur (assoc! seen ks (:key row)) (inc i)))))
        nil))
    (with-meta rows {:rf.ui.tree/run true})))

;; ---------------------------------------------------------------------------
;; Node builders (called by emitted JVM code)
;; ---------------------------------------------------------------------------

(defn events*
  "Merge the structurally-literal events map with runtime-classified
  entries, dropping nil classifications (nil handler value = entry
  dropped, per the tree contract)."
  [static dyn]
  (reduce-kv (fn [m k v] (if (nil? v) m (assoc m k v)))
             (or static {})
             (or dyn {})))

(defn- style-semantic [style-map]
  (reduce-kv (fn [m k v]
               (if (nil? v)
                 m
                 (assoc m k (rules/css-val->str (name k) v))))
             {} style-map))

(defn element
  "Build an element node in canonical form from an opts map:

    :static  compile-time-normalized attr map (semantic values)
    :norm    {kw runtime-expr-result} — values ALREADY semantic
             (class strings, style maps); nil values dropped
    :dyn     author-space runtime map (per-prop dynamic values or an
             ui/spread merge) — split/normalized here: on-* -> events,
             :class merges sugar-first, :style normalizes, :key/:ref
             skipped, custom-element properties classified via the
             runtime registry
    :events  structurally-known events map (data/opaque markers)
    :dyn-events {kw classified} — runtime-classified entries (nil drops)
    :key? :key-val  key presence + value
    :props   compile-time property-prop set (custom elements)
    :children thunk building the children vector (bound ns context)"
  [tag {:keys [static norm dyn events dyn-events key? key-val props children]}]
  (let [properties (when (str/includes? (name tag) "-")
                     (rules/custom-element-properties tag))
        base    (reduce-kv (fn [m k v] (if (nil? v) m (assoc m k v)))
                           (or static {})
                           (or norm {}))
        [attrs evts prop-props]
        (reduce-kv
         (fn [[m es pp] k v]
           (let [n (name k)]
             (cond
               (nil? v)                 [m es pp]
               (contains? #{:key :ref} k) [m es pp]
               (= k :class)             [(let [c (rules/classes-str
                                                  [(get m :class)
                                                   (rules/class-val v)])]
                                           (if c (assoc m :class c) (dissoc m :class)))
                                         es pp]
               (= k :style)             (if (map? v)
                                          [(let [s (style-semantic v)]
                                             (if (seq s) (assoc m :style s) m))
                                           es pp]
                                          (error/throw-error!
                                           :rf.error/ui-tree-malformed
                                           're-frame.ui/render
                                           "a dynamic :style value must be a map of style entries"
                                           {:extra {:value v}}))
               (str/starts-with? n "on-") [m (let [c (classify-event v)]
                                               (if c (assoc es k c) es)) pp]
               (and properties (properties k)) [(assoc m k v) es (conj pp k)]
               :else                    [(let [v' (rules/attr-val-semantic k v)]
                                           (if (nil? v') m (assoc m k v')))
                                         es pp])))
         [base {} (set (or props #{}))]
         (or dyn {}))
        evts    (events* events (merge evts dyn-events))
        ctx     *ns-context*
        chs     (when children
                  (binding [*ns-context* (rules/child-ns-context ctx tag attrs)]
                    (children)))
        el-ns   (rules/element-ns (case tag :svg :svg :math :mathml ctx))]
    (cond-> {:tag tag}
      el-ns             (assoc :ns el-ns)
      (seq attrs)       (assoc :attrs attrs)
      (seq evts)        (assoc :events evts)
      key?              (assoc :key key-val)
      (seq prop-props)  (assoc :rf.ui/property-props prop-props)
      (seq chs)         (assoc :children chs))))

(defn fragment
  [key-present? key-val & xs]
  (let [chs (apply children xs)]
    (cond-> {}
      key-present? (assoc :key key-val)
      (seq chs)    (assoc :children chs)
      ;; a fragment node must remain discriminable even when empty
      (not (seq chs)) (assoc :children []))))

(defn view-boundary
  "Wrap one internal-view expansion (Q12: view-boundary nodes are real
  nodes, nesting recursively). `props` is the props map the view fn
  received; the recorded :props drops :children (children are structural
  — they live in the expansion) and replaces top-level fn values with the
  opaque marker. The boundary's :children is the view's rendered output —
  a fragment-rooted view splices its children into the boundary (several
  children), a nil-rooted view has none; both stay matchable."
  [view-id props body]
  (let [recorded (reduce-kv (fn [m k v] (assoc m k (sanitize-prop v)))
                            {} (dissoc props :children))
        chs (cond
              (nil? body) nil
              ;; fragment-rooted view: boundary adopts the fragment's children
              (and (map? body) (not (contains? body :tag))
                   (not (contains? body :view-id)) (not (contains? body :html))
                   (contains? body :children)
                   (not (contains? body :key)))
              (let [c (:children body)] (when (seq c) c))
              :else [body])]
    (cond-> {:view-id view-id}
      (seq recorded) (assoc :props recorded)
      (seq chs)      (assoc :children chs))))

(defn html
  "Trusted-HTML node (`ui/html`) — the single escaping bypass; both
  emitters treat it identically."
  [s]
  (when-not (string? s)
    (error/throw-error!
     :rf.error/ui-tree-malformed 're-frame.ui/html
     "(ui/html x) requires a string" {:extra {:value s}}))
  {:html s})

(def opaque-fn
  "The single sentinel for non-data values (jvm-tree contract §The opaque
  marker)."
  {:rf.ui/opaque :fn})

(def opaque-foreign
  {:rf.ui/opaque :foreign})

(defn sanitize-prop
  "View-boundary :props sanitization (top-level values only): fns ->
  opaque :fn marker; ui/raw-marked and other host values pass through the
  emitted call-site markers; data stays data."
  [v]
  (if (fn? v) opaque-fn v))

(defn classify-event
  "Runtime classification of a DYNAMIC handler value for the :events map:
  vector -> the vector (data, verbatim); map -> the options map (data);
  fn -> the opaque :fn marker; nil -> nil (entry dropped)."
  [v]
  (cond
    (nil? v)    nil
    (vector? v) v
    (map? v)    v
    (fn? v)     opaque-fn
    :else (error/throw-error!
           :rf.error/ui-tree-malformed 're-frame.ui/render
           (str "a dynamic handler expression produced " (pr-str v)
                " — handlers classify by type: event vector, options map,"
                " handler fn, or nil")
           {:extra {:value v}})))

(defn jvm-host-op!
  "Raise the typed host-op error for host-bearing features hit on the JVM
  (Spec 004 §The JVM structural subset)."
  [op reason]
  (error/throw-error!
   :rf.error/jvm-host-op 're-frame.ui/render
   (str reason " — host-bearing features need mounted (Tier-3) tests")
   {:extra {:op op}}))

;; ---------------------------------------------------------------------------
;; Render entry
;; ---------------------------------------------------------------------------

(def tree-version
  "Node-schema version this emitter writes (jvm-tree contract
  §Versioning)."
  1)

(defn register-view!
  "Registrar `:view` entry for a compiled view — the JVM twin of
  `re-frame.ui.runtime/register-view!` (JVM builds are dev builds; no
  elision concern here)."
  [id view-fn manifest]
  (registrar/register! :view id (cond-> {:rf/id id
                                         :handler-fn view-fn
                                         :rf.ui/compiled? true
                                         :rf.ui/manifest manifest}
                                  (:doc manifest) (assoc :doc (:doc manifest))))
  view-fn)

(defn render
  "Invoke a compiled view fn (its JVM realisation) with `props` and stamp
  the root with :rf.ui/tree-version. The root is always the view's
  boundary node — a map. This is the entry `ui.test/render` (S1d)
  consumes."
  ([view] (render view {}))
  ([view props]
   (let [root (view props)]
     (assoc root :rf.ui/tree-version tree-version))))
