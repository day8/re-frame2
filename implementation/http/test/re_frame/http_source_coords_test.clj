(ns re-frame.http-source-coords-test
  "`rf/reg-http-interceptor` is a `defreg-macro` form, so the call site's
  source coords (`:ns` / `:line` / `:column` / `:file`) land on the stored
  interceptor slot beside the user's registration metadata, per Spec 001
  §Source-coordinate capture and the `:rf/http-interceptor-meta` schema
  (`[:merge RegistrationMetadata ...]`). The other reg-* surfaces have the
  same coverage in `core/test/re_frame/source_coords_test.clj`."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.http.managed :as rf.http.managed]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

;; reg-http-interceptor needs a frame context; the fixture's default ambient
;; frame is :rf/default.
(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- slot-for
  "The stored slot for `id` on `frame-id`."
  [frame-id id]
  (->> (rf.http.managed/interceptors-snapshot frame-id)
       (filter #(= id (:id %)))
       first))

(deftest reg-http-interceptor-stamps-auto-captured-source-coords-rf2-may3f
  (testing "the call site's :ns / :line / :column / :file land on the stored
            slot beside the user's :doc / :tags / :sensitive?"
    (rf/reg-http-interceptor :rf2-may3f/auth
      {:doc        "auth header attacher"
       :tags       #{:auth :security}
       :sensitive? true
       :before     identity})
    (let [slot (slot-for :rf/default :rf2-may3f/auth)]
      (is (= {:ns         're-frame.http-source-coords-test
              :doc        "auth header attacher"
              :tags       #{:auth :security}
              :sensitive? true}
             (select-keys slot [:ns :doc :tags :sensitive?])))
      (is (every? pos-int? [(:line slot) (:column slot)]))
      (is (string? (:file slot))))))

(deftest reg-http-interceptor-user-coord-keys-override-rf2-may3f
  (testing "explicit :ns / :line / :column / :file win over the auto-captured
            values (Spec 001: user keys win over framework auto-capture)"
    (rf/reg-http-interceptor :rf2-may3f/forwarded
      {:ns     'app.wrappers.http-interceptor-builder
       :line   42
       :column 7
       :file   "src/app/wrappers/http.clj"
       :before identity})
    (is (= {:ns     'app.wrappers.http-interceptor-builder
            :line   42
            :column 7
            :file   "src/app/wrappers/http.clj"}
           (select-keys (slot-for :rf/default :rf2-may3f/forwarded) [:ns :line :column :file])))))
