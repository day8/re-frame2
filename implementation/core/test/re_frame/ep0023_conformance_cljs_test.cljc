(ns re-frame.ep0023-conformance-cljs-test
  "EP-0023 (Images And Frame-Loaded Instruction Sets) end to end:
  image -> frame -> event stream, driven through the public surfaces
  (`rf/image`, `image-assembly/assemble`, `live-frame/make-frame`,
  re-`make-frame` reload, `reproject-live-frames!`) the way a consumer reaches
  them. Each slice's own contract is pinned by its unit suite; this suite
  holds the cross-slice cases, each named for its EP section:

    s1 image construction + selection (the glob grammar and inline lowering:
       `image-cljs-test`)
    s2 sealed assembly + validation (generation shape, duplicate id,
       unsupported kind: `image-assembly-cljs-test`)
    -- layered resolution: `ep0026-select-ns-cljs-test`,
       `image-assembly-cljs-test`
    s4 default-image projection (the cross-namespace collision:
       `image-assembly-default-cljs-test`)
    s5 make-frame attaches the generation (target-frame resolution,
       absence-is-default, all-or-nothing scope: `frame-resolution-cljs-test`;
       idempotent re-construction: `live-frame-cljs-test`)
    -- generation cache + invalidation: `image-assembly-cache-cljs-test`,
       `image-assembly-default-cljs-test`, `source-store-cljs-test`
    s7 hot reload (a sibling frame left untouched: `live-frame-reload-cljs-test`)
    s8 the one frame constructor (EP-0024) and construction ordering (EP-0027)
    s9 the framework-standard registry
    s10 inline events deliver `:rf.cofx/requires`

  Fail-loud cases read the `:rf.error/id` discriminator, never the message
  (Spec 009 §The thrown-error shape rule 3). Most cases resolve against an
  explicit descriptor pool (the 2-arity), so the fixture resets only the
  derived state; the live-store reprojection case snapshot/restores the source
  store and registrar itself."
  (:require #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
            [re-frame.image          :as rf.image]
            [re-frame.image-assembly :as rf.image-assembly]
            [re-frame.source-store   :as rf.source-store]
            [re-frame.registrar      :as rf.registrar]
            [re-frame.live-frame     :as rf.live-frame]
            [re-frame.frame          :as rf.frame]
            [re-frame.interop        :as rf.interop]
            [re-frame.core           :as rf]
            [re-frame.std-interceptors    :as rf.std-interceptors]
            [re-frame.interceptor-registry :as rf.interceptor-registry]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support   :as rf.test-support]))

;; The registrar is snapshot/restored by the runtime fixture (never
;; `clear-all!`); the standard registry and generation cache are EP-0023's own
;; process state, reset directly.
(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter})
  (fn [t]
    (rf.image-assembly/clear-standards!)
    (rf.image-assembly/clear-generation-cache!)
    (t)
    (rf.image-assembly/clear-standards!)
    (rf.image-assembly/clear-generation-cache!)))

(defn- reg-desc
  "A synthetic registered descriptor authored in `provenance-ns`, shaped like a
  source-store entry."
  [provenance-ns kind id impl]
  {:rf.provenance/ns provenance-ns
   :kind             kind
   :id               id
   :handler-fn       impl})

(defn- ex-data-of [thunk]
  (try (thunk) nil
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
         (ex-data e))))

(defn- err-id [thunk] (:rf.error/id (ex-data-of thunk)))

(defn- resolved-handler [gen kind id]
  (:handler-fn (rf.image-assembly/resolve-descriptor gen kind id)))

;; ---- s1: image construction + selection ------------------------------------

(deftest s1-image-is-an-inert-value-with-normalized-shape
  ;; §Public API: equal specs give equal values — no realm, no registrar
  (let [a (rf/image {:id :counter/v2 :select-ns {:include ["docs.counter.v2"]}})
        b (rf/image {:id :counter/v2 :select-ns {:include ["docs.counter.v2"]}})]
    (is (= [b :counter/v2 ["docs.counter.v2"] true]
           [a (:rf.image/id a) (:rf.image/include-ns a) (vector? (:rf.image/inline a))]))))

(deftest s1-zero-match-selection-fails-loud
  ;; §Namespace-Selected Images: the error names the image, the pattern and the
  ;; loaded provenance namespaces considered
  (let [data (ex-data-of
               #(rf.image/select-descriptors
                  (rf/image {:id :shop/main :select-ns {:include ["shop.checkout.**"]}})
                  [(reg-desc "shop.cart" :event :cart/add ::add)]))]
    (is (= {:image :shop/main :pattern "shop.checkout.**" :loaded-ns ["shop.cart"]}
           (select-keys data [:image :pattern :loaded-ns])))))

;; ---- s2: sealed assembly + validation --------------------------------------

(deftest s2-missing-reference-fails-loud
  ;; §Image Validation: an event naming an application interceptor the image
  ;; does not select fails; selecting the interceptor too resolves cleanly
  (let [img (rf/image {:select-ns {:include ["app.core"]}})
        ev  (assoc (reg-desc "app.core" :event :counter/inc ::inc) :interceptors [:my.audit/guard])]
    (is (= [:rf.error/image-missing-reference true]
           [(err-id #(rf.image-assembly/assemble [img] [ev]))
            (map? (rf.image-assembly/assemble
                    [img] [ev (reg-desc "app.core" :interceptor :my.audit/guard ::guard)]))]))))

;; ---- s4: default-image projection -----------------------------------------

(deftest s4-default-image-projects-the-whole-store-plus-standards
  ;; §Default Image Semantics: empty or nil :images is the implicit selector
  ;; over the WHOLE store, with the framework standards unioned in
  (rf.image-assembly/register-standard! :fx :rf.nav/push-url {:handler-fn ::std-nav})
  (let [pool [(reg-desc "shop.cart"    :event :cart/add    ::add)
              (reg-desc "shop.auth"    :event :auth/login  ::login)
              (reg-desc "shop.catalog" :view  :catalog/grid ::grid)]
        gen  (rf.image-assembly/assemble [] pool)]
    (is (every? #(contains? (:rf.gen/resolver gen) %)
                [[:event :cart/add] [:event :auth/login] [:view :catalog/grid] [:fx :rf.nav/push-url]]))
    (is (= gen (rf.image-assembly/assemble nil pool)))))

;; ---- s5: make-frame + frame-derived resolution -----------------------------

(deftest s5-make-frame-attaches-the-resolved-generation
  ;; §Frame: the frame carries the SAME sealed generation assemble produces
  (let [pool [(reg-desc "examples.counter" :event :counter/inc ::inc)]
        img  (rf/image {:select-ns {:include ["examples.counter"]}})
        gen  (rf.live-frame/frame-generation (rf.live-frame/make-frame {:images [img]} pool))]
    (is (= [(rf.image-assembly/assemble [img] pool) ::inc]
           [gen (resolved-handler gen :event :counter/inc)]))))

;; ---- s7: hot reload --------------------------------------------------------

(deftest s7-reload-images-swaps-the-generation-and-reports-the-diff
  ;; §Hot Reload: re-`make-frame` with a new :images vector REPLACES the whole
  ;; composition (a different ns selected), and generation-diff over the
  ;; before/after generations classifies the move
  (let [v1-pool [(reg-desc "counter.v1" :event :counter/inc ::v1-inc)]
        v2-pool [(reg-desc "counter.v2" :event :counter/inc ::v2-inc)
                 (reg-desc "counter.v2" :event :counter/reset ::v2-reset)]
        before  (rf.live-frame/frame-generation
                  (rf.live-frame/make-frame {:id :counter/main
                                             :images [(rf/image {:select-ns {:include ["counter.v1"]}})]}
                                            v1-pool))
        after   (rf.live-frame/frame-generation
                  (rf.live-frame/make-frame {:id :counter/main
                                             :images [(rf/image {:select-ns {:include ["counter.v2"]}})]}
                                            v2-pool))
        diff    (rf.live-frame/generation-diff before after)]
    (is (= [::v2-inc true true]
           [(resolved-handler after :event :counter/inc)
            (contains? (:added diff) [:event :counter/reset])
            (contains? (:changed diff) [:event :counter/inc])]))))

(deftest s7-reproject-live-frames-re-resolves-explicit-image-frames
  ;; §Hot Reload: a reg-* re-eval in a namespace an explicit image selects
  ;; reprojects THAT frame; a frame whose resolution is unchanged is untouched.
  ;; `next-tick` is a no-op for the whole case, body and teardown: the
  ;; registration hook's deferred flush would otherwise race the explicit
  ;; reproject, swap the frame first, and leave it reporting `{}`.
  (with-redefs [rf.interop/next-tick (fn [_f] nil)]
    (rf.live-frame/flush-pending-reprojection!)
    (let [store-before @rf.source-store/kind->id->ns->descriptor
          reg-before   @rf.registrar/kind->id->metadata]
      (try
        (reset! rf.source-store/kind->id->ns->descriptor {})
        (reset! rf.registrar/kind->id->metadata {})
        (rf.image-assembly/clear-generation-cache!)
        (rf.registrar/register! :event :counter/inc {:handler-fn ::v1 :ns "docs.counter.parity"})
        (rf.registrar/register! :event :other/x     {:handler-fn ::ox :ns "docs.other"})
        (rf.live-frame/make-frame {:id :docs/parity
                                   :images [(rf/image {:select-ns {:include ["docs.counter.parity"]}})]})
        (rf.live-frame/make-frame {:id :docs/other
                                   :images [(rf/image {:select-ns {:include ["docs.other"]}})]})
        (let [other-gen-before (rf.live-frame/frame-generation :docs/other)]
          (rf.registrar/register! :event :counter/inc {:handler-fn ::v2 :ns "docs.counter.parity"})
          (let [moved (rf.live-frame/reproject-live-frames!)]
            (is (= [true ::v2 false true]
                   [(contains? moved :docs/parity)
                    (resolved-handler (rf.live-frame/frame-generation :docs/parity) :event :counter/inc)
                    (contains? moved :docs/other)
                    (identical? other-gen-before (rf.live-frame/frame-generation :docs/other))]))))
        (finally
          (rf.live-frame/flush-pending-reprojection!)
          (reset! rf.source-store/kind->id->ns->descriptor store-before)
          (reset! rf.registrar/kind->id->metadata reg-before)
          (rf.image-assembly/clear-generation-cache!))))))

;; ---- s8: the one frame constructor -----------------------------------------

(deftest s8-make-frame-is-the-one-constructor
  ;; EP-0024 §One constructor: rf/make-frame returns the frame VALUE (not a
  ;; keyword id), and record-config keys are honoured in the same call
  (let [pool    [(reg-desc "examples.counter" :event :counter/inc ::inc)]
        img     (rf/image {:select-ns {:include ["examples.counter"]}})
        created (rf/make-frame {:id :counter/one :images [img]} pool)
        cfg     (rf/make-frame {:id :counter/cfg :images [img] :doc "configured" :preset :test} pool)]
    (is (= [true :counter/one "configured"]
           [(rf.live-frame/frame-object? created)
            (rf.frame/frame-value->id created)
            (:doc (rf/frame-meta :counter/cfg))]))
    (rf/destroy-frame! created)
    (rf/destroy-frame! cfg)))

(deftest s8-initial-events-seed-installed-before-later-setup-events
  ;; EP-0027 §Construction ordering: the [:rf/set-db …] seed is installed
  ;; before the later setup event runs, which observes it without clobbering it
  (rf/reg-event :ep0024.oc/init
    (fn [{:keys [db]} _] {:db (assoc db :saw-seed (:seed db) :booted? true)}))
  (let [f (rf/make-frame {:id :ep0024/oc-frame
                          :initial-events [[:rf/set-db {:seed 42}] [:ep0024.oc/init]]})]
    (is (= {:seed 42 :saw-seed 42 :booted? true} (rf/app-db-value :ep0024/oc-frame)))
    (rf/destroy-frame! f)))

;; ---- s9: the framework-standard registry -----------------------------------
;;
;; The boot path contributes `:rf.interceptor/path` to the standard registry,
;; not only the registrar: generation-routed lookup reads only the generation's
;; resolver, so an image-loaded event naming the standard by reference needs it
;; there. The fixture clears standards, so these cases re-seed through the boot
;; fn `init!` runs.

(deftest s9-standard-interceptor-is-in-the-framework-standard-registry
  (rf.std-interceptors/register-standard-interceptors!)
  (let [std-desc (some #(when (= [:interceptor :rf.interceptor/path] [(:kind %) (:id %)]) %)
                       (rf.image-assembly/standard-descriptors))]
    ;; invariant-coupled (non-empty requires-conformance), hence not replaceable,
    ;; and carrying the descriptor the regular registrar stores
    (is (= [true true true false true]
           [(some? std-desc)
            (true? (:standard std-desc))
            (boolean (seq (:rf.standard/requires-conformance std-desc)))
            (rf.image-assembly/standard-replaceable? std-desc)
            (contains? std-desc :rf/interceptor-descriptor)]))))

(deftest s9-standard-interceptor-ref-resolves-under-a-generation
  ;; §Frame-derived live registration resolution: the standard rides into the
  ;; frame's generation and a [:rf.interceptor/path …] ref resolves under it,
  ;; while an unknown ref still fails loud there
  (rf.std-interceptors/register-standard-interceptors!)
  (let [pool  [(reg-desc "shop.cart" :event :cart/add ::cart-add)]
        frame (rf.live-frame/make-frame {:images [(rf/image {:select-ns {:include ["shop.cart"]}})]} pool)]
    (is (contains? (:rf.gen/resolver (rf.live-frame/frame-generation frame))
                   [:interceptor :rf.interceptor/path]))
    (rf.live-frame/call-with-frame-resolution frame
      (fn []
        (let [resolved (rf.interceptor-registry/resolve-ref [:rf.interceptor/path [:cart :items]])]
          (is (= [true :rf.interceptor/path true :rf.error/unregistered-interceptor]
                 [(some? rf.registrar/*generation*)
                  (:id resolved)
                  (fn? (:before resolved))
                  (err-id #(rf.interceptor-registry/resolve-ref :app/never-registered))])))))))

(deftest s9-public-app-image-cannot-shadow-a-standard
  ;; EP-0026 §Framework Standard Registrations: there is no public
  ;; standard-replacement opt-in — even a standard flagged replaceable? is not
  ;; shadowable by a public app image
  (rf.image-assembly/register-standard! :fx :rf.nav/push-url
                                        {:handler-fn ::std-nav :rf.standard/replaceable? true})
  (is (= :rf.error/image-standard-replacement-forbidden
         (err-id #(rf.image-assembly/assemble
                    [(rf/image {:id :i :select-ns {:include ["product.story"]}})]
                    [(reg-desc "product.story" :fx :rf.nav/push-url ::app-nav)])))))

;; ---- s10: inline events deliver :rf.cofx/requires --------------------------
;;
;; §Image Fragments: the inline path lowers to the same runtime descriptor
;; shape as `reg-event`, including `:rf.cofx/requires-parsed`, so declared-only
;; delivery (EP-0017 §5) is uniform across registration paths. The supplier is
;; declared inline too: a bound generation resolves only its own descriptors.

(deftest s10-inline-image-event-receives-declared-cofx-requires
  (let [seen  (atom ::unset)
        img   (rf/image
                {:id :inline/cofx
                 :registrations
                 {:reg-cofx  [[:inline.cofx/locale {:doc "Inline ambient supplier."} (fn [] "en-AU")]]
                  :reg-event [[:inline.cofx/touch
                               {:doc "Inline event declaring a cofx requirement."
                                :rf.cofx/requires [:inline.cofx/locale]}
                               (fn [{:keys [db inline.cofx/locale]} _]
                                 (reset! seen locale)
                                 {:db (assoc db :ran? true)})]]}})
        frame (rf.live-frame/make-frame {:id :inline/cofx-frame :images [img]} [])]
    (rf/dispatch-sync [:inline.cofx/touch] {:frame :inline/cofx-frame})
    (is (= [true "en-AU"] [(:ran? (rf/app-db-value :inline/cofx-frame)) @seen]))
    (rf/destroy-frame! frame)))
