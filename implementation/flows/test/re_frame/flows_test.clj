(ns re-frame.flows-test
  "JVM coverage for Spec 013 — Flows: registration validation and its error
  ids, the clear lifecycle's registry and app-db effects, hot-reload,
  frame-value targets, and the drain ordering `:fx` and the `:db` install
  observe.

  The canonical flow shapes (dirty-check, topological order, hot-reload, frame
  scoping, teardown) run as data in spec/conformance/fixtures/flow-*.edn via
  `re-frame.flows-conformance-test`."
  (:require [clojure.test :refer [are deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.elision :as rf.elision]
            [re-frame.frame :as rf.frame]
            [re-frame.flows :as rf.flows]
            [re-frame.flows.registry :as rf.flows.registry]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [re-frame.trace.tooling :as rf.trace.tooling])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(deftest exact-owner-loss-in-first-derive-stops-the-flow-tail
  ;; Removing the post-derive owner check publishes :killer's output; removing
  ;; the per-flow loop check invokes :later against same-id B; letting
  ;; destroy+throw win over owner loss leaks an obsolete A exception.
  (let [id         :flow/destroy-owner
        later-runs (atom 0)
        destroyed  (CountDownLatch. 1)
        release    (CountDownLatch. 1)]
    (rf/make-frame {:id id})
    (rf/reg-flow :killer
      {:frame id :inputs [[:seed]] :output-path [:derived]}
      (fn [_]
        (rf.frame/destroy-frame! id)
        (.countDown destroyed)
        (.await release 10 TimeUnit/SECONDS)
        (throw (ex-info "obsolete derive failure" {}))))
    (rf/reg-flow :later
      {:frame id :inputs [[:derived]] :output-path [:later]}
      (fn [v] (swap! later-runs inc) v))
    (rf/reg-event :flow/destroy-owner-event (fn [_ _] {:db {:seed 1}}))
    (let [dispatch-a (future (rf/dispatch-sync [:flow/destroy-owner-event] {:frame id}))]
      (is (.await destroyed 10 TimeUnit/SECONDS)
          "A was destroyed while its derive remained on the stack")
      (rf/make-frame {:id id})
      (.countDown release)
      (is (nil? (deref dispatch-a 5000 ::timeout))
          "destroy+throw in A's derive is inert after exact-owner loss"))
    (is (zero? @later-runs) "no later flow callback runs")
    (is (= {} (rf.frame/frame-app-db-value id))
        "A's pending handler/flow transition never commits into B")))

;; ---- clear-flow ----------------------------------------------------------

(deftest clear-flow-noop-dissoc-does-not-rewrite-the-container
  ;; Clearing a flow whose leaf never materialised must not install a fresh,
  ;; value-equal db: that would invalidate every subscription for nothing.
  (rf/reg-event :seed (fn [_ _] {:db {:other 1}}))
  (rf/dispatch-sync [:seed])
  (rf/reg-flow :pending {:inputs [[:n]] :output-path [:step-2 :result]} (fn [_] :never-runs))
  (let [before (rf/app-db-value :rf/default)]
    (rf/clear :flow :pending)
    (is (identical? before (rf/app-db-value :rf/default)))))

(deftest clear-flow-non-map-intermediate-is-noop
  ;; A scalar at an intermediate output-path step cannot hold the leaf: the
  ;; clear leaves it alone rather than throwing.
  (rf/reg-event :seed (fn [_ _] {:db {:step-2 1 :foo 3}}))
  (rf/dispatch-sync [:seed])
  (rf/reg-flow :pending {:inputs [[:foo]] :output-path [:step-2 :result]} (fn [_] :never-stored))
  (rf/clear :flow :pending)
  (is (= {:step-2 1 :foo 3} (rf/app-db-value :rf/default))))

;; ---- registration validation ---------------------------------------------

(defn- reg-flow-throwing
  "Register `flow-map` with the 3-slot grammar; return what it threw, or nil."
  [flow-map]
  (try (rf/reg-flow (:id flow-map) (dissoc flow-map :id :derive) (:derive flow-map)) nil
       (catch Throwable t t)))

(deftest reg-flow-error-carries-canonical-rf-error-id-slot
  ;; Spec 009 §The thrown-error shape: the discriminator rides `:rf.error/id`,
  ;; never an `:error` slot. Every validation rule builds its error through one
  ;; helper, so the shape is read once.
  (testing "each shape rule throws its own :rf.error/id"
    (are [id metadata derive-fn expected]
         (= expected (:rf.error/id (ex-data (try (rf/reg-flow id metadata derive-fn) nil
                                                 (catch Throwable t t)))))
      nil     {:inputs [[:n]] :output-path [:x]}          identity       :rf.error/flow-missing-id
      "creds" {:inputs [[:n]] :output-path [:x]}          identity       :rf.error/flow-bad-id
      :bad    {:inputs :not-a-vector :output-path [:out]} (fn [_ _] nil) :rf.error/flow-bad-inputs
      :bad    {:inputs [[:n]] :output-path [:x]}          42             :rf.error/flow-bad-output))
  (testing "the thrown error carries the canonical shape"
    (let [ex   (reg-flow-throwing {:id :bad :inputs :not-a-vector :output-path [:out]
                                   :derive (fn [_ _] nil)})
          data (ex-data ex)]
      (is (re-find #"\[:rf\.error/flow-bad-inputs\]" (ex-message ex)))
      ;; `:error` is selected too, so its absence is part of the equality.
      (is (= {:where 'rf/reg-flow :recovery :fix-registration}
             (select-keys data [:error :where :recovery])))
      (is (string? (:reason data))))))

(deftest reg-flow-rejects-malformed-inputs
  (are [inputs bad-entries]
       (= {:rf.error/id :rf.error/flow-bad-inputs :bad-entries bad-entries}
          (select-keys (ex-data (reg-flow-throwing {:id :bad :inputs inputs :derive identity
                                                    :output-path [:out]}))
                       [:rf.error/id :bad-entries]))
    [[:foo] :bar] [:bar]            ; only the bad entry is named
    [[]]          [[]]              ; an empty path
    [[[:nested]]] [[[:nested]]]))   ; a path step that is a vector

(deftest reg-flow-rejects-malformed-output-path
  (are [output-path bad-elements reason-re]
       (let [data (ex-data (reg-flow-throwing {:id :bad :inputs [[:n]] :derive identity
                                               :output-path output-path}))]
         (and (= :rf.error/flow-bad-path (:rf.error/id data))
              (= bad-elements (:bad-elements data))
              (some? (re-find reason-re (str (:reason data))))))
    :not-a-vec  nil         #"must be a vector"
    ;; `(prefix? [] x)` holds for every x, so an empty path would make this
    ;; flow a prerequisite of every other flow in the frame
    []          nil         #"non-empty"
    [[:nested]] [[:nested]] #"path segment"))

(deftest reg-flow-rejects-runtime-partition-rooted-output-path
  ;; A leading :rf.db/runtime marks a runtime-db INPUT; an output always
  ;; writes app-db. Only the leading position is reserved.
  (are [output-path expected]
       (= expected (select-keys (ex-data (reg-flow-throwing {:id :bad :inputs [[:n]] :derive identity
                                                             :output-path output-path}))
                                [:rf.error/id :bad-elements]))
    [:rf.db/runtime :cur] {:rf.error/id  :rf.error/flow-reserved-output-path
                           :bad-elements [:rf.db/runtime]}
    [:app :rf.db/runtime] {}))

(deftest reg-flow-accepts-empty-inputs-vector
  (is (= :constant (rf/reg-flow :constant {:inputs [] :output-path [:k]} (fn [] 42)))))

(deftest reg-flow-rejects-malformed-classification-marks
  ;; EP-0025: a malformed safety mark fails closed rather than installing an
  ;; unprotected output.
  (are [marks expected]
       (= (assoc expected :rf.error/id :rf.error/flow-bad-marks)
          (select-keys (ex-data (reg-flow-throwing (merge {:id :bad/marks :inputs [[:n]]
                                                           :derive identity :output-path [:out]}
                                                          marks)))
                       (conj (keys expected) :rf.error/id)))
    {:sensitive {:secret :leak}}             {:bad-key :sensitive :bad-value {:secret :leak}}
    {:large "blob"}                          {:bad-key :large :bad-value "blob"}
    {:sensitive [[:ok] :token]}              {:bad-key :sensitive :bad-entries [:token]}
    {:large [[:ok] [[:nested]]]}             {:bad-key :large :bad-entries [[[:nested]]]}
    {:large? 1}                              {:bad-key :large? :bad-value 1}
    {:sensitive? true}                       {:bad-key :sensitive? :bad-value true :use :sensitive}
    {:rf.egress/output-sensitivity :inherit} {:bad-key :rf.egress/output-sensitivity :bad-value :inherit}))

(deftest reg-flow-bad-marks-installs-no-flow-row-and-no-elision-declaration
  ;; One well-formed and one malformed mark: the rejection installs neither.
  (let [state  #(vector (rf.flows/flows-snapshot)
                        (rf.elision/sensitive-declarations :rf/default)
                        (rf.elision/declarations :rf/default))
        before (state)
        ex     (reg-flow-throwing {:id :bad/no-leak :inputs [[:n]] :derive identity
                                   :output-path [:out] :sensitive [[:secret]] :large :not-a-vector})]
    (is (= :rf.error/flow-bad-marks (:rf.error/id (ex-data ex))))
    (is (= before (state)))))

(deftest reg-flow-self-cycle-replacement-preserves-prior-registration-and-output
  ;; A replacement whose input overlaps its own output is rejected before any
  ;; mutation: the prior definition, its dirty-check row and its output stand.
  (rf/reg-event :init (fn [_ _] {:db {:n 5}}))
  (rf/reg-flow :double {:inputs [[:n]] :output-path [:derived :doubled]} (fn [n] (* 2 n)))
  (rf/dispatch-sync [:init])
  (let [state  #(vector (get-in (rf.flows/flows-snapshot) [:rf/default :double])
                        (rf.flows.registry/get-frame-flow-last-inputs :rf/default :double)
                        (rf/app-db-value :rf/default))
        before (state)]
    (is (= {:n 5 :derived {:doubled 10}} (peek before)))
    (is (= :rf.error/flow-cycle
           (:rf.error/id (ex-data (reg-flow-throwing {:id :double :inputs [[:derived :doubled]]
                                                      :derive inc :output-path [:derived :doubled]})))))
    (is (= before (state)))))

;; ---- hot-reload ----------------------------------------------------------

(deftest flow-hot-reload-invalidates-last-inputs
  ;; Re-registering a flow drops its dirty-check row, so the next drain
  ;; evaluates the new body even though its inputs are unchanged.
  (rf/reg-event :init (fn [_ _] {:db {:n 5}}))
  (rf/reg-event :tick (fn [{:keys [db]} _] {:db (update db :tick (fnil inc 0))}))
  (rf/reg-flow :double {:inputs [[:n]] :output-path [:doubled]} (fn [n] (* 2 n)))
  (rf/dispatch-sync [:init])
  (is (= 10 (:doubled (rf/app-db-value :rf/default))))
  (rf/reg-flow :double {:inputs [[:n]] :output-path [:doubled]} (fn [n] (* 100 n)))
  (rf/dispatch-sync [:tick])
  (is (= 500 (:doubled (rf/app-db-value :rf/default)))))

;; ---- frame-value targets -------------------------------------------------

(deftest reg-and-clear-flow-normalize-frame-value-target
  ;; EP-0024: a frame VALUE is accepted wherever a frame id is. Both write paths
  ;; key the per-frame store by the normalized id, and clearing a frame's last
  ;; flow prunes its key rather than leaving a `{frame-id {}}` husk.
  (let [frame-val (rf/make-frame {:id :fv/host})]
    (rf/reg-flow :area {:frame frame-val :inputs [[:w]] :output-path [:area]} identity)
    (is (= [:fv/host] (keys (rf.flows/flows-snapshot))))
    (is (= :area (:id (rf.flows/flow-meta {:frame frame-val :id :area}))))
    (rf/clear :flow :area {:frame frame-val})
    (is (= {} (rf.flows/flows-snapshot)))))

;; ---- drain ordering ------------------------------------------------------

(deftest flow-runs-before-db-install
  ;; Flows are the outermost `:after`: the event's single install already
  ;; carries their output, and `:fx`, which runs after the install, reads it.
  (let [installs (atom [])
        fx-saw   (atom nil)]
    (rf.trace.tooling/register-listener!
      ::db-changed
      (fn [ev]
        (when (= :rf.event/db-changed (:operation ev))
          (swap! installs conj (rf/app-db-value :rf/default)))))
    (try
      (rf/reg-fx :test/peek-db (fn [_ _] (reset! fx-saw (rf/app-db-value :rf/default))))
      (rf/reg-flow :double {:inputs [[:n]] :output-path [:doubled]} (fn [n] (* 2 n)))
      (rf/reg-event :go (fn [_ [_ v]] {:db {:n v} :fx [[:test/peek-db {}]]}))
      (rf/dispatch-sync [:go 6])
      (is (= [{:n 6 :doubled 12}] @installs))
      (is (= {:n 6 :doubled 12} @fx-saw))
      (finally
        (rf.trace.tooling/unregister-listener! ::db-changed)))))
