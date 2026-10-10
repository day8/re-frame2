(ns re-frame.bench.fresco.walk-profile-baseline-cljs-test
  "THE ABLATION BASELINE IS THE *OLD* WALK.

  `walk_profile_app`'s `local` arm is both the ablation baseline and the
  OLD arm of the in-process A/B that prices the codec candidate. Every
  shipping `front.codec` helper the baseline reaches for is a piece of the
  candidate it has already absorbed, and the A/B then quotes a smaller
  reduction with no arm going red. So both halves of its prop conversion
  are frozen locally (`local-prop-cache`, `local-convert-prop-value`).

  Two claims: the frozen converter answers what the shipping one answers
  (why the freeze is safe), and the baseline arm never ENTERS the shipping
  helpers (why the freeze is there). No output tells `string?`-first from
  `fn?`-first, so the second counts entries, each beside a CONTROL that
  shows the probe live. `codec/cached-parse`'s reserved-name check stays in
  the baseline deliberately — `local-convert-prop-value`'s docstring says
  why — so nothing here asserts it."
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [re-frame.bench.fresco.front.codec :as rf.bench.fresco.front.codec]
            [re-frame.bench.fresco.walk-profile-app :as rf.bench.fresco.walk-profile-app]))

(use-fixtures :each {:before (fn [] (rf.bench.fresco.front.codec/reset-caches!))})

(def ^:private keyword-keyed
  "Census-shaped markup: keyword-keyed attributes, string values, a
  propless element, a nested `:style` map and a literal `:key`."
  [:div.home-page
   [:div.banner
    [:h1.logo-font "conduit"]]
   [:a.nav-link {:href "#" :data-testid "your-feed-tab" :class "active"}
    "Your Feed"]
   [:div.tag-list {:data-testid "tag-list" :style {:background-color "red"}}
    [:a.tag-pill.tag-default {:key "clj" :href "#" :data-testid "tag-clj"} "clj"]]])

(def ^:private string-keyed
  "The one spelling that reaches `cached-prop-name` on the SHIPPING side,
  so the prop-name control has something to count."
  [:div {"data-testid" "string-keyed" "aria-label" "x"}])

(defn- entries
  "How many times `thunk` enters the shipping fn `install!` wraps."
  [install! restore! thunk]
  (let [n (atom 0)]
    (install! n)
    (try (thunk) (finally (restore!)))
    @n))

(deftest the-frozen-converter-answers-what-the-shipping-one-answers
  (let [shipping rf.bench.fresco.front.codec/convert-prop-value
        local    rf.bench.fresco.walk-profile-app/local-convert-prop-value
        by-identity ["href" (fn [_] nil) #js {:a 1} (array 1 2) 42 nil true false]
        converted   [:active 'sym {:background-color "red" :z-index 3} {:outer {:font-weight "bold"}}
                     ["a" "b"] #{"a"} [:conduit/show-your-feed]]]
    (is (= (mapv shipping by-identity) (mapv local by-identity))
        "the classes the codec passes through, passed through by identity")
    (is (= (mapv (comp js->clj shipping) converted) (mapv (comp js->clj local) converted))
        "the classes the codec converts, converted the same way")
    (is (= [{"backgroundColor" "red"} false]
           [(js->clj (local {:background-color "red"})) (identical? shipping local)])
        "it really converts, and it is a distinct function — an alias of the
         shipping var would satisfy both equalities above")))

(deftest the-baseline-arm-never-enters-the-shipping-value-converter
  (let [real     rf.bench.fresco.front.codec/convert-prop-value
        install! (fn [n] (set! rf.bench.fresco.front.codec/convert-prop-value
                               (fn [v] (swap! n inc) (real v))))
        restore! (fn [] (set! rf.bench.fresco.front.codec/convert-prop-value real))
        walk     (fn [mode] (entries install! restore!
                                     #(rf.bench.fresco.walk-profile-app/walk-arm mode keyword-keyed)))]
    (is (= [true 0 0]
           [(pos? (entries install! restore! #(rf.bench.fresco.front.codec/as-element keyword-keyed)))
            (walk rf.bench.fresco.walk-profile-app/M-FULL)
            (walk rf.bench.fresco.walk-profile-app/M-NO-VALUE)])
        "the shipping walk enters it (the probe is live); the local arm and the
         no-value ablation never do")))

(deftest the-baseline-arm-never-enters-the-shipping-prop-name-cache
  (let [real     rf.bench.fresco.front.codec/cached-prop-name
        install! (fn [n] (set! rf.bench.fresco.front.codec/cached-prop-name
                               (fn [k] (swap! n inc) (real k))))
        restore! (fn [] (set! rf.bench.fresco.front.codec/cached-prop-name real))
        walk     (fn [markup] (entries install! restore!
                                       #(rf.bench.fresco.walk-profile-app/walk-arm rf.bench.fresco.walk-profile-app/M-FULL markup)))]
    (is (= [true 0 0]
           [(pos? (entries install! restore! #(rf.bench.fresco.front.codec/as-element string-keyed)))
            (walk keyword-keyed)
            (walk string-keyed)])
        "the shipping walk enters it on a string-keyed map; the local arm
         names keyword- and string-keyed props through its own cache")))
