(ns re-frame.ep0026-select-ns-cljs-test
  "EP-0026 §Namespace Selection / §Layered Resolution / §Image Keys — the core
  resolution mechanism the rest of EP-0026 builds on:

    * `:select-ns` is ONE `{:include … :exclude …}` map, with GLOBAL exclusion
      and STRICT include diagnostics (a zero-match `:include` pattern fails
      loud);
    * image order resolves — the LATER image in `:images` wins;
    * within ONE image any `[kind id]` that resolves two ways is an error (two
      selected = ambiguous; inline-vs-selected = the override must be a later
      image; two inline = malformed, pinned by `image-assembly-cljs-test`).

  Image-id uniqueness and framework-standard protection are pinned by
  `image-assembly-cljs-test`. Fail-loud cases read the `:rf.error/id`
  discriminator, never the message (Spec 009 §The thrown-error shape rule 3).
  The framework-standard registry is process state, so the fixture clears it."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.image          :as rf.image]
            [re-frame.image-assembly :as rf.image-assembly]))

(use-fixtures :each
  (fn [t]
    (rf.image-assembly/clear-standards!)
    (t)
    (rf.image-assembly/clear-standards!)))

(defn- reg-desc
  "A synthetic registered descriptor authored in `provenance-ns`."
  [provenance-ns kind id impl]
  {:rf.provenance/ns provenance-ns
   :kind             kind
   :id               id
   :handler-fn       impl})

(defn- err-data [thunk]
  (try (thunk) nil
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
         (ex-data e))))

(defn- err-id [thunk] (:rf.error/id (err-data thunk)))

;; ---- :select-ns — one {:include :exclude} map ------------------------------

(deftest select-ns-include-is-required-and-non-empty
  ;; one row per validation clause: missing / empty / non-vector :include,
  ;; non-vector :exclude, an unknown key, a non-map, a non-string glob in
  ;; :include and in :exclude
  (is (= (repeat 8 :rf.error/invalid-image)
         (map #(err-id (fn [] (rf.image/image {:id :x :select-ns %})))
              [{:exclude ["a.**"]}
               {:include []}
               {:include "a.**"}
               {:include ["a.**"] :exclude "b.**"}
               {:include ["a.**"] :bogus 1}
               [:not :a :map]
               {:include ['a.b]}
               {:include ["a.b"] :exclude ['c.d]}]))))

(deftest select-ns-global-exclusion-and-strict-include
  (let [pool     [(reg-desc "app.todo.list"     :event :todo/add ::add)
                  (reg-desc "app.todo.dev.seed" :event :todo/seed ::seed)
                  (reg-desc "app.admin.users"   :event :admin/ban ::ban)]
        resolves (fn [select-ns]
                   (set (keys (:rf.gen/resolver
                                (rf.image-assembly/assemble [(rf.image/image {:id :app/main :select-ns select-ns})]
                                                           pool)))))]
    (testing "include selects the union; exclude is GLOBAL — a namespace any
              exclude matches is dropped whichever include caught it"
      (is (= #{[:event :todo/add] [:event :admin/ban]}
             (resolves {:include ["app.todo.**" "app.admin.**"] :exclude ["app.todo.dev.**"]}))))
    (testing "every include pattern must match: a zero-match pattern fails loud,
              alone or beside a matching one"
      (is (= [:rf.error/image-zero-match :rf.error/image-zero-match]
             [(err-id #(resolves {:include ["app.nope.**"]}))
              (err-id #(resolves {:include ["app.todo.**" "ghost.**"]}))])))
    (testing "an :exclude pattern matching nothing is a no-op, not fail-loud"
      (is (= #{[:event :todo/add]}
             (resolves {:include ["app.todo.list"] :exclude ["does.not.exist.**"]}))))))

;; ---- image order: the LATER image wins -------------------------------------

(deftest later-image-wins-cross-image-override
  ;; image order is the only precedence, so reversing it reverses the winner
  (let [pool         [(reg-desc "app.checkout" :fx :checkout.http/post ::real)]
        app-image    (rf.image/image {:id :app/main :select-ns {:include ["app.checkout"]}})
        test-doubles (rf.image/image {:id :test/doubles
                                      :registrations {:reg-fx [[:checkout.http/post {} ::stub]]}})
        resolved     #(rf.image-assembly/resolve-descriptor (rf.image-assembly/assemble % pool) :fx :checkout.http/post)]
    (is (= [::stub ::real]
           [(:impl (resolved [app-image test-doubles]))
            (:handler-fn (resolved [test-doubles app-image]))]))))

(deftest multi-image-chain-last-wins
  ;; three layers tell "the last wins" apart from "the second wins"
  (let [layer (fn [id impl] (rf.image/image {:id id :registrations {:reg-fx [[:metrics/send {} impl]]}}))
        gen   (rf.image-assembly/assemble [(layer :base ::base) (layer :ov/a ::a) (layer :ov/b ::b)] [])]
    (is (= ::b (:impl (rf.image-assembly/resolve-descriptor gen :fx :metrics/send))))))

;; ---- within one image, a [kind id] resolving two ways is an error -----------

(deftest within-image-two-selected-is-ambiguous
  ;; two SELECTED descriptors for one [kind id] from different namespaces are
  ;; ambiguous, in either pool order
  (let [a   (reg-desc "todo.boot"    :event :boot/init ::a)
        b   (reg-desc "counter.boot" :event :boot/init ::b)
        img (rf.image/image {:id :i :select-ns {:include ["todo.boot" "counter.boot"]}})]
    (is (= [:rf.error/image-duplicate-id :rf.error/image-duplicate-id]
           [(err-id #(rf.image-assembly/assemble [img] [a b]))
            (err-id #(rf.image-assembly/assemble [img] [b a]))]))))

(deftest within-image-inline-vs-selected-collides
  ;; an INLINE entry colliding with a SELECTED registration in one image fails,
  ;; and the recovery names the move-to-a-later-image fix
  (let [img (rf.image/image {:id :i
                             :select-ns {:include ["counter.core"]}
                             :registrations {:reg-event [[:counter/inc {} ::inline]]}})]
    (is (= {:rf.error/id :rf.error/image-within-image-collision
            :recovery    :move-the-override-to-a-later-image-or-deduplicate}
           (select-keys (err-data #(rf.image-assembly/assemble
                                     [img] [(reg-desc "counter.core" :event :counter/inc ::selected)]))
                        [:rf.error/id :recovery])))))
