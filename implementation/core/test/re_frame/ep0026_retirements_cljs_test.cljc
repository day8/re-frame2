(ns re-frame.ep0026-retirements-cljs-test
  "EP-0026 §Image Keys / §Capability Removal / §Backwards Compatibility —
  retired image keys and the absent image-capability surfaces:

    * the EP-0023 image source keys `:include-ns`, `:exclude-ns`, `:replace`,
      `:replace-standard`, and `:rf.image/requires` are RETIRED and FAIL LOUD at
      `rf/image` with an actionable migration diagnostic (`:rf.error/invalid-image`);
    * the THREE image-capability surfaces are absent end-to-end —
      `:rf.image/requires` (not on the image value), `make-frame
      :capabilities` (no frame-boundary capability check, no
      `image-assembly/check-capabilities!` / `image-requires`), and
      `:rf.gen/requires` (not on a sealed generation). The image value's and
      the sealed generation's exact shapes, which carry neither slot, are
      pinned by `image-cljs-test` and `facade-frame-read-cljs-test`.

  Each fail-loud assertion checks the `:rf.error/id` discriminator, never the
  message bytes (Spec 009 §The thrown-error shape rule 3). `.cljc` ending
  `-cljs-test` rides `npm run test:cljs` AND `clojure -M:test`."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])
            [re-frame.image          :as rf.image]
            #?(:cljs [re-frame.image-assembly :as rf.image-assembly])))

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
  (testing ":include-ns is RETIRED (the spelling is :select-ns :include)"
    (is (= :rf.error/invalid-image
           (err-id #(rf.image/image {:id :x :include-ns ["docs.counter.v2"]}))))
    (is (= :include-ns
           (retired-key? #(rf.image/image {:id :x :include-ns ["docs.counter.v2"]})))))
  (testing ":exclude-ns is RETIRED (the spelling is :select-ns :exclude)"
    (is (= :rf.error/invalid-image
           (err-id #(rf.image/image {:id :x :exclude-ns ["docs.counter.dev.**"]}))))
    (is (= :exclude-ns
           (retired-key? #(rf.image/image {:id :x :exclude-ns ["docs.counter.dev.**"]})))))
  (testing ":replace is RETIRED (composition resolves by image order)"
    (is (= :rf.error/invalid-image
           (err-id #(rf.image/image {:id :x :replace {[:event :counter/inc]
                                                   {:ns "docs.counter.v3"}}}))))
    (is (= :replace
           (retired-key? #(rf.image/image {:id :x :replace {[:event :counter/inc]
                                                         {:ns "docs.counter.v3"}}})))))
  (testing ":replace-standard is RETIRED (standards are protected)"
    (is (= :rf.error/invalid-image
           (err-id #(rf.image/image {:id :x :replace-standard
                                  {[:interceptor :rf.interceptor/path] {:standard true}}}))))
    (is (= :replace-standard
           (retired-key? #(rf.image/image {:id :x :replace-standard
                                        {[:interceptor :rf.interceptor/path] {:standard true}}})))))
  (testing ":rf.image/requires is RETIRED (images declare no host capabilities)"
    (is (= :rf.error/invalid-image
           (err-id #(rf.image/image {:id :x :rf.image/requires #{:rf.capability/http}}))))
    (is (= :rf.image/requires
           (retired-key? #(rf.image/image {:id :x :rf.image/requires #{:rf.capability/http}}))))))

;; ===========================================================================
;; 2. The THREE image-capability surfaces are absent end-to-end.
;; ===========================================================================

(deftest image-capability-check-vars-are-gone
  (testing "EP-0026: image-assembly/check-capabilities! and /image-requires do
            not exist — there is no capability-check seam, not even a
            half-feature"
    ;; A resolved var would be a live fn; an absent one resolves to nil. ns-resolve
    ;; on the CLJS side is not available, so probe the ns map on the JVM and the
    ;; module export on CLJS via a deliberate compile-safe indirection: the
    ;; behavioural proof is that assembling an image with no capability inputs
    ;; succeeds (no capability gate runs at the frame boundary). The frame
    ;; boundary itself is exercised by live_frame_cljs_test (a frame created
    ;; from an image needs no :capabilities).
    #?(:clj
       (do
         (is (nil? (ns-resolve 're-frame.image-assembly 'check-capabilities!))
             "image-assembly/check-capabilities! is absent")
         (is (nil? (ns-resolve 're-frame.image-assembly 'image-requires))
             "image-assembly/image-requires is absent")
         (is (nil? (ns-resolve 're-frame.frame 'frame-capabilities))
             "frame/frame-capabilities is absent"))
       :cljs
       ;; CLJS: no runtime var table to probe; the absence is enforced by the
       ;; clean compile (any caller of the absent vars would fail to build) plus
       ;; the JVM branch above. Assert the behavioural counterpart instead:
       ;; assembling needs no capability inputs and produces no requires slot.
       (let [pool [{:rf.provenance/ns "a.b" :kind :event :id :foo/x :handler-fn (fn [_ _])}]
             gen  (rf.image-assembly/assemble [(rf.image/image {:id :a :select-ns {:include ["a.b"]}})] pool)]
         ;; A sealed generation carries no :rf.gen/requires.
         (is (not (contains? gen :rf.gen/requires)))))))
