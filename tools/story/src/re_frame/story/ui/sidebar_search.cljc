(ns re-frame.story.ui.sidebar-search
  "Sidebar search-as-you-type filter (rf2-yngai).

  Different ergonomic from the Cmd-K command palette (rf2-9hc8):
  the palette is fuzzy whole-registry jump; this is in-tree narrowing
  by a substring query. Author audience expects both.

  ## Surface

  - `match-variant?`       — pure: does a variant id / body match the
                             query?
  - `match-story?`         — pure: does the story id itself match?
  - `filter-grouped-tree`  — pure: take the `group-variants-by-story`
                             output + a query, return the subtree
                             whose stories OR variants match.
  - `highlight-segments`   — pure: split a label into match / non-match
                             segments for amber-tint highlighting.

  Every fn here is pure data → data — JVM testable. The Reagent
  input lives in `re-frame.story.ui.sidebar` and feeds these helpers
  the live query string.

  ## Match semantics

  Token-AND, case-insensitive, substring per token. Matches against
  the variant id's string form (`:story.counter/at-five` → searched as
  `\"story.counter/at-five\"`) AND the parent story id's string form.
  Token-AND mirrors the command-palette `match-score` discrimination
  so users get consistent narrowing across both surfaces.

  Empty / blank query → every variant matches (the filter no-ops)."
  (:require [clojure.string :as str]))

;; ---- pure: query tokenisation -------------------------------------------

(defn tokenise
  "Pure: split a search query into lowercase, non-blank tokens.
  Empty / nil input returns an empty vector."
  [query]
  (if (string? query)
    (->> (str/split (str/lower-case (str/trim query)) #"\s+")
         (remove str/blank?)
         vec)
    []))

(defn- variant-id-string
  "Render a variant id as the lowercase string the matcher sees.
  Strips the leading `:` from `pr-str` so `\":story.counter/at-five\"`
  becomes `\"story.counter/at-five\"`. Pure data → data."
  [variant-id]
  (let [s (str variant-id)]
    (str/lower-case
      (if (str/starts-with? s ":") (subs s 1) s))))

(defn- haystack-for-variant
  "Pure: the concatenated lowercase string the matcher tests for a
  given variant. Includes the variant id, parent story id (when
  derivable from the namespace), and the variant body's `:doc`
  string + variant tags (each tag's `(name kw)`).

  All-in-one haystack so token-AND can hit across slots — typing
  `count five` matches a variant whose id is `at-five` AND whose
  story id is `story.counter`."
  [variant-id variant-body]
  (let [id-str  (variant-id-string variant-id)
        ;; namespace = parent story id (Story v1 convention)
        ns-str  (when (keyword? variant-id) (some-> (namespace variant-id) str/lower-case))
        doc     (some-> (:doc variant-body) str str/lower-case)
        tags    (when (set? (:tags variant-body))
                  (->> (:tags variant-body)
                       (keep #(when (keyword? %) (name %)))
                       (str/join " ")
                       str/lower-case))]
    (str/join " " (remove nil? [id-str ns-str doc tags]))))

;; ---- pure: predicates ---------------------------------------------------

(defn match-variant?
  "Pure predicate: does `[variant-id variant-body]` match `tokens` (the
  output of `tokenise`)? Returns true when every token is a substring
  of the variant's haystack. Defensive — lowercases tokens internally
  so callers can pass raw strings without piping through `tokenise`."
  [tokens variant-id variant-body]
  (if (empty? tokens)
    true
    (let [hay (haystack-for-variant variant-id variant-body)]
      (every? (fn [t] (str/includes? hay (str/lower-case t))) tokens))))

(defn match-story?
  "Pure: does a parent story id match every token? Used so a story
  header that itself matches keeps its variants visible even when the
  variants' own id strings don't carry the query. Lowercases tokens
  internally."
  [tokens story-id]
  (if (empty? tokens)
    true
    (let [hay (variant-id-string (or story-id ""))]
      (every? (fn [t] (str/includes? hay (str/lower-case t))) tokens))))

;; ---- pure: grouped-tree filter -----------------------------------------

(defn filter-grouped-tree
  "Pure: given the output of `state/group-variants-by-story` (a vector
  of `{:story-id ... :variants [[variant-id body] ...]}` entries) and
  a search `query`, return the subset whose story id matches OR whose
  variants match the query.

  Resolution per spec rf2-yngai §match-semantics:

  - A story whose id matches every token keeps ALL its variants
    (ancestor-keeps-children).
  - A story whose id does NOT match keeps only the variants whose
    own haystack matches every token.
  - A story with zero surviving variants is dropped from the tree.

  Empty / blank query returns `entries` unchanged so the filter
  no-ops on the empty-query case."
  [entries query]
  (let [tokens (tokenise query)]
    (if (empty? tokens)
      entries
      (vec
        (for [{:keys [story-id variants] :as entry} entries
              :let [story-match? (match-story? tokens story-id)
                    kept (if story-match?
                           variants
                           (vec (filter (fn [[vid body]]
                                          (match-variant? tokens vid body))
                                        variants)))]
              :when (seq kept)]
          (assoc entry :variants kept))))))

(defn filter-workspaces
  "Pure: trim a workspace map down to entries whose id matches every
  token. Pre-emptive: when query is blank, returns the workspaces map
  unchanged. Workspace bodies don't carry as much haystack as variants,
  but the same `match-story?` semantics on the id keeps the filter
  intuitive."
  [workspaces query]
  (let [tokens (tokenise query)]
    (if (empty? tokens)
      workspaces
      (into {}
            (filter (fn [[wid _body]]
                      (match-story? tokens wid)))
            workspaces))))

;; ---- pure: highlight segmentation ---------------------------------------

(defn- find-first-match
  "Pure: return `[start end]` of the first match of any `token` in
  `s-lower`, or nil. Token order honoured — first declared wins."
  [s-lower tokens]
  (some (fn [t]
          (when-let [i (str/index-of s-lower t)]
            [i (+ i (count t))]))
        tokens))

(defn highlight-segments
  "Pure data → data: split `label` into a vector of
  `{:text ... :match? boolean}` segments based on the tokens in
  `query`. Used by the sidebar row renderer to wrap matched
  substrings in an amber-tint span.

  - Empty query → single non-match segment of the whole label.
  - Token-order honoured: the first token whose substring appears in
    the label is highlighted at its first occurrence; recursive on
    the remainder of the label so multiple disjoint matches get their
    own segments.
  - Case-insensitive match; the highlighted segment preserves the
    label's original case."
  [label query]
  (let [tokens (tokenise query)]
    (if (or (empty? tokens) (str/blank? label))
      [{:text label :match? false}]
      (loop [acc [] remaining label]
        (let [r-lower (str/lower-case remaining)]
          (if-let [[s e] (find-first-match r-lower tokens)]
            (let [before (subs remaining 0 s)
                  match  (subs remaining s e)
                  after  (subs remaining e)]
              (recur (cond-> acc
                       (seq before) (conj {:text before :match? false})
                       :always      (conj {:text match :match? true}))
                     after))
            (cond-> acc
              (seq remaining) (conj {:text remaining :match? false}))))))))
