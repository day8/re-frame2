(ns re-frame.ui.frame-scope-resolve-dom-cljs-test
  "rf2-vxgfnd.24 + rf2-vxgfnd.25 (+ .138) — the KEYSTONE, proven end to end on a
  REAL react-dom root through a REAL ViewCell: a compiled `(sub …)` mounted
  inside a `frame-provider` / `frame-root` subtree resolves the scoped
  frame's app-db on the AMBIENT React-context path — no explicit pin, no
  dynamic `with-frame` binding.

    - .24 publishes the `:adapter/current-frame` reader (re-frame.ui.substrate),
      so core's `require-current-frame!` — reached by the compiled sub-read
      (`reactive/sub-read` → `observation/resolve-target`) — sees the
      React-context tier.
    - .25 makes `frame-root` EMIT that scope (frames/scope-element), so a sub
      under a bare `frame-root` (no enclosing `frame-provider`) resolves too.
    - .138 extends .24 past the mount-time seed to a GENUINE post-mount
      compiled-`sub` update — dispatch → drain → observation → microtask
      reactive flush → useSyncExternalStore → React rerender — and proves it
      stays inside awaited React `act()` (zero 'not wrapped in act(...)'
      warnings escape).
    - rf2-6sslj gives that zero-warning proof a REAL CAUSAL negative control
      (`unacted-post-mount-update-warns-and-is-caught`): it drives the SAME
      post-mount update WITHOUT an act boundary and proves React emits — and the
      shared capture CATCHES — the real 'not wrapped in act(...)' warning, so the
      positive zero is meaningful rather than vacuous.

  Runs on the re-frame.ui REACTIVE adapter (`ui/adapter`). .24/.25 resolve the
  scoped frame identically under any substrate (the `:adapter/current-frame`
  reader ships in re-frame.ui, not the substrate adapter); .138's post-mount
  RERENDER is the reason the reactive adapter is required — a headless substrate
  (e.g. plain-atom) renders the mount seed but wires no reactive→React rerender,
  so a post-mount dispatch would never repaint.

  The `(sub …)` lives in a DESCENDANT view boundary (`n-view`), not the
  provider's own body — React-context semantics scope descendants, and a
  ViewCell render is where `_currentValue` is live.

  Browser-only bodies — `-dom-cljs-test$` opts this file into `:browser-test`;
  under `:node-test` every DOM body gates on `(browser?)` and exits early."
  (:require [cljs.test :refer [async deftest is testing use-fixtures]]
            ["react" :as React]
            ["react-dom" :as react-dom]
            [re-frame.core :as rf]
            [re-frame.test-support :as test-support]
            [re-frame.ui :as ui :refer [defview frame-root frame-provider sub]]
            [re-frame.ui.client :as client]
            [re-frame.ui.frames :as frames]
            [re-frame.ui.reactive :as reactive]
            [re-frame.ui.test :as uit]))

(defn- browser? [] (exists? js/document))

;; ---------------------------------------------------------------------------
;; The canonical React act() helper (rf2-vxgfnd.89)
;;
;; This is the repository's established native-React act pattern — the same
;; get-act / enable-react-act-env! shape as re-frame.adapter's shared React
;; suite (core/.../react_shared_suite.cljs `with-browser-act`) and the
;; machines-viz `*-dom-cljs-test` namespaces. It uses React's OWN `act()`, NOT
;; UIx/Reagent machinery, so this fixture stays native re-frame.ui on the
;; reactive adapter.
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
;; write drain → observation callback → microtask reactive flush →
;; useSyncExternalStore listener → React rerender, through the framework's
;; canonical awaited-act flush idiom (`ui.test/flush!`). Because that idiom
;; commits the rerender INSIDE awaited React `act`, React emits NO "An update to
;; … was not wrapped in act(...)" warning. Drive the same update through a
;; non-awaited path — or drop the awaited act — and the commit escapes act while
;; IS_REACT_ACT_ENVIRONMENT is true (this ns enables it per test), so React
;; DOES emit that console.error. The browser runner records console output but
;; does NOT fail on a mere warning (`scripts/run-browser-tests.cjs`), so without
;; a capture the zero-warning obligation could stay false-green.
;; `with-console-error-capture` gives it teeth: it FORWARDS every console.error
;; line to the original console (diagnostics are never suppressed) and, bounded
;; to the wrapped operation, COUNTS the act-discipline warning. No global
;; console suppression and no broad unrelated-warning policy — only the act
;; warning is counted, and the original console.error is restored on every exit
;; (synchronously for a plain thunk; after the Promise settles for the awaited
;; flush, so the capture spans the async React commit).

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
  increments `counter`. Returns the thunk's value. The original `console.error`
  is restored on exit (bounded to this call — nothing global changes): for a
  synchronous thunk immediately, and for a thunk that returns a Promise (the
  awaited-act flush idiom) only after that Promise settles, so the capture spans
  the async React commit and the original returned value is preserved."
  [counter thunk]
  (let [orig     (.-error js/console)
        restore! (fn [] (set! (.-error js/console) orig))]
    (set! (.-error js/console)
          (fn [& args]
            (when (some act-warning? args)
              (swap! counter inc))
            (.apply orig js/console (into-array args))))
    (let [result (try (thunk) (catch :default e (restore!) (throw e)))]
      (if (instance? js/Promise result)
        (.finally result restore!)
        (do (restore!) result)))))

;; --- un-acted settlement seam for the causal negative control (rf2-6sslj) -----
;;
;; The positive fixture proves ZERO act warnings when the post-mount compiled-sub
;; update rides the awaited-act `ui.test/flush!` idiom. That zero is only
;; meaningful if the SAME `with-console-error-capture`, on the SAME
;; dispatch→observation→microtask→React-commit path, genuinely CATCHES React's
;; real warning when the act boundary is ABSENT. `act-warning-detector-has-teeth`
;; proves the predicate + counter against SIMULATED warning text (cross-runtime,
;; node included); the CAUSAL proof — a real un-acted React commit that actually
;; emits the warning — is `unacted-post-mount-update-warns-and-is-caught` below.
;;
;; Driving that un-acted path needs a settle boundary the framework's own
;; machinery reaches WITHOUT an act wrapper. A synchronous `flush-pending!` right
;; after dispatch is too EARLY — value-movement `on-change` marks the cell on a
;; COALESCED MICROTASK (reactive/schedule-flush!), so a synchronous flush drains
;; an empty registry and the DOM never repaints (the trap the .138 author hit and
;; documented). So the seam is HONEST host time: dispatch OUTSIDE act, then yield
;; to the host MACROTASK queue — which drains the whole microtask cascade (the
;; coalesced mark, the armed flush, and React's own sync-commit work) — and poll
;; to a bounded fixed point. No act, no `ui.test/flush!`, no public surface, no
;; console suppression: just `setTimeout` and the shared capture.
(defn- await-macrotask
  "A Promise that resolves on the next host MACROTASK — a boundary that first
  drains the entire pending microtask queue (the coalesced reactive flush + the
  React sync-commit work), so an un-acted commit has fully landed by the time it
  resolves."
  []
  (js/Promise. (fn [resolve _] (js/setTimeout resolve 0))))

(defn- drive-until!
  "Yield through host macrotasks until `done?` is truthy or `budget` turns are
  spent, returning a Promise. BOUNDED, so a path that never satisfies `done?`
  lets the caller's assertion fail RED rather than hang — the deterministic-red
  contract the negative control needs (never an unbounded settle)."
  [done? budget]
  (if (or (done?) (not (pos? budget)))
    (js/Promise.resolve nil)
    (.then (await-macrotask) (fn [_] (drive-until! done? (dec budget))))))

;; reset-live-roots! / reset-installed-plans! / reset-scheduler! are BOOKKEEPING
;; resets — a clean framework slate between tests. They are NOT host teardown:
;; each test OWNS the React root it mounts and `ui/unmount!`s it in a `finally`
;; (see `assert-torn-down!`). This fixture is only the belt-and-suspenders slate,
;; not a substitute for `.unmount()`.
;; MAP-form fixtures. cljs.test requires EVERY fixture in a namespace with an
;; async test to be a `{:before :after}` map — a wrapping fn-fixture calls the
;; test synchronously and cannot host an async `done` (it aborts with "Async
;; tests require fixtures to be specified as maps"). rf2-vxgfnd.138's post-mount
;; rerender proof awaits a real react-dom commit, so both fixtures below are
;; map-form; the synchronous tests in this ns run under them unchanged. cljs.test
;; runs tests sequentially (each async `done` gates the next), so the single
;; `prior-act-env` atom safely threads the restore context between :before and
;; :after — the same pattern `make-reset-runtime-fixture`'s `:async?` map uses.
(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter ui/adapter
                                            :ambient-frame nil
                                            :async? true})
  ;; Enable React's act environment for this ns's DOM tests (act() warns /
  ;; no-ops unless IS_REACT_ACT_ENVIRONMENT is set), then RESTORE the prior
  ;; value so the fixture neither depends on nor leaks the shared-page flag.
  (let [prior-act-env (atom nil)]
    {:before
     (fn []
       (when (browser?)
         (reset! prior-act-env (.-IS_REACT_ACT_ENVIRONMENT js/globalThis)))
       (enable-react-act-env!)
       (reactive/reset-scheduler!)
       (client/reset-live-roots!)
       (frames/reset-installed-plans!))
     :after
     (fn []
       (reactive/reset-scheduler!)
       (client/reset-live-roots!)
       (frames/reset-installed-plans!)
       (when (browser?)
         (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) @prior-act-env)))}))

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
      (is (re-find #"n=42" (.-innerHTML c))
          (str "the ambient (sub …) resolved the frame-provider-scoped frame "
               "through the React-context tier — the :adapter/current-frame "
               "reader is live on the compiled sub-read path (rf2-vxgfnd.24)"))
      ;; rf2-vxgfnd.138 — a GENUINE post-mount compiled-`sub` update: dispatch
      ;; n=43 against the ALREADY-MOUNTED frame and settle the pending
      ;; framework/reactive work through the framework's canonical awaited-act
      ;; flush idiom (`ui.test/flush!`, 07 §2 "act + epoch drain + commit").
      ;; This exercises the real runtime path the mount-time seed never reaches
      ;; — dispatch → drain → observation callback → microtask reactive flush →
      ;; useSyncExternalStore → React rerender — where framework notifications
      ;; and React commits alternate to a fixed point INSIDE awaited React act.
      ;; The whole rerender commits under act, so React emits no warning and the
      ;; capture counts zero. A non-awaited path (an `act!` wrapping dispatch +
      ;; a synchronous `flush-pending!`) flushes BEFORE the coalesced microtask
      ;; marks the cell, never commits the rerender (the DOM stays n=42), and —
      ;; were the commit forced outside awaited act — would leak the
      ;; "not wrapped in act(...)" warning the capture (teeth proven by
      ;; `act-warning-detector-has-teeth`) is here to catch. Async because a
      ;; real react-dom commit under React 19 `act` settles on a Promise.
      (async done
        (-> (js/Promise.resolve)
            (.then
             (fn []
               (with-console-error-capture warnings
                 (fn []
                   (uit/flush! #(rf/dispatch-sync [:test/set-db {:n 43}] {:frame :app/live}))))))
            (.then
             (fn []
               (is (re-find #"n=43" (.-innerHTML c))
                   (str "the post-mount dispatch drove the compiled ambient "
                        "(sub …) through observation → reactive flush → React "
                        "rerender; the mounted ViewCell repainted the scoped "
                        "frame's new value"))
               (is (zero? @warnings)
                   (str "the post-mount dispatch + reactive settlement stayed "
                        "inside awaited act() — zero 'not wrapped in act(...)' "
                        "warnings escaped"))))
            (.catch
             (fn [e]
               (is false (str "post-mount flush! rejected: "
                              (some-> e .-message)))))
            (.then
             (fn []
               (assert-torn-down! root)
               (done))))))))

(deftest unacted-post-mount-update-warns-and-is-caught
  ;; rf2-6sslj — the CAUSAL negative control for the zero-warning assertion in
  ;; `sub-under-frame-provider-resolves-scoped-frame-ambiently`. That positive
  ;; test drives the post-mount compiled-`sub` update through the awaited-act
  ;; `ui.test/flush!` idiom and asserts `with-console-error-capture` counts ZERO
  ;; "not wrapped in act(...)" warnings. Alone, that zero could be VACUOUS: a
  ;; capture that can never OBSERVE a real un-acted commit proves nothing about
  ;; whether it WOULD catch one, so removing the act boundary need not fail for
  ;; the claimed reason (the .138 proof gap this bead closes).
  ;;
  ;; This drives the SAME mounted dispatch→observation→microtask→React-commit
  ;; path WITHOUT any act boundary. IS_REACT_ACT_ENVIRONMENT stays true (the
  ;; fixture enabled it), so when the framework's OWN coalesced microtask flush
  ;; fires outside act — advance-revision! → useSyncExternalStore notify → React
  ;; rerender + commit — React emits the REAL "not wrapped in act(...)"
  ;; console.error. The SAME `with-console-error-capture` the positive path relies
  ;; on FORWARDS it (diagnostics preserved) and COUNTS it, turning the real
  ;; warning into a deterministic RED assertion (`(pos? @warnings)`). The DOM
  ;; really repaints n=43, so this is a GENUINE post-mount React commit — not a
  ;; no-op that happens to warn for an unrelated reason. Mount + teardown stay
  ;; under `act!`, so root/ViewCell/frame/observation ownership returns to
  ;; baseline. Browser-only: a headless substrate wires no reactive→React commit,
  ;; so there is no un-acted commit to warn about.
  (when (browser?)
    (reg!)
    (rf/make-frame {:id :app/unacted})
    (rf/dispatch-sync [:test/set-db {:n 42}] {:frame :app/unacted})
    (let [c        (container)
          warnings (atom 0)
          root     (act!
                    #(ui/mount [provider-wrap {:frame-id :app/unacted}] c
                               {:root-id :dom-scope/unacted}))]
      (is (re-find #"n=42" (.-innerHTML c))
          "the ambient (sub …) rendered the frame-provider-scoped seed at mount")
      (async done
        (-> (js/Promise.resolve)
            (.then
             (fn []
               (with-console-error-capture warnings
                 (fn []
                   ;; Dispatch OUTSIDE act — no `act!`, no `ui.test/flush!`. This
                   ;; marks the mounted ViewCell dirty and ARMS the coalesced
                   ;; microtask flush; the flush + React commit then run outside
                   ;; any act scope while IS_REACT_ACT_ENVIRONMENT is true, so
                   ;; React warns. Yield to the host macrotask queue (draining the
                   ;; whole microtask cascade) and poll to the fixed point where
                   ;; the real warning has been caught.
                   (rf/dispatch-sync [:test/set-db {:n 43}] {:frame :app/unacted})
                   (drive-until! #(pos? @warnings) 30)))))
            (.then
             (fn []
               (is (re-find #"n=43" (.-innerHTML c))
                   (str "the un-acted dispatch drove the SAME compiled ambient "
                        "(sub …) commit path — the DOM really repainted n=43, so "
                        "the warning came from a genuine post-mount React commit, "
                        "not a no-op"))
               (is (pos? @warnings)
                   (str "the un-acted commit escaped act while "
                        "IS_REACT_ACT_ENVIRONMENT was true, so React emitted the "
                        "real 'not wrapped in act(...)' warning and the SAME "
                        "with-console-error-capture the positive test relies on "
                        "CAUGHT it — the detector has teeth on a REAL warning, so "
                        "the positive zero is meaningful"))))
            (.catch
             (fn [e]
               (is false (str "un-acted negative control rejected: "
                              (some-> e .-message)))))
            (.then
             (fn []
               (assert-torn-down! root)
               (done))))))))

(deftest act-warning-detector-has-teeth
  ;; rf2-vxgfnd.138 proof discipline — the zero-warning assertion above is only
  ;; meaningful if its predicate genuinely matches React's act diagnostic AND
  ;; the capture actually COUNTS it (fails, not merely prints). Prove both here
  ;; WITHOUT emitting a real warning (a real one would itself violate the
  ;; zero-warning contract), so this runs cross-runtime — including under
  ;; `:node-test`, where the DOM-mount body above is gated out. This is the
  ;; PREDICATE + COUNTER proof (against simulated warning text); the CAUSAL proof
  ;; that a real un-acted commit actually emits — and this same capture catches —
  ;; React's warning is the browser-only `unacted-post-mount-update-warns-and-is-
  ;; caught` above (rf2-6sslj). Removing the update's `act!` boundary makes the
  ;; live capture behave exactly like the simulated warning below: `counter`
  ;; increments and `(zero? @counter)` is false — which the causal negative
  ;; control demonstrates end to end on the real React runtime.
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
