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
  (let [hist-123 [(epoch-record 1 []) (epoch-record 2 []) (epoch-record 3 [])]
        hist-567 [(epoch-record 5 []) (epoch-record 6 []) (epoch-record 7 [])]]
    (are [focus-id history status] (= status (focus/resolve-focus-status focus-id history))
      ;; cold start: no focus AND no history
      nil []                    :no-focus
      nil nil                   :no-focus
      ;; head-fallback
      nil hist-123              :focused
      nil [(epoch-record 1 [])] :focused
      ;; a pinned id the history carries
      1   hist-123              :focused
      2   hist-123              :focused
      3   hist-123              :focused
      ;; a pinned id the history no longer carries
      1   hist-567              :epoch-evicted
      99  hist-567              :epoch-evicted
      1   []                    :epoch-evicted
      1   nil                   :epoch-evicted)))

;; ---- find-epoch-record -------------------------------------------------

(deftest find-epoch-record-resolves-the-pinned-or-head-record
  ;; epoch-history is oldest-first per re-frame.epoch/epoch-history, so the
  ;; head is the last element. A seq history resolves the same way as a
  ;; vector: the production sub joins on the framework's vector-backed slot,
  ;; but a caller handing in a seq must not get nil back silently.
  (let [r5   (epoch-record 5 [{:id 100}])
        r6   (epoch-record 6 [{:id 101}])
        r7   (epoch-record 7 [])
        r42  (epoch-record 42 [{:id 1}])]
    (are [focus-id history expected] (= expected (focus/find-epoch-record focus-id history))
      ;; a pinned id: its record, or nil once it has gone
      5   [r5 r6]          r5
      6   [r5 r6]          r6
      99  [r5 r6]          nil
      ;; no pin: the head
      nil [r5 r6 r7]       r7
      nil [r42]            r42
      nil []               nil
      nil nil              nil
      ;; a seq history
      nil (list r5 r6 r7)  r7
      5   (list r5 r6 r7)  r5)))
