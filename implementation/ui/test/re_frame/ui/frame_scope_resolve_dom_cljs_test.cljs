(ns re-frame.ui.frame-scope-resolve-dom-cljs-test
  "rf2-vxgfnd.24 + rf2-vxgfnd.25 — the KEYSTONE, proven end to end on a REAL
  react-dom root through a REAL ViewCell: a compiled `(sub …)` mounted
  inside a `frame-provider` / `frame-root` subtree resolves the scoped
  frame's app-db on the AMBIENT React-context path — no explicit pin, no
  dynamic `with-frame` binding.

    - .24 publishes the `:adapter/current-frame` reader (re-frame.ui.adapter),
      so core's `require-current-frame!` — reached by the compiled sub-read
      (`reactive/sub-read` → `observation/resolve-target`) — sees the
      React-context tier under the plain-atom runtime.
    - .25 makes `frame-root` EMIT that scope (frames/scope-element), so a sub
      under a bare `frame-root` (no enclosing `frame-provider`) resolves too.

  The `(sub …)` lives in a DESCENDANT view boundary (`n-view`), not the
  provider's own body — React-context semantics scope descendants, and a
  ViewCell render is where `_currentValue` is live.

  Browser-only bodies — `-dom-cljs-test$` opts this file into `:browser-test`;
  under `:node-test` every DOM body gates on `(browser?)` and exits early."
  (:require [cljs.test :refer [deftest is use-fixtures]]
            ["react-dom" :as react-dom]
            [re-frame.core :as rf]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [re-frame.ui :as ui :refer [defview frame-root frame-provider sub]]
            [re-frame.ui.client :as client]
            [re-frame.ui.frames :as frames]
            [re-frame.ui.reactive :as reactive]))

(defn- browser? [] (exists? js/document))

;; reset-live-roots! / reset-installed-plans! / reset-scheduler! are BOOKKEEPING
;; resets — a clean framework slate between tests. They are NOT host teardown:
;; each test OWNS the React root it mounts and `ui/unmount!`s it in a `finally`
;; (see `assert-torn-down!`). This fixture is only the belt-and-suspenders slate,
;; not a substitute for `.unmount()`.
(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter
                                            :ambient-frame nil})
  (fn [t]
    (reactive/reset-scheduler!)
    (client/reset-live-roots!)
    (frames/reset-installed-plans!)
    (try
      (t)
      (finally
        (reactive/reset-scheduler!)
        (client/reset-live-roots!)
        (frames/reset-installed-plans!)))))

(defn- container [] (js/document.createElement "div"))

;; Host teardown + the leak proof, run in each test's `finally` so it fires on
;; BOTH the happy render and the expected no-frame-context abort. `ui/unmount!`
;; is the REAL teardown (bookkeeping reset is not) — it fires each ViewCell's
;; layout-effect cleanup synchronously inside `root.unmount()` (03 §4). After it,
;; no live-root claim and no connected ViewCell may survive into the next test.
(defn- assert-torn-down! [root]
  (react-dom/flushSync #(some-> root ui/unmount!))
  (is (= #{} (client/live-root-ids))
      "the root's claim is released — no live-root survives the test")
  (is (empty? (reactive/current-live-cells))
      "the mounted ViewCell was torn down — no connected cell leaks"))

(defn- reg! []
  (rf/reg-event :test/set-db (fn [_ [_ db]] {:db db}))
  (rf/reg-sub :scope/n (fn [db _] (:n db))))

;; n-view SUBS ambiently — a descendant view boundary, so its ViewCell render
;; reads the enclosing provider/root scope through the React context.
(defview n-view [] [:div.n (str "n=" (sub [:scope/n]))])

;; the sub sits UNDER a frame-provider, in a descendant view (n-view), never
;; in the provider's own template body.
(defview provider-wrap [{:keys [frame-id]}]
  [:div.wrap [frame-provider {:frame frame-id} [n-view]]])

;; ---------------------------------------------------------------------------
;; rf2-vxgfnd.24 — a compiled sub under a frame-provider resolves ambiently
;; ---------------------------------------------------------------------------

(deftest sub-under-frame-provider-resolves-scoped-frame-ambiently
  (when (browser?)
    (reg!)
    (rf/make-frame {:id :app/live})
    (rf/dispatch-sync [:test/set-db {:n 42}] {:frame :app/live})
    (let [c    (container)
          root (react-dom/flushSync
                #(ui/mount [provider-wrap {:frame-id :app/live}] c {:root-id :dom-scope/prov}))]
      (try
        (is (re-find #"n=42" (.-innerHTML c))
            (str "the ambient (sub …) resolved the frame-provider-scoped frame "
                 "through the React-context tier — the :adapter/current-frame "
                 "reader is live on the compiled sub-read path (rf2-vxgfnd.24)"))
        (finally
          (assert-torn-down! root))))))

;; ---------------------------------------------------------------------------
;; rf2-vxgfnd.25 — a compiled sub under a bare frame-root resolves ambiently
;; (frame-root now EMITS its scope; scope-element is reached, not dead)
;; ---------------------------------------------------------------------------

(deftest sub-under-frame-root-resolves-scoped-frame-ambiently
  (when (browser?)
    (reg!)
    (let [c    (container)
          root (react-dom/flushSync
                #(ui/mount [frame-root {:id :app/rooted :initial-events [[:test/set-db {:n 7}]]}
                            [n-view]]
                           c {:root-id :dom-scope/root}))]
      (try
        (is (re-find #"n=7" (.-innerHTML c))
            (str "the ambient (sub …) resolved the frame-root-scoped frame — "
                 "frame-root now emits its scope through frames/scope-element "
                 "(rf2-vxgfnd.25), and .24's published reader consults it"))
        (finally
          (assert-torn-down! root))))))

;; ---------------------------------------------------------------------------
;; NEGATIVE — a compiled sub OUTSIDE any provider/root still fails loud
;; (React aborts the commit; the container never renders the scoped value).
;; The synchronous, assert-the-id form of this negative rides the headless
;; twin (frame-context-hook-cljs-test); here we pin the mount-level effect on
;; a REAL root: no scope → nothing renders.
;; ---------------------------------------------------------------------------

(defview bare-sub-root [] [:div.bare [n-view]])

(deftest sub-outside-any-provider-does-not-resolve
  (when (browser?)
    (reg!)
    (rf/make-frame {:id :app/orphan})
    (rf/dispatch-sync [:test/set-db {:n 99}] {:frame :app/orphan})
    (let [c        (container)
          captured (atom nil)
          ;; No frame-provider / frame-root anywhere above n-view → the ambient
          ;; sub-read finds no scope and throws :rf.error/no-frame-context during
          ;; render. Under React 19 an uncaught render-phase throw is NOT
          ;; re-thrown out of `.render`; React aborts the commit and reports the
          ;; error through the root's `onUncaughtError` HOST callback (see
          ;; root_mount_dom_cljs_test §criterion-3). We route that callback into
          ;; `captured` so (a) the fail-loud error is asserted at the host tier,
          ;; and (b) React's DEFAULT onUncaughtError — which calls `reportError`,
          ;; surfacing an uncaught window error the browser-test runner rejects
          ;; even on a green cljs.test summary (rf2-mwx08) — is REPLACED rather
          ;; than left to fire. React internally unmounts the aborted tree but
          ;; the ROOT stays registered (§criterion-3), so this test still OWNS
          ;; the root-id claim and releases it in `finally` (rf2-vxgfnd.87).
          root     (react-dom/flushSync
                    #(ui/mount [bare-sub-root {}] c
                               {:root-id :dom-scope/orphan
                                :on-uncaught-error (fn [error _info] (reset! captured error))}))]
      (try
        (is (= :rf.error/no-frame-context (:rf.error/id (ex-data @captured)))
            (str "the ambient sub with NO provider/root above it FAILED LOUD with "
                 ":rf.error/no-frame-context, routed to the root's onUncaughtError "
                 "host callback (rf2-vxgfnd.24/.25)"))
        (is (not (re-find #"n=99" (.-innerHTML c)))
            (str "React aborted the commit — the ambient sub did not silently "
                 "resolve some frame; the scoped value never renders"))
        (finally
          (assert-torn-down! root))))))
