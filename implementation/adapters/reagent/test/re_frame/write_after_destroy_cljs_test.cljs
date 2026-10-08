(ns re-frame.write-after-destroy-cljs-test
  "Every app-db write goes through `re-frame.substrate.adapter/replace-container!`,
  so a scheduled drain that races frame destruction no-ops there and fires
  the always-on `:rf.error/write-after-destroy` with `:recovery :ignored`
  rather than throwing. The core artefact pins this against the plain-atom
  JVM adapter (`re-frame.frame-lifecycle-test`); this re-pins the
  destroyed-frame shape router.cljc's `:db` commit takes, under the Reagent
  adapter. The bare `replace-container! nil` call is
  `re-frame.write-after-destroy-always-on-cljs-test`'s."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.substrate.adapter :as rf.substrate.adapter]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.test-support :as rf.test-support])
  (:require-macros [re-frame.test-support :refer [with-trace-recorder!]]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent/adapter}))

(def ^:private write-after-destroy-pred
  (fn [ev]
    (and (= :error (:op-type ev))
         (= :rf.error/write-after-destroy (:operation ev)))))

;; ---- live-destroy → captured-container-write -------------------------------

(deftest reagent-replace-container-on-destroyed-frame-does-not-npe
  (testing "frame/app-db-container on a destroyed frame returns nil; feeding
            that nil into replace-container! must no-op + warn.
            This is the exact shape router.cljc's per-event :db commit
            takes when a scheduled drain reaches the write AFTER destroy."
    (let [frame-id :rf-9od6t/race]
      (with-trace-recorder! [errs {:pred write-after-destroy-pred}]
        (rf/make-frame {:id frame-id :doc "destroy-race reproducer"})
        (rf/destroy-frame! frame-id)
        (let [container (rf.frame/app-db-container frame-id)]
          (is (= [nil nil]
                 [container (rf.substrate.adapter/replace-container! container {:would :have :npe'd true})])
              "a destroyed frame's container is nil, and writing through it is a documented no-op"))
        (is (and (seq @errs) (every? #(= :ignored (:recovery %)) @errs))
            (str ":rf.error/write-after-destroy fired for the write, carrying :recovery :ignored; got "
                 (pr-str @errs)))))))
