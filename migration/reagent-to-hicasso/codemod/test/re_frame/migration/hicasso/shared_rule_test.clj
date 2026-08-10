(ns re-frame.migration.hicasso.shared-rule-test
  "The two conversion tables, pinned where this tool can reach them.

  ## Why the first test matters more than it looks

  The design's §9.5 charge — *\"the tool reimplements `prop-name`, and
  nothing pins the two together\"* — survived its own adversarial pass
  UNREPAIRED, and §10.3 named it the open question the page could not
  close: a corpus pins the tool, a DOM suite pins the runtime, and nothing
  pins them equal, with the drift silent in both directions.

  §10.2's third recommendation was the only structural answer, and
  rf2-ani6y took it: the slot rule moved to `front/slot.cljc`, the one
  file both hosts can load. This tool therefore does not *mirror*
  `prop-name` — it CALLS it. [[the-slot-rule-is-the-shared-one]] is what
  keeps that true, by asserting the function came out of the shared file
  rather than out of this artefact's own tree. Copy the rule in here to
  \"remove a dependency\" and that test goes red, which is the whole
  point.

  ## What is still mirrored, and honestly labelled

  `codec/html-attr-slots` and `intent/event-prop?` live in `.cljs` this
  JVM cannot load, so [[re-frame.migration.hicasso.dest]] carries small
  transcriptions of both. Those are conventions, not pins, and the design
  says so. The rows below assert the transcription's behaviour so a
  reader can diff two short tables by eye.

  ## The donor's two deltas

  Reagent's `cached-prop-name` and our `slot/prop-name` are one rule apart
  from two cells. Each row below cites the executed donor witness that
  pins it — `codemod-contract-donor-*` in
  `implementation/adapters/reagent/test/re_frame/reagent_codemod_contract_donor_cljs_test.cljs`
  (rf2-d2mwk)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [re-frame.bench.hicasso.front.slot :as slot]
            [re-frame.migration.hicasso.dest :as dest]
            [re-frame.migration.hicasso.donor :as donor]
            [re-frame.migration.hicasso.rewrite :as rw]
            [rewrite-clj.parser :as p]))

;; ---------------------------------------------------------------------------
;; There is no second copy
;; ---------------------------------------------------------------------------

(deftest the-slot-rule-is-the-shared-one
  (testing "the tool's slot resolver IS `slot/prop-name`, by identity —
            not a transcription of it"
    (is (identical? dest/canonical-slot slot/prop-name)))

  (testing "and it is loaded from the SHARED `.cljc`, not from a copy
            inside this artefact. rf2-ani6y extracted that file precisely
            so the tool and the door cannot hold two answers; a copy in
            `src/` would satisfy every other test in this suite and
            silently re-open the design's §10.3."
    (let [url (io/resource "re_frame/bench/hicasso/front/slot.cljc")
          path (some-> url .getPath (str/replace "\\" "/"))]
      (is (some? url) "the shared slot rule is on the classpath")
      (is (str/includes? path "implementation/freehand/test/re_frame/bench/hicasso/front/slot.cljc")
          (str "the slot rule must come from the shared implementation file; it came from "
               path))
      (is (not (str/includes? path "/migration/reagent-to-hicasso/"))
          "a copy of the slot rule has appeared inside the codemod's own tree"))))

;; ---------------------------------------------------------------------------
;; The one mirror the tool REPRINTS, held against the door's own file
;; ---------------------------------------------------------------------------

(def ^:private door-file
  "`mint-host!`'s namespace. `.cljs`, so this JVM cannot load it — but it
  can READ it, and for the one roster the tool prints back to a migrator
  that is worth doing."
  (io/file ".." ".." ".." "implementation" "hicasso" "src" "re_frame"
           "hicasso" "impl" "codec.cljs"))

(defn- door-contracts
  "The contract roster as the DOOR spells it, lifted out of its own
  source text."
  []
  (some-> (re-find #"\(def \^:private callback-contracts[\s\S]*?(#\{[^}]*\})" (slurp door-file))
          second
          edn/read-string))

(deftest the-callback-contracts-are-the-door's
  (testing "the door's file is where this test thinks it is — a moved
            file must red this pin rather than silently skip it"
    (is (.exists door-file) (str "the door is not at " door-file)))

  (testing "rf2-vi11 — the report PRINTS this roster into the `defhost`
            sketch it invites a migrator to paste, so a fourth contract at
            the door, or a renamed one, makes the tool's own advice wrong.
            `html-attr-slots` and `re-event-prop` are mirrors the design
            calls conventions; this one is a mirror with an alarm on it."
    (let [door (door-contracts)]
      (is (set? door) "the door's `callback-contracts` set was not found in its source")
      (is (= door (set dest/callback-contracts))
          (str "the door accepts " (pr-str door) " and this tool prints "
               (pr-str dest/callback-contracts)))))

  (testing "`:fn` — the keyword the sketch used to print at every
            position — is not among them, which is the whole of the bug"
    (is (not (contains? (door-contracts) :fn)))))

;; ---------------------------------------------------------------------------
;; The donor's key function, and its two deltas from the shared rule
;; ---------------------------------------------------------------------------

(deftest the-donor-key-rule
  (testing "kebab→camel, which is `dash-to-prop-name`
            (donor witness: codemod-contract-donor-w2-nested-map-keys)"
    (is (= "pageSize"  (donor/key-name :page-size)))
    (is (= "firstName" (donor/key-name :first-name)))
    (is (= "otherKey"  (donor/key-name :other-key))))

  (testing "the three seeded renames, which hold for every spelling of the
            same attribute because the cache is keyed on `(name k)`"
    (is (= "className" (donor/key-name :class)))
    (is (= "htmlFor"   (donor/key-name :for)))
    (is (= "charSet"   (donor/key-name :charset)))
    (is (= "className" (donor/key-name :x/class))))

  (testing "`aria` and `data` are exempt"
    (is (= "aria-label" (donor/key-name :aria-label)))
    (is (= "data-kind"  (donor/key-name :data-kind))))

  (testing "symbols take the same arm — `named?` is keyword-or-symbol"
    (is (= "pageSize" (donor/key-name 'page-size))))

  (testing "DELTA 1 — a STRING key is verbatim under the donor, seeded
            renames included, where our own rule applies them to every
            spelling. This is the cell that makes a transcription of
            `prop-name` wrong for W2's purposes."
    (is (= "first-name" (donor/key-name "first-name")))
    (is (= "class"      (donor/key-name "class")))
    (is (= "className"  (slot/prop-name "class"))
        "the shared rule's answer for the same key, for contrast"))

  (testing "DELTA 2 — a CSS custom property is DETECTED and refused, never
            reproduced. Reagent mangled `--brand-color` into `BrandColor`,
            a style key nothing reads; preserving that would mean writing
            a key that never worked."
    (is (donor/css-var-name? "--brand-color"))
    (is (nil? (donor/key-name :--brand-color)))
    (is (= "--brand-color" (slot/prop-name :--brand-color))
        "our rule preserves it, and React routes it through `setProperty`"))

  (testing "everywhere else the two rules agree, which is why `key-name`
            delegates rather than transcribes"
    (doseq [k [:page-size :first-name :other-key :class :for :charset
               :aria-label :data-kind :onClick :on-click]]
      (is (= (slot/prop-name k) (donor/key-name k))
          (str "the two rules should agree at " k)))))

;; ---------------------------------------------------------------------------
;; The destination vocabulary (mirrors, labelled as such)
;; ---------------------------------------------------------------------------

(deftest the-destination-vocabulary
  (testing "`html-attr-slot?` mirrors the codec's, prefix families included"
    (is (every? dest/html-attr-slot? ["className" "id" "role" "data-kind" "aria-label"]))
    (is (not-any? dest/html-attr-slot? ["variant" "theme" "key" "ref" "onClick"])))

  (testing "`event-prop?` mirrors `intent/event-prop?`, INCLUDING its type
            gate — a prop key spelled `on-click` as a SYMBOL is not an
            event position, so the tool must not refuse there"
    (is (dest/event-prop? :on-click))
    (is (dest/event-prop? :onClick))
    (is (dest/event-prop? "on-click"))
    (is (not (dest/event-prop? 'on-click)) "symbols answer false")
    (is (not (dest/event-prop? :once)) "`on` followed by a lowercase letter is not `on-`")
    (is (not (dest/event-prop? :online))))

  (testing "a nested map key's destination name is `clj->js`'s answer,
            which is what W2 has to make agree with the donor"
    (is (= "page-size" (dest/nested-key-name :page-size)))
    (is (= "x/page-size" (dest/nested-key-name 'x/page-size)))
    (is (= "first-name" (dest/nested-key-name "first-name"))))

  (testing "`dangerouslySetInnerHTML` is recognised in any spelling,
            because the kebab one never reached React's exact name under
            either runtime and the migrator needs telling regardless"
    (is (dest/dangerous-html-key? :dangerouslySetInnerHTML))
    (is (dest/dangerous-html-key? :dangerously-set-inner-html))
    (is (dest/dangerous-html-key? "dangerouslySetInnerHTML"))
    (is (not (dest/dangerous-html-key? :dangerous)))))

;; ---------------------------------------------------------------------------
;; Amendment (B), at the unit
;; ---------------------------------------------------------------------------

(defn- collisions [src] (rw/injective-keys (p/parse-string src)))

(deftest amendment-b-injectivity
  (testing "an ordinary camel-case collision"
    (is (= [{:slot "fooBar" :keys [":foo-bar" ":fooBar"]}]
           (collisions "{:foo-bar 1 :fooBar 2}"))))

  (testing "a seeded-rename collision"
    (is (= [{:slot "className" :keys [":class" ":className"]}]
           (collisions "{:class \"a\" :className \"b\"}"))))

  (testing "a keyword/string collision — the donor takes the string
            verbatim and camelCases the keyword onto the same name"
    (is (= [{:slot "fooBar" :keys ["\"fooBar\"" ":foo-bar"]}]
           (collisions "{:foo-bar 1 \"fooBar\" 2}"))))

  (testing "three sources onto one slot are all named, not just the pair"
    (is (= [{:slot "fooBar" :keys ["\"fooBar\"" ":foo-bar" ":fooBar"]}]
           (collisions "{:foo-bar 1 :fooBar 2 \"fooBar\" 3}"))))

  (testing "an injective map is not refused"
    (is (nil? (collisions "{:page-size 10 :first-name \"a\"}")))
    (is (nil? (collisions "{:aria-label 1 :data-kind 2}")))
    (is (nil? (collisions "{\"first-name\" 1 :first-name 2}"))
        "the donor takes the string verbatim, so these are two DIFFERENT
         slots — `first-name` and `firstName` — and neither collides"))

  (testing "a CSS custom property cannot be half of a minted duplicate,
            because it is never respelled"
    (is (nil? (collisions "{:--brand-color \"red\" :font-size 12}")))))
