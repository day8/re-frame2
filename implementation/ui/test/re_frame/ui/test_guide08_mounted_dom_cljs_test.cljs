(ns re-frame.ui.test-guide08-mounted-dom-cljs-test
  "Mounted behavioral fixture for Guide 08's caller-owned-frame recipe.

  This is intentionally a guide-local helper, not a general Promise utility.
  Real `with-root` runs prove root/container ownership composes with the frame
  owner across synchronous construction failures and every Promise settlement."
  (:require [cljs.test :refer [async deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.test-support :as test-support]
            [re-frame.ui :as ui :refer [defview]]
            [re-frame.ui.test :as uit]))

(defn- browser? [] (exists? js/document))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter ui/adapter
                                            :ambient-frame nil
                                            :async? true}))

(defview mounted-value []
  [:output {:data-role "mounted-value"} "mounted"])

(defview cached-value []
  [:output {:data-role "cached-value"} (str (ui/sub [::cached-value]))])

(defn- runtime-footprint
  []
  (into {}
        (map (fn [frame-id]
               [frame-id
                (if-let [sub-cache (:sub-cache (frame/frame frame-id))]
                  (set (keys @sub-cache))
                  #{})]))
        (frame/frame-ids)))

(defn- mount-value!
  [owned value]
  (uit/with-root [_root [ui/frame-provider {:frame owned} [mounted-value]]]
    value))

(defn- mount-rejection!
  [owned primary]
  (uit/with-root [_root [ui/frame-provider {:frame owned} [mounted-value]]]
    (throw primary)))

(defn- attach-cleanup-diagnostic!
  [primary cleanup]
  (let [attached?
        (try
          (js/Object.defineProperty
            primary "rfUiTestCleanupError"
            #js {:value cleanup :configurable true})
          true
          (catch :default _ false))]
    (when (and (not attached?) (exists? js/console))
      (.warn js/console
             "frame cleanup could not ride the primitive primary rejection"
             cleanup)))
  primary)

(defn- destroy-frame-after*
  [destroy! owned thunk]
  (letfn [(reject-after-current! [error]
            (-> (js/Promise.resolve nil)
                (.then (fn [] (throw error)))))
          (finish! [settlement value]
            (try
              (destroy! owned)
              (if (= :rejected settlement)
                (reject-after-current! value)
                (js/Promise.resolve value))
              (catch :default cleanup-error
                (if (= :rejected settlement)
                  (reject-after-current!
                    (attach-cleanup-diagnostic! value cleanup-error))
                  (reject-after-current! cleanup-error)))))]
    (try
      (-> (js/Promise.resolve (thunk))
          (.then (fn [value] (finish! :fulfilled value))
                 (fn [error] (finish! :rejected error))))
      (catch :default error
        (finish! :rejected error)))))

(defn- destroy-frame-after!
  [owned thunk]
  (destroy-frame-after* rf/destroy-frame! owned thunk))

(defn- reject-unexpectedly!
  [done label error]
  (is false (str label ": " error))
  (done))

(defn- capture-synchronous-call
  [thunk]
  (try
    {:kind :returned :value (thunk)}
    (catch :default error
      {:kind :thrown :value error})))

(deftest mounted-owner-preserves-truthy-nil-and-false-fulfillments
  (when (browser?)
    (async done
      (let [baseline (runtime-footprint)]
        (-> (reduce
              (fn [chain expected]
                (.then chain
                  (fn []
                    (let [owned       (rf/make-frame {:initial-events [[:rf/set-db {}]]})
                          destroy-runs (atom 0)]
                      (-> (destroy-frame-after*
                            (fn [candidate]
                              (swap! destroy-runs inc)
                              (rf/destroy-frame! candidate))
                            owned
                            #(mount-value! owned expected))
                          (.then
                            (fn [actual]
                              (is (= expected actual)
                                  "mounted fulfillment passes through unchanged")
                              (is (= 1 @destroy-runs)
                                  "the caller-owned frame is destroyed exactly once")
                              (is (= baseline (runtime-footprint))))))))))
              (js/Promise.resolve nil)
              [::truthy nil false])
            (.then (fn [_] (done))
                   (fn [error]
                     (reject-unexpectedly! done "fulfillment fixture rejected" error))))))))

(deftest mounted-owner-preserves-primary-rejection-identity
  (when (browser?)
    (async done
      (let [baseline     (runtime-footprint)
            owned        (rf/make-frame {:initial-events [[:rf/set-db {}]]})
            primary      (ex-info "mounted body rejected" {:kind ::primary})
            destroy-runs (atom 0)]
        (-> (destroy-frame-after*
              (fn [candidate]
                (swap! destroy-runs inc)
                (rf/destroy-frame! candidate))
              owned
              #(mount-rejection! owned primary))
            (.then
              (fn [_]
                (reject-unexpectedly! done "primary rejection resolved" nil))
              (fn [error]
                (is (identical? primary error))
                (is (= 1 @destroy-runs))
                (is (= baseline (runtime-footprint)))
                (done))))))))

(deftest mounted-owner-surfaces-cleanup-only-failure
  (when (browser?)
    (async done
      (let [baseline     (runtime-footprint)
            owned        (rf/make-frame {:initial-events [[:rf/set-db {}]]})
            real-destroy rf/destroy-frame!
            cleanup      (js/Error. "frame cleanup failed")
            destroy-runs (atom 0)]
        (-> (destroy-frame-after*
              (fn [_]
                (swap! destroy-runs inc)
                (throw cleanup))
              owned
              #(mount-value! owned ::body-value))
            (.then
              (fn [_]
                (real-destroy owned)
                (reject-unexpectedly! done "cleanup-only failure resolved" nil))
              (fn [error]
                (is (identical? cleanup error)
                    "the only cleanup failure is the rejection")
                (is (= 1 @destroy-runs))
                (real-destroy owned)
                (is (= baseline (runtime-footprint)))
                (done))))))))

(deftest mounted-owner-keeps-primary-when-cleanup-also-fails
  (when (browser?)
    (async done
      (let [baseline     (runtime-footprint)
            owned        (rf/make-frame {:initial-events [[:rf/set-db {}]]})
            real-destroy rf/destroy-frame!
            primary      (js/Error. "mounted primary")
            cleanup      (js/Error. "frame cleanup")
            destroy-runs (atom 0)]
        (-> (destroy-frame-after*
              (fn [_]
                (swap! destroy-runs inc)
                (throw cleanup))
              owned
              #(mount-rejection! owned primary))
            (.then
              (fn [_]
                (real-destroy owned)
                (reject-unexpectedly! done "primary + cleanup resolved" nil))
              (fn [error]
                (is (identical? primary error)
                    "cleanup never replaces the primary rejection")
                (is (identical? cleanup
                                (unchecked-get error "rfUiTestCleanupError"))
                    "cleanup rides the primary under the repository convention")
                (is (= 1 @destroy-runs))
                (real-destroy owned)
                (is (= baseline (runtime-footprint)))
                (done))))))))

(deftest synchronous-thunk-throw-is-owned
  (when (browser?)
    (async done
      (let [baseline     (runtime-footprint)
            owned        (rf/make-frame {:initial-events [[:rf/set-db {}]]})
            real-destroy rf/destroy-frame!
            primary      (ex-info "construction failed" {:kind ::sync-primary})
            destroy-runs (atom 0)
            call         (capture-synchronous-call
                           #(destroy-frame-after*
                              (fn [candidate]
                                (swap! destroy-runs inc)
                                (real-destroy candidate))
                              owned
                              (fn [] (throw primary))))
            outcome      (:value call)]
        (is (= :returned (:kind call))
            "the owner helper does not throw synchronously")
        (is (instance? js/Promise outcome)
            "a synchronous construction throw is normalized to a rejection")
        (if (instance? js/Promise outcome)
          (-> outcome
              (.then
                (fn [_]
                  (reject-unexpectedly! done "sync primary resolved" nil))
                (fn [error]
                  (is (identical? primary error))
                  (is (= 1 @destroy-runs))
                  (is (= baseline (runtime-footprint)))
                  (done))))
          (do
            (is (= 1 @destroy-runs)
                "even the synchronous path destroys exactly once")
            (real-destroy owned)
            (is (= baseline (runtime-footprint)))
            (done)))))))

(deftest forgotten-await-overlap-before-with-root-promise-is-owned
  (when (browser?)
    (async done
      (let [baseline     (runtime-footprint)
            release      (volatile! nil)
            first-act    (uit/flush!
                           (fn []
                             (js/Promise.
                               (fn [resolve _reject]
                                 (vreset! release resolve)))))
            owned        (rf/make-frame {:initial-events [[:rf/set-db {}]]})
            real-destroy rf/destroy-frame!
            destroy-runs (atom 0)
            call         (capture-synchronous-call
                           #(destroy-frame-after*
                              (fn [candidate]
                                (swap! destroy-runs inc)
                                (real-destroy candidate))
                              owned
                              (fn []
                                (uit/with-root
                                  [_root [ui/frame-provider {:frame owned}
                                          [mounted-value]]]
                                  nil))))
            outcome      (:value call)
            promise?     (instance? js/Promise outcome)]
        (is (= :returned (:kind call))
            "the overlap is normalized instead of escaping synchronously")
        (is promise?
            "with-root's synchronous overlap throw is returned as a rejection")
        (when-not promise?
          (real-destroy owned))
        (@release nil)
        (-> (js/Promise.all
              #js [first-act
                   (if promise?
                     (.then outcome
                       (fn [_] ::unexpected-resolution)
                       (fn [error] error))
                     (js/Promise.resolve outcome))])
            (.then
              (fn [results]
                (let [error (aget results 1)]
                  (is (= :rf.error/ui-test-overlapping-act
                         (:rf.error/id (ex-data error))))
                  (is (= 1 @destroy-runs)
                      "the pre-Promise overlap still destroys exactly once")
                  (is (= baseline (runtime-footprint)))
                  (done)))
              (fn [error]
                (reject-unexpectedly! done "overlap fixture rejected" error))))))))

(deftest mounted-sub-cache-and-live-frame-return-to-baseline
  (when (browser?)
    (async done
      (rf/reg-sub ::cached-value (fn [db _] (:value db)))
      (let [baseline (runtime-footprint)
            owned    (rf/make-frame
                       {:initial-events [[:rf/set-db {:value 42}]]})]
        (-> (destroy-frame-after!
              owned
              #(uit/with-root
                 [root [ui/frame-provider {:frame owned} [cached-value]]]
                 (is (= "42"
                        (.-textContent
                          (.querySelector root "[data-role='cached-value']"))))
                 (is (some (fn [cache-keys]
                             (contains? cache-keys [::cached-value]))
                           (vals (runtime-footprint)))
                     "the mounted view materializes a real per-frame sub-cache entry")
                 ::mounted))
            (.then
              (fn [value]
                (is (= ::mounted value))
                (is (= baseline (runtime-footprint))
                    "settlement restores the live-frame/sub-cache baseline")
                (done))
              (fn [error]
                (reject-unexpectedly! done "baseline fixture rejected" error))))))))
