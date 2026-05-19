(ns re-frame.test-helpers
  "View-assertion test helpers — walk a hiccup tree by `:data-testid`,
  read out content and event-handlers, invoke an attached handler.

  ## Why this ns exists (rf2-irp6j)

  re-frame2's testing surface (Spec 008, [`re-frame.test-support`]
  (test_support.cljc)) covers events, fxs, subs, machines, and whole
  dispatch cascades cheaply on the JVM. None of that, by itself,
  proves the **view** shows the right thing. Two classes of bug live
  in the view-vs-state gap:

    1. **State-correct, view-broken** (Causa rf2-70tkv class) — the
       handler updated `app-db`, the sub computes the right value, but
       the view reads from the wrong path / formats it wrong / forgets
       to render one branch. State-only assertions pass; the user sees
       a broken screen.

    2. **Wrong-frame dispatch** (Causa rf2-83d4x class) — the view
       wires `:on-click` to dispatch into the wrong frame (or no
       frame at all). State-assertions in the host frame stay green;
       the click in production fires into a sibling and nothing
       happens.

  Both are caught by a single test shape:

      dispatch → call the view-fn → walk the returned hiccup →
      assert on content (catches class 1) or invoke `:on-click`
      (catches class 2)

  The view-fn is just a function — call it directly. The returned
  hiccup is just a vector — walk it like any other tree. No JSDOM,
  no React, no `act()`. Runs on JVM and Node-CLJS equally.

  ## Hiccup-walk vs render-to-string

  Two flavours of view-content test exist:

    - **`render-to-string`** (Spec 011) — renders the whole view to
      an HTML string. Best when the assertion is about the rendered
      markup (\"is the `<button>` disabled?\", \"does the `<h1>` carry
      the right class?\"). Output is a string.

    - **hiccup-walk** (this ns) — calls the view-fn directly and
      walks the returned hiccup data. Best when the assertion is
      about the **structure** of the view (\"is the testid present?\",
      \"what handler does the button carry?\") or when the test wants
      to **invoke** a handler to drive interaction. Output is hiccup
      data; assertions read keys.

  Reach for `render-to-string` when the test cares about HTML;
  reach for hiccup-walk when the test cares about handlers or
  testid-keyed structure.

  ## Function-component expansion

  Reagent hiccup admits a function in the first slot of a vector —
  `[my-component {...}]` — and lazily invokes it during render. The
  walkers in this ns expand those nested function components by
  calling them with their args (just like Reagent's renderer
  would) before walking, so a test that calls a parent view-fn
  sees the leaf hiccup the user sees. The expansion is recursive
  but terminating: a non-vector / non-fn leaf is a fixed point.

  Form-3 components built via `r/create-class` are detected (the
  reagent-slim class tag + the stashed `:reagent-render` slot) and
  expanded by invoking the render fn directly with the hiccup args.
  The walker does NOT instantiate React or run lifecycle methods —
  if a Form-3 view's hiccup output depends on lifecycle state, the
  test sees the initial render only. JVM runs identically: class-3
  detection is a no-op because the JVM has no JS class instances.

  ## Authoring side — the `testid` helper

  Tests benefit from views that **carry** a `:data-testid` on the
  outer attrs map. The [[testid]] helper standardises the attrs
  fragment so the convention reads at every call site:

      [:button (testid \"counter-inc\" {:on-click #(rf/dispatch [:counter/inc])})
       \"+\"]

  Equivalent to writing `{:data-testid \"counter-inc\" :on-click ...}`
  by hand; pick whichever reads better in your view.

  ## Selector convention — `data-testid` vs `data-test` vs custom

  React conventionally uses `:data-testid`; some codebases (notably
  Story) standardised on `:data-test` before the rename; framework
  tools may use their own prefix (Causa uses `:data-rf-causa-*`).
  This ns ships two layers:

    - [[find-by-attr]] / [[find-all-by-attr]] — the underlying. Match
      against any attr key the caller supplies. Use these directly
      when your codebase keys on `:data-test` or a custom attribute.

    - [[find-by-testid]] / [[find-all-by-testid]] — thin wrappers
      that pre-bind the attr to `:data-testid`. Use these for the
      common React-convention case.

  ## Public API

  ### Walking — generic attribute
  - [[find-by-attr]] — first node whose attrs map carries `attr == val`,
    or nil.
  - [[find-all-by-attr]] — all nodes whose attrs map carries
    `attr == val`.
  - [[find-by-attr-prefix]] — all nodes whose `attr` value (a string)
    starts with the given prefix.

  ### Walking — `:data-testid` convenience
  - [[find-by-testid]] — first node with the given testid, or nil.
  - [[find-all-by-testid]] — all nodes with the given testid.
  - [[find-by-testid-prefix]] — all nodes whose testid starts with
    the given prefix.

  ### Reading
  - [[text-content]] — concatenate string leaves under a node.
  - [[attrs]] — return the attrs map for a hiccup node (or nil).
  - [[children]] — return the child elements (everything after attrs).
  - [[extract-handler]] — pluck an event handler (e.g. `:on-click`)
    off a node.

  ### Driving
  - [[invoke-handler]] — find a handler and call it. Returns the
    handler's return value (or throws if no handler is present).

  ### Authoring
  - [[testid]] — build a `:data-testid`-carrying attrs map at the
    view call site. Optional `extra` map merges additional attrs."
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Hiccup-tree expansion
;; ---------------------------------------------------------------------------

(declare expand-tree)

(defn- reagent-class-render
  "When `head` is a reagent-slim Form-3 class constructor (built by
  `reagent2.impl.component/create-class*`), return its stashed
  `:reagent-render` fn. Otherwise return nil.

  Detection is property-based — we look for the tag
  `.-cljsReagentClass` (true) and pluck the render fn from
  `.-cljsReagentRender`. No `:require` on reagent so this ns stays
  classpath-clean for callers that don't pull reagent.

  JVM: always nil — JVM has no JS classes and no `.-` property
  access on plain fns. The reader conditional below makes the
  body a no-op there."
  [head]
  #?(:cljs (when (and (some? head)
                      (true? (.-cljsReagentClass ^js head)))
            (.-cljsReagentRender ^js head))
     :clj  nil))

(defn expand-tree
  "Recursively expand a hiccup tree, invoking any function components
  (or Form-3 class components) with their args. After expansion,
  every vector's first element is a keyword tag or a non-component
  value, never a fn / class.

  Mirrors what Reagent's renderer does at mount time:

    - `[component-fn & args]` — invoke `component-fn` with `args`.
    - `[reagent-class & args]` — pluck the class's `:reagent-render`
      slot (stashed at create-class time) and invoke with `args`. The
      class is NOT instantiated and lifecycle methods do NOT run —
      this walker is for state/structure assertions, not behaviour
      that depends on `componentDidMount` etc.

  Non-vector branches (strings, numbers, maps, nil) are returned
  unchanged. Lazy sequences are walked through `map`; vectors through
  `mapv`.

  Public so test files mid-walk can re-expand a sub-tree if needed."
  [tree]
  (cond
    (and (vector? tree) (fn? (first tree)))
    (let [head (first tree)
          render-fn (reagent-class-render head)]
      (cond
        ;; Form-3 reagent class — call its render-fn directly with
        ;; the hiccup args. Skips React instantiation entirely.
        (some? render-fn)
        (expand-tree (apply render-fn (rest tree)))

        ;; Plain function component — invoke as Reagent would.
        :else
        (expand-tree (apply head (rest tree)))))

    (vector? tree)
    (mapv expand-tree tree)

    (seq? tree)
    (map expand-tree tree)

    :else
    tree))

(defn- hiccup-seq
  "Return a seq of every node in the expanded hiccup tree, in
  depth-first order. Each yielded node is either a hiccup vector,
  a child seq, or a leaf (string / number / nil)."
  [tree]
  (let [expanded (expand-tree tree)]
    (tree-seq (some-fn vector? seq?) seq expanded)))

;; ---------------------------------------------------------------------------
;; Reading hiccup nodes
;; ---------------------------------------------------------------------------

(defn attrs
  "Return the attrs map of a hiccup node, or nil if the node has no
  attrs map. A hiccup vector's second element is an attrs map iff it
  is a map; otherwise the second element is a child.

  Returns nil for any input that isn't a hiccup vector with an
  attrs-map slot."
  [node]
  (when (and (vector? node)
             (>= (count node) 2)
             (map? (second node)))
    (second node)))

(defn children
  "Return the child elements of a hiccup node — everything after the
  tag (and optional attrs map). Always returns a vector (empty if the
  node has no children). Returns nil for non-hiccup input."
  [node]
  (cond
    (not (vector? node)) nil
    (and (>= (count node) 2) (map? (second node))) (vec (drop 2 node))
    :else (vec (drop 1 node))))

(defn text-content
  "Recursively collect string leaves under `node` and return them
  joined into a single string. Numbers are coerced to strings; nils
  are skipped. Empty result is the empty string.

  Useful for assertions like:

      (is (= \"Count: 5\" (text-content (find-by-testid tree \"counter-label\"))))"
  [node]
  (->> (hiccup-seq node)
       (filter (some-fn string? number?))
       (map str)
       (str/join)))

(defn extract-handler
  "Return the value of `event-key` (e.g. `:on-click`, `:on-change`)
  from `node`'s attrs map, or nil if the node has no such attr.
  Equivalent to `(get (attrs node) event-key)` but reads better at
  call sites."
  [node event-key]
  (get (attrs node) event-key))

;; ---------------------------------------------------------------------------
;; Finding by arbitrary attribute (the underlying)
;;
;; The `find-by-testid` family below is a thin wrapper around these.
;; Use `find-by-attr` directly when your codebase keys on `:data-test`
;; (Story's legacy convention) or a custom prefix (e.g. Causa's
;; `:data-rf-causa-*`). Cross-framework code that doesn't want to
;; commit to a single attr convention talks at this layer.
;; ---------------------------------------------------------------------------

(defn- attr-of
  "Return the value of `attr` on a hiccup node's attrs map, or nil."
  [node attr]
  (when (vector? node)
    (when-let [a (attrs node)]
      (get a attr))))

(defn find-by-attr
  "Walk `tree` (expanding function and class components) and return
  the FIRST hiccup node whose attrs map carries `attr == val`, or
  nil if no node matches.

  Generic over the attribute keyword — pick whichever your codebase
  uses (`:data-testid`, `:data-test`, `:id`, a custom prefix, …):

      (find-by-attr tree :data-testid \"counter-inc\")
      (find-by-attr tree :data-test    \"submit\")
      (find-by-attr tree :id           \"root\")"
  [tree attr val]
  (some (fn [node]
          (when (= val (attr-of node attr))
            node))
        (hiccup-seq tree)))

(defn find-all-by-attr
  "Like [[find-by-attr]] but returns a vector of EVERY matching node,
  in depth-first order. Empty vector when no match."
  [tree attr val]
  (filterv (fn [node] (= val (attr-of node attr)))
           (hiccup-seq tree)))

(defn find-by-attr-prefix
  "Return a vector of every hiccup node whose `attr` value (a string)
  STARTS with `prefix`. Useful for view layouts that mint a family of
  attribute values off a stable stem (e.g. `\"counter-row-1\"`,
  `\"counter-row-2\"`, …).

  Non-string attr values do not match — only strings respond to
  prefix comparison."
  [tree attr prefix]
  (filterv (fn [node]
             (when-let [v (attr-of node attr)]
               (and (string? v) (str/starts-with? v prefix))))
           (hiccup-seq tree)))

;; ---------------------------------------------------------------------------
;; Finding by testid — thin wrappers over find-by-attr
;;
;; The common case (React's `:data-testid` convention). Authoring side
;; uses the [[testid]] helper to keep the convention readable at view
;; call sites. Both layers (these and `find-by-attr` directly) walk the
;; same underlying tree-seq.
;; ---------------------------------------------------------------------------

(defn find-by-testid
  "Walk `tree` (expanding function and class components) and return
  the FIRST hiccup node whose attrs map carries `:data-testid ==
  test-id`, or nil if no node matches.

  Equivalent to `(find-by-attr tree :data-testid test-id)`. Use the
  generic [[find-by-attr]] directly when your codebase keys on a
  different attribute (e.g. `:data-test`).

  Use to anchor on a stable element from a view:

      (let [tree (counter-view {:n 5})
            label (find-by-testid tree \"counter-label\")]
        (is (= \"Count: 5\" (text-content label))))"
  [tree test-id]
  (find-by-attr tree :data-testid test-id))

(defn find-all-by-testid
  "Like [[find-by-testid]] but returns a vector of EVERY matching
  node, in depth-first order. Empty vector when no match.

  Equivalent to `(find-all-by-attr tree :data-testid test-id)`."
  [tree test-id]
  (find-all-by-attr tree :data-testid test-id))

(defn find-by-testid-prefix
  "Return a vector of every hiccup node whose `:data-testid` STARTS
  with `prefix`. Useful for view layouts that mint a family of testids
  off a stable stem (e.g. `\"counter-row-1\"`, `\"counter-row-2\"`,
  …).

  Equivalent to `(find-by-attr-prefix tree :data-testid prefix)`."
  [tree prefix]
  (find-by-attr-prefix tree :data-testid prefix))

;; ---------------------------------------------------------------------------
;; Driving handlers
;; ---------------------------------------------------------------------------

(defn invoke-handler
  "Find the handler under `event-key` on `node` and call it with the
  supplied args. Returns the handler's return value (typically nil for
  re-frame `:on-click` handlers that `dispatch` for side effects).

  Throws (CLJS: `js/Error`, JVM: `ex-info`) when:
    - `node` is nil or not a hiccup vector
    - the node has no attrs map
    - no handler is registered under `event-key`

  The throwing failure mode is intentional — a missing handler is
  almost always a test bug (wrong testid, wrong key, view changed),
  not a passing case.

  Use to drive a click and assert state changed downstream:

      (let [tree (counter-view {:n 0})
            btn  (find-by-testid tree \"counter-inc\")]
        (invoke-handler btn :on-click)
        (is (= 1 (get-frame-db [:n]))))"
  [node event-key & args]
  (when-not (vector? node)
    (throw (ex-info "invoke-handler: node must be a hiccup vector"
                    {:node node :event-key event-key})))
  (let [h (extract-handler node event-key)]
    (when-not (fn? h)
      (throw (ex-info (str "invoke-handler: no handler under " event-key)
                      {:node node :event-key event-key :handler h})))
    (apply h args)))

;; ---------------------------------------------------------------------------
;; Authoring side
;; ---------------------------------------------------------------------------

(defn testid
  "Build an attrs map carrying `:data-testid id`. Optional `extra`
  map merges additional attrs (its keys win on collision EXCEPT for
  `:data-testid`, which is always set to `id`).

  Use at the view call site:

      [:button (testid \"counter-inc\" {:on-click #(rf/dispatch [:counter/inc])})
       \"+\"]

  Reads as one assertion-friendly fragment instead of inline
  `{:data-testid \"...\" :on-click ...}`. Pick whichever style is
  clearer in context — both work with [[find-by-testid]]."
  ([id]
   {:data-testid id})
  ([id extra]
   (assoc extra :data-testid id)))
