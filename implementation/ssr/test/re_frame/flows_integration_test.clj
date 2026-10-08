(ns re-frame.flows-integration-test
  "Flows composed with machines, app-db schemas, routing and SSR. It lives
  in the ssr artefact because its `:test` classpath is the only one that
  pulls all of them at once.

  The contract: flows run at the OUTERMOST `:after`, transforming the
  handler's pending effects before the single install, which is the atomic
  commit boundary. So a flow evaluates once per event on the settled db; a
  flow throw aborts the event with nothing installed and no `:fx` walked; a
  schema-violating flow output rejects the whole candidate (dev posture
  only — production trusts the programmer); and the dirty-check keys on
  BOTH partitions, so a runtime-only event recomputes a flow reading
  `[:rf.db/runtime …]`.

  Posture: the trace-bus assertions sit in `(when interop/debug-enabled? …)`
  arms; every interaction also has a witness outside them (the flow's own
  eval log, app-db, the route slice, an fx counter) that runs under the
  production gate."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.interop :as rf.interop]
            [re-frame.schemas :as rf.schemas]
            ;; Publishes the `:schemas/malli-validate` hook; without it
            ;; `reg-app-schema` validation soft-passes.
            [re-frame.schemas.malli]
            [re-frame.ssr :as rf.ssr]
            [re-frame.ssr.test-fixture :as rf.ssr.test-fixture]
            [re-frame.test-support :refer [with-trace-recorder!]]))

(def ^:dynamic ^:private *captured* nil)

(defn- reset-runtime
  "The canonical SSR reset, plus the global schema validator and the
  error-listener registry (which it does not reset), plus a capture of every
  trace into `*captured*`."
  [test-fn]
  (rf.ssr.test-fixture/reset-runtime
    (fn []
      (rf.schemas/set-schema-fns! rf.schemas/default-schema-fns)
      (rf.error-emit/clear-error-listeners!)
      (with-trace-recorder! [captured]
        (binding [*captured* captured]
          (try
            (test-fn)
            (finally
              (rf.schemas/set-schema-fns! rf.schemas/default-schema-fns))))))))

(use-fixtures :each reset-runtime)

(defn- ops-among
  "The captured trace operations that are members of `op-set`, in order."
  [op-set]
  (filterv op-set (map :operation @*captured*)))

(defn- default-db [] (rf/app-db-value :rf/default))

(defn- current-route-id []
  (get-in (rf/frame-state-value :rf/default) [:rf.db/runtime :rf.runtime/routing :current :route-id]))

(deftest machine-multi-microstep-macrostep-then-single-flow-eval
  (testing "a machine macrostep chaining :raise and :always settles within one
            event, then exactly ONE flow eval runs, on the settled value"
    (let [flow-evals (atom [])]
      (rf/reg-machine :gauge/flow
        {:initial :idle
         :data    {:ticks 0}
         :guards  {:ready? (fn [{data :data}] (>= (:ticks data) 2))}
         :actions {:bump-then-raise (fn [{data :data}]
                                      {:data {:ticks (inc (:ticks data))} :fx [[:raise [:tick]]]})
                   :bump            (fn [{data :data}] {:data {:ticks (inc (:ticks data))}})}
         :states  {:idle     {:on {:start {:target :counting :action :bump-then-raise}}}
                   :counting {:always [{:guard :ready? :target :ready}]
                              :on     {:tick {:action :bump}}}
                   :ready    {}}})
      (rf/reg-flow :gauge/label
        {:inputs      [[:rf.db/runtime :rf.runtime/machines :snapshots :gauge/flow :data :ticks]]
         :output-path [:derived :gauge-label]}
        (fn [ticks] (swap! flow-evals conj ticks) (str "ticks=" ticks)))
      (rf/dispatch-sync [:gauge/flow [:start]])
      (is (= [2] @flow-evals)))))

(deftest flow-output-schema-failure-rejects-candidate-before-install
  (testing "a flow output violating the app-db schema rejects the WHOLE
            candidate (handler write and flow write) before install — in dev.
            Production has no validator, so the candidate installs whole"
    (rf/reg-app-schema [:derived] [:map [:doubled [:int {:min 0}]]])
    (rf/reg-event :seed  (fn [_ _] {:db {:n 1 :derived {:doubled 0}}}))
    (rf/reg-event :set-n (fn [{:keys [db]} [_ v]] {:db (assoc db :n v)}))
    (let [flow-outputs (atom [])]
      (rf/reg-flow :doubler {:inputs [[:n]] :output-path [:derived :doubled]}
        (fn [n] (let [out (* 2 n)] (swap! flow-outputs conj out) out)))
      (rf/dispatch-sync [:seed])
      (let [baseline-db (default-db)]
        (reset! *captured* [])
        (reset! flow-outputs [])
        (rf/dispatch-sync [:set-n -3])
        ;; The flow computed its bad value: a candidate rejection of a
        ;; computed value, not a flow-eval throw.
        (is (= [-6] @flow-outputs))
        (if rf.interop/debug-enabled?
          (do
            (is (= baseline-db (default-db)))
            (is (= [:rf.error/schema-validation-failure]
                   (ops-among #{:rf.event/db-changed :rf.error/schema-validation-failure}))
                "one schema failure and no db-changed: the candidate never installed")
            (is (true? (-> (filter #(= :rf.error/schema-validation-failure (:operation %)) @*captured*)
                           first :tags :rollback?))))
          ;; `reg-app-schema` is a development-only assertion, so a change that
          ;; made it always-on reddens here.
          (is (= {:n -3 :derived {:doubled -6}} (default-db))))))))

(deftest flow-throw-aborts-event-no-db-changed-no-partial-commit
  (testing "a flow THROW aborts the event before install: the handler's :db
            does not land"
    (rf/reg-event :seed (fn [_ _] {:db {:n 0}}))
    (rf/reg-event :bump (fn [{:keys [db]} _] {:db (update db :n inc)}))
    (rf/dispatch-sync [:seed])
    (let [flow-attempts (atom 0)]
      (rf/reg-flow :boom {:inputs [[:n]] :output-path [:derived :doomed]}
        (fn [_] (swap! flow-attempts inc) (throw (ex-info "flow boom" {:why :test}))))
      (reset! *captured* [])
      (rf/dispatch-sync [:bump])
      (is (= {:n 0} (default-db)))
      (is (= 1 @flow-attempts) "the flow was evaluated and threw, not skipped")
      ;; DEV ARM
      (when rf.interop/debug-enabled?
        (is (= [:rf.flow/failed] (ops-among #{:rf.flow/failed :rf.event/db-changed})))))))

(deftest flow-augmented-value-renders-under-ssr-sync-drain
  (testing "under SSR's synchronous drain a sub over a flow's :output-path
            renders the flow-augmented value"
    (rf/reg-event :ssr/seed (fn [_ _] {:db {:user {:first "Ada" :last "Lovelace"}}}))
    (rf/reg-flow :user/full-name
      {:inputs [[:user :first] [:user :last]] :output-path [:derived :full-name]}
      (fn [first last] (str first " " last)))
    (rf/reg-sub :full-name (fn [db _] (get-in db [:derived :full-name])))
    (rf/reg-view* :pages/greeting
      (fn [] [:div.greeting [:h1 "Hello, " (rf/subscribe-once [:full-name])]]))
    (rf/dispatch-sync [:ssr/seed])
    (is (str/includes? (rf.ssr/render-to-string [(rf/view :pages/greeting)] {})
                       "Hello, Ada Lovelace"))))

(deftest each-child-dispatch-gets-its-own-independent-flow-eval
  (testing "a parent's :fx :dispatch child gets its OWN flow eval over its
            own settled db"
    (let [flow-inputs (atom [])]
      (rf/reg-flow :tracker {:inputs [[:n]] :output-path [:derived :scaled]}
        (fn [n] (swap! flow-inputs conj n) (* 10 n)))
      (rf/reg-event :parent (fn [{:keys [db]} _] {:db (assoc db :n 1) :fx [[:dispatch [:child]]]}))
      (rf/reg-event :child  (fn [{:keys [db]} _] {:db (assoc db :n 2)}))
      (rf/dispatch-sync [:parent])
      (is (= [1 2] @flow-inputs)))))

(deftest flow-throw-on-route-transition-aborts-event-slice-unchanged-no-on-match-fx
  (testing "a flow throw on a :rf.route/handle-url-change aborts the WHOLE
            event: neither partition installs and the :on-match :dispatch in
            its :fx is never walked"
    (let [on-match-fired (atom 0)]
      (rf/reg-event :route/load-article
        (fn [{:keys [db]} _] (swap! on-match-fired inc) {:db (assoc db :article/loaded? true)}))
      (rf/reg-route :route/article {:params [:map [:id :string]] :on-match [[:route/load-article]]}
                    "/articles/:id")
      (rf/reg-route :route/home {} "/")
      (rf/dispatch-sync [:rf.route/handle-url-change "/" {:rf.route/cause :link}])
      (let [baseline-db (default-db)]
        (rf/reg-flow :route/boom
          {:inputs      [[:rf.db/runtime :rf.runtime/routing :current :route-id]]
           :output-path [:derived :route-doomed]}
          (fn [_] (throw (ex-info "flow boom on route" {:why :test}))))
        (reset! *captured* [])
        (rf/dispatch-sync [:rf.route/handle-url-change "/articles/42" {:rf.route/cause :link}])
        (is (= baseline-db (default-db)))
        (is (= :route/home (current-route-id)))
        (is (zero? @on-match-fired))
        ;; DEV ARM
        (when rf.interop/debug-enabled?
          (is (= [:rf.flow/failed] (ops-among #{:rf.flow/failed :rf.event/db-changed}))))))))

(deftest runtime-only-event-triggers-runtime-db-reading-flow-recompute
  (testing "a runtime-only :rf.route/handle-url-change (no :db effect)
            recomputes a flow whose only input is a [:rf.db/runtime …] path,
            writes its output to app-db, and a value-equal re-transition
            still skips"
    (rf/reg-route :route/article {:params [:map [:id :string]]} "/articles/:id")
    (rf/reg-route :route/home    {} "/")
    (let [flow-evals (atom [])]
      (rf/reg-flow :nav/breadcrumb
        {:inputs      [[:rf.db/runtime :rf.runtime/routing :current :route-id]]
         :output-path [:nav :breadcrumb]}
        (fn [route-id] (swap! flow-evals conj route-id) (str "at:" route-id)))
      (rf/dispatch-sync [:rf.route/handle-url-change "/" {:rf.route/cause :link}])
      (rf/dispatch-sync [:rf.route/handle-url-change "/articles/42" {:rf.route/cause :link}])
      (is (= [:route/home :route/article] @flow-evals))
      (is (= "at::route/article" (get-in (default-db) [:nav :breadcrumb])))
      (reset! flow-evals [])
      (rf/dispatch-sync [:rf.route/handle-url-change "/articles/42" {:rf.route/cause :link}])
      (is (= [] @flow-evals)))))

(deftest flow-composing-app-db-and-runtime-db-inputs-resolves-each-partition
  (testing "one flow with a bare app-db input and a [:rf.db/runtime …] input
            resolves each against its own partition and recomputes when
            EITHER changes"
    (rf/reg-route :route/article {:params [:map [:id :string]]} "/articles/:id")
    (rf/reg-event :set-greeting (fn [{:keys [db]} [_ g]] {:db (assoc db :greeting g)}))
    (let [flow-evals (atom [])]
      (rf/reg-flow :nav/banner
        {:inputs      [[:greeting] [:rf.db/runtime :rf.runtime/routing :current :route-id]]
         :output-path [:nav :banner]}
        (fn [greeting route-id] (swap! flow-evals conj [greeting route-id]) (str greeting " @ " route-id)))
      (rf/dispatch-sync [:set-greeting "Hi"])
      (rf/dispatch-sync [:rf.route/handle-url-change "/articles/42" {:rf.route/cause :link}])
      (rf/dispatch-sync [:set-greeting "Yo"])
      (is (= [["Hi" nil] ["Hi" :route/article] ["Yo" :route/article]] @flow-evals)))))
