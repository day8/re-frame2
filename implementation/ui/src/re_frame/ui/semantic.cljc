(ns re-frame.ui.semantic
  "Semantic normalization `N` — the parity/fingerprint INPUT (owning
  contract: jvm-tree-and-conversion-contract.md §Semantic normalization
  N; consumed by the S1f parity corpus now, by `re-frame.ssr/
  ui-tree-fingerprint` at S5).

  `(normalize tree)` maps a version-1 structural tree (the value
  `re-frame.ui.tree/render` / `ui.test/render` returns) to the
  SEMANTIC-NODE space — the exact input to normalized structural
  equivalence and the render fingerprint. Pinned, in order:

    1. remove every `:rf.ui/*` reserved key from the OUTPUT (no reserved
       key reaches a fingerprint) — but `:rf.ui/property-props` is
       SEMANTIC conversion input, not a diagnostic: step 5 consumes it
       (property-classified custom-element props omitted) BEFORE the
       marker is dropped, so removing it up front changes the result;
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

(defn- norm-element [el path]
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
        chs       (norm-nodes (:children el) path)
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

(def ^:private node-discriminators
  "The three PRIMARY structural discriminators of a map tree node
  (jvm-tree contract §Node schema — the closed five-variant set). A
  canonical map node carries EXACTLY ONE of these — `:tag` (element),
  `:view-id` (view boundary), `:html` (trusted markup) — OR none, in
  which case it is a FRAGMENT, discriminated by its (always-present,
  possibly empty `[]`) `:children`. `:children` is NOT a co-equal
  discriminator: element and view-boundary nodes carry it as content, so
  it is only the fragment signal in the ABSENCE of the three primaries."
  [:tag :view-id :html])

(defn- coalesce
  "Canonicalize a spliced node vector (step 6): coalesce adjacent text
  runs into one string and drop empty-text nodes. Shared by `norm-nodes`
  (element children) and `normalize` (the root splice) so the root and
  every nested children level canonicalize identically."
  [vs]
  (let [coalesced (reduce (fn [acc v]
                            (if (and (string? v) (string? (peek acc)))
                              (conj (pop acc) (str (peek acc) v))
                              (conj acc v)))
                          [] vs)]
    (into [] (remove #(and (string? %) (= "" %))) coalesced)))

(defn- malformed-node!
  "Fail loud with the shared node-malformation id, naming the closed
  variant set (the escape the emitter/author should honour) AND the
  root-relative `path` that LOCATES the offending node.

  `path` is the deterministic `get-in`-shaped position of the node in the
  INPUT tree — a vector of `:children`/index segments threaded through the
  walk (`[]` is the root; `[:children 0 :children 2]` is the root's first
  child's third child). It rides both the human message (so a log reader
  can find the node) and the `:path` ex-data slot (so a tool can
  `(get-in tree path)` straight to it), and DISAMBIGUATES two equal-looking
  malformed nodes at different positions. `extra` carries the arm-specific
  slots (`:value`, `:got`).

  rf2-1aj9s — `extra` crosses `error/safe-form` HERE, once, for every arm,
  because a cyclic value riding out in ex-data explodes at a downstream
  logger / error projector / trace sink that `pr-str`s it, which is someone
  else's boundary rather than this one. Callers do the MESSAGE half of the
  same crossing (`error/pr-form`) because the reason string is composed
  before it arrives. `path` is framework-built (`:children` keywords and
  ints) and needs no crossing."
  [reason path extra]
  (error/throw-error!
   :rf.error/ui-tree-malformed 're-frame.ui.semantic/normalize
   (str reason " (at tree path " (pr-str path) ")")
   {:extra (assoc (error/safe-form extra) :path path)}))

(defn- norm-node
  "-> vector of semantic nodes/strings this tree node contributes. `path`
  is the node's own root-relative `get-in` position in the INPUT tree
  (threaded by `norm-nodes*`; `[]` at the root), carried onto every
  malformation so a deeply nested failure LOCATES the offending node.

  Discrimination is CLOSED and UNAMBIGUOUS (jvm-tree contract §Node
  discrimination): a map node carries exactly one of `node-
  discriminators`, or none plus `:children` (a fragment). Zero
  discriminators with no `:children`, or two-plus discriminators, is a
  malformed node — it fails loud (carrying its `path`) rather than being
  guessed by branch order or silently dropped as `[]`."
  [node path]
  (cond
    (string? node) [node]
    (map? node)
    (let [ds (filterv #(contains? node %) node-discriminators)]
      (cond
        (> (count ds) 1)
        (malformed-node!
         (str "a tree node carries multiple node discriminators "
              (pr-str ds) " — every map node is EXACTLY ONE of :tag "
              "(element), :view-id (view boundary), :html (trusted "
              "markup), or a fragment (none of these, splicing its "
              ":children); an ambiguous map must not be interpreted by "
              "branch order: " (error/pr-form node))
         path {:value node :got ds})

        (= 1 (count ds))
        (case (first ds)
          :tag     [(norm-element node path)]
          :html    [{:html (:html node)}]
          ;; view boundaries SPLICE — children replace the node
          :view-id (norm-nodes* (:children node) path))

        ;; no primary discriminator: a fragment (splices its children)
        (contains? node :children)
        (norm-nodes* (:children node) path)

        ;; no discriminator AND no :children — not a renderable node; must
        ;; not silently disappear as [] (a lost fragment / corrupt node)
        :else
        (malformed-node!
         (str "a tree node carries no node discriminator — every map node "
              "is an element (:tag), a view boundary (:view-id), trusted "
              "markup (:html), or a fragment (:children); a map with none "
              "is not a renderable tree node: " (error/pr-form node))
         path {:value node :got []})))
    :else
    (malformed-node!
     (str "malformed tree node in normalization N: " (error/pr-form node))
     path {:value node})))

(defn- norm-nodes*
  "Splice a children vector, threading each child's root-relative path.
  `parent-path` is the OWNING node's path; child `i` is located at
  `parent-path` + `[:children i]` — a deterministic INPUT-tree position
  that depends only on document child ORDER (order-significant per the
  contract), never on map key iteration order."
  [children parent-path]
  (into []
        (comp (map-indexed
               (fn [i c] (norm-node c (conj parent-path :children i))))
              cat)
        children))

(defn norm-nodes
  "Normalize + canonicalize a children vector under `parent-path`: splice
  (locating any malformed child), then coalesce adjacent text runs and
  drop empties (step 6)."
  [children parent-path]
  (coalesce (norm-nodes* children parent-path)))

(def ^:private supported-tree-versions
  "The node-schema versions `normalize` (semantic projection N) accepts.
  Bumping the tree-version integer (jvm-tree contract §Versioning — any
  change to the variant set, discrimination, canonical-form rules, or
  N's behaviour) edits this set DELIBERATELY, in lockstep with the walk
  above. An unsupported version must never be reinterpreted under v1's
  rules."
  #{1})

(defn- validate-tree-version!
  "Root schema-version gate (jvm-tree contract §Versioning): the root of
  a structural tree carries `:rf.ui/tree-version` (`re-frame.ui.tree/
  render` stamps it). A missing, non-integer, or unsupported version is
  NOT silently coerced to v1 — it fails loud at the N boundary BEFORE any
  traversal, so a future-version or corrupt tree can never produce a
  plausible semantic projection / fingerprint. (The S5 SSR serialiser
  carries its own version-gate sibling
  `:rf.error/ssr-ui-tree-version-unsupported`; this Tier-1 parity
  boundary reuses the shared node-malformation id with the same
  `{:got … :supported …}` ex-data.)"
  [tree]
  (let [v (:rf.ui/tree-version tree)]
    (when-not (and (integer? v) (contains? supported-tree-versions v))
      (error/throw-error!
       :rf.error/ui-tree-malformed 're-frame.ui.semantic/normalize
       (str "normalization N requires a root :rf.ui/tree-version in "
            (pr-str supported-tree-versions) " — got " (error/pr-form v)
            "; an absent or unsupported schema version is never coerced "
            "to v1 (that would make the version field meaningless and let "
            "a future-version tree be misread under today's rules)")
       ;; rf2-1aj9s — `v` is read straight off a caller-supplied tree, so it
       ;; crosses `error/safe-form` on the ex-data side exactly as it crosses
       ;; `error/pr-form` on the message side above. The SSR sibling
       ;; (`re-frame.ssr.ui-tree/validate-tree-version!`) crosses the same
       ;; two halves at the same two places; `supported-tree-versions` is a
       ;; compile-time constant and needs no crossing.
       {:extra {:got (error/safe-form v) :supported supported-tree-versions}}))))

(defn normalize
  "`N(tree)` — the semantic-node projection of a version-1 structural
  tree. Validates the root `:rf.ui/tree-version` FIRST (fail-loud on a
  missing / non-integer / unsupported version), then walks from the ROOT
  (path `[]`) and returns the VECTOR of semantic roots in document order.
  A malformed node past the version gate fails loud carrying the
  root-relative `:path` that locates it (`malformed-node!`)."
  [tree]
  (validate-tree-version! tree)
  (coalesce (norm-node tree [])))
