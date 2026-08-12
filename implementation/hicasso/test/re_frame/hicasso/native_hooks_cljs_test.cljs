(ns re-frame.hicasso.native-hooks-cljs-test
  "THE TWO NATIVE HOOKS, WHERE NO DOM IS NEEDED (rf2-hic-031).

  `n/use-sub` and `n/use-frame` are React hooks and most of what is true
  about them is true only once React is driving a real fiber — identity
  across re-renders, StrictMode's double mount, teardown. That is
  `native_hooks_dom_cljs_test`'s subject and it is the larger half.

  Three claims do NOT need a fiber, and they are the ones here, because
  the server renderer runs a component body for real:

  | row | what it establishes |
  |---|---|
  | [[a-native-read-under-a-live-frame-answers-without-committing-anything]] | the COLD tier. A body runs, the value is right, and the runtime retained nothing — because nothing committed. |
  | [[both-hooks-refuse-outside-every-frame-and-each-names-itself]] | the refusal the guide's chapter 10 table promises, and that the two hooks are distinguishable in it. |
  | [[use-frame-answers-the-runtimes-own-row-rather-than-capturing-its-own]] | the hook is not a second `capture-frame`. This is the row the incarnation rule rests on, and it is an IDENTITY test because an equality test passes for the wrong implementation. |

  ## Why the server renderer is the harness

  `native_fence_cljs_test` established it for this tier and the reason is
  the same: `renderToStaticMarkup` invokes bodies, resolves context and
  runs hooks, in a Node process with no `document`. What it does NOT do
  is commit — React never calls `subscribe` on the server — so it is
  also the only harness in which *what a render retains* can be read as
  a flat zero. A DOM lane would have to distinguish \"retained nothing\"
  from \"retained and then released\", and those are different claims.

  Nothing here is an SSR claim. A native component's server policy is
  `n/defcomponent`'s declaration (`{:server :render}` / Client-only) and
  its enforcement is `re-frame.hicasso.native-ssr-dom-cljs-test`'s; this
  file uses the server renderer as an instrument, exactly as the fence
  suite does. The islands below therefore DECLARE `{:server :render}`,
  because since rf2-hic-046 the policy decides whether a body runs on
  the server at all — an undeclared island is Client-only, so this
  instrument would read every row below as an empty string."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.core :as rf]
            [re-frame.hicasso.impl.collector :as collector]
            [re-frame.hicasso.impl.inventory :as inventory]
            [re-frame.hicasso.impl.mount :as mount]
            [re-frame.hicasso.native :as n]
            [re-frame.test-support :as test-support]
            ["react-dom/server" :as react-dom-server]))

(def ^:private frame-id ::native-hooks)

;; Registered ABOVE `use-fixtures`, as every suite in this package does:
;; the reset fixture captures its source-store baseline when the
;; `use-fixtures` form is EVALUATED, so a registration written below it is
;; erased before the first row runs.
(rf/reg-sub ::price (fn [db [_ sym]] (get-in db [:prices sym])))
(rf/reg-event ::seed (fn [_ [_ prices]] {:db {:prices prices}}))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
     :ambient-frame nil
     :init-fn       (fn [] (collector/reset-runtime!))}))

;; ---------------------------------------------------------------------------
;; The islands
;; ---------------------------------------------------------------------------

(defonce ^:private !observed-ops
  ;; What `use-frame` handed the body on its last run. A module atom rather
  ;; than a returned value because the thing under test is what a COMPONENT
  ;; saw, and a component's return value is markup.
  (atom nil))

(n/defcomponent reader
  "Reads one subscription, and nothing else.

  `{:server :render}` because the harness below is the server renderer
  and the policy is now READ (rf2-hic-046): a Client-only island's body
  does not run there, so the instrument needs the declaration. That is
  the policy working rather than a concession to the test."
  {:server :render}
  [^js props]
  (n/$ :span nil (str (n/use-sub [::price (.-sym props)]))))

(n/defcomponent framed
  "Takes the frame-locked ops and reports the frame it was locked to.
  `{:server :render}` for [[reader]]'s reason."
  {:server :render}
  [^js _props]
  (let [ops (n/use-frame)]
    (reset! !observed-ops ops)
    (n/$ :span nil (str (:frame ops)))))

;; ---------------------------------------------------------------------------
;; Harness
;; ---------------------------------------------------------------------------

(defn- seat!
  "Create the frame and seed it. Answers nothing — a row that wants the
  incarnation asks `frame/frame-incarnation-token` for it."
  []
  (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
  (rf/make-frame {:id frame-id})
  (rf/with-frame frame-id (rf/dispatch-sync [::seed {"AAPL" 191}]))
  nil)

(defn- render-under-frame!
  "Render `element` with the frame context installed — the same provider
  `h/root!` installs, reached through the runtime's own door rather than
  through a hand-built context wrapper this suite would then be pinning
  instead of the product."
  [element]
  (react-dom-server/renderToStaticMarkup (mount/provider frame-id element)))

(defn- render-frameless!
  "Render `element` with NO provider above it. The island in a portal
  outside the app, in a second root, or in a test without the harness —
  the three cases the guide's chapter 10 error table names."
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

;; ---------------------------------------------------------------------------
;; 1. The cold tier — a read before any commit
;; ---------------------------------------------------------------------------

(deftest a-native-read-under-a-live-frame-answers-without-committing-anything
  (seat!)
  (testing "the island reads the frame it is mounted in, on a render that
            never commits — which is every island's FIRST render, since
            React calls `subscribe` in a passive effect after the commit.
            Narrowing caught: a hook whose only read path is the warm cell
            deref. It would answer nil here and on the first render of
            every island ever mounted, and the DOM lane would not see it
            because React repaints the moment the subscription lands"
    (is (= "<span>191</span>" (render-under-frame! (n/$ reader {:sym "AAPL"})))))

  (testing "and it RETAINED nothing. A cold read is the observation port's
            probe: no cell, no reader membership, no boundary, no edge, no
            disposal obligation. Narrowing caught: acquiring during the
            render rather than at commit — the ownership state machine's
            one prohibition — which would leave a cell and a membership
            behind on a render React discarded, and which is invisible
            from the markup because the markup is right either way"
    (is (= {:cells 0 :cell-refs 0 :boundaries 0 :edges 0}
           (dissoc (inventory/residue) :entries))))

  (testing "`:entries` is deliberately outside that reading, and being
            exact about it is the difference between a census and a
            slogan. A read-set entry is minted during the RENDER — it is
            the cached `subscribe`/`getSnapshot` pair React is about to be
            handed — and claimed at the COMMIT that never came here; an
            unclaimed one belongs to
            `collector/entry-reap-horizon-ms`'s reaper. That is not a
            property of hooks: a boundary body whose render React
            discards leaves exactly the same one, and the hook seam mints
            through the same door precisely so there is one story"
    (is (= 1 (:entries (inventory/residue))))))

;; ---------------------------------------------------------------------------
;; 2. The refusal, and that the two hooks are distinguishable in it
;; ---------------------------------------------------------------------------

(deftest both-hooks-refuse-outside-every-frame-and-each-names-itself
  (testing "`n/use-sub` outside every frame refuses with the id the guide
            promises. Narrowing caught: resolving through the dynamic-var
            tier or falling back to `:rf/default` — either would answer
            SOMETHING here, and an island silently reading a frame its own
            subtree is not under is the isolation failure this tier must
            not have"
    (let [data (refusal #(render-frameless! (n/$ reader {:sym "AAPL"})))]
      (is (= :rf.error/no-frame-context (:rf.error/id data)))
      (is (= :mount-under-a-frame (:recovery data)))
      (is (= 're-frame.hicasso.native/use-sub (:where data)))))

  (testing "`n/use-frame` refuses identically, and names ITSELF. The two
            `:where` values are the reason the shell's resolution takes
            one: a single hardcoded `:where` would send a reader of either
            refusal to the boundary shell, which is not what refused"
    (let [data (refusal #(render-frameless! (n/$ framed nil)))]
      (is (= :rf.error/no-frame-context (:rf.error/id data)))
      (is (= 're-frame.hicasso.native/use-frame (:where data))))))

;; ---------------------------------------------------------------------------
;; 3. `use-frame` is the runtime's row, not a second capture
;; ---------------------------------------------------------------------------

(deftest use-frame-answers-the-runtimes-own-row-rather-than-capturing-its-own
  (seat!)
  (reset! !observed-ops nil)
  (let [markup (render-under-frame! (n/$ framed nil))
        ops    @!observed-ops]

    (testing "the shape is `capture-frame`'s, locked to the ambient frame"
      (is (= (str "<span>" frame-id "</span>") markup))
      (is (= frame-id (:frame ops)))
      (is (every? #(fn? (get ops %)) [:dispatch :dispatch-sync :subscribe])))

    (testing "and it is IDENTICAL to the arm's own memo row for this frame
              — the bundle a lowered intent's ambient dispatch was minted
              over, not a second `rf/capture-frame` the hook took for
              itself.

              This is the row the incarnation rule rests on, and equality
              would not have caught it: `rf/capture-frame` called twice on
              one live frame answers two maps that are `=` and are not the
              same object. The UIx adapter's `use-frame` is exactly that
              implementation, memoised on the frame KEYWORD — which is `=`
              across a same-id reincarnation, so it would hand a destroyed
              incarnation's bundle out forever. `native_hooks_dom_cljs_test`
              drives the reincarnation itself; this row is where the
              structural reason lives"
      (is (identical? (:ops (collector/frame-row frame-id)) ops)))))
