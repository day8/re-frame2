(ns re-frame.source-coord-warn-once-cljs-test
  "Per Spec 006 §Documented exemption: non-DOM roots (rf2-z7f7 / rf2-z9n1):

    'A registered view whose root element is one of [Fragment, host-
     component head, function/component head] is exempt from the
     annotation. The adapter MUST emit a one-shot warning per id (so
     the developer learns the pair-tool footgun without spamming the
     console on re-render) and MUST NOT inject the attribute in
     these cases.'

  Coverage gap before this file (rf2-d4v7 sub-gap 2 / rf2-o423 audit):
  the attribute-emission path has tests in
  `source_coord_dom_cljs_test` (Fragment-root exempt, interop-root
  exempt, attribute-format shape) — the WARNING-FIRES-ONLY-ONCE
  contract has no test. This file pins the warn-once-per-id semantic.

  Mechanism: `re-frame.views/warn-non-dom-root!` (private) consults a
  process-wide `defonce` set `warned-non-dom-roots`. First call for an
  id `swap!`s the id into the set and emits `js/console.warn`;
  subsequent calls for the same id are silenced. The atom is a
  `defonce` so the first warning per id sticks for the lifetime of
  the JS process — re-rendering the same view repeatedly emits the
  warning exactly ONCE."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.test-support :as test-support]
            [re-frame.views])
  (:require-macros [re-frame.core :refer [reg-view]]))

(use-fixtures :each
  (test-support/reset-runtime-fixture
    {:adapter reagent-adapter/adapter}))

;; ---- helper: capture console.warn calls ----------------------------------

(defn- with-captured-console-warn
  "Replace js/console.warn with a recording shim around `thunk`. Returns
  a vector of the messages observed (each message is the joined string
  of the call's args). Restores the original on the way out, even if
  thunk throws."
  [thunk]
  (let [calls    (atom [])
        original (.-warn js/console)]
    (try
      (set! (.-warn js/console)
            (fn [& args]
              (swap! calls conj (apply str args))))
      (thunk)
      @calls
      (finally
        (set! (.-warn js/console) original)))))

;; ---- Fragment root: warning fires exactly once per id ---------------------

(deftest fragment-root-warn-fires-once-across-multiple-renders
  (testing "A Fragment-headed reg-view'd component renders without the
            data-rf2-source-coord attribute (existing coverage) and
            ALSO emits the documented warning EXACTLY ONCE — the
            second through fifth re-render do NOT re-emit. Per Spec
            006 §Documented exemption: 'one-shot warning per id (so
            the developer learns the pair-tool footgun without
            spamming the console on re-render)'.

            Note: the warned-set is a `defonce` atom in re-frame.views,
            so the first warning across the whole test process sticks
            per-id. We use a unique id per test to avoid cross-test
            contamination."
    ;; Unique id — the warned-non-dom-roots set is process-wide
    ;; defonce, so a different id per test is the safe shape.
    (rf/reg-view* :rf.warn-once-test/fragment-multi
                  (fn [] [:<> [:p "a"] [:p "b"]]))
    (let [render   (rf/view :rf.warn-once-test/fragment-multi)
          warnings (with-captured-console-warn
                     (fn []
                       ;; Render 5 times — the warning must fire on
                       ;; the FIRST render and stay silent on the
                       ;; subsequent four.
                       (dotimes [_ 5] (render))))]
      (is (= 1 (count warnings))
          (str "expected EXACTLY ONE warning across 5 renders of the "
               "Fragment-rooted view; got " (count warnings) ": "
               (pr-str warnings)))
      (is (str/includes? (first warnings)
                         "rf.warn-once-test/fragment-multi")
          "the single warning names the offending view-id")
      (is (str/includes? (first warnings) "data-rf2-source-coord")
          "the warning mentions the attribute that was skipped")
      (is (or (str/includes? (first warnings) "Spec 006")
              (str/includes? (first warnings) "fall back"))
          "the warning points the user at the documented contract"))))

;; ---- Per-id silencing is independent across ids --------------------------

(deftest warn-once-is-per-id-not-global
  (testing "The warn-once contract is keyed by view-id. Two different
            non-DOM-rooted views each emit their OWN one-shot warning
            (not a single global gate)."
    (rf/reg-view* :rf.warn-once-test/fragment-id-a
                  (fn [] [:<> [:p "a"]]))
    (rf/reg-view* :rf.warn-once-test/fragment-id-b
                  (fn [] [:<> [:p "b"]]))
    (let [warnings (with-captured-console-warn
                     (fn []
                       ;; Render each twice. Each id should warn once
                       ;; — total 2 warnings, not 1 (per-id silencing,
                       ;; not global), not 4 (one-shot, not per-render).
                       (let [render-a (rf/view :rf.warn-once-test/fragment-id-a)
                             render-b (rf/view :rf.warn-once-test/fragment-id-b)]
                         (render-a) (render-b)
                         (render-a) (render-b))))]
      (is (= 2 (count warnings))
          (str "expected EXACTLY TWO warnings (one per id) across 4 "
               "renders; got " (count warnings) ": "
               (pr-str warnings)))
      (is (some #(str/includes? % "fragment-id-a") warnings)
          "id-a's warning fired")
      (is (some #(str/includes? % "fragment-id-b") warnings)
          "id-b's warning fired"))))

;; ---- Interop / fn-headed roots also warn-once -----------------------------

(deftest interop-root-warn-fires-once
  (testing "A `:>` interop-headed root is on the same exemption list as
            Fragments; it MUST warn exactly once per id across renders.
            Pair tools fall back to :rf/id for these roots. Per Spec
            006 §Documented exemption: non-DOM roots."
    (rf/reg-view* :rf.warn-once-test/interop-multi
                  (fn [] [:> "div" {} "body"]))
    (let [render   (rf/view :rf.warn-once-test/interop-multi)
          warnings (with-captured-console-warn
                     (fn [] (dotimes [_ 5] (render))))]
      (is (= 1 (count warnings))
          (str "expected EXACTLY ONE warning across 5 renders of the "
               "interop-rooted view; got " (count warnings) ": "
               (pr-str warnings)))
      (is (str/includes? (first warnings) "interop-multi")
          "the single warning names the interop-rooted view-id"))))
