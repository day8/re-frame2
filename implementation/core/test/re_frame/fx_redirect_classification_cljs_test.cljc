(ns re-frame.fx-redirect-classification-cljs-test
  "Under an `:fx-overrides` keyword redirect the resolved TARGET's
  `[:rf.fx/id :rf.fx/args]` trace slots carry the ORIGINAL fx's args. Keyed on
  the target id alone, a classified fx redirected to an unclassified stub would
  ride raw. So `handle-one-fx` stamps the original id as `:rf.fx/from`, and the
  projector composes BOTH registrations' classification — the target's own
  first, then the original's static paths and per-fx-id dynamic case.

  The live round-trips read the dev trace, so their trace reads sit inside
  `(when rf.interop/debug-enabled? …)` arms (a no-leak sweep over an empty
  stream passes for free). Each first proves, in every posture, that the
  redirect resolved and the target received the RAW args."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [clojure.string :as str]
            [re-frame.classification :as rf.classification]
            [re-frame.core :as rf]
            [re-frame.interop :as rf.interop]
            ;; Loading the http artefact binds the `:http/project-managed-fx-args` hook…
            [re-frame.http.managed]
            ;; …and registers the `:rf.http/managed-canned-*` stub fxs.
            [re-frame.http.test-support]
            [re-frame.privacy :as rf.privacy]
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(def ^:private pw-sentinel "rf2-2siusz-PW-4c8d19")

(defn- leaks? [sentinel x] (str/includes? (pr-str x) sentinel))

(defn- record-traces! []
  (let [a (atom [])]
    (rf/register-listener! :trace ::probe (fn [ev] (swap! a conj ev)))
    a))

(deftest redirected-slot-composes-both-registrations
  (testing "the redirect TARGET's own static declaration composes WITH the
            original's — both ids' paths redact on the same slot"
    (rf.registrar/register! :fx ::orig {:sensitive [[:token]]})
    (rf.registrar/register! :fx ::stub {:sensitive [[:note]]})
    (is (= {:token rf.privacy/redacted-sentinel
            :note  rf.privacy/redacted-sentinel
            :other "x"}
           (:rf.fx/args (:tags (rf.classification/project-trace-event
                                 {:operation :rf.fx/handled
                                  :tags {:frame      :rf/default
                                         :rf.fx/id   ::stub
                                         :rf.fx/from ::orig
                                         :rf.fx/args {:token pw-sentinel
                                                      :note  pw-sentinel
                                                      :other "x"}}})))))))

(deftest live-redirect-stamps-from-and-redacts-original-static-paths
  (testing "a classified fx keyword-redirected to an unclassified stub: the stub
            receives the RAW args; its :rf.fx/handled stamps :rf.fx/from and
            redacts the ORIGINAL id's declared path"
    (let [got (atom ::none)]
      (rf/reg-fx ::orig {:sensitive [[:token]]} (fn [_ _] nil))
      (rf/reg-fx ::stub (fn [_ args] (reset! got args)))
      (rf/reg-event ::go
        (fn [_ _] {:fx [[::orig {:token pw-sentinel :note "plain"}]]}))
      (let [traces (record-traces!)]
        (rf/dispatch-sync [::go] {:fx-overrides {::orig ::stub}})
        (rf/unregister-listener! :trace ::probe)
        (is (= {:token pw-sentinel :note "plain"} @got)
            "the redirect resolved to ::stub, which received the RAW args")
        (when rf.interop/debug-enabled?
          (let [handled (filter #(= ::stub (get-in % [:tags :rf.fx/id])) @traces)]
            (is (seq handled) "the stub emitted a :rf.fx/handled trace")
            (doseq [ev handled]
              (is (= ::orig (get-in ev [:tags :rf.fx/from])))
              (is (= {:token rf.privacy/redacted-sentinel :note "plain"}
                     (get-in ev [:tags :rf.fx/args])))))
          (let [entries (for [ev    @traces
                              :let  [fx-vec (get-in ev [:tags :rf.event/fx])]
                              :when (vector? fx-vec)
                              [id args] fx-vec
                              :when (= ::orig id)]
                          args)]
            (is (seq entries) "the do-fx aggregate carried the original entry")
            (doseq [args entries]
              (is (= rf.privacy/redacted-sentinel (:token args)))))
          (is (not (some #(leaks? pw-sentinel %) @traces))
              "no emitted trace event leaks the token"))))))

(deftest live-canned-stub-redirect-redacts-sensitive-managed-request
  (testing "a :sensitive? true :rf.http/managed request keyword-redirected to the
            canned-success stub — the canned reply lands, and the stub's own
            :rf.fx/handled redacts the body through the ORIGINAL id's dynamic case"
    (let [reply (atom ::none)]
      (rf/reg-event ::got (fn [{:keys [db]} [_ r]] (reset! reply r) {:db db}))
      (rf/reg-event ::issue
        (fn [_ _]
          {:fx [[:rf.http/managed
                 {:request    {:method :post
                               :url    "https://api.example.test/login"
                               :body   {:password pw-sentinel}
                               :request-content-type :json
                               :sensitive? true}
                  :decode     :json
                  :value      {:ok true}
                  :on-success [::got]
                  :on-failure [::got]}]]}))
      (let [traces (record-traces!)]
        (rf/dispatch-sync [::issue]
                          {:fx-overrides {:rf.http/managed
                                          :rf.http/managed-canned-success}})
        (rf/unregister-listener! :trace ::probe)
        (is (= {:status :ok :value {:ok true}} @reply)
            "the redirect resolved to the canned stub, whose reply completed the cascade")
        (when rf.interop/debug-enabled?
          (let [handled (filter #(= :rf.http/managed-canned-success
                                    (get-in % [:tags :rf.fx/id]))
                                @traces)]
            (is (seq handled) "the canned stub emitted its own :rf.fx/handled")
            (doseq [ev handled]
              (is (= :rf.http/managed (get-in ev [:tags :rf.fx/from])))
              (is (= rf.privacy/redacted-sentinel
                     (get-in ev [:tags :rf.fx/args :request :body])))
              (is (= "https://api.example.test/login"
                     (get-in ev [:tags :rf.fx/args :request :url]))
                  "shape retained — the url survives")))
          (is (some #(and (= :rf.fx/override-applied (:operation %))
                          (= :rf.http/managed (get-in % [:tags :rf.fx/from]))
                          (= :rf.http/managed-canned-success
                             (get-in % [:tags :rf.fx/to])))
                    @traces)
              ":rf.fx/override-applied records the redirect pair")
          (is (not (some #(leaks? pw-sentinel %) @traces))
              "no emitted trace event leaks the password"))))))
