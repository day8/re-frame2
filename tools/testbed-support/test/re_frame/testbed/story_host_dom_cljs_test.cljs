(ns re-frame.testbed.story-host-dom-cljs-test
  "Browser tests for the Story-host React-root handoff.

  The Node suite covers listener identity with mount stubs. This suite uses a
  real DOM node and minimal real React roots to verify that each surface
  unmounts before the other claims `#app`. Its bodies no-op under Node."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [reagent.dom.client :as rdc]
            ["react-dom" :as react-dom]
            [re-frame.story :as story]
            [re-frame.testbed.story-host :as host]))

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

(defn- reset-host-handles! []
  (reset! @#'host/hash-listener* nil)
  (reset! @#'host/root-view* nil)
  (reset! @#'host/app-root nil))

(use-fixtures :each
  {:before (fn []
             (reset-host-handles!)
             (reset! shell-root* nil))
   :after  (fn []
             ;; Unmount held roots directly; the Story redefinitions are no
             ;; longer active during fixture teardown.
             (when-let [r @@#'host/app-root]
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
                (with-redefs [story/mount-shell!   shell-mount!
                              story/unmount-shell! shell-unmount!]
                  ;; Start with the live app.
                  (set-hash! "#/")
                  (react-dom/flushSync
                   (fn [] (host/mount-with-hash-routing! live-view)))
                  ;; Hand the same node to the shell.
                  (set-hash! "#/stories")
                  (react-dom/flushSync (fn [] (#'host/on-hash-change!)))
                  ;; Hand the node back to the live app.
                  (set-hash! "#/")
                  (react-dom/flushSync (fn [] (#'host/on-hash-change!)))
                  ;; Re-run the host as hot reload would.
                  (react-dom/flushSync
                   (fn [] (host/mount-with-hash-routing! live-view)))
                  ;; Repeat the handoff after the re-run.
                  (set-hash! "#/stories")
                  (react-dom/flushSync (fn [] (#'host/on-hash-change!)))
                  (set-hash! "#/")
                  (react-dom/flushSync (fn [] (#'host/on-hash-change!))))))]
        ;; The host retains the currently installed listener handle.
        (is (some? @@#'host/hash-listener*)
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
        (with-redefs [story/mount-shell!   shell-mount!
                      story/unmount-shell! shell-unmount!]
          (set-hash! "#/")
          (react-dom/flushSync
           (fn [] (host/mount-with-hash-routing! live-view)))
          (let [handle-1 @@#'host/hash-listener*]
            (is (some? handle-1) "first run records a listener handle")
            ;; A new function identity simulates a recompile.
            (with-redefs [host/on-hash-change! (fn [] nil)]
              (react-dom/flushSync
               (fn [] (host/mount-with-hash-routing! live-view))))
            (let [handle-2 @@#'host/hash-listener*]
              (is (some? handle-2) "re-run records a (new) listener handle")
              (is (not (identical? handle-1 handle-2))
                  "the stored handle advanced to the recompiled listener fn — the
                   prior one was removed, not stacked"))))))))
