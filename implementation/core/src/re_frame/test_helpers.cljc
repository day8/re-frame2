(ns re-frame.test-helpers
  "View-assertion test helpers — walk a hiccup tree by `:data-testid`,
  read out content and event-handlers, invoke an attached handler.

  ## See also — `re-frame.test-support` (rf2-v7kjq)

  Sibling namespace covering the **runtime-state assertion axis** —
  registrar snapshot/restore, the `make-reset-runtime-fixture`
  per-process `:each` fixture, `dispatch-sequence`, the `assert-*-equals`
  fn-family (mirrors the `:rf.assert/*` Story event-family),
  bounded-deadline `poll-until`.

  This namespace owns the **view-tree assertion axis**: call the view-fn
  directly, walk the returned hiccup, assert on content (catches
  state-correct-but-view-broken bugs) or invoke a handler (catches
  wrong-frame dispatch bugs).

  A test that exercises events / subs / machines reaches
  `re-frame.test-support`. A test that asserts on rendered view content
  reaches here. A test doing both `:require`s both. See [Spec 008
  §Audience-split]
  (../../../../../spec/008-Testing.md#audience-split--re-frametest-support-vs-re-frametest-helpers-rf2-v7kjq)
  for the axis rationale.

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
    view call site. Optional `extra` map merges additional attrs.

  ### Single-frame e2e fixture (rf2-wy1ac)
  - [[with-app-fixture]] — macro. Brackets `body` with a fresh frame
    (created, bound as `*current-frame*`, destroyed on exit) and
    optionally runs an `:install` hook and stashes a `:root-view`
    for downstream `expect-text` / `wait-until` calls. Compresses the
    five-line per-test fixture pattern to two lines (frame + body).
  - [[expect-text]] — locate a `:data-testid` in the root-view's
    rendered hiccup and assert on its text content via
    `clojure.test/is`. 2-arity uses the fixture-stashed root view; the
    3-arity accepts an explicit tree.
  - [[wait-until]] — bounded-deadline poll for a condition. JVM is
    synchronous; CLJS returns a `js/Promise` (composes with
    `cljs.test/async`). Replaces incidental fixed-sleep waits when the
    post-condition is observable in view-state or app-db."
  (:require [clojure.string :as str]
            [re-frame.frame :as frame]
            #?(:clj  [clojure.test :as ctest]
               :cljs [cljs.test :as ctest :include-macros true]))
  #?(:cljs (:require-macros
             [re-frame.test-helpers :refer [with-app-fixture]])))

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
        ;; clj-kondo can't refine `render-fn` to non-nil through the
        ;; surrounding `cond`+`some?` guard, so it reads the `apply`
        ;; below as "received: nil". The guard is correct — silence
        ;; the type-inference miss.
        #_:clj-kondo/ignore
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
    (throw (ex-info ":rf.error/invoke-handler-bad-node"
                    {:rf.error/id :rf.error/invoke-handler-bad-node
                     :where     'rf/invoke-handler
                     :recovery  :no-recovery
                     :reason    "invoke-handler's node must be a hiccup vector"
                     :node      node
                     :event-key event-key})))
  (let [h (extract-handler node event-key)]
    (when-not (fn? h)
      (throw (ex-info ":rf.error/invoke-handler-missing"
                      {:rf.error/id :rf.error/invoke-handler-missing
                       :where     'rf/invoke-handler
                       :recovery  :no-recovery
                       :reason    (str "invoke-handler found no handler fn under " event-key)
                       :node      node
                       :event-key event-key
                       :handler   h})))
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

;; ---------------------------------------------------------------------------
;; Single-frame e2e fixture (rf2-wy1ac)
;;
;; The trio below — `with-app-fixture`, `expect-text`, `wait-until` —
;; compresses the dominant single-frame e2e test pattern from five
;; lines of fixture boilerplate to two. Multi-frame setups (Causa,
;; Story) keep using `rf/with-frame` + the lower-level primitives
;; directly; this fixture is for the common app-developer case.
;;
;; The shape:
;;
;;     (deftest counter-increments
;;       (th/with-app-fixture {:install  counter/install!
;;                             :root-view counter/main}
;;                            :test-app
;;         (rf/dispatch-sync [:counter/inc])
;;         (rf/dispatch-sync [:counter/inc])
;;         (th/expect-text :counter-display \"2\")))
;;
;; `with-app-fixture` creates the frame, pins it as `*current-frame*`,
;; calls the `:install` fn inside that scope (so any `reg-event-db` /
;; `reg-sub` etc. land while the frame is active), then runs `body`
;; with the root view stashed for `expect-text`. On exit (success or
;; exception) the frame is destroyed.
;; ---------------------------------------------------------------------------

(def ^:dynamic *current-root-view*
  "Root-view fn stashed by [[with-app-fixture]] for the body's dynamic
  extent. [[expect-text]] and [[wait-until]]'s 2-arity testid form read
  this var to know which view-fn to call when assembling the hiccup
  tree. `nil` outside a fixture body; callers that want to operate on
  an explicit tree use the 3-arity shapes instead."
  nil)

(def ^:dynamic *current-root-view-args*
  "Args vector passed to `*current-root-view*` when rendering the
  tree. Defaults to `[]` (the common Reagent-style zero-arg view).
  [[with-app-fixture]] sets this from the fixture opts' `:root-view-args`
  key — callers whose root view takes arguments (e.g. a props map)
  supply them once at the fixture site, not at every `expect-text`
  call."
  [])

#?(:clj
   (defmacro with-app-fixture
     "Bracket `body` with a fresh single-frame fixture — the dominant
     shape for app-developer e2e tests (rf2-wy1ac).

     Two call shapes — first arg is the discriminator:

       (with-app-fixture opts-map frame-id body+)
       (with-app-fixture opts-map           body+)   ; anonymous gensym'd id

     `opts-map` (all keys optional):

       :install         zero-arg fn called *inside* the frame's dynamic
                        extent, after the frame is created. Typical
                        body: `(reg-event-db ...)` / `(reg-sub ...)` /
                        `(reg-view ...)` calls that the test relies on.
                        Registrations land in the global registrar; pair
                        this fixture with `re-frame.test-support/make-reset-runtime-fixture`
                        (or `with-fresh-registrar`) to roll them back
                        between tests.
       :root-view       view fn (a hiccup-returning function). Stashed in
                        `*current-root-view*` for the body so [[expect-text]]
                        and [[wait-until]]'s testid forms can find it
                        without an explicit tree argument.
       :root-view-args  args vector passed to `:root-view` when rendering
                        the tree. Defaults to `[]`. Use when the view fn
                        takes a props map or similar.
       :frame-config    extra map merged into the frame's config map
                        (passed to `make-frame` / `reg-frame`). Use for
                        `:on-create`, `:fx-overrides`, `:interceptor-overrides`,
                        `:interceptors` and the rest of the frame-shape
                        contract per Spec 002.

     The macro:
       1. Creates the frame (anonymous gensym'd id, or the supplied
          `frame-id`).
       2. Binds `re-frame.frame/*current-frame*` to that id for the
          dynamic extent of `body`.
       3. Calls `:install` (if supplied) — zero-arg, with the frame
          already bound.
       4. Binds `*current-root-view*` / `*current-root-view-args*` from
          the opts (if `:root-view` is supplied).
       5. Runs `body`.
       6. In a `finally`, calls `(destroy-frame! id)` so the frame is
          released regardless of whether `body` returned normally.

     Per Spec 008 §Test fixture lifecycle patterns — this is the
     ergonomic shorthand over the Pattern 1 / Pattern 2 long forms."
     {:arglists '([opts-map frame-id body+] [opts-map body+])}
     [opts & more]
     (let [[frame-id body] (if (and (seq more) (keyword? (first more)))
                             [(first more) (rest more)]
                             [nil more])
           opts-sym       (gensym "opts")
           install-sym    (gensym "install")
           root-view-sym  (gensym "root-view")
           root-args-sym  (gensym "root-args")
           frame-cfg-sym  (gensym "frame-config")
           id-sym         (gensym "frame-id")
           create-form    (if frame-id
                            `(re-frame.frame/reg-frame ~frame-id ~frame-cfg-sym)
                            `(re-frame.frame/make-frame ~frame-cfg-sym))]
       `(let [~opts-sym       ~opts
              ~install-sym    (:install      ~opts-sym)
              ~root-view-sym  (:root-view    ~opts-sym)
              ~root-args-sym  (or (:root-view-args ~opts-sym) [])
              ~frame-cfg-sym  (or (:frame-config   ~opts-sym) {})
              ~id-sym         ~create-form]
          (try
            (binding [re-frame.frame/*current-frame*                 ~id-sym
                      re-frame.test-helpers/*current-root-view*      ~root-view-sym
                      re-frame.test-helpers/*current-root-view-args* ~root-args-sym]
              (when ~install-sym (~install-sym))
              ~@body)
            (finally
              (re-frame.frame/destroy-frame! ~id-sym)))))))

(defn- render-current-root
  "Render the fixture-stashed root view to a hiccup tree, or throw
  with a helpful message when no `:root-view` was supplied."
  []
  (if-let [view *current-root-view*]
    (apply view *current-root-view-args*)
    (throw (ex-info
             ":rf.error/no-root-view"
             {:rf.error/id :rf.error/no-root-view
              :where    'rf/expect-text
              :recovery :no-recovery
              :reason   (str "expect-text / wait-until called outside a "
                             "`with-app-fixture` body, OR the fixture did not "
                             "supply :root-view. Pass an explicit tree as the "
                             "first arg, or set :root-view in the fixture opts.")}))))

(defn- coerce-testid-string
  "Allow testids supplied as keywords (`:counter-display`) at the call
  site, while the underlying hiccup-walk keys on the string form. A
  string testid passes through; anything else (`nil`, a number) is an
  argument error."
  [testid]
  (cond
    (keyword? testid) (name testid)
    (string?  testid) testid
    :else
    (throw (ex-info ":rf.error/testid-bad-arg"
                    {:rf.error/id :rf.error/testid-bad-arg
                     :where    'rf/find-by-testid
                     :recovery :no-recovery
                     :reason   (str "testid must be a keyword or string, got "
                                    (pr-str testid))
                     :testid   testid}))))

(defn expect-text
  "Assert that the hiccup node carrying `:data-testid testid` has
  `text-content` equal to `expected`. Reports via `clojure.test/is`
  — failure carries the actual text in the diagnostic.

  Two call shapes:

    (expect-text testid expected)
      Uses the fixture-stashed root view from `*current-root-view*`
      (set by [[with-app-fixture]]). The view fn is called with
      `*current-root-view-args*` to assemble the tree.

    (expect-text tree testid expected)
      Walks the supplied `tree` directly — no fixture required.

  `testid` may be a string (`\"counter-display\"`) or a keyword
  (`:counter-display`); keywords are coerced via `name`.

  Returns `true` on pass, `false` on fail — the `clojure.test`
  failure has already been reported in either case, so callers
  rarely care about the boolean.

  Per Spec 008 §Single-frame e2e fixture (rf2-wy1ac)."
  ([testid expected]
   (expect-text (render-current-root) testid expected))
  ([tree testid expected]
   (let [testid-str (coerce-testid-string testid)
         node       (find-by-testid tree testid-str)
         actual     (when node (text-content node))
         pass?      (= expected actual)]
     (ctest/do-report
       {:type     (if pass? :pass :fail)
        :message  (cond
                    (nil? node)
                    (str "expect-text: no node with :data-testid "
                         (pr-str testid-str)
                         " found in tree")
                    :else
                    (str "expect-text: text mismatch at :data-testid "
                         (pr-str testid-str)))
        :expected expected
        :actual   actual})
     pass?)))

;; ---------------------------------------------------------------------------
;; wait-until — bounded-deadline poll for async-stable assertions
;;
;; The view-test counterpart to `re-frame.test-support/poll-until`:
;; same shape (JVM-sync / CLJS-Promise), tuned for the hiccup-walk
;; pattern. The 1-arity (predicate) is a thin alias on poll-until
;; semantics; the testid-form (`(wait-until testid expected)`) polls
;; the fixture-stashed root view until its `:data-testid` node's text
;; matches `expected`, or the deadline elapses.
;;
;; Use this when an event cascade is async (HTTP, dispatched
;; machine transition, scheduled event via `dispatch`) and the
;; post-condition is observable in the rendered view. For sync
;; cascades, `expect-text` after `dispatch-sync` is sufficient.
;; ---------------------------------------------------------------------------

(defn- wait-timeout-error
  "Shared timeout-error constructor so test code can pattern-match on
  the canonical `:rf.error/id :rf.error/wait-until-timeout` discriminator
  (per Spec 009) regardless of runtime."
  [label elapsed-ms]
  (ex-info ":rf.error/wait-until-timeout"
           {:rf.error/id :rf.error/wait-until-timeout
            :where       'rf/wait-until
            :recovery    :no-recovery
            :reason      (str "wait-until timed out"
                              (when label (str " — " label)))
            :elapsed-ms  elapsed-ms
            :label       label}))

#?(:clj
   (defn- jvm-wait-until-pred
     [pred {:keys [timeout-ms interval-ms label]
            :or   {timeout-ms 2000 interval-ms 5}}]
     (let [start    (System/currentTimeMillis)
           deadline (+ start timeout-ms)]
       (loop []
         (let [v (try (pred) (catch Throwable _ false))]
           (cond
             v v
             (>= (System/currentTimeMillis) deadline)
             (throw (wait-timeout-error
                      label (- (System/currentTimeMillis) start)))
             :else (do (Thread/sleep ^long interval-ms) (recur))))))))

#?(:cljs
   (defn- cljs-wait-until-pred
     [pred {:keys [timeout-ms interval-ms label]
            :or   {timeout-ms 2000 interval-ms 5}}]
     (let [start    (.now js/Date)
           deadline (+ start timeout-ms)]
       (js/Promise.
         (fn [resolve reject]
           (letfn [(settle [v]
                     (cond
                       v (resolve v)
                       (>= (.now js/Date) deadline)
                       (reject (wait-timeout-error
                                 label (- (.now js/Date) start)))
                       :else (js/setTimeout tick interval-ms)))
                   (tick []
                     (let [raw (try (pred)
                                    (catch :default _ false))]
                       (if (instance? js/Promise raw)
                         (-> ^js/Promise raw
                             (.then settle)
                             (.catch (fn [_] (settle false))))
                         (settle raw))))]
             (tick)))))))

(defn wait-until
  "Bounded-deadline poll until a condition is truthy. The view-test
  counterpart to `re-frame.test-support/poll-until`.

  Two call shapes:

    (wait-until pred)
    (wait-until pred opts)
      Poll `(pred)` until truthy or the deadline elapses.

    (wait-until testid expected)
    (wait-until testid expected opts)
      Poll the fixture-stashed root view (`*current-root-view*`)
      until `(text-content (find-by-testid tree testid)) = expected`,
      or the deadline elapses. Equivalent to:
        (wait-until #(= expected
                        (text-content
                          (find-by-testid (render-root) testid))))

  `opts` (all optional):
    :timeout-ms   default 2000 — overall deadline.
    :interval-ms  default 5    — gap (ms) between probes.
    :label        string/keyword used in the timeout message.

  Per-platform shape (matching `poll-until`):
    JVM:  synchronous — returns the truthy value, throws `ex-info`
                        with `:rf.test-helpers/wait-timeout` `true` on
                        timeout.
    CLJS: async       — returns a `js/Promise`. Resolves with the
                        truthy value, rejects with an `ex-info`-style
                        error on timeout. Compose with `cljs.test/async`.

  Use for async event flows (HTTP, scheduled events, machine `:after`
  transitions) that need to drain past `dispatch-sync`. Not a
  substitute for timer-semantics sleeps (grace-period elapse,
  throttle/debounce window) — those should keep their explicit sleep
  and annotate the intent locally.

  Per Spec 008 §Single-frame e2e fixture (rf2-wy1ac)."
  ([pred-or-testid]
   (wait-until pred-or-testid nil))
  ([pred-or-testid opts-or-expected]
   (cond
     (fn? pred-or-testid)
     #?(:clj  (jvm-wait-until-pred  pred-or-testid (or opts-or-expected {}))
        :cljs (cljs-wait-until-pred pred-or-testid (or opts-or-expected {})))

     ;; (wait-until testid expected) — testid form, default opts.
     :else
     (wait-until pred-or-testid opts-or-expected nil)))
  ([testid expected opts]
   (let [testid-str (coerce-testid-string testid)
         label      (or (:label opts)
                        (str "text under :data-testid " (pr-str testid-str)
                             " = " (pr-str expected)))
         opts*      (assoc (or opts {}) :label label)
         probe      (fn []
                      (let [node (find-by-testid (render-current-root)
                                                 testid-str)
                            actual (when node (text-content node))]
                        (when (= expected actual)
                          actual)))]
     #?(:clj  (jvm-wait-until-pred  probe opts*)
        :cljs (cljs-wait-until-pred probe opts*)))))
