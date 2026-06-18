(ns re-frame2-pair-mcp.restore-epoch-test
  "Unit tests for the restore-epoch tool (rf2-ee38b.18).

  Time-travel undo — rewinds a frame's whole frame-state (BOTH app-db
  and runtime-db) to a recorded prior epoch's `:frame-state-after` via
  the Tool-Pair `restore-epoch` write primitive (`replace-frame-state!`).
  Pins:

    - the `--allow-writes` gate (default OFF returns
      `:rf.error/writes-disabled` without touching the runtime);
    - the EDN parse of the `epoch-id` arg, including INTEGER ids (the
      reference runtime emits integers — `:epoch-id` is `:any`);
    - the success / restore-rejected envelope shapes."
  (:require [cljs.test :refer-macros [deftest is async]]
            [cljs.reader]
            [clojure.string :as str]
            [re-frame2-pair-mcp.test-utils :as tu]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.tools.raw-state :as raw-state]
            [re-frame2-pair-mcp.tools.writes :as writes]
            [re-frame2-pair-mcp.tools.restore-epoch :as restore-epoch]))

(defn- fresh-conn []
  (let [conn (nrepl/make-conn 0 "127.0.0.1")]
    (swap! conn assoc :probed-builds #{:app})
    conn))

;; The tool now issues TWO evals on the happy path: the
;; `configure-raw-state!` signal (raw-state/signal-runtime!, rf2-6nks4)
;; and the `restore-epoch` form. The stub matches by substring — the
;; configure eval resolves to nil (swallowed); the restore eval resolves
;; to `canned-value`. `captured*` records the LAST non-configure form
;; (the restore form) so the existing form-shape assertions keep working;
;; `with-captured-all!` (below) records EVERY form for ordering tests.
(defn- with-captured-eval!
  [captured* canned-value body-fn]
  (let [orig nrepl/cljs-eval-value
        run  (fn [form-str]
               (if (str/includes? form-str "configure-raw-state!")
                 (js/Promise.resolve nil)
                 (do (reset! captured* form-str)
                     (js/Promise.resolve canned-value))))
        stub (fn
               ([_conn _build-id form-str] (run form-str))
               ([_conn _build-id form-str _opts] (run form-str)))]
    (set! nrepl/cljs-eval-value stub)
    (raw-state/reset-runtime-signal-cache!)
    (-> (js/Promise.resolve nil)
        (.then (fn [_] (body-fn)))
        (.finally (fn [] (tu/restore-eval! stub orig))))))

(defn- with-captured-all!
  "Record EVERY emitted form (configure + restore) in order, so a test
  can assert the raw-state signal precedes the restore eval (rf2-6nks4)."
  [forms* canned-value body-fn]
  (let [orig nrepl/cljs-eval-value
        run  (fn [form-str]
               (swap! forms* conj form-str)
               (js/Promise.resolve
                 (if (str/includes? form-str "configure-raw-state!")
                   nil
                   canned-value)))
        stub (fn
               ([_conn _build-id form-str] (run form-str))
               ([_conn _build-id form-str _opts] (run form-str)))]
    (set! nrepl/cljs-eval-value stub)
    (raw-state/reset-runtime-signal-cache!)
    (-> (js/Promise.resolve nil)
        (.then (fn [_] (body-fn)))
        (.finally (fn [] (tu/restore-eval! stub orig))))))

(defn- with-writes-on! [body-fn]
  (let [prev (writes/allow-writes-enabled?)]
    (writes/set-allow-writes! true)
    (-> (js/Promise.resolve nil)
        (.then (fn [_] (body-fn)))
        (.finally (fn [] (writes/set-allow-writes! prev))))))

(def ^:private read-result-text tu/extract-edn)
(def ^:private err? tu/error?)

;; ---------------------------------------------------------------------------
;; Gate — default OFF.
;; ---------------------------------------------------------------------------

(deftest gated-off-by-default-without-touching-runtime
  ;; The default-safe posture: with --allow-writes OFF the tool refuses
  ;; before any nREPL round-trip. We install a stub that would FAIL the
  ;; test (reset! captured) if reached — proving the gate short-circuits.
  (async done
    (let [captured (atom :untouched)
          prev     (writes/allow-writes-enabled?)]
      (writes/set-allow-writes! false)
      (-> (with-captured-eval! captured :should-not-reach
            (fn []
              (restore-epoch/restore-epoch-tool (fresh-conn) #js {:epoch-id "7"})))
          (.then (fn [r]
                   (is (err? r))
                   (is (= :rf.error/writes-disabled (:reason (read-result-text r))))
                   (is (= :untouched @captured) "runtime must NOT be contacted when gated")))
          (.finally (fn [] (writes/set-allow-writes! prev) (done)))))))

;; ---------------------------------------------------------------------------
;; epoch-id parsing — :any, including integers.
;; ---------------------------------------------------------------------------

(deftest accepts-integer-epoch-id-as-data
  ;; The reference runtime emits INTEGER epoch-ids. "7" reads as the
  ;; number 7 and rides into the runtime call as a data literal.
  (async done
    (let [captured (atom nil)]
      (-> (with-writes-on!
            (fn []
              (with-captured-eval! captured true
                (fn []
                  (restore-epoch/restore-epoch-tool (fresh-conn) #js {:epoch-id "7"})))))
          (.then (fn [r]
                   (is (not (err? r)))
                   (let [edn (read-result-text r)]
                     (is (= true (:ok? edn)))
                     (is (= true (:restored? edn)))
                     (is (= 7 (:epoch-id edn)) "integer id round-trips through the envelope"))
                   (let [parsed (cljs.reader/read-string @captured)]
                     (is (= 're-frame2-pair.runtime/restore-epoch (first parsed)))
                     (is (= 7 (second parsed)) "epoch-id rides as the integer 7, not \"7\""))
                   (done)))))))

(deftest passes-frame-as-second-arg
  (async done
    (let [captured (atom nil)]
      (-> (with-writes-on!
            (fn []
              (with-captured-eval! captured true
                (fn []
                  (restore-epoch/restore-epoch-tool (fresh-conn)
                                                    #js {:epoch-id "12" :frame ":stories"})))))
          (.then (fn [_]
                   (let [parsed (cljs.reader/read-string @captured)]
                     ;; (rt/restore-epoch 12 :stories) — frame is the 2nd arg.
                     (is (= 12 (second parsed)))
                     (is (= :stories (nth parsed 2))))
                   (done)))))))

(deftest rejects-missing-epoch-id
  (async done
    (-> (with-writes-on!
          (fn []
            (restore-epoch/restore-epoch-tool (fresh-conn) #js {})))
        (.then (fn [r]
                 (is (err? r))
                 (is (= :missing-epoch-id (:reason (read-result-text r))))
                 (done))))))

(deftest rejects-unreadable-epoch-id
  (async done
    (-> (with-writes-on!
          (fn []
            (restore-epoch/restore-epoch-tool (fresh-conn) #js {:epoch-id "#("})))
        (.then (fn [r]
                 (is (err? r))
                 (is (= :invalid-epoch-id (:reason (read-result-text r))))
                 (done))))))

;; ---------------------------------------------------------------------------
;; Restore failure — runtime returns false.
;; ---------------------------------------------------------------------------

(deftest surfaces-restore-rejected-when-runtime-returns-false
  ;; restore-epoch returns false on any failure (aged-out id,
  ;; drain-in-flight, …); the app-db is unchanged. We surface a
  ;; structured :restore-rejected.
  (async done
    (let [captured (atom nil)]
      (-> (with-writes-on!
            (fn []
              (with-captured-eval! captured false
                (fn []
                  (restore-epoch/restore-epoch-tool (fresh-conn) #js {:epoch-id "999"})))))
          (.then (fn [r]
                   (is (not (err? r)) "soft-failure rides as an ok-text envelope, not isError")
                   (let [edn (read-result-text r)]
                     (is (= false (:ok? edn)))
                     (is (= false (:restored? edn)))
                     (is (= :restore-rejected (:reason edn)))
                     (is (= 999 (:epoch-id edn))))
                   (done)))))))

;; ---------------------------------------------------------------------------
;; Cascade summary (rf2-6yqdl) — the runtime's restore-epoch helper now
;; returns a structured envelope on success carrying :cascade-summary +
;; :unreplayable-effects. The tool passes the runtime map through
;; unchanged when the runtime returned a map; falls back to the legacy
;; envelope when the runtime returned plain `true` (pre-rf2-6yqdl
;; runtimes).
;; ---------------------------------------------------------------------------

(deftest cascade-summary-passes-through-on-success
  (async done
    (let [canned-cascade {:epoch-id 7
                          :event-id :cart/add
                          :event-vector [:cart/add {:sku "x"}]
                          :frame :rf/default
                          :outcome :ok
                          :db-diff {:changed-paths [[:cart]]
                                    :added-paths [] :removed-paths []}
                          :fx-fired [:http]
                          :subs-recomputed 2
                          :renders 1
                          :restore? true}
          runtime-envelope {:ok? true :restored? true :epoch-id 7
                            :frame :rf/default
                            :cascade-summary canned-cascade
                            :unreplayable-effects [{:fx-id :http :coord [:my.app.cart 42 4]}]}]
      (-> (with-writes-on!
            (fn []
              (with-captured-eval! (atom nil) runtime-envelope
                (fn []
                  (restore-epoch/restore-epoch-tool (fresh-conn) #js {:epoch-id "7"})))))
          (.then (fn [r]
                   (is (not (err? r)))
                   (let [edn (read-result-text r)]
                     (is (true? (:ok? edn)))
                     (is (true? (:restored? edn)))
                     (is (= canned-cascade (:cascade-summary edn))
                         "cascade-summary rides through verbatim")
                     (is (= [{:fx-id :http :coord [:my.app.cart 42 4]}]
                            (:unreplayable-effects edn))
                         "unreplayable-effects rides through verbatim"))
                   (done)))))))

;; ---------------------------------------------------------------------------
;; rf2-6nks4 (finding-2) — sensitive cascade-summary :event-vector egress.
;;
;; The runtime's restore-cascade-summary redacts the target epoch's raw
;; :event-vector to :rf/redacted when the epoch is sensitive AND the
;; raw-state gate is OFF (the published-build default). For that runtime
;; redaction to fire, the tool MUST signal `configure-raw-state!` to the
;; runtime BEFORE the restore eval — exactly like dispatch-dry-run. These
;; tests pin the WIRE boundary (the signal ordering + gate posture pushed);
;; the runtime redaction itself is exercised by the bb runtime test
;; (skills/re-frame2-pair/tests/runtime/cascade_summary_redaction_test.clj).
;; ---------------------------------------------------------------------------

(deftest signals-raw-state-posture-before-the-restore-eval
  (async done
    (let [forms (atom [])
          prev  (raw-state/allow-raw-state-enabled?)]
      (raw-state/set-allow-raw-state! false)
      (-> (with-writes-on!
            (fn []
              (with-captured-all! forms true
                (fn []
                  (restore-epoch/restore-epoch-tool (fresh-conn) #js {:epoch-id "7"})))))
          (.then (fn [_]
                   (let [all      @forms
                         cfg-idx  (first (keep-indexed (fn [i f] (when (str/includes? f "configure-raw-state!") i)) all))
                         rst-idx  (first (keep-indexed (fn [i f] (when (str/includes? f "restore-epoch") i)) all))]
                     (is (some? cfg-idx) "configure-raw-state! is signalled")
                     (is (some? rst-idx) "the restore-epoch form is evaluated")
                     (is (< cfg-idx rst-idx)
                         "raw-state posture is signalled BEFORE the restore eval (so the runtime redacts a sensitive :event-vector)")
                     (is (str/includes? (nth all cfg-idx) ":allow-raw-state? false")
                         "the gate-OFF posture is pushed to the runtime"))))
          (.finally (fn [] (raw-state/set-allow-raw-state! prev) (done)))))))

(deftest redacted-sensitive-event-vector-rides-through
  ;; The runtime already redacted the sensitive target epoch's
  ;; :event-vector to :rf/redacted (gate OFF). The tool passes the
  ;; runtime envelope through verbatim — the redacted marker must survive
  ;; on the wire, and the raw payload must NOT appear anywhere.
  (async done
    (let [redacted-cascade {:epoch-id 7
                            :event-id :auth/login
                            :event-vector :rf/redacted
                            :sensitive? true
                            :frame :rf/default
                            :outcome :ok
                            :db-diff {:changed-paths [[:auth]] :added-paths [] :removed-paths []}
                            :fx-fired [:http]
                            :subs-recomputed 1
                            :renders 1
                            :restore? true}
          runtime-envelope {:ok? true :restored? true :epoch-id 7
                            :frame :rf/default
                            :cascade-summary redacted-cascade
                            :unreplayable-effects [{:fx-id :http}]}]
      (-> (with-writes-on!
            (fn []
              (with-captured-eval! (atom nil) runtime-envelope
                (fn []
                  (restore-epoch/restore-epoch-tool (fresh-conn) #js {:epoch-id "7"})))))
          (.then (fn [r]
                   (is (not (err? r)))
                   (let [edn  (read-result-text r)
                         text (tu/extract-text r)]
                     (is (= :rf/redacted (get-in edn [:cascade-summary :event-vector]))
                         "the redacted :event-vector marker rides through verbatim")
                     (is (true? (get-in edn [:cascade-summary :sensitive?]))
                         "the :sensitive? annotation rides through")
                     (is (not (str/includes? text "password"))
                         "no raw payload literal appears on the wire"))
                   (done)))))))

(deftest legacy-true-runtime-falls-back-to-stub-envelope
  ;; A pre-rf2-6yqdl runtime returns plain `true` on success. The tool
  ;; falls back to the historical synthesised envelope so the wire
  ;; stays sane even against an out-of-date preload.
  (async done
    (let [captured (atom nil)]
      (-> (with-writes-on!
            (fn []
              (with-captured-eval! captured true
                (fn []
                  (restore-epoch/restore-epoch-tool (fresh-conn) #js {:epoch-id "7"})))))
          (.then (fn [r]
                   (is (not (err? r)))
                   (let [edn (read-result-text r)]
                     (is (true? (:ok? edn)))
                     (is (true? (:restored? edn)))
                     (is (= 7 (:epoch-id edn))))
                   (done)))))))
