(ns re-frame.http-canned-correlation-test
  "Canned managed-HTTP replies carry the live reply's
  `:correlation {:request-id …}` whenever the request carries a
  `:request-id`, and omit it otherwise.

  The resources tutorial's login and session-restore reply handlers drop a
  stale reply by comparing `(second (:request-id correlation))` against the
  app's `:auth-generation`. That guard is only testable if every path that
  synthesises a reply in tests echoes the correlation: the two canned fxs
  (immediate and `:after-ms`-deferred), and the route-map stub behind both
  `with-request-stubs` and `install-managed-request-stubs!`."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.http.managed]
            [re-frame.http.test-support :as rf.http.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(def ^:private login-url "/users/login")

(defn- reg-guarded-login!
  "The tutorial's shape: submitting advances `:auth-generation` and issues the
  request under `[:auth/login generation]`; each reply acts only when that
  generation is still current. `extra` merges over the args map, so a caller
  can pick the canned fx's own args or force a stale `:request-id`."
  []
  (rf/reg-event :login/submit
    (fn [{:keys [db]} [_ extra]]
      (let [generation (inc (:auth-generation db 0))]
        {:db (-> db (assoc :auth-generation generation) (dissoc :outcome))
         :fx [[:rf.http/managed
               (merge {:request    {:method :post :url login-url}
                       :request-id [:auth/login generation]
                       :decode     :json
                       :on-success [:login/succeeded]
                       :on-failure [:login/failed]}
                      extra)]]})))
  (rf/reg-event :login/succeeded
    (fn [{:keys [db]} [_ {:keys [value correlation]}]]
      (if (not= (:auth-generation db) (second (:request-id correlation)))
        {}
        {:db (assoc db :outcome [:ok value])})))
  (rf/reg-event :login/failed
    (fn [{:keys [db]} [_ {:keys [status error correlation]}]]
      (if (not= (:auth-generation db) (second (:request-id correlation)))
        {}
        {:db (assoc db :outcome [status (:kind error)])}))))

(defn- outcome [] (:outcome (rf/app-db-value :rf/default)))

(defn- submit-via!
  "Submit through the fx `fx-id` stands in for `:rf.http/managed` with."
  [fx-id extra]
  (rf/dispatch-sync [:login/submit extra] {:fx-overrides {:rf.http/managed fx-id}}))

(deftest guarded-reply-handler-acts-on-every-canned-reply
  (reg-guarded-login!)
  (let [ada {:user {:username "ada"}}]
    (doseq [[label fire! expected]
            [[":rf.http/managed-canned-success"
              #(submit-via! :rf.http/managed-canned-success {:value ada})
              [:ok ada]]
             [":rf.http/managed-canned-failure"
              #(submit-via! :rf.http/managed-canned-failure {:kind :rf.http/http-4xx})
              [:error :rf.http/http-4xx]]
             [":rf.http/managed-canned-failure with an :rf.http/aborted kind"
              #(submit-via! :rf.http/managed-canned-failure {:kind :rf.http/aborted})
              [:cancelled :rf.http/aborted]]
             ["with-request-stubs, an :ok route"
              #(rf.http.test-support/with-request-stubs
                 {[:post login-url] {:reply {:ok ada}}}
                 (fn [] (rf/dispatch-sync [:login/submit {}])))
              [:ok ada]]
             ["with-request-stubs, a :failure route"
              #(rf.http.test-support/with-request-stubs
                 {[:post login-url] {:reply {:failure {:kind :rf.http/http-4xx :status 422}}}}
                 (fn [] (rf/dispatch-sync [:login/submit {}])))
              [:error :rf.http/http-4xx]]
             ["with-request-stubs, no matching route"
              #(rf.http.test-support/with-request-stubs
                 {}
                 (fn [] (rf/dispatch-sync [:login/submit {}])))
              [:error :rf.http/transport]]
             ["install-managed-request-stubs!"
              #(try
                 (submit-via! (rf.http.test-support/install-managed-request-stubs!
                                {[:post login-url] {:reply {:ok ada}}})
                              {})
                 (finally (rf.http.test-support/uninstall-managed-request-stubs!)))
              [:ok ada]]]]
      (testing label
        (fire!)
        (is (= expected (outcome))
            "the generation guard reads the canned reply's :correlation and accepts it"))))
  (testing ":rf.http/managed-canned-success deferred by :after-ms"
    (submit-via! :rf.http/managed-canned-success {:value {:late true} :after-ms 1})
    (is (= [:ok {:late true}]
           (rf.test-support/poll-until outcome {:timeout-ms 5000 :label "deferred canned reply"}))
        "the deferred re-fire keeps the :request-id, so the reply still correlates")))

(deftest canned-reply-carries-correlation-iff-request-id
  (testing ":correlation is {:request-id <id>} when the args carry one and
            absent when they do not, as on the live reply"
    (rf/reg-event :t/replied
      (fn [{:keys [db]} [_ reply]] {:db (assoc db :reply reply)}))
    (rf/reg-event :t/issue
      (fn [_ [_ extra]]
        {:fx [[:rf.http/managed
               (merge {:request {:method :get :url "/t"} :reply-to [:t/replied]} extra)]]}))
    (doseq [fx-id [:rf.http/managed-canned-success :rf.http/managed-canned-failure]]
      (testing (str fx-id)
        (rf/dispatch-sync [:t/issue {:request-id :t/load}] {:fx-overrides {:rf.http/managed fx-id}})
        (is (= {:request-id :t/load}
               (:correlation (:reply (rf/app-db-value :rf/default)))))
        (rf/dispatch-sync [:t/issue {}] {:fx-overrides {:rf.http/managed fx-id}})
        (is (not (contains? (:reply (rf/app-db-value :rf/default)) :correlation)))))))
