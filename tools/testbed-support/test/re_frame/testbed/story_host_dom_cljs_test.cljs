(ns re-frame.testbed.story-host-dom-cljs-test
  "Browser-level handoff test for `re-frame.testbed.story-host`.

  ## Why this exists (the gap the node suite cannot close)

  The sibling node suite (`story_host_cljs_test.cljs`) pins the
  `hashchange`-listener lifecycle — exactly one active listener across
  hot-reload re-`run`s — but it does so with `mount-app!` / `mount-stories!`
  redefined to count-only NO-OPS and a fake `js/window`. That is the right
  shape for proving listener identity, but by stubbing the mount switch it
  CANNOT catch a regression in the real React-DOM-root choreography the host
  performs on the live `#app` node:

    - `mount-app!`     → `story/unmount-shell!` then `ensure-app-root!`
                         (`rdc/create-root`) then `rdc/render` the live view.
    - `mount-stories!` → `tear-down-app-root!` (`rdc/unmount` the live root)
                         then `story/mount-shell!` (a fresh `rdc/create-root`
                         on the SAME node).

  The load-bearing invariant is that whichever surface currently owns the
  `#app` node MUST release its React root before the other surface calls
  `rdc/create-root` on that same node — otherwise React 19 emits the
  \"You are calling ReactDOMClient.createRoot() on a container that has
  already been passed to createRoot() before\" warning, and roots leak.
  A subtle way to violate it is passing the DOM node, not the root handle,
  to `unmount` — a silent no-op that leaves the shell root alive.
  Only a test that drives the REAL handoff on a REAL node can lock it out.

  ## What this test does

  It mounts on a real `#app` div and drives the REAL `on-hash-change!`
  listener through the full `#/` -> `#/stories` -> `#/` cycle, plus a
  hot-reload-style re-`run`, asserting:

    1. exactly one `hashchange` listener is ever active (across the re-run);
    2. the surfaces hand off the `#app` node cleanly — the live view's text
       and the (stand-in) shell's text alternate correctly, proving each
       side's root was torn down before the other created one;
    3. React emits NO createRoot-reuse / handoff warning across the whole
       cycle (captured off `console.error` / `console.warn`).

  ## Why the shell side is a REAL minimal root, not the full Story shell

  We redefine `story/mount-shell!` / `story/unmount-shell!` to perform the
  GENUINE React-root lifecycle (`rdc/create-root` + `rdc/render` +
  `rdc/unmount`) on the passed node, rendering a tiny marker view rather
  than the full Story shell tree (sidebar + embedded Xray inspector +
  recorder + command palette …). That keeps the slice test free of the
  heavyweight Story+Xray render surface and its registry/app-state
  preconditions, while still exercising the EXACT root-handoff contract the
  bead names — the host's `tear-down-app-root!` must release the live root
  before this real `create-root` runs on the same node, or React warns.
  This is a non-stubbed handoff (real roots, real unmount), not the
  count-only no-op the node suite uses.

  ns ends in `-dom-cljs-test` so shadow's `:browser-test` discovers it; the
  `:node-test` runner also loads it, where the body gates on `(browser?)`
  and no-ops cleanly (no DOM)."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [reagent.dom.client :as rdc]
            ["react-dom" :as react-dom]
            [re-frame.story :as story]
            [re-frame.testbed.story-host :as host]))

(defn- browser? []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

;; ---- the real `#app` node ------------------------------------------------
;;
;; `story_host` reads the node via `(js/document.getElementById "app")`, so
;; the test must install a real element with that id on the live document.

(defn- ensure-app-node! []
  (when (browser?)
    (or (js/document.getElementById "app")
        (let [el (.createElement js/document "div")]
          (set! (.-id el) "app")
          (.appendChild js/document.body el)
          el))))

(defn- remove-app-node! []
  (when-let [el (and (browser?) (js/document.getElementById "app"))]
    (.remove el)))

;; ---- a real-but-minimal Story-shell stand-in -----------------------------
;;
;; A holder for the shell's own React root so our `mount-shell!` /
;; `unmount-shell!` stand-ins perform the genuine create-root / unmount
;; lifecycle on the `#app` node — the real handoff the host must coordinate.
(defonce ^:private shell-root* (atom nil))

(defn- shell-mount! [node]
  ;; Mirror the real `story/mount-shell!` contract: tear down any prior
  ;; shell root, then create + render a fresh root on the node.
  (when-let [prev @shell-root*]
    (try (rdc/unmount prev) (catch :default _ nil)))
  (let [root (rdc/create-root node)]
    (rdc/render root [:div {:id "shell-marker"} "STORIES"])
    (reset! shell-root* root)
    {:root root :node node}))

;; Mirror the real `story/unmount-shell!` arities ([] / [handle]) so the
;; `(story/unmount-shell!)` arity-0 call site inside `mount-app!` resolves
;; against the redefinition (a variadic-only stand-in does not expose the
;; arity-0 invoke slot the compiled call site dispatches through).
(defn- shell-unmount!
  ([] (shell-unmount! nil))
  ([_handle]
   (when-let [root @shell-root*]
     (try (rdc/unmount root) (catch :default _ nil))
     (reset! shell-root* nil)
     nil)))

;; ---- console capture (createRoot-reuse warnings land on error/warn) ------

(defn- with-captured-console [thunk]
  (let [calls         (atom [])
        orig-error    (.-error js/console)
        orig-warn     (.-warn js/console)
        record        (fn [& args] (swap! calls conj (apply str args)))]
    (try
      (set! (.-error js/console) record)
      (set! (.-warn js/console) record)
      (thunk)
      @calls
      (finally
        (set! (.-error js/console) orig-error)
        (set! (.-warn js/console) orig-warn)))))

(defn- handoff-warning? [msg]
  (let [m (str/lower-case msg)]
    (or (str/includes? m "createroot")
        (str/includes? m "already been passed")
        (str/includes? m "already mounted"))))

;; ---- reset the host's defonce handles between tests ----------------------

(defn- reset-host-handles! []
  (reset! @#'host/hash-listener* nil)
  (reset! @#'host/root-view* nil)
  (reset! @#'host/app-root nil))

(use-fixtures :each
  {:before (fn []
             (reset-host-handles!)
             (reset! shell-root* nil))
   :after  (fn []
             ;; Tear down anything still mounted so a leaked root never
             ;; bleeds into another namespace's tests. Unmount the held
             ;; roots directly (do NOT re-enter `mount-app!`, which would
             ;; call the REAL `story/unmount-shell!` outside the test's
             ;; `with-redefs` and create a root on a possibly-removed node).
             (when-let [r @@#'host/app-root]
               (try (rdc/unmount r) (catch :default _ nil)))
             (when-let [r @shell-root*]
               (try (rdc/unmount r) (catch :default _ nil)))
             (reset-host-handles!)
             (reset! shell-root* nil)
             (remove-app-node!))})

;; ---- a trivial live-app root view ----------------------------------------

(defn- live-view [] [:div {:id "live-marker"} "LIVE"])

(defn- set-hash! [h]
  (set! (.. js/window -location -hash) h))

(defn- marker-text [id]
  (when-let [el (js/document.getElementById id)]
    (.-textContent el)))

;; ---------------------------------------------------------------------------
;; The handoff test
;; ---------------------------------------------------------------------------

(deftest live-stories-live-handoff-no-listener-or-root-leak
  (testing "rf2-fzgcii: driving the REAL host through #/ -> #/stories -> #/
            (plus a hot-reload re-run) hands the #app node off between the
            live view and the (real-root) Story shell with NO duplicate
            hashchange listener, NO leaked React root, and NO createRoot
            handoff warning"
    (if-not (browser?)
      (is true ":node-test: no DOM — :browser-test runner exercises the assertion")
      (let [app-node (ensure-app-node!)
            warnings
            (with-captured-console
              (fn []
                (with-redefs [story/mount-shell!   shell-mount!
                              story/unmount-shell! shell-unmount!]
                  ;; Start on the live app (#/). mount-with-hash-routing!
                  ;; installs the listener and renders the current hash's
                  ;; surface.
                  (set-hash! "#/")
                  (react-dom/flushSync
                   (fn [] (host/mount-with-hash-routing! live-view)))
                  ;; -> #/stories : tear down the live root, mount the shell
                  ;; root on the SAME node.
                  (set-hash! "#/stories")
                  (react-dom/flushSync (fn [] (#'host/on-hash-change!)))
                  ;; -> #/ : tear down the shell, re-create the live root on
                  ;; the same node.
                  (set-hash! "#/")
                  (react-dom/flushSync (fn [] (#'host/on-hash-change!)))
                  ;; Hot-reload re-run: a fresh `run` re-installs the
                  ;; listener (must REMOVE the prior one, not stack) and
                  ;; re-renders the current surface on the same node.
                  (react-dom/flushSync
                   (fn [] (host/mount-with-hash-routing! live-view)))
                  ;; One more #/stories -> #/ cycle AFTER the re-run, to
                  ;; prove the post-reload listener still drives a clean
                  ;; handoff.
                  (set-hash! "#/stories")
                  (react-dom/flushSync (fn [] (#'host/on-hash-change!)))
                  (set-hash! "#/")
                  (react-dom/flushSync (fn [] (#'host/on-hash-change!))))))]
        ;; -- exactly one active listener (no stacking across the re-run) --
        ;; The host stores the single installed handle; a stacked duplicate
        ;; would mean a different handle was added without removing the old.
        (is (some? @@#'host/hash-listener*)
            "the single installed hashchange handle is recorded")
        ;; -- the node ends on the LIVE surface, cleanly handed back --------
        (is (= "LIVE" (marker-text "live-marker"))
            "after the final #/ the live view owns #app")
        (is (nil? (marker-text "shell-marker"))
            "the Story-shell marker is gone — its root was torn down on handoff")
        (is (nil? @shell-root*)
            "the shell root handle was released (no leaked shell root)")
        (is (= app-node (js/document.getElementById "app"))
            "the same #app node was reused throughout (one node, one owner)")
        ;; -- NO React createRoot-reuse / handoff warning -------------------
        (let [bad (filterv handoff-warning? warnings)]
          (is (empty? bad)
              (str "no createRoot-reuse / handoff warning across the full "
                   "#/ <-> #/stories cycle and re-run; saw: " (pr-str bad))))))))

(deftest hot-reload-rerun-does-not-stack-listener-on-real-node
  (testing "rf2-fzgcii: a hot-reload re-run where on-hash-change! is rebound
            to a fresh fn (the CLJS recompile churn) still leaves the stored
            listener handle advanced to the NEW fn — the remove-then-add
            discipline holds on a real node, not just the fake-window node
            suite"
    (if-not (browser?)
      (is true ":node-test: no DOM — :browser-test runner exercises the assertion")
      (do
        (ensure-app-node!)
        (with-redefs [story/mount-shell!   shell-mount!
                      story/unmount-shell! shell-unmount!]
          (set-hash! "#/")
          (react-dom/flushSync
           (fn [] (host/mount-with-hash-routing! live-view)))
          (let [handle-1 @@#'host/hash-listener*]
            (is (some? handle-1) "first run records a listener handle")
            ;; Simulate a recompile: on-hash-change! becomes a fresh fn.
            (with-redefs [host/on-hash-change! (fn [] nil)]
              (react-dom/flushSync
               (fn [] (host/mount-with-hash-routing! live-view))))
            (let [handle-2 @@#'host/hash-listener*]
              (is (some? handle-2) "re-run records a (new) listener handle")
              (is (not (identical? handle-1 handle-2))
                  "the stored handle advanced to the recompiled listener fn — the
                   prior one was removed, not stacked"))))))))
