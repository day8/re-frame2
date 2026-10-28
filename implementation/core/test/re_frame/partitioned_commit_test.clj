(ns re-frame.partitioned-commit-test
  "The partitioned commit (Spec 006 §Commit boundary). A frame holds ONE
  physical frame-state container; app-db and runtime-db are read-only
  projections over it. An ordinary `:db` effect replaces only app-db, a
  framework `:rf.db/runtime` effect only runtime-db, and a cascade touching
  both installs them as one transition. Change derives from projection
  equality: `:rf.event/db-changed` reports app-db changes only and
  `:rf.event/frame-state-changed` names the touched partitions. An app-db
  schema rejection discards the whole candidate, both partitions, before
  install.

  The deftests about schema rejection, `replace-frame-state!` and the change
  traces carry `^:requires-debug`: app-schema validation, `replace-frame-state!`
  and the trace stream are all dev-only."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.elision :as rf.elision]
            ;; Publishes the `:flows/*` late-bind hooks, among them the one
            ;; that classifies a flow's output (`:source :flow`).
            [re-frame.flows]
            [re-frame.frame :as rf.frame]
            [re-frame.registrar :as rf.registrar]
            ;; Publishes `:schemas/validate-app-schema!`, which the rejection
            ;; tests need.
            [re-frame.schemas]
            [re-frame.substrate.adapter :as rf.substrate.adapter]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.trace.tooling :as rf.trace.tooling]
            ;; `replace-frame-state!` is epoch's tool write.
            [re-frame.epoch :as rf.epoch]))

(defn reset-runtime [test-fn]
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (rf.trace.tooling/clear-listeners!)
  (rf/init! rf.substrate.plain-atom/adapter)
  (rf/make-frame {:id :rf/default})
  (rf/with-frame :rf/default
    (test-fn)))

(use-fixtures :each reset-runtime)

(defn- record-traces! [listener-id]
  (let [a (atom [])]
    (rf/register-listener! :trace listener-id (fn [ev] (swap! a conj ev)))
    a))

(defn- events-of [recorded operation]
  (filterv #(= operation (:operation %)) @recorded))

;; The `:rf/machine? true` marker a machine registration carries lets a
;; handler emit `:rf.db/runtime` without the dev runtime-write diagnostic.
(defn- reg-fw-runtime-handler! [id f]
  (rf/reg-event id {:doc "framework-authority" :rf/machine? true} f))

(deftest projections-are-read-only
  (rf/make-frame {:id :pc/ro})
  (is (= [:rf.error/derived-container-replaced :rf.error/derived-container-replaced]
         (for [container [(rf.frame/app-db-container :pc/ro)
                          (rf.frame/runtime-db-container :pc/ro)]]
           (try (rf.substrate.adapter/replace-container! container {:x 1})
                nil
                (catch clojure.lang.ExceptionInfo e
                  (:rf.error/id (ex-data e))))))))

(deftest ordinary-db-effect-scoped-to-app-db
  (rf/make-frame {:id :pc/scope :doc "scope"})
  (reg-fw-runtime-handler! :pc/seed-rt
    (fn [_ _] {:rf.db/runtime {:rf.runtime/machines {:door {:state :open}}}}))
  (rf/dispatch-sync [:pc/seed-rt] {:frame :pc/scope})
  (is (= {:rf.runtime/machines {:door {:state :open}}}
         (:rf.db/runtime (rf/frame-state-value :pc/scope))))
  ;; A fresh-map :db return, the classic footgun shape, must not drop runtime-db.
  (rf/reg-event :pc/fresh (fn [_ _] {:db {:session :anonymous}}))
  (rf/dispatch-sync [:pc/fresh] {:frame :pc/scope})
  (is (= {:session :anonymous} (rf/app-db-value :pc/scope))
      "app-db replaced wholesale")
  (is (= {:rf.runtime/machines {:door {:state :open}}}
         (:rf.db/runtime (rf/frame-state-value :pc/scope)))
      "runtime-db survives a fresh-map :db return"))

(deftest runtime-db-effect-whole-value-commit
  (rf/make-frame {:id :pc/rtfx})
  (rf/reg-event :pc/seed-app (fn [_ _] {:db {:app :data}}))
  (rf/dispatch-sync [:pc/seed-app] {:frame :pc/rtfx})
  (reg-fw-runtime-handler! :pc/write-rt
    (fn [_ _] {:rf.db/runtime {:rf.runtime/routing {:current {:route-id :home}}}}))
  (rf/dispatch-sync [:pc/write-rt] {:frame :pc/rtfx})
  (is (= {:rf.db/app     {:app :data}
          :rf.db/runtime {:rf.runtime/routing {:current {:route-id :home}}}}
         (rf/frame-state-value :pc/rtfx))
      "the runtime effect installed runtime-db and left app-db untouched"))

(deftest atomic-cross-partition-commit
  (rf/make-frame {:id :pc/both})
  (reg-fw-runtime-handler! :pc/app-and-rt
    (fn [{:keys [db]} _]
      {:db            (assoc db :page :account)
       :rf.db/runtime {:rf.runtime/routing {:current {:route-id :account}}}}))
  (rf/dispatch-sync [:pc/app-and-rt] {:frame :pc/both})
  (is (= {:rf.db/app     {:page :account}
          :rf.db/runtime {:rf.runtime/routing {:current {:route-id :account}}}}
         (rf.substrate.adapter/read-container (rf.frame/frame-state-container :pc/both)))
      "both partitions land in the single physical frame-state value"))

(deftest runtime-only-commit-does-not-invalidate-app-subs
  (rf/reg-event :pc/seed (fn [_ _] {:db {:n 1}}))
  (rf/dispatch-sync [:pc/seed])
  (let [runs (atom 0)]
    (rf/reg-sub :pc/app-sub (fn [db _] (swap! runs inc) (:n db)))
    (is (= 1 (rf/with-frame :rf/default @(rf/subscribe [:pc/app-sub]))))
    (let [after-prime @runs]
      (reg-fw-runtime-handler! :pc/touch-rt
        (fn [_ _] {:rf.db/runtime {:rf.runtime/machines {:m 1}}}))
      (rf/dispatch-sync [:pc/touch-rt])
      (is (= 1 (rf/with-frame :rf/default @(rf/subscribe [:pc/app-sub]))))
      (is (= after-prime @runs)
          "the app sub did NOT recompute — the app-db projection stayed `=` on a runtime-only commit")
      (is (= {:rf.runtime/machines {:m 1}} (:rf.db/runtime (rf/frame-state-value :rf/default)))
          "the runtime-only commit DID land in runtime-db"))))

(deftest app-only-commit-does-not-invalidate-runtime-projection
  (rf/make-frame {:id :pc/inval-rt :doc "inval-rt"})
  (reg-fw-runtime-handler! :pc/seed-rt2
    (fn [_ _] {:rf.db/runtime {:rf.runtime/routing {:current {:route-id :home}}}}))
  (rf/dispatch-sync [:pc/seed-rt2] {:frame :pc/inval-rt})
  (let [rt-before (:rf.db/runtime (rf/frame-state-value :pc/inval-rt))]
    (rf/reg-event :pc/app-write (fn [{:keys [db]} _] {:db (assoc db :touched? true)}))
    (rf/dispatch-sync [:pc/app-write] {:frame :pc/inval-rt})
    (is (true? (:touched? (rf/app-db-value :pc/inval-rt))))
    (is (identical? rt-before (:rf.db/runtime (rf/frame-state-value :pc/inval-rt)))
        "runtime-db is reference-identical across an app-only commit — no spurious runtime change")))

(deftest ^:requires-debug change-traces-name-the-touched-partitions
  ;; [db-changed count, the :rf.event/partitions of each frame-state-changed]
  (doseq [[id framework? handler expected]
          [[:pc/tr-app  false (fn [{:keys [db]} _] {:db (assoc db :k 1)})
            [1 [#{:app-db}]]]
           [:pc/tr-rt   true  (fn [_ _] {:rf.db/runtime {:rf.runtime/machines {:m 1}}})
            [0 [#{:runtime-db}]]]
           [:pc/tr-both true  (fn [{:keys [db]} _]
                                {:db            (assoc db :k 1)
                                 :rf.db/runtime {:rf.runtime/routing {:current {:route-id :x}}}})
            [1 [#{:app-db :runtime-db}]]]
           [:pc/tr-noop false (fn [{:keys [db]} _] {:db db})
            [0 []]]]]
    (rf/make-frame {:id id})
    (if framework?
      (reg-fw-runtime-handler! id handler)
      (rf/reg-event id handler))
    (let [recorded (record-traces! id)]
      (rf/dispatch-sync [id] {:frame id})
      (is (= expected
             [(count (events-of recorded :rf.event/db-changed))
              (mapv (comp :rf.event/partitions :tags)
                    (events-of recorded :rf.event/frame-state-changed))])
          (str id)))))

(deftest ^:requires-debug replace-frame-state-replaces-the-whole-frame-state
  ;; A full map replaces BOTH partitions wholesale: no key of the old
  ;; runtime-db survives.
  (rf/make-frame {:id :pc/m-full})
  (rf.epoch/replace-frame-state! :pc/m-full {:rf.db/app     {:a :old}
                                       :rf.db/runtime {:rf.runtime/machines {:m :old}}})
  (rf.epoch/replace-frame-state! :pc/m-full {:rf.db/app     {:a :new}
                                       :rf.db/runtime {:rf.runtime/routing {:r :new}}})
  (is (= {:rf.db/app {:a :new} :rf.db/runtime {:rf.runtime/routing {:r :new}}}
         (rf/frame-state-value :pc/m-full))))

(deftest ^:requires-debug schema-rejection-discards-both-partitions
  (rf/make-frame {:id :pc/rb})
  (rf.epoch/replace-frame-state! :pc/rb {:rf.db/app     {:n 0}
                                   :rf.db/runtime {:rf.runtime/machines {:m :pre}}})
  (rf/reg-app-schema [] [:map [:n [:int {:min 0}]]])
  (reg-fw-runtime-handler! :pc/bad
    (fn [_ _]
      {:db            {:n -5}
       :rf.db/runtime {:rf.runtime/machines {:m :post}}}))
  (rf/dispatch-sync [:pc/bad] {:frame :pc/rb})
  (is (= {:rf.db/app {:n 0} :rf.db/runtime {:rf.runtime/machines {:m :pre}}}
         (rf/frame-state-value :pc/rb))
      "both partitions keep their pre-handler values"))

(deftest ^:requires-debug schema-rejection-keeps-flow-claims-on-the-committed-output
  ;; A re-registration that moves a flow's output path reaches the frame on its
  ;; next event, whose pass vacates the old path. The frame's classification
  ;; covers both the active definition's path and the one its memo row still
  ;; names, so when that event is rejected the old output stays committed and
  ;; claimed; the next committed event publishes the new row, and the old
  ;; path's claim goes with the output it covered.
  (let [mover (fn [path]
                (rf/reg-flow :mover {:inputs      [[:n]]
                                     :output-path path
                                     :sensitive   [[:secret]]}
                  (fn [n] {:secret n})))
        claims #(rf.elision/sensitive-declarations :pc/rb-move)]
    (mover [:old :creds])
    (rf/make-frame {:id :pc/rb-move :flows [:mover]})
    (rf.epoch/replace-frame-state! :pc/rb-move {:rf.db/app {:n 0} :rf.db/runtime {}})
    (rf/reg-app-schema [] [:map [:n [:int {:min 0}]]])
    (rf/reg-event :pc/ok (fn [{:keys [db]} _] {:db db}))
    (rf/reg-event :pc/bad (fn [_ _] {:db {:n -5}}))
    (rf/dispatch-sync [:pc/ok] {:frame :pc/rb-move})
    (is (= {[:old :creds :secret] #{{:source :flow :flow-id :mover}}} (claims))
        "precondition: the old-path claim is installed")
    (mover [:new :creds])
    (rf/dispatch-sync [:pc/bad] {:frame :pc/rb-move})
    (is (= {:n 0 :old {:creds {:secret 0}}} (rf/app-db-value :pc/rb-move)))
    (is (= {:source :flow :flow-id :mover}
           (first (get (claims) [:old :creds :secret])))
        "the committed old output is still claimed")
    (rf/dispatch-sync [:pc/ok] {:frame :pc/rb-move})
    (is (= [{:n 0 :old {} :new {:creds {:secret 0}}}
            {[:new :creds :secret] #{{:source :flow :flow-id :mover}}}]
           [(rf/app-db-value :pc/rb-move) (claims)]))))

(deftest ^:requires-debug schema-rejection-preserves-full-app-db-under-path-interceptor
  ;; A path interceptor focuses `[:coeffects :db]` on its slice, so a
  ;; rejection that restored from there would write the slice as the whole
  ;; app-db. `:keep` is the canary outside the path.
  (rf/make-frame {:id :pc/rb-path})
  (rf.epoch/replace-frame-state! :pc/rb-path
                           {:rf.db/app     {:keep :must-survive :slice {:n 0}}
                            :rf.db/runtime {:rf.runtime/machines {:m :pre}}})
  (rf/reg-app-schema [] [:map
                         [:keep  :keyword]
                         [:slice [:map [:n [:int {:min 0}]]]]])
  (rf/reg-event :pc/path-bad
    {:interceptors [[:rf.interceptor/path [:slice]]]}
    (fn [{:keys [db]} _]
      (is (= {:n 0} db) "the handler sees only the [:slice] sub-db")
      {:db {:n -5}}))
  (rf/dispatch-sync [:pc/path-bad] {:frame :pc/rb-path})
  (is (= {:rf.db/app     {:keep :must-survive :slice {:n 0}}
          :rf.db/runtime {:rf.runtime/machines {:m :pre}}}
         (rf/frame-state-value :pc/rb-path))
      "the full prior app-db and runtime-db survive the rejection"))
