(ns re-frame.configure-test
  "The closed-key contract of `(rf/configure! ...)` and its read twin
  `rf/current-config`.

  Unknown keys apply nothing and return nil; a bare or framework-namespaced
  unknown key also emits the dev-gated `:rf.warning/unknown-configure-key`,
  while a user-namespaced key passes in silence. A non-map argument fails
  loud on an always-on guard.

  `:trace-buffer` is dev-only end to end (its setter and its ring are both
  gated on `debug-enabled?`), so every trace-buffer read sits in a
  `(when rf.interop/debug-enabled? ...)` arm: under the production gate the
  ring is empty and a `<=` bound over it would pass vacuously."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.repl :as repl]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.interop :as rf.interop]
            [re-frame.registrar :as rf.registrar]
            [re-frame.schemas :as rf.schemas]
            [re-frame.flows :as rf.flows]
            [re-frame.elision :as rf.elision]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.trace :as rf.trace]
            [re-frame.trace.tooling :as rf.trace.tooling]
            ;; publishes the :epoch/current-config hook
            [re-frame.epoch :as rf.epoch]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(defn reset-runtime [test-fn]
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (rf.flows/reset-flows!)
  (rf.schemas/clear-schemas-by-frame!)
  (rf.trace.tooling/clear-listeners!)
  (rf.trace.tooling/clear-trace-rings!)
  (rf/init! rf.substrate.plain-atom/adapter)
  (rf/make-frame {:id :rf/default})
  (try (rf/with-frame :rf/default (test-fn))
       (finally
         (rf/configure! {:trace-buffer {:events-retained 50}
                         :elision      {:rf.egress/threshold-bytes 16384}}))))

(use-fixtures :each reset-runtime)

(deftest current-config-round-trips-what-configure-wrote
  (rf/configure! {:elision {:rf.egress/threshold-bytes 8192}})
  (is (= 8192 (get-in (rf/current-config) [:elision :rf.egress/threshold-bytes])))
  (try
    (rf/configure! {:epoch-history {:depth 100}})
    (is (= 100 (get-in (rf/current-config) [:epoch-history :depth])))
    (finally
      (rf/configure! {:epoch-history {:depth 50}})))
  ;; the reader is ungated; only the write is dev-only
  (is (contains? (rf/current-config) :trace-buffer))
  (when rf.interop/debug-enabled?
    (rf/configure! {:trace-buffer {:events-retained 25}})
    (is (= 25 (get-in (rf/current-config) [:trace-buffer :events-retained])))))

(deftest current-config-omits-an-absent-subsystem
  ;; Dropping a published hook stands in for a build without that producer.
  ;; Trace tooling goes first: a production bundle DCEs it while keeping the
  ;; epoch artefact, so each key must be absent only when ITS producer is.
  (rf/configure! {:epoch-history {:depth 123}})
  (let [epoch-hook (rf.late-bind/get-fn :epoch/current-config)
        trace-hook (rf.late-bind/get-fn :trace.tooling/current-trace-buffer-config)
        drop-hook! (fn [k]
                     (swap! rf.late-bind/hooks dissoc k)
                     (rf.late-bind/invalidate-cache! k))]
    (try
      (drop-hook! :trace.tooling/current-trace-buffer-config)
      (let [cfg (rf/current-config)]
        (is (not (contains? cfg :trace-buffer)))
        (is (= 123 (get-in cfg [:epoch-history :depth])))
        (is (contains? cfg :elision)))
      (drop-hook! :epoch/current-config)
      (is (not (contains? (rf/current-config) :epoch-history))
          "absent, not a fabricated default")
      (finally
        (rf.late-bind/set-fn! :epoch/current-config epoch-hook)
        (rf.late-bind/set-fn! :trace.tooling/current-trace-buffer-config trace-hook)
        (rf/configure! {:epoch-history {:depth 50}})))))

(deftest trace-buffer-rejected-opts-warn-not-silent
  ;; the retired {:depth N} shape and a negative :events-retained are no-ops
  ;; that warn, and leave retention at the last good value
  (is (nil? (rf/configure! {:trace-buffer {:depth 200}})))
  (rf/configure! {:trace-buffer {:events-retained 9}})
  (when rf.interop/debug-enabled?
    (let [warnings (atom [])]
      (rf/register-listener! :trace ::trace-buffer-opts
                             (fn [ev]
                               (when (= :rf.warning/trace-buffer-unrecognised-opts
                                        (:operation ev))
                                 (swap! warnings conj ev))))
      (try
        (rf/configure! {:trace-buffer {:depth 200}})
        (is (= [{:depth 200}] (mapv (comp :opts :tags) @warnings)))
        (rf/configure! {:trace-buffer {:events-retained -1}})
        (is (= 2 (count @warnings)))
        (rf/reg-event :ping (fn [{:keys [db]} _] {:db db}))
        (dotimes [_ 20] (rf/dispatch-sync [:ping]))
        (is (<= (count (rf/trace-buffer :rf/default)) 9))
        (rf/configure! {:trace-buffer {:events-retained 3}})
        (is (= 2 (count @warnings)) "the canonical shape does not warn")
        (finally
          (rf/unregister-listener! :trace ::trace-buffer-opts))))))

(deftest trace-buffer-severity-warning-filter-catches-unrecognised-opts
  ;; Called from inside a handler so the warning carries the dispatch-id and
  ;; frame a ring needs to retain it.
  (rf/reg-event :bad-configure-call
                (fn [{:keys [db]} _]
                  (rf/configure! {:trace-buffer {:depth 200}})
                  {:db (assoc db ::bad-configure-committed :yes)}))
  (rf/dispatch-sync [:bad-configure-call])
  (is (= :yes (::bad-configure-committed (rf/app-db-value :rf/default)))
      "the rejected configure! did not derail the dispatch")
  (when rf.interop/debug-enabled?
    (let [ops (fn [severity]
                (set (map :operation (rf/trace-buffer :rf/default {:flat true :severity severity}))))]
      (is (contains? (ops :warning) :rf.warning/trace-buffer-unrecognised-opts))
      (is (not (contains? (ops :error) :rf.warning/trace-buffer-unrecognised-opts))))))

(defn- unknown-configure-key-warnings
  "The `:rf.warning/unknown-configure-key` events one `(rf/configure! config)`
  leaves in the frame's ring. Called inside a dispatch because a ring only
  retains events carrying a dispatch-id and a frame."
  [config]
  (rf.trace.tooling/clear-trace-rings!)
  (rf/reg-event ::unknown-key-probe
                (fn [{:keys [db]} _]
                  (rf/configure! config)
                  {:db db}))
  (rf/dispatch-sync [::unknown-key-probe])
  (filterv #(= :rf.warning/unknown-configure-key (:operation %))
           (rf/trace-buffer :rf/default {:flat true})))

(deftest configure-unknown-bare-key-warns-and-no-ops
  (is (= [nil nil nil]
         (mapv rf/configure! [{:strict-subs true} {:rf.nope/x 1} {:myapp/thing 1}]))
      "an unknown key of any namespace applies nothing and returns nil")
  (when rf.interop/debug-enabled?
    (testing "a bare or framework-namespaced unknown key emits one observational warning"
      (doseq [[config k] [[{:strict-subs true} :strict-subs]
                          [{:rf.nope/x 1} :rf.nope/x]]]
        (is (= [{:op-type :warning :recovery :ignored :unknown-keys [k]}]
               (mapv #(assoc (select-keys % [:op-type :recovery])
                             :unknown-keys (:unknown-keys (:tags %)))
                     (unknown-configure-key-warnings config)))))
      (is (= [#{:strict-subs :totally-made-up}]
             (mapv (comp set :unknown-keys :tags)
                   (unknown-configure-key-warnings {:strict-subs true :totally-made-up 1})))
          "one warning per call, naming every offending key"))
    (testing "a user-namespaced key and the known vocabulary emit nothing"
      (is (empty? (unknown-configure-key-warnings {:myapp/thing 1})))
      (is (empty? (unknown-configure-key-warnings {:epoch-history {:depth 7}
                                                   :trace-buffer  {:events-retained 9}
                                                   :elision       {:rf.egress/threshold-bytes 2048}})))))
  (testing "a map mixing known and unknown keys applies the known ones"
    (rf/configure! {:trace-buffer {:events-retained 6}
                    :elision      {:rf.egress/threshold-bytes 2048}
                    :no-such-key  {:foo 1}
                    :strict-subs  true})
    (is (= 2048 (:rf.egress/threshold-bytes (rf.elision/current-config))))
    (when rf.interop/debug-enabled?
      (rf/reg-event :ping (fn [{:keys [db]} _] {:db db}))
      (dotimes [_ 20] (rf/dispatch-sync [:ping]))
      (is (<= (count (rf/trace-buffer :rf/default)) 6)))))

(def ^:private non-map-arg
  "A non-map that carries content, so the ex-data can be checked for not echoing it."
  [:trace-buffer {:events-retained 3}])

(deftest configure-non-map-arg-fails-loud
  (let [d (try (rf/configure! non-map-arg) nil
               (catch clojure.lang.ExceptionInfo e (ex-data e)))]
    (is (= {:rf.error/id :rf.error/configure-bad-arg
            :where       'rf/configure!
            :recovery    :pass-a-config-map}
           (select-keys d [:rf.error/id :where :recovery])))
    ;; configuration may be sensitive: the argument is summarised by shape
    (is (contains? (:received d) :type))
    (is (not= non-map-arg (:received d)))))

;; The guard must hold in an assertion-elided build (CLJS :elide-asserts, or a
;; JVM load under *assert* false), so it cannot be an `assert`. `assert` is a
;; macro, so the harness recompiles configure!'s own source with *assert*
;; false, in a throwaway namespace carrying re-frame.core's aliases and vars.

(def ^:private probe-ns-sym 're-frame.configure-elision-probe)

(defn- eval-with-assertions-elided
  "Compile `form` with `*assert*` false in a throwaway namespace carrying
  `re-frame.core`'s aliases and interned vars (private ones re-interned by
  value, since a private var cannot be referred), and return the Var `sym`."
  [form sym]
  (remove-ns probe-ns-sym)
  (let [probe    (create-ns probe-ns-sym)
        core-ns  (find-ns 're-frame.core)]
    (binding [*ns* probe]
      (refer-clojure)
      (doseq [[a n] (ns-aliases core-ns)]
        (.addAlias ^clojure.lang.Namespace probe a n))
      (doseq [[s v] (ns-interns core-ns)
              :when (not= s sym)]
        (if (and (:private (meta v))
                 (not (:macro (meta v)))
                 (bound? v))
          (intern probe (with-meta s nil) (deref v))
          (.refer ^clojure.lang.Namespace probe s v)))
      (binding [*assert* false]
        (eval form)))
    (ns-resolve probe sym)))

(defn- configure!-source-form []
  (let [src (repl/source-fn 're-frame.core/configure!)]
    (assert (string? src) "could not read re-frame.core/configure! source")
    (read {:read-cond :allow}
          (java.io.PushbackReader. (java.io.StringReader. src)))))

(deftest configure-non-map-guard-survives-assertion-elision
  (let [elided-assert-fn (eval-with-assertions-elided
                           '(defn probe-fn [x] (assert (map? x)) :applied)
                           'probe-fn)]
    (is (= :applied (elided-assert-fn :not-a-map))
        "control: the harness really does elide assertions"))
  (let [elided-configure! (eval-with-assertions-elided
                            (configure!-source-form) 'configure!)]
    (is (= :rf.error/configure-bad-arg
           (:rf.error/id (try (elided-configure! non-map-arg) nil
                              (catch clojure.lang.ExceptionInfo e (ex-data e))))))
    (elided-configure! {:elision {:rf.egress/threshold-bytes 4096}})
    (is (= 4096 (:rf.egress/threshold-bytes (rf.elision/current-config)))
        "the recompiled fn still applies a valid map")))
