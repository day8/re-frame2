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
            [re-frame.interop :as interop]
            [re-frame.registrar :as registrar]
            [re-frame.ui.reactive :as reactive]
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
             ;; rf2-q9q9y — the EX-DATA-ONLY half of the crossing: this
             ;; message prints nothing, so nothing overflowed at the thrower
             ;; and a cyclic `row` rode out intact to explode at a downstream
             ;; logger / projector / trace sink instead. `error/safe-form`
             ;; closes it at the boundary that owns the value.
             {:extra (error/safe-form {:row row})}))
          (let [ks (rules/js-string-coerce (:key row))]
            (if-let [prev (get seen ks)]
              (error/throw-error!
               :rf.error/ui-duplicate-key 're-frame.ui/render
               ;; rf2-q9q9y — `:key` is caller-supplied and reaches here only
               ;; after `js-string-coerce` (`(str v)`, bounded on a foreign
               ;; value), so two cyclic keys COLLIDE and arrive raw. `prev` is
               ;; a previously-seen key and is equally caller-supplied, so the
               ;; whole `:extra` crosses rather than the `:key` slot alone.
               (str "duplicate key " (error/pr-form (:key row)) " in a keyed list "
                    "(keys compare after React's string coercion: key 1 "
                    "collides with key \"1\"). Keys must be unique per list site")
               {:extra (error/safe-form {:key (:key row) :collides-with prev})})
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

(defn- fold-dyn-entry
  "Fold one dynamic prop entry into the `[attr-map events prop-props]`
  accumulator via the rule table shared by `ui/spread`, `ui/spread-safe`, and
  the JVM tree builder: on-* -> events, :class merges, :style normalizes,
  :key/:ref skipped, custom-element `properties` classified. `properties` is
  the tag's property set (nil for plain DOM).

  Every key is FIRST put through `rules/assert-spread-prop-key!` — the JVM twin
  of the CLJS `runtime/convert-prop-map!` guard, the runtime half of the
  analyzer's literal rejected-spelling deny (rf2-5pr75). A LITERAL element's
  per-prop dynamic values also fold through here, but their keys are literal and
  the analyzer already rejected those spellings, so the guard can only fire on a
  key that arrived inside a runtime map."
  [properties [m es pp] k v]
  (rules/assert-spread-prop-key! k)
  (let [n (name k)]
    (cond
      (nil? v)                   [m es pp]
      (contains? #{:key :ref} k) [m es pp]
      (= k :class)               [(let [c (rules/classes-str
                                           [(get m :class) (rules/class-val v)])]
                                    (if c (assoc m :class c) (dissoc m :class)))
                                  es pp]
      (= k :style)               (if (map? v)
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
      :else                      [(let [v' (rules/attr-val-semantic k v)]
                                    (if (nil? v') m (assoc m k v')))
                                  es pp])))

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
    :spread-safe {:caller runtime-map :owned-handler-keys #{kw}} — the
             ui/spread-safe caller map: guarded against owned/structural keys
             in EVERY build, then folded UNDER the owned attrs (owned wins,
             :class composes owned-first)
    :children thunk building the children vector (bound ns context)"
  [tag {:keys [static norm dyn events dyn-events key? key-val props children spread-safe]}]
  ;; Property classification for DYNAMIC (`ui/spread`) props needs the tag's
  ;; declared properties at RENDER time. This builder is emitted into the view
  ;; fn (emit-jvm), so it runs with NO `cljs.env/*compiler*` bound — a pure
  ;; runtime read, not a compile read. Its correct source is therefore the
  ;; RUNTIME arm ledger `re-frame.ui.rules/custom-elements` (populated on this
  ;; host by the emitted `register-custom-element!`, keyed `[build-id ns-sym]`),
  ;; NOT the compile-time `build/elements` slice. With the process-global
  ;; compiler mirror removed (rf2-vxgfnd.91), that ledger is no longer clobbered
  ;; by a sibling build's contribution, so this render read is now stable.
  (let [properties (when (str/includes? (name tag) "-")
                     (rules/custom-element-properties tag))
        base    (reduce-kv (fn [m k v] (if (nil? v) m (assoc m k v)))
                           (or static {})
                           (or norm {}))
        [attrs evts prop-props]
        (reduce-kv (partial fold-dyn-entry properties)
                   [base {} (set (or props #{}))]
                   (or dyn {}))
        ;; ui/spread-safe: the caller attr map is guarded (owned-key deny in
        ;; EVERY build — not goog.DEBUG-gated) then folded UNDER the owned
        ;; attrs. Owned props WIN every collision (the caller can carry no
        ;; owned/structural key); :class composes owned-classes-first.
        [attrs evts prop-props]
        (if-let [{:keys [caller owned-handler-keys]} spread-safe]
          ;; assert-safe-caller! DENIES by canonical emitted slot AND returns the
          ;; canonicalized caller (each accepted key rewritten to its author
          ;; keyword), so an alternate spelling folds through the SAME slot as
          ;; the literal author keyword — `:ns/class` composes, an ordinary key
          ;; dedupes against an owned collision, host-parity with CLJS (rf2-xdvob).
          (let [caller (rules/assert-safe-caller! caller owned-handler-keys)
                [c-attrs c-evts c-pp]
                (reduce-kv (partial fold-dyn-entry properties)
                           [{} {} #{}]
                           (or caller {}))
                merged (merge c-attrs attrs)
                merged (if (and (:class c-attrs) (:class attrs))
                         (assoc merged :class
                                (rules/classes-str [(:class attrs) (:class c-attrs)]))
                         merged)]
            [merged (merge c-evts evts) (into prop-props c-pp)])
          [attrs evts prop-props])
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

(defn client-only-fallback
  "The JVM/SSR deterministic fallback node for `(ui/client-only {:fallback tpl}
  …)` — a fragment carrying the `:rf.ui/boundary :client-only` diagnostic marker
  (§004B; a droppable reserved key, stripped by semantic normalization). The
  JVM renders the capability-free fallback, never the browser-only client
  subtree."
  [& xs]
  (let [chs (apply children xs)]
    (cond-> {:rf.ui/boundary :client-only}
      (seq chs)       (assoc :children chs)
      (not (seq chs)) (assoc :children []))))

(defn presence
  "The JVM/SSR structural node for `(ui/presence {:timeout-ms n} …)`: a fragment
  carrying the `:rf.ui/presence {:phase :present :timeout-ms n}` diagnostic
  marker (§004B — the \"presence metadata exposed structurally\"; a droppable
  reserved key, stripped by semantic normalization). The JVM has no lifecycle,
  so every retained child renders `:present`."
  [timeout-ms & xs]
  (let [chs (apply children xs)]
    (cond-> {:rf.ui/presence {:phase :present :timeout-ms timeout-ms}}
      (seq chs)       (assoc :children chs)
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
              ;; fragment-rooted view: boundary adopts the fragment's children.
              ;; A marker-bearing fragment (`:rf.ui/boundary`, e.g. a root-level
              ;; ui/client-only fallback; or `:rf.ui/presence`, a root-level
              ;; ui/presence boundary) is NOT adopted — its marker is a
              ;; load-bearing child node that must survive as its own node.
              (and (map? body) (not (contains? body :tag))
                   (not (contains? body :view-id)) (not (contains? body :html))
                   (not (contains? body :rf.ui/boundary))
                   (not (contains? body :rf.ui/presence))
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

(def opaque-render-fn
  "The structural marker for a `ui/render-fn` value recorded on a
  view-boundary's :props — distinct from the plain opaque-fn so tools and
  ui.test can read a slot-carrying prop as a render-fn (the pre-declared
  `:ui/render-fn` member of the §004B opaque-marker set)."
  {:rf.ui/opaque :ui/render-fn})

;; ---------------------------------------------------------------------------
;; Compiled render slots — `ui/render-fn` + `ui/slot`
;;
;; The JVM twin of `re-frame.ui.runtime`'s render-slot vocabulary. A
;; `ui/render-fn` value wraps the compiled pure-render closure in a carrier
;; that `ui/slot` proves before invoking (a bare fn is never a slot). The
;; carrier is a plain record — NOT a node and NOT `fn?` — so it can neither
;; masquerade as tree content nor be sanitized as an ordinary opaque fn.
;; ---------------------------------------------------------------------------

(defrecord RenderFn [f arity])

(defn render-fn
  "Wrap a compiled pure-render closure as a `ui/render-fn` value, recording its
  fixed `arity` (the declared parameter count) so `check-slot-arity!` can enforce
  the arity host-identically."
  [f arity]
  (->RenderFn f arity))

(defn render-fn?
  "True when `x` is a compiled `ui/render-fn` value."
  [x]
  (instance? RenderFn x))

(defn invalid-slot!
  "The didactic error for a `ui/slot` value that is neither nil nor a
  `ui/render-fn`."
  [x]
  (error/throw-error!
   :rf.error/ui-tree-malformed 're-frame.ui/slot
   ;; rf2-q9q9y — the `tree` twin of `runtime/invalid-slot!`, crossed at the
   ;; same two halves so the two host arms cannot drift.
   (str "a ui/slot received " (error/pr-form x) " — a slot accepts only a "
        "ui/render-fn value (author it as (ui/render-fn [args…] template)) "
        "or nil (renders nothing)")
   {:extra (error/safe-form {:value x})}))

(defn slot-ready?
  "Gate a `ui/slot` value: nil renders nothing (false); a `ui/render-fn`
  renders (true); anything else is the didactic `invalid-slot!` error."
  [x]
  (cond
    (nil? x)       false
    (render-fn? x) true
    :else          (invalid-slot! x)))

(defn check-slot-arity!
  "Enforce the host-independent ui/slot ↔ ui/render-fn arity contract BEFORE
  invocation: a slot passing `argc` runtime args to a render-fn compiled with a
  different fixed arity is the didactic `:rf.error/ui-tree-malformed` (the arity
  twin of `invalid-slot!`) on BOTH hosts — neither leans on the native call
  quirk (JS silently drops surplus args; the JVM throws a raw ArityException)."
  [rf argc]
  (let [arity (:arity rf)]
    (when (not= arity argc)
      (error/throw-error!
       :rf.error/ui-tree-malformed 're-frame.ui/slot
       (str "a ui/slot passed " argc " argument" (when (not= 1 argc) "s")
            " to a ui/render-fn that declares " arity " parameter"
            (when (not= 1 arity) "s")
            " — a render-fn is a fixed-arity callback; the slot must pass exactly "
            "its declared parameters. Match the slot's argument count to the "
            "render-fn's parameter vector")
       {:extra {:expected arity :actual argc}}))))

(defn sanitize-prop
  "View-boundary :props sanitization (top-level values only): render-fn
  values -> the opaque :render-fn marker; other fns -> opaque :fn marker;
  ui/raw-marked and other host values pass through the emitted call-site
  markers; data stays data."
  [v]
  (cond
    (render-fn? v) opaque-render-fn
    (fn? v)        opaque-fn
    :else          v))

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
           ;; rf2-q9q9y — the throw is reached exactly when `v` is not
           ;; nil/vector/map/fn, i.e. when it IS a foreign object. Message
           ;; text is shared verbatim with `events/dynamic-handler`'s `:else`
           ;; arm, so both crossed in the same change.
           (str "a dynamic handler expression produced " (error/pr-form v)
                " — handlers classify by type: event vector, options map,"
                " handler fn, or nil")
           {:extra (error/safe-form {:value v})})))

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
  boundary node — a map. This is a public Tier-1 JVM render entry (S1d).

  Establish the JVM render-slice memo scope (rf2-vxgfnd.267) around the view
  thunk so a single render shares ONE slice memo — sibling cold probes of a
  shared derived parent compute it once, and a later render recomputes — exactly
  as the browser/Tier-3 path does. Re-entrant: a nested `with-capture` reuses the
  enclosing slice."
  ([view] (render view {}))
  ([view props]
   (reactive/with-slice-memo
     (fn []
       (let [root (view props)]
         (assoc root :rf.ui/tree-version tree-version))))))

(defn render-root-tree
  "Run a compiled ROOT-FORM tree `thunk` inside the JVM render-slice memo scope
  and stamp the versioned structural tree — the literal-root-form sibling of
  `render` (which takes a compiled view fn + props). `re-frame.ui/render-static`
  renders the server tree through here before `re-frame.ssr/emit-ui-tree`
  serialises it to an inert HTML string; the root node is the single mounted
  view's boundary (or the top-region wrapper enclosing it) the root form
  produced.

  Renders against the AMBIENT frame (the SSR host's surrounding `with-frame`); a
  frame-scoped read with no frame bound raises `:rf.error/no-frame-context` —
  honest, never defaulted. Re-entrant: a nested `with-capture` reuses the
  enclosing slice, exactly as `render` does."
  [thunk]
  (reactive/with-slice-memo
    (fn []
      (assoc (thunk) :rf.ui/tree-version tree-version))))

#?(:clj
   (defn resolve-ssr-emitter
     "Late-resolve `re-frame.ssr/emit-ui-tree` on the JVM — the SSR artefact's
     pure tree -> HTML serialiser. Returns the resolved var (loading
     `re-frame.ssr` on demand if it is on the classpath but not yet required),
     or nil when the artefact is absent. The `hydrate-root` late-require
     precedent: `re-frame.ui` takes NO static require on `re-frame.ssr`, so a
     server namespace that requires only `re-frame.ui` still renders (the emitter
     is loaded here, at render time), and Spec 011's `ui -> ssr` static wall
     stays intact. Extracted as its own seam so the artefact-absent branch is
     drivable in a test."
     []
     (try
       (requiring-resolve 're-frame.ssr/emit-ui-tree)
       (catch java.io.FileNotFoundException _ nil))))

#?(:clj
   (defn- strip-host-root-annotation
     "Recursively drop the DEV host-root view-evidence annotation
     (`:data-rf2-source-coord` / `:data-rf-view` — the compiler-emit stamp,
     rf2-hac8p) from a JVM structural `tree`. `render-static` is the pure
     `:server`, NON-hydrating static-HTML path (Spec 011 §Phase flip): it carries
     NO manifest, NO hydration payload, and NO dev / adoption markers, so the
     compiler's DEBUG-gated host-root annotation — present in the tree the
     compiled views build (matching the client render, which the SSR-then-hydrate
     `render-to-string` hiccup path relies on) — is stripped HERE before the pure
     static serialisation, exactly as `goog.DEBUG=false` / `-Dre-frame.debug=false`
     erases it in a production build. Only this render-static seam strips it; the
     hydrating `render-to-string` path (a separate `re-frame.ssr.emit` hiccup
     serialiser) is untouched.

     PROVENANCE (rf2-zgezz #3): removal is scoped by the out-of-band provenance
     metadata `:rf.ui/host-root-annotation` the compiler attaches to a host-root
     element node (`emit-jvm/with-host-root-provenance`) — a `{key -> canonical
     value}` map. A marker key is dropped ONLY where the node's current attr value
     still equals the compiler's canonical value; a PROGRAMMER-AUTHORED own value
     of the same spelling (rf2-x1nbv collision law) differs and is PRESERVED, and a
     node with no provenance metadata (any nested authored element) is left
     untouched. The blunt recursive `dissoc` this replaced deleted authored nested
     attributes of the same spelling."
     [node]
     (if (map? node)
       (let [prov (:rf.ui/host-root-annotation (meta node))
             node (if (and prov (:attrs node))
                    (let [a (reduce-kv (fn [a k canon]
                                         (if (= (get a k ::absent) canon) (dissoc a k) a))
                                       (:attrs node) prov)]
                      (if (seq a) (assoc node :attrs a) (dissoc node :attrs)))
                    node)]
         (cond-> node
           (:children node) (update :children #(mapv strip-host-root-annotation %))))
       node)))

#?(:clj
   (defn emit-static-html
     "Fold a JVM structural `tree` to an inert HTML string through the SSR
     artefact's `re-frame.ssr/emit-ui-tree`, resolved LATE (see
     `resolve-ssr-emitter`). This is the runtime seam `re-frame.ui/render-static`
     emits a call to instead of naming `re-frame.ssr/emit-ui-tree` directly — a
     direct emit compiled a hard `re-frame.ssr` reference into the caller's
     namespace, so a documented render-static call in a namespace that required
     only `re-frame.ui` failed to COMPILE with a raw `ClassNotFoundException:
     re-frame.ssr`. When the `day8/re-frame2-ssr` artefact is absent from the
     server classpath this raises the ruled, typed
     `:rf.error/ssr-artefact-missing` naming the artefact — never a raw host
     exception, never a fallback.

     The DEV host-root view-evidence annotation (rf2-hac8p) is stripped from the
     tree first (see `strip-host-root-annotation`): render-static is the pure,
     non-hydrating static-HTML path and carries no dev / adoption markers.

     The strip is BYPASSED ENTIRELY when `interop/debug-enabled?` is false
     (rf2-zgezz #4): the compiler emits no annotation and no provenance metadata in
     that build, so there is nothing to remove — the whole recursive clone/walk is
     skipped and the tree reaches the serialiser untouched (identical root/children
     identities)."
     [tree]
     (if-let [emit (resolve-ssr-emitter)]
       (emit (cond-> tree interop/debug-enabled? strip-host-root-annotation))
       (error/throw-error!
        :rf.error/ssr-artefact-missing
        'ui/render-static
        (str "re-frame.ui/render-static requires day8/re-frame2-ssr on the "
             "classpath; add it to deps and require re-frame.ssr at app boot.")
        {:extra {:maven "day8/re-frame2-ssr" :require-ns "re-frame.ssr"}}))))
