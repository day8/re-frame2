(ns re-frame.testbed.story-host-dom-cljs-test
  "Browser tests for the Story-host React-root handoff.

  The Node suite covers listener identity with mount stubs. This suite uses a
  real DOM node and minimal real React roots to verify that each surface
  unmounts before the other claims `#app`. Its bodies no-op under Node.

  Unlike the Node suite's fake window — which `clear-window!` throws away
  whole between cases — `js/window` here is the SHARED browser page, so a
  listener this suite installs outlives the test that installed it unless
  the fixture removes it by its exact recorded identity. See
  `unregister-host-listener!` and the census test at the bottom
  (rf2-6r9j.116)."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [reagent.dom.client :as rdc]
            ["react-dom" :as react-dom]
            [re-frame.story :as rf.story]
            [re-frame.testbed.story-host :as rf.testbed.story-host]))

(defn- browser? []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

;; story-host finds the target by id, so install it on the live document.

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

;; A minimal shell root exercises the real create/render/unmount lifecycle.
(defonce ^:private shell-root* (atom nil))

(defn- shell-mount! [node]
  ;; Match mount-shell!: release any prior root before creating the next one.
  (when-let [prev @shell-root*]
    (try (rdc/unmount prev) (catch :default _ nil)))
  (let [root (rdc/create-root node)]
    (rdc/render root [:div {:id "shell-marker"} "STORIES"])
    (reset! shell-root* root)
    {:root root :node node}))

;; Preserve both real arities so compiled arity-0 calls reach the redefinition.
(defn- shell-unmount!
  ([] (shell-unmount! nil))
  ([_handle]
   (when-let [root @shell-root*]
     (try (rdc/unmount root) (catch :default _ nil))
     (reset! shell-root* nil)
     nil)))

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

(defn- unregister-host-listener!
  "Remove the EXACT hashchange listener the host recorded, using the stored
  identity — the ONLY handle able to unregister it — BEFORE that handle is
  discarded.

  Production never needs this: the host owns `#app` for the life of the page
  and `mount-with-hash-routing!` re-registration is self-balancing, because it
  removes `@hash-listener*` before adding the new fn. But a fixture that only
  nils the atom strands a live listener on the shared browser page — the next
  mount then finds `nil` where the previous handle was, so it ADDS a second
  listener rather than replacing the first, and every later namespace on the
  page inherits them all."
  []
  (when (browser?)
    (when-let [listener @@#'rf.testbed.story-host/hash-listener*]
      (.removeEventListener js/window "hashchange" listener))))

(defn- reset-host-handles! []
  (unregister-host-listener!)
  (reset! @#'rf.testbed.story-host/hash-listener* nil)
  (reset! @#'rf.testbed.story-host/root-view* nil)
  (reset! @#'rf.testbed.story-host/app-root nil))

(use-fixtures :each
  {:before (fn []
             (reset-host-handles!)
             (reset! shell-root* nil))
   :after  (fn []
             ;; Unmount held roots directly; the Story redefinitions are no
             ;; longer active during fixture teardown.
             (when-let [r @@#'rf.testbed.story-host/app-root]
               (try (rdc/unmount r) (catch :default _ nil)))
             (when-let [r @shell-root*]
               (try (rdc/unmount r) (catch :default _ nil)))
             (reset-host-handles!)
             (reset! shell-root* nil)
             (remove-app-node!))})

(defn- live-view [] [:div {:id "live-marker"} "LIVE"])

(defn- set-hash! [h]
  (set! (.. js/window -location -hash) h))

(defn- marker-text [id]
  (when-let [el (js/document.getElementById id)]
    (.-textContent el)))

(deftest live-stories-live-handoff-no-listener-or-root-leak
  (testing "a live -> stories -> live cycle and re-run release each React root
            before the next surface claims #app"
    (if-not (browser?)
      (is true ":node-test: no DOM — :browser-test runner exercises the assertion")
      (let [app-node (ensure-app-node!)
            warnings
            (with-captured-console
              (fn []
                (with-redefs [rf.story/mount-shell!   shell-mount!
                              rf.story/unmount-shell! shell-unmount!]
                  ;; Start with the live app.
                  (set-hash! "#/")
                  (react-dom/flushSync
                   (fn [] (rf.testbed.story-host/mount-with-hash-routing! live-view)))
                  ;; Hand the same node to the shell.
                  (set-hash! "#/stories")
                  (react-dom/flushSync (fn [] (#'rf.testbed.story-host/on-hash-change!)))
                  ;; Hand the node back to the live app.
                  (set-hash! "#/")
                  (react-dom/flushSync (fn [] (#'rf.testbed.story-host/on-hash-change!)))
                  ;; Re-run the host as hot reload would.
                  (react-dom/flushSync
                   (fn [] (rf.testbed.story-host/mount-with-hash-routing! live-view)))
                  ;; Repeat the handoff after the re-run.
                  (set-hash! "#/stories")
                  (react-dom/flushSync (fn [] (#'rf.testbed.story-host/on-hash-change!)))
                  (set-hash! "#/")
                  (react-dom/flushSync (fn [] (#'rf.testbed.story-host/on-hash-change!))))))]
        ;; The host retains the currently installed listener handle.
        (is (some? @@#'rf.testbed.story-host/hash-listener*)
            "the single installed hashchange handle is recorded")
        (is (= "LIVE" (marker-text "live-marker"))
            "after the final #/ the live view owns #app")
        (is (nil? (marker-text "shell-marker"))
            "the Story-shell marker is gone — its root was torn down on handoff")
        (is (nil? @shell-root*)
            "the shell root handle was released (no leaked shell root)")
        (is (= app-node (js/document.getElementById "app"))
            "the same #app node was reused throughout (one node, one owner)")
        (let [bad (filterv handoff-warning? warnings)]
          (is (empty? bad)
              (str "no createRoot-reuse / handoff warning across the full "
                   "#/ <-> #/stories cycle and re-run; saw: " (pr-str bad))))))))

(deftest hot-reload-rerun-does-not-stack-listener-on-real-node
  (testing "a re-run with a fresh listener identity advances the stored handle
            on a real DOM node"
    (if-not (browser?)
      (is true ":node-test: no DOM — :browser-test runner exercises the assertion")
      (do
        (ensure-app-node!)
        (with-redefs [rf.story/mount-shell!   shell-mount!
                      rf.story/unmount-shell! shell-unmount!]
          (set-hash! "#/")
          (react-dom/flushSync
           (fn [] (rf.testbed.story-host/mount-with-hash-routing! live-view)))
          (let [handle-1 @@#'rf.testbed.story-host/hash-listener*]
            (is (some? handle-1) "first run records a listener handle")
            ;; A new function identity simulates a recompile.
            (with-redefs [rf.testbed.story-host/on-hash-change! (fn [] nil)]
              (react-dom/flushSync
               (fn [] (rf.testbed.story-host/mount-with-hash-routing! live-view))))
            (let [handle-2 @@#'rf.testbed.story-host/hash-listener*]
              (is (some? handle-2) "re-run records a (new) listener handle")
              (is (not (identical? handle-1 handle-2))
                  "the stored handle advanced to the recompiled listener fn — the
                   prior one was removed, not stacked"))))))))

;; ---------------------------------------------------------------------------
;; Listener census — the probe that makes the teardown above load-bearing.
;;
;; The two tests above inspect the STORED HANDLE, which stays green whether or
;; not the listener was actually unregistered from the page. `js/window`
;; publishes no listener registry, so counting the calls is the only way to
;; prove a mount/teardown cycle nets to zero.
;; ---------------------------------------------------------------------------

(defn- with-hashchange-census
  "Calls `(f net)` with `js/window`'s listener API wrapped to count
  \"hashchange\" registrations into the `net` atom (add ⇒ inc, remove ⇒ dec),
  and returns the net still registered when `f` returns.

  The wrappers are own properties on `js/window`; `addEventListener` /
  `removeEventListener` are inherited from `EventTarget.prototype`, so
  `js-delete` restores the page exactly."
  [f]
  (let [net      (atom 0)
        orig-add (.-addEventListener js/window)
        orig-rem (.-removeEventListener js/window)]
    (try
      (set! (.-addEventListener js/window)
            (fn [type listener opts]
              (when (= type "hashchange") (swap! net inc))
              (.call orig-add js/window type listener opts)))
      (set! (.-removeEventListener js/window)
            (fn [type listener opts]
              (when (= type "hashchange") (swap! net dec))
              (.call orig-rem js/window type listener opts)))
      (f net)
      @net
      (finally
        (js-delete js/window "addEventListener")
        (js-delete js/window "removeEventListener")))))

(deftest fixture-teardown-leaves-no-hashchange-listener-on-the-page
  (testing "a mount + fixture teardown cycle nets ZERO registered hashchange
            listeners — the recorded handle is unregistered before it is
            discarded, so the next test's mount cannot stack a second one"
    (if-not (browser?)
      (is true ":node-test: no DOM — :browser-test runner exercises the assertion")
      (do
        (ensure-app-node!)
        (let [net (with-hashchange-census
                    (fn [net]
                      (with-redefs [rf.story/mount-shell!   shell-mount!
                                    rf.story/unmount-shell! shell-unmount!]
                        (set-hash! "#/")
                        (react-dom/flushSync
                         (fn [] (rf.testbed.story-host/mount-with-hash-routing! live-view))))
                      ;; Non-vacuity: the census saw the real registration.
                      (is (= 1 @net)
                          "the mount registered exactly one hashchange listener")
                      (is (some? @@#'rf.testbed.story-host/hash-listener*)
                          "and the host recorded its handle")
                      ;; The fixture teardown, run explicitly so its balance is
                      ;; observable inside the census window.
                      (reset-host-handles!)))]
          (is (zero? net)
              (str "every hashchange listener the host registered was removed "
               "from js/window before its handle was discarded; net still "
               "registered: " net)))))))
