(ns re-frame.reprojection-install-race-jvm-test
  "A partially-installed reprojection must never be observable as installed.
  `re-frame.live-frame/ensure-reprojection-installed!` performs three side
  effects — the registrar registration hook and the
  `:live-frame/mark-projection-dirty!` / `:live-frame/flush-projection!`
  late-bind keys — and publishes its once-flag last, inside a JVM monitor. A
  concurrent `make-frame` therefore either performs the install or blocks until
  it is complete. One that could pass early would seal a generation with the
  wiring missing and stay permanently stale, reporting `:rf.error/no-such-handler`
  for a handler `rf.registrar/lookup` holds.

  The test parks the installer at the LAST side effect with a `with-redefs`
  barrier, so any publication of the flag before the install completes lets the
  contender through. The contender is blocked on a monitor the parked installer
  holds, so no timeout value can make the negative assertion fail falsely.

  CLJS is single-threaded, so no second caller can observe the once-body
  mid-flight; there is no counterpart there.

  The fixture rewinds the process-wide once-state — the flag, the reprojection
  registration hook and the two late-bind keys — so the test opens the window a
  fresh JVM's first `make-frame` opens, and restores it afterwards."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.flows :as rf.flows]
            [re-frame.frame :as rf.frame]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.live-frame :as rf.live-frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.schemas :as rf.schemas]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.trace.tooling :as rf.trace.tooling])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

(def ^:private installed-flag     #'rf.live-frame/reprojection-installed?)
(def ^:private registration-hooks #'rf.registrar/registration-hooks)

(defn- reprojection-hook-installed? []
  (boolean (some #{rf.live-frame/reproject-on-registration-change!}
                 @@registration-hooks)))

(def ^:private published-keys
  [:live-frame/mark-projection-dirty! :live-frame/flush-projection!])

(defn- reset-runtime [test-fn]
  (let [hooks-before     @@registration-hooks
        late-bind-before @rf.late-bind/hooks
        flag-before      @@installed-flag]
    (try
      (rf.registrar/clear-all!)
      (reset! rf.frame/frames {})
      (rf.flows/reset-flows!)
      (rf.schemas/clear-schemas-by-frame!)
      (rf.trace.tooling/clear-listeners!)
      (rf/init! rf.substrate.plain-atom/adapter)
      (require 're-frame.routing :reload)
      (require 're-frame.ssr :reload)
      ;; Rewind AFTER the reloads, which re-add their own registration hooks:
      ;; drop only the reprojection hook and the two keys, then clear the flag.
      (swap! @registration-hooks
             (fn [hs] (vec (remove #{rf.live-frame/reproject-on-registration-change!} hs))))
      (swap! rf.late-bind/hooks #(apply dissoc % published-keys))
      (run! rf.late-bind/invalidate-cache! published-keys)
      (reset! @installed-flag false)
      (test-fn)
      (finally
        (reset! @registration-hooks hooks-before)
        (reset! rf.late-bind/hooks late-bind-before)
        (run! rf.late-bind/invalidate-cache! published-keys)
        (reset! @installed-flag flag-before)
        (rf.registrar/clear-all!)
        (reset! rf.frame/frames {})))))

(use-fixtures :each reset-runtime)

;; Bounds a wait that must fire; the serialized install returns promptly.
(def ^:private ^:const settle-ms 10000)

;; Bounds a wait that must NOT fire. The blocked contender cannot return at any
;; value; an early-publishing install would return at once.
(def ^:private ^:const window-ms 2000)

(defn- await! [^CountDownLatch latch ^long ms]
  (.await latch ms TimeUnit/MILLISECONDS))

(deftest install-boundary-not-passable-before-the-flush-late-bind
  (testing "a second make-frame cannot observe the reprojection as installed
            while the elected installer is still publishing the late-bind keys"
    (let [set-fn!    rf.late-bind/set-fn!
          at-barrier (CountDownLatch. 1)
          release    (CountDownLatch. 1)
          b-started  (CountDownLatch. 1)
          b-returned (CountDownLatch. 1)
          b-saw      (atom nil)]
      (with-redefs [rf.late-bind/set-fn!
                    (fn [k f]
                      ;; park before the last of the three side effects
                      (when (= k :live-frame/flush-projection!)
                        (.countDown at-barrier)
                        (await! release settle-ms))
                      (set-fn! k f))]
        (let [a (future (rf/make-frame {:id :audit.flush/a}))
              _ (is (await! at-barrier settle-ms)
                    "the elected installer parked before the last publication")
              b (future
                  (.countDown b-started)
                  (rf/make-frame {:id :audit.flush/b})
                  (reset! b-saw {:hook?  (reprojection-hook-installed?)
                                 :mark?  (some? (rf.late-bind/get-fn :live-frame/mark-projection-dirty!))
                                 :flush? (some? (rf.late-bind/get-fn :live-frame/flush-projection!))})
                  (.countDown b-returned)
                  :done)]
          (is (await! b-started settle-ms) "the contender thread started")
          (is (not (await! b-returned window-ms))
              (str "a concurrent make-frame passed the install boundary with the "
                   ":live-frame/flush-projection! consult still unpublished"))
          (.countDown release)
          (is (= [:done true {:hook? true :mark? true :flush? true}]
                 [(deref b settle-ms ::timeout)
                  (some? (deref a settle-ms ::timeout))
                  @b-saw])
              "both threads finished, and the contender observed the full wiring"))))))
