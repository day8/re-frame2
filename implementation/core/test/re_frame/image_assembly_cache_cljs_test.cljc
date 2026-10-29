(ns re-frame.image-assembly-cache-cljs-test
  "The resolved-generation cache (EP-0023 §Image: the reference implementation
  MUST cache resolved generations). Identical inputs return the identical
  sealed object, so a request-scoped frame does not re-seal. Every input that
  can change the generation is part of the key: the ordered image vector (with
  each image's selection and inline descriptors), the source store's identity
  and generation, and the standard registration generation. A failing assembly
  caches nothing. A fixture clears the store, the standards and the cache."
  (:require #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
            [re-frame.image          :as rf.image]
            [re-frame.image-assembly :as rf.image-assembly]
            [re-frame.source-store   :as rf.source-store]))

(defn- clear-all! []
  (rf.source-store/clear-all!)
  (rf.image-assembly/clear-standards!)
  (rf.image-assembly/clear-generation-cache!))

(use-fixtures :each
  (fn [t]
    (clear-all!)
    (t)
    (clear-all!)))

(defn- record! [provenance-ns kind id impl]
  (rf.source-store/record-descriptor! kind id {:ns provenance-ns :kind kind :id id
                                  :handler-fn impl}))

(deftest identical-inputs-reuse-the-same-sealed-generation
  ;; keyed by value: a separately constructed equal image hits the same slot
  (record! "shop.cart" :event :cart/add ::add)
  (let [spec {:id :shop/main :select-ns {:include ["shop.cart"]}}]
    (is (identical? (rf.image-assembly/assemble [(rf.image/image spec)])
                    (rf.image-assembly/assemble [(rf.image/image spec)])))))

(deftest a-cache-hit-never-flattens-the-store
  ;; the descriptor pool is read only on a miss, so a hit is a key build and a
  ;; lookup; the miss after a registration is the instrument's control
  (record! "shop.cart" :event :cart/add ::add)
  (let [img      (rf.image/image {:id :shop/main :select-ns {:include ["shop.cart"]}})
        sealed   (rf.image-assembly/assemble [img])
        default  (rf.image-assembly/assemble [])
        flattens (atom 0)
        kinds    rf.source-store/kinds-present]
    (with-redefs [rf.source-store/kinds-present (fn [] (swap! flattens inc) (kinds))]
      (let [hits [(identical? sealed (rf.image-assembly/assemble [img]))
                  (identical? default (rf.image-assembly/assemble []))
                  @flattens]]
        (record! "shop.cart" :sub :cart/items ::items)
        (reset! flattens 0)
        (rf.image-assembly/assemble [img])
        (is (= [[true true 0] 1] [hits @flattens])
            "both hits skip the flattener; the miss after a registration calls it once")))))

(deftest changed-selected-descriptor-invalidates
  (record! "shop.cart" :event :cart/add ::add)
  (let [img (rf.image/image {:id :shop/main :select-ns {:include ["shop.cart"]}})]
    (rf.image-assembly/assemble [img])
    (record! "shop.cart" :sub :cart/items ::items)
    (is (contains? (:rf.gen/resolver (rf.image-assembly/assemble [img])) [:sub :cart/items])
        "the re-sealed generation reflects the new registration")))

(deftest forgetting-a-selected-descriptor-invalidates
  (record! "shop.cart" :event :cart/add ::add)
  (record! "shop.cart" :sub   :cart/items ::items)
  (let [img (rf.image/image {:id :shop/main :select-ns {:include ["shop.cart"]}})]
    (rf.image-assembly/assemble [img])
    (rf.source-store/forget-descriptor! :sub :cart/items "shop.cart")
    (is (not (contains? (:rf.gen/resolver (rf.image-assembly/assemble [img])) [:sub :cart/items]))
        "the re-sealed generation does not carry the forgotten descriptor")))

(deftest changed-standard-descriptor-invalidates
  (record! "shop.cart" :event :cart/add ::add)
  (let [img (rf.image/image {:id :shop/main :select-ns {:include ["shop.cart"]}})]
    (rf.image-assembly/assemble [img])
    (rf.image-assembly/register-standard! :fx :rf.nav/push-url {:handler-fn ::std-nav})
    (is (contains? (:rf.gen/resolver (rf.image-assembly/assemble [img])) [:fx :rf.nav/push-url])
        "the re-sealed generation unions in the new standard")))

(deftest changed-inline-descriptor-invalidates
  (record! "checkout.core" :event :checkout/start ::start)
  (let [image-with (fn [impl]
                     (rf.image/image {:id            :checkout/main
                                      :select-ns     {:include ["checkout.core"]}
                                      :registrations {:reg-fx [[:checkout.http/post {} impl]]}}))]
    (is (= [::impl-a ::impl-b]
           (mapv #(:impl (rf.image-assembly/resolve-descriptor
                           (rf.image-assembly/assemble [(image-with %)]) :fx :checkout.http/post))
                 [::impl-a ::impl-b])))))

(deftest image-order-invalidates-even-with-same-selections
  ;; the later image wins, so the two orders resolve differently
  (record! "checkout.core"       :fx :checkout.http/post ::real)
  (record! "checkout.story.http" :fx :checkout.http/post ::fake)
  (let [img-real (rf.image/image {:id :checkout/real :select-ns {:include ["checkout.core"]}})
        img-fake (rf.image/image {:id :checkout/fake :select-ns {:include ["checkout.story.http"]}})]
    (is (= [::fake ::real]
           (mapv #(:handler-fn (rf.image-assembly/resolve-descriptor
                                 (rf.image-assembly/assemble %) :fx :checkout.http/post))
                 [[img-real img-fake] [img-fake img-real]])))))

(deftest select-ns-selection-is-part-of-the-key
  (record! "shop.cart"  :event :cart/add  ::cart)
  (record! "shop.admin" :event :admin/ban ::admin)
  (is (= [#{[:event :cart/add]} #{[:event :admin/ban]}]
         (mapv #(set (keys (:rf.gen/resolver
                             (rf.image-assembly/assemble
                               [(rf.image/image {:id :shop/main :select-ns {:include [%]}})]))))
               ["shop.cart" "shop.admin"]))))

(deftest exclude-ns-selection-is-part-of-the-key
  (record! "app.feature"     :event :feature/run ::run)
  (record! "app.feature.dev" :event :dev/probe   ::probe)
  (let [include ["app.feature.**" "app.feature"]]
    (is (= [true false]
           (mapv #(contains? (:rf.gen/resolver
                               (rf.image-assembly/assemble
                                 [(rf.image/image {:id :app/main :select-ns %})]))
                             [:event :dev/probe])
                 [{:include include}
                  {:include include :exclude ["app.feature.dev.**" "app.feature.dev"]}])))))

(deftest fail-loud-input-is-not-cached
  ;; so correcting the store and re-assembling recomputes instead of re-throwing
  (record! "todo.boot"    :event :boot/init ::todo)
  (record! "counter.boot" :event :boot/init ::counter)
  (let [img (rf.image/image {:id :both :select-ns {:include ["todo.boot" "counter.boot"]}})]
    (is (= [:rf.error/image-duplicate-id 0]
           [(try (rf.image-assembly/assemble [img]) nil
                 (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
                   (:rf.error/id (ex-data e))))
            (rf.image-assembly/cache-size)]))))

(deftest a-restored-store-is-a-new-generation
  ;; a restore moves the table back to an earlier value but the generation
  ;; forward, so the entry sealed from the later table cannot answer for it
  (let [img     (rf.image/image {:id :shared/main :select-ns {:include ["shared.core"]}})
        resolve #(:handler-fn (rf.image-assembly/resolve-descriptor
                                (rf.image-assembly/assemble [img]) :event :shared/boot))]
    (record! "shared.core" :event :shared/boot ::a-handler)
    (let [snapshot @rf.source-store/kind->id->ns->descriptor
          sealed-a (resolve)]
      (record! "shared.core" :event :shared/boot ::b-handler)
      (let [sealed-b (resolve)]
        (rf.source-store/restore! snapshot)
        (is (= [::a-handler ::b-handler ::a-handler] [sealed-a sealed-b (resolve)])
            "after the restore the table's own handler resolves again")))))

(deftest a-re-seal-drops-the-generation-it-supersedes
  ;; the store and standard generations only move forward, so a composition's
  ;; entry at an older one can never hit again
  (let [img   (rf.image/image {:id :shop/main :select-ns {:include ["shop.cart"]}})
        other (rf.image/image {:id :shop/other :select-ns {:include ["shop.cart"]}})
        pool  [{:rf.provenance/ns "shop.cart" :kind :event :id :cart/add :handler-fn ::pooled}]]
    (dotimes [_ 3]
      (record! "shop.cart" :event :cart/add ::add)
      (rf.image-assembly/assemble [img]))
    (rf.image-assembly/register-standard! :fx :rf.nav/push-url {:handler-fn ::std-nav})
    (rf.image-assembly/assemble [img])
    (rf.image-assembly/assemble [other])
    (rf.image-assembly/assemble [img] pool)
    (is (= 3 (rf.image-assembly/cache-size))
        "one live-store entry per composition, beside the explicit-pool entry")))

(deftest the-cache-holds-at-most-its-limit
  ;; an inline registration built per call around a fresh fn is a new key on
  ;; every assemble, so only the bound stops those compositions accumulating:
  ;; the oldest entry goes first, and the newest still hits
  (record! "shop.cart" :event :cart/add ::add)
  (let [limit        @#'rf.image-assembly/generation-cache-limit
        app          (rf.image/image {:id :shop/main :select-ns {:include ["shop.cart"]}})
        mock         (fn [n] (rf.image/image {:id            :shop/mock
                                              :registrations {:reg-event [[:shop/price (fn [_ _] {:price n})]]}}))
        oldest       [app (mock -1)]
        oldest-gen   (rf.image-assembly/assemble oldest)]
    (dotimes [n limit]
      (rf.image-assembly/assemble [app (mock n)]))
    (let [newest [app (mock :newest)]
          sealed (rf.image-assembly/assemble newest)]
      (is (= [limit true false]
             [(rf.image-assembly/cache-size)
              (identical? sealed (rf.image-assembly/assemble newest))
              (identical? oldest-gen (rf.image-assembly/assemble oldest))])))))

(deftest explicit-pool-arity-hits-on-equal-pool
  ;; the supplied pool value is the key's pool leg
  (let [pool [{:rf.provenance/ns "a.core" :kind :event :id :a/e :handler-fn ::a}]
        img  (rf.image/image {:id :a :select-ns {:include ["a.core"]}})]
    (is (identical? (rf.image-assembly/assemble [img] pool)
                    (rf.image-assembly/assemble [img] (vec pool))))
    (is (contains? (:rf.gen/resolver
                     (rf.image-assembly/assemble
                       [img] (conj pool {:rf.provenance/ns "a.core" :kind :sub :id :a/s
                                         :handler-fn ::s})))
                   [:sub :a/s])
        "a changed pool re-seals")))
