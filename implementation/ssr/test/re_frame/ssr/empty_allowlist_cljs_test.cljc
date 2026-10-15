(ns re-frame.ssr.empty-allowlist-cljs-test
  "An empty allowlist is a policy that ships nothing. `:payload []` projects
  the hydration payload's `:rf/app-db` to `{}` however full app-db is, and an
  empty `:render-state` slot projects that partition to `{}`. An absent or nil
  `:payload` still fails closed. Platform-neutral, so it runs on the JVM and
  Node."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.ssr :as rf.ssr]
            [re-frame.ssr.payload-policy :as rf.ssr.payload-policy]
            [re-frame.ssr.render-state :as rf.ssr.render-state]))

;; COLD-START the adapter slot: this ns shares the node bundle with suites
;; that seat other adapters, and `init!` raises when handed a different one.
(use-fixtures :once
  (fn [f]
    (rf/destroy-adapter!)
    (rf/init! rf.ssr/adapter)
    (try (f) (finally (rf/destroy-adapter!)))))

(def ^:private app-db
  {:public/articles  [:a :b]
   :server-only/auth "SECRET_TOKEN"})

(def ^:private runtime-db
  {:rf.runtime/custom {:tenant "acme"}})

(def ^:private counter (atom 0))

(defn- populated-server-frame!
  "A server frame under a fresh id, holding `app-db` and `runtime-db`."
  []
  (let [fid (keyword "rf.ssr-empty" (str "frame" (swap! counter inc)))]
    (rf/make-frame {:id fid :platform :server})
    (rf.frame/replace-frame-state! fid {rf.frame/app-partition-key     app-db
                                        rf.frame/runtime-partition-key runtime-db})
    fid))

(defn- error-id
  "The `:rf.error/id` of the structured error `f` throws, or nil when it returns."
  [f]
  (try (f) nil
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e
         (:rf.error/id (ex-data e)))))

(deftest an-empty-payload-allowlist-is-a-policy-that-ships-nothing
  ;; A literal vector, a list, and an allowlist computed to nothing — the app
  ;; has no `:client/*` keys — all validate at construction and project `{}`.
  (doseq [payload [[] '() (filterv #(= "client" (namespace %)) (keys app-db))]]
    (testing (pr-str payload)
      (let [opts {:initial-events [[:init]] :payload payload}]
        (is (= opts (rf.ssr.payload-policy/validate-policy-opts! opts)))
        (is (= {} (rf.ssr.payload-policy/apply-policy app-db opts)))))))

(deftest an-empty-payload-allowlist-reaches-the-wire-as-an-empty-app-db
  ;; The payload builder's order: allowlist, frame-scoped egress, assembly.
  (let [fid   (populated-server-frame!)
        slice (rf.ssr.payload-policy/project-app-db-egress
                (rf.ssr.payload-policy/apply-policy
                  (rf.frame/frame-app-db-value fid) {:payload []})
                fid)]
    (is (= {} (:rf/app-db (rf.ssr.payload-policy/build-payload nil slice nil {}))))))

(deftest an-absent-or-nil-payload-still-fails-closed
  (doseq [opts [{:initial-events [[:init]]}
                {:initial-events [[:init]] :payload nil}]]
    (is (= :rf.error/ssr-missing-payload-policy
           (error-id #(rf.ssr.payload-policy/validate-policy-opts! opts)))
        (pr-str opts))))

(deftest an-empty-render-state-slot-projects-that-partition-as-empty
  (let [fid (populated-server-frame!)]
    (doseq [[policy expected]
            [[{:app-db []}
              {:rf/app-db {} :rf/runtime-db {}}]
             [{:app-db [] :runtime-db [:rf.runtime/custom]}
              {:rf/app-db {} :rf/runtime-db runtime-db}]
             [{:app-db [:public/articles] :runtime-db '()}
              {:rf/app-db {:public/articles [:a :b]} :rf/runtime-db {}}]]]
      (testing (pr-str policy)
        (let [opts {:render-state policy}]
          (is (= opts (rf.ssr.render-state/validate-policy-opts! opts)))
          (is (= expected (rf.ssr.render-state/project fid opts))))))))
