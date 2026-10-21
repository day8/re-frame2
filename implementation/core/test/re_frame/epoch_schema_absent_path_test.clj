(ns re-frame.epoch-schema-absent-path-test
  "Restore and the pair tool's `replace-frame-state!` check a candidate app-db
  through `failing-schema-paths`, which answers as a commit does (Spec 010
  §When schemas are checked): a registered path the candidate lacks is not
  checked, and a present value, `nil` included, is."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.epoch :as rf.epoch]
            [re-frame.epoch.tool-pair :as rf.epoch.tool-pair]
            [re-frame.schemas]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(deftest failing-schema-paths-skips-an-absent-path-and-flags-a-present-nil
  (rf/make-frame {:id ::f})
  (rf/reg-app-schema [:n] :int)
  (rf/reg-app-schema [:user :name] :string)
  (let [failing #(set (rf.epoch.tool-pair/failing-schema-paths ::f %))]
    (is (= #{} (failing {})) "both paths absent")
    (is (= #{} (failing {:user {}})) "a path absent beneath a present parent")
    (is (= #{[:n] [:user :name]} (failing {:n nil :user {:name nil}}))
        "a present nil is checked")
    (is (= #{[:n]} (failing {:n "x" :user {:name "a"}})))))

(deftest ^:requires-debug replace-frame-state-admits-an-app-db-lacking-a-registered-path
  (rf/make-frame {:id ::f})
  (rf/reg-app-schema [:n] :int)
  (is (:ok? (rf.epoch/replace-frame-state! ::f {:rf.db/app {:other 1}})))
  (is (= {:other 1} (rf/app-db-value ::f)))
  (is (false? (:ok? (rf.epoch/replace-frame-state! ::f {:rf.db/app {:n nil}})))
      "a present nil is refused")
  (is (= {:other 1} (rf/app-db-value ::f))))
