(ns re-frame.adapter.reagent-slim-source-coord-warn-once-cljs-test
  "reagent-slim parity for the non-DOM-root warn-once contract (rf2-sx77q
  G5; mirrors `re-frame.source-coord-warn-once-cljs-test` for the Reagent
  bridge).

  Per Spec 006 §Documented exemption: a registered view whose root
  element is a non-DOM root (Fragment, interop head, function/component
  head) is exempt from source-coord annotation. The adapter MUST emit a
  one-shot warning per id (so the developer learns the pair-tool footgun
  without spamming the console on re-render) and MUST NOT inject the
  attribute.

  WHY THIS FILE EXISTS (rf2-sx77q G5, slim side). slim is a drop-in
  Reagent replacement and renders hiccup through the SAME
  `re-frame.views/warn-non-dom-root!` path (the warned-set is a
  process-wide `defonce` in re-frame.views). The Reagent bridge pins
  fire-once via `source_coord_warn_once_cljs_test`; before this file slim
  had no fire-once coverage at all (the audit's G1/G5 slim gap). This
  file closes it.

  Mechanism: `re-frame.views/warn-non-dom-root!` (private) consults the
  process-wide `defonce` set. First call for an id warns + records;
  subsequent calls for the same id are silenced. Re-rendering the same
  view repeatedly emits the warning exactly ONCE.

  ns ends in -cljs-test so shadow-cljs's :node-test build picks it up."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent-slim :as reagent-slim-adapter]
            [re-frame.test-support :as test-support]
            [re-frame.views]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter reagent-slim-adapter/adapter}))

;; ---- helper: capture console.warn calls ----------------------------------

(defn- with-captured-console-warn
  "Replace js/console.warn with a recording shim around `thunk`. Returns
  a vector of the joined-string messages observed. Restores the original
  on the way out, even if thunk throws."
  [thunk]
  (let [calls    (atom [])
        original (.-warn js/console)]
    (try
      (set! (.-warn js/console)
            (fn [& args] (swap! calls conj (apply str args))))
      (thunk)
      @calls
      (finally
        (set! (.-warn js/console) original)))))

;; ---- Fragment root: warning fires exactly once per id ---------------------

(deftest fragment-root-warn-fires-once-across-multiple-renders-slim
  (testing "A Fragment-headed reg-view'd component under slim renders
            without the :data-rf2-source-coord attribute AND emits the
            documented warning EXACTLY ONCE — the second through fifth
            re-render do NOT re-emit. The warned-set is a `defonce` atom
            in re-frame.views (shared with the Reagent bridge), so a
            unique id per test avoids cross-test contamination."
    (rf/reg-view* :rf.slim-warn-once-test/fragment-multi
                  (fn [] [:<> [:p "a"] [:p "b"]]))
    (let [render   (rf/view :rf.slim-warn-once-test/fragment-multi)
          warnings (with-captured-console-warn
                     (fn [] (dotimes [_ 5] (render))))]
      (is (= 1 (count warnings))
          (str "expected EXACTLY ONE warning across 5 renders of the "
               "Fragment-rooted slim view; got " (count warnings) ": "
               (pr-str warnings)))
      (is (str/includes? (first warnings) "rf.slim-warn-once-test/fragment-multi")
          "the single warning names the offending view-id")
      (is (str/includes? (first warnings) "data-rf2-source-coord")
          "the warning mentions the attribute that was skipped"))))

;; ---- Per-id silencing is independent across ids --------------------------

(deftest warn-once-is-per-id-not-global-slim
  (testing "The warn-once contract is keyed by view-id under slim too.
            Two different non-DOM-rooted views each emit their OWN
            one-shot warning (not a single global gate)."
    (rf/reg-view* :rf.slim-warn-once-test/fragment-id-a
                  (fn [] [:<> [:p "a"]]))
    (rf/reg-view* :rf.slim-warn-once-test/fragment-id-b
                  (fn [] [:<> [:p "b"]]))
    (let [render-a (rf/view :rf.slim-warn-once-test/fragment-id-a)
          render-b (rf/view :rf.slim-warn-once-test/fragment-id-b)
          warnings (with-captured-console-warn
                     (fn [] (render-a) (render-b) (render-a) (render-b)))]
      (is (= 2 (count warnings))
          (str "expected EXACTLY TWO warnings (one per id) across 4 renders; got "
               (count warnings) ": " (pr-str warnings)))
      (is (some #(str/includes? % "fragment-id-a") warnings) "id-a's warning fired")
      (is (some #(str/includes? % "fragment-id-b") warnings) "id-b's warning fired"))))

;; ---- Interop / fn-headed roots also warn-once -----------------------------

(deftest interop-root-warn-fires-once-slim
  (testing "A `:>` interop-headed root is on the same exemption list as
            Fragments; under slim it MUST warn exactly once per id across
            renders. Per Spec 006 §Documented exemption: non-DOM roots."
    (rf/reg-view* :rf.slim-warn-once-test/interop-multi
                  (fn [] [:> "div" {} "body"]))
    (let [render   (rf/view :rf.slim-warn-once-test/interop-multi)
          warnings (with-captured-console-warn
                     (fn [] (dotimes [_ 5] (render))))]
      (is (= 1 (count warnings))
          (str "expected EXACTLY ONE warning across 5 renders of the "
               "interop-rooted slim view; got " (count warnings) ": "
               (pr-str warnings)))
      (is (str/includes? (first warnings) "interop-multi")
          "the single warning names the interop-rooted view-id"))))
