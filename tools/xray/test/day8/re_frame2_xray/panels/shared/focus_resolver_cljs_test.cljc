(ns day8.re-frame2-xray.panels.shared.focus-resolver-cljs-test
  "Pure-data tests for the shared focus-resolver.

  ## Why the `.cljc` + `_cljs_test` naming

    - Cognitect's test-runner (CLJ) picks it up via the default
      `.*-test$` regex on the ns name.
    - Shadow's `:node-test` build picks it up via the `cljs-test$`
      regex on the ns name.

  ## What's under test

    - `resolve-focus-status` — classifies the focus + history pair
      into `:no-focus` / `:focused` / `:epoch-evicted`, honouring the
      head-fallback (nil focus + non-empty history →
      `:focused`).
    - `find-epoch-record` — looks up the matching `:rf/epoch-record`
      or returns the head record under the same head-fallback
      contract."
  (:require #?(:clj  [clojure.test :refer [are deftest]]
               :cljs [cljs.test    :refer-macros [are deftest]])
            [day8.re-frame2-xray.panels.shared.focus-resolver :as focus]))

;; ---- fixture builders ---------------------------------------------------

(defn- epoch-record
  ([trace-events]
   (epoch-record 1 trace-events))
  ([epoch-id trace-events]
   {:epoch-id     epoch-id
    :trace-events (vec trace-events)}))

;; ---- resolve-focus-status ----------------------------------------------

(deftest resolve-focus-status-classifies-focus-against-history
  ;; A nil focus over a non-empty history is the HEAD-FALLBACK: it resolves
  ;; to :focused and `find-epoch-record` returns the most-recent record. That
  ;; is the natural debugging UX: show the latest unless the operator
  ;; explicitly picks an earlier row.
  (let [hist-123 [(epoch-record 1 []) (epoch-record 2 []) (epoch-record 3 [])]]
    (are [focus-id history status] (= status (focus/resolve-focus-status focus-id history))
      ;; cold start: no focus AND no history
      nil []                    :no-focus
      ;; head-fallback
      nil hist-123              :focused
      ;; a pinned id the history carries
      1   hist-123              :focused
      ;; a pinned id the history no longer carries
      99  hist-123              :epoch-evicted)))

;; ---- find-epoch-record -------------------------------------------------

(deftest find-epoch-record-resolves-the-pinned-or-head-record
  ;; epoch-history is oldest-first per re-frame.epoch/epoch-history, so the
  ;; head is the last element. A seq history resolves the same way as a
  ;; vector: the production sub joins on the framework's vector-backed slot,
  ;; but a caller handing in a seq must get its head, not its first record.
  (let [r5   (epoch-record 5 [{:id 100}])
        r6   (epoch-record 6 [{:id 101}])
        r7   (epoch-record 7 [])]
    (are [focus-id history expected] (= expected (focus/find-epoch-record focus-id history))
      ;; a pinned id: its record, or nil once it has gone
      5   [r5 r6]          r5
      99  [r5 r6]          nil
      ;; no pin: the head
      nil [r5 r6 r7]       r7
      nil []               nil
      ;; a seq history
      nil (list r5 r6 r7)  r7)))
