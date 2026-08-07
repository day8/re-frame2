(ns re-frame.bench.hicasso.arm1.hydrate-dom-cljs-test
  "THE HYDRATION DOOR, MOUNTED (rf2-2rtt6.84).

  `arm1/mount/hydrate-root!` adopting real server-shaped DOM in a real
  browser: the closer closes the window, the reap horizon leaves the
  adopted subscriptions alone, presence children are born PRESENT, a
  controlled input arrives with React's `defaultValue` mirror intact, and
  the body-run counter reports bodies that RAN rather than renders React
  was asked for.

  ## Where the \"server\" bytes come from, stated plainly

  **Not from `react-dom/server`.** The Node render entry is
  rf2-2rtt6.86's bead and the end-to-end spike is rf2-2rtt6.87's; this
  file is about the DOOR, so its fixture markup is this same tree's own
  CLIENT render, captured as `innerHTML` **under an open adoption
  window** — which is the state a server render is in, and is why the
  captured bytes are born-present rather than mid-enter.

  That substitution is honest for what is asserted here and dishonest for
  what is not, so the markup contract is asserted **explicitly on the
  captured bytes** ([[the-hydrated-controlled-input-keeps-its-mirror]]
  reads `value=` out of them) rather than assumed. A server that emits
  something else fails that row instead of sliding past it. The
  byte-identity and canonical-DOM parity claims belong to the spike and
  are deliberately absent.

  ## Two ways a row here could go green over a live failure

  1. **React routes a render or effect exception to `reportError`**, so
     `cljs.test` can read zero failures across a component that threw.
     Every row runs inside `hydration-support/open-console-capture!`,
     which listens on `window`'s `error` event AND spies `console.error`
     — the two channels React 19 reports a hydration mismatch on — and
     asserts the capture is empty.

     **The window is held open until [[adopted!]] resolves, and it has
     to be** (rf2-2rtt6.87, measured). `hydrateRoot` is called plain, so
     it RETURNS BEFORE THE TREE IS ADOPTED: React schedules the
     hydration render, and the complaint — if there is one — arrives in
     a later turn. These rows originally closed the window when the call
     returned, and the SSR spike's mutation proof measured what that
     window can see: hydrating an eight-row screen against nine-row
     server bytes reported **1 complaint across the adoption and 0 at
     the call's return**. A window that shuts on return is open across
     the call and shut across the render, i.e. shut across the only part
     that can complain, so every `(is (= [] @captured))` taken through
     one was vacuous.
  2. **`act` and `flushSync` are not the browser's schedule.** Adoption
     is React's own concurrent business, so `hydration-support/adopted!`
     waits on real timers for the closer's passive effect and never pulls
     it forward. `mount/dispatch!` still flushes, but only in rows whose
     claim is about the model rather than about the turn.

  Both live in `arm1/hydration_support.cljs` — one copy, shared with the
  SSR spike (rf2-2rtt6.87), because a second copy of a refusal is the
  copy that quietly weakens.

  Runtime: `-dom-cljs-test`, so `:browser-test` runs it against a real
  React DOM; under `:node-test` every DOM claim degrades to a stated
  skip."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.bench.hicasso.arm1.hydration-support
             :refer [adopted! open-console-capture! server-dom! server-node?
                     stamp-server-nodes!]]
            [re-frame.bench.hicasso.arm1.mount :as mount]
            [re-frame.bench.hicasso.arm1.presence :refer [presence]]
            [re-frame.bench.hicasso.arm1.runtime :as rt]
            [re-frame.bench.hicasso.front.controlled :as controlled]
            [re-frame.bench.hicasso.lane :as lane]
            [re-frame.core :as rf]
            [re-frame.test-support :as test-support])
  (:require-macros [re-frame.bench.hicasso.arm1.lang :refer [defview]]))

(def ^:private frame-id ::arm1-hydrate-dom)
(def ^:private toast-timeout-ms 400)

;; Registered ABOVE `use-fixtures`, deliberately — the reset fixture
;; captures its source-store baseline when the `use-fixtures` form is
;; EVALUATED, so a `reg-sub` written below it is erased before the first
;; row runs and every screen renders nothing.

(rf/reg-sub :hyd/title (fn [db _] (:title db)))
(rf/reg-sub :hyd/row (fn [db [_ i]] (get-in db [:rows i] "")))
(rf/reg-sub :hyd/toasts (fn [db _] (:toasts db)))
(rf/reg-sub :hyd/field (fn [db _] (:field db)))
(rf/reg-sub :hyd/revision (fn [db _] (:revision db)))

(rf/reg-event :hyd/seed
  (fn [_ _]
    {:db {:title    "hydrated"
          :rows     {0 "alpha" 1 "beta" 2 "gamma"}
          :toasts   [{:id 1 :message "Saved"}]
          :field    "abc"
          :revision "rev-1"}}))

(rf/reg-event :hyd/set-revision
  (fn [{:keys [db]} [_ r]] {:db (assoc db :revision r)}))

(rf/reg-event :hyd/set-row
  (fn [{:keys [db]} [_ i v]] {:db (assoc-in db [:rows i] v)}))

(rf/reg-event :hyd/set-field
  (fn [{:keys [db]} [_ v]] {:db (assoc db :field v)}))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
     :ambient-frame nil
     :async?        true
     :init-fn       (fn [] (rt/reset-runtime!) (rt/reset-body-runs!))}))

(defn- skip! [why]
  (is true (str "a hydration claim needs a real React DOM — " why)))

(defn- fresh! []
  (lane/leave-act-environment!)
  (rf/make-frame {:id frame-id})
  (rf/with-frame frame-id (rf/dispatch-sync [:hyd/seed]))
  (rt/reset-runtime!)
  (rt/reset-body-runs!)
  frame-id)

;; ---------------------------------------------------------------------------
;; The screens
;; ---------------------------------------------------------------------------

(defview row
  "One row boundary. A single text child on purpose: React's SSR puts a
  `<!-- -->` separator between adjacent text runs and a client render
  does not, so a fixture with two text children would be measuring that
  known divergence (rf2-2rtt6.88's text-separator row) instead of this
  door."
  [{:keys [i]}]
  [:li.row {:data-i i} (rt/sub [:hyd/row i])])

(defview screen
  "Four boundaries: this one and three rows."
  [_]
  [:div.screen
   [:h1.title (rt/sub [:hyd/title])]
   [:ul.rows (for [i (range 3)] [row {:key i :i i}])]])

(def ^:private boundary-count
  "Four: [[screen]] and its three [[row]]s.

  **WHAT THIS CONSTANT IS AND IS NOT** (`studio/raw-escape-spec.md` §8.3,
  restated here rather than loosened). It is the number of boundary
  BODIES on the page, read by
  [[the-body-run-count-across-adoption-is-counted-and-not-inferred]]
  after adoption resolves. It is green because [[screen]] carries no
  foreign crossing — and it goes red **on correct code** the moment one
  is added under a policy that renders nothing on the server, because
  such a crossing legitimately runs a body the server never ran. The two
  obvious readings when that happens — \"my change is wrong\" and \"this
  test is wrong\" — are both wrong. Do not delete the assertion and do
  not loosen it to a range: restate it as this count PLUS the number of
  crossings on the page that the server did not render, so it still
  fails if a body runs twice or a boundary is skipped.

  `:ssr :render` is the policy that does NOT move it (rf2-l0wfx). Under
  `:render` the declaration mints no gate, the component is the
  element's own type, and the same tree renders on the server and on
  hydration's first pass — so the crossing adds no body run to adopt and
  no adoption swap afterwards. The arithmetic above applies to
  `:client-only` and `{:fallback …}` crossings only."
  4)

(defview toast-tray
  "A presence tray whose children carry BOTH override maps, so a child
  that entered is visibly different from one that was born present."
  [_]
  [presence {:timeout-ms toast-timeout-ms}
   (for [t (rt/sub [:hyd/toasts])]
     [:div.toast {:key (:id t)
                  :data-id (:id t)
                  :re-frame.hicasso/mounting   {:class "toast toast--enter"}
                  :re-frame.hicasso/unmounting {:class "toast toast--exit"}}
      (:message t)])])

(defview field-screen
  "One controlled text input — HD-019's shape, on the hydrated path."
  [_]
  [:input#hyd-field.field {:type     "text"
                           :value    (rt/sub [:hyd/field])
                           :on-input [:hyd/set-field :re-frame.hicasso/value]}])

(defview revision-field-screen
  "[[field-screen]] carrying `::h/revision` off a subscription — the shape
  §6 needs, and the only difference is that the reset trigger is a value
  the model owns rather than a literal."
  [_]
  [:input#hyd-field.field {:type     "text"
                           :value    (rt/sub [:hyd/field])
                           :re-frame.hicasso/revision (rt/sub [:hyd/revision])
                           :on-input [:hyd/set-field :re-frame.hicasso/value]}])

(defn- drift!
  "Move the field's value WITHOUT telling React — what a user typing into
  server-rendered HTML before the bundle arrived leaves behind, and the
  draft a reset exists to discard.

  Written through the PROTOTYPE's `value` setter because React patches the
  instance setter to maintain its change tracker; a plain `set!` would
  update the tracker too, after which React reads the node as already
  agreeing and no re-assert runs. Same reason, same shape as
  `front/revision_dom_cljs_test`'s own."
  [node v]
  (let [d (js/Object.getOwnPropertyDescriptor js/HTMLInputElement.prototype "value")]
    (.call (.-set d) node v)
    nil))

;; ---------------------------------------------------------------------------
;; The fixture doors
;; ---------------------------------------------------------------------------

(defn- server-html!
  "The bytes an SSR route would deliver for `hiccup` — see the ns
  docstring for what stands in for what. Rendered under an OPEN adoption
  window, which is the state a server render is in, and released
  afterwards so the runtime the hydration is measured on is empty."
  [hiccup]
  (rt/open-adoption-window!)
  (let [container (mount/fresh-container!)
        handle    (mount/root! container frame-id hiccup)
        html      (.-innerHTML container)]
    (mount/release! handle)
    html))

;; `server-dom!`, `stamp-server-nodes!`, `server-node?`, `capture-console!`
;; and `adopted!` moved to `arm1/hydration_support.cljs` when the SSR spike
;; (rf2-2rtt6.87) became their second caller. They are the three reasons a
;; hydration row can answer FALSE, and a second copy of a refusal is the
;; copy that quietly weakens — so there is one copy, and both callers
;; take it.

(defn- text-of [container sel]
  (some-> (.querySelector container sel) (.-textContent)))

;; ===========================================================================
;; 1 — the door adopts, and the CLOSER is what says so
;; ===========================================================================

(deftest hydrate-root-adopts-the-server-dom-and-the-closer-shuts-the-window
  (async done
    (if-not (mount/browser?)
      (do (skip! ":node-test has no DOM") (done))
      (do
        (fresh!)
        (let [html      (server-html! [screen {}])
              container (stamp-server-nodes! (server-dom! html))
              title     (.querySelector container ".title")]
          (is (server-node? title) "the stamp is on the server's own node")
          (rt/reset-body-runs!)
          (let [{:keys [captured close!]} (open-console-capture!)
                handle (mount/hydrate-root! container frame-id [screen {}])]
            (is (true? (rt/adopting?))
                "the window is OPEN the instant `hydrate-root!` returns —
                 `hydrateRoot` was called plain, so this returns before the
                 tree is adopted and the window has to outlive the call")
            (-> (adopted!)
                (.then
                  (fn [ok]
                    (close!)
                    (try
                      (is (true? ok) "the closer's passive effect ran")
                      (is (false? (rt/adopting?))
                          "and shut the window — adoption is complete, and
                           `(rt/adopting?)` answering false IS that effect
                           having run, which is the completion signal this
                           door offers in place of a `flushSync`")
                      (is (= [] @captured)
                          (str "React reported nothing on either channel: "
                               (pr-str @captured)))
                      (is (server-node? (.querySelector container ".title"))
                          "the title node is the SERVER'S node — React adopted
                           the DOM rather than replacing it")
                      (is (every? server-node?
                                  (array-seq (.querySelectorAll container ".row")))
                          "and so is every row")
                      (is (= "hydrated" (text-of container ".title")))
                      (is (= "alpha" (text-of container ".row[data-i=\"0\"]")))
                      (finally (mount/release! handle) (done))))))))))))

;; ===========================================================================
;; 2 — the reap horizon does not detach the adopted subscriptions
;; ===========================================================================

(deftest the-reaper-does-not-evict-the-entries-hydration-subscribed-to
  (async done
    (if-not (mount/browser?)
      (do (skip! ":node-test has no DOM") (done))
      (do
        (fresh!)
        (let [html      (server-html! [screen {}])
              container (server-dom! html)
              handle    (mount/hydrate-root! container frame-id [screen {}])]
          (-> (adopted!)
              (.then
                (fn [ok]
                  (try
                    (is (true? ok) "adoption completed")
                    (let [stats (rt/stats)]
                      (is (= boundary-count (:boundaries stats))
                          "every boundary committed its reads")
                      (is (= boundary-count (:entries stats))
                          "**and every one of them is subscribed to an entry
                           that is STILL IN THE CACHE.** This is the row the
                           0 -> 4 ms horizon exists for: an entry minted in
                           the render and reaped before `hydrateRoot`'s
                           passive subscribe leaves its boundary holding a
                           detached entry, and the count here falls below the
                           boundary count. Four read sequences, four entries")
                      (is (pos? (:cell-refs stats))
                          "and the references are real, so this is a reading
                           of something"))
                    ;; The adopted subscription is LIVE, and re-rendering
                    ;; through it does not rebuild the cache.
                    (mount/dispatch! handle [:hyd/set-row 1 "BETA"])
                    (is (= "BETA" (text-of container ".row[data-i=\"1\"]"))
                        "a write after adoption reaches the adopted boundary")
                    (is (= boundary-count (:entries (rt/stats)))
                        "and mints no second entry — the read sequence is
                         unchanged, so the cache hit keeps `subscribe`'s
                         identity and React never re-subscribes")
                    (finally (mount/release! handle) (done)))))))))))

;; ===========================================================================
;; 3 — presence is BORN PRESENT under adoption
;; ===========================================================================

(deftest a-client-mounted-tray-enters-so-the-row-below-can-answer-false
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (fresh!)
      (let [container (mount/fresh-container!)
            handle    (mount/root! container frame-id [toast-tray {}])]
        (try
          (testing "**the control.** An ordinary client mount meets its
                   children for the first time, so they are `:mounting` and
                   wear the `::h/mounting` override — which is what makes
                   the hydrated row below a difference rather than a claim
                   about a class nothing ever sets"
            (let [toast (.querySelector container ".toast")]
              (is (some? toast))
              (is (.contains (.-classList toast) "toast--enter")
                  "the enter override is on the freshly mounted node")))
          (finally (mount/release! handle)))))))

(deftest a-hydrated-tray-is-born-present-and-never-replays-its-enter
  (async done
    (if-not (mount/browser?)
      (do (skip! ":node-test has no DOM") (done))
      (do
        (fresh!)
        (let [html (server-html! [toast-tray {}])]
          (is (not (re-find #"toast--enter" html))
              "**the server's bytes carry no enter override.** A server
               render is inside the adoption window, so its children are
               born present — which is also what stops the server shipping
               an `opacity: 0` mounting style over content the user is
               meant to see")
          (is (re-find #"toast" html) "and the toast itself IS in them")
          (let [container (stamp-server-nodes! (server-dom! html))
                toast     (.querySelector container ".toast")]
            (is (server-node? toast))
            (let [{:keys [captured close!]} (open-console-capture!)
                  handle (mount/hydrate-root! container frame-id [toast-tray {}])]
              (-> (adopted!)
                  (.then
                    (fn [ok]
                      (close!)
                      (try
                        (is (true? ok) "adoption completed")
                        (is (= [] @captured)
                            (str "with nothing reported — an enter override
                                  applied to DOM that carries none is exactly
                                  a hydration mismatch: " (pr-str @captured)))
                        (let [after (.querySelector container ".toast")]
                          (is (server-node? after)
                              "the toast is the server's own node")
                          (is (not (.contains (.-classList after) "toast--enter"))
                              "**born present.** No enter override at any point
                               — the child was already on the screen, so
                               entering it would replay an animation the user
                               has already watched")
                          (is (= "Saved" (.-textContent after))))
                        (finally (mount/release! handle) (done)))))))))))))

;; ===========================================================================
;; 4 — HD-019's rider: the hydrated controlled input
;; ===========================================================================

(deftest the-hydrated-controlled-input-keeps-its-mirror
  (async done
    (if-not (mount/browser?)
      (do (skip! ":node-test has no DOM") (done))
      (do
        (fresh!)
        (let [html (server-html! [field-screen {}])]
          (is (re-find #"value=\"abc\"" html)
              "**what a server must emit for a controlled input.** The
               value is a rendered ATTRIBUTE, not something the client
               supplies after the fact — `hydrateRoot` matches the field
               against it, and `front.controlled`'s whole caret-restore
               rests on React's `defaultValue` mirror of it")
          (let [container (stamp-server-nodes! (server-dom! html))
                before    (.querySelector container "#hyd-field")]
            (is (server-node? before))
            (is (= "abc" (.-value before))
                "the field shows the server's value before any JS ran")
            (rt/reset-body-runs!)
            (let [{:keys [captured close!]} (open-console-capture!)
                  handle (mount/hydrate-root! container frame-id [field-screen {}])]
              (-> (adopted!)
                  (.then
                    (fn [ok]
                      (close!)
                      (try
                        (is (true? ok) "adoption completed")
                        (is (= [] @captured)
                            (str "nothing reported: " (pr-str @captured)))
                        (let [after (.querySelector container "#hyd-field")]
                          (is (server-node? after)
                              "**the node is the server's.** A remount here
                               would lose focus, selection and any composition
                               in flight")
                          (is (= "abc" (.-value after))
                              "**hydration converged nothing.** `converge!`
                               runs at the end of a change handler and
                               hydration fires none, so the field holds
                               exactly what the server rendered")
                          (is (= "abc" (controlled/last-rendered after))
                              "**and the `defaultValue` mirror is established
                               on the hydrated path too** — the one dependency
                               `front.controlled` has on React, extended to
                               adoption. Without it every caret restore on a
                               hydrated field would converge to the wrong
                               string"))
                        (is (= 1 (rt/body-runs))
                            (str "one boundary, one body run across the whole "
                                 "adoption — no converge, and so no extra "
                                 "render; read " (rt/body-runs)))
                        (finally (mount/release! handle) (done)))))))))))))

;; ===========================================================================
;; 5 — HD-028's rider: the memo cannot fake adoption
;; ===========================================================================

(deftest the-body-run-count-across-adoption-is-counted-and-not-inferred
  (async done
    (if-not (mount/browser?)
      (do (skip! ":node-test has no DOM") (done))
      (do
        (fresh!)
        (let [html      (server-html! [screen {}])
              container (stamp-server-nodes! (server-dom! html))]
          (rt/reset-body-runs!)
          (let [handle (mount/hydrate-root! container frame-id [screen {}])]
            (-> (adopted!)
                (.then
                  (fn [ok]
                    (try
                      (is (true? ok) "adoption completed")
                      (is (= boundary-count (rt/body-runs))
                          (str "**each boundary's body ran exactly once to "
                               "adopt.** Counted in `run-once`, where a body "
                               "is invoked — read " (rt/body-runs)))
                      (testing "**and the count is real, not memo-inferred**
                               (HD-028's rider). Re-rendering the same tree
                               with equal props bails out at `mint-view!`'s
                               memo, no body runs, and the counter says so by
                               NOT moving. An instrument that inferred a run
                               from the render React was asked for would move
                               here, and would then be unable to tell a
                               genuine adoption from a skipped one.

                               **This row also pins the hydrated root's
                               SHAPE** (`mount/tree`). The closer rides as a
                               Fragment sibling, and a `render!` that handed
                               the same root a bare provider instead would not
                               be a cheap re-render — React would reconcile a
                               different top element, tear the adopted subtree
                               down and mount a fresh one. Measured before the
                               repair: four body runs and four replaced nodes,
                               i.e. everything the adoption achieved, undone by
                               the first ordinary render"
                        (rt/reset-body-runs!)
                        (mount/render! handle [screen {}])
                        (is (zero? (rt/body-runs))
                            (str "a props-equal re-render ran no body; read "
                                 (rt/body-runs)))
                        (is (every? server-node?
                                    (array-seq (.querySelectorAll container ".row")))
                            "and every row is still the SERVER'S node — the
                             render did not remount the adopted tree"))
                      (finally (mount/release! handle) (done))))))))))))

;; ===========================================================================
;; 6 — HD-019's OTHER rider: a `::h/revision` arriving mid-adoption
;;
;; R3 of `studio/revision-prop-spec.md`, adjudicated on this harness
;; (rf2-ne3ey). The design claimed a revision arriving during adoption
;; lands on the first post-adoption commit, on the SERVER's node. R3
;; demoted that to witness-gated intent with the fallback pre-committed;
;; rf2-zq8kh settled the STRUCTURAL half — the revision is consumed at the
;; codec, so React is handed a prop-identical element whether it moved or
;; not (`front/revision_dom_cljs_test`) — and left the TIMING half here,
;; because two hand-rolled `hydrateRoot` arms disagreed about node identity
;; in opposite directions on consecutive runs.
;;
;; **The row below is not a re-run of that race, because `mid-adoption` is
;; a fact here rather than a wait.** `hydrate-root!` refuses to `flushSync`,
;; so it returns before the tree is adopted and `(rt/adopting?)` is TRUE on
;; the line after — asserted, exactly as §1 asserts it. The dispatch is
;; synchronous in that same turn, so no timer, no retry loop and no
;; tolerance stands between the two: nothing can interleave and there is
;; nothing to settle. It goes through `rt/dispatch!` rather than
;; `mount/dispatch!` deliberately — the latter's `settle!` is a `flushSync`,
;; and flushing React mid-adoption would manufacture the very schedule this
;; door exists to refuse.
;;
;; **The verdict: the revision is ABSORBED, not deferred, and the adoption
;; never notices it.** The write reaches no committed boundary — this arm
;; acquires at COMMIT, and the adoption commit is the first one — so it
;; notifies nothing, and the already-scheduled adoption render simply reads
;; the new revision as its FIRST revision. A change with no predecessor to
;; be a change from fires nothing; and React's hydration is a mount rather
;; than an update, so the per-commit `updateInput` re-assert that carries
;; the whole reset never runs. One body ran, the server's node survived,
;; React reported nothing, and the draft the user typed into the server HTML
;; is still in the field.
;;
;; So "lands on the first post-adoption commit" is false in both halves:
;; there is no post-adoption commit to land on, and the adoption commit does
;; not re-assert. What survives of the claim is the NODE, and the closing
;; arm pins it — the next revision change after the window shuts resets the
;; field on the server's own node, without remount. The field is never
;; stranded; the reset is simply the caller's to send again.
;; ===========================================================================

(deftest a-revision-arriving-mid-adoption-is-absorbed-by-the-adoption
  (async done
    (if-not (mount/browser?)
      (do (skip! ":node-test has no DOM") (done))
      (do
        (fresh!)
        (let [html (server-html! [revision-field-screen {}])]
          (is (re-find #"value=\"abc\"" html)
              "the server emitted the model as an attribute")
          (is (not (re-find #"revision" html))
              (str "and no revision — the strip is codec-level, so the wire
                    cannot carry one: " html))
          (let [container (stamp-server-nodes! (server-dom! html))
                before    (.querySelector container "#hyd-field")]
            (is (server-node? before))
            (drift! before "a draft the model never took")
            (is (= "a draft the model never took" (.-value before))
                "the draft really landed, and it landed BEFORE any JS adopted
                 the page — which is the only way a hydrated field can be
                 carrying one at all")
            (rt/reset-body-runs!)
            (let [{:keys [captured close!]} (open-console-capture!)
                  handle (mount/hydrate-root! container frame-id
                                              [revision-field-screen {}])]
              (is (true? (rt/adopting?))
                  "**the window is OPEN**, so the dispatch on the next line is
                   mid-adoption by construction rather than by timing")
              (rt/dispatch! frame-id [:hyd/set-revision "rev-2"])
              (-> (adopted!)
                  (.then
                    (fn [ok]
                      (close!)
                      (try
                        (is (true? ok) "the closer's passive effect ran")
                        (is (= "rev-2" @(rf/subscribe [:hyd/revision]
                                                      {:frame frame-id}))
                            "and the mid-adoption write really landed in the
                             model — what follows measures an ABSORBED
                             revision, not a dispatch that went nowhere")
                        (is (= [] @captured)
                            (str "React reported nothing on either channel: "
                                 (pr-str @captured)))
                        (let [after (.querySelector container "#hyd-field")]
                          (is (identical? before after)
                              "**the server's node survived.** A revision React
                               was never given cannot make it deopt to a client
                               render")
                          (is (server-node? after)
                              "and the stamp says so — an expando cannot
                               survive re-serialisation")
                          (is (= "a draft the model never took" (.-value after))
                              "**THE RESET DID NOT LAND.** Hydration is a mount,
                               not an update, so the per-commit re-assert that
                               carries the whole reset never ran — the draft is
                               exactly where the user left it")
                          (is (= "abc" (controlled/last-rendered after))
                              "while the mirror is the MODEL's, so the field is
                               one ordinary commit away from converging"))
                        (is (= 1 (rt/body-runs))
                            (str "one boundary, ONE body run across the whole "
                                 "adoption — the mid-adoption revision produced "
                                 "no render of its own, which is what ABSORBED "
                                 "means; read " (rt/body-runs)))
                        (testing "**and the field is not stranded.** The next
                                 revision change after the window shuts resets
                                 it, on the SERVER's own node — the half of
                                 R3's claim that does survive"
                          (rt/reset-body-runs!)
                          (mount/dispatch! handle [:hyd/set-revision "rev-3"])
                          (let [after (.querySelector container "#hyd-field")]
                            (is (= "abc" (.-value after))
                                "the draft is discarded and the field
                                 re-baselined to the model")
                            (is (identical? before after)
                                "WITHOUT REMOUNT — still the server's own node")
                            (is (server-node? after))
                            (is (= 1 (rt/body-runs))
                                (str "on one ordinary commit; read "
                                     (rt/body-runs)))))
                        (finally (mount/release! handle) (done)))))))))))))
