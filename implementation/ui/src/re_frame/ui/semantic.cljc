(ns re-frame.ui.semantic
  "Semantic normalization `N` — the parity/fingerprint INPUT (owning
  contract: jvm-tree-and-conversion-contract.md §Semantic normalization
  N; consumed by the S1f parity corpus now, by `re-frame.ssr/
  ui-tree-fingerprint` at S5).

  `(normalize tree)` maps a version-1 structural tree (the value
  `re-frame.ui.tree/render` / `ui.test/render` returns) to the
  SEMANTIC-NODE space — the exact input to normalized structural
  equivalence and the render fingerprint. Pinned, in order:

    1. strip every `:rf.ui/*` reserved key (diagnostics never reach a
       fingerprint);
    2. splice view-boundary nodes (HTML has no view boundaries);
    3. splice fragment nodes;
    4. drop `:events` entirely and drop `:key` values (neither has HTML
       presence; keyed ORDER survives as the child order itself);
    5. per element, convert to FINAL attribute space via the conversion
       table: final attribute names (`rules/dom-attr-name`), serialised
       values (booleans -> presence/absence or \"true\"/\"false\" per
       their class; style -> a MAP of css-property -> value string,
       compared order-insensitively; class as the exact canonical
       string), the form-control special forms (input `default-*`,
       textarea value -> text child, select value -> `selected` on the
       matching option), and property-classified custom-element props
       omitted (they never reach markup);
    6. coalesce adjacent text again post-splice (text is compared
       DECODED — escaping is a serialisation concern);
    7. carry trusted-HTML nodes as opaque raw-markup leaves
       `{:html s}`, compared verbatim.

  A semantic node is `{:ns … :tag … :attrs {final-name-string ->
  serialised-value} :children [node-or-string …]}` — attribute maps
  order-insensitive, child vectors order-significant. `normalize`
  returns a VECTOR of semantic roots (a spliced boundary/fragment root
  may yield zero or several).

  Fingerprint input = the canonical-EDN serialisation of `N(tree)`; the
  hash algorithm/encoding stay owned by Spec 011/008."
  (:require [clojure.string :as str]
            [re-frame.error :as error]
            [re-frame.ui.rules :as rules]))

;; ---------------------------------------------------------------------------
;; Attr-value serialisation (step 5 — the value half)
;; ---------------------------------------------------------------------------

(defn- str-val
  "Tree semantic values are canonical strings already (numbers went
  through JS ToString at tree build); coerce defensively."
  [v]
  (if (string? v) v (rules/js-string-coerce v)))

(defn- truthy-attr?
  "React's boolean-attribute presence test over TREE-SPACE values:
  `true` -> present; `false` -> absent; a non-empty string is present
  (probed: `hidden \"until-found\"` renders bare `hidden=\"\"` on React
  19.2.0 — the boolean row's corrected shape)."
  [v]
  (cond
    (boolean? v) v
    (string? v)  (not (str/blank? v))
    :else        (some? v)))

(defn- serialise-attr
  "One author-space `[k v]` -> `[final-name serialised-value]` or nil
  (attribute absent from markup)."
  [k v]
  (let [n         (name k)
        collapsed (str/lower-case (str/replace n "-" ""))]
    (cond
      (str/starts-with? n "aria-")
      ;; aria-* values ALWAYS stringify — :aria-hidden false ->
      ;; aria-hidden="false", never omitted
      [n (if (boolean? v) (str v) (str-val v))]

      (str/starts-with? n "data-")
      ;; data-* verbatim names; boolean values stringify
      [n (if (boolean? v) (str v) (str-val v))]

      (contains? rules/booleanish-attrs collapsed)
      ;; true/false -> "true"/"false", never omitted
      [(rules/dom-attr-name n) (if (boolean? v) (str v) (str-val v))]

      (contains? rules/overloaded-boolean-attrs collapsed)
      ;; true -> bare presence, false -> omitted, other values stringify
      (cond
        (true? v)  [(rules/dom-attr-name n) ""]
        (false? v) nil
        :else      [(rules/dom-attr-name n) (str-val v)])

      (contains? rules/boolean-attrs collapsed)
      ;; presence/absence; presence serialises as attr=""
      (when (truthy-attr? v)
        [(rules/dom-attr-name n) ""])

      (boolean? v)
      ;; a boolean on a non-boolean-class attribute never reaches markup
      ;; (React drops it; dev warns client-side)
      nil

      :else
      [(rules/dom-attr-name n) (str-val v)])))

(defn- style->semantic
  "Tree `:style` (kw -> canonical css string) -> the order-insensitive
  {css-name-string value-string} map."
  [style]
  (into {} (map (fn [[k v]] [(name k) v])) style))

;; ---------------------------------------------------------------------------
;; Elements (step 5 — names, values, form controls, custom elements)
;; ---------------------------------------------------------------------------

(defn- custom-element-tag? [tag]
  (str/includes? (name tag) "-"))

(declare norm-nodes norm-nodes*)

(defn- final-attrs
  [attrs property-props]
  (reduce-kv
   (fn [m k v]
     (cond
       (contains? property-props k) m           ; never reach markup
       (= k :class) (assoc m "class" (str-val v))
       (= k :style) (let [s (style->semantic v)]
                      (if (seq s) (assoc m "style" s) m))
       :else (if-let [[n sv] (serialise-attr k v)]
               (assoc m n sv)
               m)))
   {}
   attrs))

(defn- node-text
  "Concatenated text content of a SEMANTIC node (option-label matching)."
  [node]
  (apply str (map #(if (string? %) % (node-text %)) (:children node))))

(defn- mark-selected-options
  "The `:value`-on-`:select` row: `selected` lands on the option(s)
  whose value (their `value` attribute, else their text content)
  matches, compared in the string space."
  [children sel-val]
  (mapv (fn [c]
          (if (and (map? c) (= :option (:tag c)))
            (let [ov (or (get-in c [:attrs "value"]) (node-text c))]
              (if (= ov sel-val)
                (assoc-in c [:attrs "selected"] "")
                c))
            (if (and (map? c) (:children c))
              (assoc c :children (mark-selected-options (:children c) sel-val))
              c)))
        children))

(defn- norm-element [el]
  (let [tag   (:tag el)
        pp    (if (custom-element-tag? tag)
                (set (or (:rf.ui/property-props el) #{}))
                #{})
        attrs (or (:attrs el) {})
        ;; form-control pre-pass: default-value/default-checked
        ;; serialise as value/checked ([S1-CONFIRM] row 8)
        attrs (reduce (fn [m [from to]]
                        (if (contains? m from)
                          (-> m (dissoc from) (assoc to (get m from)))
                          m))
                      attrs
                      [[:default-value :value] [:default-checked :checked]])
        textarea? (= tag :textarea)
        select?   (= tag :select)
        ;; textarea value -> the element's TEXT CHILD; select value ->
        ;; `selected` on the matching option(s) — neither serialises as
        ;; a `value` attribute ([S1-CONFIRM] row 8)
        ta-val    (when textarea? (get attrs :value))
        sel-val   (when select? (get attrs :value))
        attrs     (cond-> attrs
                    (or textarea? select?) (dissoc :value))
        fa        (final-attrs attrs pp)
        chs       (norm-nodes (:children el))
        chs       (cond
                    (some? ta-val)  [(str-val ta-val)]
                    (some? sel-val) (mark-selected-options chs (str-val sel-val))
                    :else chs)]
    (cond-> {:tag tag}
      (:ns el)   (assoc :ns (:ns el))
      (seq fa)   (assoc :attrs fa)
      (seq chs)  (assoc :children chs))))

;; ---------------------------------------------------------------------------
;; The walk (steps 1-4, 6, 7)
;; ---------------------------------------------------------------------------

(defn- norm-node
  "-> vector of semantic nodes/strings this tree node contributes."
  [node]
  (cond
    (string? node) [node]
    (map? node)
    (cond
      (contains? node :tag)     [(norm-element node)]
      (contains? node :html)    [{:html (:html node)}]
      ;; view boundaries + fragments SPLICE — children replace the node
      (or (contains? node :view-id) (contains? node :children))
      (norm-nodes* (:children node))
      :else [])
    :else
    (error/throw-error!
     :rf.error/ui-tree-malformed 're-frame.ui.semantic/normalize
     (str "malformed tree node in normalization N: " (pr-str node))
     {:extra {:value node}})))

(defn- norm-nodes*
  [children]
  (into [] (mapcat norm-node) children))

(defn norm-nodes
  "Normalize + canonicalize a children vector: splice, then coalesce
  adjacent text runs and drop empties (step 6)."
  [children]
  (let [vs (norm-nodes* children)
        coalesced (reduce (fn [acc v]
                            (if (and (string? v) (string? (peek acc)))
                              (conj (pop acc) (str (peek acc) v))
                              (conj acc v)))
                          [] vs)]
    (into [] (remove #(and (string? %) (= "" %))) coalesced)))

(defn normalize
  "`N(tree)` — the semantic-node projection of a version-1 structural
  tree. Returns the VECTOR of semantic roots in document order."
  [tree]
  (norm-nodes [tree]))
