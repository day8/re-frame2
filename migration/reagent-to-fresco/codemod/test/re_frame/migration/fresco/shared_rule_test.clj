(ns re-frame.migration.fresco.shared-rule-test
  "The two conversion tables, pinned where this tool can reach them.

  ## Why the first test matters more than it looks

  A tool that reimplements `prop-name` leaves nothing pinning the two
  together: a corpus pins the tool, a DOM suite pins the runtime, and the
  drift is silent in both directions (the design's §9.5 and §10.3). So the
  slot rule lives in a `.cljc` both hosts load — `impl/slot.cljc` in the
  shipped package — and this tool does not *mirror* `prop-name`, it CALLS
  it. [[the-slot-rule-is-the-shared-one]] keeps that true: copy the rule in
  here to \"remove a dependency\" and it goes red.

  ## What is still mirrored, and honestly labelled

  `codec/html-attr-slots` and `intent/event-prop?` live in `.cljs` this
  JVM cannot load, so [[re-frame.migration.fresco.dest]] carries small
  transcriptions of both. Those are conventions, not pins; the rows below
  assert the transcription's behaviour so a reader can diff two short
  tables by eye.

  ## The donor's two deltas

  Reagent's `cached-prop-name` and our `rf.fresco.impl.slot/prop-name` are
  one rule apart from two cells. The donor rows match the executed donor
  witnesses `codemod-contract-donor-*` in
  `implementation/adapters/reagent/test/re_frame/reagent_codemod_contract_donor_cljs_test.cljs`."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [re-frame.fresco.impl.slot :as rf.fresco.impl.slot]
            [re-frame.migration.fresco.dest :as rf.migration.fresco.dest]
            [re-frame.migration.fresco.donor :as rf.migration.fresco.donor]
            [re-frame.migration.fresco.rewrite :as rf.migration.fresco.rewrite]
            [rewrite-clj.parser :as p]))

;; ---------------------------------------------------------------------------
;; There is no second copy
;; ---------------------------------------------------------------------------

(deftest the-slot-rule-is-the-shared-one
  (testing "the tool's slot resolver IS `rf.fresco.impl.slot/prop-name`, by
            identity — not a transcription of it"
    (is (identical? rf.migration.fresco.dest/canonical-slot rf.fresco.impl.slot/prop-name)))

  (testing "and it is loaded from the shared `.cljc` IN THE SHIPPED PACKAGE:
            a copy in `src/` would satisfy every other test in this suite
            and silently re-open the design's §10.3"
    (let [path (some-> (io/resource "re_frame/fresco/impl/slot.cljc") .getPath (str/replace "\\" "/"))]
      (is (str/includes? path "implementation/fresco/src/re_frame/fresco/impl/slot.cljc")
          (str "the slot rule must come from the shipped package's shared file; it came from "
               path)))))

;; ---------------------------------------------------------------------------
;; The one mirror the tool REPRINTS, held against the door's own file
;; ---------------------------------------------------------------------------

(def ^:private door-file
  "`mint-host!`'s namespace. `.cljs`, so this JVM cannot load it — but it
  can READ it, and for the one roster the tool prints back to a migrator
  that is worth doing."
  (io/file ".." ".." ".." "implementation" "fresco" "src" "re_frame"
           "fresco" "impl" "codec.cljs"))

(defn- door-contracts
  "The contract roster as the DOOR spells it, lifted out of its own
  source text."
  []
  (some-> (re-find #"\(def \^:private callback-contracts[\s\S]*?(#\{[^}]*\})" (slurp door-file))
          second
          edn/read-string))

(deftest the-callback-contracts-are-the-doors
  (testing "the report PRINTS this roster into the `defhost` sketch it
            invites a migrator to paste, so a new contract at the door, or a
            renamed one, makes the tool's own advice wrong"
    (let [door (door-contracts)]
      (is (= door (set rf.migration.fresco.dest/callback-contracts))
          (str "the door accepts " (pr-str door) " and this tool prints "
               (pr-str rf.migration.fresco.dest/callback-contracts))))))

;; ---------------------------------------------------------------------------
;; The donor's key function, and its two deltas from the shared rule
;; ---------------------------------------------------------------------------

(deftest the-donor-key-rule
  (testing "keyword and symbol keys take the shared rule — kebab→camel, the
            seeded renames, the exempt `aria-`/`data-` prefixes — keyed on
            `(name k)`, so a namespaced spelling lands on its bare twin's
            slot. DELTA 1: a STRING key is verbatim under the donor, seeded
            renames included. DELTA 2: a CSS custom property is DETECTED and
            refused (`nil`), never reproduced — Reagent mangled
            `--brand-color` into `BrandColor`, a style key nothing reads."
    (is (= ["pageSize" "className" "className" "aria-label" "pageSize" "class" nil]
           (mapv rf.migration.fresco.donor/key-name
                 [:page-size :class :x/class :aria-label 'page-size "class" :--brand-color])))))

;; ---------------------------------------------------------------------------
;; The destination vocabulary (mirrors, labelled as such)
;; ---------------------------------------------------------------------------

(deftest the-destination-vocabulary
  (testing "`html-attr-slot?` mirrors the codec's, prefix families included"
    (is (= [true true true false]
           (mapv rf.migration.fresco.dest/html-attr-slot? ["className" "data-kind" "aria-label" "variant"]))))

  (testing "`event-prop?` mirrors `intent/event-prop?`, INCLUDING its type
            gate — a prop key spelled `on-click` as a SYMBOL is not an event
            position, so the tool must not refuse there — and `on` followed
            by a lowercase letter is not `on-`"
    (is (= [true true true false false]
           (mapv rf.migration.fresco.dest/event-prop? [:on-click :onClick "on-click" 'on-click :once]))))

  (testing "a nested map key's destination name is `clj->js`'s answer,
            which is what W2 has to make agree with the donor"
    (is (= ["page-size" "x/page-size" "first-name"]
           (mapv rf.migration.fresco.dest/nested-key-name [:page-size 'x/page-size "first-name"]))))

  (testing "`dangerouslySetInnerHTML` is recognised in any spelling, because
            the kebab one never reached React's exact name under either
            runtime and the migrator needs telling regardless"
    (is (= [true true true false]
           (mapv rf.migration.fresco.dest/dangerous-html-key?
                 [:dangerouslySetInnerHTML :dangerously-set-inner-html "dangerouslySetInnerHTML" :dangerous])))))

;; ---------------------------------------------------------------------------
;; Amendment (B), at the unit
;; ---------------------------------------------------------------------------

;; The pairwise collisions (camel-case, seeded rename, keyword/string), the
;; injective maps and the CSS custom property are pinned with their exact
;; `:collisions` data by the `w2-normalized-key-collisions` and
;; `w2-nested-map-keys` corpus cases. These are the two shapes the corpus
;; does not carry.

(defn- collisions [src] (rf.migration.fresco.rewrite/injective-keys (p/parse-string src)))

(deftest amendment-b-injectivity
  (testing "three sources onto one slot are all named, not just the pair"
    (is (= [{:slot "fooBar" :keys ["\"fooBar\"" ":foo-bar" ":fooBar"]}]
           (collisions "{:foo-bar 1 :fooBar 2 \"fooBar\" 3}"))))

  (testing "a string key and its keyword twin are not refused"
    (is (nil? (collisions "{\"first-name\" 1 :first-name 2}"))
        "the donor takes the string verbatim, so these are two DIFFERENT
         slots — `first-name` and `firstName` — and neither collides")))
