(ns ^:no-doc re-frame.freehand.test
  "`t` — the Freehand STRUCTURAL test surface (EP-0036 F1e; Spec 008).

  A programmer renders a declared view to the versioned semantic tree and
  asserts on it WITHOUT a browser, on BOTH hosts:

      (t/render [save-button {:article-id 42}])
      ;; => {:view-id :app.article/save-button
      ;;     :children [{:tag :button
      ;;                 :events {:on-click [:article/save 42]}
      ;;                 :children [\"Save\"]}]
      ;;     :rf.ui/tree-version 1}

  Five names query semantic VALUES; none simulates behaviour. Handler sites
  are event vectors as data, so 'what does this button do' is an equality
  check — no click simulation, no DOM, no flake:

      (deftest save-button-carries-intent
        (let [tree   (t/render [save-button {:article-id 42}])
              button (t/find tree #(= :button (:tag %)))]
          (is (= \"Save\" (t/text button)))
          (is (= [:article/save 42] (:on-click (t/attrs button))))))

  ## The surface

    (render form)          the versioned STRUCTURAL TREE for a declared-view
                           call or arbitrary markup — the same value on the
                           JVM and in ClojureScript, in BOTH execution modes
                           (an interpreted body is walked, a compiled body's
                           structural realisation is run — the call spelling
                           and the tree are identical either way).
    (find tree pred)       the FIRST node the predicate matches, or nil.
    (find-all tree pred)   every matching node, in document order.
    (attrs node)           the MERGED attribute projection of a node.
    (text node)            the node's text descendants in document order.

  `find` / `find-all` are conveniences over the whole traversal API, which
  is ordinary Clojure — `(tree-seq map? :children tree)` and a predicate
  over `(:tag %)` (element tag) or `(:view-id %)` (view boundary).

  ## Frame scope, and what runs headlessly

  There is no frame option. Establish frame scope with the programmer's
  ordinary bracket — `rf/with-new-frame` for a fresh owned frame
  (eval-bind-run-destroy), `rf/with-frame` to pin one you hold — drive state
  with `rf/dispatch-sync`, and assert on a FRESH `render`. The structural
  walk itself subscribes to nothing and dispatches nothing (Spec 004B): the
  events and subs a view under test touches must be `.cljc`, the standard
  re-frame discipline. Host-bearing behaviour (real listeners firing into
  the DOM, focus, presence timing, error recovery) is the mounted browser
  tier's, not this one.

  ## Reading a node

  `(:on-click node)` is a FIELD miss — a keyword lookup on a node reads its
  FIELDS, never its attributes. Event intent lives under `:events` and plain
  attributes under `:attrs`; `attrs` MERGES the two on an element and reads
  `:props` on a view boundary, so an intent assertion is
  `(is (= [:cart/add 42] (:on-click (t/attrs node))))`.

  Dev/test scope ONLY: nothing in a production bundle may `:require` this
  namespace. It sits alongside the emitters, below the `re-frame.freehand`
  door — `t/render` wraps the structural emitter the way `v/mount` wraps the
  host one.

  Normative owner: [`spec/008-Testing.md`](../../../../../spec/008-Testing.md);
  the node schema and projections are
  [`spec/004B-UI-Tree-and-Conversion.md`](../../../../../spec/004B-UI-Tree-and-Conversion.md)."
  (:refer-clojure :exclude [find])
  (:require [re-frame.error :as error]
            [re-frame.freehand.tree :as tree]))

#?(:clj (set! *warn-on-reflection* true))

(def ^:private where
  "The raising site every projection diagnostic names."
  're-frame.freehand.test)

(defn- malformed!
  [reason extra]
  (error/throw-error!
    :rf.error/ui-tree-malformed
    where
    reason
    {:recovery :no-recovery :extra extra}))

;; ---------------------------------------------------------------------------
;; render — the structural emitter, wrapped as the test entry
;; ---------------------------------------------------------------------------

(defn render
  "Render `form` and answer its versioned structural tree — the value a
  structural test asserts against.

  `form` is a declared-view call (`[view props & children]`, in either
  execution mode) or arbitrary markup; the return is always the ROOT node —
  a map carrying `:rf.ui/tree-version` — and a form that denotes text,
  several nodes, or nothing roots in a fragment. Plain, serialisable data:
  `(tree-seq map? :children tree)` is the whole traversal API, and the tree
  prints and reads back losslessly.

  The same declaration renders to the same tree on the JVM and in
  ClojureScript, which is what makes a `.cljc` structural test a cross-host
  claim rather than a JVM claim with a `.cljc` extension."
  [form]
  (tree/render form))

;; ---------------------------------------------------------------------------
;; Finders — conveniences over (tree-seq map? :children tree)
;; ---------------------------------------------------------------------------

(defn find-all
  "Every node under `tree` (the root included) for which `pred` is truthy,
  in document order. Empty vector when nothing matches. `pred` reads node
  fields — `(:tag %)` for an element, `(:view-id %)` for a view boundary."
  [tree pred]
  (filterv pred (tree-seq map? :children tree)))

(defn find
  "The FIRST node under `tree` (the root included, document order) for which
  `pred` is truthy, or nil. `nil` threads through a missed match, so
  `(t/attrs (t/find tree p))` nil-puns rather than throwing when nothing
  matched."
  [tree pred]
  (first (find-all tree pred)))

;; ---------------------------------------------------------------------------
;; Node discrimination (Spec 004B §Node schema — the closed five-variant set)
;; ---------------------------------------------------------------------------

(defn- node-kind
  "Discriminate a MAP node per the pinned order: `:tag` → element, else
  `:view-id` → view boundary, else `:html` → trusted-HTML, else `:children`
  → fragment. More than one primary discriminating field, or none of the
  four, is malformed — a projection over a malformed node fails loud rather
  than reading a plausible answer off a broken tree."
  [m]
  (let [primaries (cond-> 0
                    (contains? m :tag)     inc
                    (contains? m :view-id) inc
                    (contains? m :html)    inc)]
    (when (> primaries 1)
      (malformed!
        (str "malformed tree node — a map may carry only ONE of :tag / "
             ":view-id / :html (the closed five-variant node set); got "
             (pr-str (select-keys m [:tag :view-id :html])))
        {:value m}))
    (cond
      (contains? m :tag)      :element
      (contains? m :view-id)  :view-boundary
      (contains? m :html)     :html
      (contains? m :children) :fragment
      :else
      (malformed!
        (str "malformed tree node — a map node needs a discriminating field "
             "(:tag / :view-id / :html / :children); got " (pr-str m))
        {:value m}))))

(defn- not-a-node!
  [got]
  (malformed!
    (str "not a structural tree node — a projection takes a node (the value "
         "t/render returns, or any node reached by traversing it with "
         "(tree-seq map? :children tree)); got " (pr-str got))
    {:value got}))

;; ---------------------------------------------------------------------------
;; Projections (Spec 004B §Projections)
;; ---------------------------------------------------------------------------

(defn attrs
  "The MERGED attribute projection of a structural node — the ONE attribute
  read:

    element        → `:attrs` merged with `:events` (collision-free by
                     construction — the compiler routes `:on-*` to `:events`;
                     handler slots carry event vectors / options maps / an
                     opaque marker AS DATA)
    view-boundary  → the `:props` map
    fragment/html  → `{}` (no attributes exist; total, not an error)
    nil            → nil (nil-punning threads through a missed traversal)

  A keyword lookup on a node reads its FIELDS, never its attributes:
  `(:on-click node)` is a field miss. Intent assertion is an equality check
  `(is (= [:cart/add 42] (:on-click (t/attrs node))))`."
  [node]
  (cond
    (nil? node)    nil
    (string? node) (malformed!
                     (str "text content is not a node — attrs projects map "
                          "nodes; read text with t/text on the PARENT node")
                     {:value node})
    (map? node)    (case (node-kind node)
                     :element          (merge {} (:attrs node) (:events node))
                     :view-boundary    (or (:props node) {})
                     (:fragment :html) {})
    :else          (not-a-node! node)))

(defn- text*
  [n]
  (case (node-kind n)
    :html "" ; trusted-HTML contributes nothing — unparsed markup, not text data
    (apply str
           (map (fn [c]
                  (cond
                    (string? c) c
                    (map? c)    (text* c)
                    :else (malformed!
                            (str "malformed tree — a :children entry must be a "
                                 "node map or text content (a string); got "
                                 (pr-str c))
                            {:value c})))
                (:children n)))))

(defn text
  "The concatenation of `node`'s text descendants in document order —
  descending through elements, fragments and view boundaries alike;
  trusted-HTML nodes contribute nothing (their content is unparsed markup).
  No whitespace normalization beyond what the tree carries. `nil` → nil
  (nil-punning)."
  [node]
  (cond
    (nil? node)    nil
    (string? node) (malformed!
                     (str "text content is not a node — it IS the text; call "
                          "t/text on the node that contains it")
                     {:value node})
    (map? node)    (text* node)
    :else          (not-a-node! node)))
