(ns re-frame.interceptor-runtime-complete-cljs-test
  "The registered-interceptor runtime on both hosts: the standard
  `:rf.interceptor/path` contract (Spec 002 rules 3 and 4, the root path, the
  bad-path error; rule 5 and nesting are in `re-frame.interceptor-test`),
  exact-reference `:interceptor-overrides`, and the registry's chain and
  factory resolution failures."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.interceptor :as rf.interceptor]
            [re-frame.interceptor-registry :as rf.interceptor-registry]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter}))

(defn- ex-data-of
  "The ex-data `f` throws, or nil when it returns."
  [f]
  (try (f)
       nil
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo) e
         (ex-data e))))

;; ---- standard :rf.interceptor/path -----------------------------------------

(deftest path-unchanged-db-keeps-the-app-db-object-identical
  ;; Rule 4 (an unchanged slice re-emits the original app-db object) and rule 3
  ;; (no `:db` effect, no synthetic one) keep the commit's `identical?` no-op.
  (doseq [[label handler] [["rule 4: the slice returned unchanged" (fn [{:keys [db]} _] {:db db})]
                           ["rule 3: no :db effect" (fn [_ _] {})]]]
    (testing label
      (rf/reg-event :noop-path/seed
        (fn [{:keys [db]} _] {:db (assoc db :cart {:items [:milk :eggs]})}))
      (rf/reg-event :noop-path/touch
        {:interceptors [[:rf.interceptor/path [:cart]]]}
        handler)
      (rf/dispatch-sync [:noop-path/seed])
      (let [before (rf/app-db-value :rf/default)]
        (rf/dispatch-sync [:noop-path/touch])
        (is (identical? before (rf/app-db-value :rf/default)))))))

(deftest path-root-path-focuses-whole-db
  (rf/reg-event :root/seed
    (fn [{:keys [db]} _] {:db (assoc db :seeded? true)}))
  (rf/reg-event :root/assoc
    {:interceptors [[:rf.interceptor/path []]]}
    (fn [{:keys [db]} _] {:db (assoc db :root-wrote? true)}))
  (rf/dispatch-sync [:root/seed])
  (rf/dispatch-sync [:root/assoc])
  (let [db (rf/app-db-value :rf/default)]
    (is (and (:seeded? db) (:root-wrote? db))
        "the root focus saw and wrote the whole db")))

(deftest path-bad-path-arg-is-structured-error
  (is (thrown-with-msg?
        #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo)
        #":rf.error/path-interceptor-bad-path"
        (rf/reg-event :bad/path
          {:interceptors [[:rf.interceptor/path :not-a-vector]]}
          (fn [{:keys [db]} _] {:db db})))))

;; ---- :interceptor-overrides exact-reference matching -----------------------

(deftest override-matches-exact-parameterized-ref-not-a-sibling
  (rf/reg-interceptor :ov/tag
    {:factory (fn [tag]
                {:before (fn [ctx]
                           (update-in ctx [:coeffects :db ::seen] (fnil conj []) tag))})})
  (rf/reg-event :ov/run
    {:interceptors [[:ov/tag :a]
                    [:ov/tag :b]]}
    (fn [{:keys [db]} _] {:db db}))
  (rf/dispatch-sync [:ov/run] {:interceptor-overrides {[:ov/tag :a] nil}})
  (is (= [:b] (::seen (rf/app-db-value :rf/default)))
      "only the exact [:ov/tag :a] reference was removed"))

(deftest override-malformed-key-is-structured-error
  (rf/reg-interceptor :ov/ok {:before identity})
  (rf/reg-event :ov/run3
    {:interceptors [:ov/ok]}
    (fn [{:keys [db]} _] {:db db}))
  (is (thrown-with-msg?
        #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo)
        #":rf.error/interceptor-override-invalid"
        (rf/dispatch-sync [:ov/run3] {:interceptor-overrides {"not-a-ref" nil}}))))

;; ---- registry resolution ---------------------------------------------------

(deftest resolve-chain-rejects-inline-values-and-malformed-entries
  ;; `make-frame` validates a frame's `:interceptors` chain through
  ;; `resolve-chain`, so these are the frame-chain errors; `reg-event` rejects
  ;; the same entries earlier, at `:where rf/reg-event`.
  (let [inline (rf.interceptor/->interceptor* :id :stale/inline :before identity :after identity)]
    (is (= {:rf.error/id :rf.error/inline-interceptor-removed
            :where       'rf/resolve-chain
            :entry       inline}
           (select-keys (ex-data-of #(rf.interceptor-registry/resolve-chain [inline]))
                        [:rf.error/id :where :entry])))
    (is (= {:rf.error/id :rf.error/invalid-interceptor-ref
            :ref         "not-a-ref"}
           (select-keys (ex-data-of #(rf.interceptor-registry/resolve-chain ["not-a-ref"]))
                        [:rf.error/id :ref])))))

(deftest resolve-factory-wraps-unexpected-failures-and-passes-rf-errors-through
  ;; A factory's own `:rf.error/*` throw propagates verbatim; any other throw,
  ;; or a return that is neither a descriptor nor an interceptor value, is
  ;; `:rf.error/interceptor-factory-arity`.
  (doseq [[id factory expected]
          [[:fac/boom (fn [_] (throw (ex-info "kaboom" {})))
            :rf.error/interceptor-factory-arity]
           [:fac/custom-err (fn [_] (throw (ex-info "custom"
                                                    {:rf.error/id :rf.error/my-custom-factory-error})))
            :rf.error/my-custom-factory-error]
           [:fac/garbage (fn [_] {})
            :rf.error/interceptor-factory-arity]]]
    (rf/reg-interceptor id {:factory factory})
    (is (= expected (:rf.error/id (ex-data-of #(rf.interceptor-registry/resolve-ref [id :x]))))
        (str id)))
  (is (re-find #"kaboom" (:reason (ex-data-of #(rf.interceptor-registry/resolve-ref [:fac/boom :x]))))
      "the wrapped reason keeps the factory's own message"))
