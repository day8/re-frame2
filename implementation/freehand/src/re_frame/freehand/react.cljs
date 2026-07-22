(ns re-frame.freehand.react
  "The INTERPRETED React emitter — unrestricted Hiccup from a view body in,
  real React elements out.

  This is Freehand's browser half. Its sibling,
  [[re-frame.freehand.tree]], answers the versioned structural tree on
  either host; this one answers `react/createElement` output, and only in
  ClojureScript.

  The two are **separate walks by design** (EP-0036 governing law 7). They
  share the pure rules in [[re-frame.freehand.conversion]] — one tag
  parser, one class composer, one style canonicaliser, one number
  formatter — but neither emitter is written in terms of the other, and
  neither is derived from the other's output. Their agreement is proven,
  not assumed: the structural corpus pins what the tree says, and a
  mounted browser assertion pins what the DOM does, over the same
  declarations.

  ## What this slice emits

    - **elements** — `.class#id` sugar merged, attributes in React's
      spelling, `:key` lifted into the element's key, children passed as
      varargs so React needs no synthetic keys for a literal run;
    - **view boundaries** — a real React function component per declared
      view, memoized by descriptor identity, so React sees a boundary and
      DevTools sees a name;
    - **fragments**, **text**, spliced seqs, and the same dropped
      `nil`/`false`/`true`.

  ## What this slice deliberately does NOT emit

  **Handler sites carrying event INTENT.** A `:on-*` whose value is a
  function is attached as an ordinary React handler; a `:on-*` carrying an
  event vector or an options map is recorded in the structural tree and
  materialized by the reactive slice, which owns the projection
  materializer, the listener options, and — decisively — which frame the
  intent is dispatched into. Attaching a listener here would mean choosing
  that frame before the contract that chooses it exists.

  Normative owner:
  [`spec/004B-UI-Tree-and-Conversion.md`](../../../../../spec/004B-UI-Tree-and-Conversion.md)."
  (:require ["react" :as react]
            [goog.object :as gobj]
            [re-frame.error :as error]
            [re-frame.freehand :as v]
            [re-frame.freehand.conversion :as conv]
            [re-frame.freehand.tree :as tree]))

(defn- malformed!
  [reason extra]
  (error/throw-error!
    :rf.error/ui-tree-malformed
    're-frame.freehand.react/element
    reason
    {:recovery :no-recovery :extra extra}))

(defn- shape [x] (error/diag-value-summary x))
(defn- type-name [x] (name (:type (shape x))))

;; ---------------------------------------------------------------------------
;; Props
;; ---------------------------------------------------------------------------

(defn- react-style
  [tag v]
  (when-not (map? v)
    (malformed! (str "The :style value on " tag " is a " (type-name v)
                     "; :style is a map of CSS property to value.")
                {:attr :style :value (shape v)}))
  (reduce-kv (fn [o k x]
               (if (nil? x)
                 o
                 (let [css-name (name k)]
                   (gobj/set o (conv/react-style-name css-name)
                             (conv/css-value css-name x))
                   o)))
             #js {} v))

(defn- put-attr!
  [o ns-ctx tag k raw]
  (let [semantic (conv/attr-value raw)]
    (when (= ::conv/reject semantic)
      (malformed! (str "The " k " attribute on " tag " carries a " (type-name raw)
                       ", which has no attribute spelling. An attribute value is a string, a "
                       "keyword, a symbol, a number or a boolean; :class and :style have their "
                       "own richer grammars.")
                  {:attr k :value (shape raw)}))
    (gobj/set o (conv/react-attr-name ns-ctx k) semantic)))

(defn- react-props
  "The React props object for one element: the author attribute map in
  React's spelling, with the `.class#id` sugar merged in and handler sites
  resolved per the emitter's stated coverage."
  [ns-ctx tag sugar-classes sugar-id attrs]
  (let [o (js-obj)]
    (when-let [c (conv/class-string sugar-classes)]
      (gobj/set o "className" c))
    (when sugar-id
      (gobj/set o "id" sugar-id))
    (doseq [[k raw] attrs]
      (cond
        (= :key k)   nil
        (nil? raw)   nil

        (conv/handler-key? k)
        (when (fn? raw)
          (gobj/set o (conv/react-event-name k) raw))

        (= :class k)
        (let [parts (conv/class-parts raw)]
          (when (= ::conv/reject parts)
            (malformed! (str "The :class value on " tag " is outside the class grammar. Write a "
                             "string, a keyword, a vector of them in order, or a flag map whose "
                             "truthy entries name classes.")
                        {:attr :class :value (shape raw)}))
          (if-let [c (conv/class-string (into (vec sugar-classes) parts))]
            (gobj/set o "className" c)
            (gobj/remove o "className")))

        (= :style k)
        (gobj/set o "style" (react-style tag raw))

        (= :id k)
        (do (when (and sugar-id (some? raw))
              (malformed! (str "The element " tag " spells its id twice — once as #" sugar-id
                               " sugar and once as :id. Two id spellings on one element is an "
                               "ambiguity; keep one.")
                          {:attr :id :value (shape raw)}))
            (put-attr! o ns-ctx tag k raw))

        :else (put-attr! o ns-ctx tag k raw)))
    (when-let [k (:key attrs)]
      (gobj/set o "key" k))
    o))

;; ---------------------------------------------------------------------------
;; Declared views become real React components
;; ---------------------------------------------------------------------------

(declare ^:private emit)

(def ^:private components
  "Descriptor -> React component. Keyed by descriptor IDENTITY, so a
  redeclared view (a reload) mints a new component and React remounts the
  boundary rather than reusing a stale body."
  (atom {}))

(defn- component-for
  [view]
  (or (get @components view)
      (let [view-id (:view-id (v/describe view))
            body    (v/render-body view)
            c       (fn freehand-view [js-props]
                      (emit nil (body (conv/forward-children
                                        (gobj/get js-props "props")))))]
        (gobj/set c "displayName" (str view-id))
        (swap! components assoc view c)
        c)))

;; ---------------------------------------------------------------------------
;; The walk
;; ---------------------------------------------------------------------------

(declare ^:private children)

(defn- element-node
  [ns-ctx tag-kw args]
  (when (namespace tag-kw)
    (malformed! (str "The element head " tag-kw " is namespaced. An element tag is an unqualified "
                     "keyword — its namespace would be silently dropped on the way to the DOM.")
                {:value (shape tag-kw)}))
  (let [{:keys [tag classes id]} (conv/parse-tag tag-kw)
        own-ns    (conv/enter-ns ns-ctx tag)
        attrs?    (map? (first args))
        attrs     (if attrs? (first args) {})
        kid-forms (if attrs? (rest args) args)
        kids      (children (conv/child-ns own-ns tag attrs) kid-forms)]
    (when (and (seq kids) (contains? conv/children-rejected-tags tag))
      (malformed! (str "The element " tag " cannot have children — it is a void element, and "
                       "React throws rather than render one. Put the content in an attribute, "
                       "or use an element that takes children.")
                  {:tag tag :children-count (count kids)}))
    (apply react/createElement
           (name tag)
           (react-props (conv/element-ns own-ns) tag classes id attrs)
           kids)))

(defn- fragment-node
  [ns-ctx args]
  (let [attrs? (map? (first args))
        k      (when attrs? (:key (first args)))
        kids   (children ns-ctx (if attrs? (rest args) args))]
    (apply react/createElement
           react/Fragment
           (when (some? k) #js {:key k})
           kids)))

(defn- boundary-node
  [view args]
  (let [{:keys [key props]} (v/normalize-call view args)
        js-props (js-obj "props" props)]
    (when (some? key)
      (gobj/set js-props "key" key))
    (react/createElement (component-for view) js-props)))

(defn- node
  [ns-ctx form]
  (let [head (first form)]
    (if (= tree/fragment-tag head)
      (fragment-node ns-ctx (rest form))
      (case (v/classify-head head)
        :element (element-node ns-ctx head (rest form))
        :view    (boundary-node head (rest form))
        :host    (malformed!
                   (str "A declared host descriptor is a legal vector head, but the React "
                        "emitter cannot cross one yet — the host boundary and its three host "
                        "shapes land with the host-lifecycle slice.")
                   {:value (shape head)})))))

(defn- collect [ns-ctx acc form]
  (cond
    (nil? form)     acc
    (boolean? form) acc
    (string? form)  (conj acc form)
    (number? form)  (conj acc (conv/js-number-str form))
    (conv/child-run? form) (reduce (fn [a f] (collect ns-ctx a f)) acc form)
    (vector? form)  (conj acc (node ns-ctx form))
    (seq? form)     (reduce (fn [a f] (collect ns-ctx a f)) acc form)
    :else
    (malformed!
      (str "A view body produced a " (type-name form) " where a child was expected. "
           "A child is markup (a vector), text (a string or a number), a seq of "
           "children, or nothing (nil / false).")
      {:value (shape form)})))

(defn- children
  [ns-ctx forms]
  (reduce (fn [acc f] (collect ns-ctx acc f)) [] forms))

(defn- emit
  [ns-ctx form]
  (let [kids (children ns-ctx [form])]
    (case (count kids)
      0 nil
      1 (first kids)
      (apply react/createElement react/Fragment nil kids))))

(defn element
  "Interpret `form` and answer the React element it denotes — or `nil` when
  it denotes nothing, or a fragment when it denotes several things.

      (element [my-panel {:title \"Details\"}])

  Pure: it builds elements and mounts nothing. Handing the result to
  `react-dom/client` is what puts it on a page."
  [form]
  (emit nil form))
