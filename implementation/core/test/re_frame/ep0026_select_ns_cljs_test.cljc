(ns re-frame.ep0026-select-ns-cljs-test
  "EP-0026 §Namespace Selection / §Layered Resolution / §Image Keys (rf2-6ls85a)
  — the CORE resolution mechanism the rest of EP-0026 builds on:

    * `:select-ns` as ONE `{:include … :exclude …}` map (not sibling keys),
      with GLOBAL exclusion and STRICT include diagnostics (a zero-match
      `:include` pattern FAILS LOUD);
    * image-order resolution — the LATER image in `:images` WINS;
    * within ONE image any `[kind id]` that resolves two ways is an ERROR
      (two selected = ambiguous; inline-vs-selected = override-must-be-later;
      two inline = malformed);
    * image ids are UNIQUE per `:images` composition (a duplicate id fails loud);
    * `:select-ns` SELECTS, it does NOT load (it must not defeat DCE).

  Each fail-loud assertion checks the `:rf.error/id` discriminator, never the
  message bytes (Spec 009 §The thrown-error shape rule 3). Pure data — no
  adapter/runtime state. The framework-standard registry IS process state, so a
  fixture clears it per case. `.cljc` ending `-cljs-test` rides
  `npm run test:cljs` AND `clojure -M:test`."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.image          :as image]
            [re-frame.image-assembly :as asm]))

(use-fixtures :each
  (fn [t]
    (asm/clear-standards!)
    (t)
    (asm/clear-standards!)))

(defn- reg-desc
  "A synthetic REGISTERED descriptor authored in `provenance-ns`."
  [provenance-ns kind id impl]
  {:rf.provenance/ns provenance-ns
   :kind             kind
   :id               id
   :handler-fn       impl})

(defn- err-id
  [thunk]
  (try (thunk) nil
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
         (:rf.error/id (ex-data e)))))

(defn- err-data
  [thunk]
  (try (thunk) nil
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
         (ex-data e))))

;; ===========================================================================
;; 1. :select-ns — one {:include :exclude} map; global exclusion; strict include
;; ===========================================================================

(deftest select-ns-normalizes-to-the-internal-slots
  (testing ":select-ns {:include … :exclude …} lowers to the normalized
            :rf.image/include-ns / :rf.image/exclude-ns slots"
    (let [v (image/image {:id :app/main
                          :select-ns {:include ["app.todo.**" "app.admin.**"]
                                      :exclude ["app.todo.dev.**"]}})]
      (is (= ["app.todo.**" "app.admin.**"] (:rf.image/include-ns v)))
      (is (= ["app.todo.dev.**"] (:rf.image/exclude-ns v)))))
  (testing ":exclude is optional and defaults to []"
    (let [v (image/image {:id :app/main :select-ns {:include ["app.**"]}})]
      (is (= ["app.**"] (:rf.image/include-ns v)))
      (is (= [] (:rf.image/exclude-ns v))))))

(deftest select-ns-include-is-required-and-non-empty
  (testing "a :select-ns with NO :include fails loud"
    (is (= :rf.error/invalid-image
           (err-id #(image/image {:id :x :select-ns {:exclude ["a.**"]}})))))
  (testing "a :select-ns with an EMPTY :include vector fails loud"
    (is (= :rf.error/invalid-image
           (err-id #(image/image {:id :x :select-ns {:include []}})))))
  (testing "a non-vector :include fails loud"
    (is (= :rf.error/invalid-image
           (err-id #(image/image {:id :x :select-ns {:include "a.**"}})))))
  (testing "a non-vector :exclude fails loud"
    (is (= :rf.error/invalid-image
           (err-id #(image/image {:id :x :select-ns {:include ["a.**"] :exclude "b.**"}})))))
  (testing "an unknown :select-ns key fails loud"
    (is (= :rf.error/invalid-image
           (err-id #(image/image {:id :x :select-ns {:include ["a.**"] :bogus 1}})))))
  (testing "a non-map :select-ns fails loud"
    (is (= :rf.error/invalid-image
           (err-id #(image/image {:id :x :select-ns [:not :a :map]})))))
  (testing "a non-string include/exclude glob element fails loud"
    (is (= :rf.error/invalid-image
           (err-id #(image/image {:id :x :select-ns {:include ['a.b]}}))))
    (is (= :rf.error/invalid-image
           (err-id #(image/image {:id :x :select-ns {:include ["a.b"] :exclude ['c.d]}}))))))

(deftest select-ns-not-combinable-with-legacy-keys
  (testing ":select-ns cannot be combined with :include-ns / :exclude-ns"
    (is (= :rf.error/invalid-image
           (err-id #(image/image {:id :x :select-ns {:include ["a.**"]}
                                  :include-ns ["a.b"]}))))
    (is (= :rf.error/invalid-image
           (err-id #(image/image {:id :x :select-ns {:include ["a.**"]}
                                  :exclude-ns ["a.b"]}))))))

(deftest select-ns-global-exclusion-and-strict-include
  (let [pool [(reg-desc "app.todo.list"     :event :todo/add ::add)
              (reg-desc "app.todo.dev.seed" :event :todo/seed ::seed)
              (reg-desc "app.admin.users"   :event :admin/ban ::ban)]]
    (testing "include selects the union; exclude is GLOBAL (a namespace matched by
              any exclude is never selected, regardless of which include caught it)"
      (let [img (image/image {:id :app/main
                              :select-ns {:include ["app.todo.**" "app.admin.**"]
                                          :exclude ["app.todo.dev.**"]}})
            gen (asm/assemble [img] pool)]
        (is (contains? (:rf.gen/resolver gen) [:event :todo/add]))
        (is (contains? (:rf.gen/resolver gen) [:event :admin/ban]))
        (is (not (contains? (:rf.gen/resolver gen) [:event :todo/seed]))
            "the excluded dev namespace is dropped even though app.todo.** caught it")))
    (testing "a zero-match :include pattern FAILS LOUD (strict include diagnostics)"
      (let [img (image/image {:id :app/main :select-ns {:include ["app.nope.**"]}})]
        (is (= :rf.error/image-zero-match
               (err-id #(asm/assemble [img] pool))))))
    (testing "ONE matching + ONE zero-match include still fails (every pattern must match)"
      (let [img (image/image {:id :app/main
                              :select-ns {:include ["app.todo.**" "ghost.**"]}})]
        (is (= :rf.error/image-zero-match
               (err-id #(asm/assemble [img] pool))))))
    (testing "an :exclude pattern matching nothing is a no-op, NOT fail-loud"
      (let [img (image/image {:id :app/main
                              :select-ns {:include ["app.todo.list"]
                                          :exclude ["does.not.exist.**"]}})
            gen (asm/assemble [img] pool)]
        (is (contains? (:rf.gen/resolver gen) [:event :todo/add]))))))

;; ===========================================================================
;; 2. Image-order resolution — the LATER image wins (cross-image override)
;; ===========================================================================

(deftest later-image-wins-cross-image-override
  (testing "a [kind id] defined in two composed images resolves to the LATER
            image's descriptor — image order is the only precedence"
    (let [pool [(reg-desc "app.checkout" :fx :checkout.http/post ::real)]
          app-image    (image/image {:id :app/main
                                     :select-ns {:include ["app.checkout"]}})
          test-doubles (image/image {:id :test/doubles
                                     :registrations {:reg-fx [[:checkout.http/post {} ::stub]]}})]
      (testing "test-doubles composed AFTER app-image wins (the inline stub)"
        (let [gen (asm/assemble [app-image test-doubles] pool)]
          (is (= ::stub (:impl (asm/resolve-descriptor gen :fx :checkout.http/post))))))
      (testing "reversing the order reverses the winner (app-image now last)"
        (let [gen (asm/assemble [test-doubles app-image] pool)]
          (is (= ::real (:handler-fn (asm/resolve-descriptor gen :fx :checkout.http/post)))))))))

(deftest cross-image-shadow-does-not-fail-assembly
  (testing "a later image overriding an earlier one RESOLVES (later wins) — it
            does NOT fail assembly (a cross-image shadow is reported, not failed)"
    (let [pool      [(reg-desc "a.core" :event :app/boot ::a-boot)
                     (reg-desc "b.core" :event :app/boot ::b-boot)]
          img-a     (image/image {:id :img/a :select-ns {:include ["a.core"]}})
          img-b     (image/image {:id :img/b :select-ns {:include ["b.core"]}})
          gen       (asm/assemble [img-a img-b] pool)]
      (is (= ::b-boot (:handler-fn (asm/resolve-descriptor gen :event :app/boot)))
          "the later image (img-b) wins; assembly succeeds"))))

(deftest multi-image-chain-last-wins
  (testing "a chain [base override-a override-b] resolves to the LAST image's
            descriptor for the shared [kind id]"
    (let [base  (image/image {:id :base
                              :registrations {:reg-fx [[:metrics/send {} ::base]]}})
          ov-a  (image/image {:id :ov/a
                              :registrations {:reg-fx [[:metrics/send {} ::a]]}})
          ov-b  (image/image {:id :ov/b
                              :registrations {:reg-fx [[:metrics/send {} ::b]]}})
          gen   (asm/assemble [base ov-a ov-b] [])]
      (is (= ::b (:impl (asm/resolve-descriptor gen :fx :metrics/send)))
          "the last image in the chain wins"))))

;; ===========================================================================
;; 3. Within-image collision — every [kind id] resolving two ways is an ERROR
;; ===========================================================================

(deftest within-image-two-selected-is-ambiguous
  (testing "two SELECTED descriptors for the same [kind id] (different source
            namespaces) within ONE image → :rf.error/image-duplicate-id (ambiguous)"
    (let [pool [(reg-desc "todo.boot"    :event :boot/init ::todo)
                (reg-desc "counter.boot" :event :boot/init ::counter)]
          img  (image/image {:id :both
                             :select-ns {:include ["todo.boot" "counter.boot"]}})]
      (is (= :rf.error/image-duplicate-id
             (err-id #(asm/assemble [img] pool))))))
  (testing "the two-selected ambiguous error is order-independent within the image"
    (let [a   (reg-desc "todo.boot"    :event :boot/init ::a)
          b   (reg-desc "counter.boot" :event :boot/init ::b)
          img (image/image {:id :i :select-ns {:include ["todo.boot" "counter.boot"]}})]
      (is (= :rf.error/image-duplicate-id (err-id #(asm/assemble [img] [a b]))))
      (is (= :rf.error/image-duplicate-id (err-id #(asm/assemble [img] [b a])))))))

(deftest within-image-inline-vs-selected-collides
  (testing "an INLINE entry colliding with a SELECTED registration in ONE image →
            :rf.error/image-within-image-collision (an override must be a LATER image)"
    (let [pool [(reg-desc "counter.core" :event :counter/inc ::selected)]
          img  (image/image {:id :i
                             :select-ns {:include ["counter.core"]}
                             :registrations {:reg-event [[:counter/inc {} ::inline]]}})]
      (is (= :rf.error/image-within-image-collision
             (err-id #(asm/assemble [img] pool))))))
  (testing "the recovery names the move-to-a-later-image fix"
    (let [pool [(reg-desc "counter.core" :event :counter/inc ::selected)]
          img  (image/image {:id :i
                             :select-ns {:include ["counter.core"]}
                             :registrations {:reg-event [[:counter/inc {} ::inline]]}})
          d    (err-data #(asm/assemble [img] pool))]
      (is (= :rf.error/image-within-image-collision (:rf.error/id d)))
      (is (= :move-the-override-to-a-later-image-or-deduplicate (:recovery d))))))

(deftest within-image-two-inline-is-malformed
  (testing "TWO inline :registrations entries for the same [kind id] in ONE image →
            :rf.error/image-within-image-collision (malformed: define each once)"
    (let [img (image/image {:id :i
                            :registrations {:reg-event [[:counter/inc {} ::a]
                                                        [:counter/inc {} ::b]]}})]
      (is (= :rf.error/image-within-image-collision
             (err-id #(asm/assemble [img] [])))))))

;; ===========================================================================
;; 4. Image ids unique per :images composition (a duplicate id fails loud)
;; ===========================================================================

(deftest duplicate-image-id-fails-loud
  (testing "two images sharing an :id within one :images composition →
            :rf.error/image-duplicate-image-id"
    (let [pool [(reg-desc "a.core" :event :a/e ::a)
                (reg-desc "b.core" :event :b/e ::b)]
          img1 (image/image {:id :dup :select-ns {:include ["a.core"]}})
          img2 (image/image {:id :dup :select-ns {:include ["b.core"]}})]
      (is (= :rf.error/image-duplicate-image-id
             (err-id #(asm/assemble [img1 img2] pool))))))
  (testing "the diagnostic names the duplicate id"
    (let [img1 (image/image {:id :dup :registrations {:reg-fx [[:a {} ::a]]}})
          img2 (image/image {:id :dup :registrations {:reg-fx [[:b {} ::b]]}})
          d    (err-data #(asm/assemble [img1 img2] []))]
      (is (= :rf.error/image-duplicate-image-id (:rf.error/id d)))
      (is (= [:dup] (:duplicate-image-ids d)))))
  (testing "distinct ids compose cleanly; ANONYMOUS images (no :id) are exempt"
    (let [img1 (image/image {:registrations {:reg-fx [[:a {} ::a]]}})
          img2 (image/image {:registrations {:reg-fx [[:b {} ::b]]}})
          gen  (asm/assemble [img1 img2] [])]
      (is (contains? (:rf.gen/resolver gen) [:fx :a]))
      (is (contains? (:rf.gen/resolver gen) [:fx :b])))))

;; ===========================================================================
;; 5. Framework standards are protected — an app [kind id] colliding with a
;;    standard fails loud (standards are not part of app layer order)
;; ===========================================================================

(deftest app-shadowing-a-standard-fails-loud
  (testing "an app descriptor with the same [kind id] as a framework STANDARD →
            :rf.error/image-standard-replacement-forbidden (a standard must not
            be shadowed by a public app image)"
    (asm/register-standard! :fx :rf.nav/push-url {:handler-fn ::std})
    (let [pool [(reg-desc "product.story" :fx :rf.nav/push-url ::app-override)]
          img  (image/image {:id :i :select-ns {:include ["product.story"]}})]
      (is (= :rf.error/image-standard-replacement-forbidden
             (err-id #(asm/assemble [img] pool))))))
  (testing "an INLINE app entry colliding with a standard also fails loud"
    (asm/register-standard! :fx :rf.nav/push-url {:handler-fn ::std})
    (let [img (image/image {:id :i :registrations {:reg-fx [[:rf.nav/push-url {} ::app]]}})]
      (is (= :rf.error/image-standard-replacement-forbidden
             (err-id #(asm/assemble [img] []))))))
  (testing "a standard with NO colliding app id is unioned into the generation"
    (asm/register-standard! :fx :rf.nav/push-url {:handler-fn ::std})
    (let [pool [(reg-desc "app.core" :event :app/boot ::boot)]
          img  (image/image {:id :i :select-ns {:include ["app.core"]}})
          gen  (asm/assemble [img] pool)]
      (is (= ::std (:handler-fn (asm/resolve-descriptor gen :fx :rf.nav/push-url))))
      (is (contains? (:rf.gen/resolver gen) [:event :app/boot])))))

;; ===========================================================================
;; 6. :select-ns SELECTS, it does NOT load — DCE preserved
;; ===========================================================================

(deftest select-ns-selects-does-not-load
  (testing ":select-ns only FILTERS the candidate pool by :rf.provenance/ns — it
            never loads a namespace. A namespace NOT present in the pool is simply
            not selectable (an include naming only it zero-matches); selection
            does not conjure descriptors for an unloaded namespace."
    (let [pool [(reg-desc "loaded.core" :event :a/e ::a)]
          ;; "unloaded.feature" is NOT in the pool — :select-ns cannot load it.
          img  (image/image {:id :i :select-ns {:include ["unloaded.feature.**"]}})]
      (is (= :rf.error/image-zero-match
             (err-id #(asm/assemble [img] pool)))
          "an include naming only an unloaded namespace zero-matches — selection
           never loaded it into existence")))
  (testing "selection chooses from the descriptors the runtime ALREADY knows
            about — a loaded sibling is selected, the absent one is not"
    (let [pool [(reg-desc "loaded.a" :event :a/e ::a)
                (reg-desc "loaded.b" :event :b/e ::b)]
          img  (image/image {:id :i :select-ns {:include ["loaded.a"]}})
          gen  (asm/assemble [img] pool)]
      (is (contains? (:rf.gen/resolver gen) [:event :a/e]))
      (is (not (contains? (:rf.gen/resolver gen) [:event :b/e]))
          "loaded.b exists in the pool but is not selected — and was never loaded
           by the selection"))))

(deftest select-ns-resolution-is-pure-no-mutation
  (testing "resolution does NOT mutate the image value or its registrations
            (EP-0026 §Layered Resolution — resolution must not mutate the image)"
    (let [pool [(reg-desc "app.core" :event :a/e ::a)]
          img  (image/image {:id :i :select-ns {:include ["app.core"]}})
          img-before (into {} img)]
      (asm/assemble [img] pool)
      (is (= img-before (into {} img)) "the image value is unchanged after assembly"))))
