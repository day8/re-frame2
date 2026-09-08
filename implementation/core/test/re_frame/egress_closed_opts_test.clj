(ns re-frame.egress-closed-opts-test
  "rf2-kuky.6 — ONE policy vocabulary, and it is CLOSED at every egress door.

  ## The defect this pins

  On a privacy surface a RECOGNISED policy key could vanish without a
  signal, in both directions:

    1. `re-frame.elision/elide-wire-value` never read `:rf.egress/profile` — profiles are
       resolved a layer up, in `re-frame.projection`. Three normative
       teaching sites nevertheless passed one to the walker, where it was
       silently dropped: the call READ as though it had named the off-box
       boundary while the walk ran under the default policy.
    2. The epoch `project-egress` boundary read the two shared inclusion
       axes ONLY in their unqualified spelling, so a caller passing
       `:rf.size/include-sensitive? true` — the spelling the walker,
       `project-egress`, `:rf/project-egress-opts` and Conventions
       §`:rf.size/*` all use — was silently dropped there.

  Neither direction errored. Both read exactly like a policy that had been
  applied. The fix is that the opts maps are CLOSED and an unrecognised key
  is a loud `:rf.error/bad-egress-opts` naming it.

  ## Why closing cannot widen egress

  An unknown key did NOTHING before this change — it was neither read nor
  rejected. So no value that used to be redacted can now escape; the only
  new outcome is a throw where there used to be a silent no-op. That is why
  a closed map is a fail-CLOSED change on a privacy surface, and it is the
  claim the `unknown-key-cannot-widen-egress` deftest below pins directly.

  The three doors' key sets differ by exactly the keys each door OWNS —
  `project-egress` adds `:rf.egress/profile` to the walker's set, and the
  epoch door (pinned in `implementation/epoch/test`) trades the walk-shaping
  keys for its three epoch-local knobs. The sets are DERIVED from one
  another in source rather than re-spelled, so `walker-and-projection-sets-
  agree` below pins the derivation and not a transcription."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.elision :as rf.elision]
            [re-frame.projection :as rf.projection]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- bad-opts-ex-data
  "Run `f`, expecting a `:rf.error/bad-egress-opts` throw; return its
  `ex-data`. Returns `nil` when nothing was thrown, and RE-THROWS anything
  carrying a different `:rf.error/id` so a wrong-error failure reports the
  wrong error rather than an unhelpful nil."
  [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo e
      (let [d (ex-data e)]
        (if (= :rf.error/bad-egress-opts (:rf.error/id d))
          (assoc d ::message (ex-message e))
          (throw e))))))

;; ---------------------------------------------------------------------------
;; 1. The walker's closed opts
;; ---------------------------------------------------------------------------

(deftest walker-rejects-a-profile
  (testing "rf2-kuky.6 — `:rf.egress/profile` is NOT a walker opt. It names a
            BOUNDARY and is resolved by `project-egress`, which passes the
            resolved `:rf.size/*` opt-set down. Passing one to the walker was
            the original silent no-op; it now throws and the message says
            where the profile belongs."
    (let [d (bad-opts-ex-data
              #(rf.elision/elide-wire-value
                 {:a 1}
                 {:frame :app/main
                  :rf.egress/profile :rf.egress/off-box-tool}))]
      (is (some? d)
          "a profile handed to the walker throws :rf.error/bad-egress-opts")
      (is (= [:rf.egress/profile] (:unknown-keys d))
          "ex-data NAMES the offending key")
      (is (= :pass-the-profile-to-project-egress (:recovery d))
          "the profile case carries its own recovery disposition")
      (is (= 're-frame.elision/elide-wire-value (:where d))
          ":where names the door the caller actually called")
      (is (contains? (set (:accepted d)) :rf.size/include-sensitive?)
          "ex-data enumerates the ACCEPTED set so the caller needs no docs")
      (is (not (contains? (set (:accepted d)) :rf.egress/profile))
          "and the accepted set does not include the key just rejected"))))

(deftest walker-rejects-the-unqualified-inclusion-axes
  (testing "rf2-kuky.6 — the unqualified `include-sensitive?` spelling is the
            OTHER half of the same defect. `spec/Conventions.md` taught it on
            an `elide-wire-value` call, where it was harmless only because
            `false` happened to be the default. Passing `true` would have
            read as an opt-in that never happened."
    (doseq [k [:include-sensitive? :include-large? :include-digests?]]
      (let [d (bad-opts-ex-data #(rf.elision/elide-wire-value {:a 1} {k true}))]
        (is (some? d) (str k " throws"))
        (is (= [k] (:unknown-keys d)) (str k " is named"))
        (is (= :use-a-recognised-egress-opts-key (:recovery d))
            "a plain misspelling gets the generic recovery")))))

(deftest walker-rejects-an-arbitrary-unknown-key
  (testing "rf2-kuky.6 — the guard is a CLOSED-SET test, not a denylist of
            the two spellings that bit us."
    (let [d (bad-opts-ex-data
              #(rf.elision/elide-wire-value {:a 1} {:frame :app/main
                                                    :totally-made-up true}))]
      (is (some? d))
      (is (= [:totally-made-up] (:unknown-keys d)))))
  (testing "several unknown keys are ALL named, sorted, not just the first"
    (let [d (bad-opts-ex-data
              #(rf.elision/elide-wire-value {:a 1} {:aaa 1 :zzz 2}))]
      (is (= [:aaa :zzz] (:unknown-keys d))))))

(deftest walker-accepts-every-member-of-its-own-set
  (testing "rf2-kuky.6 — the CONTROL. A guard that rejected everything would
            pass every test above, so exercise the accepted set itself: each
            key, alone, must get through the guard. `:frame` is deliberately
            an unresolvable id, so the walk takes its own fail-closed arm and
            returns the sentinel — a value, not a throw."
    (doseq [k rf.elision/walker-opt-keys]
      (is (nil? (bad-opts-ex-data
                  #(rf.elision/elide-wire-value {:a 1} {k nil})))
          (str k " is accepted by the walker guard"))))
  (testing "and the whole set at once"
    (is (nil? (bad-opts-ex-data
                #(rf.elision/elide-wire-value
                   {:a 1}
                   (zipmap rf.elision/walker-opt-keys (repeat nil))))))))

(deftest walker-nil-and-empty-opts-are-fine
  (testing "the 1-arity and an empty map are not 'unknown keys'"
    (is (nil? (bad-opts-ex-data #(rf.elision/elide-wire-value {:a 1}))))
    (is (nil? (bad-opts-ex-data #(rf.elision/elide-wire-value {:a 1} nil))))
    (is (nil? (bad-opts-ex-data #(rf.elision/elide-wire-value {:a 1} {}))))))

(deftest unknown-key-cannot-widen-egress
  (testing "rf2-kuky.6 — the SAFETY argument, pinned rather than asserted in
            prose. With no live frame and no sensitive opt-out the walker
            fails closed to the `:rf/redacted` sentinel. Adding an unknown
            key to that call cannot turn the sentinel back into the value:
            before the change the key was ignored (same sentinel), after it
            the call throws. There is no third outcome in which the raw value
            ships."
    (is (= :rf/redacted
           (rf.elision/elide-wire-value {:secret "s"} {:frame ::never-registered}))
        "baseline: unresolvable frame ⇒ whole-value redaction")
    (is (thrown? clojure.lang.ExceptionInfo
                 (rf.elision/elide-wire-value
                   {:secret "s"}
                   {:frame ::never-registered
                    :rf.egress/profile :rf.egress/local-raw}))
        "and a profile that LOOKS like a raw opt-in throws instead of lifting")))

;; ---------------------------------------------------------------------------
;; 2. `project-egress`'s closed opts — on EVERY record kind
;; ---------------------------------------------------------------------------

(def ^:private record-kind-samples
  "One sample input per dispatch arm of `project-record-by-kind`, INCLUDING
  the kindless value path. The guard must fire on all of them: a record whose
  slots are all event-shaped or summary-only never reaches the delegated
  walker, so a guard left to the walker downstream would miss it. That is
  why the check lives at the door."
  {:kindless           {:some "value"}
   :handled-event      {:kind  :rf.observe/handled-event
                        :frame :app/main
                        :event [:auth/login {:password "secret"}]}
   :error              {:kind  :rf.observe/error
                        :frame :app/main
                        :event [:auth/login {:password "secret"}]}
   :derived-tree       {:kind  :rf.observe/derived-tree
                        :frame :app/main
                        :tree  {:a 1}}})

(deftest project-egress-rejects-an-unknown-key-on-every-kind
  (testing "rf2-kuky.6 — one guard at the door means every `:rf.observe/*`
            kind and the kindless value path answer identically."
    (doseq [[label record] record-kind-samples]
      (let [d (bad-opts-ex-data
                #(rf.projection/project-egress
                   record
                   {:rf.egress/profile :rf.egress/off-box-tool
                    :include-sensitive? true}))]
        (is (some? d) (str label " throws on an unqualified inclusion axis"))
        (is (= [:include-sensitive?] (:unknown-keys d))
            (str label " names the offending key"))
        (is (= 'rf/project-egress (:where d))
            (str label " attributes the throw to project-egress"))))))

(deftest project-egress-accepts-a-profile-the-walker-refuses
  (testing "rf2-kuky.6 — the two sets differ by exactly the key this layer
            OWNS. The same map that throws at the walker is valid here; that
            asymmetry is the whole point of the split."
    (is (nil? (bad-opts-ex-data
                #(rf.projection/project-egress
                   {:a 1}
                   {:frame :app/main
                    :rf.egress/profile :rf.egress/off-box-tool}))))))

(deftest walker-and-projection-sets-agree
  (testing "rf2-kuky.6 — `project-egress`'s set is DERIVED from the walker's,
            so the two cannot drift into disagreeing about the vocabulary
            they SHARE. It adds exactly two things and nothing else: the one
            key this layer owns (`:rf.egress/profile`) and, since rf2-bv1p
            retired the standalone `projected-record` door, the three
            epoch-only axes that door used to own."
    (is (= (into (conj rf.elision/walker-opt-keys :rf.egress/profile)
                 rf.projection/epoch-only-opt-keys)
           rf.projection/project-egress-opt-keys)
        "the derivation is exact — a fourth addition would fail here rather
         than widen the door quietly")
    (is (= #{:include-fx-args? :include-runtime-db? :include-event-args?}
           rf.projection/epoch-only-opt-keys)
        "and the three are named, so this test moves when rf2-kuky.93
         renames them rather than passing whatever it finds")
    (is (not (contains? rf.elision/walker-opt-keys :rf.egress/profile))
        "the walker's own set excludes the profile")
    (is (empty? (filter rf.elision/walker-opt-keys
                        rf.projection/epoch-only-opt-keys))
        "and it excludes all three epoch-only axes — they are STRIPPED at
         the door and must never reach the walker's closed map")))

(deftest all-six-profiles-still-resolve
  (testing "rf2-kuky.6 — the closed opts map must not have narrowed the
            profile enum. Every ruled profile still passes the door."
    (doseq [p rf.projection/profiles]
      (is (nil? (bad-opts-ex-data
                  #(rf.projection/project-egress {:a 1} {:rf.egress/profile p})))
          (str p " still resolves"))))
  (testing "while an unknown profile is still the OTHER error — the two
            closed-vocabulary guards are distinct and neither swallows the
            other."
    (is (= :rf.error/unknown-egress-profile
           (:rf.error/id
             (try (rf.projection/project-egress {:a 1} {:rf.egress/profile :nope})
                  nil
                  (catch clojure.lang.ExceptionInfo e (ex-data e))))))))
