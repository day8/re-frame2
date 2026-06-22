(ns re-frame.image-cljs-test
  "EP-0023 §Image / §Namespace-Selected Images / §Image Fragments — the
  foundation slice (rf2-32siq3.3): `rf/image` constructor, the normalized image
  value, the `:include-ns` glob grammar, inline `:registrations`, and the PURE
  `select-descriptors` selector against SYNTHETIC descriptor collections.

  Pins the bead's enumerated coverage:

    * single `*` (exactly one segment);
    * `**` (zero AND multiple segments);
    * case-sensitivity (whole-namespace, case-sensitive matching);
    * multi-segment literal paths;
    * exact (no-wildcard) inclusion vs prefix (`*`) vs recursive (`**`);
    * inline `:registrations` lowering + unconditional selection;
    * selection BY `:rf.provenance/ns`, NOT by the registration-id namespace;
    * zero-match `:include-ns` patterns fail loud;
    * each selected descriptor included at most once.

  Dual-runtime: the ns ends in `-cljs-test` so it rides the always-on
  `:node-test` gate (`npm run test:cljs`); cognitect-test-runner also discovers
  the `.cljc` on the JVM (`clojure -M:test`). Pure data — no adapter/runtime
  state, so no reset-runtime fixture."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.image :as image]))

;; ============================================================================
;; ns-matches? — the glob grammar primitive
;; ============================================================================

(deftest exact-inclusion-no-wildcard
  (testing "a pattern with no wildcard matches the whole namespace exactly"
    (is (true?  (image/ns-matches? "docs.quickstart.counter.v2"
                                   "docs.quickstart.counter.v2")))
    (is (false? (image/ns-matches? "docs.quickstart.counter.v2"
                                   "docs.quickstart.counter.v3"))))
  (testing "exact pattern does NOT match a longer or shorter namespace"
    (is (false? (image/ns-matches? "docs.quickstart.counter"
                                   "docs.quickstart.counter.v2")))
    (is (false? (image/ns-matches? "docs.quickstart.counter.v2"
                                   "docs.quickstart.counter")))))

(deftest single-star-matches-exactly-one-segment
  (testing "`*` matches exactly one dot-free segment"
    (is (true?  (image/ns-matches? "docs.*.counter" "docs.foo.counter")))
    (is (true?  (image/ns-matches? "docs.*.counter" "docs.bar.counter"))))
  (testing "`*` matches ONE segment, never zero"
    (is (false? (image/ns-matches? "docs.*.counter" "docs.counter"))))
  (testing "`*` matches ONE segment, never two"
    (is (false? (image/ns-matches? "docs.*.counter" "docs.a.b.counter"))))
  (testing "trailing `*` — exactly one more segment (EP prefix example)"
    ;; "docs.shared.widgets.*" matches docs.shared.widgets.button but NOT
    ;; docs.shared.widgets and NOT docs.shared.widgets.forms.input.
    (is (true?  (image/ns-matches? "docs.shared.widgets.*"
                                   "docs.shared.widgets.button")))
    (is (false? (image/ns-matches? "docs.shared.widgets.*"
                                   "docs.shared.widgets")))
    (is (false? (image/ns-matches? "docs.shared.widgets.*"
                                   "docs.shared.widgets.forms.input"))))
  (testing "multiple `*` segments each consume exactly one"
    (is (true?  (image/ns-matches? "docs.*.counter.*"
                                   "docs.x.counter.v2")))
    (is (false? (image/ns-matches? "docs.*.counter.*"
                                   "docs.x.counter")))
    (is (false? (image/ns-matches? "docs.*.counter.*"
                                   "docs.x.y.counter.v2")))))

(deftest double-star-matches-zero-or-more-segments
  (testing "`**` matches ZERO segments (the EP `docs.shared.**` ⊇ docs.shared)"
    (is (true? (image/ns-matches? "docs.shared.**" "docs.shared"))))
  (testing "`**` matches ONE segment"
    (is (true? (image/ns-matches? "docs.shared.**" "docs.shared.widgets"))))
  (testing "`**` matches MULTIPLE segments"
    (is (true? (image/ns-matches? "docs.shared.**"
                                  "docs.shared.widgets.forms.input"))))
  (testing "`**` is anchored — the prefix must still match"
    (is (false? (image/ns-matches? "docs.shared.**" "docs.other")))
    (is (false? (image/ns-matches? "docs.shared.**" "docs")))
    (is (false? (image/ns-matches? "docs.shared.**" "other.shared.x"))))
  (testing "a bare `**` matches everything (zero or more from the root)"
    (is (true? (image/ns-matches? "**" "anything")))
    (is (true? (image/ns-matches? "**" "deeply.nested.namespace")))
    ;; bare ** absorbing zero segments matches the empty string too
    (is (true? (image/ns-matches? "**" ""))))
  (testing "`**` in the middle bridges any number of segments"
    (is (true?  (image/ns-matches? "shop.**.http" "shop.cart.http")))
    (is (true?  (image/ns-matches? "shop.**.http" "shop.a.b.c.http")))
    ;; `**` zero-segment in the middle: shop.**.http ⊇ shop.http
    (is (true?  (image/ns-matches? "shop.**.http" "shop.http")))
    (is (false? (image/ns-matches? "shop.**.http" "shop.cart.https")))))

(deftest consecutive-double-stars-collapse-and-bound-backtracking
  ;; M3 guard (EP-0023 code-quality): consecutive `**` runs collapse to one
  ;; `**` before matching, so a pathological pattern can never trigger the
  ;; matcher's exponential-backtracking worst case. The collapse is semantics-
  ;; preserving: a run of `**`s accepts exactly the segment sets a single `**`
  ;; does.
  (testing "a run of `**` is semantically equivalent to a single `**`"
    (is (= (image/ns-matches? "**.**" "a.b.c")     (image/ns-matches? "**" "a.b.c")))
    (is (= (image/ns-matches? "**.**.**" "a.b.c")   (image/ns-matches? "**" "a.b.c")))
    (is (true?  (image/ns-matches? "**.**.**" "anything.at.all")))
    (is (true?  (image/ns-matches? "**.**" "")))
    (is (true?  (image/ns-matches? "shop.**.**.http" "shop.a.b.c.http")))
    (is (true?  (image/ns-matches? "shop.**.**.http" "shop.http")))
    (is (false? (image/ns-matches? "shop.**.**.http" "shop.a.b.https"))))
  (testing "a pathological all-`**` pattern returns promptly on a NON-match
            (no exponential blow-up) and preserves the right answer"
    ;; Without the collapse guard, the trailing literal forces the matcher to
    ;; fork 2^k ways across the k adjacent `**`s before reporting the non-match.
    (is (false? (image/ns-matches? "**.**.**.**.**.**.**.**.x"
                                   "a.b.c.d.e.f.g.h.i.j.k.l.m.n")))
    (is (true?  (image/ns-matches? "**.**.**.**.**.**.**.**.x"
                                   "a.b.c.d.e.f.g.h.i.j.k.l.m.x")))))

(deftest matching-is-case-sensitive
  (testing "whole-namespace match is case-sensitive (literal segments)"
    (is (false? (image/ns-matches? "Docs.counter" "docs.counter")))
    (is (false? (image/ns-matches? "docs.Counter" "docs.counter")))
    (is (true?  (image/ns-matches? "docs.counter" "docs.counter"))))
  (testing "case-sensitivity holds under wildcards too"
    (is (false? (image/ns-matches? "Docs.*" "docs.counter")))
    (is (false? (image/ns-matches? "Docs.**" "docs.counter.v2")))
    (is (true?  (image/ns-matches? "docs.**" "docs.Counter.V2")))))

(deftest multi-segment-literal-paths
  (testing "deep literal paths match whole-namespace only"
    (is (true?  (image/ns-matches? "a.b.c.d.e" "a.b.c.d.e")))
    (is (false? (image/ns-matches? "a.b.c.d.e" "a.b.c.d")))
    (is (false? (image/ns-matches? "a.b.c.d.e" "a.b.c.d.e.f"))))
  (testing "single-segment namespace"
    (is (true?  (image/ns-matches? "core" "core")))
    (is (false? (image/ns-matches? "core" "core.sub")))
    (is (true?  (image/ns-matches? "*" "core")))
    (is (false? (image/ns-matches? "*" "core.sub")))))

(deftest intra-segment-glob-matches-within-one-segment
  (testing "a trailing-suffix intra-segment `*` matches within the last segment"
    (is (true?  (image/ns-matches? "*-cljs-test" "mount-cljs-test")))
    (is (true?  (image/ns-matches? "*-cljs-test" "core-cljs-test")))
    ;; `*` is ZERO-OR-MORE chars, so the bare suffix matches too
    (is (true?  (image/ns-matches? "*-cljs-test" "-cljs-test")))
    (is (false? (image/ns-matches? "*-cljs-test" "mount")))
    (is (false? (image/ns-matches? "*-cljs-test" "mount-cljs-tests"))))
  (testing "prefix and middle intra-segment `*`"
    (is (true?  (image/ns-matches? "foo*" "foobar")))
    (is (true?  (image/ns-matches? "foo*" "foo")))      ; zero-or-more
    (is (false? (image/ns-matches? "foo*" "xfoo")))
    (is (true?  (image/ns-matches? "a*b" "aXXXb")))
    (is (true?  (image/ns-matches? "a*b" "ab")))
    (is (false? (image/ns-matches? "a*b" "aXXX"))))
  (testing "the intra-segment `*` NEVER crosses a `.` boundary (that is `**`)"
    ;; A trailing intra-glob segment matches ONE segment only — it does not
    ;; absorb a deeper segment.
    (is (false? (image/ns-matches? "docs.*-test" "docs.foo.bar-test")))
    (is (true?  (image/ns-matches? "docs.*-test" "docs.foo-test"))))
  (testing "intra-segment glob combines with `**` to drop test namespaces at
            any depth (the EP-0023 §Xray Beside The Target exclusion case)"
    ;; `**` absorbs zero-or-more segments, then the trailing `*-cljs-test`
    ;; intra-glob matches the leaf — so depth-1 AND deep test nss both match,
    ;; while production nss (no `-cljs-test` suffix) do not.
    (is (true?  (image/ns-matches? "day8.re-frame2-xray.**.*-cljs-test"
                                   "day8.re-frame2-xray.mount-cljs-test")))
    (is (true?  (image/ns-matches? "day8.re-frame2-xray.**.*-cljs-test"
                                   "day8.re-frame2-xray.panels.app-db-diff-cljs-test")))
    (is (false? (image/ns-matches? "day8.re-frame2-xray.**.*-cljs-test"
                                   "day8.re-frame2-xray.mount")))
    (is (false? (image/ns-matches? "day8.re-frame2-xray.**.*-cljs-test"
                                   "day8.re-frame2-xray.panels.app-db-diff"))))
  (testing "literal regex metacharacters in a segment are matched literally,
            not as regex ops (portable escaping across JVM/JS engines)"
    ;; A `+` in the namespace must be a literal `+`, never one-or-more.
    (is (true?  (image/ns-matches? "a+*" "a+xyz")))
    (is (false? (image/ns-matches? "a+*" "aaa")))
    ;; A literal `.` inside the PATTERN string is a segment separator, so a
    ;; pattern segment can never itself contain a `.`; but a `(` / `[` etc. must
    ;; stay literal within the segment.
    (is (true?  (image/ns-matches? "a(b)*" "a(b)c")))
    (is (false? (image/ns-matches? "a(b)*" "ab")))))

;; ============================================================================
;; image — the constructor + normalized value
;; ============================================================================

(deftest image-normalizes-the-spec
  (testing "id stamps :rf.image/id; include-ns becomes a vector; requires a set"
    (let [v (image/image {:id :docs.counter/v2
                          :include-ns ["docs.counter.v2"]
                          :rf.image/requires [:rf.capability/http]})]
      (is (= :docs.counter/v2 (:rf.image/id v)))
      (is (= ["docs.counter.v2"] (:rf.image/include-ns v)))
      (is (= [] (:rf.image/exclude-ns v)) "exclude-ns defaults to an empty vector")
      (is (= #{:rf.capability/http} (:rf.image/requires v)))
      (is (= [] (:rf.image/inline v)))))
  (testing ":exclude-ns becomes a vector of glob strings alongside :include-ns"
    (let [v (image/image {:id :tool/img
                          :include-ns ["day8.re-frame2-xray.**"]
                          :exclude-ns ["day8.re-frame2-xray.**-cljs-test"]})]
      (is (= ["day8.re-frame2-xray.**"] (:rf.image/include-ns v)))
      (is (= ["day8.re-frame2-xray.**-cljs-test"] (:rf.image/exclude-ns v)))))
  (testing "anonymous image (no :id) omits :rf.image/id — valid for local use"
    (let [v (image/image {:include-ns ["docs.counter.**"]})]
      (is (not (contains? v :rf.image/id)))
      (is (= ["docs.counter.**"] (:rf.image/include-ns v)))
      (is (= #{} (:rf.image/requires v)))))
  (testing "empty spec yields an empty-but-well-formed image value"
    (let [v (image/image {})]
      (is (= [] (:rf.image/include-ns v)))
      (is (= [] (:rf.image/inline v)))
      (is (= #{} (:rf.image/requires v)))))
  (testing "equal specs produce equal image values (inert data)"
    (is (= (image/image {:id :x :include-ns ["a.b.*"]})
           (image/image {:id :x :include-ns ["a.b.*"]})))))

(deftest image-rejects-malformed-specs
  (testing "non-map spec throws :rf.error/invalid-image"
    (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                          #"\[:rf\.error/invalid-image\]"
                          (image/image [:not :a :map]))))
  (testing "unknown top-level key throws"
    (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                          #"\[:rf\.error/invalid-image\]"
                          (image/image {:id :x :includeNS ["a"]}))))
  (testing "non-string :include-ns pattern throws"
    (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                          #"\[:rf\.error/invalid-image\]"
                          (image/image {:include-ns ['docs.counter]}))))
  (testing "non-string :exclude-ns pattern throws (same glob-string validation)"
    (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                          #"\[:rf\.error/invalid-image\]"
                          (image/image {:exclude-ns ['docs.counter]}))))
  (testing "the ex-data carries :rf.error/id for machine branching"
    (is (= :rf.error/invalid-image
           (try (image/image {:bogus 1})
                (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e
                  (:rf.error/id (ex-data e))))))))

;; ============================================================================
;; :replace / :replace-standard — declared-winner maps (EP-0023 §Image
;; Patching And Overrides). The constructor validates the STRUCTURAL shape of
;; each entry (the [kind id] key + the winner source coordinate, rf2-32siq3.20)
;; and carries the validated map through uninterpreted; the SEMANTIC winner
;; resolution against the actual selected collisions is the assembly slice's job.
;; ============================================================================

(deftest image-rejects-non-map-replace-keys
  (testing "a non-map :replace throws :rf.error/invalid-image"
    (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                          #"\[:rf\.error/invalid-image\]"
                          (image/image {:id :x :replace [:not :a :map]}))))
  (testing "a non-map :replace-standard throws :rf.error/invalid-image"
    (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                          #"\[:rf\.error/invalid-image\]"
                          (image/image {:id :x :replace-standard :nope}))))
  (testing "the bad-key/received surface slots ride the top-level ex-data"
    ;; thrown-ex-info MERGES :extra onto the top-level ex-data (not nested) —
    ;; read the surface slots at the top level.
    (let [data (try (image/image {:id :y :replace 42})
                    (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e
                      (ex-data e)))]
      (is (= :rf.error/invalid-image (:rf.error/id data)))
      (is (= :replace (:bad-key data)))
      (is (= 42 (:received data))))))

(deftest image-carries-replace-keys-through
  (testing "valid :replace / :replace-standard maps carry through to the value"
    (let [rep      {[:event :counter/inc] {:ns "docs.counter.v3"}}
          rep-std  {[:fx :http] {:standard true}}
          v        (image/image {:id :combo
                                 :replace          rep
                                 :replace-standard rep-std})]
      (is (= rep     (:replace v)))
      (is (= rep-std (:replace-standard v)))))
  (testing "an inline winner coordinate carries through"
    (let [rep {[:fx :checkout.http/post]
               {:image :checkout/story :inline [:reg-fx :checkout.http/post]}}
          v   (image/image {:id :checkout/story :replace rep})]
      (is (= rep (:replace v)))))
  (testing "an empty :replace map is still carried through (present-when-supplied)"
    ;; cond-> branches on (:replace spec) truthiness; an empty map is truthy.
    (let [v (image/image {:id :e :replace {}})]
      (is (contains? v :replace))
      (is (= {} (:replace v)))))
  (testing "absent :replace / :replace-standard keys are NOT stamped onto the value"
    (let [v (image/image {:id :bare :include-ns ["docs.counter.v2"]})]
      (is (not (contains? v :replace)))
      (is (not (contains? v :replace-standard))))))

;; rf2-32siq3.20 — replacement map ENTRY-shape validation at the image boundary.
;; Malformed keys / winner coordinates must fail loud at rf/image with an
;; actionable :rf.error/invalid-image, BEFORE assembly's collision checks
;; destructure the key (where a keyword key would surface as `nth not supported`).

(deftest replace-key-must-be-a-kind-id-pair
  (testing "a keyword :replace key (not a [kind id] vector) throws"
    (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                          #"\[:rf\.error/invalid-image\]"
                          (image/image {:id :x
                                        :replace {:counter/inc {:ns "a.b"}}}))))
  (testing "a wrong-arity key (1- or 3-element vector) throws"
    (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                          #"\[:rf\.error/invalid-image\]"
                          (image/image {:id :x
                                        :replace {[:event] {:ns "a.b"}}})))
    (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                          #"\[:rf\.error/invalid-image\]"
                          (image/image {:id :x
                                        :replace {[:event :counter/inc :extra]
                                                  {:ns "a.b"}}}))))
  (testing "the bad-key diagnostic rides the top-level ex-data"
    (let [data (try (image/image {:id :y :replace {:nope {:ns "a.b"}}})
                    (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e
                      (ex-data e)))]
      (is (= :rf.error/invalid-image (:rf.error/id data)))
      (is (= :replace (:replace-map data)))
      (is (= :nope (:bad-key data))))))

(deftest replace-key-kind-must-be-supported
  (testing "an unsupported registration kind in the key throws"
    (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                          #"\[:rf\.error/invalid-image\]"
                          (image/image {:id :x
                                        :replace {[:bogus-kind :counter/inc]
                                                  {:ns "a.b"}}}))))
  (testing "the unsupported-kind diagnostic carries the bad kind"
    (let [data (try (image/image {:id :y
                                  :replace-standard {[:nope :x] {:standard true}}})
                    (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e
                      (ex-data e)))]
      (is (= :rf.error/invalid-image (:rf.error/id data)))
      (is (= :replace-standard (:replace-map data)))
      (is (= :nope (:bad-kind data))))))

(deftest replace-winner-must-be-a-supported-coordinate
  (testing "a keyword winner (not a coordinate map) throws"
    (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                          #"\[:rf\.error/invalid-image\]"
                          (image/image {:id :x
                                        :replace {[:event :counter/inc]
                                                  :docs.counter.v3/winner}}))))
  (testing "a malformed registered coordinate (:ns not a string) throws"
    (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                          #"\[:rf\.error/invalid-image\]"
                          (image/image {:id :x
                                        :replace {[:event :counter/inc]
                                                  {:ns :not-a-string}}}))))
  (testing "a registered coordinate with extra keys throws"
    (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                          #"\[:rf\.error/invalid-image\]"
                          (image/image {:id :x
                                        :replace {[:event :counter/inc]
                                                  {:ns "a.b" :stray 1}}}))))
  (testing "a malformed inline coordinate (:inline not a 2-vector) throws"
    (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                          #"\[:rf\.error/invalid-image\]"
                          (image/image {:id :x
                                        :replace {[:fx :checkout.http/post]
                                                  {:image :s :inline [:reg-fx]}}}))))
  (testing "the bad-winner diagnostic rides the top-level ex-data"
    (let [data (try (image/image {:id :y
                                  :replace {[:event :counter/inc] 99}})
                    (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e
                      (ex-data e)))]
      (is (= :rf.error/invalid-image (:rf.error/id data)))
      (is (= 99 (:bad-winner data)))))
  (testing "all three valid coordinate shapes are accepted"
    (is (some? (image/image {:id :a :replace {[:event :e] {:ns "x.y"}}})))
    (is (some? (image/image {:id :b
                             :replace {[:fx :p]
                                       {:image :s :inline [:reg-fx :p]}}})))
    (is (some? (image/image {:id :c
                             :replace-standard {[:fx :rf.nav/push-url]
                                                {:standard true}}})))))

;; ============================================================================
;; inline :registrations — lowering to inline descriptors
;; ============================================================================

(deftest inline-registrations-lower-to-descriptors
  (let [body-inc (fn [_ _] {})
        body-val (fn [_ _] 0)
        v (image/image
            {:id :test/small
             :registrations
             {:reg-event [[:counter/inc {:doc "Increment."} body-inc]]
              :reg-sub   [[:counter/value {:doc "Value."} body-val]]}})
        inline (:rf.image/inline v)
        by-id  (into {} (map (juxt :id identity)) inline)]
    (testing "each inline entry lowers to a descriptor with kind + id"
      (is (= 2 (count inline)))
      (is (= :event (:kind (by-id :counter/inc))))
      (is (= :sub   (:kind (by-id :counter/value)))))
    (testing "the body is carried under :impl"
      (is (= body-inc (:impl (by-id :counter/inc))))
      (is (= body-val (:impl (by-id :counter/value)))))
    (testing "metadata is carried under :metadata"
      (is (= {:doc "Increment."} (:metadata (by-id :counter/inc)))))
    (testing "inline source coordinate names the image + the inline slot"
      (is (= :test/small (:rf.provenance/image (by-id :counter/inc))))
      (is (= [:reg-event :counter/inc] (:rf.provenance/inline (by-id :counter/inc)))))
    (testing "inline descriptors carry NO :rf.provenance/ns (not glob-selectable)"
      (is (not (contains? (by-id :counter/inc) :rf.provenance/ns))))))

;; EP-0026 §Inline Registration Grammar — the EP-0023 metadata-only [id metadata]
;; tuple is RETIRED (fsd822). A 2-tuple's second slot is the handler BODY, not
;; metadata; for the four supported inline kinds the body is a FUNCTION, so a map
;; in the body slot is unambiguously the retired metadata-only form and fails
;; loud. To attach metadata, use the 3-tuple [id metadata body].
(deftest inline-metadata-only-entry-rejected
  (testing "a [id metadata-map] 2-tuple (the retired metadata-only form) throws
            :rf.error/invalid-image"
    (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                          #"\[:rf\.error/invalid-image\]"
                          (image/image {:id :m
                                        :registrations {:reg-fx [[:my/fx {:doc "meta only"}]]}}))))
  (testing "the diagnostic names the image, section, and offending metadata-only entry"
    (let [entry [:my/fx {:doc "meta only"}]
          data  (try (image/image {:id :m :registrations {:reg-fx [entry]}})
                     (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e
                       (ex-data e)))]
      (is (= :rf.error/invalid-image (:rf.error/id data)))
      (is (= :m (:image data)))
      (is (= :reg-fx (:section data)))
      (is (= entry (:entry data))))))

(deftest inline-rejects-unknown-section-and-malformed-entry
  (testing "unknown inline section key throws :rf.error/invalid-image"
    (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                          #"\[:rf\.error/invalid-image\]"
                          (image/image {:registrations {:reg-bogus [[:x {}]]}}))))
  (testing "a non-tuple inline entry throws"
    (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                          #"\[:rf\.error/invalid-image\]"
                          (image/image {:registrations {:reg-event [:not-a-tuple]}})))))

;; EP-0026 §Inline Registration Grammar (fsd822) — inline tuple ARITY is exact:
;; [id body] (metadata defaults to {}) and [id metadata body] (explicit
;; metadata). EVERY inline registration carries a body. [id], [], non-vectors,
;; and 4+-tuples fail loud at rf/image rather than being coerced. The EP-0023
;; metadata-only [id metadata] form is retired (see
;; inline-metadata-only-entry-rejected).

(deftest inline-accepts-exactly-two-and-three-tuples
  (testing "a 3-tuple [id metadata body] is accepted (explicit metadata)"
    (let [body (fn [_ _] {})
          v    (image/image {:id :i
                             :registrations {:reg-event [[:counter/inc {:doc "x"} body]]}})
          d    (first (:rf.image/inline v))]
      (is (= :counter/inc (:id d)))
      (is (= body (:impl d)))
      (is (= {:doc "x"} (:metadata d)))))
  (testing "a 2-tuple [id body] is accepted (metadata defaults to {} / omitted)"
    (let [body (fn [_] nil)
          v    (image/image {:id :i :registrations {:reg-fx [[:my/fx body]]}})
          d    (first (:rf.image/inline v))]
      (is (= :my/fx (:id d)))
      (is (= body (:impl d)))
      (is (not (contains? d :metadata))
          "an omitted metadata map is not stamped onto the descriptor"))))

(deftest inline-rejects-too-short-tuple
  (testing "a 1-tuple [id] (no metadata slot) throws :rf.error/invalid-image"
    (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                          #"\[:rf\.error/invalid-image\]"
                          (image/image {:id :i :registrations {:reg-event [[:counter/inc]]}}))))
  (testing "an empty tuple [] throws"
    (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                          #"\[:rf\.error/invalid-image\]"
                          (image/image {:id :i :registrations {:reg-event [[]]}}))))
  (testing "the diagnostic names the image, the section, and the offending entry"
    (let [data (try (image/image {:id :my/image
                                  :registrations {:reg-sub [[:counter/value]]}})
                    (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e
                      (ex-data e)))]
      (is (= :rf.error/invalid-image (:rf.error/id data)))
      (is (= :my/image (:image data)))
      (is (= :reg-sub (:section data)))
      (is (= [:counter/value] (:entry data))))))

(deftest inline-rejects-too-long-tuple
  (testing "a 4-tuple [id metadata body extra] throws (extra slot not silently dropped)"
    (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                          #"\[:rf\.error/invalid-image\]"
                          (image/image
                            {:id :i
                             :registrations {:reg-event [[:counter/inc {} (fn [_ _] {}) :extra]]}}))))
  (testing "the offending 4-tuple is named in the diagnostic"
    (let [entry [:counter/inc {} (fn [_ _] {}) :extra]
          data  (try (image/image {:id :i :registrations {:reg-event [entry]}})
                     (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e
                       (ex-data e)))]
      (is (= :rf.error/invalid-image (:rf.error/id data)))
      (is (= entry (:entry data))))))

(deftest anonymous-image-inline-omits-image-coordinate
  (testing "anonymous image inline descriptors omit :rf.provenance/image"
    (let [v (image/image {:registrations {:reg-event [[:x {} (fn [_ _] {})]]}})
          d (first (:rf.image/inline v))]
      (is (not (contains? d :rf.provenance/image)))
      ;; the inline slot coordinate is still present (id is nameable locally)
      (is (= [:reg-event :x] (:rf.provenance/inline d))))))

;; ============================================================================
;; select-descriptors — the PURE selector against synthetic descriptors
;; ============================================================================

(defn- desc
  "A synthetic registered descriptor — the source store's output shape this
  slice consumes. The ONLY load-bearing field is :rf.provenance/ns (the
  source-code namespace). :kind/:id/:impl are carried through untouched."
  [provenance-ns kind id]
  {:rf.provenance/ns provenance-ns
   :kind             kind
   :id               id
   :impl             (fn [_ _] ::stub)})

(def ^:private synthetic-store
  "A synthetic descriptor collection spanning several provenance namespaces,
  deliberately REUSING the registration-id namespace `:counter/inc` across
  DIFFERENT source namespaces — so a test can prove selection is by
  :rf.provenance/ns, not by the id's namespace."
  [(desc "docs.quickstart.counter.v2"  :event :counter/inc)
   (desc "docs.quickstart.counter.v2"  :sub   :counter/value)
   (desc "docs.quickstart.counter.v3"  :event :counter/inc) ; same id, diff ns
   (desc "docs.shared.widgets"         :view  :widgets/button)
   (desc "docs.shared.widgets.button"  :view  :button/root)
   (desc "shop.cart"                   :event :cart/add)
   (desc "shop.auth"                   :event :auth/set-user)])

(deftest select-by-provenance-ns-not-id-ns
  (testing "exact :include-ns selects only the matching provenance namespace"
    (let [img (image/image {:id :i :include-ns ["docs.quickstart.counter.v2"]})
          sel (image/select-descriptors img synthetic-store)]
      (is (= 2 (count sel)))
      ;; BOTH selected descriptors came from v2 — the v3 :counter/inc (same id
      ;; namespace!) is NOT selected. This is the headline EP rule.
      (is (every? #(= "docs.quickstart.counter.v2" (:rf.provenance/ns %)) sel))
      (is (= #{:counter/inc :counter/value} (set (map :id sel))))))
  (testing "the v3 :counter/inc is reachable only via its own provenance ns"
    (let [img (image/image {:id :i :include-ns ["docs.quickstart.counter.v3"]})
          sel (image/select-descriptors img synthetic-store)]
      (is (= 1 (count sel)))
      (is (= "docs.quickstart.counter.v3" (:rf.provenance/ns (first sel))))
      (is (= :counter/inc (:id (first sel)))))))

(deftest select-glob-prefix-and-recursive
  (testing "prefix `*` selects exactly-one-deeper provenance namespaces"
    (let [img (image/image {:id :i :include-ns ["docs.shared.widgets.*"]})
          sel (image/select-descriptors img synthetic-store)]
      ;; matches docs.shared.widgets.button but NOT docs.shared.widgets
      (is (= ["docs.shared.widgets.button"] (map :rf.provenance/ns sel)))))
  (testing "recursive `**` selects the base ns AND any deeper ns"
    (let [img (image/image {:id :i :include-ns ["docs.shared.**"]})
          sel (image/select-descriptors img synthetic-store)]
      (is (= #{"docs.shared.widgets" "docs.shared.widgets.button"}
             (set (map :rf.provenance/ns sel))))))
  (testing "a `*.*` mid-glob selects across sibling counter versions"
    (let [img (image/image {:id :i :include-ns ["docs.*.counter.*"]})
          sel (image/select-descriptors img synthetic-store)]
      (is (= #{"docs.quickstart.counter.v2" "docs.quickstart.counter.v3"}
             (set (map :rf.provenance/ns sel)))))))

(deftest select-multiple-patterns-deduped-and-ordered
  (testing "a descriptor matched by two patterns is included at most once"
    (let [img (image/image {:id :i
                            :include-ns ["docs.shared.**"
                                         "docs.shared.widgets.button"]})
          sel (image/select-descriptors img synthetic-store)]
      ;; docs.shared.widgets.button matches BOTH patterns — but appears once.
      (is (= 2 (count sel)))
      (is (= #{"docs.shared.widgets" "docs.shared.widgets.button"}
             (set (map :rf.provenance/ns sel))))))
  (testing "selection preserves input order of the descriptor collection"
    (let [img (image/image {:id :i :include-ns ["shop.**"]})
          sel (image/select-descriptors img synthetic-store)]
      (is (= [:cart/add :auth/set-user] (map :id sel))))))

(deftest select-zero-match-fails-loud
  (testing "an :include-ns pattern matching no descriptor throws"
    (let [img (image/image {:id :i :include-ns ["does.not.exist.**"]})]
      (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                            #"\[:rf\.error/image-zero-match\]"
                            (image/select-descriptors img synthetic-store)))))
  (testing "the diagnostic names the image, the pattern, and loaded namespaces"
    (let [img (image/image {:id :my/image :include-ns ["nope.*"]})]
      (try
        (image/select-descriptors img synthetic-store)
        (is false "expected a zero-match throw")
        (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e
          ;; thrown-ex-info MERGES :extra onto the top-level ex-data (it is not
          ;; nested under :extra) — read the surface slots at the top level.
          (let [data (ex-data e)]
            (is (= :rf.error/image-zero-match (:rf.error/id data)))
            (is (= :my/image (:image data)))
            (is (= "nope.*" (:pattern data)))
            (is (some #{"shop.cart"} (:loaded-ns data))))))))
  (testing "ONE matching + ONE zero-match pattern still fails (every pattern must match)"
    (let [img (image/image {:id :i :include-ns ["shop.**" "ghost.*"]})]
      (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                            #"\[:rf\.error/image-zero-match\]"
                            (image/select-descriptors img synthetic-store))))))

(deftest select-includes-inline-descriptors-unconditionally
  (testing "inline descriptors are selected even with NO :include-ns globs"
    (let [body (fn [_ _] {})
          img  (image/image
                 {:id :test/small
                  :registrations {:reg-event [[:counter/inc {} body]]}})
          sel  (image/select-descriptors img synthetic-store)]
      ;; no :include-ns → no glob selection, but the inline descriptor is present
      (is (= 1 (count sel)))
      (is (= :counter/inc (:id (first sel))))
      (is (= :test/small (:rf.provenance/image (first sel))))))
  (testing "inline descriptors are appended AFTER the glob-selected ones"
    (let [img (image/image
                {:id :combo
                 :include-ns ["shop.auth"]
                 :registrations {:reg-event [[:extra/evt {} (fn [_ _] {})]]}})
          sel (image/select-descriptors img synthetic-store)]
      (is (= 2 (count sel)))
      (is (= :auth/set-user (:id (first sel))))     ; glob-selected first
      (is (= :extra/evt     (:id (second sel))))))) ; inline appended

(deftest select-ignores-descriptors-without-provenance-ns
  (testing "a descriptor with no :rf.provenance/ns is never glob-selected"
    (let [store (conj synthetic-store
                      {:kind :fx :id :standard/fx :impl (fn [_] nil)}) ; no provenance
          img   (image/image {:id :i :include-ns ["**"]})
          sel   (image/select-descriptors img store)]
      ;; bare `**` matches every PROVENANCE ns, but the provenance-less
      ;; descriptor is excluded (it carries no :rf.provenance/ns to match).
      (is (not (some #(= :standard/fx (:id %)) sel)))
      (is (= (count synthetic-store) (count sel))))))

(deftest select-empty-image-selects-nothing
  (testing "an image with no globs and no inline selects an empty set"
    (let [img (image/image {:id :empty})]
      (is (= [] (image/select-descriptors img synthetic-store))))))

;; ============================================================================
;; :exclude-ns — the subtractive narrowing knob (EP-0023 §Namespace-Selected
;; Images). Drops glob-selected descriptors whose provenance ns matches an
;; exclude glob; never zero-match fail-loud; never touches inline descriptors.
;; ============================================================================

(def ^:private xray-style-store
  "A store modelling the EP-0023 §Xray Beside The Target singleton-seating case:
  PRODUCTION namespaces alongside their `*-cljs-test` siblings that co-register
  the same `(kind, id)` — the collision a `:exclude-ns` of the test nss avoids."
  [(desc "day8.re-frame2-xray.open-in-editor"          :fx    :rf.editor/open)
   (desc "day8.re-frame2-xray.mount"                   :event :rf.xray/open)
   (desc "day8.re-frame2-xray.panels.app-db-diff"      :sub   :rf.xray/diff)
   ;; the colliding test siblings (same ids, `*-cljs-test` provenance)
   (desc "day8.re-frame2-xray.open-in-editor-cljs-test" :fx    :rf.editor/open)
   (desc "day8.re-frame2-xray.mount-cljs-test"          :event :rf.xray/open)
   (desc "day8.re-frame2-xray.panels.app-db-diff-cljs-test" :sub :rf.xray/diff)
   ;; a test-helpers support ns (clean whole-segment boundary)
   (desc "day8.re-frame2-xray.test-helpers.host-fixtures.counter" :event :counter/inc)])

(deftest exclude-ns-subtracts-from-the-glob-selection
  (testing "an :exclude-ns glob drops the matched descriptors from the
            :include-ns selection — the test siblings are gone"
    (let [img (image/image {:id :rf.xray/image
                            :include-ns ["day8.re-frame2-xray.**"]
                            :exclude-ns ["day8.re-frame2-xray.**.*-cljs-test"
                                         "day8.re-frame2-xray.test-helpers.**"]})
          sel (image/select-descriptors img xray-style-store)
          prov (set (map :rf.provenance/ns sel))]
      ;; only the 3 PRODUCTION descriptors survive
      (is (= 3 (count sel)))
      (is (= #{"day8.re-frame2-xray.open-in-editor"
               "day8.re-frame2-xray.mount"
               "day8.re-frame2-xray.panels.app-db-diff"} prov))
      ;; no `*-cljs-test` provenance survives — no dup-id collision downstream
      (is (not-any? #(re-find #"-cljs-test$" %) prov))
      (is (not-any? #(re-find #"test-helpers" %) prov)))))

(deftest exclude-ns-makes-the-selection-id-disjoint
  (testing "without :exclude-ns the same `**` glob selects BOTH a prod and a
            test descriptor for the same [kind id] (the collision); WITH it the
            selection is id-disjoint"
    (let [include-only (image/image {:id :rf.xray/image
                                     :include-ns ["day8.re-frame2-xray.**"]})
          sel-all      (image/select-descriptors include-only xray-style-store)
          editor-all   (filter #(= :rf.editor/open (:id %)) sel-all)
          narrowed     (image/image {:id :rf.xray/image
                                     :include-ns ["day8.re-frame2-xray.**"]
                                     :exclude-ns ["day8.re-frame2-xray.**.*-cljs-test"
                                                  "day8.re-frame2-xray.test-helpers.**"]})
          sel-narrow   (image/select-descriptors narrowed xray-style-store)
          editor-narrow (filter #(= :rf.editor/open (:id %)) sel-narrow)]
      ;; the bare `**` glob selects TWO :rf.editor/open descriptors (prod + test)
      ;; — that is the duplicate-id that fails assembly.
      (is (= 2 (count editor-all)) "the bare glob sweeps in the colliding pair")
      ;; the exclude narrows it to exactly the production one.
      (is (= 1 (count editor-narrow)))
      (is (= "day8.re-frame2-xray.open-in-editor"
             (:rf.provenance/ns (first editor-narrow)))))))

(deftest exclude-ns-is-not-zero-match-fail-loud
  (testing "an :exclude-ns pattern that matches nothing in this build is a
            no-op, NOT a fail-loud error (unlike :include-ns)"
    (let [img (image/image {:id :i
                            :include-ns ["docs.shared.**"]
                            :exclude-ns ["does.not.exist.**" "*-cljs-test"]})
          sel (image/select-descriptors img synthetic-store)]
      ;; the include selection is untouched; the no-match excludes are no-ops.
      (is (= #{"docs.shared.widgets" "docs.shared.widgets.button"}
             (set (map :rf.provenance/ns sel)))))))

(deftest exclude-ns-never-drops-inline-descriptors
  (testing "inline descriptors are selected by image membership, never by
            provenance ns — an :exclude-ns glob does NOT remove them even if it
            would match their (synthetic) coordinate"
    (let [img (image/image {:id :combo
                            :include-ns ["day8.re-frame2-xray.**"]
                            :exclude-ns ["day8.re-frame2-xray.**.*-cljs-test"
                                         "day8.re-frame2-xray.test-helpers.**"
                                         ;; even a `**` that would match everything
                                         "**"]
                            :registrations {:reg-event [[:inline/evt {} (fn [_ _] {})]]}})
          sel (image/select-descriptors img xray-style-store)]
      ;; the bare `**` exclude drops every glob-selected registered descriptor,
      ;; but the inline descriptor survives (image-membership selection).
      (is (= 1 (count sel)))
      (is (= :inline/evt (:id (first sel))))
      (is (= :combo (:rf.provenance/image (first sel)))))))

(deftest exclude-by-ns-helper-is-order-preserving-and-empty-safe
  (testing "exclude-by-ns preserves input order and returns unchanged on no
            patterns"
    ;; Bare provenance-only descriptors so map equality is value-based (no fn
    ;; closures); `exclude-by-ns` reads only :rf.provenance/ns.
    (let [d   (fn [ns id] {:rf.provenance/ns ns :kind :event :id id})
          sel [(d "a.b" :x) (d "a.c" :y) (d "a.d" :z)]]
      (is (= sel (image/exclude-by-ns [] sel))
          "no patterns → identity (as a vector)")
      (is (= [(d "a.b" :x) (d "a.d" :z)]
             (image/exclude-by-ns ["a.c"] sel))
          "the matched-provenance descriptor is dropped; order preserved")
      (is (= [] (image/exclude-by-ns ["a.**"] sel))
          "a `**` exclude drops everything matched"))))
