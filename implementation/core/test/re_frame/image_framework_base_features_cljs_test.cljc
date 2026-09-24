(ns re-frame.image-framework-base-features-cljs-test
  "PRODUCER-DERIVED: the framework's own feature
  registrations reach a frame built from an explicit `:select-ns` image.

  Every id below is registered by its owning artefact at namespace load,
  through the fn-alias path that records NO source namespace — so no
  `:select-ns` glob can select it, and none of them is a protected standard.
  Without the framework base, an explicit-image frame therefore could not
  resolve routing (`:rf.route/navigate` would fail its first dispatch with
  `:rf.error/no-such-handler`), managed HTTP, Resources, SSR hydration, the
  machine hydrate re-arm, or even core's own `:rf/time-ms`, while the same
  program on the default image works. Nothing here is a synthetic descriptor:
  each assertion reads the registration its producer actually made, beside
  the default-image control.

  The pure-pool half (membership rules, shadow edges, standards, caching) is
  `re-frame.image-framework-base-cljs-test`.

  `.cljc` ending `-cljs-test` rides `npm run test:cljs` AND `clojure -M:test`;
  the one dispatch through the routing façade is JVM-only (on the node lane
  the platform is `:client`, where navigation drives the host history API)."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.fx :as rf.fx]
            [re-frame.image :as rf.image]
            [re-frame.image-assembly :as rf.image-assembly]
            [re-frame.source-store :as rf.source-store]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            ;; Required for their ns-load registrations — the producers under test.
            [re-frame.routing]
            [re-frame.http.managed]
            [re-frame.resources]
            [re-frame.machines]
            [re-frame.ssr]))

;; An ordinary namespace-authored registration, so an image selecting THIS
;; namespace has something to select (a zero-match :include fails loud).
;; Registered before the fixture is built, so it is in the ns-load baseline.
(rf/reg-event :fwbase/ping (fn [{:keys [db]} _] {:db db}))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter       rf.substrate.plain-atom/adapter
                                               :ambient-frame nil}))

(def ^:private this-ns-image
  (rf.image/image {:id        :fwbase/app
                   :select-ns {:include ["re-frame.image-framework-base-features-cljs-test"]}}))

(defn- err-id
  [thunk]
  (try (thunk) nil
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
         (:rf.error/id (ex-data e)))))

(defn- shadows-of [gen k+id]
  (filterv #(= k+id (:registration %)) (:rf.gen/shadows gen)))

(def ^:private framework-ids
  "At least one registration per producer artefact, each recorded with NO
  source namespace."
  [[:event :rf.route/navigate]         ;; routing
   [:event :rf.route/url-requested]
   [:event :rf.route/entry-denied]     ;; the default Spec 012 promises resolves
   [:sub   :rf/route]
   [:view  :route/link]                ;; the one framework id outside the :rf root
   [:fx    :rf.http/managed]           ;; managed HTTP
   [:fx    :rf.http/managed-abort]
   [:sub   :rf/resource]               ;; Resources
   [:sub   :rf.resource/data]
   [:sub   :rf/mutation]
   [:fx    :rf.machine/hydrate-rearm]  ;; machines — not one of its standards
   [:event :rf/hydrate]                ;; SSR
   [:cofx  :rf/time-ms]])              ;; core

(deftest framework-feature-handlers-resolve-in-an-explicit-generation
  (let [default  (rf.image-assembly/assemble-default)
        explicit (rf.image-assembly/assemble [this-ns-image])]
    (doseq [[kind id] framework-ids]
      (testing (pr-str [kind id])
        (is (contains? (rf.source-store/descriptors-for kind id) nil)
            "premise: its producer recorded it with NO source namespace")
        (is (some? (rf.image-assembly/resolve-descriptor default kind id))
            "control: the default image resolves it")
        (is (some? (rf.image-assembly/resolve-descriptor explicit kind id))
            "an explicit :select-ns image resolves it too")))))

(deftest a-handler-requiring-time-ms-runs-in-an-explicit-frame
  (rf/reg-event :fwbase/plain
    (fn [{:keys [db]} _] {:db (assoc db :plain true)}))
  (rf/reg-event :fwbase/stamp
    {:rf.cofx/requires [:rf/time-ms]}
    (fn [{:keys [db rf/time-ms]} _] {:db (assoc db :stamped time-ms)}))
  (doseq [[label opts] [["default image"             {:id :fwbase/default-frame}]
                        ["explicit :select-ns image" {:id     :fwbase/explicit-frame
                                                      :images [this-ns-image]}]]]
    (testing label
      (let [f (rf/make-frame opts)]
        (try
          (is (nil? (err-id #(rf/dispatch-sync [:fwbase/plain] {:frame f})))
              "control: a handler with no requirement runs")
          (is (true? (:plain (rf/app-db-value f))))
          (is (nil? (err-id #(rf/dispatch-sync [:fwbase/stamp] {:frame f})))
              "the framework's provided :rf/time-ms coeffect satisfies the requirement")
          (is (number? (:stamped (rf/app-db-value f))))
          (finally
            (rf/destroy-frame! f)))))))

(deftest only-reserved-root-registrations-ride-the-base
  (let [probe (fn [_ctx _args] nil)]
    ;; Both through the fn-alias path, so neither records a source namespace.
    (rf.fx/reg-fx :rf.test/probe probe)
    (rf.fx/reg-fx :app/programmatic probe)
    (let [explicit (rf.image-assembly/assemble [this-ns-image])]
      (is (= probe (:handler-fn (rf.image-assembly/resolve-descriptor explicit :fx :rf.test/probe)))
          "an unstamped id under the reserved :rf root is framework-owned")
      (is (nil? (rf.image-assembly/resolve-descriptor explicit :fx :app/programmatic))
          "isolation control: an unstamped APP id stays out of an explicit image"))))

(deftest an-overrides-image-inlining-managed-http-wins-and-is-reported
  (let [double    (fn [_ctx _args] :double)
        overrides (rf.image/image {:id            :test/doubles
                                   :registrations {:reg-fx [[:rf.http/managed double]]}})
        gen       (rf.image-assembly/assemble [this-ns-image overrides])]
    (is (= double (:handler-fn (rf.image-assembly/resolve-descriptor gen :fx :rf.http/managed))))
    (is (= [{:registration [:fx :rf.http/managed]
             :image        :rf/framework
             :shadowed-by  :test/doubles}]
           (shadows-of gen [:fx :rf.http/managed])))))

(deftest the-entry-denied-default-resolves-and-an-app-override-shadows-it
  (testing "with no override selected, the framework's replaceable default
            resolves — Spec 012's promise that the denial dispatch never
            fails :rf.error/no-such-handler"
    (let [d (rf.image-assembly/resolve-descriptor
              (rf.image-assembly/assemble [this-ns-image]) :event :rf.route/entry-denied)]
      (is (true? (:rf/framework-default? d)))))
  (testing "an app image selecting its own :rf.route/entry-denied wins, and the
            shadow names :rf/framework"
    (rf/reg-event :rf.route/entry-denied (fn [{:keys [db]} _] {:db (assoc db :denied true)}))
    (let [gen (rf.image-assembly/assemble [this-ns-image])]
      (is (not (:rf/framework-default?
                 (rf.image-assembly/resolve-descriptor gen :event :rf.route/entry-denied))))
      (is (= [{:registration [:event :rf.route/entry-denied]
               :image        :rf/framework
               :shadowed-by  :fwbase/app}]
             (shadows-of gen [:event :rf.route/entry-denied]))))))

(deftest an-inline-entry-denied-override-keeps-the-framework-carriers
  (let [fw      (rf.image-assembly/framework-default-classification :event :rf.route/entry-denied)
        handler (fn [{:keys [db]} _] {:db db})
        auth    (rf.image/image {:id            :app/auth
                                 :registrations {:reg-event [[:rf.route/entry-denied
                                                              {:sensitive [[:note]]}
                                                              handler]]}})
        d       (rf.image-assembly/resolve-descriptor
                  (rf.image-assembly/assemble [this-ns-image auth]) :event :rf.route/entry-denied)]
    (is (seq (:sensitive fw)) "premise: routing's default declares its URL carriers")
    (is (= handler (:impl d)) "the inline override is the winner")
    (is (= (conj (vec (:sensitive fw)) [:note]) (:sensitive d))
        "the framework's carriers ride across the override, then the override's own")))

#?(:clj
   (deftest navigate-dispatches-in-an-explicit-frame
     (rf/reg-route :fwbase/home {} "/fwbase")
     (doseq [[label opts] [["default image"             {:id :fwbase/default-frame}]
                           ["explicit :select-ns image" {:id     :fwbase/explicit-frame
                                                         :images [this-ns-image]}]]]
       (testing label
         (let [f (rf/make-frame opts)]
           (try
             (is (nil? (err-id #(rf/dispatch-sync [:rf.route/navigate {:to :fwbase/home}]
                                                  {:frame f}))))
             (is (= :fwbase/home
                    (get-in (:rf.db/runtime (rf/frame-state-value f))
                            [:rf.runtime/routing :current :route-id]))
                 "the navigation committed")
             (finally
               (rf/destroy-frame! f))))))))
