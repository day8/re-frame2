(ns re-frame.ui.frame-scope-resolve-dom-cljs-test
  "rf2-vxgfnd.24 + rf2-vxgfnd.25 — the KEYSTONE, proven end to end on a REAL
  react-dom root through a REAL ViewCell: a compiled `(sub …)` mounted
  inside a `frame-provider` / `frame-root` subtree resolves the scoped
  frame's app-db on the AMBIENT React-context path — no explicit pin, no
  dynamic `with-frame` binding.

    - .24 publishes the `:adapter/current-frame` reader (re-frame.ui.substrate),
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
  (:require [cljs.test :refer [deftest is testing use-fixtures]]
            ["react" :as React]
            ["react-dom" :as react-dom]
            [re-frame.core :as rf]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [re-frame.ui :as ui :refer [defview frame-root frame-provider sub]]
            [re-frame.ui.client :as client]
            [re-frame.ui.frames :as frames]
            [re-frame.ui.reactive :as reactive]))

(defn- browser? [] (exists? js/document))

;; ---------------------------------------------------------------------------
;; The canonical React act() helper (rf2-vxgfnd.89)
;;
;; This is the repository's established native-React act pattern — the same
;; get-act / enable-react-act-env! shape as re-frame.adapter's shared React
;; suite (core/.../react_shared_suite.cljs `with-browser-act`) and the
;; machines-viz `*-dom-cljs-test` namespaces. It uses React's OWN `act()`, NOT
;; UIx/Helix/Reagent machinery, so this fixture stays native re-frame.ui +
;; plain-atom.
;;
;; `flushSync` is NOT an act substitute: React's test contract treats any
;; update committed outside an act scope as "not wrapped in act(...)". The
;; `:browser-test` build runs EVERY `-dom-cljs-test` namespace on one shared
;; page, and sibling suites (the adapter + machines-viz DOM tests) set
;; IS_REACT_ACT_ENVIRONMENT=true and never reset it — so once the browser gate
;; actually runs this file (rf2-vxgfnd.90), a flushSync-only mount here emits
;; that warning as soon as the flag has leaked true. Wrapping every mount /
;; unmount in `act` — with the act env explicitly enabled per-test below —
;; makes the fixture clean under act rather than silencing anything.
(defn- get-act
  "React's act() — React 19 promotes it to the React namespace proper
  (react-dom/test-utils on 18)."
  []
  (or (when (exists? (.-act React)) (.-act React))
      (try (.-act (js/require "react-dom/test-utils")) (catch :default _ nil))))

(defn- enable-react-act-env! []
  (when (browser?)
    (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)))

(defn- act!
  "Run `thunk` inside React `act()`, returning the thunk's value. `act()`
  itself returns a thenable (not the callback's value); the mount/unmount work
  here commits synchronously under IS_REACT_ACT_ENVIRONMENT (enabled per-test
  in the fixture), so the callback runs eagerly and we capture its result.
  Wraps the mount (returns the root handle) AND the unmount (returns nil) so
  every React commit — the happy render AND the render-abort teardown — settles
  inside an act scope."
  [thunk]
  (let [ret    (volatile! nil)
        act-fn (get-act)]
    (act-fn (fn [] (vreset! ret (thunk))))
    @ret))

(defn- mount-expecting-abort!
  "Run a mount whose render DELIBERATELY throws (the no-provider negative
  case). React 19 routes the render-phase throw to the root's
  `onUncaughtError` and aborts the commit WITHOUT re-throwing out of
  `.render` — but React `act` SURFACES that error at the act boundary, so
  wrapping this path in `act!` would turn the deliberate abort into an
  uncaught test error. Use `flushSync` with the act env toggled OFF (the
  canonical 'real flushSync commit path' pattern — react_shared_suite.cljs)
  so the error is captured by `onUncaughtError`, not re-thrown, and the
  aborted render — which commits nothing — emits no act warning. The act env
  is restored afterwards so `assert-torn-down!`'s unmount still runs under
  `act!`. Returns the registered root (its claim is still owned)."
  [thunk]
  (let [prior (.-IS_REACT_ACT_ENVIRONMENT js/globalThis)]
    (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
    (try
      (react-dom/flushSync thunk)
      (finally
        (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) prior)))))

;; --- bounded namespace-local act-warning capture (rf2-vxgfnd.138) -------------
;;
;; The post-mount compiled-`sub` update below drives a real dispatch → queued-
;; write drain → observation callback → reactive flush → useSyncExternalStore
;; listener → React rerender. Committed OUTSIDE an act scope while
;; IS_REACT_ACT_ENVIRONMENT is true (this ns enables it per test), React emits
;; its "An update to … was not wrapped in act(...)" console.error. The browser
;; runner records console output but does NOT fail on a mere warning
;; (`scripts/run-browser-tests.cjs`), so without a capture the obligation could
;; stay false-green. `with-console-error-capture` gives the zero-warning
;; assertion its teeth: it FORWARDS every console.error line to the original
;; console (diagnostics are never suppressed) and, bounded to the wrapped
;; operation, COUNTS the act-discipline warning so removing the update's `act!`
;; makes the assertion go RED (not merely print). No global console suppression
;; and no broad unrelated-warning policy — only the act warning is counted, and
;; the original console.error is restored on every exit.

(defn- act-warning?
  "True iff `text` is React's 'not wrapped in act(...)' console diagnostic — the
  exact warning the act boundary suppresses. Used BOTH by the live capture and
  by the detector unit test, so the predicate the zero-warning assertion depends
  on is proven to match React's real wording."
  [text]
  (and (string? text)
       (boolean (re-find #"not wrapped in act" text))))

(defn- with-console-error-capture
  "Run `thunk` with `console.error` intercepted for its dynamic extent: every
  call is FORWARDED to the original console, and any act-discipline warning
  increments `counter`. The original `console.error` is restored on every exit
  (bounded to this call — nothing global changes)."
  [counter thunk]
  (let [orig (.-error js/console)]
    (set! (.-error js/console)
          (fn [& args]
            (when (some act-warning? args)
              (swap! counter inc))
            (.apply orig js/console (into-array args))))
    (try (thunk) (finally (set! (.-error js/console) orig)))))

;; reset-live-roots! / reset-installed-plans! / reset-scheduler! are BOOKKEEPING
;; resets — a clean framework slate between tests. They are NOT host teardown:
;; each test OWNS the React root it mounts and `ui/unmount!`s it in a `finally`
;; (see `assert-torn-down!`). This fixture is only the belt-and-suspenders slate,
;; not a substitute for `.unmount()`.
(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter
                                            :ambient-frame nil})
  (fn [t]
    ;; Enable React's act environment for this ns's DOM tests (act() warns /
    ;; no-ops unless IS_REACT_ACT_ENVIRONMENT is set), then RESTORE the prior
    ;; value so the fixture neither depends on nor leaks the shared-page flag.
    (let [prior (when (browser?) (.-IS_REACT_ACT_ENVIRONMENT js/globalThis))]
      (enable-react-act-env!)
      (reactive/reset-scheduler!)
      (client/reset-live-roots!)
      (frames/reset-installed-plans!)
      (try
        (t)
        (finally
          (reactive/reset-scheduler!)
          (client/reset-live-roots!)
          (frames/reset-installed-plans!)
          (when (browser?)
            (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) prior)))))))

(defn- container [] (js/document.createElement "div"))

;; Host teardown + the leak proof, run in each test's `finally` so it fires on
;; BOTH the happy render and the expected no-frame-context abort. `ui/unmount!`
;; is the REAL teardown (bookkeeping reset is not) — it fires each ViewCell's
;; layout-effect cleanup synchronously inside `root.unmount()` (03 §4). After it,
;; no live-root claim and no connected ViewCell may survive into the next test.
(defn- assert-torn-down! [root]
  (act! #(some-> root ui/unmount!))
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
    (let [c        (container)
          warnings (atom 0)
          root     (act!
                    #(ui/mount [provider-wrap {:frame-id :app/live}] c {:root-id :dom-scope/prov}))]
      (try
        (is (re-find #"n=42" (.-innerHTML c))
            (str "the ambient (sub …) resolved the frame-provider-scoped frame "
                 "through the React-context tier — the :adapter/current-frame "
                 "reader is live on the compiled sub-read path (rf2-vxgfnd.24)"))
        ;; rf2-vxgfnd.138 — a GENUINE post-mount compiled-`sub` update: dispatch
        ;; n=43 against the ALREADY-MOUNTED frame and settle the pending
        ;; framework/reactive work. This exercises the real runtime path the
        ;; mount-time seed never reaches — dispatch → drain → observation
        ;; callback → reactive flush → useSyncExternalStore → React rerender —
        ;; which has its own act boundary and scheduling behaviour. The dispatch
        ;; AND the explicit pending-work settlement (`flush-pending!`, which
        ;; forces the microtask-armed coalesced flush synchronously) run inside
        ;; the canonical `act!`, so React commits the rerender under act and
        ;; emits no warning. Removing this `act!` boundary commits the update
        ;; outside act while the env flag is true → React's
        ;; "not wrapped in act(...)" warning → the capture counts it → the
        ;; zero-warning assertion below goes RED.
        (with-console-error-capture warnings
          (fn []
            (act!
             #(do
                (rf/dispatch-sync [:test/set-db {:n 43}] {:frame :app/live})
                (reactive/flush-pending!)))))
        (is (re-find #"n=43" (.-innerHTML c))
            (str "the post-mount dispatch drove the compiled ambient (sub …) "
                 "through observation → reactive flush → React rerender; the "
                 "mounted ViewCell repainted the scoped frame's new value"))
        (is (zero? @warnings)
            (str "the post-mount dispatch + explicit reactive settlement stayed "
                 "inside act() — zero 'not wrapped in act(...)' warnings escaped"))
        (finally
          (assert-torn-down! root))))))

(deftest act-warning-detector-has-teeth
  ;; rf2-vxgfnd.138 proof discipline — the zero-warning assertion above is only
  ;; meaningful if its predicate genuinely matches React's act diagnostic AND
  ;; the capture actually COUNTS it (fails, not merely prints). Prove both here
  ;; WITHOUT emitting a real warning (a real one would itself violate the
  ;; zero-warning contract), so this runs cross-runtime — including under
  ;; `:node-test`, where the DOM-mount body above is gated out. Removing the
  ;; update's `act!` boundary makes the live capture behave exactly like the
  ;; simulated warning below: `counter` increments and `(zero? @counter)` is
  ;; false.
  (testing "the predicate recognises React's real wording, not unrelated errors"
    (is (act-warning?
         "Warning: An update to n-view inside a test was not wrapped in act(...)."))
    (is (act-warning?
         "An update to ViewCell inside a test was not wrapped in act(...). When testing…"))
    (is (not (act-warning? "TypeError: querySelector is not a function")))
    (is (not (act-warning? nil))))
  (testing "the capture COUNTS an act warning while forwarding it (teeth, not print)"
    (let [counter   (atom 0)
          forwarded (atom [])
          orig      (.-error js/console)]
      (set! (.-error js/console) (fn [& args] (swap! forwarded conj (vec args))))
      (try
        (with-console-error-capture counter
          (fn []
            (js/console.error
             "Warning: An update to n-view inside a test was not wrapped in act(...).")
            (js/console.error "an ordinary diagnostic, not an act warning")))
        (finally
          (set! (.-error js/console) orig)))
      (is (= 1 @counter)
          "exactly the act warning is counted — the ordinary diagnostic is not")
      (is (false? (zero? @counter))
          "a leaked act warning makes the zero-warning assertion RED")
      (is (= 2 (count @forwarded))
          "BOTH lines were forwarded to the original console — nothing suppressed"))))

;; ---------------------------------------------------------------------------
;; rf2-vxgfnd.25 — a compiled sub under a bare frame-root resolves ambiently
;; (frame-root now EMITS its scope; scope-element is reached, not dead)
;; ---------------------------------------------------------------------------

(deftest sub-under-frame-root-resolves-scoped-frame-ambiently
  (when (browser?)
    (reg!)
    (let [c    (container)
          root (act!
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
          ;; The render throw is DELIBERATE, so this mount uses
          ;; `mount-expecting-abort!` (flushSync, act env off) rather than
          ;; `act!` — React `act` would surface the intended error instead of
          ;; letting `onUncaughtError` own it (rf2-vxgfnd.89). The teardown
          ;; unmount below still runs under `act!` (assert-torn-down!).
          root     (mount-expecting-abort!
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
