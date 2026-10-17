(ns re-frame.flows-test
  "JVM coverage for Spec 013 — Flows: registration validation and its error
  ids, path vacation, and the drain ordering `:fx` and the `:db` install
  observe.

  Activation, re-registration and frame isolation are in
  `re-frame.flows-activation-test`; the canonical flow shapes (dirty-check,
  topological order, hot-reload, frame scoping, teardown) run as data in
  spec/conformance/fixtures/flow-*.edn via `re-frame.flows-conformance-test`."
  (:require [clojure.test :refer [are deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.elision :as rf.elision]
            [re-frame.frame :as rf.frame]
            [re-frame.flows.registry :as rf.flows.registry]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [re-frame.trace.tooling :as rf.trace.tooling]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(deftest a-derive-that-destroys-its-frame-lets-the-event-finish
  ;; The destroy waits for the event boundary (Spec 002 §Destroy): the flow
  ;; tail runs, the event commits, and `:on-destroy` then reads the committed
  ;; db before the frame goes.
  (let [id         :flow/destroy-owner
        later-runs (atom 0)
        final-db   (atom ::unset)]
    (rf/reg-event :flow/record-final (fn [{:keys [db]} _] (reset! final-db db) {}))
    (rf/reg-flow :killer
      {:inputs [[:seed]] :output-path [:derived]}
      (fn [seed]
        (when seed (rf.frame/destroy-frame! id))
        (some-> seed inc)))
    (rf/reg-flow :later
      {:inputs [[:derived]] :output-path [:later]}
      (fn [v] (when v (swap! later-runs inc)) v))
    (rf/make-frame {:id id :on-destroy [:flow/record-final] :flows [:killer :later]})
    (rf/reg-event :flow/destroy-owner-event (fn [_ _] {:db {:seed 1}}))
    (rf/dispatch-sync [:flow/destroy-owner-event] {:frame id})
    (is (= {:later-runs 1
            :final-db   {:seed 1 :derived 2 :later 2}
            :frame      nil}
           {:later-runs @later-runs
            :final-db   @final-db
            :frame      (rf.frame/frame id)})
        "the later flow ran once, :on-destroy saw both outputs, and the frame is gone after the event")))

;; ---- path vacation -------------------------------------------------------

(deftest vacating-a-leaf-that-never-materialised-returns-the-same-db
  ;; A leaf under a missing parent or a scalar one leaves the very db it found:
  ;; no throw, and no fresh value-equal db that would invalidate every
  ;; subscription for nothing.
  (doseq [db [{:other 1} {:step-2 1 :foo 3}]]
    (is (identical? db (rf.flows.registry/vacate-path-in-db db [:step-2 :result]))
        (pr-str db))))

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

(deftest reg-flow-rejects-a-frame-key
  ;; Which frames run a flow is each frame's `:flows` config, never the
  ;; registration's (Spec 013 §The registration shape).
  (let [data (ex-data (reg-flow-throwing {:id :scoped :frame :rf/default :inputs [[:n]]
                                          :derive identity :output-path [:out]}))]
    (is (= {:rf.error/id :rf.error/invalid-flow-metadata :value :rf/default}
           (select-keys data [:rf.error/id :value])))
    (is (re-find #":flows" (str (:reason data))) "the message names the frame's :flows config")
    (is (= {} (rf/registrations {:source :store :kind :flow})) "nothing registered")))

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

(deftest reg-flow-bad-marks-installs-no-registration-and-no-elision-declaration
  ;; One well-formed and one malformed mark: the rejection installs neither.
  (let [state  #(vector (rf/registrations {:source :store :kind :flow})
                        (rf.elision/sensitive-declarations :rf/default)
                        (rf.elision/declarations :rf/default))
        before (state)
        ex     (reg-flow-throwing {:id :bad/no-leak :inputs [[:n]] :derive identity
                                   :output-path [:out] :sensitive [[:secret]] :large :not-a-vector})]
    (is (= :rf.error/flow-bad-marks (:rf.error/id (ex-data ex))))
    (is (= before (state)))))

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
      (rf/make-frame {:id :rf/default :flows [:double]})
      (rf/reg-event :go (fn [_ [_ v]] {:db {:n v} :fx [[:test/peek-db {}]]}))
      (rf/dispatch-sync [:go 6])
      (is (= [{:n 6 :doubled 12}] @installs))
      (is (= {:n 6 :doubled 12} @fx-saw))
      (finally
        (rf.trace.tooling/unregister-listener! ::db-changed)))))
