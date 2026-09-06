(ns re-frame.hicasso.hooks-island-cljs-test
  "THE TWO HOOKS, WHERE NO DOM IS NEEDED.

  `re-frame.hicasso.native` is two React hooks — `n/use-sub` and
  `n/use-frame` — and nothing else, by the rf2-6c12m.3 ruling. An
  island is an ordinary React component, raw or UIx, and it reaches
  Hicasso state through these two. Most of what is true about them is
  true only once React is driving a real fiber — identity across
  re-renders, StrictMode's double mount, teardown, the crossing through
  `h/defhost` — and that is `hooks_island_dom_cljs_test`'s subject.

  Four claims do NOT need a fiber, and they are the ones here, because
  the server renderer runs a component body for real:

  | row | what it establishes |
  |---|---|
  | [[a-read-under-a-live-frame-answers-without-committing-anything]] | the COLD tier. A body runs, the value is right, and the runtime retained nothing — because nothing committed. Both arms. |
  | [[both-hooks-refuse-outside-every-frame-and-each-names-itself]] | the refusal, and that the two hooks are distinguishable in it. |
  | [[use-frame-answers-the-runtimes-own-row-rather-than-capturing-its-own]] | the hook is not a second `capture-frame`. This is the row the incarnation rule rests on, and it is an IDENTITY test because an equality test passes for the wrong implementation. |
  | [[the-namespace-is-the-two-hooks]] | the membership pin: `use-sub` and `use-frame` are present, and the exact census is armed for the wave-2 bead. |

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
  directly, under `mount/provider` (the same context `h/mount!`
  installs) or under nothing, and the crossing is measured where it
  costs something: the DOM lane."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.core :as rf]
            [re-frame.hicasso.impl.collector :as rf.hicasso.impl.collector]
            [re-frame.hicasso.impl.mount :as rf.hicasso.impl.mount]
            [re-frame.hicasso.native :as rf.hicasso.native]
            [re-frame.hicasso.test.runtime :as rf.hicasso.test.runtime]
            [re-frame.test-support :as rf.test-support]
            [uix.core :as uix :refer-macros [defui]]
            ["react" :as react]
            ["react-dom/server" :as react-dom-server])
  (:require-macros [re-frame.hicasso.expansion-probe :as rf.hicasso.expansion-probe]))

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
     :init-fn       (fn [] (rf.hicasso.impl.collector/reset-runtime!))}))

;; ---------------------------------------------------------------------------
;; The islands
;; ---------------------------------------------------------------------------

(defonce ^:private !observed-ops
  ;; What `use-frame` handed the body on its last run. A module atom rather
  ;; than a returned value because the thing under test is what a COMPONENT
  ;; saw, and a component's return value is markup.
  (atom nil))

(defn- reader
  "Reads one subscription, and nothing else. Raw React."
  [^js props]
  (react/createElement "span" nil (str (rf.hicasso.native/use-sub [::price (.-sym props)]))))

(defn- framed
  "Takes the frame-locked ops and reports the frame it was locked to."
  [^js _props]
  (let [ops (rf.hicasso.native/use-frame)]
    (reset! !observed-ops ops)
    (react/createElement "span" nil (str (:frame ops)))))

(defui uix-reader
  "The same read in a UIx `defui`: the hook does not know which dialect
  called it."
  [{:keys [sym]}]
  (uix/$ :span (str (rf.hicasso.native/use-sub [::price sym]))))

(defn- uix-arm
  "The plain React shim every crossing into UIx needs — UIx's ABI is a
  carrier object its own `uix/$` builds."
  [^js props]
  (uix/$ uix-reader {:sym (.-sym props)}))

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
  `h/mount!` installs, reached through the runtime's own door rather than
  through a hand-built context wrapper this suite would then be pinning
  instead of the product."
  [element]
  (react-dom-server/renderToStaticMarkup (rf.hicasso.impl.mount/provider frame-id element)))

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
  (dissoc (rf.hicasso.test.runtime/residue) :entries))

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
    (is (= {:cells 0 :cell-refs 0 :boundaries 0 :edges 0} (cold-residue))))

  (testing "`:entries` is deliberately outside that reading, and being
            exact about it is the difference between a census and a
            slogan. A read-set entry is minted during the RENDER — it is
            the cached `subscribe`/`getSnapshot` pair React is about to be
            handed — and claimed at the COMMIT that never came here; an
            unclaimed one belongs to `collector/entry-reap-horizon-ms`'s
            reaper. That is not a property of hooks: a boundary body whose
            render React discards leaves exactly the same one, and the
            hook seam mints through the same door precisely so there is
            one story"
    (is (= 1 (:entries (rf.hicasso.test.runtime/residue)))))

  (testing "the UIx arm is the same hook and the same reading: the value
            is right, and nothing was committed"
    (is (= "<span>191</span>"
           (render-under-frame! (react/createElement uix-arm #js {:sym "AAPL"}))))
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
      (is (= 're-frame.hicasso.native/use-sub (:where data)))))

  (testing "`n/use-frame` refuses identically, and names ITSELF. The two
            `:where` values are the reason the shell's resolution takes
            one: a single hardcoded `:where` would send a reader of either
            refusal to the boundary shell, which is not what refused"
    (let [data (refusal #(render-frameless! (react/createElement framed nil)))]
      (is (= :rf.error/no-frame-context (:rf.error/id data)))
      (is (= 're-frame.hicasso.native/use-frame (:where data)))))

  (testing "and a UIx island refuses the same way: the dialect the body is
            written in does not change where the frame comes from"
    (let [data (refusal #(render-frameless! (react/createElement uix-arm #js {:sym "AAPL"})))]
      (is (= :rf.error/no-frame-context (:rf.error/id data)))
      (is (= 're-frame.hicasso.native/use-sub (:where data))))))

;; ---------------------------------------------------------------------------
;; 3. `use-frame` is the runtime's row, not a second capture
;; ---------------------------------------------------------------------------

(deftest use-frame-answers-the-runtimes-own-row-rather-than-capturing-its-own
  (seat!)
  (reset! !observed-ops nil)
  (let [markup (render-under-frame! (react/createElement framed nil))
        ops    @!observed-ops]

    (testing "the shape is `capture-frame`'s, locked to the ambient frame"
      (is (= (str "<span>" frame-id "</span>") markup))
      (is (= frame-id (:frame ops)))
      (is (every? #(fn? (get ops %)) [:dispatch :dispatch-sync :subscribe])))

    (testing "and it is IDENTICAL to the runtime's own memo row for this
              frame — the bundle a lowered intent's ambient dispatch was
              minted over, not a second `rf/capture-frame` the hook took
              for itself.

              This is the row the incarnation rule rests on, and equality
              would not have caught it: `rf/capture-frame` called twice on
              one live frame answers two maps that are `=` and are not the
              same object. The UIx adapter's `use-frame` is exactly that
              implementation, memoised on the frame KEYWORD — which is `=`
              across a same-id reincarnation, so it would hand a destroyed
              incarnation's bundle out forever. `hooks_island_dom_cljs_test`
              drives the reincarnation itself; this row is where the
              structural reason lives"
      (is (identical? (:ops (rf.hicasso.impl.collector/frame-row frame-id)) ops)))))

;; ---------------------------------------------------------------------------
;; 4. The membership pin
;; ---------------------------------------------------------------------------

(def ^:private publics
  "Every public var name in `re-frame.hicasso.native`, read from the
  compiler at expansion — the analyser's public defs for the runtime
  half, the JVM namespace's public vars for the macro half."
  (set (rf.hicasso.expansion-probe/public-vars re-frame.hicasso.native)))

(deftest the-namespace-is-the-two-hooks
  (testing "the census is not vacuous: the probe really read a namespace.
            Narrowing caught: an analyser lookup answering an empty map on
            a miss, which would make the row below pass by describing
            nothing"
    (is (seq publics)))

  (testing "the two hooks are there, and they resolve"
    (is (contains? publics "use-sub"))
    (is (contains? publics "use-frame"))
    (is (fn? rf.hicasso.native/use-sub))
    (is (fn? rf.hicasso.native/use-frame)))

  (testing "and they are the whole namespace — a third public var reds
            here at the diff that adds it (rf2-6c12m.3)"
    (is (= #{"use-sub" "use-frame"} publics)
        "the namespace's public census is exactly the two hooks")))
