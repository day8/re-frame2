(ns re-frame.ep0026-retirements-cljs-test
  "EP-0026 §Image Keys / §Capability Removal / §Backwards Compatibility
  (rf2-dlvmpc) — the RETIREMENTS slice:

    * the EP-0023 image source keys `:include-ns`, `:exclude-ns`, `:replace`,
      `:replace-standard`, and `:rf.image/requires` are RETIRED and FAIL LOUD at
      `rf/image` with an actionable migration diagnostic (`:rf.error/invalid-image`);
    * the THREE image-capability surfaces are removed SURGICALLY end-to-end —
      `:rf.image/requires` (no longer on the image value), `make-frame
      :capabilities` (no frame-boundary capability check, no
      `image-assembly/check-capabilities!` / `image-requires`), and
      `:rf.gen/requires` (no longer on a sealed generation);
    * the removal is SCOPED: the UNRELATED `:rf.capability/*` host-service
      vocabulary stays valid + usable as ordinary keyword data — only the
      image-capability requirement surface is gone.

  Each fail-loud assertion checks the `:rf.error/id` discriminator, never the
  message bytes (Spec 009 §The thrown-error shape rule 3). `.cljc` ending
  `-cljs-test` rides `npm run test:cljs` AND `clojure -M:test`."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])
            [re-frame.image          :as image]
            [re-frame.image-assembly :as asm]))

(defn- err-id
  [thunk]
  (try (thunk) nil
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
         (:rf.error/id (ex-data e)))))

(defn- retired-key?
  [thunk]
  (try (thunk) nil
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
         (:retired-key (ex-data e)))))

;; ===========================================================================
;; 1. Each retired image key FAILS LOUD at rf/image (EP-0026 §Backwards
;;    Compatibility — "Retired keys MUST fail loudly so stale examples do not
;;    keep working by accident").
;; ===========================================================================

(deftest each-retired-image-key-fails-loud
  (testing ":include-ns is RETIRED (superseded by :select-ns :include)"
    (is (= :rf.error/invalid-image
           (err-id #(image/image {:id :x :include-ns ["docs.counter.v2"]}))))
    (is (= :include-ns
           (retired-key? #(image/image {:id :x :include-ns ["docs.counter.v2"]})))))
  (testing ":exclude-ns is RETIRED (superseded by :select-ns :exclude)"
    (is (= :rf.error/invalid-image
           (err-id #(image/image {:id :x :exclude-ns ["docs.counter.dev.**"]}))))
    (is (= :exclude-ns
           (retired-key? #(image/image {:id :x :exclude-ns ["docs.counter.dev.**"]})))))
  (testing ":replace is RETIRED (composition resolves by image order)"
    (is (= :rf.error/invalid-image
           (err-id #(image/image {:id :x :replace {[:event :counter/inc]
                                                   {:ns "docs.counter.v3"}}}))))
    (is (= :replace
           (retired-key? #(image/image {:id :x :replace {[:event :counter/inc]
                                                         {:ns "docs.counter.v3"}}})))))
  (testing ":replace-standard is RETIRED (standards are protected)"
    (is (= :rf.error/invalid-image
           (err-id #(image/image {:id :x :replace-standard
                                  {[:interceptor :rf.interceptor/path] {:standard true}}}))))
    (is (= :replace-standard
           (retired-key? #(image/image {:id :x :replace-standard
                                        {[:interceptor :rf.interceptor/path] {:standard true}}})))))
  (testing ":rf.image/requires is RETIRED (image-declared host capabilities removed)"
    (is (= :rf.error/invalid-image
           (err-id #(image/image {:id :x :rf.image/requires #{:rf.capability/http}}))))
    (is (= :rf.image/requires
           (retired-key? #(image/image {:id :x :rf.image/requires #{:rf.capability/http}}))))))

(deftest a-retired-key-is-not-a-silent-alias
  (testing "a retired key is REJECTED, never silently lowered to its successor"
    ;; :include-ns must NOT be quietly accepted as :select-ns :include — it fails.
    (is (= :rf.error/invalid-image
           (err-id #(image/image {:id :x :include-ns ["a.b"]}))))
    ;; The clean three-key surface still works.
    (let [v (image/image {:id :x :select-ns {:include ["a.b"]}})]
      (is (= ["a.b"] (:rf.image/include-ns v))))))

;; ===========================================================================
;; 2. The THREE image-capability surfaces are removed end-to-end (SURGICAL).
;; ===========================================================================

(deftest image-value-carries-no-requires-slot
  (testing "EP-0026 removed the :rf.image/requires slot — a constructed image
            value does NOT carry it"
    (let [v (image/image {:id :app/main :select-ns {:include ["a.b"]}})]
      (is (not (contains? v :rf.image/requires))
          "the image value no longer carries an :rf.image/requires slot"))))

(deftest sealed-generation-carries-no-requires-slot
  (testing "EP-0026 removed :rf.gen/requires — a sealed generation carries
            :rf.gen/resolver / :rf.gen/images / :rf.gen/kinds (+ the additive
            :rf.gen/shadows report, rf2-ke7w5j), but no :rf.gen/requires"
    (let [pool [{:rf.provenance/ns "a.b" :kind :event :id :foo/x :handler-fn (fn [_ _])}]
          img  (image/image {:id :app/main :select-ns {:include ["a.b"]}})
          gen  (asm/assemble [img] pool)]
      (is (not (contains? gen :rf.gen/requires))
          "the sealed generation no longer carries :rf.gen/requires")
      (is (contains? gen :rf.gen/resolver))
      (is (contains? gen :rf.gen/images))
      (is (contains? gen :rf.gen/kinds)))))

(deftest image-capability-check-vars-are-gone
  (testing "EP-0026 deleted image-assembly/check-capabilities! and /image-requires
            — the capability-check seam is removed, not left as a half-feature"
    ;; A resolved var would be a live fn; an absent one resolves to nil. ns-resolve
    ;; on the CLJS side is not available, so probe the ns map on the JVM and the
    ;; module export on CLJS via a deliberate compile-safe indirection: the
    ;; behavioural proof is that assembling an image with no capability inputs
    ;; succeeds (no capability gate runs at the frame boundary). The frame-boundary
    ;; capability check lived in re-frame.live-frame; its removal is exercised by
    ;; live_frame_cljs_test (a frame created from an image needs no :capabilities).
    #?(:clj
       (do
         (is (nil? (ns-resolve 're-frame.image-assembly 'check-capabilities!))
             "image-assembly/check-capabilities! is deleted")
         (is (nil? (ns-resolve 're-frame.image-assembly 'image-requires))
             "image-assembly/image-requires is deleted")
         (is (nil? (ns-resolve 're-frame.frame 'frame-capabilities))
             "frame/frame-capabilities is deleted"))
       :cljs
       ;; CLJS: no runtime var table to probe; the absence is enforced by the
       ;; clean compile (any caller of the deleted vars would fail to build) plus
       ;; the JVM branch above. Assert the behavioural counterpart instead:
       ;; assembling needs no capability inputs and produces no requires slot.
       (let [pool [{:rf.provenance/ns "a.b" :kind :event :id :foo/x :handler-fn (fn [_ _])}]
             gen  (asm/assemble [(image/image {:id :a :select-ns {:include ["a.b"]}})] pool)]
         ;; :rf.gen/requires was RETIRED with the image-capability feature.
         (is (not (contains? gen :rf.gen/requires)))))))

;; ===========================================================================
;; 3. The removal is SCOPED — the UNRELATED :rf.capability/* host-service
;;    vocabulary stays valid + usable (EP-0026 §Capability Removal — "none of
;;    those are touched").
;; ===========================================================================

(deftest unrelated-rf-capability-vocabulary-is-untouched
  (testing "the :rf.capability/* host-service keywords remain ordinary, valid
            keyword data — EP-0026 retired only the image-capability REQUIREMENT
            surface, never the capability vocabulary itself"
    (doseq [k [:rf.capability/http :rf.capability/clock :rf.capability/random
               :rf.capability/schemas :rf.capability/routes :rf.capability/ssr]]
      (is (keyword? k))
      (is (= "rf.capability" (namespace k))))
    ;; A capability map remains a perfectly ordinary value an app may build, pass
    ;; as frame record-config, or hand to an adapter — it is simply no longer
    ;; checked against an image-declared requirement.
    (let [cap-map {:rf.capability/http ::http-impl
                   :rf.capability/clock ::clock-impl}]
      (is (= ::http-impl (:rf.capability/http cap-map)))
      (is (contains? cap-map :rf.capability/clock)))))
