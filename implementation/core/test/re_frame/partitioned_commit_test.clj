(ns re-frame.partitioned-commit-test
  "The partitioned commit (Spec 006 §Commit boundary). A frame holds ONE
  physical frame-state container; app-db and runtime-db are read-only
  projections over it. An ordinary `:db` effect replaces only app-db, a
  framework `:rf.db/runtime` effect only runtime-db, and a cascade touching
  both installs them as one transition. Change derives from projection
  equality: `:rf.event/db-changed` reports app-db changes only and
  `:rf.event/frame-state-changed` names the touched partitions. An app-db
  schema rejection discards the whole candidate, both partitions, before
  install, while out-of-band classification writes made during the rejected
  event stand."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.elision :as rf.elision]
            ;; Publishes the `:flows/*` late-bind hooks; `reg-flow` installs its
            ;; `:source :flow` elision marks at registration.
            [re-frame.flows]
            [re-frame.frame :as rf.frame]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.registrar :as rf.registrar]
            ;; Publishes `:schemas/validate-app-schema!`, which the rejection
            ;; tests need.
            [re-frame.schemas]
            [re-frame.substrate.adapter :as rf.substrate.adapter]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.trace.tooling :as rf.trace.tooling]
            ;; `replace-frame-state!` delegates to the epoch artefact's
            ;; late-bind hooks.
            [re-frame.epoch]))

(defn reset-runtime [test-fn]
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (when-let [clear-schemas! (rf.late-bind/get-fn :schemas/clear-by-frame!)]
    (clear-schemas!))
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

(deftest one-physical-container-two-projections
  (rf/make-frame {:id :pc/shape})
  (is (= [{:rf.db/app {} :rf.db/runtime {}} {} {}]
         (mapv rf.substrate.adapter/read-container
               [(rf.frame/frame-state-container :pc/shape)
                (rf.frame/app-db-container :pc/shape)
                (rf.frame/runtime-db-container :pc/shape)]))
      "the container holds both partitions; each projection derefs its slice"))

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
  ;; The exact runtime-db value also pins that a frame which never used
  ;; elision gains no `:rf.runtime/elision` sub-tree on a runtime commit.
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

(deftest change-traces-name-the-touched-partitions
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

(deftest replace-frame-state-replaces-the-whole-frame-state
  ;; A full map replaces BOTH partitions wholesale: no key of the old
  ;; runtime-db survives.
  (rf/make-frame {:id :pc/m-full})
  (rf/replace-frame-state! :pc/m-full {:rf.db/app     {:a :old}
                                       :rf.db/runtime {:rf.runtime/machines {:m :old}}})
  (rf/replace-frame-state! :pc/m-full {:rf.db/app     {:a :new}
                                       :rf.db/runtime {:rf.runtime/routing {:r :new}}})
  (is (= {:rf.db/app {:a :new} :rf.db/runtime {:rf.runtime/routing {:r :new}}}
         (rf/frame-state-value :pc/m-full))))

(deftest schema-rejection-discards-both-partitions
  (rf/make-frame {:id :pc/rb})
  (rf/replace-frame-state! :pc/rb {:rf.db/app     {:n 0}
                                   :rf.db/runtime {:rf.runtime/machines {:m :pre}}})
  (rf/with-frame :pc/rb
    (rf/reg-app-schema [] [:map [:n [:int {:min 0}]]]))
  (reg-fw-runtime-handler! :pc/bad
    (fn [_ _]
      {:db            {:n -5}
       :rf.db/runtime {:rf.runtime/machines {:m :post}}}))
  (rf/dispatch-sync [:pc/bad] {:frame :pc/rb})
  (is (= {:rf.db/app {:n 0} :rf.db/runtime {:rf.runtime/machines {:m :pre}}}
         (rf/frame-state-value :pc/rb))
      "both partitions keep their pre-handler values"))

(deftest schema-rejection-never-installs-in-band-effect-keeps-flow-mark
  ;; The in-band `:sensitive` effect rides the rejected candidate, so it never
  ;; installs; the `:source :flow` mark predates the event and stands.
  (rf/make-frame {:id :pc/rb-srcaware})
  (rf/replace-frame-state! :pc/rb-srcaware {:rf.db/app {:n 0} :rf.db/runtime {}})
  (rf/reg-flow :creds {:frame       :pc/rb-srcaware
                       :inputs      [[:n]]
                       :output-path [:derived :creds]
                       :sensitive   [[:secret]]}
    (fn [n] {:secret n}))
  (rf/with-frame :pc/rb-srcaware
    (rf/reg-app-schema [] [:map [:n [:int {:min 0}]]]))
  (rf/reg-event :pc/bad-with-effect
    (fn [_ _] {:db {:n -5} :sensitive [[:another-secret]]}))
  (rf/dispatch-sync [:pc/bad-with-effect] {:frame :pc/rb-srcaware})
  (is (= {:n 0} (rf/app-db-value :pc/rb-srcaware)))
  (is (= {[:derived :creds :secret] #{{:source :flow :flow-id :creds}}}
         (rf.elision/sensitive-declarations :pc/rb-srcaware))))

(deftest schema-rejection-keeps-subsystem-mark-lowered-during-event
  ;; A subsystem lowering a declaration during the event writes the LIVE
  ;; registry, not the discarded candidate, so the mark stands.
  (rf/make-frame {:id :pc/rb-subsys})
  (rf/replace-frame-state! :pc/rb-subsys {:rf.db/app {:n 0} :rf.db/runtime {}})
  (rf/with-frame :pc/rb-subsys
    (rf/reg-app-schema [] [:map [:n [:int {:min 0}]]]))
  (rf/reg-interceptor :pc/subsystem-mark-writer
    {:after (fn [ctx]
              (rf.elision/swap-elision-slot! :pc/rb-subsys
                (fn [reg]
                  (rf.elision/add-claims (or reg {}) :sensitive-declarations
                                         {:source :machine :machine-id :door}
                                         [[:actor :token]])))
              ctx)})
  (rf/reg-event :pc/bad-subsys
    {:interceptors [:pc/subsystem-mark-writer]}
    (fn [_ _] {:db {:n -5}}))
  (rf/dispatch-sync [:pc/bad-subsys] {:frame :pc/rb-subsys})
  (is (= {:n 0} (rf/app-db-value :pc/rb-subsys)))
  (is (= {[:actor :token] #{{:source :machine :machine-id :door}}}
         (rf.elision/sensitive-declarations :pc/rb-subsys))))

(deftest schema-rejection-keeps-flow-move-reconcile
  ;; A reentrant `reg-flow` output-path move inside a rejected event is a
  ;; durable re-registration: the old-path claim stays dropped and the
  ;; new-path claim stands.
  (rf/make-frame {:id :pc/rb-move})
  (rf/replace-frame-state! :pc/rb-move {:rf.db/app {:n 0} :rf.db/runtime {}})
  (rf/reg-flow :mover {:frame       :pc/rb-move
                       :inputs      [[:n]]
                       :output-path [:old :creds]
                       :sensitive   [[:secret]]}
    (fn [n] {:secret n}))
  (is (= {[:old :creds :secret] #{{:source :flow :flow-id :mover}}}
         (rf.elision/sensitive-declarations :pc/rb-move))
      "precondition: the old-path mark is installed")
  (rf/with-frame :pc/rb-move
    (rf/reg-app-schema [] [:map [:n [:int {:min 0}]]]))
  (rf/reg-interceptor :pc/flow-mover
    {:after (fn [ctx]
              (rf/reg-flow :mover {:frame       :pc/rb-move
                                   :inputs      [[:n]]
                                   :output-path [:new :creds]
                                   :sensitive   [[:secret]]}
                (fn [n] {:secret n}))
              ctx)})
  (rf/reg-event :pc/bad-move
    {:interceptors [:pc/flow-mover]}
    (fn [_ _] {:db {:n -5}}))
  (rf/dispatch-sync [:pc/bad-move] {:frame :pc/rb-move})
  (is (= {:n 0} (rf/app-db-value :pc/rb-move)))
  (is (= {[:new :creds :secret] #{{:source :flow :flow-id :mover}}}
         (rf.elision/sensitive-declarations :pc/rb-move))))

(deftest schema-rejection-preserves-full-app-db-under-path-interceptor
  ;; A path interceptor focuses `[:coeffects :db]` on its slice, so a
  ;; rejection that restored from there would write the slice as the whole
  ;; app-db. `:keep` is the canary outside the path.
  (rf/make-frame {:id :pc/rb-path})
  (rf/replace-frame-state! :pc/rb-path
                           {:rf.db/app     {:keep :must-survive :slice {:n 0}}
                            :rf.db/runtime {:rf.runtime/machines {:m :pre}}})
  (rf/with-frame :pc/rb-path
    (rf/reg-app-schema [] [:map
                           [:keep  :keyword]
                           [:slice [:map [:n [:int {:min 0}]]]]]))
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
