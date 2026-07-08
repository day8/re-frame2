(ns re-frame2-pair-mcp.trace-window-test
  "Unit tests for the `trace-window` MCP tool — the authoritative-ring
  read contract and the empty-result advisory.

  ## Authoritative-ring read

  `trace-window` MUST read the framework's per-frame epoch-history RING
  — `(rf/epoch-history frame-id)`, the SAME source `eval-cljs` hits
  directly — NOT a parallel session-side capture buffer that only fills
  while a listener is attached. A session-side buffer can return EMPTY
  while the ring HOLDS epochs, so reading the ring is the only
  drift-free source. `reads-the-authoritative-ring` pins that the
  emitted eval form calls `epoch-history`, and
  `non-empty-ring-returns-epochs` proves a populated ring's epochs ride
  back (not empty).

  ## Empty-result advisory

  When the time window excludes every event in the per-frame ring the
  tool returns `:count 0` WITH an advisory explaining why. Operators
  otherwise misread a bare `:count 0` as \"events aren't being captured\"
  when in fact events existed but fell outside `:ms`. The advisory
  distinguishes \"genuinely empty history\" from \"window excludes the
  history\"."
  (:require [cljs.test :refer-macros [deftest is testing async]]
            [clojure.string :as str]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.test-utils :as tu]
            [re-frame2-pair-mcp.tools.cursor :as cursor]
            [re-frame2-pair-mcp.tools.trace-window :as tw]))

;; ---------------------------------------------------------------------------
;; Stub harness — `cljs-eval-value` is scripted by form-keyword
;; substring: a sequence of [substring response] pairs picks the first
;; pair whose substring appears in the eval form (probe forms contain
;; `__re_frame2_pair_runtime`; trace-window's slice form contains
;; `epoch-history`). A `:default` sentinel is the fall-through.
;; ---------------------------------------------------------------------------

(defn- with-substr-eval!
  [script body-fn]
  (let [orig nrepl/cljs-eval-value
        match (fn [form-str]
                (some (fn [[k v]]
                        (when (or (= k :default)
                                  (and (string? k) (re-find (re-pattern k) form-str)))
                          v))
                      script))
        stub (fn
               ([_conn _build-id form-str]
                (js/Promise.resolve (match form-str)))
               ([_conn _build-id form-str _opts]
                (js/Promise.resolve (match form-str))))]
    (set! nrepl/cljs-eval-value stub)
    (-> (js/Promise.resolve nil)
        (.then (fn [_] (body-fn)))
        (.finally (fn [] (tu/restore-eval! stub orig))))))

;; ---------------------------------------------------------------------------
;; Authoritative-ring read contract.
;; ---------------------------------------------------------------------------

(defn- capturing-eval!
  "Like `with-substr-eval!` but ALSO records every eval form string into
  `forms-atom`, so a test can assert WHAT the tool sent — here, that it
  reads the authoritative ring (`epoch-history`)."
  [forms-atom canned body-fn]
  (let [orig nrepl/cljs-eval-value
        stub (fn
               ([_conn _build-id form-str]
                (swap! forms-atom conj form-str)
                (js/Promise.resolve (if (re-find #"__re_frame2_pair_runtime" form-str)
                                      true canned)))
               ([_conn _build-id form-str _opts]
                (swap! forms-atom conj form-str)
                (js/Promise.resolve (if (re-find #"__re_frame2_pair_runtime" form-str)
                                      true canned))))]
    (set! nrepl/cljs-eval-value stub)
    (-> (js/Promise.resolve nil)
        (.then (fn [_] (body-fn)))
        (.finally (fn [] (tu/restore-eval! stub orig))))))

(deftest reads-the-authoritative-ring
  (testing "the emitted eval form reads (rf/epoch-history) — the runtime's
            authoritative ring, the same source eval-cljs hits — not a
            session-side capture buffer"
    (async done
      (let [forms (atom [])]
        (-> (capturing-eval! forms
              {:epochs [] :id-aged-out? false :requested-id nil
               :head-id nil :next-id nil :history-count 0 :remaining 0}
              (fn [] (tw/trace-window-tool nil (tu/args->js {:ms 1000}))))
            (.then
              (fn [_result]
                (let [slice-form (some (fn [f]
                                         (when (str/includes? f "epoch-history") f))
                                       @forms)]
                  (is (some? slice-form)
                      "trace-window must emit a form that reads epoch-history")
                  (is (str/includes? slice-form "re-frame2-pair.runtime/epoch-history")
                      "the read targets the runtime's epoch-history pass-through
                       to (rf/epoch-history) — the authoritative ring")
                  (is (not (str/includes? slice-form "observed-epochs"))
                      "must NOT read a session-side capture buffer (the
                       drift-prone proxy this bead removed)"))
                (done))))))))

(deftest non-empty-ring-returns-epochs
  (testing "a populated authoritative ring returns its epochs (not empty) —
            the wide-window happy path proving reads aren't silently empty"
    (async done
      (let [ring-epochs [{:epoch-id :e1 :committed-at 100}
                         {:epoch-id :e2 :committed-at 200}
                         {:epoch-id :e3 :committed-at 300}]
            script [["__re_frame2_pair_runtime" true]
                    [:default {:epochs        ring-epochs
                               :id-aged-out?  false
                               :requested-id  nil
                               :head-id       :e3
                               :next-id       nil
                               :history-count 3
                               :remaining     0}]]]
        (-> (with-substr-eval! script
              (fn []
                (-> (tw/trace-window-tool nil (tu/args->js {:ms 60000}))
                    (.then (fn [result]
                             (let [edn     (tu/extract-edn result)
                                   ;; :epochs may be dedup-wrapped on the
                                   ;; wire; expand before shape assertions.
                                   epochs  (tu/dedup-expand (:epochs edn))]
                               (is (true? (:ok? edn)))
                               (is (= 3 (:count edn))
                                   "all 3 ring epochs returned — not a silent empty")
                               (is (seq epochs) "epochs ride back, not an empty vector")
                               (is (= 3 (count epochs)))
                               (is (= [:e1 :e2 :e3] (mapv :epoch-id epochs))
                                   "the ring's epochs ride back in order")
                               (is (not (contains? edn :advisory))
                                   "no advisory when the ring's epochs land in-window")
                               (done))))))))))))

;; ---------------------------------------------------------------------------
;; Empty window + empty history → NO advisory.
;; ---------------------------------------------------------------------------

(deftest empty-history-no-advisory
  (testing "trace-window with empty history returns count 0 + no advisory"
    (async done
      (let [script [["__re_frame2_pair_runtime" true]
                    [:default                    {:epochs         []
                                                  :id-aged-out?   false
                                                  :requested-id   nil
                                                  :head-id        nil
                                                  :next-id        nil
                                                  :history-count  0
                                                  :remaining      0}]]]
        (-> (with-substr-eval! script
              (fn []
                (-> (tw/trace-window-tool nil (tu/args->js {:ms 1000}))
                    (.then (fn [result]
                             (let [edn (tu/extract-edn result)]
                               (is (true? (:ok? edn)))
                               (is (= 0 (:count edn)))
                               (is (not (contains? edn :advisory))
                                   "genuine empty: history empty → no advisory")
                               (done))))))))))))

;; ---------------------------------------------------------------------------
;; Empty window + non-empty history → ADVISORY with epochs-in-history.
;; ---------------------------------------------------------------------------

(deftest empty-window-non-empty-history-surfaces-advisory
  (testing "trace-window with no events in window but 9 in history → :advisory"
    (async done
      (let [script [["__re_frame2_pair_runtime" true]
                    [:default                    {:epochs         []
                                                  :id-aged-out?   false
                                                  :requested-id   nil
                                                  :head-id        :epoch-9
                                                  :next-id        nil
                                                  :history-count  9
                                                  :remaining      0}]]]
        (-> (with-substr-eval! script
              (fn []
                (-> (tw/trace-window-tool nil (tu/args->js {:ms 1000 :frame "step-deck"}))
                    (.then (fn [result]
                             (let [edn      (tu/extract-edn result)
                                   advisory (:advisory edn)]
                               (is (true? (:ok? edn)))
                               (is (= 0 (:count edn)))
                               (is (some? advisory)
                                   "advisory should fire: 0 matches but 9 in history")
                               (is (= :window-excludes-history (:reason advisory)))
                               (is (= 9 (:epochs-in-history advisory)))
                               (is (= 1000 (:window-ms advisory)))
                               (is (= :step-deck (:frame advisory)))
                               (is (re-find #"9 epochs exist" (:hint advisory)))
                               (is (re-find #"snapshot" (:hint advisory))
                                   "hint points operators to snapshot for historical inspection")
                               (done))))))))))))

;; ---------------------------------------------------------------------------
;; Non-empty window → NO advisory (the happy path).
;; ---------------------------------------------------------------------------

(deftest non-empty-window-no-advisory
  (testing "trace-window with matches in window → no advisory regardless of history"
    (async done
      (let [script [["__re_frame2_pair_runtime" true]
                    [:default                    {:epochs         [{:epoch-id :e1 :committed-at 100}]
                                                  :id-aged-out?   false
                                                  :requested-id   nil
                                                  :head-id        :e1
                                                  :next-id        nil
                                                  :history-count  20
                                                  :remaining      0}]]]
        (-> (with-substr-eval! script
              (fn []
                (-> (tw/trace-window-tool nil (tu/args->js {:ms 60000}))
                    (.then (fn [result]
                             (let [edn (tu/extract-edn result)]
                               (is (true? (:ok? edn)))
                               (is (= 1 (:count edn)))
                               (is (not (contains? edn :advisory))
                                   "advisory should NOT fire when count > 0")
                               (done))))))))))))

;; ---------------------------------------------------------------------------
;; Cursor paths driven through the REAL trace-window-tool (rf2-j9i3vh).
;;
;; Three gaps closed: the MALFORMED-:cursor short-circuit
;; (trace_window.cljs L71-73), the AGED-OUT branch (L136-139), and — the
;; asymmetry the bead flagged — trace-window-tool's OWN :next-cursor mint
;; (L148-154). cursor_pagination_test only ever asserted the slice/mint
;; logic against a HAND-REIMPLEMENTED copy (`runtime-form-output`); these
;; drive the actual `trace-window-tool` so a divergence between the real
;; emitted envelope and the reimplementation can no longer stay green.
;; ---------------------------------------------------------------------------

(deftest malformed-cursor-returns-cursor-stale
  (testing "a garbage :cursor short-circuits to the :rf.mcp/cursor-stale envelope before any runtime eval"
    (async done
      ;; No eval stub: the malformed branch returns before the runtime
      ;; round-trip, so an unstubbed socket call would fail the test if it
      ;; regressed to fall through.
      (-> (tw/trace-window-tool nil (tu/args->js {:cursor "AAAA"}))
          (.then (fn [result]
                   (is (true? (tu/error? result)) "a malformed cursor rides isError")
                   (let [edn (tu/extract-edn result)]
                     (is (false? (:ok? edn)))
                     (is (= :rf.mcp/cursor-stale (:reason edn)))
                     (is (= "trace-window" (:tool edn)))
                     (is (string? (:hint edn))))
                   (done)))))))

(deftest aged-out-ring-returns-cursor-stale
  (testing ":id-aged-out? true from the runtime trips the cursor-stale envelope through the real tool"
    (async done
      (let [script [["__re_frame2_pair_runtime" true]
                    [:default                    {:epochs        []
                                                  :id-aged-out?  true
                                                  :requested-id  :epoch-7
                                                  :head-id       :epoch-57
                                                  :next-id       nil
                                                  :history-count 50
                                                  :remaining     0}]]]
        (-> (with-substr-eval! script
              (fn []
                (-> (tw/trace-window-tool nil (tu/args->js {:ms 1000}))
                    (.then (fn [result]
                             (is (true? (tu/error? result)))
                             (let [edn (tu/extract-edn result)]
                               (is (false? (:ok? edn)))
                               (is (= :rf.mcp/cursor-stale (:reason edn)))
                               (is (= "trace-window" (:tool edn)))
                               (is (= :epoch-7 (:requested-id edn)))
                               (is (= :epoch-57 (:head-id edn))))
                             (done)))))))))))

(deftest next-cursor-mint-encodes-sticky-window-and-frame
  (testing "the REAL trace-window-tool mints a :next-cursor that decodes to the sticky :after-id / :ms / :frame"
    (async done
      (let [ring   [{:epoch-id :e1 :committed-at 100}
                    {:epoch-id :e2 :committed-at 200}]
            script [["__re_frame2_pair_runtime" true]
                    [:default {:epochs        ring
                               :id-aged-out?  false
                               :requested-id  nil
                               :head-id       :e3
                               ;; page short of the full slice ⇒ mint a cursor
                               :next-id       :e2
                               :history-count 3
                               :remaining     1}]]]
        (-> (with-substr-eval! script
              (fn []
                (-> (tw/trace-window-tool nil (tu/args->js {:ms 5000 :frame ":rf/default"}))
                    (.then (fn [result]
                             (let [edn         (tu/extract-edn result)
                                   next-cursor (:next-cursor edn)
                                   decoded     (cursor/decode-cursor next-cursor)]
                               (is (true? (:ok? edn)))
                               (is (true? (:has-more? edn)) "a next-id ⇒ has-more?")
                               (is (some? next-cursor) "the real tool minted a continuation cursor")
                               (is (not= :re-frame2-pair-mcp.tools.cursor/malformed decoded)
                                   "the minted cursor round-trips (not malformed)")
                               (is (= :e2 (:after-id decoded))
                                   "the cursor resumes after the last emitted epoch id")
                               (is (= 5000 (:ms decoded))
                                   "the sticky :ms window rides the cursor for page 2")
                               (is (= :rf/default (:frame decoded))
                                   "the sticky frame rides the cursor")
                               (is (number? (:until-ms decoded))
                                   "the first-call clock is pinned so page 2 sees the same window"))
                             (done)))))))))))
