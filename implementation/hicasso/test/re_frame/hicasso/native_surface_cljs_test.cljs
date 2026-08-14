(ns re-frame.hicasso.native-surface-cljs-test
  "THE NATIVE TIER'S PUBLIC SURFACE, STATED AND PINNED (rf2-e0d2).

  `specification.md` §12 has Phase 3 freeze *the grammar and the ABI*,
  and a freeze is a deterministic act OVER A STATED MEMBERSHIP. The
  membership of `re-frame.hicasso.native` was not stated: its public vars
  spanned consumer doors, tier seams and macro-expansion targets, five
  carried naming-ledger rows, and nothing marked which was which.

  The classification itself — what each var IS, and one line saying why —
  lives in `native.cljc`'s own namespace docstring, where a reader
  arrives at it. This file pins the MEMBERSHIP that classification is
  over, so the two cannot drift: a var added to that namespace without a
  line in one of the two sets below reds here, at the diff that adds it,
  which is the rule this repository already applies to a facade export.

  ## Why a macro reads it

  ClojureScript has no runtime `ns-publics`, and this namespace is a
  self-requiring `.cljc` whose macros are Clojure vars while its runtime
  defs are analyser records — two halves, neither of which sees the
  other. [[re-frame.hicasso.expansion-probe/public-vars]] asks both at
  expansion and hands the union here as data.

  ## The internal half's licence is CHECKED, not asserted

  Three of the four internal vars are public because a macro expansion in
  the CONSUMER's namespace names them, and that is a claim about an
  expansion rather than about a docstring — so
  [[the-internal-half-is-public-for-a-reason-the-expansion-can-show]]
  reads the expansions and finds them. The fourth, `n/prop-slots`, is
  named by no expansion at all, and the row says so rather than letting
  it shelter under the other three's reason."
  (:require [clojure.set :as set]
            [cljs.test :refer-macros [deftest is testing]]
            [re-frame.hicasso.native :as n])
  (:require-macros [re-frame.hicasso.expansion-probe :as probe]))

;; ---------------------------------------------------------------------------
;; The classification, as the two sets a freeze is over
;; ---------------------------------------------------------------------------

(def ^:private surface
  "**SURFACE** — the tier's public contract. A consumer writes it, or a
  tool reads it, and Phase 3 freezes it. Each name's one-line
  justification is in `native.cljc`'s namespace docstring; the ledger
  rows are in `docs/design/hicasso/product/naming-ledger.md`."
  #{"$"                 ; the one native authoring form (ledger row 7)
    "props"             ; the explicit dynamic props operand (row 8)
    "defcomponent"      ; the island declaration door (row 9)
    "use-sub"           ; a subscription read, in hook position (row 10)
    "use-frame"         ; frame-locked operations, in hook position (row 10)
    "memo"              ; React.memo with the marker carried (row 29)
    "lazy"              ; React.lazy, marker carried, chunk gated (row 29)
    "component"         ; the mint door, named by a chunk's own def (row 43)
    "marker"            ; the seam every ABI helper and both crossings read (row 44)
    "tier-sentinel"})   ; the string a bundle scan looks for (row 45)

(def ^:private internal
  "**INTERNAL** — not a consumer surface, and public only for a stated
  reason (ledger row 46). Three are named by a macro expansion in the
  CONSUMER's namespace and therefore cannot be private at all; the fourth
  is public because a witness drives it directly. Nothing here is frozen
  as an authoring API, and `n/declared-server`'s own spelling stays open
  (naming-findings C3-2)."
  #{"el"                ; named by `$`'s expansion
    "props*"            ; named by `props`'s expansion
    "declared-server"   ; named by `defcomponent`'s expansion
    "prop-slots"})      ; the shared rule, driven directly by the parity row

(def ^:private publics
  "Every public var name in `re-frame.hicasso.native`, read from the
  compiler at expansion — the analyser's public defs for the runtime
  half, the JVM namespace's public vars for the macro half."
  (set (probe/public-vars re-frame.hicasso.native)))

;; ---------------------------------------------------------------------------
;; The census
;; ---------------------------------------------------------------------------

(deftest every-public-var-of-the-native-namespace-is-classified
  (testing "the census is not vacuous: the probe really read a namespace,
            and it found the macros as well as the runtime defs.
            Narrowing caught: an analyser lookup answering an empty map on
            a miss, which would make every row below pass by describing
            nothing"
    (is (contains? publics "$"))
    (is (contains? publics "defcomponent"))
    (is (contains? publics "use-sub"))
    (is (contains? publics "marker"))
    (is (< 10 (count publics))))

  (testing "every public var falls in exactly one of the two classes.
            THIS is the row a Phase 3 freeze rests on: an unclassified
            public var is a member of a frozen surface nobody ruled on,
            and it reds here at the diff that adds it rather than at the
            freeze"
    (is (= #{} (set/difference publics surface internal))
        "unclassified public vars — classify each in native.cljc's
         namespace docstring, then add it to `surface` or `internal` here")
    (is (= #{} (set/intersection surface internal))
        "a name cannot be both"))

  (testing "and neither set names a var that is not there. Narrowing
            caught: a classification kept for a var since made private or
            renamed, which would leave the record describing a surface
            that no longer exists"
    (is (= #{} (set/difference (set/union surface internal) publics))))

  (testing "the tier's own private helpers are NOT in the census, which
            is what makes it a public-surface census rather than a file
            listing. `check-child!` is the one this bead made private —
            it is named by no expansion and reached by nothing outside its
            namespace, so its publicity bought nothing"
    (is (not (contains? publics "check-child!")))
    (is (not (contains? publics "checked")))
    (is (not (contains? publics "mint-server-gate")))))

(deftest the-internal-half-is-public-for-a-reason-the-expansion-can-show
  (testing "`el` cannot be private: `$` emits a call to it in the
            CONSUMER's namespace. Narrowing caught: a classification that
            merely asserted the licence — this row goes and reads the
            expansion for it"
    (let [out (probe/expansion (re-frame.hicasso.native/$ :div nil "kid"))]
      (is (= :expanded (:outcome out)))
      (is (contains? (:names out) 're-frame.hicasso.native/el))))

  (testing "`props*` likewise, from the `props` marker's expansion"
    (let [dynamic-map {:class "c"}
          out         (probe/expansion
                        (re-frame.hicasso.native/props dynamic-map))]
      (is (= :expanded (:outcome out)))
      (is (contains? (:names out) 're-frame.hicasso.native/props*))))

  (testing "`declared-server` and `component` both, from
            `defcomponent`'s. `component` is on the SURFACE side as well,
            because a code-split chunk's own `def` reaches it by name —
            the two reasons are independent and it needs only one"
    (let [out (probe/expansion
                (re-frame.hicasso.native/defcomponent probed-island
                  [^js _props] nil))]
      (is (= :expanded (:outcome out)))
      (is (contains? (:names out) 're-frame.hicasso.native/declared-server))
      (is (contains? (:names out) 're-frame.hicasso.native/component))))

  (testing "NON-MEMBER: `prop-slots` is named by NO expansion — both its
            callers are inside its own namespace — so it does not shelter
            under the licence above. It is public because the three-way
            parity row drives it as its own arm, and a fixture that could
            only reach the rule through one of its two callers would be
            pinning that caller rather than the rule"
    (let [dynamic-map {:class "c"}
          names       (into #{}
                            (mapcat :names)
                            [(probe/expansion
                               (re-frame.hicasso.native/$ :div {:class "c"}))
                             (probe/expansion
                               (re-frame.hicasso.native/props dynamic-map))
                             (probe/expansion
                               (re-frame.hicasso.native/defcomponent probed-island-2
                                 [^js _props] nil))])]
      (is (not (contains? names 're-frame.hicasso.native/prop-slots))))
    (is (= [["className" "card"]] (n/prop-slots {:class "card"} 'test))
        "and it answers the shared rule directly, which is the reach the
         classification is buying"))

  (testing "the surface names all resolve, so the SURFACE half is a claim
            about reachable vars rather than a list of strings"
    (is (some? n/marker))
    (is (some? n/component))
    (is (string? n/tier-sentinel))))
