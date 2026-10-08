(ns re-frame.classification-effect-shape-record-cljs-test
  "What a PRODUCTION build learns when a malformed commit-plane
  classification effect aborts an event.

  `router/emit-classification-effect-shape!` fans the rejection through
  `dispatch-on-error!`, which is not debug-gated, so the record reaches an
  off-box shipper from a production build. It names WHICH of the four axes was
  malformed (`:offending-key`, a framework keyword from a closed set) and
  carries nothing derived from the rejected payload: `:value` and the
  `:reason` that `pr-str`s it stay on the dev trace, which DCEs.

  Every assertion reads the always-on `:errors` registry, so the namespace
  runs in the production gate too. Dual-runtime `*_cljs_test.cljc`."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.frame :as rf.frame]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter
     :init-fn (fn [] (rf.error-emit/clear-error-listeners!))}))

(def ^:private record-keys
  "Every slot the always-on record carries. Pinned closed, because widening
  it is an egress decision."
  #{:error :event :event-id :frame :time :exception :elapsed-ms :source-coord
    :offending-key})

(defn- reject!
  "Seed `:n` to 1, dispatch `ev-id` returning `effect-key` with the malformed
  `payload`, assert the event aborted pre-commit, and return the always-on
  `:rf.error/classification-effect-shape` records it fanned."
  [effect-key payload ev-id]
  (rf/reg-event :seed (fn [{:keys [db]} _] {:db (assoc db :n 1)}))
  (rf/dispatch-sync [:seed])
  (rf/reg-event ev-id
    (fn [{:keys [db]} _]
      {:db (assoc db :n 2) effect-key payload}))
  (let [seen (atom [])]
    (rf.error-emit/register-error-listener! ::rec (fn [r] (swap! seen conj r)))
    (try (rf/dispatch-sync [ev-id])
         (finally (rf.error-emit/unregister-error-listener! ::rec)))
    (is (= 1 (:n (rf.frame/frame-app-db-value :rf/default)))
        "precondition: the event aborted pre-commit (no :db write landed)")
    (filterv #(= :rf.error/classification-effect-shape (:error %)) @seen)))

(deftest the-always-on-record-names-the-offending-key
  (testing "a malformed payload on each of the four axes fans exactly ONE
            always-on record naming that axis, its event and its frame"
    (doseq [k [:sensitive :large :clear-sensitive :clear-large]]
      (let [ev-id (keyword "bad" (name k))]
        (is (= [{:offending-key k :event-id ev-id :frame :rf/default}]
               (mapv #(select-keys % [:offending-key :event-id :frame])
                     (reject! k :not-a-vector ev-id))))))))

(deftest a-malformed-path-entry-names-its-key-too
  (testing "the bad-path-entry arm and the caught `:rf.error/bad-path` arm of
            `classification-effect-defect` attribute the same way"
    (is (= :large (:offending-key (first (reject! :large [:not-a-path-vector] :bad/large-entry)))))
    (is (= :sensitive (:offending-key (first (reject! :sensitive [[(fn [] :nope)]] :bad/segment)))))))

(deftest the-always-on-record-key-set-is-closed
  (testing "the record's key set is closed, and the rejected payload's content
            appears nowhere on it — omitted outright, not scrubbed"
    (let [secret "sentinel-secret-value"
          rec    (first (reject! :sensitive [secret] :bad/secret-carrier))]
      (is (= record-keys (set (keys rec))))
      (is (not (str/includes? (pr-str rec) secret))))))
