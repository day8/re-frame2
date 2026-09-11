(ns day8.re-frame2-xray.palette.empty-row-frame-context-dom-cljs-test
  "Real-DOM witness for the palette's EMPTY-RESULTS row (rf2-ap5w).

  ## The fault this file was written to reproduce

  `palette/view.cljs` used to HEAD its empty row — `[empty-row]` — and
  `empty-row` performed an ambient `(rf/subscribe [:rf.xray/palette-query])`
  of its own. A head makes Reagent mint a component for the plain `defn`,
  and a plain `defn` carries no `:contextType`, so `(.-context cmp)` is
  React's empty default, `re-frame.views.provider/current-frame` coerces
  that to nil, and the ambient subscribe raises
  `:rf.error/no-frame-context` (Spec 006 §Plain-fn footgun). The repair
  passes the already-bound `query` down — `(empty-row query)` — which
  both inlines the call and removes the duplicate read.

  ## Why no existing lane could see it

  Three independent reasons, and all three had to hold at once:

  1. `sources/rank`'s contract is \"Empty query keeps every item\", so the
     palette's OPEN state renders RESULTS. The empty row needs a TYPED
     query that matches nothing.
  2. `rf-xray-palette-empty` had no reference in any lane — node, browser
     or `scenarios.cjs`. Nothing drove the branch.
  3. THE NODE LANE CANNOT SEE THIS EVEN IF IT DROVE THE BRANCH. Xray's
     node rows build the tree with `(rf/with-frame :rf/xray …)` and walk
     it with a hiccup walker that CALLS function heads — inside that
     dynamic scope, so the dynamic-var tier answers and the ambient read
     resolves. Only a committed React render puts the plain fn in its own
     component with no context to read.

  ## Two traps this suite is shaped around

  THE FIXTURE MUST OPT OUT OF THE AMBIENT FRAME. The core fixture
  establishes `*current-frame*` `:rf/default` unless `:ambient-frame nil`
  says otherwise, and that binding is the very dynamic-var tier the fault
  depends on being absent — a suite that takes the default masks the
  refusal and reports a clean palette.

  REACT SWALLOWS THE RENDER THROW. A `try/catch` around `flushSync` never
  fires; React 19 reports the failure and re-raises it as an UNCAUGHT
  window error, so only a window `error` listener can name the refusal.
  [[w0-the-listener-bites]] exercises that listener, so a silent run in
  [[w1-empty-row-commits-under-a-provider]] means \"did not raise\"
  rather than \"was not watching\".

  THE CONTROL DISPATCHES AN `ErrorEvent` RATHER THAN PLANTING A REAL
  THROW, and that is the lane's rule rather than a softening. The browser
  runner fails any run in which the page emitted an uncaught error at all
  — a dedicated `pageerror` array, deliberately independent of the
  `cljs.test` summary (rf2-mwx08 / rf2-wf5al) — so a suite that plants
  one reddens the whole lane with a green summary beside it. Measured:
  with a planted throw here the run read `0 failures, 0 errors` and
  still exited 1, naming `1 uncaught pageerror(s)`.

  What the synthetic event leaves unproven — that React really does
  re-raise a render refusal onto `window` — was measured directly rather
  than assumed. Before the repair, W1 caught
  `:rf.error/no-frame-context` through this very listener, with neither
  the dialog nor the empty row committed. That run is the end-to-end
  evidence for the channel; W0 is the standing check that the listener
  is still armed and still reads the payload off the event.

  ## Node-lane behaviour

  This ns matches the `:browser-test` build's `-dom-cljs-test$` regex and
  also loads under `:node-test`, where every row short-circuits through
  [[browser?]] and reports the skip rather than passing silently."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [reagent.dom.client :as rdc]
            ["react-dom" :as react-dom]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.core :as rf]
            [re-frame.test-support :as rf.test-support]
            [day8.re-frame2-xray.palette :as palette]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.shell :as shell]
            [day8.re-frame2-xray.test-support :as xray-test-support]))

(def ^:private palette-frame
  "The frame this suite's palette instance owns. NOT `:rf/xray`: that is
  the production singleton, shared with every other suite on the page.
  Naming a private one is what keeps these rows independent."
  ::palette)

;; `:ambient-frame nil` is LOAD-BEARING — see the ns docstring. The core
;; fixture is used directly rather than `xray-test-support/make-xray-runtime-
;; fixture` precisely because that wrapper does not thread the key.
(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.reagent/adapter
     :ambient-frame nil
     :async?        true
     :init-fn       (fn [] (xray-test-support/reset-all!))}))

(defn- browser?
  "True only under the real-DOM `:browser-test` build."
  []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

;; ---- the uncaught-error channel -----------------------------------------

(defonce ^:private !last-uncaught (atom nil))

(defonce ^:private error-capture-armed?
  (when (exists? js/window)
    (.addEventListener js/window "error"
                       (fn [^js e] (reset! !last-uncaught (.-error e))))
    true))

(defn- uncaught-id
  "The `:rf.error/id` of the last uncaught render error, or its message
  when it carries no re-frame payload, or nil when nothing was raised."
  []
  (when-some [e @!last-uncaught]
    (or (:rf.error/id (ex-data e)) (ex-message e) (str e))))

(defn- settle
  "A promise resolving once every render pipeline on the page has had a
  real chance to commit — and, for this suite, once an uncaught error
  raised during that commit has had a chance to reach the window."
  []
  (js/Promise.
    (fn [resolve]
      (js/requestAnimationFrame
        (fn [_]
          (js/requestAnimationFrame
            (fn [_] (js/setTimeout resolve 20))))))))

;; ---- mount helpers -------------------------------------------------------

(defn- setup!
  "Register Xray's handlers — which is what fills the palette index — and
  make the frames. `:rf/xray` is made as well as this suite's own because
  several Xray registrations reach the production singleton by name."
  []
  (registry/register-xray-handlers!)
  (rf/make-frame {:id shell/default-frame-id})
  (rf/make-frame {:id palette-frame})
  nil)

(defn- mount!
  "Mount `body` the way `shell.cljs` mounts the palette at the shell-view
  root — under the outer `frame-provider` `mount.cljs` installs. That
  wrapper is the whole point: a BARE mount fails for an entirely
  different reason, which would be a false positive for the fault under
  test. Under rf2-k97c.3 that different reason has simply changed hands
  and stayed a refusal — `Modal` is the `as-component` bridge onto the
  `ModalView` BOUNDARY now, and a boundary at a bare root resolves no
  frame from React context either. The wrapper is what supplies one.

  Committed synchronously — React 19's `root.render` is otherwise async
  and the first assertion would read an empty container."
  [frame body]
  (let [container (.createElement js/document "div")
        root      (rdc/create-root container)]
    (.appendChild (.-body js/document) container)
    (react-dom/flushSync
      (fn []
        (rdc/render root [rf/frame-provider {:frame frame} body])))
    {:container container :root root}))

(defn- teardown! [root container]
  (react-dom/flushSync (fn [] (.unmount root)))
  (.remove container))

(defn- testid [container id]
  (.querySelector container (str "[data-testid=\"" id "\"]")))

;; ===========================================================================
;; W0 — the control: the listener bites
;; ===========================================================================

(deftest w0-the-listener-bites
  (testing "rf2-ap5w — the window `error` listener this suite reads W1's
            verdict through is armed, and extracts the `ex-data` off the
            event rather than merely noting that something happened.
            Without this row a silent W1 could mean either 'nothing
            raised' or 'nobody was watching', and those are opposite
            findings.

            Synthetic rather than planted: see the ns docstring — the
            runner fails a run on any uncaught page error, so a real
            throw here would redden the lane with a green summary."
    (if-not (browser?)
      (is true "skipped: no DOM (node lane)")
      (async done
        (reset! !last-uncaught nil)
        (.dispatchEvent js/window
                        (js/ErrorEvent.
                          "error"
                          #js {:error (ex-info "planted"
                                               {:rf.error/id ::planted})}))
        (-> (settle)
            (.then
              (fn [_]
                (is (true? error-capture-armed?)
                    "the window `error` listener is armed")
                (is (= ::planted (uncaught-id))
                    (str "the listener read the payload off the event; got "
                         (pr-str (uncaught-id))))
                (reset! !last-uncaught nil)
                (done))))))))

;; ===========================================================================
;; W1 — the witness
;; ===========================================================================

(deftest w1-empty-row-commits-under-a-provider
  (testing "rf2-ap5w — a typed query that matches nothing drives the
            palette to its empty-results branch, and that branch COMMITS
            under the production provider shape instead of refusing with
            `:rf.error/no-frame-context`.

            This is the row the fault failed: with `[empty-row]` in head
            position, the plain `defn`'s ambient subscribe reads a nil
            frame and raises, React discards the whole subtree, and the
            palette paints nothing at all."
    (if-not (browser?)
      (is true "skipped: no DOM (node lane)")
      (async done
        (setup!)
        (reset! !last-uncaught nil)
        (rf/with-frame palette-frame
          (rf/dispatch-sync [:rf.xray/palette-open])
          ;; Gibberish, deliberately: an EMPTY query keeps every item
          ;; (`sources/rank`), so the open palette would render RESULTS
          ;; and never reach the branch under test.
          (rf/dispatch-sync [:rf.xray/palette-set-query "zzqqxxjjvvww"]))
        (is (empty? (rf/with-frame palette-frame
                      (rf/subscribe-once [:rf.xray/palette-results])))
            "the query matches nothing — the empty branch is the one that
             will render")
        (let [{:keys [container root]} (mount! palette-frame [palette/Modal])]
          (-> (settle)
              (.then
                (fn [_]
                  (is (nil? (uncaught-id))
                      (str "no uncaught refusal during render; got "
                           (pr-str (uncaught-id))))
                  (is (some? (testid container "rf-xray-palette-dialog"))
                      "the palette dialog committed")
                  (is (some? (testid container "rf-xray-palette-empty"))
                      "the empty-results row committed")
                  (teardown! root container)
                  (reset! !last-uncaught nil)
                  (done)))))))))
