(ns re-frame.egress-closed-opts-test
  "ONE policy vocabulary, and it is CLOSED at every egress door.

  ## What this pins

  On a privacy surface a RECOGNISED-looking policy key must never vanish
  without a signal. There are two ways it could:

    1. `re-frame.elision/elide-wire-value` does not read `:rf.egress/profile` —
       profiles are resolved a layer up, in `re-frame.projection`. A profile
       passed to the walker would be silently dropped: the call READS as
       though it named the off-box boundary while the walk runs under the
       default policy.
    2. The shared inclusion axes are spelled `:rf.egress/include-sensitive?`
       and so on — the spelling the walker, `project-egress`,
       `:rf/project-egress-opts` and Conventions §`:rf.egress/*` all use. A
       door that read only the unqualified spelling, or only the qualified
       one, would silently drop the other.

  Either would read exactly like a policy that had been applied. So the opts
  maps are CLOSED and an unrecognised key is a loud `:rf.error/bad-egress-opts`
  naming it.

  ## Why closing cannot widen egress

  An open map ignores an unknown key — it is neither read nor rejected. So
  closing the map cannot let out a value that would otherwise be redacted;
  the only different outcome is a throw where an open map would silently
  no-op. That is why a closed map is fail-CLOSED on a privacy surface.

  The two doors' key sets differ by exactly the keys `project-egress` OWNS:
  it adds `:rf.egress/profile` and the three epoch-only axes to the walker's
  set. The sets are DERIVED from one another in source rather than
  re-spelled, so `walker-and-projection-sets-agree` below pins the
  derivation and not a transcription."
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
  (testing "`:rf.egress/profile` is NOT a walker opt. It names a
            BOUNDARY and is resolved by `project-egress`, which passes the
            resolved `:rf.egress/*` opt-set down. Passing one to the walker
            throws, and the message says where the profile belongs."
    (let [d (bad-opts-ex-data
              #(rf.elision/elide-wire-value
                 {:a 1}
                 {:frame :app/main
                  :rf.egress/profile :rf.egress/off-box-tool}))]
      (is (= [:rf.egress/profile] (:unknown-keys d))
          "a profile handed to the walker throws :rf.error/bad-egress-opts, and
           the ex-data NAMES the offending key")
      (is (= :pass-the-profile-to-project-egress (:recovery d))
          "the profile case carries its own recovery disposition")
      (is (= 're-frame.elision/elide-wire-value (:where d))
          ":where names the door the caller actually called")
      (is (contains? (set (:accepted d)) :rf.egress/include-sensitive?)
          "ex-data enumerates the ACCEPTED set so the caller needs no docs")
      (is (not (contains? (set (:accepted d)) :rf.egress/profile))
          "and the accepted set does not include the key just rejected"))
    (is (nil? (bad-opts-ex-data
                #(rf.projection/project-egress
                   {:a 1}
                   {:frame :app/main
                    :rf.egress/profile :rf.egress/off-box-tool})))
        "the SAME map is valid at project-egress — the two sets differ by
         exactly the key that layer OWNS, and that asymmetry is the split")))

(deftest walker-rejects-the-unqualified-inclusion-axes
  (testing "the unqualified `include-sensitive?` spelling is the OTHER half.
            On an `elide-wire-value` call it would be harmless only while it
            said `false`, the default; passing `true` would read as an
            opt-in that never happened."
    (doseq [k [:include-sensitive? :include-large? :include-digests?]]
      (let [d (bad-opts-ex-data #(rf.elision/elide-wire-value {:a 1} {k true}))]
        (is (= [k] (:unknown-keys d)) (str k " throws and is named"))
        (is (= :use-a-recognised-egress-opts-key (:recovery d))
            "a plain misspelling gets the generic recovery")))))

(deftest the-retired-rf-size-opts-spellings-are-refused
  (testing "the opts vocabulary is `:rf.egress/*`, so the whole closed map
            is describable by ONE namespace. The retired `:rf.size/*`
            spellings are the OLD names of LIVE axes, which is what makes
            them worth pinning: a caller using one must throw rather than
            quietly lose the opt-in it believes it passed. `:rf.size/*`
            reserves the wire MARKER (`:rf.size/large-elided`) and nothing
            else."
    (doseq [k [:rf.size/include-sensitive? :rf.size/include-large?
               :rf.size/include-digests?   :rf.size/threshold-bytes]]
      (let [d (bad-opts-ex-data #(rf.elision/elide-wire-value {:a 1} {k true}))]
        (is (= [k] (:unknown-keys d))
            (str "the retired " k " is refused by the walker and NAMED in the ex-data")))
      (let [d (bad-opts-ex-data
                #(rf.projection/project-egress
                   {:a 1}
                   {:rf.egress/profile :rf.egress/off-box-tool k true}))]
        (is (= [k] (:unknown-keys d))
            (str "the retired " k " is refused and named at project-egress too")))))
  (testing "the three epoch-only axes are `:rf.egress/*` too, so their
            bare spellings are refused alongside the `:rf.size/*` ones."
    (doseq [k [:include-fx-args? :include-runtime-db? :include-event-args?]]
      (let [d (bad-opts-ex-data
                #(rf.projection/project-egress
                   {:a 1}
                   {:rf.egress/profile :rf.egress/off-box-tool k true}))]
        (is (= [k] (:unknown-keys d)) (str "the retired bare " k " is refused and named")))))
  (testing "CONTROL — every LIVE spelling is accepted at the door it belongs
            to. Without this, a guard that had simply narrowed to nothing
            would satisfy every assertion above."
    (doseq [k [:rf.egress/include-sensitive? :rf.egress/include-large?
               :rf.egress/include-digests?   :rf.egress/threshold-bytes]]
      (is (nil? (bad-opts-ex-data
                  #(rf.elision/elide-wire-value {:a 1} {k nil})))
          (str k " is live walker vocabulary")))
    (doseq [k [:rf.egress/include-fx-args? :rf.egress/include-runtime-db?
               :rf.egress/include-event-args?]]
      (is (nil? (bad-opts-ex-data
                  #(rf.projection/project-egress
                     {:a 1}
                     {:rf.egress/profile :rf.egress/off-box-tool k true})))
          (str k " is live project-egress vocabulary"))))
  (testing "and the MARKER stays `:rf.size/*`. It shares no key with the
            opts map, so a prefix-level substitution over `:rf.size/` would
            destroy the one `:rf.size/*` member that has to survive."
    (is (rf.elision/marker? {:rf.size/large-elided {:path [] :bytes 1}})
        ":rf.size/large-elided is the wire marker")))

(deftest as-of-epoch-is-retired
  (testing "`:as-of-epoch` is not egress vocabulary. It would produce a
            four-element marker handle,
            `[:rf.elision/at <path> :as-of-epoch <id>]`, which Spec-Schemas
            `:rf/elision-marker` rejects (the handle is a two-tuple), and
            there are no epoch-resolvable handles. Absent from the CLOSED
            maps, a caller passing it gets the closed-map refusal naming it,
            at BOTH doors — never a silently schema-invalid marker."
    (let [d (bad-opts-ex-data
              #(rf.elision/elide-wire-value {:a 1} {:as-of-epoch 7}))]
      (is (= [:as-of-epoch] (:unknown-keys d)) "the walker refuses :as-of-epoch and names it")
      (is (= 're-frame.elision/elide-wire-value (:where d)))
      (is (= :use-a-recognised-egress-opts-key (:recovery d)))
      (is (not (contains? (set (:accepted d)) :as-of-epoch))))
    (let [d (bad-opts-ex-data
              #(rf.projection/project-egress
                 {:a 1}
                 {:rf.egress/profile :rf.egress/off-box-tool :as-of-epoch 7}))]
      (is (= [:as-of-epoch] (:unknown-keys d)) "project-egress refuses :as-of-epoch and names it")
      (is (= 'rf/project-egress (:where d)))
      (is (= :use-a-recognised-egress-opts-key (:recovery d)))
      (is (not (contains? (set (:accepted d)) :as-of-epoch)))))
  (testing "CONTROL — the same calls without the retired key pass the guard,
            so the refusal above is about `:as-of-epoch` and nothing else."
    (is (nil? (bad-opts-ex-data
                #(rf.elision/elide-wire-value {:a 1} {:frame :app/main}))))
    (is (nil? (bad-opts-ex-data
                #(rf.projection/project-egress
                   {:a 1}
                   {:rf.egress/profile :rf.egress/off-box-tool}))))))

(deftest walker-rejects-an-arbitrary-unknown-key
  (testing "the guard is a CLOSED-SET test, not a denylist of two known-bad
            spellings."
    (let [d (bad-opts-ex-data
              #(rf.elision/elide-wire-value {:a 1} {:frame :app/main
                                                    :totally-made-up true}))]
      (is (= [:totally-made-up] (:unknown-keys d)))))
  (testing "several unknown keys are ALL named, sorted, not just the first"
    (let [d (bad-opts-ex-data
              #(rf.elision/elide-wire-value {:a 1} {:aaa 1 :zzz 2}))]
      (is (= [:aaa :zzz] (:unknown-keys d))))))

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
  (testing "one guard at the door means every `:rf.observe/*`
            kind and the kindless value path answer identically."
    (doseq [[label record] record-kind-samples]
      (let [d (bad-opts-ex-data
                #(rf.projection/project-egress
                   record
                   {:rf.egress/profile :rf.egress/off-box-tool
                    :include-sensitive? true}))]
        (is (= [:include-sensitive?] (:unknown-keys d))
            (str label " throws on an unqualified inclusion axis, naming it"))
        (is (= 'rf/project-egress (:where d))
            (str label " attributes the throw to project-egress"))))))

(deftest walker-and-projection-sets-agree
  (testing "`project-egress`'s set is DERIVED from the walker's,
            so the two cannot drift into disagreeing about the vocabulary
            they SHARE. It adds exactly two things and nothing else: the one
            key this layer owns (`:rf.egress/profile`) and the three
            epoch-only axes (there is no standalone `projected-record` door)."
    (is (= (into (conj rf.elision/walker-opt-keys :rf.egress/profile)
                 rf.projection/epoch-only-opt-keys)
           rf.projection/project-egress-opt-keys)
        "the derivation is exact — a fourth addition would fail here rather
         than widen the door quietly")
    (is (= #{:rf.egress/include-fx-args? :rf.egress/include-runtime-db?
             :rf.egress/include-event-args?}
           rf.projection/epoch-only-opt-keys)
        "and the three are named, so a rename has to move this test on
         purpose rather than find it already passing")
    (is (not (contains? rf.elision/walker-opt-keys :rf.egress/profile))
        "the walker's own set excludes the profile")
    (is (empty? (filter rf.elision/walker-opt-keys
                        rf.projection/epoch-only-opt-keys))
        "and it excludes all three epoch-only axes — they are STRIPPED at
         the door and must never reach the walker's closed map")))
