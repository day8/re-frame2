(ns re-frame.flows-activation-test
  "Spec 013 §Activation and §Re-registration: a flow is a top-level
  registration, a frame runs the flows its `:flows` config names, and a
  re-registration reaches each frame running the id on its next event."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.flows.registry :as rf.flows.registry]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [re-frame.trace.tooling :as rf.trace.tooling]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- error-id
  "The `:rf.error/id` of what `f` throws, or nil when it returns."
  [f]
  (try (f) nil (catch Throwable t (:rf.error/id (ex-data t)))))

(defn- with-ops
  "Run `(f ops)` with `ops` recording the `:operation` of every trace."
  [f]
  (let [ops (atom [])]
    (rf.trace.tooling/register-listener! ::ops (fn [ev] (swap! ops conj (:operation ev))))
    (try (f ops)
         (finally (rf.trace.tooling/unregister-listener! ::ops)))))

(defn- reg-cart-flows! []
  (rf/reg-flow :cart/subtotal
    {:inputs [[:cart :items]] :output-path [:cart :subtotal]}
    (fn [items] (reduce + (map :price items))))
  (rf/reg-flow :cart/total
    {:inputs [[:cart :subtotal] [:cart :discount-rate]] :output-path [:cart :total]}
    (fn [subtotal rate] (Math/round (double (* subtotal (- 1 (or rate 0))))))))

(defn- reg-cart-events! []
  (rf/reg-event :cart/seed (fn [_ _] {:db {:cart {:items [{:price 6000} {:price 3700}]}}}))
  (rf/reg-event :cart/touch (fn [{:keys [db]} _] {:db db})))

;; ---- registration ----------------------------------------------------------

(deftest a-top-level-reg-flow-is-an-ordinary-registration
  (testing "no frame, no scope and no live frame are needed"
    (binding [rf.frame/*current-frame* nil]
      (reset! rf.frame/frames {})
      (is (= :cart/subtotal
             (rf/reg-flow :cart/subtotal
               {:inputs [[:cart :items]] :output-path [:cart :subtotal]}
               (fn [items] (reduce + (map :price items))))))))
  (testing "the registrar queries return it"
    (is (= [:cart/subtotal] (keys (rf/registrations {:source :store :kind :flow}))))
    (is (= {:id :cart/subtotal :inputs [[:cart :items]] :output-path [:cart :subtotal]}
           (select-keys (rf/handler-meta {:source :store :kind :flow :id :cart/subtotal})
                        [:id :inputs :output-path])))))

;; ---- activation ------------------------------------------------------------

(deftest a-frame-runs-only-the-flows-its-config-names
  (reg-cart-flows!)
  (reg-cart-events!)
  (rf/make-frame {:id :t/none})
  (rf/make-frame {:id :t/sub :flows [:cart/subtotal]})
  (rf/make-frame {:id :t/both :flows [:cart/total :cart/subtotal]})
  (doseq [f [:t/none :t/sub :t/both]] (rf/dispatch-sync [:cart/seed] {:frame f}))
  (is (= {:t/none {}
          :t/sub  {:subtotal 9700}
          :t/both {:subtotal 9700 :total 9700}}
         (into {} (for [f [:t/none :t/sub :t/both]]
                    [f (dissoc (:cart (rf/app-db-value f)) :items)])))
      "no :flows runs none; the vector's order carries no meaning"))

(deftest make-frame-refuses-a-flows-set-it-cannot-run
  (reg-cart-flows!)
  (rf/reg-flow :cart/twin {:inputs [[:cart :items]] :output-path [:cart :subtotal]} count)
  (rf/reg-flow :loop/a {:inputs [[:b]] :output-path [:a]} identity)
  (rf/reg-flow :loop/b {:inputs [[:a]] :output-path [:b]} identity)
  (is (= {:unknown   :rf.error/unregistered-flow
          :malformed :rf.error/unregistered-flow
          :overlap   :rf.error/flow-path-overlap
          :cycle     :rf.error/flow-cycle}
         {:unknown   (error-id #(rf/make-frame {:id :t/bad :flows [:cart/subtotal :cart/nope]}))
          :malformed (error-id #(rf/make-frame {:id :t/bad :flows :cart/subtotal}))
          :overlap   (error-id #(rf/make-frame {:id :t/bad :flows [:cart/subtotal :cart/twin]}))
          :cycle     (error-id #(rf/make-frame {:id :t/bad :flows [:loop/a :loop/b]}))}))
  (is (nil? (rf.frame/frame :t/bad)) "no refused construction leaves a frame behind")
  (testing "a re-registration with a refused :flows restores the prior record"
    (rf/make-frame {:id :t/kept :flows [:cart/subtotal]})
    (is (= :rf.error/flow-path-overlap
           (error-id #(rf/make-frame {:id :t/kept :flows [:cart/subtotal :cart/twin]}))))
    (is (= [:cart/subtotal] (:flows (rf/frame-meta :t/kept))))))

(deftest two-flows-may-write-one-path-when-no-frame-runs-both
  (reg-cart-flows!)
  (reg-cart-events!)
  (rf/reg-flow :cart/count {:inputs [[:cart :items]] :output-path [:cart :subtotal]} count)
  (rf/make-frame {:id :t/sum :flows [:cart/subtotal]})
  (rf/make-frame {:id :t/count :flows [:cart/count]})
  (rf/dispatch-sync [:cart/seed] {:frame :t/sum})
  (rf/dispatch-sync [:cart/seed] {:frame :t/count})
  (is (= [9700 2] [(get-in (rf/app-db-value :t/sum) [:cart :subtotal])
                   (get-in (rf/app-db-value :t/count) [:cart :subtotal])])))

(deftest re-making-a-frame-without-a-flow-vacates-its-output-on-the-next-event
  (reg-cart-flows!)
  (reg-cart-events!)
  (rf/make-frame {:id :t/f :flows [:cart/subtotal :cart/total]})
  (rf/dispatch-sync [:cart/seed] {:frame :t/f})
  (rf/make-frame {:id :t/f :flows [:cart/subtotal]})
  (rf/dispatch-sync [:cart/touch] {:frame :t/f})
  (is (= {:subtotal 9700} (dissoc (:cart (rf/app-db-value :t/f)) :items))))

;; ---- re-registration -------------------------------------------------------

(deftest re-registering-a-flow-reaches-the-frame-on-its-next-event
  (reg-cart-flows!)
  (reg-cart-events!)
  (rf/make-frame {:id :t/f :flows [:cart/subtotal :cart/total]})
  (rf/dispatch-sync [:cart/seed] {:frame :t/f})
  (testing "an edited derive over unchanged inputs changes the output"
    (rf/reg-flow :cart/total
      {:inputs [[:cart :subtotal] [:cart :discount-rate]] :output-path [:cart :total]}
      (fn [subtotal _] (+ subtotal 500)))
    (is (= 9700 (get-in (rf/app-db-value :t/f) [:cart :total])) "nothing moves before an event")
    (rf/dispatch-sync [:cart/touch] {:frame :t/f})
    (is (= 10200 (get-in (rf/app-db-value :t/f) [:cart :total]))))
  (testing "a moved output path vacates the old one"
    (rf/reg-flow :cart/total
      {:inputs [[:cart :subtotal] [:cart :discount-rate]] :output-path [:cart :grand-total]}
      (fn [subtotal _] (+ subtotal 500)))
    (rf/dispatch-sync [:cart/touch] {:frame :t/f})
    (is (= {:subtotal 9700 :grand-total 10200}
           (dissoc (:cart (rf/app-db-value :t/f)) :items)))))

(deftest a-re-registration-that-completes-a-cycle-aborts-the-frames-next-event
  ;; The checks rerun over the active set on every pass, so the event aborts
  ;; whole: app-db and the memo stay as the last good event left them.
  (rf/reg-event :init (fn [_ _] {:db {:n 5}}))
  (rf/reg-event :bump (fn [{:keys [db]} _] {:db (update db :n inc)}))
  (rf/reg-flow :double {:inputs [[:n]] :output-path [:derived :doubled]} (fn [n] (* 2 n)))
  (rf/make-frame {:id :t/f :flows [:double]})
  (rf/dispatch-sync [:init] {:frame :t/f})
  (let [state  #(vector (rf/app-db-value :t/f)
                        (rf.flows.registry/frame-last-inputs-snapshot :t/f))
        before (state)]
    (is (= {:n 5 :derived {:doubled 10}} (first before)))
    (rf/reg-flow :double {:inputs [[:derived :doubled]] :output-path [:derived :doubled]} inc)
    (with-ops
      (fn [ops]
        (rf/dispatch-sync [:bump] {:frame :t/f})
        (is (some #{:rf.error/flow-eval-exception} @ops))))
    (is (= before (state)))))

(deftest clearing-a-flow-a-frame-names-vacates-it-and-re-registering-resumes-it
  (reg-cart-flows!)
  (reg-cart-events!)
  (rf/make-frame {:id :t/f :flows [:cart/subtotal :cart/total]})
  (rf/dispatch-sync [:cart/seed] {:frame :t/f})
  (with-ops
    (fn [ops]
      (rf/clear :flow :cart/total)
      (is (= {} (select-keys (rf/registrations {:source :store :kind :flow}) [:cart/total])))
      (rf/dispatch-sync [:cart/touch] {:frame :t/f})
      (is (= {:subtotal 9700} (dissoc (:cart (rf/app-db-value :t/f)) :items)))
      (is (= 1 (count (filter #{:rf.warning/unregistered-flow} @ops)))
          "the departure warns once, on the event that vacates")))
  (reg-cart-flows!)
  (rf/dispatch-sync [:cart/touch] {:frame :t/f})
  (is (= 9700 (get-in (rf/app-db-value :t/f) [:cart :total]))))

;; ---- per-frame state -------------------------------------------------------

(deftest a-frames-flow-state-is-its-evaluation-memo
  (reg-cart-flows!)
  (reg-cart-events!)
  (rf/make-frame {:id :t/f :flows [:cart/subtotal]})
  (rf/dispatch-sync [:cart/seed] {:frame :t/f})
  (let [row (get (rf.flows.registry/frame-last-inputs-snapshot :t/f) :cart/subtotal)]
    (is (= #{:flow :inputs :output} (set (keys row))))
    (is (= [:cart/subtotal [:cart :subtotal] [[{:price 6000} {:price 3700}]] 9700]
           [(:id (:flow row)) (:output-path (:flow row)) (:inputs row) (:output row)])
        "a row carries the definition it was computed with, so its output path too"))
  (rf/destroy-frame! :t/f)
  (is (= {} (rf.flows.registry/frame-last-inputs-snapshot :t/f)) "destroy drops the memo"))

;; ---- isolation -------------------------------------------------------------

(deftest two-frames-with-different-flows-stay-isolated
  (reg-cart-flows!)
  (reg-cart-events!)
  (rf/reg-flow :tenant/count {:inputs [[:cart :items]] :output-path [:cart :count]} count)
  (rf/make-frame {:id :t/a :flows [:cart/subtotal :cart/total]})
  (rf/make-frame {:id :t/b :flows [:tenant/count]})
  (rf/dispatch-sync [:cart/seed] {:frame :t/a})
  (rf/dispatch-sync [:cart/seed] {:frame :t/b})
  (rf/clear :flow :cart/total)
  (rf/dispatch-sync [:cart/touch] {:frame :t/b})
  (is (= [{:subtotal 9700 :total 9700} {:count 2}]
         [(dissoc (:cart (rf/app-db-value :t/a)) :items)
          (dissoc (:cart (rf/app-db-value :t/b)) :items)])
      "each frame holds only its own outputs, and an event in one never touches the other")
  (is (= [:cart/subtotal :cart/total] (:flows (rf/frame-meta :t/a)))))

(defn- eval-in-ns
  "Evaluate `form` with `*ns*` bound to `ns-sym`, so the `rf/reg-flow` macro
  captures that namespace as the registration's provenance."
  [ns-sym form]
  (binding [*ns* (create-ns ns-sym)]
    (refer-clojure)
    (alias 'rf 're-frame.core)
    (eval form)))

(deftest a-frame-with-images-resolves-its-flows-through-them
  (eval-in-ns 'sflows.plain '(rf/reg-flow :img/total {:inputs [[:n]] :output-path [:total]} identity))
  (eval-in-ns 'sflows.taxed '(rf/reg-flow :img/total {:inputs [[:n]] :output-path [:total]}
                               (fn [n] (* 2 n))))
  (eval-in-ns 'sflows.events '(rf/reg-event :img/seed (fn [_ _] {:db {:n 10}})))
  (let [frame-with (fn [id ns-glob]
                     (rf/make-frame {:id     id
                                     :flows  [:img/total]
                                     :images [(rf/image {:id        (keyword "img" (name id))
                                                         :select-ns {:include [ns-glob "sflows.events"]}})]}))]
    (frame-with :t/plain "sflows.plain")
    (frame-with :t/taxed "sflows.taxed")
    (rf/dispatch-sync [:img/seed] {:frame :t/plain})
    (rf/dispatch-sync [:img/seed] {:frame :t/taxed})
    (is (= [10 20] [(:total (rf/app-db-value :t/plain)) (:total (rf/app-db-value :t/taxed))])))
  (is (= :rf.error/ambiguous-registration
         (error-id #(rf/make-frame {:id :t/live :flows [:img/total]})))
      "a live frame naming an id two namespaces register is ambiguous"))
