(ns re-frame.ssr.payload-policy-cljs-test
  "The fail-closed hydration-payload policy (`:payload` is an allowlist of
  top-level keys or the whole-app-db opt-in), the runtime-db projection and the
  payload assembly. Platform-neutral, so it runs on the JVM and Node.

  It also runs in the production gate (`-Dre-frame.debug=false`). Only the
  `:rf.ssr/invalid-version` trace assertion is dev-only; the rejection it
  announces is asserted in both postures."
  (:require [clojure.test :refer [deftest is]]
            [re-frame.interop :as rf.interop]
            [re-frame.ssr.payload-policy :as rf.ssr.payload-policy]
            #?(:clj  [re-frame.test-support :refer [with-trace-recorder!]]
               :cljs [re-frame.test-support :refer-macros [with-trace-recorder!]])))

(def sample-app-db
  {:public/articles  [:a :b :c]
   :public/user-id   "u-42"
   :server-only/auth "SECRET_TOKEN"})

(deftest apply-policy-allowlist-slices-app-db
  ;; A listed key absent from app-db is omitted; a list is an allowlist too.
  (doseq [payload [[:public/articles :public/user-id :public/no-such-key]
                   '(:public/articles :public/user-id)]]
    (is (= {:public/articles [:a :b :c] :public/user-id "u-42"}
           (rf.ssr.payload-policy/apply-policy sample-app-db {:payload payload}))
        (pr-str payload))))

(deftest apply-policy-rejects-malformed-allowlists
  ;; Without the per-element check `select-keys` would ship a wrong or empty
  ;; slice silently.
  (doseq [payload [[:public/articles "public/user-id"]
                   '("public/articles")]]
    (is (thrown-with-msg?
          #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo)
          #":rf\.error/ssr-malformed-payload-allowlist"
          (rf.ssr.payload-policy/apply-policy sample-app-db {:payload payload}))
        (pr-str payload))))

(deftest malformed-allowlist-error-names-bad-entries
  (let [data (try (rf.ssr.payload-policy/validate-policy-opts!
                    {:initial-events [[:init]] :payload [:public/articles "user-id" nil]})
                  (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
                    (ex-data e)))]
    (is (= {:rf.error/id  :rf.error/ssr-malformed-payload-allowlist
            :bad-entries  ["user-id" nil]
            :recovery     :declare-payload-policy}
           (select-keys data [:rf.error/id :bad-entries :recovery])))))

(deftest apply-policy-whole-app-db-policy-ships-everything
  (is (= sample-app-db
         (rf.ssr.payload-policy/apply-policy
           sample-app-db {:payload :rf.ssr.payload/whole-app-db}))))

(deftest apply-policy-fails-closed-without-a-usable-policy
  ;; Absent, empty (zero keys is a slip, not intent) and a set (not an ordered
  ;; key selection) all throw rather than ship anything.
  (doseq [opts [{} {:payload []} {:payload #{:public/articles}}]]
    (is (thrown-with-msg?
          #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo)
          #":rf\.error/ssr-missing-payload-policy"
          (rf.ssr.payload-policy/apply-policy sample-app-db opts))
        (pr-str opts))))

(deftest apply-policy-throws-on-unknown-policy-keyword
  ;; A typo is its own error, not the missing-policy one.
  (is (thrown-with-msg?
        #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo)
        #":rf\.error/ssr-unknown-payload-policy"
        (rf.ssr.payload-policy/apply-policy
          sample-app-db {:payload :rf.ssr.payload/whole-db}))))

(deftest validate-policy-opts-passes-whole-app-db
  (let [opts {:initial-events [[:init]] :payload :rf.ssr.payload/whole-app-db}]
    (is (= opts (rf.ssr.payload-policy/validate-policy-opts! opts)))))

(deftest validate-policy-opts-fails-closed
  (is (= {:rf.error/id :rf.error/ssr-missing-payload-policy :recovery :declare-payload-policy}
         (-> (try (rf.ssr.payload-policy/validate-policy-opts! {:initial-events [[:init]]})
                  (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
                    (ex-data e)))
             (select-keys [:rf.error/id :recovery])))))

;; ---- runtime-db projection ------------------------------------------------

(def sample-runtime-db
  {:rf.runtime/machines {:snapshots {:auth.session/abc {:state :authenticated}}}
   ;; Only `:current` is durable; the rest of the routing slice stays home.
   :rf.runtime/routing  {:current            {:route-id :route/home}
                         :pending-navigation {:id "pn-1" :reason :can-leave}
                         :nav-token-counter  7}
   ;; The elision registry's keys are classified paths, which can embed a
   ;; sensitive id, so the registry never crosses the wire.
   :rf.runtime/elision  {:declarations           {[:auth :token] #{{:source :effect}}}
                         :sensitive-declarations {[:by-id "user-secret-id" :token]
                                                  #{{:source :effect}}}}
   :rf.runtime/ssr      {:hydration {:server-hash "h1"}}})

(deftest project-runtime-db-ships-durable-omits-transient
  (is (= {:rf.runtime/machines {:snapshots {:auth.session/abc {:state :authenticated}}}
          :rf.runtime/routing  {:current {:route-id :route/home}}
          :rf.runtime/ssr      {:hydration {:server-hash "h1"}}}
         (rf.ssr.payload-policy/project-runtime-db sample-runtime-db))))

(deftest project-runtime-db-nil-and-empty
  ;; nil lets build-payload omit the optional :rf/runtime-db key.
  (is (nil? (rf.ssr.payload-policy/project-runtime-db nil)))
  (is (nil? (rf.ssr.payload-policy/project-runtime-db {:rf.runtime/routing {:scroll-positions {}}}))
      "a runtime-db with only transient routing keys projects to nil"))

;; ---- payload assembly -----------------------------------------------------

(deftest build-payload-stamps-each-optional-slot-only-when-supplied
  ;; A nil optional value omits its key: a present-and-nil key is not a
  ;; spelling of absence in the `:rf/hydration-payload` schema.
  (let [rt {:rf.runtime/ssr {:hydration {:server-hash "h1"}}}]
    (is (= {:rf/version       7
            :rf/app-db        {:public/page :dashboard}
            :rf/render-hash   "body-h"
            :rf/frame-id      :app/main
            :rf/runtime-db    rt
            :rf/schema-digest "digest-abc"
            :rf/head-hash     "head-h"}
           (rf.ssr.payload-policy/build-payload
             :app/main {:public/page :dashboard} "body-h"
             {:version 7 :runtime-db rt :schema-digest "digest-abc" :head-hash "head-h"})))
    (is (= {:rf/version 1 :rf/app-db {:public/page :dashboard}}
           (rf.ssr.payload-policy/build-payload
             nil {:public/page :dashboard} nil
             {:runtime-db nil :schema-digest nil :head-hash nil})))))

(deftest resolve-version-coerces-and-rejects-to-integer
  (is (= 7 (:rf/version (rf.ssr.payload-policy/build-payload :rf/default {} "h" {:version "7"})))
      "a whole-number string is coerced")
  (with-trace-recorder! [traces]
    (is (= 1 (:rf/version (rf.ssr.payload-policy/build-payload :rf/default {} "h" {:version "1.0.0"})))
        "a semver string is rejected and falls back to v1")
    (when rf.interop/debug-enabled?
      (is (= [{:op-type :warning :value "1.0.0" :recovery :rejected-and-fell-back}]
             (->> @traces
                  (filter #(= :rf.ssr/invalid-version (:operation %)))
                  (mapv (fn [t] {:op-type  (:op-type t)
                                 :value    (-> t :tags :value)
                                 :recovery (:recovery t)}))))))))
