(ns re-frame.freehand.test
  "`t` — the Freehand STRUCTURAL test surface (EP-0036 F1e; Spec 008).

  A programmer renders a declared view to the versioned semantic tree and
  asserts on it WITHOUT a browser, on BOTH hosts:

      (t/render [save-button {:article-id 42}])
      ;; => {:view-id :app.article/save-button
      ;;     :children [{:tag :button
      ;;                 :events {:on-click [:article/save 42]}
      ;;                 :children [\"Save\"]}]
      ;;     :rf.ui/tree-version 1}

  Five names query semantic VALUES and one BRACKET makes a state-reading
  view renderable; none simulates behaviour. Handler sites are event
  vectors as data, so 'what does this button do' is an equality check — no
  click simulation, no DOM, no flake:

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
    (with-render body…)    open a DISCARDABLE render for the duration of
                           `body`, so a view that reads state with `v/sub`
                           renders.
    (find tree pred)       the FIRST node the predicate matches, or nil.
    (find-all tree pred)   every matching node, in document order.
    (attrs node)           the MERGED attribute projection of a node.
    (text node)            the node's text descendants in document order.

  `find` / `find-all` are conveniences over the whole traversal API, which
  is ordinary Clojure — `(tree-seq map? :children tree)` and a predicate
  over `(:tag %)` (element tag) or `(:view-id %)` (view boundary). They hand
  the predicate NODE MAPS ONLY. Text content is a host string rather than a
  node, and a raw walk yields those strings as leaves, so a caller who wants
  the leaves themselves walks with `tree-seq` directly.

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

  ## Rendering a view that reads state

  `v/sub` is legal only during an active declared render, and `render` runs
  the walk without opening one — in production the host is what opens a
  render. So a view that reads state renders inside [[with-render]], which
  opens a DISCARDABLE render for the walk and drops it afterwards:

      (rf/dispatch-sync [:basket/add 42])
      (let [tree (t/with-render (t/render [basket-total {}]))]
        (is (= \"1 item\" (t/text tree))))

  ## Reading a node

  `(:on-click node)` is a FIELD miss — a keyword lookup on a node reads its
  FIELDS, never its attributes. Event intent lives under `:events` and plain
  attributes under `:attrs`; `attrs` MERGES the two on an element and reads
  `:props` on a view boundary, so an intent assertion is
  `(is (= [:cart/add 42] (:on-click (t/attrs node))))`.

  Dev/test scope ONLY: nothing in a production bundle may `:require` this
  namespace. It sits alongside the emitters, below the `re-frame.freehand`
  door — `t/render` wraps the structural emitter the way `v/mount` wraps the
  host one. Dev/test scope is not privacy: this is a PUBLIC authoring
  surface, rowed at the `testing` tier in the API manifest, so a rename, a
  signature change or an accidental export reddens the public-API gate on
  both hosts.

  Normative owner: [`spec/008-Testing.md`](../../../../../spec/008-Testing.md);
  the node schema and projections are
  [`spec/004B-UI-Tree-and-Conversion.md`](../../../../../spec/004B-UI-Tree-and-Conversion.md)."
  (:refer-clojure :exclude [find])
  (:require [re-frame.error :as error]
            ;; The atomic shell, for [[with-render]] alone. INTERNAL, and it
            ;; sits BELOW this namespace and takes nothing back from it — the
            ;; bracket exists precisely so a test never has to name it.
            [re-frame.freehand.cell :as cell]
            [re-frame.freehand.tree :as tree])
  #?(:cljs (:require-macros [re-frame.freehand.test :refer [with-render]])))

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
;; with-render — the discardable render a state-reading view needs
;; ---------------------------------------------------------------------------
;;
;; Spec 006 §The subscription law makes `v/sub` legal ONLY during an active
;; declared render, and the thing that opens one in production is the host.
;; `render` is a walk, not a host, so without this bracket the blessed
;; structural surface worked on props-only views and refused every
;; state-reading one — which is most of an application.
;;
;; The bracket opens exactly what the host opens and keeps exactly the
;; property that makes a speculative render safe: a candidate publishes
;; NOTHING until it is committed, and this one is never committed. So the
;; reads inside resolve and probe, the view under test runs unmodified, and
;; when the bracket returns there is no dependency, no watch, no cache node
;; and no disposal obligation left behind — the abandoned-render path the
;; shell already guarantees, reached deliberately.
;;
;; It takes no frame, on purpose: the candidate binds no ambient frame of its
;; own, so frame scope stays the programmer's ordinary `rf/with-frame` /
;; `rf/with-new-frame` bracket and this surface keeps its one law about
;; frames rather than acquiring a second.

#?(:clj
   (defmacro with-render
     "Run `body` inside a DISCARDABLE render, and answer its value — the
     bracket a view that reads state is rendered in.

         (rf/dispatch-sync [:basket/add 42])
         (let [tree (t/with-render (t/render [basket-total {}]))]
           (is (= \"1 item\" (t/text tree))))

     `v/sub` is legal only during an active declared render (Spec 006 §The
     subscription law), and [[render]] is a walk rather than a host, so a
     view whose body reads state is refused outside this bracket with
     `:rf.error/view-read-outside-render`. `with-render` opens the render
     the host would have opened, so the view under test is rendered AS
     WRITTEN — rewriting it to take a one-shot read would be testing
     something other than the view.

     PUBLISHES NOTHING. The render it opens is never committed, which is
     the shell's own abandoned-render path: the reads inside resolve and
     probe but acquire nothing, so when the bracket returns there is no
     dependency, no watch and no disposal obligation left behind. Render it
     as often as you like; each `render` is a fresh reading of current
     state.

     It takes NO frame. Frame scope is the programmer's ordinary bracket —
     `rf/with-new-frame` for a fresh owned frame, `rf/with-frame` to pin one
     you hold — exactly as it is for a `render` that reads nothing.

     Per [`spec/008-Testing.md`](../../../../../spec/008-Testing.md#freehand-structural-and-mounted-testing)."
     [& body]
     `(cell/with-capture (cell/candidate (cell/cell ::probe) nil)
        (fn [] ~@body))))

;; ---------------------------------------------------------------------------
;; Finders — conveniences over (tree-seq map? :children tree)
;; ---------------------------------------------------------------------------

(defn find-all
  "Every node under `tree` (the root included) for which `pred` is truthy,
  in document order. Empty vector when nothing matches. `pred` reads node
  fields — `(:tag %)` for an element, `(:view-id %)` for a view boundary.

  `pred`'s domain is NODE MAPS ONLY. A raw `(tree-seq map? :children tree)`
  walk yields text content — host strings — as leaves, and those are dropped
  before the predicate runs, so a MEMBERSHIP test reads the same on both
  hosts: `#(contains? % :rf.ui/presence)` answers the marker node rather
  than throwing on the JVM and answering in ClojureScript (rf2-per51). Text
  is read with [[text]] on the owning node, or reached through its
  `:children`; a caller who genuinely wants the raw leaves walks with
  `tree-seq` directly."
  [tree pred]
  (filterv pred (filter map? (tree-seq map? :children tree))))

(defn find
  "The FIRST node under `tree` (the root included, document order) for which
  `pred` is truthy, or nil. `nil` threads through a missed match, so
  `(t/attrs (t/find tree p))` nil-puns rather than throwing when nothing
  matched. `pred`'s domain is [[find-all]]'s — node maps only, never text."
  [tree pred]
  (first (find-all tree pred)))

;; ---------------------------------------------------------------------------
;; Node discrimination (Spec 004B §Node schema — the closed five-variant set)
;; ---------------------------------------------------------------------------

(defn- node-kind
  "Discriminate a MAP node per the pinned order: `:tag` → element, else
  `:view-id` → view boundary, else `:html` → trusted-HTML, else
  `:rf.ui/host` → a declared host crossing, else `:children` → fragment.
  More than one primary discriminating field, or none of the five, is
  malformed — a projection over a malformed node fails loud rather than
  reading a plausible answer off a broken tree.

  The host arm is NOT decoration. A `v/defhost` crossing carries `:props`
  and `:children` and no `:tag`, so without its own arm it reaches the
  FRAGMENT arm and every projection answers a fragment's total, harmless,
  wrong answer — silently, because the fragment arm is documented total
  rather than an error (rf2-c20nr). `:rf.ui/presence` and `:rf.ui/boundary`
  are deliberately absent here: those genuinely ARE fragments carrying
  diagnostic metadata (004B §Reserved `:rf.ui/*` keys), so the fragment arm
  is their right answer and their metadata is an ordinary field read."
  [m]
  (let [primaries (cond-> 0
                    (contains? m :tag)         inc
                    (contains? m :view-id)     inc
                    (contains? m :html)        inc
                    (contains? m :rf.ui/host)  inc)]
    (when (> primaries 1)
      (malformed!
        (str "malformed tree node — a map may carry only ONE of :tag / "
             ":view-id / :html / :rf.ui/host (the closed node set); got "
             (pr-str (select-keys m [:tag :view-id :html :rf.ui/host])))
        {:value m}))
    (cond
      (contains? m :tag)        :element
      (contains? m :view-id)    :view-boundary
      (contains? m :html)       :html
      (contains? m :rf.ui/host) :host
      (contains? m :children)   :fragment
      :else
      (malformed!
        (str "malformed tree node — a map node needs a discriminating field "
             "(:tag / :view-id / :html / :rf.ui/host / :children); got "
             (pr-str m))
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
    host           → the `:props` map — the AUTHORED ordinary props of a
                     `v/defhost` crossing, with each filled callback
                     position recorded as its opaque role marker
    fragment/html  → `{}` (no attributes exist; total, not an error)
    nil            → nil (nil-punning threads through a missed traversal)

  A keyword lookup on a node reads its FIELDS, never its attributes:
  `(:on-click node)` is a field miss. Intent assertion is an equality check
  `(is (= [:cart/add 42] (:on-click (t/attrs node))))`. The host node's own
  fields — `:rf.ui/host`, `:rf.ui/host-ssr`, `:rf.ui/host-children`,
  `:rf.ui/host-map-props` — are field reads by the same rule."
  [node]
  (cond
    (nil? node)    nil
    (string? node) (malformed!
                     (str "text content is not a node — attrs projects map "
                          "nodes; read text with t/text on the PARENT node")
                     {:value node})
    (map? node)    (case (node-kind node)
                     :element              (merge {} (:attrs node) (:events node))
                     (:view-boundary :host) (or (:props node) {})
                     (:fragment :html)     {})
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
