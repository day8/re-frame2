(ns re-frame.image-framework-base-features-cljs-test
  "The framework's own feature registrations reach a frame built from an
  explicit `:select-ns` image. Each is registered by its owning artefact at ns
  load with no source namespace, so no glob can select it; without the
  framework base an explicit-image frame could not resolve routing, managed
  HTTP, Resources, SSR hydration, the machine hydrate re-arm, or core's own
  `:rf/time-ms`, while the default image works. Each row reads the registration
  its producer actually made, beside the default-image control.

  The pure-pool half is `re-frame.image-framework-base-cljs-test`. The routing
  dispatch is JVM-only: on the node lane the platform is `:client`, where
  navigation drives the host history API."
  (:require #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
            [re-frame.core :as rf]
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

;; something for this-ns-image to select: a zero-match :include fails loud
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
  "One registration per producer artefact."
  [[:event :rf.route/navigate]         ;; routing
   [:view  :route/link]                ;; the one framework id outside the :rf root
   [:fx    :rf.http/managed]           ;; managed HTTP
   [:sub   :rf/resource]               ;; Resources
   [:fx    :rf.machine/hydrate-rearm]  ;; machines, not one of its standards
   [:event :rf/hydrate]                ;; SSR
   [:cofx  :rf/time-ms]])              ;; core

(deftest framework-feature-handlers-resolve-in-an-explicit-generation
  (let [default  (rf.image-assembly/assemble-default)
        explicit (rf.image-assembly/assemble [this-ns-image])]
    ;; per id: recorded with no source ns, resolved by the default image (the
    ;; control), and resolved by the explicit image
    (is (= [] (keep (fn [[kind id]]
                      (let [r [(contains? (rf.source-store/descriptors-for kind id) nil)
                               (some? (rf.image-assembly/resolve-descriptor default kind id))
                               (some? (rf.image-assembly/resolve-descriptor explicit kind id))]]
                        (when (not= [true true true] r) [kind id r])))
                    framework-ids)))))

(deftest a-handler-requiring-time-ms-runs-in-an-explicit-frame
  (rf/reg-event :fwbase/plain
    (fn [{:keys [db]} _] {:db (assoc db :plain true)}))
  (rf/reg-event :fwbase/stamp
    {:rf.cofx/requires [:rf/time-ms]}
    (fn [{:keys [db rf/time-ms]} _] {:db (assoc db :stamped time-ms)}))
  (doseq [[label opts] [["default image"             {:id :fwbase/default-frame}]
                        ["explicit :select-ns image" {:id     :fwbase/explicit-frame
                                                      :images [this-ns-image]}]]]
    (let [f (rf/make-frame opts)]
      (try
        ;; a handler with no requirement runs (the control), and the provided
        ;; :rf/time-ms coeffect satisfies the requirement
        (is (= [nil nil true true]
               [(err-id #(rf/dispatch-sync [:fwbase/plain] {:frame f}))
                (err-id #(rf/dispatch-sync [:fwbase/stamp] {:frame f}))
                (:plain (rf/app-db-value f))
                (number? (:stamped (rf/app-db-value f)))])
            label)
        (finally
          (rf/destroy-frame! f))))))

(deftest an-overrides-image-inlining-managed-http-wins-and-is-reported
  (let [double    (fn [_ctx _args] :double)
        overrides (rf.image/image {:id            :test/doubles
                                   :registrations {:reg-fx [[:rf.http/managed double]]}})
        gen       (rf.image-assembly/assemble [this-ns-image overrides])]
    (is (= [double [{:registration [:fx :rf.http/managed]
                     :image        :rf/framework
                     :shadowed-by  :test/doubles}]]
           [(:handler-fn (rf.image-assembly/resolve-descriptor gen :fx :rf.http/managed))
            (shadows-of gen [:fx :rf.http/managed])]))))

(deftest the-entry-denied-default-resolves-and-an-app-override-shadows-it
  ;; with no override, Spec 012's denial dispatch never fails :rf.error/no-such-handler
  (is (true? (:rf/framework-default?
               (rf.image-assembly/resolve-descriptor
                 (rf.image-assembly/assemble [this-ns-image]) :event :rf.route/entry-denied))))
  (rf/reg-event :rf.route/entry-denied (fn [{:keys [db]} _] {:db (assoc db :denied true)}))
  (let [gen (rf.image-assembly/assemble [this-ns-image])]
    (is (= [nil [{:registration [:event :rf.route/entry-denied]
                  :image        :rf/framework
                  :shadowed-by  :fwbase/app}]]
           [(:rf/framework-default?
              (rf.image-assembly/resolve-descriptor gen :event :rf.route/entry-denied))
            (shadows-of gen [:event :rf.route/entry-denied])])
        "an app image selecting its own entry-denied wins, shadowing :rf/framework")))

(deftest an-inline-entry-denied-override-keeps-the-framework-carriers
  (let [fw      (rf.image-assembly/framework-default-classification :event :rf.route/entry-denied)
        handler (fn [{:keys [db]} _] {:db db})
        auth    (rf.image/image {:id            :app/auth
                                 :registrations {:reg-event [[:rf.route/entry-denied
                                                              {:sensitive [[:note]]}
                                                              handler]]}})
        d       (rf.image-assembly/resolve-descriptor
                  (rf.image-assembly/assemble [this-ns-image auth]) :event :rf.route/entry-denied)]
    ;; routing's default declares its URL carriers; they ride across the
    ;; override, then the override's own
    (is (= [true handler (conj (vec (:sensitive fw)) [:note])]
           [(boolean (seq (:sensitive fw))) (:impl d) (:sensitive d)]))))

#?(:clj
   (deftest navigate-dispatches-in-an-explicit-frame
     (rf/reg-route :fwbase/home {} "/fwbase")
     (doseq [[label opts] [["default image"             {:id :fwbase/default-frame}]
                           ["explicit :select-ns image" {:id     :fwbase/explicit-frame
                                                         :images [this-ns-image]}]]]
       (let [f (rf/make-frame opts)]
         (try
           (is (= [nil :fwbase/home]
                  [(err-id #(rf/dispatch-sync [:rf.route/navigate {:to :fwbase/home}]
                                              {:frame f}))
                   (get-in (:rf.db/runtime (rf/frame-state-value f))
                           [:rf.runtime/routing :current :route-id])])
               (str label ": the navigation committed"))
           (finally
             (rf/destroy-frame! f)))))))
