(ns re-frame.registrar-warnings-test
  "The registrar's two dev warnings (Spec 001): `:rf.warning/missing-doc` for a
  macro-path registration with no usable `:doc`, and
  `:rf.warning/registration-collision` when an id is re-registered from a
  different source-coord provenance. Both warn once per `(kind, id)`.

  Both are dev-only, so the warning assertions (including the negatives, which
  would pass over an empty stream) sit in `(when rf.interop/debug-enabled? ...)`
  arms. Each case also asserts the registration itself, which a production
  build honours: the cascade continues and the last registration wins."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.interop :as rf.interop]
            [re-frame.frame :as rf.frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.source-coords :as rf.source-coords]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.trace.tooling :as rf.trace.tooling]
            [re-frame.core :as rf]))

(defn reset-runtime [test-fn]
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (rf.trace.tooling/clear-listeners!)
  (rf/init! rf.substrate.plain-atom/adapter)
  (test-fn))

(use-fixtures :each reset-runtime)

(defn- record-traces!
  "Attach a recording listener and return its atom."
  [listener-id]
  (let [a (atom [])]
    (rf/register-listener! :trace listener-id (fn [ev] (swap! a conj ev)))
    a))

(defn- warnings-of
  [recorded operation]
  (filterv (fn [ev]
             (and (= :warning (:op-type ev))
                  (= operation (:operation ev))))
           @recorded))

(defn- assert-registered [kind id]
  (is (some? (rf.registrar/lookup kind id)) (str kind " " id " registered")))

(defn- assert-live-provenance
  "Which registration is live, read off the stored `:ns` (kept verbatim in
  both postures)."
  [kind id expected-ns]
  (is (= expected-ns (:ns (rf.registrar/lookup kind id)))))

(defn- with-stamped-coords
  "Invoke `f` with the macro-path coords every reg-* macro binds; missing-doc
  fires only for registrations that came through that path."
  [f]
  (binding [rf.source-coords/*pending-coords*
            {:ns 're-frame.registrar-warnings-test
             :file "registrar_warnings_test.clj"
             :line 1
             :column 1}]
    (f)))

(defn- reg-at
  "Register an `:event` with an explicit provenance and a fresh handler fn,
  through register! directly: a hot reload arrives with the SAME (ns, file,
  line) and a new fn, which two macro calls in this file (on different lines)
  cannot reproduce. nil provenance is the programmatic / REPL path."
  ([id] (reg-at id nil))
  ([id provenance]
   (rf.registrar/register! :event id
                        (merge (or provenance {})
                               {:handler-fn (fn [{:keys [db]} _] {:db db})}))))

(deftest missing-doc-fires-when-doc-absent
  (let [recorded (record-traces! ::missing-absent)]
    (with-stamped-coords
      (fn []
        (rf/reg-event :ev/no-doc (fn [{:keys [db]} _] {:db db}))))
    (assert-registered :event :ev/no-doc)
    (when rf.interop/debug-enabled?
      (is (= [[:event :ev/no-doc 're-frame.registrar-warnings-test]]
             (mapv (comp (juxt :kind :id (comp :ns :source-coords)) :tags)
                   (warnings-of recorded :rf.warning/missing-doc)))))))

(deftest missing-doc-fires-when-doc-is-nil-or-empty
  ;; nil and "" are both unusable; the empty string is the less obvious one
  (let [recorded (record-traces! ::missing-empty)]
    (with-stamped-coords
      (fn []
        (rf/reg-event :ev/empty-doc {:doc ""} (fn [{:keys [db]} _] {:db db}))))
    (assert-registered :event :ev/empty-doc)
    (when rf.interop/debug-enabled?
      (is (= 1 (count (warnings-of recorded :rf.warning/missing-doc)))))))

(deftest missing-doc-suppressed-when-doc-present
  (let [recorded (record-traces! ::doc-present)]
    (with-stamped-coords
      (fn []
        (rf/reg-event :ev/well-doc'd
                         {:doc "a real description"}
                         (fn [{:keys [db]} _] {:db db}))))
    (assert-registered :event :ev/well-doc'd)
    (when rf.interop/debug-enabled?
      (is (empty? (warnings-of recorded :rf.warning/missing-doc))))))

(deftest missing-doc-suppressed-on-re-registration-same-id
  (let [recorded (record-traces! ::suppress-rereg)]
    (with-stamped-coords
      (fn []
        (rf/reg-event :ev/same-id (fn [{:keys [db]} _] {:db db}))
        (rf/reg-event :ev/same-id (fn [{:keys [db]} _] {:db (assoc db :touched? true)}))
        (rf/reg-event :ev/same-id (fn [{:keys [db]} _] {:db db}))))
    (assert-registered :event :ev/same-id)
    (when rf.interop/debug-enabled?
      (is (= 1 (count (warnings-of recorded :rf.warning/missing-doc)))
          "one warning across three registrations of the same id"))))

(deftest missing-doc-fires-once-per-id-within-kind
  (let [recorded (record-traces! ::per-id-within-kind)]
    (with-stamped-coords
      (fn []
        (rf/reg-event :ev/alpha (fn [{:keys [db]} _] {:db db}))
        (rf/reg-event :ev/beta  (fn [{:keys [db]} _] {:db db}))))
    (is (every? #(rf.registrar/lookup :event %) [:ev/alpha :ev/beta]))
    (when rf.interop/debug-enabled?
      (is (= [:ev/alpha :ev/beta]
             (mapv #(get-in % [:tags :id]) (warnings-of recorded :rf.warning/missing-doc)))))))

(deftest missing-doc-fires-once-per-kind-for-same-id
  (let [recorded (record-traces! ::per-kind-same-id)]
    (with-stamped-coords
      (fn []
        (rf/reg-event :alias/shared (fn [{:keys [db]} _] {:db db}))
        (rf/reg-sub       :alias/shared (fn [db _] (:x db)))))
    (is (every? #(rf.registrar/lookup % :alias/shared) [:event :sub]))
    (when rf.interop/debug-enabled?
      (is (= [:event :sub]
             (mapv #(get-in % [:tags :kind]) (warnings-of recorded :rf.warning/missing-doc)))))))

(deftest missing-doc-silent-on-programmatic-path
  ;; no macro-path coords bound: an internal helper / REPL register! call
  (let [recorded (record-traces! ::programmatic)]
    (rf.registrar/register! :event :internal/no-coords
                         {:handler-fn (fn [db _] db)})
    (assert-registered :event :internal/no-coords)
    (when rf.interop/debug-enabled?
      (is (empty? (warnings-of recorded :rf.warning/missing-doc))))))

(deftest collision-fires-on-same-file-different-line
  ;; detection keys on the (ns, file, line) provenance, not fn identity: a
  ;; same-file re-eval yields a fresh fn and must stay silent
  (let [recorded (record-traces! ::collision-same-file-diff-line)]
    (reg-at :dup/id {:ns 're-frame.registrar-warnings-test
                     :file "registrar_warnings_test.clj" :line 10 :column 1})
    (reg-at :dup/id {:ns 're-frame.registrar-warnings-test
                     :file "registrar_warnings_test.clj" :line 99 :column 1})
    (is (= 99 (:line (rf.registrar/lookup :event :dup/id))) "the second site is live")
    (when rf.interop/debug-enabled?
      (is (= 1 (count (warnings-of recorded :rf.warning/registration-collision)))))))

(deftest collision-silent-on-programmatic-path
  (let [recorded (record-traces! ::collision-programmatic)]
    (reg-at :prog/id nil)
    (reg-at :prog/id nil)
    (assert-registered :event :prog/id)
    (when rf.interop/debug-enabled?
      (is (empty? (warnings-of recorded :rf.warning/registration-collision))
          "no provenance, nothing to collide on"))))

(deftest handler-replaced-fires-on-silent-hot-reload
  ;; handler-replaced fires on every re-registration; the collision warning is
  ;; a separate surface and stays silent on a same-source re-eval
  (let [recorded (record-traces! ::replaced-on-hot-reload)
        coords   {:ns 're-frame.registrar-warnings-test
                  :file "registrar_warnings_test.clj" :line 7 :column 1}]
    (reg-at :hr/id coords)
    (reg-at :hr/id coords)
    (reg-at :hr/id coords)
    (assert-live-provenance :event :hr/id 're-frame.registrar-warnings-test)
    (when rf.interop/debug-enabled?
      (is (= 2 (count (filterv #(= :rf.registry/handler-replaced (:operation %)) @recorded))))
      (is (empty? (warnings-of recorded :rf.warning/registration-collision))))))

(deftest collision-warning-coexists-with-handler-replaced
  (let [recorded (record-traces! ::coexist)]
    (doseq [[ns line] [['c.a 1] ['c.b 2] ['c.c 3]]]
      (reg-at :coex/id {:ns ns :file (str ns ".cljc") :line line :column 1}))
    (assert-live-provenance :event :coex/id 'c.c)
    (when rf.interop/debug-enabled?
      (is (= 1 (count (warnings-of recorded :rf.warning/registration-collision)))
          "warn once per (kind, id): only the first cross-provenance re-registration"))))

(defn- reg-no-handler-fn!
  "Register a `:route`-shaped slot (no `:handler-fn`) whose metadata differs
  only in provenance, so the hot-reload dedup-by-shape sees the SAME shape."
  [kind id provenance]
  (rf.registrar/register! kind id (merge (or provenance {}) {:doc "slot"})))

(deftest collision-fires-for-no-handler-fn-kind-despite-shape-dedup
  ;; the shape dedup suppresses the handler-replaced TRACE; a collision check
  ;; nested inside that gate would hide a genuine cross-source clash
  (let [recorded (record-traces! ::no-fn-collision)]
    (reg-no-handler-fn! :route :surface/main
                        {:ns 'feature.a :file "feature/a.cljc" :line 10 :column 1})
    (reg-no-handler-fn! :route :surface/main
                        {:ns 'feature.b :file "feature/b.cljc" :line 20 :column 1})
    (assert-live-provenance :route :surface/main 'feature.b)
    (when rf.interop/debug-enabled?
      (is (empty? (filterv #(= :rf.registry/handler-replaced (:operation %)) @recorded))
          "handler-replaced is dedup-suppressed")
      (is (= [[:route :surface/main 'feature.b 'feature.a]]
             (mapv (comp (juxt :kind :id (comp :ns :source-coords) (comp :ns :previous-coords)) :tags)
                   (warnings-of recorded :rf.warning/registration-collision)))
          "the collision still fires"))))
