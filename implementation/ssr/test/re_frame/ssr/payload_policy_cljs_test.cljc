(ns re-frame.ssr.payload-policy-cljs-test
  "The fail-closed hydration-payload policy (`:payload` is an allowlist of
  top-level keys or the whole-app-db opt-in), the runtime-db projection and the
  payload assembly. Platform-neutral, so it runs on the JVM and Node.

  It also runs in the production gate (`-Dre-frame.debug=false`)."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.ssr :as rf.ssr]
            [re-frame.ssr.payload-policy :as rf.ssr.payload-policy]))

;; The runtime-db projection classifies under a live frame, and making one
;; needs an adapter. COLD-START the slot: this ns shares the node bundle with
;; suites that seat other adapters, and `init!` raises when handed a different
;; one.
(use-fixtures :once
  (fn [f]
    (rf/destroy-adapter!)
    (rf/init! rf.ssr/adapter)
    (try (f) (finally (rf/destroy-adapter!)))))

(def ^:private projection-frame :rf.ssr.payload-policy-test/server)

(defn- project-under-live-frame [runtime-db]
  (rf/make-frame {:id projection-frame :platform :server})
  (try (rf.ssr.payload-policy/project-runtime-db runtime-db projection-frame)
       (finally (rf/destroy-frame! projection-frame))))

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
  ;; Absent and a set (not an ordered key selection) both throw rather than
  ;; ship anything. An empty allowlist is a policy that ships nothing.
  (doseq [opts [{} {:payload #{:public/articles}}]]
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
   :rf.runtime/ssr      {:hydration {:version 1}}})

(deftest project-runtime-db-ships-durable-omits-transient
  (is (= {:rf.runtime/machines {:snapshots {:auth.session/abc {:state :authenticated}}}
          :rf.runtime/routing  {:current {:route-id :route/home}}
          :rf.runtime/ssr      {:hydration {:version 1}}}
         (project-under-live-frame sample-runtime-db))))

(deftest project-runtime-db-nil-and-empty
  ;; nil lets build-payload omit the optional :rf/runtime-db key.
  (is (nil? (project-under-live-frame nil)))
  (is (nil? (project-under-live-frame {:rf.runtime/routing {:scroll-positions {}}}))
      "a runtime-db with only transient routing keys projects to nil"))

(deftest project-runtime-db-fails-closed-without-a-live-frame
  ;; No frame means no classification policy, so nothing user-bearing rides.
  (is (= {:rf.runtime/machines :rf/redacted
          :rf.runtime/routing  :rf/redacted
          :rf.runtime/ssr      {:hydration {:version 1}}}
         (rf.ssr.payload-policy/project-runtime-db sample-runtime-db nil))))

;; ---- payload assembly -----------------------------------------------------

(deftest build-payload-stamps-each-optional-slot-only-when-supplied
  ;; A nil optional value omits its key: a present-and-nil key is not a
  ;; spelling of absence in the `:rf/hydration-payload` schema.
  (let [rt {:rf.runtime/ssr {:hydration {:version 1}}}]
    (is (= {:rf/version       rf.ssr.payload-policy/pattern-protocol-version
            :rf/app-db        {:public/page :dashboard}
            :rf/frame-id      :app/main
            :rf/runtime-db    rt
            :rf/schema-digest "digest-abc"}
           (rf.ssr.payload-policy/build-payload
             :app/main {:public/page :dashboard}
             {:runtime-db rt :schema-digest "digest-abc"})))
    (is (= {:rf/version 1 :rf/app-db {:public/page :dashboard}}
           (rf.ssr.payload-policy/build-payload
             nil {:public/page :dashboard}
             {:runtime-db nil :schema-digest nil})))))

