(ns re-frame.story.predicates
  "Story-internal pure data → data helpers shared across the tree.

  Lives at the leaf of the require graph (depends on `clojure.core`
  only) so any sibling namespace can `:require` it without cycle risk.

  Companion leaf to `re-frame.story.late-bind` (run-time function
  resolution) — both are pure namespaces the rest of Story consumes.

  The five micro-fns here used to live as private mirrors across
  ~9 sites (args / assertions / recorder / docs / state / test-mode/pure).
  Each mirror was justified locally by a cycle dodge, but the systemic
  shape is one canonical leaf. See rf2-pzzbw / audit rf2-cgqam."
  (:require [clojure.string :as str]))

(def reserved-assertion-ns
  "Reserved namespace string for assertion event ids per Conventions.md
  (the `:rf.assert/*` family)."
  "rf.assert")

(defn parent-story-id
  "Derive a variant's parent story id by stripping the variant's name
  per spec/007 §Canonical id grammar — a variant id `:story.foo/bar`
  has namespace `\"story.foo\"`; its parent is `:story.foo`.

  Returns nil for non-keywords or keywords without a namespace."
  [variant-id]
  (when (and (keyword? variant-id) (namespace variant-id))
    (keyword (namespace variant-id))))

(defn assertion-id?
  "True iff `id` is an `:rf.assert/*` event id. Always returns a
  primitive boolean."
  [id]
  (boolean (and (keyword? id) (= reserved-assertion-ns (namespace id)))))

(defn assertion-event?
  "True iff `event` is a vector whose head is an `:rf.assert/*` keyword.

  Used by the play-runner + recorder + test-mode pane to distinguish
  observed-trace dispatches from authored assertions. Always returns a
  primitive boolean."
  [event]
  (boolean (and (sequential? event)
                (seq event)
                (assertion-id? (first event)))))

;; ---- EDN-format helpers --------------------------------------------------

(defn indent-after
  "Continuation indent that lines successive items up directly under
  the character immediately following `prefix` on the previous line.
  Returns `\"\\n<count(prefix) spaces>\"`. Pure data → string.

  Used by `recorder/gen-play-snippet`, `save-variant/gen-variant-
  snippet`, and `review-dialog`'s snippet renderer to join multi-line
  EDN bodies. The argument is the literal first-line text preceding
  the items (e.g. `\"   :script [\"` or `\"   :args {\"`) — passing the
  rendered prefix verbatim keeps the geometry obvious and
  breakage-resistant.

  Example:

      (str \"   :script [item1\" (indent-after \"   :script [\") \"item2]\")
      ;; => \"   :script [item1\\n             item2]\"
      ;;                    ^---------- item2 aligns under item1

  Lives in the predicates leaf (rf2-ar0t9) so producers
  (recorder / save-variant) don't have to `:require` the consumer
  (review-dialog) just for this 4-line helper."
  [prefix]
  (str "\n" (apply str (repeat (count prefix) \space))))

;; ---- predicate-symbol resolution -----------------------------------------

(defn resolve-sym-pred
  "Resolve a predicate symbol to a callable fn. JVM uses
  `requiring-resolve`; CLJS resolves via a best-effort `goog.global` walk
  on munged dotted names (works for fns reachable from a global ns like
  `js/cljs.user.my_pred`). Returns nil on miss.

  CLJS symbol resolution is BEST-EFFORT and FRAGILE under advanced
  compilation — the closure compiler mangles author namespace names, so
  the munged-dotted-name walk won't find them. The advanced-safe authoring
  path is to pass the predicate as a FN DIRECTLY in the
  `:wait-until [:db path :pred <fn>]` predicate (or the `:assert-db …
  :pred <fn>` form, which folds to `:rf.assert/path-matches [:fn fn]`); the
  resolver is only reached for the symbol escape-hatch (rf2-inbad).

  Shared by `assertions/resolve-fn-schema` (the `[:fn sym]` schema fold)
  and `runner-events/exec-wait-until` (the `:pred sym` form). Lives in this
  leaf so `assertions` resolves a symbol pred at validation time without a
  require into the impure `runner-events` ns (rf2-le0p4 dedup)."
  [sym]
  (when sym
    (try
      #?(:clj
         (when-let [v (requiring-resolve (symbol sym))]
           (when (var? v) @v))
         :cljs
         (let [ns-part   (namespace sym)
               name-part (name sym)]
           (when (and ns-part name-part)
             (let [dotted (str ns-part "." name-part)
                   munged (str/replace dotted #"-" "_")
                   parts  (str/split munged #"\.")]
               (loop [obj js/window
                      ks  parts]
                 (cond
                   (nil? obj)  nil
                   (empty? ks) (when (fn? obj) obj)
                   :else       (recur (aget obj (first ks)) (rest ks))))))))
      (catch #?(:clj Throwable :cljs :default) _ nil))))
