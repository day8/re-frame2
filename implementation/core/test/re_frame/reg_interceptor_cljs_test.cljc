(ns re-frame.reg-interceptor-cljs-test
  "EP-0022 — the `:interceptor` registrar + reg-interceptor + by-reference chain
  resolution. REFERENCE-ONLY since the flip (rf2-0adhqs.9).

  Adversarial coverage per the bead:
    1. reg-interceptor registers EACH descriptor form (:before / :after /
       :before+:after / :factory; one-arg factory).
    2. A chain with a BARE-KEYWORD ref AND an `[id arg]` ref resolves + runs
       in declaration order (with the standard `:rf.interceptor/path` factory).
    3. An INLINE interceptor value in a chain is REJECTED
       (`:rf.error/inline-interceptor-removed`) — the additive window is closed;
       the migration path is register + reference by id.
    4. Realm + reg-event interplay — refs resolve through the active
       (realm-bound) registrar; an unknown ref is rejected at registration.

  Dual-runtime (.cljc): runs on JVM (`clojure -M:test`) and CLJS
  (`npm run test:cljs`)."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.interceptor :as interceptor]
            [re-frame.interceptor-registry :as icpt-reg]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]))

;; ---- fixtures -------------------------------------------------------------
;;
;; Use the canonical `make-reset-runtime-fixture` (NOT a bespoke `init!` reset):
;; it force-installs the plain-atom adapter (so a sibling CLJS test's reagent
;; adapter is not stranded by `init!`'s install-once semantics) AND captures
;; the ns-load registrar baseline — which includes the standard
;; `:rf.interceptor/path` registration `re-frame.std-interceptors` performs at
;; load — so refs to it resolve in every test.

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter plain-atom/adapter}))

;; ---------------------------------------------------------------------------
;; 1. reg-interceptor registers each descriptor form
;; ---------------------------------------------------------------------------

(deftest reg-interceptor-each-descriptor-form
  (testing ":before-only descriptor"
    (rf/reg-interceptor :t/before {:doc "b"} {:before (fn [ctx] ctx)})
    (let [m (rf/handler-meta :interceptor :t/before)]
      (is (some? m))
      (is (contains? (:rf/interceptor-descriptor m) :before))))

  (testing ":after-only descriptor"
    (rf/reg-interceptor :t/after {:after (fn [ctx] ctx)})
    (is (some? (rf/handler-meta :interceptor :t/after))))

  (testing ":before + :after descriptor"
    (rf/reg-interceptor :t/both {:before (fn [ctx] ctx) :after (fn [ctx] ctx)})
    (let [d (:rf/interceptor-descriptor (rf/handler-meta :interceptor :t/both))]
      (is (and (contains? d :before) (contains? d :after)))))

  (testing ":factory descriptor (one-arg factory)"
    (rf/reg-interceptor :t/factory
      {:factory (fn [arg] {:before (fn [ctx] (assoc ctx :arg arg))})})
    (let [d (:rf/interceptor-descriptor (rf/handler-meta :interceptor :t/factory))]
      (is (contains? d :factory))))

  (testing "reg-interceptor* returns its id"
    (is (= :t/ret (rf/reg-interceptor :t/ret {:before identity}))))

  (testing "malformed descriptor is :rf.error/invalid-interceptor"
    (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo)
                          #":rf.error/invalid-interceptor"
                          (rf/reg-interceptor :t/bad {:doc "no executable slot"})))
    (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo)
                          #":rf.error/invalid-interceptor"
                          (rf/reg-interceptor :t/bad2 :not-a-map))))

  ;; rf2-pot53n — a descriptor carrying BOTH :factory AND a static slot
  ;; (:before / :after) is AMBIGUOUS and rejected. valid-descriptor?'s
  ;; factory-descriptor? branch returns (not (static-descriptor? descriptor)),
  ;; so the :factory+:before mix fails validation. The existing "malformed
  ;; descriptor" coverage above only hits the no-executable-slot ({:doc …}) and
  ;; non-map legs; this pins the ambiguity-mix rejection arm.
  (testing "ambiguous :factory + :before descriptor is :rf.error/invalid-interceptor"
    (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo)
                          #":rf.error/invalid-interceptor"
                          (rf/reg-interceptor :t/ambig
                            {:factory (fn [_] {:before identity})
                             :before  (fn [ctx] ctx)}))
        ":factory + :before in the same map is ambiguous — rejected"))

  (testing "ambiguous :factory + :after descriptor is :rf.error/invalid-interceptor"
    (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo)
                          #":rf.error/invalid-interceptor"
                          (rf/reg-interceptor :t/ambig2
                            {:factory (fn [_] {:after identity})
                             :after   (fn [ctx] ctx)}))
        ":factory + :after in the same map is ambiguous — rejected"))

  (testing "migration boundary: interceptor VALUE with mismatched :id is rejected"
    (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo)
                          #":rf.error/invalid-interceptor"
                          (rf/reg-interceptor :t/x
                            (interceptor/->interceptor* :id :different :before identity)))))

  (testing "migration boundary: interceptor VALUE with matching :id is accepted"
    (is (= :t/legacy
           (rf/reg-interceptor :t/legacy
             (interceptor/->interceptor* :id :t/legacy :before identity))))))

;; ---------------------------------------------------------------------------
;; 2. bare-keyword ref + [id arg] ref resolve + run in order
;; ---------------------------------------------------------------------------

(deftest bare-and-factory-refs-resolve-and-run-in-order
  (testing "a chain of [bare-keyword-ref [id arg]-factory-ref] runs in declaration order"
    (let [log (atom [])]
      ;; A bare-keyword static interceptor that logs.
      (rf/reg-interceptor :order/log-a
        {:before (fn [ctx] (swap! log conj [:a :before]) ctx)
         :after  (fn [ctx] (swap! log conj [:a :after]) ctx)})
      ;; A project factory interceptor referenced as [:order/log-factory tag].
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
             @log)
          ":before runs in order, handler, :after in reverse — refs resolved"))))

(deftest standard-path-factory-ref-resolves
  (testing "[:rf.interceptor/path [...]] resolves to the standard path interceptor and focuses the slice"
    (rf/reg-event :path/inc
      {:interceptors [[:rf.interceptor/path [:counter]]]}
      (fn [{:keys [db]} _]
        ;; The handler sees the FOCUSED slice as :db (the value at [:counter]).
        {:db (inc db)}))

    ;; Seed the focused slice.
    (rf/reg-event :path/seed (fn [{:keys [db]} _] {:db (assoc db :counter 0)}))
    (rf/dispatch-sync [:path/seed])
    (rf/dispatch-sync [:path/inc])
    (rf/dispatch-sync [:path/inc])

    (is (= 2 (:counter (rf/app-db-value :rf/default)))
        "the path factory ref focused the handler on [:counter] and spliced back")))

;; ---------------------------------------------------------------------------
;; 3. inline interceptor values in a chain are REJECTED (reference-only flip,
;;    rf2-0adhqs.9) — the additive window is closed
;; ---------------------------------------------------------------------------

(deftest inline-value-in-chain-rejected
  (testing "an inline interceptor value in a chain is :rf.error/inline-interceptor-removed at registration"
    (let [inline (interceptor/->interceptor*
                   :id     :inline/log
                   :before (fn [ctx] ctx)
                   :after  (fn [ctx] ctx))]
      (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo)
                            #":rf.error/inline-interceptor-removed"
                            (rf/reg-event :inline/run
                              {:interceptors [inline]}
                              (fn [{:keys [db]} _] {:db db})))
          "register the interceptor with reg-interceptor and reference it by id"))))

(deftest mixed-ref-and-inline-value-rejected
  (testing "a chain mixing a registered ref AND an inline value still fails on the inline value"
    (let [inline (interceptor/->interceptor*
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
                              (fn [{:keys [db]} _] {:db db})))
          "the registered ref is legal but the inline value is rejected — chains are reference-only"))))

(deftest registered-then-referenced-runs
  (testing "the EP-0022 path: register the formerly-inline interceptor, then reference it by id — both run"
    (let [log (atom [])]
      (rf/reg-interceptor :was-inline/log
        {:before (fn [ctx] (swap! log conj [:inline :before]) ctx)
         :after  (fn [ctx] (swap! log conj [:inline :after]) ctx)})
      (rf/reg-interceptor :mix/ref
        {:before (fn [ctx] (swap! log conj [:ref :before]) ctx)
         :after  (fn [ctx] (swap! log conj [:ref :after]) ctx)})
      (rf/reg-event :mix/run
        {:interceptors [:mix/ref :was-inline/log]}
        (fn [{:keys [db]} _] {:db db}))
      (rf/dispatch-sync [:mix/run])
      (is (= [[:ref :before]
              [:inline :before]
              [:inline :after]
              [:ref :after]]
             @log)
          "both refs resolved + ran in order"))))

(deftest frame-level-interceptor-ref-chain
  (testing "a frame-level :interceptors ref chain prepends to the event chain"
    (let [log (atom [])]
      (rf/reg-interceptor :frame/log
        {:before (fn [ctx] (swap! log conj [:frame :before]) ctx)
         :after  (fn [ctx] (swap! log conj [:frame :after]) ctx)})
      (rf/reg-interceptor :evt/log
        {:before (fn [ctx] (swap! log conj [:event :before]) ctx)
         :after  (fn [ctx] (swap! log conj [:event :after]) ctx)})
      (rf/reg-frame :test/framed {:interceptors [:frame/log]})
      (rf/reg-event :framed/run
        {:interceptors [:evt/log]}
        (fn [{:keys [db]} _] {:db db}))
      (rf/dispatch-sync [:framed/run] {:frame :test/framed})
      (is (= [[:frame :before]
              [:event :before]
              [:event :after]
              [:frame :after]]
             @log)
          "frame refs prepend the event refs (effective chain ordering)"))))

;; ---------------------------------------------------------------------------
;; 4. validation timing + realm-aware resolution
;; ---------------------------------------------------------------------------

(deftest unknown-ref-rejected-at-registration
  (testing "reg-event referencing an unregistered interceptor throws at registration"
    (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo)
                          #":rf.error/unregistered-interceptor"
                          (rf/reg-event :bad/run
                            {:interceptors [:nope/absent]}
                            (fn [{:keys [db]} _] {:db db}))))))

(deftest bare-ref-to-factory-rejected
  (testing "a bare-keyword ref to a :factory interceptor is :rf.error/interceptor-factory-arity"
    (rf/reg-interceptor :fac/only {:factory (fn [_] {:before identity})})
    (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo)
                          #":rf.error/interceptor-factory-arity"
                          (rf/reg-event :bad/fac
                            {:interceptors [:fac/only]}
                            (fn [{:keys [db]} _] {:db db}))))))

(deftest factory-ref-to-static-rejected
  (testing "an [id arg] ref to a STATIC interceptor is :rf.error/interceptor-factory-arity"
    (rf/reg-interceptor :static/only {:before identity})
    (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo)
                          #":rf.error/interceptor-factory-arity"
                          (rf/reg-event :bad/static
                            {:interceptors [[:static/only :arg]]}
                            (fn [{:keys [db]} _] {:db db}))))))

(deftest hot-reload-picks-up-new-descriptor
  (testing "re-registering an interceptor descriptor is picked up on the next dispatch (resolution at assembly)"
    (let [log (atom [])]
      (rf/reg-interceptor :hot/log {:before (fn [ctx] (swap! log conj :v1) ctx)})
      (rf/reg-event :hot/run
        {:interceptors [:hot/log]}
        (fn [{:keys [db]} _] {:db db}))
      (rf/dispatch-sync [:hot/run])
      ;; hot reload — re-register WITHOUT re-registering the event
      (rf/reg-interceptor :hot/log {:before (fn [ctx] (swap! log conj :v2) ctx)})
      (rf/dispatch-sync [:hot/run])
      (is (= [:v1 :v2] @log)
          "the next dispatch used the re-registered descriptor"))))

(deftest realm-aware-ref-resolution
  (testing "resolve-ref resolves through the active (realm-bound) registrar"
    ;; Register :rsa/icpt ONLY in a separate realm registrar atom.
    (let [realm-reg (atom {})]
      (binding [registrar/*registrar* realm-reg]
        (rf/reg-interceptor :rsa/icpt {:before identity}))
      ;; In the DEFAULT realm, :rsa/icpt is absent — resolution fails.
      (is (nil? (registrar/lookup :interceptor :rsa/icpt))
          "default realm does not see the realm-scoped interceptor")
      (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo)
                            #":rf.error/unregistered-interceptor"
                            (icpt-reg/resolve-ref :rsa/icpt)))
      ;; Bound to the realm registrar, the SAME ref resolves to its value.
      (binding [registrar/*registrar* realm-reg]
        (let [resolved (icpt-reg/resolve-ref :rsa/icpt)]
          (is (= :rsa/icpt (:id resolved)))
          (is (fn? (:before resolved))
              "the realm registrar resolved the ref to its executable interceptor"))))))

(deftest realm-and-reg-event-interplay
  (testing "an event registered + dispatched within a realm resolves its refs through that realm"
    (let [realm-reg (atom {})
          log       (atom [])]
      ;; Seat both the interceptor AND the event into the realm registrar.
      (binding [registrar/*registrar* realm-reg]
        (rf/reg-interceptor :rint/log
          {:before (fn [ctx] (swap! log conj :realm-icpt) ctx)})
        ;; reg-event's registration-time ref validation must resolve through
        ;; the realm registrar too (the ref lives only there).
        (rf/reg-event :rint/run
          {:interceptors [:rint/log]}
          (fn [{:keys [db]} _] {:db db})))
      ;; The event lives only in the realm registrar; the default cannot see it.
      (is (nil? (registrar/lookup :event :rint/run)))
      (is (some? (get-in @realm-reg [:event :rint/run]))
          "the event + interceptor are seated in the realm registrar"))))
