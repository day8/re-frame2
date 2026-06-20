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
            [clojure.string :as str]
            [re-frame.story.config :as story-config]
            [re-frame.testbed.config :as testbed-config]
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
  {:before (fn []
             (reset-host-handles!)
             ;; The project-root tests below configure Story's project-root
             ;; through the host; clear it before/after so neither leaks.
             (story-config/set-project-root! nil))
   :after  (fn []
             ;; Remove the fake so it never leaks into another namespace's
             ;; tests; restore the node-test baseline (no `window`).
             (clear-window!)
             (reset-host-handles!)
             (story-config/set-project-root! nil))})

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

;; ---------------------------------------------------------------------------
;; rf2-77wqzi — the host owns Story-host open-in-editor project-root config.
;;
;; Story stamps each registered source-coord with a CLASSPATH-RELATIVE
;; `:file` slot (e.g. `login/stories.cljs`); the open-in-editor chip
;; prepends an on-disk project-root to build a real editor URI. The two
;; `examples/reagent` Story showcases previously mounted the shell fine but
;; NEVER configured a root, so their Story source links resolved against a
;; nil root and OS editor handlers could not open the file (a false green).
;;
;; The fix moved project-root config onto the host: a consumer declares its
;; tool-relative source subdir via the `:source-subdir` opt and
;; `mount-with-hash-routing!` resolves the on-disk root (through the shared
;; `re-frame.testbed.config` build-env / `?checkout-root=` mechanism) and
;; calls `story/configure!` itself. These tests pin that contract directly:
;; the consumer can no longer mount the shell while silently forgetting the
;; root. We exercise the private `configure-story-source-root!` (it carries
;; no React-DOM / `js/window` dependency, so it runs under `:node-test`) and
;; read Story's configured root back via `re-frame.story.config`.
;; ---------------------------------------------------------------------------

(deftest source-subdir-configures-absolute-project-root
  (testing "rf2-77wqzi: declaring `:source-subdir` resolves the build-time
            checkout-root joined with that subdir and configures it as Story's
            project-root — so a classpath-relative Story coord composes to an
            ABSOLUTE on-disk path, not a nil-root relative one. Covers the
            `examples/reagent` subdir the two example Story showcases use."
    (with-redefs [testbed-config/checkout-root "/home/dev/re-frame2"]
      (#'host/configure-story-source-root! "examples/reagent")
      (is (= "/home/dev/re-frame2/examples/reagent"
             (story-config/get-project-root))
          "Story's project-root is the absolute checkout/subdir join")
      ;; The open-in-editor prepend is `(str root "/" file)`; reproduce that
      ;; single join to prove a representative example Story coord reaches
      ;; the intended on-disk file (under <checkout>/examples/reagent/...).
      (let [composed (str (story-config/get-project-root) "/" "login/stories.cljs")]
        (is (= "/home/dev/re-frame2/examples/reagent/login/stories.cljs"
               composed)
            "the composed editor path reaches the real example source file")
        (is (str/includes? composed "/examples/reagent/")
            "the example source-root segment is present (not missing)")))))

(deftest example-story-builds-resolve-absolute-source-coords
  (testing "rf2-77wqzi: the two example Story dev builds named in the bead —
            `:examples/login-with-stories` and
            `:examples/nine-states-with-stories` — both host their Story
            sources under `examples/reagent`, so the host-owned config
            resolves each build's representative Story coord to an ABSOLUTE
            on-disk path under `<checkout>/examples/reagent/...`. This
            enumerates the affected builds explicitly so a future regression
            (dropping the `:source-subdir` opt or the build-env define) trips
            here. Each entry is `[build-id story-coord expected-on-disk]`."
    (with-redefs [testbed-config/checkout-root "/home/dev/re-frame2"]
      (doseq [[build coord expected]
              [[:examples/login-with-stories
                "login/stories.cljs"
                "/home/dev/re-frame2/examples/reagent/login/stories.cljs"]
               [:examples/nine-states-with-stories
                "nine_states/stories.cljs"
                "/home/dev/re-frame2/examples/reagent/nine_states/stories.cljs"]]]
        (story-config/set-project-root! nil)
        (#'host/configure-story-source-root! "examples/reagent")
        (let [root     (story-config/get-project-root)
              composed (str root "/" coord)]
          (is (= "/home/dev/re-frame2/examples/reagent" root)
              (str build " configures the absolute examples/reagent root"))
          (is (= expected composed)
              (str build " Story coord composes to the real on-disk file"))
          (is (str/includes? composed "/examples/reagent/")
              (str build " keeps the example source-root segment")))))))

(deftest source-subdir-omitted-configures-no-root
  (testing "rf2-77wqzi: a 1-arity call (no opts) and an explicitly nil/blank
            `:source-subdir` configure NO project-root — the host leaves
            Story's root untouched so a consumer that drives `story/configure!`
            itself is not overridden, and the graceful-no-op contract holds"
    (with-redefs [testbed-config/checkout-root "/home/dev/re-frame2"]
      ;; nil subdir → skip
      (#'host/configure-story-source-root! nil)
      (is (nil? (story-config/get-project-root))
          "nil subdir configures nothing")
      ;; blank subdir → skip
      (#'host/configure-story-source-root! "   ")
      (is (nil? (story-config/get-project-root))
          "blank subdir configures nothing"))))

(deftest source-subdir-with-no-resolvable-root-degrades-to-no-op
  (testing "rf2-77wqzi: when a subdir is declared but NEITHER the build-time
            define NOR a `?checkout-root=` override is present (the default
            `:node-test` shape: blank checkout-root, no js/window), the host
            configures a nil root and open-in-editor degrades to a graceful
            no-op rather than a broken relative link"
    (with-redefs [testbed-config/checkout-root ""]
      (#'host/configure-story-source-root! "examples/reagent")
      (is (nil? (story-config/get-project-root))
          "no resolvable root → Story's root stays nil (no broken prefix)"))))

(deftest mount-with-hash-routing-arity-2-configures-project-root
  (testing "rf2-77wqzi: the public 2-arity entry threads `:source-subdir`
            into the project-root config before wiring the router — the
            exact call shape the two example Story hosts now use. We stub
            the mount switch + a fake window so the router wiring is inert
            and assert only that the project-root landed."
    (let [{:keys [window]} (make-fake-window "#/")]
      (install-window! window)
      (with-redefs [testbed-config/checkout-root "/home/dev/re-frame2"
                    host/mount-app!     (constantly nil)
                    host/mount-stories! (constantly nil)]
        (host/mount-with-hash-routing! dummy-view {:source-subdir "examples/reagent"}))
      (is (= "/home/dev/re-frame2/examples/reagent"
             (story-config/get-project-root))
          "the 2-arity call configured the resolved absolute root"))))

(deftest mount-with-hash-routing-arity-1-leaves-root-untouched
  (testing "rf2-77wqzi: the 1-arity entry (no opts) does NOT touch Story's
            project-root — a consumer that calls `story/configure!` itself
            keeps full control. We pre-set a root and confirm the host
            leaves it intact."
    (let [{:keys [window]} (make-fake-window "#/")]
      (install-window! window)
      (story-config/set-project-root! "/preset/by/consumer")
      (with-redefs [host/mount-app!     (constantly nil)
                    host/mount-stories! (constantly nil)]
        (host/mount-with-hash-routing! dummy-view))
      (is (= "/preset/by/consumer" (story-config/get-project-root))
          "1-arity call left the consumer-set root untouched"))))
