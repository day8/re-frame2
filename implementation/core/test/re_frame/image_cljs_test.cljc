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
      (is (= #{:rf.capability/http} (:rf.image/requires v)))
      (is (= [] (:rf.image/inline v)))))
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
  (testing "the ex-data carries :rf.error/id for machine branching"
    (is (= :rf.error/invalid-image
           (try (image/image {:bogus 1})
                (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e
                  (:rf.error/id (ex-data e))))))))

;; ============================================================================
;; :replace / :replace-standard — declared-winner maps (EP-0023 §Image
;; Patching And Overrides). BARE structural slots — validated here (must be
;; maps when supplied) and carried through uninterpreted.
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
    (let [rep      {[:event :counter/inc] :docs.counter.v3/counter-inc}
          rep-std  {[:fx :http] :app.http/winner}
          v        (image/image {:id :combo
                                 :replace          rep
                                 :replace-standard rep-std})]
      (is (= rep     (:replace v)))
      (is (= rep-std (:replace-standard v)))))
  (testing "an empty :replace map is still carried through (present-when-supplied)"
    ;; cond-> branches on (:replace spec) truthiness; an empty map is truthy.
    (let [v (image/image {:id :e :replace {}})]
      (is (contains? v :replace))
      (is (= {} (:replace v)))))
  (testing "absent :replace / :replace-standard keys are NOT stamped onto the value"
    (let [v (image/image {:id :bare :include-ns ["docs.counter.v2"]})]
      (is (not (contains? v :replace)))
      (is (not (contains? v :replace-standard))))))

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

(deftest inline-metadata-only-entry
  (testing "a [id metadata] tuple lowers without an :impl"
    (let [v (image/image {:id :m
                          :registrations {:reg-fx [[:my/fx {:doc "meta only"}]]}})
          d (first (:rf.image/inline v))]
      (is (= :fx (:kind d)))
      (is (= :my/fx (:id d)))
      (is (not (contains? d :impl)))
      (is (= {:doc "meta only"} (:metadata d))))))

(deftest inline-rejects-unknown-section-and-malformed-entry
  (testing "unknown inline section key throws :rf.error/invalid-image"
    (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                          #"\[:rf\.error/invalid-image\]"
                          (image/image {:registrations {:reg-bogus [[:x {}]]}}))))
  (testing "a non-tuple inline entry throws"
    (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                          #"\[:rf\.error/invalid-image\]"
                          (image/image {:registrations {:reg-event [:not-a-tuple]}})))))

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
