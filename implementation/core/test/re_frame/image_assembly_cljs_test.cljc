(ns re-frame.image-assembly-cljs-test
  "Image assembly (EP-0023, EP-0026 §Layered Resolution): image values resolve
  into a sealed, validated `[kind id]` generation, and every malformed
  composition fails loud before a frame runs, with a structured diagnostic. A
  `[kind id]` defined by several images resolves to the later image, and the
  shadow is reported rather than failed.

  Each refusal is asserted on its `:rf.error/id` and ex-data, never the message
  (Spec 009 §The thrown-error shape rule 3). The framework-standard registry is
  process state, so a fixture clears it per case."
  (:require #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
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

(defn- assembly-error-data
  "The ex-data `thunk` throws, or nil."
  [thunk]
  (try (thunk) nil
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
         (ex-data e))))

(deftest successful-projection-selected-plus-inline-plus-standard
  (rf.image-assembly/register-standard! :fx :rf.nav/push-url {:handler-fn ::std-nav})
  (let [pool [(reg-desc "shop.cart" :event :cart/add ::cart-add)
              (reg-desc "shop.cart" :sub   :cart/items ::cart-items)
              (reg-desc "shop.other" :event :other/noise ::noise)]
        img  (rf.image/image
               {:id :shop/main
                :select-ns {:include ["shop.cart"]}
                :registrations
                {:reg-fx [[:cart.http/post {:doc "post"} ::http-post]]}})
        gen  (rf.image-assembly/assemble [img] pool)]
    (is (= #{[:event :cart/add] [:sub :cart/items] [:fx :cart.http/post] [:fx :rf.nav/push-url]}
           (set (keys (:rf.gen/resolver gen))))
        "the selected namespace, the inline entry and the standard, nothing else")
    (is (= [::cart-add ::http-post #{:event :sub :fx}]
           [(:handler-fn (rf.image-assembly/resolve-descriptor gen :event :cart/add))
            (:impl (rf.image-assembly/resolve-descriptor gen :fx :cart.http/post))
            (rf.image-assembly/generation-kinds gen)]))))

(deftest within-image-two-inline-is-malformed
  (let [img (rf.image/image
              {:id :i
               :registrations {:reg-fx [[:checkout.http/post {} ::a]
                                        [:checkout.http/post {} ::b]]}})]
    (is (= :rf.error/image-within-image-collision
           (:rf.error/id (assembly-error-data #(rf.image-assembly/assemble [img] [])))))))

(deftest unsupported-kind-ex-data-carries-provenance
  (let [pool [{:rf.provenance/ns "weird.ns" :kind :not-a-kind :id :x/y
               :handler-fn ::w}]
        img  (rf.image/image {:id :w/img :select-ns {:include ["weird.ns"]}})]
    (is (= {:rf.error/id      :rf.error/image-unsupported-kind
            :image            :w/img
            :kind             :not-a-kind
            :id               :x/y
            :rf.provenance/ns "weird.ns"
            :coordinate       {:ns "weird.ns"}
            :recovery         :correct-the-descriptor-kind}
           (select-keys (assembly-error-data #(rf.image-assembly/assemble [img] pool))
                        [:rf.error/id :image :kind :id :rf.provenance/ns :coordinate :recovery])))))

(deftest later-image-cannot-shadow-a-standard
  ;; standards are not part of app layer order, so image position does not matter
  (rf.image-assembly/register-standard! :fx :rf.nav/push-url {:handler-fn ::std})
  (let [pool [(reg-desc "app.core" :event :app/boot ::boot)]
        base (rf.image/image {:id :base :select-ns {:include ["app.core"]}})
        ovr  (rf.image/image {:id :ovr
                              :registrations {:reg-fx [[:rf.nav/push-url {} ::app]]}})]
    (is (= :rf.error/image-standard-replacement-forbidden
           (:rf.error/id (assembly-error-data #(rf.image-assembly/assemble [base ovr] pool)))))))

(deftest framework-standard-interceptor-reference-skipped
  ;; a reserved :rf.interceptor/* ref is framework-provided, not image-supplied
  (let [pool [(assoc (reg-desc "app.core" :event :cart/add ::add)
                     :interceptors [[:rf.interceptor/path [:cart]]])]
        img  (rf.image/image {:id :i :select-ns {:include ["app.core"]}})]
    (is (contains? (:rf.gen/resolver (rf.image-assembly/assemble [img] pool))
                   [:event :cart/add]))))

(deftest descriptor-coordinate-by-source
  (is (= [{:ns "shop.cart"} {:image :i :inline [:reg-fx :x]} {:standard true}]
         (mapv rf.image-assembly/descriptor-coordinate
               [(reg-desc "shop.cart" :event :x ::f)
                {:kind :fx :id :x :rf.provenance/image :i :rf.provenance/inline [:reg-fx :x]}
                {:kind :fx :id :x :standard true}]))))

(deftest duplicate-image-id-across-composition-fails-loud
  (let [pool  [(reg-desc "a.core" :fx :checkout.http/post ::a)
               (reg-desc "b.core" :fx :checkout.http/post ::b)]
        img-a (rf.image/image {:id :dup :select-ns {:include ["a.core"]}})
        img-b (rf.image/image {:id :dup :select-ns {:include ["b.core"]}})]
    (is (= {:rf.error/id :rf.error/image-duplicate-image-id :duplicate-image-ids [:dup]}
           (select-keys (assembly-error-data #(rf.image-assembly/assemble [img-a img-b] pool))
                        [:rf.error/id :duplicate-image-ids])))))

(deftest anonymous-image-in-multi-composition-fails-loud
  ;; an anonymous image cannot be named in the shadow report, so it cannot take
  ;; part in a multi-image composition, colliding or not
  (let [pool   [(reg-desc "app.a" :event :x ::a)
                (reg-desc "app.b" :event :x ::b)]
        anon-a (rf.image/image {:select-ns {:include ["app.a"]}})
        anon-b (rf.image/image {:select-ns {:include ["app.b"]}})
        named  (rf.image/image {:id :app/a :select-ns {:include ["app.a"]}})]
    (is (= {:rf.error/id :rf.error/image-duplicate-image-id :anonymous-image-count 2}
           (select-keys (assembly-error-data #(rf.image-assembly/assemble [anon-a anon-b] pool))
                        [:rf.error/id :anonymous-image-count]))
        "two colliding anonymous images fail loud instead of a nil/nil shadow entry")
    (is (= :rf.error/image-duplicate-image-id
           (:rf.error/id (assembly-error-data #(rf.image-assembly/assemble [named anon-b] pool))))
        "one anonymous image beside a named one")))

(deftest shadow-report-two-losers-of-the-same-winner
  ;; one entry per shadowed registration, naming its own loser image
  (let [pool  [(reg-desc "a.core" :fx  :pay/post ::a-pay)
               (reg-desc "b.core" :sub :pay/total ::b-total)]
        img-a (rf.image/image {:id :img/a :select-ns {:include ["a.core"]}})
        img-b (rf.image/image {:id :img/b :select-ns {:include ["b.core"]}})
        dbls  (rf.image/image {:id :test/doubles
                               :registrations {:reg-fx  [[:pay/post {} ::stub-post]]
                                               :reg-sub [[:pay/total (fn [_ _] 0)]]}})]
    (is (= [{:registration [:fx :pay/post]   :image :img/a :shadowed-by :test/doubles}
            {:registration [:sub :pay/total] :image :img/b :shadowed-by :test/doubles}]
           (rf.image-assembly/generation-shadows
             (rf.image-assembly/assemble [img-a img-b dbls] pool))))))

(deftest cross-image-shadow-does-not-fail-and-is-reported
  (let [pool  [(reg-desc "a.core" :fx :checkout.http/post ::a)
               (reg-desc "b.core" :fx :checkout.http/post ::b)]
        img-a (rf.image/image {:id :img/a :select-ns {:include ["a.core"]}})
        img-b (rf.image/image {:id :img/b :select-ns {:include ["b.core"]}})
        gen   (rf.image-assembly/assemble [img-a img-b] pool)]
    (is (= [::b [{:registration [:fx :checkout.http/post] :image :img/a :shadowed-by :img/b}]]
           [(:handler-fn (rf.image-assembly/resolve-descriptor gen :fx :checkout.http/post))
            (rf.image-assembly/generation-shadows gen)])
        "the later image wins and the shadow is reported")))

(defn- resource-desc
  "A synthetic `:resource` descriptor whose spec carries `scope`; a
  `{:from-db <id>}` scope references a `:resource-scope` resolver."
  [provenance-ns resource-id scope]
  {:rf.provenance/ns provenance-ns
   :kind             :resource
   :id               resource-id
   :handler-fn       ::request-fn
   :rf/resource      {:scope scope :params-schema [:map] :request ::request-fn}})

(defn- scope-resolver-desc
  "A synthetic `:resource-scope` resolver authored in `provenance-ns`."
  [provenance-ns scope-id]
  {:rf.provenance/ns provenance-ns
   :kind             :resource-scope
   :id               scope-id
   :handler-fn       ::resolve-fn})

(deftest resource-present-scope-resolver-passes
  (let [pool [(resource-desc "shop.articles" :article/by-slug {:from-db :shop/session})
              (scope-resolver-desc "shop.scopes" :shop/session)]
        img  (rf.image/image {:id :i :select-ns {:include ["shop.articles" "shop.scopes"]}})]
    (is (= #{[:resource :article/by-slug] [:resource-scope :shop/session]}
           (set (keys (:rf.gen/resolver (rf.image-assembly/assemble [img] pool))))))))

(deftest resource-concrete-scope-references-nothing
  ;; only a {:from-db ...} scope references a resolver, even when a concrete
  ;; scope tuple carries a map
  (let [pool [(resource-desc "shop.a" :a/global :rf.scope/global)
              (resource-desc "shop.b" :b/session [:rf.scope/session {:u 1}])]
        img  (rf.image/image {:id :i :select-ns {:include ["shop.a" "shop.b"]}})]
    (is (= #{[:resource :a/global] [:resource :b/session]}
           (set (keys (:rf.gen/resolver (rf.image-assembly/assemble [img] pool))))))))

(deftest resource-missing-scope-ref-ex-data-is-structured
  (let [pool [(resource-desc "shop.articles" :article/by-slug {:from-db :shop/session})]
        img  (rf.image/image {:id :shop/img :select-ns {:include ["shop.articles"]}})]
    (is (= {:rf.error/id       :rf.error/image-missing-reference
            :image             :shop/img
            :kind              :resource
            :id                :article/by-slug
            :rf.provenance/ns  "shop.articles"
            :coordinate        {:ns "shop.articles"}
            :missing-reference [:resource-scope :shop/session]
            :recovery          :select-the-missing-registration-or-fix-the-reference}
           (select-keys (assembly-error-data #(rf.image-assembly/assemble [img] pool))
                        [:rf.error/id :image :kind :id :rf.provenance/ns :coordinate
                         :missing-reference :recovery])))))

(deftest interceptor-missing-ref-ex-data-carries-provenance
  (let [pool [(assoc (reg-desc "app.core" :event :cart/add ::add)
                     :interceptors [:my.audit/guard])]
        img  (rf.image/image {:id :app/img :select-ns {:include ["app.core"]}})]
    (is (= {:rf.error/id       :rf.error/image-missing-reference
            :image             :app/img
            :kind              :event
            :id                :cart/add
            :rf.provenance/ns  "app.core"
            :coordinate        {:ns "app.core"}
            :missing-reference [:interceptor :my.audit/guard]
            :recovery          :select-the-missing-registration-or-fix-the-reference}
           (select-keys (assembly-error-data #(rf.image-assembly/assemble [img] pool))
                        [:rf.error/id :image :kind :id :rf.provenance/ns :coordinate
                         :missing-reference :recovery])))))

(deftest standard-forbidden-ex-data-names-the-app-coordinate
  (rf.image-assembly/register-standard! :fx :rf.nav/push-url {:handler-fn ::std})
  (let [pool [(reg-desc "product.story" :fx :rf.nav/push-url ::app-override)]
        img  (rf.image/image {:id :p/img :select-ns {:include ["product.story"]}})]
    (is (= {:rf.error/id         :rf.error/image-standard-replacement-forbidden
            :kind                :fx
            :id                  :rf.nav/push-url
            :standard-coordinate {:standard true}
            :app-coordinate      {:ns "product.story"}
            :recovery            :rename-the-app-id-or-deselect-it}
           (select-keys (assembly-error-data #(rf.image-assembly/assemble [img] pool))
                        [:rf.error/id :kind :id :standard-coordinate :app-coordinate
                         :recovery])))))

(deftest within-image-duplicate-id-ex-data-names-colliding-coordinates
  (let [pool [(reg-desc "todo.boot"    :event :boot/init ::a)
              (reg-desc "counter.boot" :event :boot/init ::b)]
        img  (rf.image/image {:id :both/img
                              :select-ns {:include ["todo.boot" "counter.boot"]}})
        d    (assembly-error-data #(rf.image-assembly/assemble [img] pool))]
    (is (= [{:rf.error/id :rf.error/image-duplicate-id
             :image       :both/img
             :kind        :event
             :id          :boot/init
             :recovery    :narrow-the-selection-or-rename-the-id}
            #{{:ns "todo.boot"} {:ns "counter.boot"}}]
           [(select-keys d [:rf.error/id :image :kind :id :recovery])
            (set (:colliding-coordinates d))]))))
