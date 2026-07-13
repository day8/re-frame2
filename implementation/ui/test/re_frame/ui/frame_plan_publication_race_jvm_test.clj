(ns re-frame.ui.frame-plan-publication-race-jvm-test
  "JVM lifecycle race coverage for frame-plan record publication.

  `destroy-frame!` marks an incarnation CLOSING before it runs the UI teardown
  hook, but does not flip the frame's `:destroyed?` bit until after that hook.
  The hook prunes the installed-plan record. Publication in the post-prune /
  pre-liveness-flip window must therefore reject the closing incarnation, or it
  can write a dead-lifetime record after the only prune has already happened.

  A wrapped real UI hook opens that window with a CountDownLatch handoff; there
  are no sleeps or scheduler guesses. CLJS cannot interleave a second publisher
  while synchronous destruction is paused, so this fixture is JVM-only."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core                 :as rf]
            [re-frame.frame                :as frame]
            [re-frame.late-bind            :as late-bind]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support         :as test-support]
            [re-frame.ui.frames            :as frames])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter
                                            :ambient-frame nil})
  (fn [test-fn]
    (frames/reset-installed-plans!)
    (try
      (test-fn)
      (finally
        (frames/reset-installed-plans!)))))

(defn- plan [frame-id fingerprint]
  {:frame-id frame-id :config {} :config-fingerprint fingerprint})

(defn- err-id [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo e
      (:rf.error/id (ex-data e)))))

(deftest closing-incarnation-cannot-republish-after-the-destroy-prune
  (let [fid          :plan-race/frame
        _            (rf/make-frame {:id fid :doc "incarnation A"})
        token-a      (frame/frame-incarnation-token fid)
        pruned       (CountDownLatch. 1)
        release      (CountDownLatch. 1)
        orig-ui-hook (late-bind/get-fn :ui/on-frame-destroyed!)
        destroy*     (atom nil)]
    (try
      ;; Run the REAL UI teardown first (including installed-plan pruning), then
      ;; pause before destroy-frame! advances to its liveness flip.
      (late-bind/set-fn!
       :ui/on-frame-destroyed!
       (fn [frame-id]
         (orig-ui-hook frame-id)
         (when (= fid frame-id)
           (.countDown pruned)
           (.await release 10 TimeUnit/SECONDS))))

      (reset! destroy* (future (frame/destroy-frame! fid)))
      (is (.await pruned 10 TimeUnit/SECONDS)
          "destroy reached the deterministic post-UI-prune barrier")
      (is (some? (frame/frame fid))
          "precondition: liveness has not flipped yet")
      (is (true? (frame/frame-incarnation-closing? fid token-a))
          "precondition: incarnation A is already marked closing")
      (is (nil? (frames/installed-plan-entry fid))
          "precondition: the real UI hook already pruned plan authority")

      ;; This config-less plan would ordinarily adopt the live boot frame. A
      ;; closing incarnation is not publishable even though frame/frame and its
      ;; incarnation token remain visible until the destroy's next step.
      (frames/execute-frame-plans!
       :root/a [(plan fid "cf1-closing-aaaaaaaa")])
      (is (nil? (frames/installed-plan-entry fid))
          "publication rejects the closing incarnation after the prune")

      (.countDown release)
      (is (nil? (deref @destroy* 10000 ::timeout))
          "incarnation A finishes destruction")
      (is (nil? (frame/frame fid)) "incarnation A is absent")

      ;; A boot replacement makes any stale record observable again. Root B's
      ;; different fingerprint must adopt that new lifetime, not conflict with
      ;; the record root A attempted to publish after A's prune.
      (rf/make-frame {:id fid :doc "incarnation B"})
      (is (nil? (frames/installed-plan-entry fid))
          "incarnation B inherits no dead-lifetime plan record")
      (is (nil? (err-id
                 #(frames/execute-frame-plans!
                   :root/b [(plan fid "cf1-replacement-bbbbb")])))
          "root B adopts incarnation B without a stale payload conflict")
      (is (= :root/b (:adopted-by (frames/installed-plan-entry fid)))
          "incarnation B records only its real adopter")
      (finally
        ;; Never strand the destroy future if an assertion or setup step fails.
        (.countDown release)
        (when-let [destroy @destroy*]
          (deref destroy 10000 ::timeout))
        (late-bind/set-fn! :ui/on-frame-destroyed! orig-ui-hook)))))
