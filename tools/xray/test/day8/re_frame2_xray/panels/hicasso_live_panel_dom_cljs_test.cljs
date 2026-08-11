(ns day8.re-frame2-xray.panels.hicasso-live-panel-dom-cljs-test
  "The Hicasso tab is LIVE under real React — the running panel's own DOM
  (rf2-r98a, merged-PR audit of #7881).

  ## The claim this row exists to carry, and the one it replaces

  `hicasso_cljs_test/the-populated-roster-arrives-on-the-TRACE-TICK-and-not-on-a-cache-clear`
  proves that `:rf.xray.hicasso/data` INVALIDATES and RECOMPUTES on a
  `:rf.xray/trace-buffer` tick. That is real and it stays. But it reaches
  the panel by CALLING `hicasso/Panel` a second time itself and reading the
  hiccup that comes back: no React root is mounted, nothing commits, and
  no DOM is asserted. A panel wired to a live subscription and a panel
  whose sub happens to recompute when something calls it are not the same
  panel, and only the second was witnessed. The audit of #7881 named the
  gap; this row closes it.

  Here `Panel` is mounted ONCE, into a real `reagent.dom.client` root,
  wrapped in `[rf/frame-provider {:frame :rf/xray}]` exactly as
  `shell/shell-view` wraps it in production. **Nothing in this file ever
  calls `Panel` again.** Every later assertion reads
  `container.querySelector…` — the DOM React committed on its own.

  ## The three phases, and which one is the control

  1. **Mounted, empty.** The committed DOM carries
     `rf-xray-hicasso-empty-mounted`, so the panel really did render its
     empty arm before anything moved.
  2. **A real boundary mounts, and the panel does NOT move.** This is the
     load-bearing control. Hicasso's tables are process-global rather than
     part of Xray's app-db, so a mount invalidates nothing the panel's
     reaction watches — a tab wired to no tick at all would sit on an
     empty roster forever while the application it inspects mounts
     boundaries. The render queue is DRAINED here (same
     `:flush-render!` op as phase 3), so the staleness asserted is a
     reaction that never invalidated and not a commit that never
     happened.
  3. **One trace tick, and the roster arrives in the DOM.** The tick is
     the collector's own seam — `refresh-trace-rings!` dispatches
     `:rf.xray/sync-trace-buffer` on every coalesced drain (rf2-43koh) —
     and after it the committed DOM carries a boundary ROW naming the
     read the boundary really holds.

  The row testid, not the section wrapper: `rf-xray-hicasso-mounted` is
  what `mounted-view` renders in EVERY arm, with the empty note inside it,
  so a selector for the wrapper would match the stale empty roster this
  row exists to catch.

  ## Why the `<section>` identity is asserted

  Phase 3 also pins that the panel's root `<section>` is the SAME DOM node
  it was in phase 1. React reconciled the live tree in place; the roster
  did not arrive because something remounted the panel from scratch, which
  is the one other way a fresh roster could reach the screen and is not
  liveness.

  ## Substrate

  The Reagent adapter, because a real React commit is the whole point and
  because Hicasso's cell wiring calls `add-watch` on the substrate's
  derived value — under the ratom family that value IS a
  `reagent.ratom/Reaction` (`impl/collector.cljs` §`wire-cell!`), while
  plain-atom's is not `IWatchable`.

  `:ambient-frame nil` is load-bearing: the panel's `rf/subscribe` calls
  must resolve `:rf/xray` through the React-context tier the
  `frame-provider` establishes, the way the shipped shell resolves them.
  The fixture's default ambient `:rf/default` scope is still in effect
  during a synchronous `flushSync`, and would shadow that tier at tier 1 —
  the panel would then read `:rf/default`'s app-db and the test would be
  about a frame the shell never renders in.

  ## Test target

  The ns ends in `-dom-cljs-test` so it runs under the `:browser-test`
  build (real DOM / React via Chromium) per
  `implementation/shadow-cljs.edn` — the existing browser lane, which
  already carries `tools/xray/test` on `:source-paths`. No new deck, no
  new build id, no `:dev-http` port. The `:node-test` build's `cljs-test$`
  regex also matches the ns, so it loads under Node too, where the body
  short-circuits via `(browser?)`."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [clojure.string :as string]
            [reagent.dom.client :as rdc]
            ["react-dom" :as react-dom]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.test-support :as test-support]
            [re-frame.hicasso :as h]
            [re-frame.hicasso.impl.collector :as collector]
            [day8.re-frame2-xray.panels.hicasso :as hicasso]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-support :as xray-test-support]))

(def ^:private app-frame ::hicasso-live-app)

(rf/reg-sub :hlive/left (fn [db _] (:left db)))
(rf/reg-event :hlive/seed (fn [_ [_ db]] {:db db}))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       reagent-adapter/adapter
     :ambient-frame nil
     :init-fn       (fn []
                      (xray-test-support/reset-all!)
                      ;; Hicasso's tables are process-global `defonce`s that
                      ;; the core fixture knows nothing about; without this a
                      ;; neighbour's boundary is still in the entry cache and
                      ;; the empty arm of phase 1 is not empty.
                      (collector/reset-runtime!))}))

(defn- browser?
  "True only under the real-DOM `:browser-test` build. The `:node-test`
  build loads this ns but has no `js/document` to mount React into."
  []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

(defn- flush-render!
  "Run `thunk`, then SYNCHRONOUSLY commit whatever re-render it scheduled.

  The adapter's own `:flush-render!` contract slot (Spec 006), which is
  what the Pair MCP drives a headless render with — not a test-only
  mechanism. Reagent schedules a dependent component's re-render on a
  `requestAnimationFrame` turn, so without this the committed DOM would
  lag every phase below by a frame and the assertions would be about
  timing rather than about liveness."
  [thunk]
  ((:flush-render! reagent-adapter/adapter) thunk))

(defn- setup! []
  (registry/register-xray-handlers!)
  (rf/make-frame {:id :rf/xray})
  (rf/make-frame {:id app-frame})
  (rf/with-frame app-frame
    (rf/dispatch-sync [:hlive/seed {:left 1}]))
  nil)

(defn- mount-panel!
  "Mount the real `Panel` into a real React root, committed synchronously
  (React 19's `root.render` is otherwise async). The `frame-provider` is
  the shell's own wrapper — `shell/shell-view` renders every panel inside
  `[rf/frame-provider {:frame :rf/xray}]`."
  []
  (let [container (.createElement js/document "div")
        root      (rdc/create-root container)]
    (.appendChild (.-body js/document) container)
    (react-dom/flushSync
      (fn []
        (rdc/render root [rf/frame-provider {:frame :rf/xray}
                          [hicasso/Panel]])))
    {:container container :root root}))

(defn- mount-boundary!
  "A real Hicasso boundary, rendered and committed through the runtime's
  own seam — the same `subscribe` closure React calls. Returns the
  release fn."
  []
  (collector/render-body app-frame (fn [_] (h/sub [:hlive/left]) nil) {})
  (collector/commit-boundary! (collector/last-reads) (fn [])))

(defn- tick-trace!
  "One trace-buffer tick, delivered the way the collector delivers it:
  `trace-collector/refresh-trace-rings!` dispatches
  `:rf.xray/sync-trace-buffer` with its snapshot on every coalesced task
  drain, and `:rf.xray/trace-buffer` reads the slot that dispatch writes
  (rf2-43koh)."
  []
  (rf/dispatch-sync [:rf.xray/sync-trace-buffer
                     [{:id 1 :op-type :rf.event
                       :operation :rf.event/dispatched :tags {}}]]
                    {:frame :rf/xray}))

(defn- q [container sel] (.querySelector container sel))

(defn- boundary-rows
  "The boundary ROWS in the committed DOM.

  `li[…]`, not a bare prefix match: the row's two loss chips render under
  testids that EXTEND the row's own (`…-boundary-<slug>-view-loss-…`), so
  an unqualified prefix selector would count one boundary three times."
  [container]
  (vec (js/Array.from
         (.querySelectorAll container "li[data-testid^=\"rf-xray-hicasso-boundary-\"]"))))

(deftest the-mounted-panel-picks-up-a-new-boundary-on-the-trace-tick
  (testing "rf2-r98a — a REAL React root holding the Hicasso tab re-renders
            itself on a `:rf.xray/trace-buffer` tick and commits the
            populated roster to the DOM. Nothing here calls `Panel` a second
            time; the roster arrives because the panel is live. Reddens if
            `:rf.xray.hicasso/data` stops composing off `:rf.xray/trace-buffer`."
    (if-not (browser?)
      (is true ":node — the :browser-test runner drives the real React mount")
      (let [_          (setup!)
            {:keys [container root]} (mount-panel!)
            release    (volatile! nil)]
        (try
          ;; ---- phase 1: mounted, and rendering its empty arm --------------
          (let [section (q container "[data-testid=\"rf-xray-hicasso\"]")]
            (is (some? section)
                "the Hicasso Panel committed a real DOM root under React")
            (is (some? (q container "[data-testid=\"rf-xray-hicasso-empty-mounted\"]"))
                "the live panel rendered the EMPTY mounted census — nothing is
                 mounted yet, so the roster this test drives in cannot already
                 be on screen")
            (is (empty? (boundary-rows container))
                "NON-VACUITY: no boundary row is in the DOM before one mounts")

            ;; ---- phase 2: a real boundary mounts, and the panel is deaf ---
            ;; The render queue is drained here too, so what is asserted is a
            ;; reaction that never invalidated — not a commit that never ran.
            (flush-render! (fn [] (vreset! release (mount-boundary!))))
            (is (some? (q container "[data-testid=\"rf-xray-hicasso-empty-mounted\"]"))
                "CONTROL: a real mount moves nothing the panel's reaction
                 watches — Hicasso's tables are process-global, not Xray
                 app-db — so the committed DOM still shows the empty note.
                 Without this the last phase would pass on a panel that had
                 simply never rendered before the tick")

            ;; ---- phase 3: one tick, and the roster is on screen -----------
            (flush-render! tick-trace!)
            (let [rows (boundary-rows container)]
              (is (= 1 (count rows))
                  (str "the trace tick re-fired the live panel and ONE boundary "
                       "row committed to the DOM — with no cache clear and no "
                       "second call to Panel anywhere in this test. DOM: "
                       (.-textContent container)))
              (is (string/includes? (.-textContent (first rows)) "[:hlive/left]")
                  (str "and the row names the read the boundary really holds, so "
                       "the assertion above cannot pass on a row projected from "
                       "nothing. row text: " (.-textContent (first rows)))))
            (is (nil? (q container "[data-testid=\"rf-xray-hicasso-empty-mounted\"]"))
                "the empty note is gone from the DOM — the roster REPLACED it
                 rather than rendering beside it")
            (is (identical? section (q container "[data-testid=\"rf-xray-hicasso\"]"))
                "and it is the SAME <section> node — React reconciled the live
                 tree in place, so the roster did not arrive by the panel being
                 remounted from scratch, which would not be liveness"))
          (finally
            (when-some [r @release] (r))
            (try (.unmount root) (catch :default _ nil))
            (.remove container)))))))
