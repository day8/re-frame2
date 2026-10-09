(ns re-frame.dry-run-effect-sink-cljs-test
  "Binding `re-frame.fx/*effect-sink*` makes a `dispatch-sync` RECORD every
  source-ordered `[fx-id args]` and run NO fx body. Interception sits at the
  single effect executor (`do-fx`), before any per-fx resolution, so it covers
  fx that an enumeration of the `:fx` registrar would miss: image-only inline
  fx and the reserved fx whose real body core always runs. Each test carries a
  positive control showing the body does run without the sink."
  (:require #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
            [re-frame.core                 :as rf]
            [re-frame.fx                   :as rf.fx]
            [re-frame.image                :as rf.image]
            [re-frame.late-bind            :as rf.late-bind]
            [re-frame.live-frame           :as rf.live-frame]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support         :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter       rf.substrate.plain-atom/adapter
                                            :ambient-frame nil}))

(deftest dry-run-does-not-execute-image-only-inline-fx
  (rf/make-frame {:id :inline/main})
  (let [fired (atom [])
        ;; the fx exists only in the frame's image, never in the :fx registrar
        img   (rf.image/image
                {:id :inline/fx
                 :registrations
                 {:reg-event [[:do/it {}
                               (fn [{:keys [db]} _]
                                 {:db (assoc db :ran true)
                                  :fx [[:img/side-effect {:n 7}]]})]]
                  :reg-fx    [[:img/side-effect {}
                               (fn [_ctx args] (swap! fired conj args))]]}})]
    (rf.live-frame/make-frame {:id :inline/main :images [img]} [])
    (let [sink (atom [])]
      (binding [rf.fx/*effect-sink* sink]
        (rf/dispatch-sync [:do/it] {:frame :inline/main}))
      (is (= [] @fired))
      (is (= [[:img/side-effect {:n 7}]] @sink)))
    (rf/dispatch-sync [:do/it] {:frame :inline/main})
    (is (= [{:n 7}] @fired) "positive control: without the sink the inline fx fires")))

(deftest dry-run-skips-and-records-reserved-flow-fx
  ;; `:rf.fx/reg-flow` / `:rf.fx/clear-flow` are reject-tier reserved fx: core
  ;; runs their real body (the `:flows/*` late-bind hook) even over an override.
  (rf/make-frame {:id :rf/frame})
  (let [hits      (atom [])
        old-reg   (rf.late-bind/get-fn :flows/reg-flow)
        old-clear (rf.late-bind/get-fn :flows/clear-flow)]
    (try
      (rf.late-bind/set-fn! :flows/reg-flow   (fn [& _] (swap! hits conj :reg-flow)))
      (rf.late-bind/set-fn! :flows/clear-flow (fn [& _] (swap! hits conj :clear-flow)))
      (rf/reg-event :rf/go
        (fn [{:keys [db]} _]
          {:db (assoc db :ran true)
           :fx [[:rf.fx/reg-flow  [:my/flow {} (fn [_] 1)]]
                [:rf.fx/clear-flow :my/flow]]}))
      (let [sink (atom [])]
        (binding [rf.fx/*effect-sink* sink]
          (rf/dispatch-sync [:rf/go] {:frame :rf/frame}))
        (is (= [] @hits))
        (is (= [:rf.fx/reg-flow :rf.fx/clear-flow] (mapv first @sink))))
      (rf/dispatch-sync [:rf/go] {:frame :rf/frame})
      (is (= [:reg-flow :clear-flow] @hits)
          "positive control: without the sink the reserved flow bodies run")
      (finally
        (rf.late-bind/set-fn! :flows/reg-flow   old-reg)
        (rf.late-bind/set-fn! :flows/clear-flow old-clear)))))

(deftest dry-run-records-complete-source-ordered-and-nothing-escapes
  ;; The tentative :db still commits; rolling it back is the caller's job.
  (rf/make-frame {:id :ord/frame})
  (let [escaped   (atom :untouched)
        child-ran (atom false)]
    (rf/reg-fx :ext/http (fn [_ _] (reset! escaped :ESCAPED)))
    (rf/reg-event :child (fn [{:keys [db]} _] (reset! child-ran true) {:db db}))
    (rf/reg-event :ord/go
      (fn [{:keys [db]} _]
        {:db (assoc db :n 1)
         :fx [[:ext/http {:url "/a"}]
              [:dispatch  [:child]]
              [:ext/http {:url "/b"}]]}))
    (let [sink (atom [])]
      (binding [rf.fx/*effect-sink* sink]
        (rf/dispatch-sync [:ord/go] {:frame :ord/frame}))
      (is (= :untouched @escaped))
      (is (false? @child-ran) "the :dispatch fx was recorded, not executed")
      (is (= [[:ext/http {:url "/a"}]
              [:dispatch  [:child]]
              [:ext/http {:url "/b"}]]
             @sink)))))
