(ns re-frame2-pair-mcp.replay-epoch-test
  "Unit tests for the replay-epoch tool (rf2-ov144).

  Strict replay of a retained epoch in ONE call — the tool sends only the
  id; the preload runtime's `replay-epoch` primitive resolves the raw
  record in-process and re-drives it under `:rf.cofx/mint-policy :strict`
  with the recorded cofx + override maps. Pins:

    - NO `--allow-writes` gate: like `dispatch`, the tool drives the
      app's own handlers and reaches the runtime with the gate OFF;
    - the EDN parse of the `epoch-id` arg, including INTEGER ids;
    - the frame arg rides as the SECOND runtime arg;
    - the success envelope passes through verbatim; every
      `{:ok? false …}` refusal (and the strict missing-cofx failure the
      runtime translates) rides as `isError: true`;
    - the raw-state posture is signalled BEFORE the replay eval."
  (:require [cljs.test :refer-macros [deftest is async]]
            [cljs.reader]
            [clojure.string :as str]
            [re-frame2-pair-mcp.test-utils :as tu]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.tools.raw-state :as raw-state]
            [re-frame2-pair-mcp.tools.writes :as writes]
            [re-frame2-pair-mcp.tools.replay-epoch :as replay-epoch]))

(defn- fresh-conn []
  (let [conn (nrepl/make-conn 0 "127.0.0.1")]
    (swap! conn assoc :probed-builds #{:app})
    conn))

;; Two evals on the happy path: the `configure-raw-state!` signal and the
;; `replay-epoch` form. `captured*` records the LAST non-configure form.
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

(def ^:private read-result-text tu/extract-edn)
(def ^:private err? tu/error?)

(def ^:private success-envelope
  {:ok? true :replayed? true :source-epoch-id 7 :epoch-id 12
   :event-id :cart/checkout :frame :rf/default
   :db-changed? true :changed-paths [[:cart]] :effects-fired [:http] :no-op? false
   :cascade-summary {:epoch-id 12 :event-id :cart/checkout
                     :event-vector [:cart/checkout :rf/redacted]
                     :frame :rf/default :outcome :ok
                     :db-diff {:changed-paths [[:cart]] :added-paths [] :removed-paths []}
                     :fx-fired [:http] :subs-recomputed 2 :renders 1}})

;; ---------------------------------------------------------------------------
;; Dispatch authority — NOT the writes gate.
;; ---------------------------------------------------------------------------

(deftest reaches-the-runtime-with-allow-writes-off
  ;; replay drives the app's own handlers (dispatch posture); the
  ;; --allow-writes gate names the two out-of-band rewrite tools only.
  (async done
    (let [captured (atom nil)
          prev     (writes/allow-writes-enabled?)]
      (writes/set-allow-writes! false)
      (-> (with-captured-eval! captured success-envelope
            (fn []
              (replay-epoch/replay-epoch-tool (fresh-conn) #js {:epoch-id "7"})))
          (.then (fn [r]
                   (is (not (err? r)) "not refused by the writes gate")
                   (is (some? @captured) "the runtime WAS contacted with writes OFF")
                   (is (= true (:ok? (read-result-text r))))))
          (.finally (fn [] (writes/set-allow-writes! prev) (done)))))))

;; ---------------------------------------------------------------------------
;; epoch-id parsing — :any, including integers; frame is the 2nd arg.
;; ---------------------------------------------------------------------------

(deftest accepts-integer-epoch-id-as-data
  (async done
    (let [captured (atom nil)]
      (-> (with-captured-eval! captured success-envelope
            (fn []
              (replay-epoch/replay-epoch-tool (fresh-conn) #js {:epoch-id "7"})))
          (.then (fn [r]
                   (is (not (err? r)))
                   (let [edn (read-result-text r)]
                     (is (= true (:ok? edn)))
                     (is (= true (:replayed? edn)))
                     (is (= 7 (:source-epoch-id edn)))
                     (is (= 12 (:epoch-id edn)) "the NEW epoch id rides through"))
                   (let [parsed (cljs.reader/read-string @captured)]
                     (is (= 're-frame2-pair.runtime/replay-epoch (first parsed)))
                     (is (= 7 (second parsed)) "epoch-id rides as the integer 7, not the string")
                     (is (= 2 (count parsed)) "no frame arg when none was given"))
                   (done)))))))

(deftest passes-frame-as-second-arg
  (async done
    (let [captured (atom nil)]
      (-> (with-captured-eval! captured success-envelope
            (fn []
              (replay-epoch/replay-epoch-tool (fresh-conn)
                                              #js {:epoch-id "12" :frame ":stories"})))
          (.then (fn [_]
                   (let [parsed (cljs.reader/read-string @captured)]
                     (is (= 12 (second parsed)))
                     (is (= :stories (nth parsed 2)) "frame is the 2nd runtime arg"))
                   (done)))))))

(deftest rejects-missing-epoch-id
  (async done
    (let [captured (atom :untouched)]
      (-> (with-captured-eval! captured :should-not-reach
            (fn []
              (replay-epoch/replay-epoch-tool (fresh-conn) #js {})))
          (.then (fn [r]
                   (is (err? r))
                   (is (= :missing-epoch-id (:reason (read-result-text r))))
                   (is (= :untouched @captured) "no runtime round-trip on a missing id")
                   (done)))))))

(deftest rejects-unreadable-epoch-id
  (async done
    (-> (replay-epoch/replay-epoch-tool (fresh-conn) #js {:epoch-id "#("})
        (.then (fn [r]
                 (is (err? r))
                 (is (= :invalid-epoch-id (:reason (read-result-text r))))
                 (done))))))

;; ---------------------------------------------------------------------------
;; Refusals ride as isError, verbatim.
;; ---------------------------------------------------------------------------

(deftest pre-dispatch-refusal-rides-as-isError
  (async done
    (let [refusal {:ok? false :reason :rf.epoch/replay-unknown-epoch
                   :frame :rf/default :epoch-id 999 :history-size 50}]
      (-> (with-captured-eval! (atom nil) refusal
            (fn []
              (replay-epoch/replay-epoch-tool (fresh-conn) #js {:epoch-id "999"})))
          (.then (fn [r]
                   (is (err? r) "a refusal is not a landed replay — isError")
                   (let [edn (read-result-text r)]
                     (is (= refusal edn) "the framework's refusal envelope rides verbatim")
                     (is (= 50 (:history-size edn))))
                   (done)))))))

(deftest unreplayable-fn-override-refusal-carries-fx-ids
  (async done
    (let [refusal {:ok? false :reason :rf.epoch/replay-unreplayable-fx-override
                   :frame :rf/default :epoch-id 8 :fx-ids [:http]}]
      (-> (with-captured-eval! (atom nil) refusal
            (fn []
              (replay-epoch/replay-epoch-tool (fresh-conn) #js {:epoch-id "8"})))
          (.then (fn [r]
                   (is (err? r))
                   (is (= [:http] (:fx-ids (read-result-text r))))
                   (done)))))))

(deftest strict-missing-cofx-failure-rides-as-isError
  ;; The runtime translated the framework's loud throw into an envelope.
  (async done
    (let [failure {:ok? false :reason :rf.error/missing-required-cofx
                   :frame :rf/default :epoch-id 9
                   :message "[:rf.error/missing-required-cofx] absent fact"}]
      (-> (with-captured-eval! (atom nil) failure
            (fn []
              (replay-epoch/replay-epoch-tool (fresh-conn) #js {:epoch-id "9"})))
          (.then (fn [r]
                   (is (err? r))
                   (is (= :rf.error/missing-required-cofx (:reason (read-result-text r))))
                   (done)))))))

(deftest non-envelope-runtime-value-is-not-a-success
  ;; An out-of-date preload (no `replay-epoch` fn) can only yield a
  ;; non-map; the tool must not read that as a landed replay.
  (async done
    (-> (with-captured-eval! (atom nil) false
          (fn []
            (replay-epoch/replay-epoch-tool (fresh-conn) #js {:epoch-id "7"})))
        (.then (fn [r]
                 (is (err? r))
                 (let [edn (read-result-text r)]
                   (is (= false (:ok? edn)))
                   (is (= :replay-unavailable (:reason edn)))
                   (is (= 7 (:epoch-id edn))))
                 (done))))))

;; ---------------------------------------------------------------------------
;; Success envelope passes through; raw-state posture precedes the eval.
;; ---------------------------------------------------------------------------

(deftest consequence-envelope-passes-through-on-success
  (async done
    (-> (with-captured-eval! (atom nil) success-envelope
          (fn []
            (replay-epoch/replay-epoch-tool (fresh-conn) #js {:epoch-id "7"})))
        (.then (fn [r]
                 (is (not (err? r)))
                 (let [edn (read-result-text r)]
                   (is (= success-envelope edn) "the runtime consequence rides verbatim")
                   (is (= :rf/redacted (second (get-in edn [:cascade-summary :event-vector])))
                       "the redacted :event-vector marker survives the wire"))
                 (done))))))

(deftest signals-raw-state-posture-before-the-replay-eval
  (async done
    (let [forms (atom [])
          prev  (raw-state/allow-raw-state-enabled?)]
      (raw-state/set-allow-raw-state! false)
      (-> (with-captured-all! forms success-envelope
            (fn []
              (replay-epoch/replay-epoch-tool (fresh-conn) #js {:epoch-id "7"})))
          (.then (fn [_]
                   (let [all     @forms
                         cfg-idx (first (keep-indexed (fn [i f] (when (str/includes? f "configure-raw-state!") i)) all))
                         rpl-idx (first (keep-indexed (fn [i f] (when (str/includes? f "replay-epoch") i)) all))]
                     (is (some? cfg-idx) "configure-raw-state! is signalled")
                     (is (some? rpl-idx) "the replay-epoch form is evaluated")
                     (is (< cfg-idx rpl-idx)
                         "raw-state posture is signalled BEFORE the replay eval")
                     (is (str/includes? (nth all cfg-idx) ":allow-raw-state? false")
                         "the gate-OFF posture is pushed to the runtime"))))
          (.finally (fn [] (raw-state/set-allow-raw-state! prev) (done)))))))
