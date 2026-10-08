(ns re-frame.frame-initial-events-cljs-test
  "`:initial-events` frame construction (EP-0027). A first `make-frame` runs
  the setup steps synchronously, in order, before it returns, each with the
  ordinary dispatch-sync opts and `:source :frame-init` provenance. Malformed
  shapes and the retired `:on-create` / `:initial-db` keys fail at preflight. A
  re-registration re-records the steps without replaying them; a destroy and a
  fresh `make-frame` replays them. Any failing step, whether it throws out of
  dispatch-sync or is captured in-band by the chain, tears the partial frame
  down and names the step. Construction inside a handler, directly or through
  an `:fx` `:dispatch`, fails `:rf.error/frame-construction-in-handler`.

  `:test/set-db` stands in for `:rf/set-db`. The provenance tags ride the dev
  trace, so they are read in a `(when rf.interop/debug-enabled? ...)` arm."
  (:require #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
            [re-frame.core         :as rf]
            [re-frame.interop      :as rf.interop]
            [re-frame.frame        :as rf.frame]
            [re-frame.image        :as rf.image]
            [re-frame.late-bind    :as rf.late-bind]
            [re-frame.live-frame   :as rf.live-frame]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- err-id
  "The `:rf.error/id` discriminator of a thrown re-frame2 error, or nil."
  [thunk]
  (try (thunk) nil
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
         (:rf.error/id (ex-data e)))))

(defn- err-data
  "The full `ex-data` of a thrown re-frame2 error, or nil."
  [thunk]
  (try (thunk) nil
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
         (ex-data e))))

(defn- reg-test-events!
  "Register `:test/set-db` and the small setup events the cases dispatch."
  []
  (rf/reg-event :test/set-db (fn [_ [_ new-db]] {:db new-db}))
  (rf/reg-event :test/inc    (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
  (rf/reg-event :test/add    (fn [{:keys [db]} [_ k v]] {:db (assoc db k v)}))
  (rf/reg-event :test/stamp-time
    {:rf.cofx/requires [:rf/time-ms]}
    (fn [{:keys [db rf/time-ms]} _]
      {:db (assoc db :stamped-at time-ms)}))
  (rf/reg-event :test/boom
    ;; in-band: dispatch-sync captures a handler throw and returns normally
    (fn [_ _] (throw (ex-info "setup blew up" {:kind :boom}))))
  (rf/reg-event :test/needs-missing-cofx
    ;; escaping: an unregistered cofx throws out of dispatch-sync
    {:rf.cofx/requires [:test/unregistered-cofx]}
    (fn [{:keys [db]} _] {:db (assoc db :ran true)})))

(deftest make-frame-initial-events-runs-sync-in-order-before-return
  (reg-test-events!)
  (rf/make-frame {:id :boot/seq :initial-events [[:test/set-db {:n 0 :log []}]
                                                 [:test/inc]
                                                 [:test/inc]
                                                 [:test/add :marker :done]]})
  (is (= {:n 2 :log [] :marker :done} (rf/app-db-value :boot/seq))))

(deftest initial-events-map-step-opts-honoured
  ;; a map step's :opts reach dispatch-sync, here as a deterministic clock
  (reg-test-events!)
  (rf/make-frame {:id :clock/main :initial-events [[:test/set-db {}]
                                                   {:event [:test/stamp-time]
                                                    :opts  {:rf.cofx {:rf/time-ms 1781078400123}}}]})
  (is (= 1781078400123 (:stamped-at (rf/app-db-value :clock/main)))))

(deftest initial-events-carry-construction-provenance
  (reg-test-events!)
  (let [dispatched (atom [])
        record!    (fn [ev]
                     (when (= :rf.event/dispatched (:operation ev))
                       (swap! dispatched conj ev)))]
    (rf/register-listener! :trace ::prov record!)
    (rf/make-frame {:id :prov/main :initial-events [[:test/set-db {:n 0}]
                                                    [:test/inc]]})
    (rf/unregister-listener! :trace ::prov)
    (is (= 1 (:n (rf/app-db-value :prov/main))) "both setup steps ran, in order")
    (when rf.interop/debug-enabled?
      (is (= [0 1] (keep #(when (= :frame-init (:source %))
                            (get-in % [:tags :rf.frame/init-step-index]))
                         @dispatched))
          "each setup dispatch is :source :frame-init with its 0-based step index")
      ;; a runtime dispatch is classified as such, not as frame-init
      (reset! dispatched [])
      (rf/register-listener! :trace ::prov2 record!)
      (rf/dispatch-sync [:test/inc] {:frame :prov/main :source :test})
      (rf/unregister-listener! :trace ::prov2)
      (is (= [:test nil] ((juxt :source #(get-in % [:tags :rf.frame/init-step-index]))
                          (first @dispatched)))))))

(deftest each-malformed-construction-config-fails-its-error-id-before-any-frame-exists
  (reg-test-events!)
  ;; [id config expected]
  (let [rows [[:bad/bare {:initial-events [:test/set-db {:n 0}]}
               :rf.error/initial-events-bare-event]
              [:bad/frame-opt {:initial-events [{:event [:test/set-db {}]
                                                 :opts  {:frame :somewhere-else}}]}
               :rf.error/initial-events-bad-opts]
              [:bad/opts-shape {:initial-events [{:event [:test/set-db {}] :opts :nope}]}
               :rf.error/initial-events-bad-opts]
              [:bad/step {:initial-events [42]}
               :rf.error/initial-events-bad-step]
              [:bad/step2 {:initial-events :not-a-vector}
               :rf.error/initial-events-bad-step]
              [:bad/empty {:initial-events [[]]}
               :rf.error/initial-events-bad-event]
              [:bad/no-event {:initial-events [{:opts {:rf.cofx {}}}]}
               :rf.error/initial-events-bad-event]
              [:bad/event-kw {:initial-events [{:event :not-a-vector}]}
               :rf.error/initial-events-bad-event]
              [:ret/oc {:on-create [:test/inc]}
               :rf.error/on-create-retired]
              [:ret/idb {:initial-db {:n 0}}
               :rf.error/initial-db-retired]]]
    (is (= (mapv last rows)
           (mapv (fn [[id config]] (err-id #(rf/make-frame (assoc config :id id)))) rows)))
    (is (= [] (filterv rf.frame/frame (map first rows)))
        "no refused construction left a frame registered")))

(deftest reset-frame-image-loaded-keeps-generation-inline-resolves
  ;; The setup steps resolve through the frame's image generation: the handlers
  ;; are inline-only, and a global :counter/seed decoy would write :global if
  ;; they resolved through the registrar. A destroy and re-make with the same
  ;; :images replays them the same way.
  (rf/reg-event :counter/seed
    (fn [_ [_ _new]] {:db {:written-by :global}}))
  (let [img    (rf.image/image
                 {:id :reset/inline-counter
                  :registrations
                  {:reg-event [[:counter/seed {:doc "Inline seed (image-only)."}
                                (fn [_ [_ n]] {:db {:written-by :inline :n n}})]
                               [:counter/inc {:doc "Inline inc (image-only)."}
                                (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))})]]}})
        make!  #(rf.live-frame/make-frame {:id             :reset/inline
                                           :images         [img]
                                           :initial-events [[:counter/seed 0]
                                                            [:counter/inc]]}
                                          [])]
    (make!)
    (let [constructed (rf/app-db-value :reset/inline)]
      (rf/dispatch-sync [:counter/inc] {:frame :reset/inline})
      (rf.frame/destroy-frame! :reset/inline)
      (make!)
      (is (= [{:written-by :inline :n 1} {:written-by :inline :n 1}]
             [constructed (rf/app-db-value :reset/inline)])))))

(deftest re-registration-re-records-but-does-not-replay
  (reg-test-events!)
  (let [n          #(:n (rf/app-db-value :remount/main))
        new-config {:id :remount/main :initial-events [[:test/set-db {:n 999}]]}]
    (rf/make-frame {:id :remount/main :initial-events [[:test/set-db {:n 0}]
                                                       [:test/inc]]})
    (rf/dispatch-sync [:test/inc] {:frame :remount/main})
    (let [moved (n)]
      (rf/make-frame new-config)
      (let [after-reregistration (n)]
        (rf.frame/destroy-frame! :remount/main)
        (rf/make-frame new-config)
        ;; the re-registration preserved durable app-db, and a later destroy
        ;; plus make replays the re-recorded setup
        (is (= [2 2 999] [moved after-reregistration (n)]))))))

(deftest a-failing-setup-step-tears-down-the-partial-frame-on-every-detection-route
  ;; strict construction: the runtime's traced-and-recover leniency does not
  ;; apply, whether the failure throws out of dispatch-sync or is captured
  ;; in-band by the chain
  (reg-test-events!)
  (doseq [[route id steps failing-event]
          [["escaping unregistered-cofx throw" :teardown/main
            [[:test/set-db {:n 0}] [:test/needs-missing-cofx] [:test/inc]]
            [:test/needs-missing-cofx]]
           ;; :rf/set-db raises inside the handler, so dispatch-sync returns
           ;; normally and only the always-on error axis shows the failure
           ["in-band [:rf/set-db :not-a-map]" :set-db-bad/main
            [[:rf/set-db {:n 0}] [:rf/set-db :not-a-map] [:test/inc]]
            [:rf/set-db :not-a-map]]
           ["in-band handler-body throw" :boom/main
            [[:test/set-db {:n 0}] [:test/boom] [:test/inc]]
            [:test/boom]]]]
    (let [data (err-data #(rf/make-frame {:id id :initial-events steps}))]
      (is (= [{:rf.error/id :rf.error/initial-events-step-failed :step-index 1 :event failing-event}
              nil]
             [(select-keys data [:rf.error/id :step-index :event]) (rf.frame/frame id)])
          (str route ": names the failing step and leaves no half-frame")))))

(deftest runner-unavailable-fails-loud-not-silent-drop
  ;; without re-frame.router the :router/dispatch-sync! hook is unbound; steps
  ;; to run then fail loud rather than being dropped, while no steps is a no-op
  (reg-test-events!)
  (let [orig (rf.late-bind/get-fn :router/dispatch-sync!)]
    (try
      (rf.late-bind/set-fn! :router/dispatch-sync! nil)
      (rf.late-bind/invalidate-cache! :router/dispatch-sync!)
      (let [data (err-data
                   #(rf/make-frame {:id :unavailable/main :initial-events [[:test/set-db {:n 0}]
                                                                           [:test/inc]]}))]
        (is (= [{:rf.error/id :rf.error/initial-events-runner-unavailable :step-count 2} nil]
               [(select-keys data [:rf.error/id :step-count]) (rf.frame/frame :unavailable/main)])))
      (rf/make-frame {:id :unavailable/empty :initial-events []})
      (is (some? (rf.frame/frame :unavailable/empty)))
      (finally
        (rf.late-bind/set-fn! :router/dispatch-sync! orig)
        (rf.late-bind/invalidate-cache! :router/dispatch-sync!)))))

(deftest construction-rollback-keeps-same-id-admission-closed
  ;; A setup-step rollback keeps the reservation through its exact-incarnation
  ;; teardown: a same-id B attempted after A's provisional row is removed, but
  ;; before A's rollback returns, still loses.
  (reg-test-events!)
  (let [id           :rollback/token-fence
        real-destroy rf.frame/destroy-frame!
        b-outcome    (atom ::not-called)
        injected?    (atom false)]
    (with-redefs
      [rf.frame/destroy-frame!
       (fn [& args]
         (if (and (= id (first args)) (not @injected?))
           (do
             (reset! injected? true)
             (real-destroy id (rf.frame/frame-incarnation-token id))
             (reset! b-outcome (err-id #(rf/make-frame {:id id})))
             (apply real-destroy args))
           (apply real-destroy args)))]
      (err-data
        #(rf/make-frame {:id id :initial-events [[:test/set-db {:n 0}]
                                                 [:test/boom]]})))
    (is (= [:rf.error/frame-construction-in-progress nil true]
           [@b-outcome (rf.frame/frame id) (some? (rf/make-frame {:id id}))])
        "B lost, A left no frame, and the released reservation admits a retry")))

(deftest frame-construction-in-handler-fails-loud
  (reg-test-events!)
  (let [caught (atom nil)]
    (rf/reg-event :handler/makes-frame
      (fn [{:keys [db]} _]
        (reset! caught
                (err-id #(rf/make-frame {:id :child/in-handler :initial-events [[:test/set-db {:n 0}]]})))
        {:db db}))
    (rf/make-frame {:id :parent/main :initial-events [[:test/set-db {}]]})
    (rf/dispatch-sync [:handler/makes-frame] {:frame :parent/main})
    (is (= [:rf.error/frame-construction-in-handler nil]
           [@caught (rf.frame/frame :child/in-handler)]))))

(deftest frame-construction-in-fx-dispatch-re-entry-fails-loud
  ;; the nested dispatch an :fx :dispatch queues runs inside the same cascade,
  ;; with *handler-scope* still bound
  (reg-test-events!)
  (let [caught (atom ::not-run)]
    (rf/reg-event :fx/makes-frame
      (fn [{:keys [db]} _]
        (reset! caught
                (err-id #(rf/make-frame {:id :child/via-fx :initial-events [[:test/set-db {:n 0}]]})))
        {:db db}))
    (rf/reg-event :handler/fx-makes-frame
      (fn [{:keys [db]} _]
        {:db db :fx [[:dispatch [:fx/makes-frame]]]}))
    (rf/make-frame {:id :parent/fx :initial-events [[:test/set-db {}]]})
    (rf/dispatch-sync [:handler/fx-makes-frame] {:frame :parent/fx})
    (is (= [:rf.error/frame-construction-in-handler nil]
           [@caught (rf.frame/frame :child/via-fx)]))))
