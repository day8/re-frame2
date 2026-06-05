(ns re-frame.testbed.story-host-cljs-test
  "Focused unit tests for `re-frame.testbed.story-host` (rf2-liive).

  ## What this pins

  `mount-with-hash-routing!` is the shared live-app ↔ Story-shell host:
  every Story showcase testbed calls it LAST in its `run` to install one
  `hashchange` listener that swaps the surface mounted on `#app`. The whole
  point of the helper is to be HOT-RELOAD IDEMPOTENT — a testbed dev edits a
  file, shadow recompiles + re-runs `run`, and `mount-with-hash-routing!`
  fires again. It must NOT accumulate a second listener on that second (and
  third, …) call, or each later hash change runs the mount switch N times,
  repeatedly tearing down/remounting the Story shell on the same `#app` node
  (lost shell state, leaked shell listeners/polls, React `createRoot` churn).

  ## Why the OLD code was unsound (the bug these tests lock out)

  The previous implementation installed the top-level `on-hash-change!`
  `defn` as the listener and relied on the browser to no-op a *repeat*
  `addEventListener` of the same fn reference. But a CLJS hot-reload
  recompiles the namespace, rebinding the `on-hash-change!` `defn` to a
  FRESH function object. So the post-reload re-`run` passed a DIFFERENT
  reference — the browser did not dedupe, and a second listener stacked.
  The documented \"idempotent across hot-reload\" contract was therefore
  false for every Story showcase that consumes the helper.

  The fix mirrors the adjacent React-root store/remove discipline
  (`app-root` / `tear-down-app-root!`): the installed listener handle is
  stashed in the `defonce` `hash-listener*` atom and explicitly REMOVED
  before the next one is installed.

  ## How the hot-reload identity churn is simulated off-browser

  The genuine hazard is `on-hash-change!`'s identity changing across a
  recompile. We reproduce exactly that by `with-redefs`-ing the private
  `story-host/on-hash-change!` to a fresh fn for the second `run` — the
  same technique `config_cljs_test.cljs` uses to redefine private vars. A
  controllable fake `js/window` records every `addEventListener` /
  `removeEventListener` call into a real registry, so we can assert the
  number of ACTIVE `hashchange` listeners directly. `mount-app!` /
  `mount-stories!` are redefined to count-only no-ops so the test never
  touches React-DOM (there is no `#app` node, and no `js/document`, under
  `:node-test`)."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.testbed.story-host :as host]))

;; ---------------------------------------------------------------------------
;; A controllable fake `js/window`.
;;
;; Holds a `hashchange`-listener registry (a JS Set of installed fns) plus a
;; mutable `location.hash`. `addEventListener` / `removeEventListener` mutate
;; that set with reference identity — the exact semantics the browser uses and
;; the exact thing the fix depends on. We count ACTIVE listeners by the set's
;; size, so a stacked duplicate (the bug) shows up as size 2.
;; ---------------------------------------------------------------------------

(defn- make-fake-window
  "Build a fake window whose add/removeEventListener maintain a real
  reference-identity registry. Returns a map of the window object plus a
  0-arg `hashchange-count` reader for the test to assert on."
  ([] (make-fake-window "#/"))
  ([initial-hash]
   (let [registry (js/Set.)
         window   #js {}]
     (set! (.-location window) #js {:hash initial-hash})
     (set! (.-addEventListener window)
           (fn [type listener]
             (when (= type "hashchange")
               (.add registry listener))))
     (set! (.-removeEventListener window)
           (fn [type listener]
             (when (= type "hashchange")
               (.delete registry listener))))
     {:window          window
      :registry        registry
      :hashchange-count (fn [] (.-size registry))})))

;; Install / clear the fake on `goog.global` directly (the Closure global
;; object `js/window` references resolve against). We write
;; `goog.global.window` rather than bare `js/window`: under shadow's
;; `:node-test` target a bare-`window` `set!` is a strict-mode write to an
;; undeclared global and throws `ReferenceError`, whereas `goog.global` is a
;; real object we may add a property to — the same `goog.global` slot the
;; xray keybinding tests stub `js/document` through (rf2-higwg). Reads of
;; `js/window` inside `story-host` then see the fake.
(def ^:private real-window (when (exists? js/window) js/window))

(defn- install-window! [w]
  (set! (.-window js/goog.global) w))

(defn- clear-window! []
  (if real-window
    (set! (.-window js/goog.global) real-window)
    (js-delete js/goog.global "window")))

(defn- reset-host-handles!
  "Reset the helper's `defonce` private atoms to a clean baseline. They are
  `defonce` (they survive across calls by design), so a test that wants a
  pristine starting point must reset them explicitly."
  []
  (reset! @#'host/hash-listener* nil)
  (reset! @#'host/root-view* nil))

(use-fixtures :each
  {:before (fn [] (reset-host-handles!))
   :after  (fn []
             ;; Remove the fake so it never leaks into another namespace's
             ;; tests; restore the node-test baseline (no `window`).
             (clear-window!)
             (reset-host-handles!))})

;; A trivial 0-arg root view stand-in (never rendered — mount-* are no-ops).
(defn- dummy-view [] [:div "dummy"])

;; ---------------------------------------------------------------------------
;; Tests
;; ---------------------------------------------------------------------------

(deftest single-run-installs-exactly-one-listener
  (testing "one `mount-with-hash-routing!` call installs exactly one active
            hashchange listener, and stashes that exact handle in
            `hash-listener*`"
    (let [{:keys [window hashchange-count]} (make-fake-window "#/")
          switches (atom 0)]
      (install-window! window)
      (with-redefs [host/mount-app!     (fn [] (swap! switches inc))
                    host/mount-stories! (fn [] (swap! switches inc))]
        (host/mount-with-hash-routing! dummy-view))
      (is (= 1 (hashchange-count))
          "exactly one hashchange listener active after a single run")
      (is (some? @@#'host/hash-listener*)
          "the installed handle is recorded for later removal")
      (is (= 1 @switches)
          "the initial `on-hash-change!` ran the mount switch exactly once"))))

(deftest re-run-with-changed-handler-identity-does-not-stack
  (testing "rf2-liive: a second `mount-with-hash-routing!` run AFTER a
            simulated hot-reload — where `on-hash-change!` has been
            recompiled to a FRESH function object — removes the prior
            listener and installs the new one, leaving exactly ONE active
            hashchange listener (not two). This is the regression the old
            browser-dedupe assumption failed to provide."
    (let [{:keys [window hashchange-count]} (make-fake-window "#/")]
      (install-window! window)
      (with-redefs [host/mount-app!     (constantly nil)
                    host/mount-stories! (constantly nil)]
        ;; Run #1 — installs the real `on-hash-change!` (identity #1).
        (host/mount-with-hash-routing! dummy-view)
        (is (= 1 (hashchange-count)) "one listener after the first run")
        (let [handle-1 @@#'host/hash-listener*]
          ;; Run #2 — simulate a hot-reload by rebinding `on-hash-change!`
          ;; to a DIFFERENT function object (what a CLJS recompile does to a
          ;; top-level `defn`), then re-`run`.
          (with-redefs [host/on-hash-change! (fn [] nil)]
            (host/mount-with-hash-routing! dummy-view))
          (is (= 1 (hashchange-count))
              "STILL exactly one listener after the post-reload re-run — the
               prior listener was removed, not stacked")
          (let [handle-2 @@#'host/hash-listener*]
            (is (not (identical? handle-1 handle-2))
                "the stored handle advanced to the new (recompiled) listener")))))))

(deftest many-re-runs-never-accumulate-listeners
  (testing "across several hot-reload re-`run`s, each with a fresh
            `on-hash-change!` identity, the active hashchange listener count
            stays pinned at one — and dispatching a single hash change to the
            installed registry fires the mount switch exactly ONCE (not once
            per accumulated listener), which is the user-visible symptom the
            leak caused."
    (let [{:keys [window registry hashchange-count]} (make-fake-window "#/")
          switches (atom 0)]
      (install-window! window)
      ;; First run installs the genuine `on-hash-change!`.
      (with-redefs [host/mount-app!     (fn [] (swap! switches inc))
                    host/mount-stories! (fn [] (swap! switches inc))]
        (host/mount-with-hash-routing! dummy-view))
      ;; Then five more re-runs, each simulating a recompile (a fresh
      ;; `on-hash-change!` identity) that still routes through the same
      ;; count-only mount switch.
      (dotimes [_ 5]
        (with-redefs [host/on-hash-change! (fn [] (swap! switches inc))]
          (host/mount-with-hash-routing! dummy-view)))
      (is (= 1 (hashchange-count))
          "six runs total → still exactly one active listener (no leak)")
      ;; Fire ONE hash change by invoking every listener the registry holds
      ;; (the real browser would call each registered listener once). With a
      ;; single active listener the switch must run exactly once; the old
      ;; stacking bug would have run it six times.
      (reset! switches 0)
      (.forEach registry (fn [listener] (listener)))
      (is (= 1 @switches)
          "one hash change runs the mount switch exactly once — proving a
           single active listener, not an N-deep stack"))))
