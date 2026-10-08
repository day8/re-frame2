(ns re-frame.subs-tooling-inspected-frame-metadata-cljs-test
  "The two live subscription-cache readers, `sub-cache-snapshot` and
  `sub-cache-algebra-view`, resolve registration METADATA through the frame
  they were asked about, not through whichever registrar generation is ambient.

  Both read the target frame's cached reactions, then join each entry against
  the generation-routed `rf.registrar/registrations :sub` for `:input-kind`,
  `:doc`, `:schema`, `:derive` and the source coordinates. Xray's
  derivation-graph contributor and the Pair preload's `sub-cache-info` call
  them from OUTSIDE the inspected frame, so the target has to be passed rather
  than inherited. A wrong join is inconsistent evidence rather than a wrong app
  value, which correct values cannot vouch for, because `subscribe` establishes
  the target generation on its own path.

  CLJS-only: both readers are `#?(:cljs …)`-bodied and return nil on the JVM."
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.image :as rf.image]
            [re-frame.live-frame :as rf.live-frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.subs.tooling :as rf.subs.tooling]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter}))

(def ^:private base-q  [:review/base])
(def ^:private value-q [:review/value])
(def ^:private local-q [:review/image-only])

(defn- review-image
  "Inline registrations: a layer-1 `:review/base`, and `:review/value` and
  `:review/image-only` declaring `:inputs` over it (so `:input-kind :static`),
  all carrying `doc`. Nothing registers `:review/image-only` globally."
  [image-id doc base-value]
  (rf.image/image
    {:id            image-id
     :registrations
     {:reg-sub [[:review/base {:doc doc} (fn [_db _q] base-value)]
                [:review/value {:inputs [base-q] :doc doc} (fn [[n] _q] n)]
                [:review/image-only {:inputs [base-q] :doc doc} (fn [[n] _q] (inc n))]]}}))

(defn- install-frame! [frame-id image]
  (rf.live-frame/make-frame {:id frame-id :images [image]} []))

(defn- inspected
  "What the two readers report for `frame-id`'s `:review/value` entry. `:derive`
  is applied to `[42]`: the image's body returns 42, the global's nil."
  [frame-id]
  (let [node (get (rf.subs.tooling/sub-cache-algebra-view frame-id) value-q)]
    {:snapshot-kind (:input-kind (get (rf.subs.tooling/sub-cache-snapshot frame-id) value-q))
     :kind          (:input-kind node)
     :doc           (:doc node)
     :inputs        (:inputs node)
     :derived       ((:derive node) [42] value-q)
     :value         (:value node)}))

(deftest readers-resolve-metadata-through-the-inspected-frame
  ;; Two frames materialize :review/value from different images over a
  ;; conflicting same-id global. Each is inspected from no binding and from
  ;; inside the other frame's generation binding.
  (rf/reg-sub :review/value {:doc "GLOBAL"} (fn [db _q] (:global db)))
  (install-frame! :review/frame-a (review-image :review/image-a "IMAGE A" 5))
  (install-frame! :review/frame-b (review-image :review/image-b "IMAGE B" 50))
  (is (= [5 50 6] [@(rf/subscribe value-q {:frame :review/frame-a})
                   @(rf/subscribe value-q {:frame :review/frame-b})
                   @(rf/subscribe local-q {:frame :review/frame-a})]))
  (let [expected (fn [doc value]
                   {:snapshot-kind :static :kind :static :doc doc
                    :inputs [[:sub base-q]] :derived 42 :value value})]
    (is (= [(expected "IMAGE A" 5) (expected "IMAGE B" 50) (expected "IMAGE A" 5)]
           [(inspected :review/frame-a)
            (inspected :review/frame-b)
            (rf.live-frame/call-with-frame-resolution :review/frame-b
              #(inspected :review/frame-a))])))
  ;; An image-only sub has no global metadata to fall back to, so an ambient
  ;; read would default it to :db. Scoped to this id: the shared :node-test
  ;; build loads every test namespace into one registrar.
  (is (= [false :static [base-q]]
         [(contains? (rf.registrar/registrations :sub) :review/image-only)
          (:input-kind (get (rf.subs.tooling/sub-cache-snapshot :review/frame-a) local-q))
          (:realized-inputs (get (rf.subs.tooling/sub-cache-snapshot :review/frame-a) local-q))])))
