(ns re-frame.fresco.hooks-island-cljs-test
  "THE TWO HOOKS, WHERE NO DOM IS NEEDED.

  `re-frame.fresco.native` is two React hooks — `n/use-sub` and
  `n/use-frame` — and nothing else. An island is an ordinary React
  component, raw or UIx, and it reaches Fresco state through these two. Most of what is true about them is
  true only once React is driving a real fiber — identity across
  re-renders, StrictMode's double mount, teardown, the crossing through
  `h/defhost` — and that is `hooks_island_dom_cljs_test`'s subject.

  Two claims do NOT need a fiber, and they are the ones here, because
  the server renderer runs a component body for real:

  | row | what it establishes |
  |---|---|
  | [[a-read-under-a-live-frame-answers-without-committing-anything]] | the COLD tier. A body runs, the value is right, and the runtime retained nothing — because nothing committed. |
  | [[both-hooks-refuse-outside-every-frame-and-each-names-itself]] | the refusal, and that the two hooks are distinguishable in it. |

  ## Why the server renderer is the harness

  `renderToStaticMarkup` invokes bodies, resolves context and runs
  hooks, in a Node process with no `document`. What it does NOT do is
  commit — React never calls `subscribe` on the server — so it is also
  the only harness in which *what a render retains* can be read as a
  flat zero. A DOM lane would have to distinguish \"retained nothing\"
  from \"retained and then released\", and those are different claims.

  ## The island is rendered as itself, under the runtime's own provider

  No `h/defhost` here. The rows are about the HOOKS' contract — what a
  body sees, what it retains, what it refuses — and a crossing above
  the island would add a root boundary whose own entry and shell would
  sit in every count below. So each island element is handed to React
  directly, under `mount/provider` (the same context `h/render!`
  installs) or under nothing, and the crossing is measured where it
  costs something: the DOM lane."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.core :as rf]
            [re-frame.fresco.impl.collector :as rf.fresco.impl.collector]
            [re-frame.fresco.impl.mount :as rf.fresco.impl.mount]
            [re-frame.fresco.native :as rf.fresco.native]
            [re-frame.fresco.test.runtime :as rf.fresco.test.runtime]
            [re-frame.test-support :as rf.test-support]
            ["react" :as react]
            ["react-dom/server" :as react-dom-server]))

(def ^:private frame-id ::hooks-island)

;; Registered ABOVE `use-fixtures`, as every suite in this package does:
;; the reset fixture captures its source-store baseline when the
;; `use-fixtures` form is EVALUATED, so a registration written below it is
;; erased before the first row runs.
(rf/reg-sub ::price (fn [db [_ sym]] (get-in db [:prices sym])))
(rf/reg-event ::seed (fn [_ [_ prices]] {:db {:prices prices}}))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.uix/adapter
     :ambient-frame nil
     :init-fn       (fn [] (rf.fresco.impl.collector/reset-runtime!))}))

;; ---------------------------------------------------------------------------
;; The islands
;; ---------------------------------------------------------------------------

(defn- reader
  "Reads one subscription, and nothing else. Raw React."
  [^js props]
  (react/createElement "span" nil (str (rf.fresco.native/use-sub [::price (.-sym props)]))))

(defn- framed
  "Takes the frame-locked ops and reports the frame it was locked to."
  [^js _props]
  (let [ops (rf.fresco.native/use-frame)]
    (react/createElement "span" nil (str (:frame ops)))))

;; ---------------------------------------------------------------------------
;; Harness
;; ---------------------------------------------------------------------------

(defn- seat!
  "Create the frame and seed it."
  []
  (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
  (rf/make-frame {:id frame-id})
  (rf/with-frame frame-id (rf/dispatch-sync [::seed {"AAPL" 191}]))
  nil)

(defn- render-under-frame!
  "Render `element` with the frame context installed — the same provider
  `h/render!` installs, reached through the runtime's own door rather than
  through a hand-built context wrapper this suite would then be pinning
  instead of the product."
  [element]
  (react-dom-server/renderToStaticMarkup (rf.fresco.impl.mount/provider frame-id element)))

(defn- render-frameless!
  "Render `element` with NO provider above it. The island in a portal
  outside the app, in a second root, or in a test without the harness."
  [element]
  (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
  (react-dom-server/renderToStaticMarkup element))

(defn- refusal
  "Run `f` and answer the refusal's ex-data — or a marker saying what
  happened instead, so a failing row reports WHICH of the two ways it
  failed rather than throwing out of the assertion."
  [f]
  (try
    (f)
    {::outcome :returned-without-refusing}
    (catch :default e
      (or (ex-data e) {::outcome :threw-without-ex-data ::message (ex-message e)}))))

(defn- cold-residue
  "The census with the entry cache projected out — the four counts a
  cold read must leave at zero."
  []
  (dissoc (rf.fresco.test.runtime/residue) :entries))

;; ---------------------------------------------------------------------------
;; 1. The cold tier — a read before any commit
;; ---------------------------------------------------------------------------

(deftest a-read-under-a-live-frame-answers-without-committing-anything
  (seat!)
  (testing "the island reads the frame it is mounted in, on a render that
            never commits — which is every island's FIRST render, since
            React calls `subscribe` in a passive effect after the commit.
            Narrowing caught: a hook whose only read path is the warm cell
            deref. It would answer nil here and on the first render of
            every island ever mounted, and the DOM lane would not see it
            because React repaints the moment the subscription lands"
    (is (= "<span>191</span>"
           (render-under-frame! (react/createElement reader #js {:sym "AAPL"})))))

  (testing "and it RETAINED nothing. A cold read is a pure probe:
            no cell, no reader membership, no boundary, no edge, no
            disposal obligation. Narrowing caught: acquiring during the
            render rather than at commit — the ownership state machine's
            one prohibition — which would leave a cell and a membership
            behind on a render React discarded, and which is invisible
            from the markup because the markup is right either way"
    (is (= {:cells 0 :cell-refs 0 :boundaries 0 :edges 0} (cold-residue)))))

;; ---------------------------------------------------------------------------
;; 2. The refusal, and that the two hooks are distinguishable in it
;; ---------------------------------------------------------------------------

(deftest both-hooks-refuse-outside-every-frame-and-each-names-itself
  (testing "`n/use-sub` outside every frame refuses with
            `:rf.error/no-frame-context`. Narrowing caught: resolving
            through the dynamic-var tier or falling back to `:rf/default`
            — either would answer SOMETHING here, and an island silently
            reading a frame its own subtree is not under is the isolation
            failure the hooks must not have"
    (let [data (refusal #(render-frameless! (react/createElement reader #js {:sym "AAPL"})))]
      (is (= :rf.error/no-frame-context (:rf.error/id data)))
      (is (= 're-frame.fresco.native/use-sub (:where data)))))

  (testing "`n/use-frame` refuses identically, and names ITSELF. The two
            `:where` values are the reason the shell's resolution takes
            one: a single hardcoded `:where` would send a reader of either
            refusal to the boundary shell, which is not what refused"
    (let [data (refusal #(render-frameless! (react/createElement framed nil)))]
      (is (= :rf.error/no-frame-context (:rf.error/id data)))
      (is (= 're-frame.fresco.native/use-frame (:where data))))))
