(ns re-frame.bench.hicasso.front.slot-cljs-test
  "THE EQUIVALENCE PIN for the canonical slot rule (rf2-ani6y).

  This file is `.cljc` on purpose, and that is the whole mechanism. The
  repo's lane bijection (`scripts/check_test_lane_bijection.py` rule B2)
  requires a `.cljc` suite under a CLJS-owned test root to be selected by
  a CLJS lane as well as the JVM one, so [[corpus]] below is asserted
  twice against ONE implementation: once by `npm run test:cljs` in Node
  and once by `clojure -M:test` on the JVM, in
  `implementation/freehand`.

  ## What it would catch

  The rule has exactly one definition
  ([[re-frame.bench.hicasso.front.slot/prop-name]]), so the interesting
  failure is not \"someone edited one copy\". It is the two ways one
  definition can still answer two things:

  1. **A reader conditional.** `#?(:clj … :cljs …)` inside the rule, or
     inside anything it calls, splits it silently — the file still reads
     as one implementation and the two hosts stop agreeing. Whichever
     host the conditional favours stays green; the other goes red here,
     naming the key.
  2. **A host-differing primitive.** `str/upper-case` is
     `.toUpperCase()` on both hosts, but the JVM's consults the platform
     DEFAULT LOCALE — on a Turkish-locale JVM `\"i\"` upper-cases to
     `\"İ\"`, so `:aria-index`, `:on-input` and every other key whose
     camel hump lands on an `i` would slot differently from the
     browser's answer. `str/split`'s trailing-empty handling is a second
     such seam. Nothing here guards against either, deliberately: a
     guard would be a guess about which host is right. This suite makes
     the disagreement loud on the host that has it.

  And the codec's own agreement — that its CACHE still answers what the
  rule answers, seeded entries included — is asserted against this same
  corpus in `codec-cljs-test`, which is CLJS-only because the caches
  are."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])
            [re-frame.bench.hicasso.front.slot :as slot]))

(def corpus
  "Authored prop key → the canonical React slot it emits into.

  ONE table, read by both hosts and by the codec's cache suite. Every
  branch of the rule is represented, and every spelling the codec accepts
  appears at least once — because a rule written against the spelling is
  a rule the other spellings walk past, and a corpus that only carries
  bare kebab keywords could not tell."
  {;; the three React renames — the RULE, so they hold for every spelling
   :class          "className"
   :className      "className"
   "class"         "className"
   :x/class        "className"
   'class          "className"
   :for            "htmlFor"
   "for"           "htmlFor"
   :x/for          "htmlFor"
   :charset        "charSet"
   'charset        "charSet"

   ;; kebab → camel
   :on-click       "onClick"
   :tab-index      "tabIndex"
   :on-mouse-enter "onMouseEnter"
   :default-value  "defaultValue"
   :a-b-c          "aBC"
   'on-click       "onClick"
   :x/on-click     "onClick"

   ;; a hump the author already wrote survives — this is why the rule
   ;; carries its own `capitalize` rather than `str/capitalize`, which
   ;; would lower-case the tail and hand React `Viewbox`
   :view-Box       "viewBox"
   :on-Click       "onClick"

   ;; nothing to camelCase
   :x              "x"
   :onInput        "onInput"
   :viewBox        "viewBox"
   :id             "id"
   :ref            "ref"
   :key            "key"

   ;; aria/data are HTML attribute names in React too
   :aria-label     "aria-label"
   :aria-hidden    "aria-hidden"
   :data-index     "data-index"
   :data-testid    "data-testid"
   'aria-label     "aria-label"
   :x/data-index   "data-index"

   ;; a CSS custom property is preserved verbatim
   :--gap          "--gap"
   :--my-custom-x  "--my-custom-x"

   ;; a string is already a React name and is taken verbatim, apart from
   ;; the three renames above — so `"on-input"` and `:on-input` are
   ;; DIFFERENT slots
   "on-input"      "on-input"
   :on-input       "onInput"
   "onInput"       "onInput"
   "data-x"        "data-x"
   "--gap"         "--gap"
   "aria-label"    "aria-label"

   ;; the prototype-poisoning names are the codec's problem, not the
   ;; rule's; the rule answers them like any other name
   :__proto__      "__proto__"
   :constructor    "constructor"})

(deftest the-slot-rule-answers-the-corpus
  (doseq [[k expected] corpus]
    (is (= expected (slot/prop-name k))
        (str "slot for " (pr-str k)))))

(deftest the-slot-rule-is-a-pure-function-of-the-key
  (testing "the same key answers the same slot however often it is asked,
            on a rule that holds no state at all — the codec's caches are
            an accelerant over this, never a source of a different answer"
    (doseq [[k expected] corpus]
      (is (= expected (slot/prop-name k) (slot/prop-name k))
          (str "repeated for " (pr-str k))))))

(deftest every-branch-of-the-rule-is-represented
  (testing "a corpus that lost a branch would go on passing while the
            branch it stopped covering diverged between the hosts, so the
            branches are counted rather than assumed"
    (let [slots (into #{} (map val) corpus)]
      (is (contains? slots "className") "the rename table")
      (is (contains? slots "onClick") "kebab → camel")
      (is (contains? slots "aria-label") "the aria exemption")
      (is (contains? slots "data-index") "the data exemption")
      (is (contains? slots "--gap") "the custom-property passthrough")
      (is (contains? slots "on-input") "a string, taken verbatim"))
    (let [ks (set (keys corpus))]
      (is (some string? ks) "a string spelling")
      (is (some symbol? ks) "a symbol spelling")
      (is (some #(and (keyword? %) (namespace %)) ks) "a namespaced spelling")
      (is (some #(and (keyword? %) (not (namespace %))) ks) "a bare keyword"))))
