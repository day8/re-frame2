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
     Every row runs inside [[capture-console!]], which listens on
     `window`'s `error` event AND spies `console.error` — the two
     channels React 19 reports a hydration mismatch on — and asserts the
     capture is empty.
  2. **`act` and `flushSync` are not the browser's schedule.** Adoption
     is React's own concurrent business, so [[adopted!]] waits on real
     timers for the closer's passive effect and never pulls it forward.
     `mount/dispatch!` still flushes, but only in rows whose claim is
     about the model rather than about the turn.

  Runtime: `-dom-cljs-test`, so `:browser-test` runs it against a real
  React DOM; under `:node-test` every DOM claim degrades to a stated
  skip."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as uix-adapter]
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

(rf/reg-event :hyd/seed
  (fn [_ _]
    {:db {:title  "hydrated"
          :rows   {0 "alpha" 1 "beta" 2 "gamma"}
          :toasts [{:id 1 :message "Saved"}]
          :field  "abc"}}))

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

(def ^:private boundary-count 4)

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

(defn- server-dom!
  "A container carrying `html`, attached to the document — the page as it
  arrives, before any JavaScript has adopted it."
  [html]
  (let [container (mount/fresh-container!)]
    (set! (.-innerHTML container) html)
    container))

(def ^:private server-node-mark
  "An EXPANDO, not an attribute — it cannot survive being re-serialised,
  so a node still carrying it after adoption is the very node the server
  markup produced rather than a replacement that happens to look alike."
  "hicassoServerNode")

(defn- stamp-server-nodes!
  "Mark every element in the server DOM, so identity is checkable
  anywhere in the tree."
  [container]
  (doseq [n (array-seq (.querySelectorAll container "*"))]
    (unchecked-set n server-node-mark true))
  container)

(defn- server-node? [node]
  (and (some? node) (true? (unchecked-get node server-node-mark))))

(defn- capture-console!
  "Run `f` with React's two failure channels captured, and answer
  `[result captured]`.

  React 19 reports a hydration mismatch by `console.error` in a
  development build and routes a recoverable error to `reportError`,
  which surfaces as a `window` `error` event — and an exception React
  reports that way never reaches `cljs.test`, so a row asserting
  \"nothing complained\" that watched neither channel would be green over
  a live failure."
  [f]
  (let [captured (atom [])
        original (.-error js/console)
        on-error (fn [^js e]
                   (swap! captured conj (str "window.error: " (.-message e))))]
    (.addEventListener js/window "error" on-error)
    (set! (.-error js/console)
          (fn [& args]
            (swap! captured conj (str "console.error: " (apply str args)))
            nil))
    (try
      [(f) captured]
      (finally
        (set! (.-error js/console) original)
        (.removeEventListener js/window "error" on-error)))))

(defn- adopted!
  "Wait for the adoption window to CLOSE — the closer component's passive
  effect — and then for the entry reap horizon to pass.

  Real timers only: no `act`, no `flushSync`. The claim every row here
  makes is about React's own turn, and a witness that pulled the commit
  forward would be a witness about the pull."
  []
  (js/Promise.
    (fn [resolve]
      (let [deadline (+ (js/Date.now) 3000)]
        (letfn [(tick []
                  (cond
                    (not (rt/adopting?))
                    ;; Past the closer AND past `entry-reap-horizon-ms`,
                    ;; so a reading of the entry cache is a reading taken
                    ;; on the far side of the race.
                    (js/setTimeout (fn [] (resolve true)) 16)

                    (< deadline (js/Date.now))
                    (resolve false)

                    :else (js/setTimeout tick 4)))]
          (tick))))))

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
          (let [[handle captured]
                (capture-console! (fn [] (mount/hydrate-root! container frame-id [screen {}])))]
            (is (true? (rt/adopting?))
                "the window is OPEN the instant `hydrate-root!` returns —
                 `hydrateRoot` was called plain, so this returns before the
                 tree is adopted and the window has to outlive the call")
            (-> (adopted!)
                (.then
                  (fn [ok]
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
            (let [[handle captured]
                  (capture-console!
                    (fn [] (mount/hydrate-root! container frame-id [toast-tray {}])))]
              (-> (adopted!)
                  (.then
                    (fn [ok]
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
            (let [[handle captured]
                  (capture-console!
                    (fn [] (mount/hydrate-root! container frame-id [field-screen {}])))]
              (-> (adopted!)
                  (.then
                    (fn [ok]
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
