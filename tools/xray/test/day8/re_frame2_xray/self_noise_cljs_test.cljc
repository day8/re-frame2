(ns day8.re-frame2-xray.self-noise-cljs-test
  "Tests for the Xray self-noise filter: the ingest predicate
  `xray-internal-event?`, the event-id predicate, and the shared
  `filtered-event-bundles` projection. The CLJS-only row drives
  `collect-trace!` end-to-end."
  (:require #?(:clj  [clojure.test :refer [are deftest is testing]]
               :cljs [cljs.test    :refer-macros [are deftest is testing use-fixtures]])
            [day8.re-frame2-xray.self-noise :as self-noise]
            #?(:cljs [day8.re-frame2-xray.trace-collector :as trace-collector])))

#?(:cljs
   (use-fixtures :each
     (fn [test-fn]
       (trace-collector/reset-for-test!)
       (test-fn)
       (trace-collector/reset-for-test!))))

(deftest xray-internal-event?-ignores-stray-top-level-frame
  ;; A raw event carries its frame ONLY under `[:tags :frame]`.
  (testing "top-level-only :frame is ignored"
    (is (false? (self-noise/xray-internal-event? {:frame :rf/xray}))))
  (testing "[:tags :frame] is authoritative"
    (is (true? (self-noise/xray-internal-event?
                 {:frame :rf/default :tags {:frame :rf/xray}})))))

#?(:cljs
   (deftest collect-trace-drops-xray-view-renders
     (trace-collector/collect-trace!
       {:operation :rf.view/render :op-type :rf.view
        :id 3 :time 1002
        :tags  {:rf.view/render-key 42 :frame :rf/xray}})
     (is (empty? (trace-collector/frameless-events)))))

(deftest xray-internal-event-id?-keyword-namespace
  ;; The match is a namespace SEGMENT prefix: exactly `rf.xray` or
  ;; `rf.xray.*`, because frameless sub-namespaced internal events (the
  ;; palette's `:rf.xray.static/select-tab`) land on the host frame.
  (are [event-id expected] (= expected (self-noise/xray-internal-event-id? event-id))
    :rf.xray/focus-event       true
    :rf.xray.static/select-tab true
    :rf.xray-test/x            false
    :unnamespaced              false
    nil                        false))

(defn- dispatched-event [id dispatch-id event-v frame]
  {:id id :op-type :rf.event :operation :rf.event/dispatched
   :tags {:rf.trace/dispatch-id dispatch-id :rf.event/v event-v :frame frame}})

(deftest filtered-event-bundles-strips-xray-internal-keeps-host
  (let [buffer [(dispatched-event 1 100 [:counter/inc]          :below)
                (dispatched-event 2 101 [:rf.xray/select-tab :a] :rf/default)]]
    (is (= [[:counter/inc]] (mapv :event (self-noise/filtered-event-bundles buffer))))))
