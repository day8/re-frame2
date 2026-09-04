(ns re-frame.testbed.story-host-cljs-test
  "Node tests for Story-host routing.

  A fake window records listener identity while mount functions are stubbed.
  Rebinding `on-hash-change!` simulates CLJS hot reload and verifies that the
  previous listener is removed rather than stacked.

  The host used to carry a second job — resolving an open-in-editor source
  root from a build-seeded checkout path and writing it into Story's config.
  That path is retired: the dev server's `POST /__rf-open-in-editor` endpoint
  resolves classpath-relative coordinates at request time, so the host owns
  only the React-root handoff and hash routing. The surviving Story-config
  assertion below is the KEPT carve-out — the public
  `:rf.story/project-root` option remains the consumer's to set, and the host
  must not disturb it."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.story.config :as rf.story.config]
            [re-frame.testbed.story-host :as rf.testbed.story-host]))

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
  (reset! @#'rf.testbed.story-host/hash-listener* nil)
  (reset! @#'rf.testbed.story-host/root-view* nil))

(use-fixtures :each
  {:before (fn []
             (reset-host-handles!)
             ;; The carve-out test below writes Story's global config.
             (rf.story.config/set-project-root! nil))
   :after  (fn []
             ;; Restore the Node baseline for subsequent namespaces.
             (clear-window!)
             (reset-host-handles!)
             (rf.story.config/set-project-root! nil))})

;; Mount functions are stubbed, so this view is never rendered.
(defn- dummy-view [] [:div "dummy"])

(deftest single-run-installs-exactly-one-listener
  (testing "one `mount-with-hash-routing!` call installs exactly one active
            hashchange listener, and stashes that exact handle in
            `hash-listener*`"
    (let [{:keys [window hashchange-count]} (make-fake-window "#/")
          switches (atom 0)]
      (install-window! window)
      (with-redefs [rf.testbed.story-host/mount-app!     (fn [] (swap! switches inc))
                    rf.testbed.story-host/mount-stories! (fn [] (swap! switches inc))]
        (rf.testbed.story-host/mount-with-hash-routing! dummy-view))
      (is (= 1 (hashchange-count))
          "exactly one hashchange listener active after a single run")
      (is (some? @@#'rf.testbed.story-host/hash-listener*)
          "the installed handle is recorded for later removal")
      (is (= 1 @switches)
          "the initial `on-hash-change!` ran the mount switch exactly once"))))

(deftest re-run-with-changed-handler-identity-does-not-stack
  (testing "a re-run with a fresh handler identity replaces the prior listener"
    (let [{:keys [window hashchange-count]} (make-fake-window "#/")]
      (install-window! window)
      (with-redefs [rf.testbed.story-host/mount-app!     (constantly nil)
                    rf.testbed.story-host/mount-stories! (constantly nil)]
        ;; First install the current function identity.
        (rf.testbed.story-host/mount-with-hash-routing! dummy-view)
        (is (= 1 (hashchange-count)) "one listener after the first run")
        (let [handle-1 @@#'rf.testbed.story-host/hash-listener*]
          ;; A fresh function identity simulates a recompile.
          (with-redefs [rf.testbed.story-host/on-hash-change! (fn [] nil)]
            (rf.testbed.story-host/mount-with-hash-routing! dummy-view))
          (is (= 1 (hashchange-count))
              "STILL exactly one listener after the post-reload re-run — the
               prior listener was removed, not stacked")
          (let [handle-2 @@#'rf.testbed.story-host/hash-listener*]
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
      (with-redefs [rf.testbed.story-host/mount-app!     (fn [] (swap! switches inc))
                    rf.testbed.story-host/mount-stories! (fn [] (swap! switches inc))]
        (rf.testbed.story-host/mount-with-hash-routing! dummy-view))
      ;; Each subsequent run uses a new handler identity.
      (dotimes [_ 5]
        (with-redefs [rf.testbed.story-host/on-hash-change! (fn [] (swap! switches inc))]
          (rf.testbed.story-host/mount-with-hash-routing! dummy-view)))
      (is (= 1 (hashchange-count))
          "six runs total → still exactly one active listener (no leak)")
      ;; A browser invokes every registered listener for one hash change.
      (reset! switches 0)
      (.forEach registry (fn [listener] (listener)))
      (is (= 1 @switches)
          "one hash change runs the mount switch exactly once — proving a
           single active listener, not an N-deep stack"))))

;; ---- the KEPT carve-out ---------------------------------------------------
;;
;; `:rf.story/project-root` survives the checkout-root retirement as a public
;; option external and non-shadow hosts still need for the client's
;; `editor://` URI fallback. What went away is the HOST writing it on the
;; consumer's behalf, so the property to pin is the negative one: mounting
;; neither sets a root nor clears one the consumer set.

(deftest mount-does-not-write-story-project-root
  (testing "mounting with no Story config leaves the project-root slot unset —
            the retired `:source-subdir` path was the only thing that wrote it"
    (let [{:keys [window]} (make-fake-window "#/")]
      (install-window! window)
      (is (nil? (rf.story.config/get-project-root))
          "fixture baseline: the slot starts unset")
      (with-redefs [rf.testbed.story-host/mount-app!     (constantly nil)
                    rf.testbed.story-host/mount-stories! (constantly nil)]
        (rf.testbed.story-host/mount-with-hash-routing! dummy-view))
      (is (nil? (rf.story.config/get-project-root))
          "mounting configured no root — source-file resolution is the
           dev-server endpoint's job, not the host's"))))

(deftest mount-leaves-a-consumer-set-project-root-untouched
  (testing "a consumer that DOES set `:rf.story/project-root` (an external or
            non-shadow host leaning on the URI fallback) keeps it across a
            mount — the carve-out is genuinely reachable, not just undeleted"
    (let [{:keys [window]} (make-fake-window "#/")]
      (install-window! window)
      (rf.story.config/set-project-root! "/preset/by/consumer")
      (with-redefs [rf.testbed.story-host/mount-app!     (constantly nil)
                    rf.testbed.story-host/mount-stories! (constantly nil)]
        (rf.testbed.story-host/mount-with-hash-routing! dummy-view))
      (is (= "/preset/by/consumer" (rf.story.config/get-project-root))
          "the consumer-set root survived the mount untouched"))))
