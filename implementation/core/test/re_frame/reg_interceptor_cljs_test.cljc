(ns re-frame.reg-interceptor-cljs-test
  "The `:interceptor` registrar, `reg-interceptor`, and by-reference chain
  resolution: chains are reference-only, refs resolve in declaration order and
  are validated at registration, and a descriptor-authored interceptor carries
  its registration coord onto the built value."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.interceptor :as rf.interceptor]
            [re-frame.interceptor-registry :as rf.interceptor-registry]
            [re-frame.interop :as rf.interop]
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

;; the ns-load baseline includes the standard :rf.interceptor/path registration

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter}))

(deftest reg-interceptor-each-descriptor-form
  ;; the four valid descriptor forms are run by the chain-order tests below
  (is (= :t/ret (rf/reg-interceptor :t/ret {:before identity})))
  (testing "a malformed descriptor is :rf.error/invalid-interceptor"
    (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo)
                          #":rf.error/invalid-interceptor"
                          (rf/reg-interceptor :t/bad {:doc "no executable slot"})))
    (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo)
                          #":rf.error/invalid-interceptor"
                          (rf/reg-interceptor :t/bad2 :not-a-map)))
    (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo)
                          #":rf.error/invalid-interceptor"
                          (rf/reg-interceptor :t/ambig
                            {:factory (fn [_] {:before identity})
                             :before  (fn [ctx] ctx)}))
        ":factory beside a static slot is ambiguous"))
  (testing "a migration-boundary interceptor VALUE must carry the registered :id"
    (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo)
                          #":rf.error/invalid-interceptor"
                          (rf/reg-interceptor :t/x
                            (rf.interceptor/->interceptor* :id :different :before identity))))
    (is (= :t/legacy
           (rf/reg-interceptor :t/legacy
             (rf.interceptor/->interceptor* :id :t/legacy :before identity))))))

(deftest bare-and-factory-refs-resolve-and-run-in-order
  (let [log (atom [])]
    (rf/reg-interceptor :order/log-a
      {:before (fn [ctx] (swap! log conj [:a :before]) ctx)
       :after  (fn [ctx] (swap! log conj [:a :after]) ctx)})
    (rf/reg-interceptor :order/log-factory
      {:factory (fn [tag]
                  {:before (fn [ctx] (swap! log conj [tag :before]) ctx)
                   :after  (fn [ctx] (swap! log conj [tag :after]) ctx)})})
    (rf/reg-event :order/run
      {:interceptors [:order/log-a
                      [:order/log-factory :b]]}
      (fn [{:keys [db]} _]
        (swap! log conj [:handler :ran])
        {:db (assoc db :ran? true)}))
    (rf/dispatch-sync [:order/run])
    (is (= [[:a :before]
            [:b :before]
            [:handler :ran]
            [:b :after]
            [:a :after]]
           @log))))

(deftest mixed-ref-and-inline-value-rejected
  (let [inline (rf.interceptor/->interceptor*
                 :id     :mix/inline
                 :before (fn [ctx] ctx)
                 :after  (fn [ctx] ctx))]
    (rf/reg-interceptor :mix/ref
      {:before (fn [ctx] ctx)
       :after  (fn [ctx] ctx)})
    (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo)
                          #":rf.error/inline-interceptor-removed"
                          (rf/reg-event :mix/run
                            {:interceptors [:mix/ref inline]}
                            (fn [{:keys [db]} _] {:db db}))))))

(deftest frame-level-interceptor-ref-chain
  (let [log (atom [])]
    (rf/reg-interceptor :frame/log
      {:before (fn [ctx] (swap! log conj [:frame :before]) ctx)
       :after  (fn [ctx] (swap! log conj [:frame :after]) ctx)})
    (rf/reg-interceptor :evt/log
      {:before (fn [ctx] (swap! log conj [:event :before]) ctx)
       :after  (fn [ctx] (swap! log conj [:event :after]) ctx)})
    (rf/make-frame {:id :test/framed :interceptors [:frame/log]})
    (rf/reg-event :framed/run
      {:interceptors [:evt/log]}
      (fn [{:keys [db]} _] {:db db}))
    (rf/dispatch-sync [:framed/run] {:frame :test/framed})
    (is (= [[:frame :before]
            [:event :before]
            [:event :after]
            [:frame :after]]
           @log)
        "frame refs wrap the event refs")))

(deftest unknown-ref-rejected-at-registration
  (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo)
                        #":rf.error/unregistered-interceptor"
                        (rf/reg-event :bad/run
                          {:interceptors [:nope/absent]}
                          (fn [{:keys [db]} _] {:db db})))))

(deftest unknown-frame-ref-rejected-at-make-frame
  (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo)
                        #":rf.error/unregistered-interceptor"
                        (rf/make-frame {:id :test/bad-frame :interceptors [:nope/absent]})))
  (is (not (contains? (set (rf/frame-ids)) :test/bad-frame)) "no half-created frame")
  (rf/reg-interceptor :frame/ok {:before identity})
  (rf/make-frame {:id :test/good-frame
                  :interceptors [:frame/ok [:rf.interceptor/path [:slice]]]})
  (is (contains? (set (rf/frame-ids)) :test/good-frame)
      "a frame chain with a factory ref among its refs constructs"))

(deftest bare-ref-to-factory-rejected
  (rf/reg-interceptor :fac/only {:factory (fn [_] {:before identity})})
  (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo)
                        #":rf.error/interceptor-factory-arity"
                        (rf/reg-event :bad/fac
                          {:interceptors [:fac/only]}
                          (fn [{:keys [db]} _] {:db db})))))

(deftest factory-ref-to-static-rejected
  (rf/reg-interceptor :static/only {:before identity})
  (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo)
                        #":rf.error/interceptor-factory-arity"
                        (rf/reg-event :bad/static
                          {:interceptors [[:static/only :arg]]}
                          (fn [{:keys [db]} _] {:db db})))))

(deftest hot-reload-picks-up-new-descriptor
  ;; refs resolve at chain assembly, so a re-registered descriptor is used
  ;; without re-registering the event
  (let [log (atom [])]
    (rf/reg-interceptor :hot/log {:before (fn [ctx] (swap! log conj :v1) ctx)})
    (rf/reg-event :hot/run
      {:interceptors [:hot/log]}
      (fn [{:keys [db]} _] {:db db}))
    (rf/dispatch-sync [:hot/run])
    (rf/reg-interceptor :hot/log {:before (fn [ctx] (swap! log conj :v2) ctx)})
    (rf/dispatch-sync [:hot/run])
    (is (= [:v1 :v2] @log))))

(deftest reg-event-validates-refs-and-seats-in-the-bound-realm-registrar
  (let [realm-reg (atom {})]
    (binding [rf.registrar/*registrar* realm-reg]
      (rf/reg-interceptor :rint/log
        {:before (fn [ctx] ctx)})
      ;; the ref lives only in the realm, so validation must resolve through it
      (rf/reg-event :rint/run
        {:interceptors [:rint/log]}
        (fn [{:keys [db]} _] {:db db})))
    (is (nil? (rf.registrar/lookup :event :rint/run)))
    (is (some? (get-in @realm-reg [:event :rint/run])))))

;; ---------------------------------------------------------------------------
;; 5. registration coords ride resolution onto the exception trace
;; ---------------------------------------------------------------------------
;;
;; The reg-interceptor macro captures its call-site coord into the registry
;; meta and the resolver stamps it on the built value as :source-coord, the
;; slot Xray's jump-to-source chip reads. Trace and public registry coords are
;; dev-only, so the coord assertions sit in debug-enabled? arms.

(defn- capture-error-traces
  "Dispatch `event` and return the `:op-type :error` traces it emitted."
  [event]
  (let [traces (atom [])]
    (rf/register-listener! :trace ::tq26u
                           (fn [ev] (when (= :error (:op-type ev))
                                      (swap! traces conj ev))))
    (rf/dispatch-sync event)
    (rf/unregister-listener! :trace ::tq26u)
    @traces))

(defn- registered-coord [id]
  (select-keys (rf/handler-meta {:source :store :kind :interceptor :id id}) [:ns :file :line :column]))

(deftest descriptor-authored-coord-rides-the-exception-trace
  (rf/reg-interceptor :tq26u/boom
    {:before (fn [_] (throw (ex-info "descriptor before blew up" {})))})
  (rf/reg-event :tq26u/run
    {:interceptors [:tq26u/boom]}
    (fn [{:keys [db]} _] {:db db}))
  (let [errs (capture-error-traces [:tq26u/run])]
    (when rf.interop/debug-enabled?
      (let [coord (registered-coord :tq26u/boom)]
        (is (int? (:line coord)) "the macro captured a coord")
        (is (= [coord]
               (->> errs
                    (filter #(= :rf.error/interceptor-exception (:operation %)))
                    (mapv #(get-in % [:tags :source-coord])))))))))

(deftest factory-authored-coord-rides-the-built-value
  (rf/reg-interceptor :tq26u/fac
    {:factory (fn [tag] {:before (fn [ctx] (assoc ctx :tag tag))})})
  (when rf.interop/debug-enabled?
    (is (= [:tq26u/fac (registered-coord :tq26u/fac)]
           ((juxt :id :source-coord) (rf.interceptor-registry/resolve-ref [:tq26u/fac :x]))))))

(deftest programmatic-registration-resolves-coord-free
  ;; the plain reg-interceptor* fn captures no coord, so none is stamped
  (rf.interceptor-registry/reg-interceptor* :tq26u/plain {:before identity})
  (is (= [:tq26u/plain nil]
         ((juxt :id :source-coord) (rf.interceptor-registry/resolve-ref :tq26u/plain)))))

(deftest migration-value-coord-precedence
  (let [own-coord {:ns 'tq26u.own :file "tq26u/own.cljc" :line 7 :column 3}]
    (rf/reg-interceptor :tq26u/own-coord
      (rf.interceptor/->interceptor* :id :tq26u/own-coord :before identity
                                     :source-coord own-coord))
    (is (= own-coord (:source-coord (rf.interceptor-registry/resolve-ref :tq26u/own-coord)))
        "a value's own coord beats the registration coord")
    (rf/reg-interceptor :tq26u/no-coord
      (rf.interceptor/->interceptor* :id :tq26u/no-coord :before identity))
    (when rf.interop/debug-enabled?
      (is (= (registered-coord :tq26u/no-coord)
             (:source-coord (rf.interceptor-registry/resolve-ref :tq26u/no-coord)))
          "without one, the registration coord is the fallback"))))
