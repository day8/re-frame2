(ns re-frame.observation-port-watchable-host-mounted-dom-cljs-test
  "rf2-mjpmp — the MOUNTED completion of the observation port's NaN-stability
  proof: NaN moves NaN-STABLY through a REAL compiled ViewCell at a REAL React
  mount, on the PUBLIC compiled mount path, over the stock Reagent watchable
  host.

  WHAT THE HEADLESS SIBLING CANNOT REACH. The sibling namespace
  `re-frame.observation-port-watchable-host-cljs-test` proves the same fact
  headlessly, but drives the ViewCell BY HAND — `reactive/with-capture` +
  `reactive/commit!`, observed through a `reactive/subscribe` listener standing
  in for a render. That pins the cell-level contract with no browser, and it
  runs on every PR; but a hand-driven commit is a PROXY for a render, so
  mutating the public compiled mount path leaves it green. This namespace
  closes that gap: the NaN control is judged by the MOUNTED RENDER itself.

  WHY THIS COMPOSITION IS SUPPORTED (correcting a prior claim). An earlier
  revision of the sibling file recorded this arm as \"architecturally
  unreachable without a runtime change\". That was wrong on two counts:

    - `re-frame.ui.client/mount*` does NOT reject a non-`re-frame.ui` adapter.
      It captures the OPAQUE installed-adapter generation token
      (`current-adapter-generation`) before its side-effecting frame preflight
      and re-asserts the SAME token afterwards
      (`require-adapter-generation-open!`, rf2-vxgfnd.199) — a generic identity
      check against destroy / destroy-and-replace during preflight. It reads no
      `:kind`; there is no adapter-kind gate on the mount path, and sibling
      suites already mount compiled roots under `:kind :custom` adapters.
    - Reagent does NOT keep a compiled view away from the observation port.
      (This file used to stand a `ratom/run!` driver alongside the mount, on
      the same false rationale the headless sibling records — rf2-8cnxg. The
      mount is now the only consumer, which is what makes the mounted counts
      below attributable to the port rather than to a hand-run reaction.)
      That conflates WHICH ADAPTER SUPPLIES THE WATCHABLE HOST with WHICH
      RENDERER DRIVES THE VIEW; they are independent. A compiled `defview`
      always reads its subs through the observation port into a ViewCell —
      that is the compiled path, not an adapter feature — while the INSTALLED
      adapter supplies the sub-cache's derived nodes. Under Reagent those nodes
      ARE `reagent.ratom/Reaction`s.

  So the two properties the proof needs COMPOSE rather than exclude:

    1. HOST WATCH FIRES ON NaN→NaN — from the REAGENT adapter's Reaction, whose
       notify gate is raw `=` (`(= ##NaN ##NaN)` is false). Firing on a NO-move
       is the whole point: it is what makes the port's `node-value=`
       suppression (`make-watch-handler`) LOAD-BEARING. The first-party
       re-frame.ui / UIx substrate gates notify on `rf=`
       (`Object.is(##NaN,##NaN)` is true), so on THOSE hosts the watch never
       fires for NaN→NaN and `make-watch-handler` is never reached — a mounted
       NaN proof there would show zero renders for the WRONG reason.
    2. DRIVES A ViewCell AT A REAL MOUNT — from the COMPILED VIEW, which routes
       every `ui/sub` through the observation port → ViewCell →
       `useSyncExternalStore` regardless of the installed adapter.

  Composing them needs no bridge, no adapter-kind guard, no equality
  abstraction, and NO RUNTIME CHANGE — only the already-public surface below.

  THE RENDER EVIDENCE IS ATTRIBUTABLE AND MOUNTED, not a listener count:
  `renders` counts the compiled view's own BODY invocations and `commits`
  counts React Profiler `onRender` commits of the mounted tree. A NaN→NaN that
  escaped `node-value=` would advance the node, dirty the cell, notify
  `useSyncExternalStore`, and move BOTH.

  The `-dom-cljs-test` suffix (rf2-2hrj8) enrols this file in the
  `:browser-test` build; `:node-test`'s `cljs-test$` regex matches it too, and
  there the body gates on `(browser?)` and no-ops."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures async]]
            ["react" :as react]
            [reagent.ratom :as ratom]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.substrate.observation :as obs]
            [re-frame.ui :as ui :refer [defview]]
            [re-frame.ui.reactive :as reactive]
            [re-frame.ui.test :as uit]
            ;; The CLJS emitter wraps a SUB-BEARING view's body in
            ;; `re-frame.ui.viewcell/render` (sub-free views are never wrapped),
            ;; so a consumer of a sub-bearing `defview` must load the viewcell
            ;; runtime for that emitted reference to resolve.
            [re-frame.ui.viewcell]
            [re-frame.test-support :as test-support]))

(defn- browser? []
  (and (exists? js/document) (some? (.-createElement js/document))))

;; The stock REAGENT adapter is the watchable host under test: its derived sub
;; reactions are `reagent.ratom/Reaction`, which notify on NaN→NaN.
;;
;; `:async? true` — this is an `(async done …)` namespace, and cljs.test
;; hard-errors on a fn-form fixture there ("Async tests require fixtures to be
;; specified as maps"). The map form establishes the ambient `:rf/default`
;; scope with a PERSISTENT `set!` rather than a dynamic `binding`, so a bare
;; `dispatch-sync` still lands when the mounted body resumes on a later tick.
;; `:init-fn` resets the ViewCell scheduler per test, so this namespace never
;; inherits a sibling's pending-cell state on the shared `:browser-test` page.
(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter reagent-adapter/adapter
     :async?  true
     :init-fn reactive/reset-scheduler!}))

(def ^:private fid :rf/default)

(defn- node-reaction []
  (:reaction (get @(:sub-cache (frame/frame fid)) [:obs/n])))

(defn- register! []
  (rf/reg-sub :obs/n (fn [db _] (:n db)))
  (rf/reg-event :obs/set-n (fn [{:keys [db]} [_ v]] {:db (assoc db :n v)})))

;; The subscription target-key the ViewCell projects committed handles/values by
;; (`re-frame.ui.reactive/target-key` of a `[:sub fid query]` target).
(defn- tk [q] [:sub fid q])

(defn- nan-num? [x] (and (number? x) (js/isNaN x)))

;; An INDEPENDENT watch on the shared node reaction that counts ONLY NaN→NaN
;; fires. It proves the underlying host callback actually RAN for the no-movement
;; recompute (the port's `make-watch-handler` is a sibling watch on the same
;; reaction, fired in the same notify sweep) — so a green "nothing moved"
;; assertion cannot be vacuously green because the reaction never fired.
;; Returns the sentinel counter atom and a `remove!` thunk.
(defn- install-nan-sentinel! [reaction]
  (let [hits (atom 0)
        k    (gensym "rf-nan-sentinel")]
    (add-watch reaction k
               (fn [_ _ prev nu]
                 (when (and (nan-num? prev) (nan-num? nu))
                   (swap! hits inc))))
    [hits (fn remove! [] (remove-watch reaction k))]))

(defn- nan-commit-profiler
  "Plain React Profiler wrapper — `onRender` fires once per COMMIT of the
  mounted subtree, giving commit-level attribution alongside the compiled
  view's own body count."
  [^js props]
  (react/createElement
   (.-Profiler react)
   #js {:id       "watchable-host-nan-mounted"
        :onRender (fn [& _] (swap! (.-commits props) inc))}
   (.-children props)))

;; The compiled sub-reading component. Its BODY INVOCATION is the mounted
;; render, and its `ui/sub` read is the ViewCell → observation-port → Reagent
;; Reaction path under test. The value is surfaced into the DOM so the mounted
;; output is independently checkable.
(defview mounted-nan-view
  [{:keys [renders]}]
  (let [_ (swap! renders inc)
        v (ui/sub [:obs/n])]
    [:output {:data-nan-site "mounted"} (str v)]))

(defn- mounted-text [root]
  (some-> (.querySelector root "[data-nan-site]") .-textContent))

;; ONE movement at the mount. `dispatch-sync` moves app-db inside React `act`;
;; `ratom/flush!` then drains Reagent's queue so the WATCHED sub Reaction
;; re-runs and notifies its watchers (the port's per-handle watch among them).
;; `uit/flush!` alternates framework notification and React commits to a fixed
;; point, so any render the movement EARNED has committed by the time the
;; returned Promise resolves — and a movement that earned none has provably
;; produced none.
(defn- move! [v]
  (uit/flush! (fn []
                (rf/dispatch-sync [:obs/set-n v])
                (ratom/flush!))))

(deftest mounted-viewcell-over-watchable-host-nan-to-nan-is-render-stable
  (if-not (browser?)
    (is true ":node — no DOM; the :browser-test runner exercises the mounted body")
    (do
      (register!)
      (rf/dispatch-sync [:obs/set-n ##NaN])
            (let [baseline (reactive/current-live-cells)
            renders  (atom 0)
            commits  (atom 0)
            notes    (atom [])
            probe*   (atom nil)
            nan-rm*  (atom nil)
            cleanup! (fn []
                       (when-let [rm @nan-rm*] (rm))
                       (when-let [p @probe*] (obs/release! p)))]
        (async done
          (->
           (uit/with-root
             [root [nan-commit-profiler {:commits commits}
                    [ui/frame-provider {:frame fid}
                     [mounted-nan-view {:renders renders}]]]]
             (let [mounted           (remove baseline (reactive/current-live-cells))
                   cell              (first mounted)
                   reaction          (node-reaction)
                   [nan-hits nan-rm] (install-nan-sentinel! reaction)
                   ;; An INDEPENDENT probe handle on the SAME node: its
                   ;; `on-change` is the `{:cause :subscription}` note channel that
                   ;; the mounted cell's own (cell-dirtying) `on-change`
                   ;; cannot surface. Same port, same node, same notify sweep
                   ;; — the standard public `acquire!`, not a test bridge.
                   probe             (obs/acquire!
                                      (obs/resolve-target
                                       {:frame fid :query-v [:obs/n]})
                                      (fn [ev] (swap! notes conj ev)))
                   ver0              (:version (obs/read probe))
                   rev0              (reactive/revision cell)
                   renders0          @renders
                   commits0          @commits]
               (reset! nan-rm* nan-rm)
               (reset! probe* probe)
               (testing "preconditions — a REAL mounted ViewCell over the Reagent host"
                 (is (= 1 (count mounted))
                     "the compiled sub-reading view mounted exactly one ViewCell")
                 (is (satisfies? IWatchable reaction)
                     "the mounted cell reads a Reagent Reaction — an IWatchable host,
                      so the port armed its per-handle value-movement watch")
                 (is (obs/owned? (reactive/committed-handle cell (tk [:obs/n])))
                     "…through a REAL owned observation handle taken by the mounted commit")
                 (is (nan-num? (get (reactive/committed-values cell) (tk [:obs/n])))
                     "the mounted cell committed the host's NaN")
                 (is (= "NaN" (mounted-text root))
                     "and the MOUNTED DOM carries it — a real render, not a proxy")
                 (is (= 1 renders0) "the mount rendered the compiled view exactly once")
                 (is (= 1 commits0) "…in exactly one React commit")
                 (is (empty? @notes) "acquire! on a warm NaN node fans nothing"))
               (->
                (move! ##NaN)
                (.then
                 (fn []
                   (testing "NaN→NaN fires the host watch yet moves NOTHING at the mount"
                     (is (pos? @nan-hits)
                         "the Reagent host watch FIRED for NaN→NaN — make-watch-handler
                          genuinely ran, so the green assertions below are non-vacuous")
                     (is (empty? @notes)
                         "ZERO {:cause :subscription} notes — node-value= suppressed the fan-out
                          (a raw not= at the seam would fan a phantom movement)")
                     (is (= ver0 (:version (obs/read probe)))
                         "node version UNMOVED — NaN=NaN under the movement law")
                     (is (= rev0 (reactive/revision cell))
                         "mounted ViewCell revision UNMOVED — the cell was never dirtied")
                     (is (= renders0 @renders)
                         "ZERO further MOUNTED renders — the compiled view's body
                          did not re-run at the live mount")
                     (is (= commits0 @commits)
                         "ZERO further React commits of the mounted tree")
                     (is (= "NaN" (mounted-text root))
                         "the mounted DOM is untouched"))
                   (move! 5)))
                (.then
                 (fn []
                   (testing "positive control — a REAL move moves all four EXACTLY once"
                     (is (= 1 (count @notes))
                         "exactly one {:cause :subscription} note for the real movement")
                     (is (= :subscription (:cause (first @notes))))
                     (is (= (inc ver0) (:version (obs/read probe)))
                         "the real move advanced the node version EXACTLY once")
                     (is (= (inc rev0) (reactive/revision cell))
                         "…and the mounted cell's revision exactly once")
                     (is (= (inc renders0) @renders)
                         "…and drove EXACTLY ONE attributable mounted render")
                     (is (= (inc commits0) @commits)
                         "…committed through React exactly once")
                     (is (= "5" (mounted-text root))
                         "the mounted DOM shows the moved value")))))))
           ;; `with-root` unmounts the React root and removes its container as
           ;; part of this awaited chain, so the mount is EXPLICITLY torn down
           ;; before the fixture's `:after` restores the adapter.
           (.then (fn []
                    (cleanup!)
                    (done))
                  (fn [e]
                    (cleanup!)
                    (is false (str "mounted NaN proof rejected: " e))
                    (done)))))))))
