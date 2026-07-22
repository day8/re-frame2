(ns re-frame.freehand.tree
  "The INTERPRETED structural walk — unrestricted Hiccup from a view body
  in, the versioned semantic structural tree out.

  This is one of Freehand's two emitters, and the one that runs on both
  hosts. On the JVM it is the whole render: a declaration goes in and a
  plain, serialisable value comes out, which the SSR seam turns into
  markup and a structural test asserts against. In ClojureScript it runs
  identically and answers the same value — which is what makes the
  cross-host conformance corpus a real claim rather than a JVM claim with
  a `.cljc` extension.

  The sibling emitter, [[re-frame.freehand.react]], produces React
  elements for the browser. The two are **separate walks by design**
  (EP-0036 governing law 7): they share the pure rules in
  [[re-frame.freehand.conversion]] and they prove their agreement through
  common conformance values, but neither is implemented in terms of the
  other. Divergence between them is detected, not prevented — and the
  point of one owning contract is to give two separate implementations one
  thing to be separate against.

  ## What the walk interprets

  Everything a view body can produce, with no finite grammar and no
  compile step:

    - a **keyword head** is a DOM or custom element, with `.class#id`
      sugar, author-space attributes, handler sites, and `:key`;
    - a **declared-view head** is an internal boundary — the walk
      normalizes the call, runs the view body, and records the expansion
      inside a view-boundary node;
    - `[:<> …]` is a fragment;
    - strings and numbers are text, seqs splice, `nil`/`false`/`true`
      vanish, and adjacent text coalesces.

  Nothing here subscribes, dispatches, mounts or schedules. A view body
  that reads state is F2's; this walk is a pure function of its argument,
  which is why two calls on one declaration are indistinguishable from
  one.

  Normative owner:
  [`spec/004B-UI-Tree-and-Conversion.md`](../../../../../spec/004B-UI-Tree-and-Conversion.md)."
  (:require [re-frame.error :as error]
            [re-frame.freehand :as v]
            [re-frame.freehand.conversion :as conv]))

#?(:clj (set! *warn-on-reflection* true))

(def tree-version
  "The structural-tree schema version this walk emits, carried on the root
  node as `:rf.ui/tree-version`. Every consumer validates it FIRST, before
  any traversal (Spec 004B §Versioning)."
  1)

(def fragment-tag
  "The one authored spelling of a fragment head."
  :<>)

(defn- malformed!
  [where reason extra]
  (error/throw-error!
    :rf.error/ui-tree-malformed
    where
    reason
    {:recovery :no-recovery :extra extra}))

(defn- shape
  "A bounded SHAPE summary of an offending value — never the value itself
  (Spec 015 §Data-Classification)."
  [x]
  (error/diag-value-summary x))

(defn- type-name [x]
  (name (:type (shape x))))

;; ---------------------------------------------------------------------------
;; Children — canonical form
;; ---------------------------------------------------------------------------
;;
;; Spec 004B §Child normalization. One vector, document order, adjacent text
;; coalesced, empties dropped, seqs flattened in place. The rules run HERE,
;; once, so no node can be built in a non-canonical shape: one semantic tree
;; has exactly one representation, and that is what makes the tree a
;; legitimate fingerprint and equality input.

(declare ^:private node)

(defn- conj-text
  "Append text, coalescing it into the preceding run when there is one."
  [acc s]
  (if (string? (peek acc))
    (conj (pop acc) (str (peek acc) s))
    (conj acc s)))

(defn- collect [ctx acc form]
  (cond
    (nil? form)     acc
    (boolean? form) acc
    (string? form)  (conj-text acc form)
    (number? form)  (conj-text acc (conv/js-number-str form))
    (conv/child-run? form) (reduce (fn [a f] (collect ctx a f)) acc form)
    (vector? form)  (conj acc (node ctx form))
    (seq? form)     (reduce (fn [a f] (collect ctx a f)) acc form)
    :else
    (malformed!
      're-frame.freehand.tree/render
      (str "A view body produced a " (type-name form) " where a child was expected. "
           "A child is markup (a vector), text (a string or a number), a seq of "
           "children, or nothing (nil / false).")
      {:value (shape form)})))

(defn- children
  "The canonical children vector for `forms` — empty when the forms carry
  no content."
  [ctx forms]
  (into []
        (remove #(and (string? %) (= "" %)))
        (reduce (fn [acc f] (collect ctx acc f)) [] forms)))

;; ---------------------------------------------------------------------------
;; Element nodes
;; ---------------------------------------------------------------------------

(defn- attr-entry
  "One author-space `:attrs` entry, or nil when the entry is dropped."
  [tag k v]
  (when (some? v)
    (let [semantic (conv/attr-value v)]
      (when (= ::conv/reject semantic)
        (malformed!
          're-frame.freehand.tree/render
          (str "The " k " attribute on " tag " carries a " (type-name v)
               ", which has no attribute spelling. An attribute value is a string, a "
               "keyword, a symbol, a number or a boolean; :class and :style have their "
               "own richer grammars.")
          {:attr k :value (shape v)}))
      [k semantic])))

(defn- style-map
  [tag v]
  (when-not (map? v)
    (malformed!
      're-frame.freehand.tree/render
      (str "The :style value on " tag " is a " (type-name v)
           "; :style is a map of CSS property to value.")
      {:attr :style :value (shape v)}))
  (reduce-kv (fn [m k x]
               (if (nil? x)
                 m
                 (let [css-name (name k)]
                   (assoc m (keyword css-name) (conv/css-value css-name x)))))
             {} v))

(defn- class-value
  "The canonical `:class` string for an element: sugar classes FIRST in
  source order, then the explicit `:class` form's classes, joined with no
  de-duplication (Spec 004B §`.class#id` sugar vs explicit `:class`)."
  [tag sugar-classes v]
  (let [parts (conv/class-parts v)]
    (when (= ::conv/reject parts)
      (malformed!
        're-frame.freehand.tree/render
        (str "The :class value on " tag " is outside the class grammar. Write a string, "
             "a keyword, a vector of them in order, or a flag map whose truthy entries "
             "name classes.")
        {:attr :class :value (shape v)}))
    (conv/class-string (into (vec sugar-classes) parts))))

(defn- event-entry
  "One `:events` entry, classified BY THE VALUE PRESENT AT RENDER
  (Spec 004B §Element fields): a vector is a literal event intent, a map is
  an options map, a function is a site whose spelling is testable and whose
  behaviour is not, and nil drops the entry."
  [tag k v]
  (cond
    (nil? v)    nil
    (vector? v) [k v]
    (map? v)    [k v]
    (fn? v)     [k {:rf.ui/opaque :fn}]
    :else
    (malformed!
      're-frame.freehand.tree/render
      (str "The " k " handler site on " tag " carries a " (type-name v)
           ". A handler is an event vector, an options map carrying one, or a function.")
      {:attr k :value (shape v)})))

(defn- split-attrs
  "Split one authored attribute map into `[attrs events]` — the two key
  domains are disjoint by construction, because every `on-*` name routes to
  `:events` and nothing else does."
  [tag sugar-classes sugar-id attrs]
  (reduce-kv
    (fn [[as es] k raw]
      (cond
        (= :key k)              [as es]
        (conv/handler-key? k)   [as (if-let [e (event-entry tag k raw)] (conj es e) es)]
        (= :class k)            [(if-let [c (class-value tag sugar-classes raw)]
                                   (assoc as :class c)
                                   as)
                                 es]
        (= :style k)            [(let [s (style-map tag raw)]
                                   (if (seq s) (assoc as :style s) as))
                                 es]
        (= :id k)               (do (when (and sugar-id (some? raw))
                                      (malformed!
                                        're-frame.freehand.tree/render
                                        (str "The element " tag " spells its id twice — once as #"
                                             sugar-id " sugar and once as :id. Two id spellings on "
                                             "one element is an ambiguity; keep one.")
                                        {:attr :id :value (shape raw)}))
                                    [(if-let [e (attr-entry tag k raw)] (conj as e) as) es])
        :else                   [(if-let [e (attr-entry tag k raw)] (conj as e) as) es]))
    [(cond-> {}
       (and (seq sugar-classes) (not (contains? attrs :class)))
       (assoc :class (conv/class-string sugar-classes))
       sugar-id (assoc :id sugar-id))
     {}]
    attrs))

(defn- element-node
  [ctx tag-kw args]
  (when (namespace tag-kw)
    (malformed!
      're-frame.freehand.tree/render
      (str "The element head " tag-kw " is namespaced. An element tag is an unqualified "
           "keyword — its namespace would be silently dropped on the way to the DOM.")
      {:value (shape tag-kw)}))
  (let [{:keys [tag classes id]} (conv/parse-tag tag-kw)
        _          (when (= "" (name tag))
                     (malformed!
                       're-frame.freehand.tree/render
                       (str "The element head " tag-kw " carries only .class#id sugar and no tag.")
                       {:value (shape tag-kw)}))
        el-ns      (conv/element-ns (conv/enter-ns ctx tag))
        attrs?     (map? (first args))
        authored   (if attrs? (first args) nil)
        kid-forms  (if attrs? (rest args) args)
        [attrs es] (split-attrs tag classes id (or authored {}))
        k          (:key authored)
        kids       (children (conv/child-ns (conv/enter-ns ctx tag) tag attrs) kid-forms)]
    (when (and (seq kids) (contains? conv/children-rejected-tags tag))
      (malformed!
        're-frame.freehand.tree/render
        (str "The element " tag " cannot have children — it is a void element, and React "
             "throws rather than render one. Put the content in an attribute, or use an "
             "element that takes children.")
        {:tag tag :children-count (count kids)}))
    (cond-> {:tag tag}
      el-ns       (assoc :ns el-ns)
      (seq attrs) (assoc :attrs attrs)
      (seq es)    (assoc :events es)
      (some? k)   (assoc :key k)
      (seq kids)  (assoc :children kids))))

;; ---------------------------------------------------------------------------
;; Fragment nodes
;; ---------------------------------------------------------------------------

(defn- fragment-node
  [ctx args]
  (let [attrs? (map? (first args))
        k      (when attrs? (:key (first args)))
        kids   (children ctx (if attrs? (rest args) args))]
    ;; A fragment RETAINS `:children []` when empty — `:children` is its
    ;; required discriminator, so `{}` would be a malformed node rather than
    ;; an empty fragment (Spec 004B §Canonical uniqueness).
    (cond-> {:children kids}
      (some? k) (assoc :key k))))

;; ---------------------------------------------------------------------------
;; View-boundary nodes
;; ---------------------------------------------------------------------------

(defn- recorded-prop
  "A prop value as the boundary records it. Non-data values become the
  opaque marker — their existence and spelling stay testable, their
  behaviour does not (Spec 004B §The opaque marker)."
  [x]
  (if (fn? x) {:rf.ui/opaque :fn} x))

(defn- plain-fragment?
  "Is `x` a fragment node carrying nothing but its children?"
  [x]
  (and (map? x)
       (contains? x :children)
       (not (contains? x :tag))
       (not (contains? x :view-id))
       (not (contains? x :html))
       (not (contains? x :key))))

(defn- boundary-node
  "Wrap one internal-view expansion. The boundary is a REAL node, nesting
  recursively, so a view stays addressable in the tree whatever it renders
  — including a view that renders nothing.

  `:props` records the props the body received MINUS `:children`: the
  children are structural, and they are already visible as the expansion
  the body built from them.

  A fragment-rooted view is ADOPTED — its children become the boundary's
  children rather than sitting inside a redundant fragment — so a
  fragment-rooted view is a boundary with several children and a
  nil-rooted view is a boundary with none. Both stay matchable, which is
  what makes a `:view-id` predicate a total selector (Spec 004B §Coverage
  Q12)."
  [ctx view args]
  (let [{:keys [key props]} (v/normalize-call view args)
        recorded (reduce-kv (fn [m k x] (assoc m k (recorded-prop x)))
                            {} (dissoc props :children))
        body     ((v/render-body view) (conv/forward-children props))
        rendered (children ctx [body])
        kids     (if (and (= 1 (count rendered)) (plain-fragment? (first rendered)))
                   (:children (first rendered))
                   rendered)]
    (cond-> {:view-id (:view-id (v/describe view))}
      (seq recorded) (assoc :props recorded)
      (some? key)    (assoc :key key)
      (seq kids)     (assoc :children kids))))

;; ---------------------------------------------------------------------------
;; The walk
;; ---------------------------------------------------------------------------

(defn- node
  [ctx form]
  (let [head (first form)]
    (if (= fragment-tag head)
      (fragment-node ctx (rest form))
      (case (v/classify-head head)
        :element (element-node ctx head (rest form))
        :view    (boundary-node ctx head (rest form))
        :host    (malformed!
                   're-frame.freehand.tree/render
                   (str "A declared host descriptor is a legal vector head, but the v1 node set "
                        "carries no host variant — a foreign boundary's structural and SSR "
                        "policy is declared at the host boundary, and that declaration lands "
                        "with the host-lifecycle slice.")
                   {:value (shape head)})))))

(defn render
  "Interpret `form` and answer its versioned structural tree.

  The return is always the ROOT node — a map — carrying
  `:rf.ui/tree-version`. A form that denotes text, several nodes, or
  nothing is rooted in a fragment, which is the node variant whose whole
  job is to hold a run of children; interior nodes carry no version and
  inherit their tree's.

      (render [:section.panel {:id \"main\"} [:h2 \"Details\"]])
      ;; => {:tag :section
      ;;     :attrs {:class \"panel\" :id \"main\"}
      ;;     :children [{:tag :h2 :children [\"Details\"]}]
      ;;     :rf.ui/tree-version 1}

  The value is plain Clojure data — maps and strings, no wrapper types, no
  metadata-carried contract — so it prints and reads back losslessly, and
  `(tree-seq map? :children tree)` is the whole traversal API."
  [form]
  (let [kids (children nil [form])
        root (if (and (= 1 (count kids)) (map? (first kids)))
               (first kids)
               {:children kids})]
    (assoc root :rf.ui/tree-version tree-version)))
