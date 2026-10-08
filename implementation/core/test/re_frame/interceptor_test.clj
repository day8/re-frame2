(ns re-frame.interceptor-test
  "The interceptor chain runtime and the standard `:rf.interceptor/path`
  interceptor (Spec 002), on the JVM plain-atom adapter.

  Everything here runs in both postures (`clojure -M:test` and the production
  gate, `-Dre-frame.debug=false`) except the `^:requires-debug` deftest, which
  reads the dev-only trace stream. The always-on error records are pinned in
  `re-frame.on-error-cljs-test`."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.schemas :as rf.schemas]
            [re-frame.flows :as rf.flows]
            [re-frame.interceptor :as rf.interceptor]
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(defn reset-runtime [test-fn]
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (rf.flows/reset-flows!)
  (rf.schemas/clear-schemas-by-frame!)
  (rf/init! rf.substrate.plain-atom/adapter)
  ;; `init!` does not create `:rf/default`, and framework operations need a
  ;; carried frame.
  (rf/make-frame {:id :rf/default})
  (rf/with-frame :rf/default
    (test-fn)))

(use-fixtures :each reset-runtime)

;; ---- :rf.interceptor/path -------------------------------------------------

(deftest path-interceptor-nesting
  ;; The handler sees the innermost slice; its result splices back through
  ;; both levels, and siblings at every level survive.
  (rf/reg-event :path-nest/init
                (fn [_ _] {:db {:a {:b {:c 7} :sib :keep} :other :preserved}}))
  (rf/reg-event :path-nest/inc
                {:interceptors [[:rf.interceptor/path [:a]]
                                [:rf.interceptor/path [:b :c]]]}
                (fn [{slice :db} _] {:db (inc slice)}))
  (rf/dispatch-sync [:path-nest/init])
  (rf/dispatch-sync [:path-nest/inc])
  (is (= {:a {:b {:c 8} :sib :keep} :other :preserved}
         (rf/app-db-value :rf/default))))

(deftest path-interceptor-no-db-effect-preserves-root-under-flows
  ;; With no `:db` effect the outermost flow pass takes the `:db` coeffect as
  ;; the root, so the path interceptor's unwind must restore the full app-db
  ;; there, at every nesting level.
  (rf/reg-event :bw76/init
                (fn [_ _] {:db {:cart {:items [1]} :counter 2 :sibling :keep}}))
  (rf/reg-event :bw76/fx-only
                {:interceptors [[:rf.interceptor/path [:cart]]]}
                (fn [_ _] {:fx []}))
  (rf/reg-event :bw76/nested-fx-only
                {:interceptors [[:rf.interceptor/path [:cart]]
                                [:rf.interceptor/path [:items]]]}
                (fn [_ _] {:fx []}))
  (rf/reg-flow :bw76/derived {:inputs [[:counter]] :output-path [:derived]}
               (fn [n] (or n 0)))
  (rf/dispatch-sync [:bw76/init])
  (doseq [event-id [:bw76/fx-only :bw76/nested-fx-only]]
    (rf/dispatch-sync [event-id])
    (is (= {:cart {:items [1]} :counter 2 :sibling :keep :derived 2}
           (rf/app-db-value :rf/default))
        (str event-id))))

;; ---- context plumbing through a dispatch -----------------------------------

(deftest project-unwrap-rewrites-event-coeffect
  ;; The handler's event argument is the `:event` coeffect, so a project
  ;; interceptor that rewrites it (the documented `:app/unwrap` pattern)
  ;; changes what the handler receives.
  (let [seen-event (atom ::not-set)]
    (rf/reg-interceptor :app/unwrap
                        {:before (fn [ctx]
                                   (-> ctx
                                       (assoc ::original (rf.interceptor/get-coeffect ctx :event))
                                       (rf.interceptor/update-coeffect :event second)))
                         :after  (fn [ctx]
                                   (rf.interceptor/assoc-coeffect ctx :event (::original ctx)))})
    (rf/reg-event :unwrap-test/consume
                  {:interceptors [:app/unwrap]}
                  (fn [_ event-arg]
                    (reset! seen-event event-arg)
                    {}))
    (rf/dispatch-sync [:unwrap-test/consume {:k "v" :n 7}])
    (is (= {:k "v" :n 7} @seen-event))))

;; ---- chain execution -------------------------------------------------------

(defn- stage
  "An interceptor that logs `[:before id]` / `[:after id]` to `trail`, then
  applies `before` / `after` to the context."
  [trail id before after]
  (rf.interceptor/->interceptor*
    :id     id
    :before (fn [ctx] (swap! trail conj [:before id]) (before ctx))
    :after  (fn [ctx] (swap! trail conj [:after id]) (after ctx))))

(defn- boom [_] (throw (ex-info "boom" {})))

(defn- run-chain [chain]
  (rf.interceptor/execute-chain chain {:coeffects {} :effects {}}))

(deftest chain-composition
  (testing "befores run in order and afters in reverse; a throwing :after is
            recorded, the unwind continues, and only later afters see the error"
    (let [trail   (atom [])
          saw     (atom {})
          seen    (fn [id] (fn [ctx] (swap! saw assoc id (contains? ctx :rf/interceptor-error)) ctx))
          handler (rf.interceptor/->interceptor*
                    :id     :handler
                    :before (fn [ctx] (swap! trail conj :handler) ctx))
          final   (run-chain [(stage trail :a identity (seen :a))
                              (stage trail :boom identity boom)
                              (stage trail :c identity (seen :c))
                              handler])]
      (is (= [[:before :a] [:before :boom] [:before :c] :handler
              [:after :c] [:after :boom] [:after :a]]
             @trail))
      (is (= {:phase :after :id :boom}
             (select-keys (:rf/interceptor-error final) [:phase :id])))
      (is (= {:c false :a true} @saw))))

  (testing "every failure is appended in order; :rf/interceptor-error stays the first"
    (let [final (run-chain [(stage (atom []) :after-bad identity boom)
                            (stage (atom []) :before-bad boom identity)])
          errs  (:rf/interceptor-errors final)]
      (is (= [[:before :before-bad] [:after :after-bad]] (mapv (juxt :phase :id) errs)))
      (is (= (first errs) (:rf/interceptor-error final)))))

  (testing "a throwing :before skips the remaining befores; every :after still runs"
    (let [trail (atom [])
          final (run-chain [(stage trail :a identity identity)
                            (stage trail :boom boom identity)
                            (stage trail :c identity identity)])]
      (is (= [[:before :a] [:before :boom] [:after :c] [:after :boom] [:after :a]]
             @trail))
      (is (= [:boom] (mapv :id (:rf/interceptor-errors final)))))))

;; ---- pipeline-exception attribution (dev trace) ---------------------------

(defn- capture-error-traces
  "Dispatch `event` and return the `:op-type :error` trace events it emitted."
  [event]
  (let [traces (atom [])]
    (rf/register-listener! :trace ::mszrz (fn [ev] (when (= :error (:op-type ev))
                                                     (swap! traces conj ev))))
    (rf/dispatch-sync event)
    (rf/unregister-listener! :trace ::mszrz)
    @traces))

(deftest ^:requires-debug pipeline-exception-attributed-to-true-component
  ;; The always-on record is pinned by `re-frame.on-error-cljs-test`; the dev
  ;; trace adds the `:phase` tag that Xray's Epoch interceptor rows read.
  (doseq [phase [:before :after]]
    (let [icpt  (keyword "mszrz" (str (name phase) "-icpt"))
          event (keyword "mszrz" (str (name phase) "-boom"))]
      (rf/reg-interceptor icpt {phase (fn [_] (throw (ex-info "boom" {})))})
      (rf/reg-event event {:interceptors [icpt]} (fn [{:keys [db]} _] {:db db}))
      (is (= [[:rf.error/interceptor-exception icpt phase]]
             (->> (capture-error-traces [event])
                  (filter #(#{:rf.error/interceptor-exception :rf.error/handler-exception}
                            (:operation %)))
                  (mapv (juxt :operation
                              #(get-in % [:tags :failing-id])
                              #(get-in % [:tags :phase])))))
          (name phase)))))

;; ---- ctx-delta -------------------------------------------------------------

(deftest compute-ctx-delta-keeps-changed-values-under-falsy-keys
  ;; `false` and `nil` are legal map keys, so a change under one belongs in
  ;; the `:changed` diff like any other.
  (is (= {:coeffects {:changed {false {:before :old :after :new}
                                nil   {:before :n-old :after :n-new}}}}
         (#'rf.interceptor/compute-ctx-delta
           {:coeffects {false :old, nil :n-old, :ok 1}}
           {:coeffects {false :new, nil :n-new, :ok 1}}))))
