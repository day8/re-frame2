(ns re-frame.dry-run-effect-sink-cljs-test
  "Binding `re-frame.fx/*effect-sink*` makes a `dispatch-sync` RECORD every
  source-ordered `[fx-id args]` and run NO fx body. Interception sits at the
  single effect executor (`do-fx`), before any per-fx resolution, so it covers
  fx that an enumeration of the `:fx` registrar would miss: image-only inline
  fx and the reserved fx whose real body core always runs. The image-only and
  reserved-fx tests each carry a positive control showing the body does run
  without the sink."
  (:require #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
            [re-frame.core                 :as rf]
            [re-frame.fx                   :as rf.fx]
            [re-frame.image                :as rf.image]
            [re-frame.live-frame           :as rf.live-frame]
            ;; Loaded for `:rf.machine/spawn`, the reject-tier reserved fx.
            [re-frame.machines]
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

(deftest dry-run-skips-and-records-reject-tier-reserved-fx
  ;; `:rf.machine/spawn` is a reject-tier reserved fx: core runs its real body
  ;; even over an override.
  (rf/make-frame {:id :rf/frame})
  (rf/reg-machine :dry/m {:initial :idle :states {:idle {}}})
  (rf/reg-event :rf/go
    (fn [{:keys [db]} _]
      {:db (assoc db :ran true)
       :fx [[:rf.machine/spawn {:machine-id :dry/m :fixed-actor-id :dry/a}]]}))
  (let [spawned? #(some? (get-in (:rf.db/runtime (rf/frame-state-value :rf/frame))
                                 [:rf.runtime/machines :snapshots :dry/a]))
        sink     (atom [])]
    (binding [rf.fx/*effect-sink* sink]
      (rf/dispatch-sync [:rf/go] {:frame :rf/frame}))
    (is (not (spawned?)))
    (is (= [:rf.machine/spawn] (mapv first @sink)))
    (rf/dispatch-sync [:rf/go] {:frame :rf/frame})
    (is (spawned?) "positive control: without the sink the reserved body runs")))

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
