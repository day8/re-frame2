(ns re-frame.bench.hicasso.walk-profile-baseline-cljs-test
  "THE ABLATION BASELINE IS THE *OLD* WALK — pinned (rf2-y1jkm).

  `walk_profile_app`'s `local` arm is two things at once: the ablation
  baseline every phase delta is subtracted from, and the OLD arm of the
  in-process A/B that priced the rf2-y1jkm codec candidate. The second
  role is the fragile one. Every helper the candidate cheapened that the
  baseline still reaches for in `front.codec` is a piece of the
  candidate the baseline has already absorbed, and the A/B then quotes a
  reduction smaller than the one it took — silently, with no arm going
  red, because both numbers still look plausible.

  That is not hypothetical here: it is what the merged-PR #7383 audit
  found. `walk-value` called `codec/convert-prop-value`, and that same
  PR reordered `convert-prop-value`'s branches. The prop-NAME half was
  frozen locally in the PR itself (`local-prop-cache`); the prop-VALUE
  half was not, and this file exists because the fix for that is exactly
  the kind of thing a later refactor re-breaks by reaching for the
  obvious `codec/` name.

  ## What is asserted, and why in this shape

  1. The frozen converter answers what the shipping one answers, value
     for value and identity for identity. Only the ORDER of the tests
     differs, so a freeze that changed an answer would be a bug in the
     instrument rather than a baseline.
  2. The baseline arm never ENTERS the shipping helpers. There is no
     output that distinguishes `string?`-first from `fn?`-first — both
     converters are total and agree everywhere — so the only honest
     witness counts entries rather than compares results. Each count is
     paired with a CONTROL that drives the shipping walk through the
     same probe, so a zero can never be read off a probe that was never
     live.

  Reverting `walk-value` to `codec/convert-prop-value` fails (2) and
  leaves (1) green, which is the whole point: (1) is why the freeze is
  safe, (2) is why the freeze is there.

  ## Deliberately not asserted

  `codec/cached-parse` keeps the candidate's cheaper reserved-name check
  in the baseline. That residue is deliberate and reasoned on
  `walk-profile-app/local-convert-prop-value`'s docstring — the
  `parse-raw` benefit line prices the tag cache and needs both its arms
  on one parse implementation — so a test here would only re-litigate
  it, and would go red the day someone freezes it properly."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.bench.hicasso.front.codec :as codec]
            [re-frame.bench.hicasso.walk-profile-app :as app]))

(use-fixtures :each {:before (fn [] (codec/reset-caches!))})

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(def ^:private keyword-keyed
  "Census-shaped markup: keyword-keyed attribute maps, string values, a
  propless element (the short-circuit lane), a nested `:style` map (the
  converter's recursive branch) and a literal `:key`."
  [:div.home-page
   [:div.banner
    [:h1.logo-font "conduit"]]
   [:a.nav-link {:href "#" :data-testid "your-feed-tab" :class "active"}
    "Your Feed"]
   [:div.tag-list {:data-testid "tag-list" :style {:background-color "red"}}
    [:a.tag-pill.tag-default {:key "clj" :href "#" :data-testid "tag-clj"} "clj"]]])

(def ^:private string-keyed
  "A string-keyed attribute map — the one spelling that reaches
  `codec/cached-prop-name` on the SHIPPING side, so the control for the
  prop-name probe has something to count."
  [:div {"data-testid" "string-keyed" "aria-label" "x"}])

(defn- probe
  "Run `thunk` with `v` installed over the shipping var `restore` names,
  counting entries. Answers the count."
  [install! restore! thunk]
  (let [n (atom 0)]
    (install! n)
    (try (thunk) (finally (restore!)))
    @n))

;; ---------------------------------------------------------------------------
;; 1. The freeze is behaviour-preserving
;; ---------------------------------------------------------------------------

(deftest the-frozen-converter-answers-what-the-shipping-one-answers
  (testing "the classes the codec answers BY IDENTITY, answered by identity"
    (let [f      (fn [_] nil)
          obj    #js {:a 1}
          arr    (array 1 2)]
      (doseq [v ["href" f obj arr 42 nil true false]]
        (is (identical? v (app/local-convert-prop-value v))
            (str "frozen converter must pass " (pr-str v) " through by identity"))
        (is (identical? (codec/convert-prop-value v)
                        (app/local-convert-prop-value v))
            (str "frozen and shipping must agree by identity on " (pr-str v))))))

  (testing "the classes the codec CONVERTS, converted the same way"
    (doseq [v [:active
               'sym
               {:background-color "red" :z-index 3}
               {:outer {:font-weight "bold"}}
               ["a" "b"]
               #{"a"}
               [:conduit/show-your-feed]]]
      (is (= (js->clj (codec/convert-prop-value v))
             (js->clj (app/local-convert-prop-value v)))
          (str "frozen and shipping must agree on " (pr-str v)))))

  (testing "a nested map camelCases its keys, through the frozen name lookup"
    (is (= {"backgroundColor" "red"}
           (js->clj (app/local-convert-prop-value {:background-color "red"})))))

  (testing "the two are DISTINCT functions — a freeze that aliased the
            shipping var would satisfy every assertion above"
    (is (not (identical? codec/convert-prop-value app/local-convert-prop-value)))))

;; ---------------------------------------------------------------------------
;; 2. The baseline arm does not enter the shipping helpers
;; ---------------------------------------------------------------------------

(deftest the-baseline-arm-never-enters-the-shipping-value-converter
  (let [real codec/convert-prop-value
        install! (fn [n] (set! codec/convert-prop-value
                               (fn [v] (swap! n inc) (real v))))
        restore! (fn [] (set! codec/convert-prop-value real))]

    (testing "CONTROL — the shipping walk does enter it, so the probe is live"
      (is (pos? (probe install! restore! #(codec/as-element keyword-keyed)))
          "the shipping walk must reach convert-prop-value on this fixture"))

    (testing "the local arm converts every value through its OWN frozen copy"
      (is (zero? (probe install! restore! #(app/walk-arm app/M-FULL keyword-keyed)))
          (str "the `local` arm is the in-process A/B's OLD arm; entering the "
               "shipping converter absorbs the rf2-y1jkm candidate into the "
               "baseline it is measured against (merged-PR #7383 audit)")))

    (testing "and so does the no-value ablation, which converts nothing at all"
      (is (zero? (probe install! restore! #(app/walk-arm app/M-NO-VALUE keyword-keyed)))))))

(deftest the-baseline-arm-never-enters-the-shipping-prop-name-cache
  (let [real codec/cached-prop-name
        install! (fn [n] (set! codec/cached-prop-name
                               (fn [k] (swap! n inc) (real k))))
        restore! (fn [] (set! codec/cached-prop-name real))]

    (testing "CONTROL — the shipping walk does enter it on a string-keyed map"
      (is (pos? (probe install! restore! #(codec/as-element string-keyed)))
          "the shipping walk must reach cached-prop-name on this fixture"))

    (testing "the local arm names every prop through local-prop-name"
      (is (zero? (probe install! restore! #(app/walk-arm app/M-FULL keyword-keyed)))
          "keyword-keyed props must take the frozen string-valued cache")
      (is (zero? (probe install! restore! #(app/walk-arm app/M-FULL string-keyed)))
          "string-keyed props must take the frozen path too"))))
