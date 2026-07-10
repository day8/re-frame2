(ns re-frame.testbed.story-host-cljs-test
  "Node tests for Story-host routing and source-root configuration.

  A fake window records listener identity while mount functions are stubbed.
  Rebinding `on-hash-change!` simulates CLJS hot reload and verifies that the
  previous listener is removed rather than stacked."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [re-frame.story.config :as story-config]
            [re-frame.testbed.config :as testbed-config]
            [re-frame.testbed.story-host :as host]))

;; A JS Set gives the fake window browser-like listener identity semantics.

(defn- make-fake-window
  "Build a fake window with an observable hashchange listener registry."
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

;; js/window resolves through Closure's global object under the Node target;
;; assigning a bare window name would violate strict mode.
(def ^:private real-window (when (exists? js/window) js/window))

(defn- install-window! [w]
  (set! (.-window js/goog.global) w))

(defn- clear-window! []
  (if real-window
    (set! (.-window js/goog.global) real-window)
    (js-delete js/goog.global "window")))

(defn- reset-host-handles!
  "Reset host state that intentionally survives reloads."
  []
  (reset! @#'host/hash-listener* nil)
  (reset! @#'host/root-view* nil))

(use-fixtures :each
  {:before (fn []
             (reset-host-handles!)
             ;; Source-root tests mutate Story's global config.
             (story-config/set-project-root! nil))
   :after  (fn []
             ;; Restore the Node baseline for subsequent namespaces.
             (clear-window!)
             (reset-host-handles!)
             (story-config/set-project-root! nil))})

;; Mount functions are stubbed, so this view is never rendered.
(defn- dummy-view [] [:div "dummy"])

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
  (testing "a re-run with a fresh handler identity replaces the prior listener"
    (let [{:keys [window hashchange-count]} (make-fake-window "#/")]
      (install-window! window)
      (with-redefs [host/mount-app!     (constantly nil)
                    host/mount-stories! (constantly nil)]
        ;; First install the current function identity.
        (host/mount-with-hash-routing! dummy-view)
        (is (= 1 (hashchange-count)) "one listener after the first run")
        (let [handle-1 @@#'host/hash-listener*]
          ;; A fresh function identity simulates a recompile.
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
      ;; First run installs the current handler.
      (with-redefs [host/mount-app!     (fn [] (swap! switches inc))
                    host/mount-stories! (fn [] (swap! switches inc))]
        (host/mount-with-hash-routing! dummy-view))
      ;; Each subsequent run uses a new handler identity.
      (dotimes [_ 5]
        (with-redefs [host/on-hash-change! (fn [] (swap! switches inc))]
          (host/mount-with-hash-routing! dummy-view)))
      (is (= 1 (hashchange-count))
          "six runs total → still exactly one active listener (no leak)")
      ;; A browser invokes every registered listener for one hash change.
      (reset! switches 0)
      (.forEach registry (fn [listener] (listener)))
      (is (= 1 @switches)
          "one hash change runs the mount switch exactly once — proving a
           single active listener, not an N-deep stack"))))

;; Source-subdir options configure Story without requiring a DOM.

(deftest source-subdir-configures-absolute-project-root
  (testing "a source subdir configures an absolute Story project root"
    (with-redefs [testbed-config/checkout-root "/home/dev/re-frame2"]
      (#'host/configure-story-source-root! "examples/core")
      (is (= "/home/dev/re-frame2/examples/core"
             (story-config/get-project-root))
          "Story's project-root is the absolute checkout/subdir join")
      ;; Compose a representative classpath-relative coordinate.
      (let [composed (str (story-config/get-project-root) "/" "login/stories.cljs")]
        (is (= "/home/dev/re-frame2/examples/core/login/stories.cljs"
               composed)
            "the composed editor path reaches the real example source file")
        (is (str/includes? composed "/examples/core/")
            "the example source-root segment is present (not missing)")))))

(deftest example-story-builds-resolve-absolute-source-coords
  (testing "example Story builds resolve coordinates beneath their source subdir"
    (with-redefs [testbed-config/checkout-root "/home/dev/re-frame2"]
      (doseq [[build coord subdir expected]
              [[:examples/login-with-stories
                "login/stories.cljs"
                "examples/core"
                "/home/dev/re-frame2/examples/core/login/stories.cljs"]
               [:examples/nine-states-with-stories
                "nine_states/stories.cljs"
                "examples/patterns"
                "/home/dev/re-frame2/examples/patterns/nine_states/stories.cljs"]]]
        (story-config/set-project-root! nil)
        (#'host/configure-story-source-root! subdir)
        (let [root     (story-config/get-project-root)
              composed (str root "/" coord)]
          (is (= (str "/home/dev/re-frame2/" subdir) root)
              (str build " configures the absolute " subdir " root"))
          (is (= expected composed)
              (str build " Story coord composes to the real on-disk file"))
          (is (str/includes? composed (str "/" subdir "/"))
              (str build " keeps the example source-root segment")))))))

(deftest source-subdir-omitted-configures-no-root
  (testing "nil and blank source subdirs do not change Story config"
    (with-redefs [testbed-config/checkout-root "/home/dev/re-frame2"]
      ;; nil subdir skips configuration
      (#'host/configure-story-source-root! nil)
      (is (nil? (story-config/get-project-root))
          "nil subdir configures nothing")
      ;; blank subdir also skips configuration
      (#'host/configure-story-source-root! "   ")
      (is (nil? (story-config/get-project-root))
          "blank subdir configures nothing"))))

(deftest source-subdir-with-no-resolvable-root-degrades-to-no-op
  (testing "a declared subdir with no checkout root configures no project root"
    (with-redefs [testbed-config/checkout-root ""]
      (#'host/configure-story-source-root! "examples/reagent")
      (is (nil? (story-config/get-project-root))
          "no resolvable root → Story's root stays nil (no broken prefix)"))))

(deftest mount-with-hash-routing-arity-2-configures-project-root
  (testing "the public two-arity entry configures the declared source subdir"
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
  (testing "the one-arity entry leaves consumer-owned Story config untouched"
    (let [{:keys [window]} (make-fake-window "#/")]
      (install-window! window)
      (story-config/set-project-root! "/preset/by/consumer")
      (with-redefs [host/mount-app!     (constantly nil)
                    host/mount-stories! (constantly nil)]
        (host/mount-with-hash-routing! dummy-view))
      (is (= "/preset/by/consumer" (story-config/get-project-root))
          "1-arity call left the consumer-set root untouched"))))
