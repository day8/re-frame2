(ns re-frame.http-stub-transport-cljs-test
  "Host-symmetric (JVM + CLJS) contract of the stub transport seam (Spec 014
  §Testing): a stub stands in for the host send and NOTHING ELSE, so the real
  managed pipeline runs above and below it.

  The pipeline cases are the proof. A body the request's `:decode` schema
  refuses is classified by the real `:decode`, an `:accept` reshapes or rejects
  the real decoded body, and a `:sensitive?` request is scrubbed from the trace
  while the app still receives its own raw reply. The retry, timeout and delay
  cases need a clock and live in the JVM-only
  `re-frame.http-stub-transport-test`.

  Every answer here is INLINE — the stub calls `respond` before it returns —
  so each request's reply has landed by the time the `dispatch-sync` that
  issued it returns, on both hosts."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.http.managed :as rf.http.managed]
            [re-frame.http.registry :as rf.http.registry]
            [re-frame.http.stub-transport :as rf.http.stub-transport]
            [re-frame.schemas]
            [re-frame.test-support :as rf.test-support]
            #?@(:clj  [[re-frame.substrate.plain-atom :as rf.substrate.plain-atom]]
                :cljs [[re-frame.adapter.reagent :as rf.adapter.reagent]])))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter rf.substrate.plain-atom/adapter}
       :cljs {:adapter rf.adapter.reagent/adapter})))

(def ^:private app :http-stub/app)

(defn- fresh-frame! [] (rf/make-frame {:id app}))

(defn- fire!
  "Issue `args` (a `:rf.http/managed` args map, `:reply-to` added) from an
  event on the test frame. Returns the reply once it has landed, else nil."
  [args]
  (rf/reg-event ::replied
    {:doc "Records the reply."}
    (fn [{:keys [db]} [_ reply]] {:db (assoc db ::reply reply)}))
  (rf/reg-event ::fire
    {:doc "Issues the request under test."}
    (fn [_ _] {:fx [[:rf.http/managed (assoc args :reply-to [::replied])]]}))
  (rf/dispatch-sync [::fire] {:frame app})
  (::reply (rf/app-db-value app)))

(defn- abort! [request-id]
  (rf/reg-event ::abort
    {:doc "Aborts a request by its id."}
    (fn [_ [_ id]] {:fx [[:rf.http/managed-abort id]]}))
  (rf/dispatch-sync [::abort request-id] {:frame app}))

(defn- traces-of
  "Run `f` with every trace event collected; return the events."
  [f]
  (let [traces (atom [])
        id     (keyword (gensym "stub-transport"))]
    (rf/register-listener! :trace id #(swap! traces conj %))
    (try (f) @traces
         (finally (rf/unregister-listener! :trace id)))))

(defn- http-rows
  "The `:rf.http/*` rows in `traces`: the managed-HTTP trace surface the
  privacy scrub governs. (The reply EVENT is the app's own, classified by its
  own registration.)"
  [traces]
  (filterv #(= "rf.http" (namespace (:operation %))) traces))

(defn- replied-values [traces]
  (mapv #(get-in % [:tags :value]) (filter #(= :rf.http/replied (:operation %)) traces)))

(def ^:private url "https://api.example.test/items/1")

(defn- answering
  "A route transport answering `url` with `response`."
  [response]
  (rf.http.stub-transport/route-transport {[:get url] response}))

;; ---- the seam -----------------------------------------------------------------

(deftest an-inline-answer-lands-within-the-dispatch-sync
  (testing "the reply is the real envelope: decoded by the request's :decode,
            with the work id, attempt and completion time the live path stamps"
    (fresh-frame!)
    (let [reply (rf.http.stub-transport/with-transport
                  (answering {:status 200 :headers {"X-Total" "1"} :body {:id 1 :name "one"}})
                  #(fire! {:request {:url url} :decode :json}))]
      (is (= [:ok {:id 1 :name "one"} 1]
             [(:status reply) (:value reply) (:attempt reply)]))
      (is (= {:status 200 :status-text "" :headers {"x-total" "1" "content-type" "application/json"}}
             (:meta reply))
          "the response meta, header names lower-cased as the hosts deliver them")
      (is (= [:rf.work/http 1 1] (:rf.reply/work-id reply)))
      (is (number? (:completed-at reply)))
      (is (empty? (rf.http.managed/in-flight-snapshot app)) "the request retired"))))

(deftest a-request-keeps-the-transport-it-was-issued-under
  (testing "an exchange held past the end of its with-transport scope is still
            answered through the stub, and the real pipeline completes it"
    (fresh-frame!)
    (let [{:keys [transport exchanges]} (rf.http.stub-transport/pending-transport)]
      (rf.http.stub-transport/with-transport transport #(fire! {:request {:url url} :decode :json}))
      (is (nil? (::reply (rf/app-db-value app))) "held: no reply yet")
      (is (= [{:method :get :url url :headers {} :body nil :timeout-ms 30000}]
             (mapv :request @exchanges))
          "the wire request the stub received")
      ;; Answered outside any drain, the reply EVENT is queued for the router;
      ;; the completion itself — decode, envelope, reply trace — runs here.
      (let [settled (atom nil)
            traces  (traces-of #(reset! settled ((:respond! (first @exchanges)) {:body {:late true}})))]
        (is (true? @settled))
        (is (= [{:late true}] (replied-values traces)))
        (is (empty? (rf.http.managed/in-flight-snapshot app)) "the request retired")))))

(deftest an-abort-cancels-a-held-request-and-fences-its-late-answer
  (fresh-frame!)
  (let [{:keys [transport exchanges]} (rf.http.stub-transport/pending-transport)]
    (rf.http.stub-transport/with-transport transport
      #(fire! {:request {:url url} :decode :json :request-id :held}))
    (let [{:keys [respond! cancelled?]} (first @exchanges)]
      (is (false? (cancelled?)))
      (abort! :held)
      (is (true? (cancelled?)) "the abort reached the stub")
      (is (= [:cancelled :rf.http/aborted :user]
             (let [r (::reply (rf/app-db-value app))]
               [(:status r) (get-in r [:error :kind]) (get-in r [:error :reason])])))
      (is (empty? (rf.http.managed/in-flight-snapshot app)))
      (is (false? (respond! {:body {:too :late}})) "the late answer settles nothing")
      (is (= :cancelled (:status (::reply (rf/app-db-value app)))) "and delivers nothing"))))

(deftest a-frame-runs-on-its-installed-transport-until-it-is-destroyed
  (fresh-frame!)
  (rf.http.stub-transport/install-transport! app (answering {:body {:from "frame"}}))
  (is (= {:from "frame"} (:value (fire! {:request {:url url} :decode :json}))))
  (testing "a with-transport scope wins over the frame's transport"
    (is (= {:from "scope"}
           (:value (rf.http.stub-transport/with-transport (answering {:body {:from "scope"}})
                     #(fire! {:request {:url url} :decode :json}))))))
  (rf.frame/destroy-frame! app)
  (is (nil? (rf.http.registry/frame-transport app)) "destroying the frame drops it"))

(deftest a-live-frame-value-installs-under-its-id
  (let [f        (fresh-frame!)
        by-id    (answering {:body {:via "id"}})
        by-value (answering {:body {:via "value"}})]
    (testing "installing by the live value is installing by the id"
      (rf.http.stub-transport/install-transport! app by-id)
      (rf.http.stub-transport/install-transport! f by-value)
      (is (identical? by-value (rf.http.registry/frame-transport app)))
      (is (nil? (rf.http.registry/frame-transport f)) "nothing is keyed under the value")
      (is (= {:via "value"} (:value (fire! {:request {:url url} :decode :json})))))
    (testing "uninstalling by the value clears it"
      (rf.http.stub-transport/install-transport! f nil)
      (is (nil? (rf.http.registry/frame-transport app))))
    (testing "destroying the frame drops a transport installed by its value"
      (rf.http.stub-transport/install-transport! f by-value)
      (rf/destroy-frame! f)
      (is (nil? (rf.http.registry/frame-transport app)))
      (is (nil? (rf.http.registry/frame-transport f)) "and leaves nothing under the value"))))

(deftest an-unmatched-route-fails-as-a-transport-failure
  (fresh-frame!)
  (let [reply (rf.http.stub-transport/with-transport (rf.http.stub-transport/route-transport {})
                #(fire! {:request {:url url}}))]
    (is (= [:error :rf.http/transport (str "no stub route for :get " url)]
           [(:status reply) (get-in reply [:error :kind]) (get-in reply [:error :message])]))))

;; ---- the real pipeline runs below the stub --------------------------------------

(def ^:private Item [:map [:id :int] [:name :string]])

(deftest the-real-decode-classifies-a-body-its-schema-refuses
  (fresh-frame!)
  (let [reply (rf.http.stub-transport/with-transport (answering {:body {:id "one" :name "one"}})
                #(fire! {:request {:url url} :decode Item}))]
    (is (= [:error :rf.http/decode-failure true]
           [(:status reply)
            (get-in reply [:error :kind])
            (get-in reply [:error :schema-validation-failure?])]))
    (is (= "{\"id\":\"one\",\"name\":\"one\"}" (get-in reply [:error :body-text]))
        "the failure carries the raw body the stub sent")))

(deftest the-real-accept-runs-on-the-decoded-body
  (fresh-frame!)
  (let [accept (fn [{:keys [found?] :as body}]
                 (if found? {:ok (dissoc body :found?)} {:failure {:reason :not-found}}))]
    (is (= {:id 1}
           (:value (rf.http.stub-transport/with-transport (answering {:body {:found? true :id 1}})
                     #(fire! {:request {:url url} :decode :json :accept accept})))))
    (is (= [:rf.http/accept-failure {:reason :not-found}]
           (let [r (rf.http.stub-transport/with-transport (answering {:body {:found? false}})
                     #(fire! {:request {:url url} :decode :json :accept accept}))]
             [(get-in r [:error :kind]) (get-in r [:error :detail])])))))

(deftest a-sensitive-request-is-scrubbed-from-the-trace
  (fresh-frame!)
  (let [secret "stub-transport-secret-81c2"]
    (testing "a 2xx: the reply trace redacts the value; the app gets it raw"
      (let [reply  (atom nil)
            traces (traces-of
                     #(reset! reply
                              (rf.http.stub-transport/with-transport (answering {:body {:token secret}})
                                (fn [] (fire! {:request {:url url} :decode :json :sensitive? true})))))]
        (is (= {:token secret} (:value @reply)))
        (is (= [:rf/redacted] (replied-values traces)))
        (is (not (str/includes? (pr-str (http-rows traces)) secret)) "no :rf.http/* row carries the secret")))
    (testing "a 5xx: the failure's raw body is redacted on the error trace"
      (let [reply  (atom nil)
            traces (traces-of
                     #(reset! reply
                              (rf.http.stub-transport/with-transport (answering {:status 500 :body (str "error " secret)})
                                (fn [] (fire! {:request {:url url} :sensitive? true})))))]
        (is (= (str "error " secret) (get-in @reply [:error :body])))
        (is (= [:rf/redacted] (mapv #(get-in % [:tags :body])
                                    (filter #(= :rf.http/http-5xx (:operation %)) traces))))
        (is (not (str/includes? (pr-str (http-rows traces)) secret)) "no :rf.http/* row carries the secret")))))
