(ns re-frame.subs-tooling-inspected-frame-metadata-cljs-test
  "rf2-zimh — the two live subscription-cache readers must resolve registration
  METADATA through the frame they were asked about, not through whichever
  registrar generation happens to be ambient.

  `sub-cache-snapshot` and `sub-cache-algebra-view` both take a frame-id and read
  THAT frame's cached reactions, then join each entry against
  `rf.registrar/registrations :sub` for `:input-kind`, `:doc`, `:schema`,
  `:derive` and the source coordinates. That read is generation-routed, and
  neither reader supplied the target generation — so the values came from frame A
  while the metadata came from the global pool, or from whatever frame the
  INSPECTOR was rendering in.

  The result is internally inconsistent evidence rather than a wrong app value:
  an image-local sub with declared `:inputs` is reported `:input-kind :db`, and
  the algebra view attaches a conflicting same-id global's doc and handler to the
  inspected frame's live node. Correct values cannot vouch for it, because
  `subscribe` establishes the target generation on its own path.

  Xray's derivation-graph contributor and the Pair preload's `sub-cache-info`
  both call these readers from OUTSIDE the frame they are inspecting, which is
  why the target has to be passed rather than inherited — the third deftest
  drives exactly that shape, from inside a different frame's generation binding.

  CLJS-only: both readers are `#?(:cljs …)`-bodied and return nil on the JVM."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
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
  "An image whose inline registrations are a layer-1 `:review/base` reader and a
  DECLARED-INPUT `:review/value` over it, both carrying `doc`. `:review/value`'s
  `:inputs` is what makes its `:input-kind` `:static` — the discriminator the
  global registration below deliberately contradicts.

  `:review/image-only` exists ONLY here: nothing registers it globally, so a
  reader that consults the global pool finds no metadata at all and falls back
  to the `:db` default."
  [image-id doc base-value]
  (rf.image/image
    {:id            image-id
     :registrations
     {:reg-sub [[:review/base {:doc doc} (fn [_db _q] base-value)]
                [:review/value {:inputs [base-q] :doc doc} (fn [[n] _q] n)]
                [:review/image-only {:inputs [base-q] :doc doc} (fn [[n] _q] (inc n))]]}}))

(defn- install-frame! [frame-id image]
  (rf.live-frame/make-frame {:id frame-id :images [image]} []))

(defn- register-conflicting-global! []
  ;; SAME id, DIFFERENT everything: a layer-1 app-db reader with its own doc.
  ;; A reader that resolves metadata globally reports THIS for the image-local
  ;; subscription above.
  (rf/reg-sub :review/value {:doc "GLOBAL"} (fn [db _q] (:global db))))

(defn- snapshot-entry [frame-id query-v]
  (get (rf.subs.tooling/sub-cache-snapshot frame-id) query-v))

(defn- algebra-node [frame-id query-v]
  (get (rf.subs.tooling/sub-cache-algebra-view frame-id) query-v))

(deftest snapshot-reports-the-inspected-frames-input-kind-over-a-conflicting-global
  (testing "an image-local declared-input sub is reported :static with its
            realized edges, not :db from a same-id global app-db reader"
    (register-conflicting-global!)
    (install-frame! :review/frame-a (review-image :review/image-a "IMAGE A" 5))
    (is (= 5 @(rf/subscribe value-q {:frame :review/frame-a}))
        "precondition — the VALUE already comes from image A; only the metadata
         beside it was ambient")
    (let [entry (snapshot-entry :review/frame-a value-q)]
      (is (some? entry) "the frame's cache carries the subscription")
      (is (= :static (:input-kind entry))
          "the inspected frame's declared-input classification, not the global :db")
      (is (= [base-q] (:realized-inputs entry))
          "the realized edges still come from the cache entry")
      (is (= 5 (:value entry)) "the value is unchanged by the metadata fix"))))

(deftest snapshot-reports-an-image-only-sub-absent-from-the-global-pool
  (testing "a sub that exists ONLY in the image is classified from the image —
            the :db default is what an unresolved global lookup produces"
    (install-frame! :review/frame-a (review-image :review/image-a "IMAGE A" 5))
    (is (= 6 @(rf/subscribe local-q {:frame :review/frame-a})))
    (is (= {} (rf.registrar/registrations :sub))
        "control — the GLOBAL registrar pool is empty, so an ambient read finds
         nothing and would default every entry to :db")
    (let [entry (snapshot-entry :review/frame-a local-q)]
      (is (= :static (:input-kind entry))
          "the image-only sub is classified from the frame that owns it")
      (is (= [base-q] (:realized-inputs entry))))))

(deftest readers-resolve-the-explicit-target-from-inside-another-frames-generation
  (testing "two frames materialize the same sub id from DIFFERENT images; each is
            inspected correctly from no binding at all and from inside the
            other's generation binding — the shape Xray's contributor and the
            Pair preload are in, since neither renders inside its target"
    (register-conflicting-global!)
    (install-frame! :review/frame-a (review-image :review/image-a "IMAGE A" 5))
    (install-frame! :review/frame-b (review-image :review/image-b "IMAGE B" 50))
    (is (= 5  @(rf/subscribe value-q {:frame :review/frame-a})))
    (is (= 50 @(rf/subscribe value-q {:frame :review/frame-b})))
    (testing "outside any generation binding"
      (is (= :static (:input-kind (snapshot-entry :review/frame-a value-q))))
      (is (= :static (:input-kind (snapshot-entry :review/frame-b value-q))))
      (is (= "IMAGE A" (:doc (algebra-node :review/frame-a value-q)))
          "A's node carries A's declaration, not the global's")
      (is (= "IMAGE B" (:doc (algebra-node :review/frame-b value-q)))
          "B's node carries B's declaration"))
    (testing "from INSIDE frame B's generation binding — the explicit target wins"
      (rf.live-frame/call-with-frame-resolution :review/frame-b
        (fn []
          (is (= "IMAGE A" (:doc (algebra-node :review/frame-a value-q)))
              "inspecting A from within B still reports A's declaration")
          (is (= :static (:input-kind (snapshot-entry :review/frame-a value-q)))
              "and A's classification"))))))

(deftest algebra-view-attaches-the-inspected-frames-derivation
  (testing "the algebra view's :derive / :doc / :inputs describe the derivation
            the inspected frame actually RUNS, not a same-id global's"
    (register-conflicting-global!)
    (install-frame! :review/frame-a (review-image :review/image-a "IMAGE A" 5))
    (is (= 5 @(rf/subscribe value-q {:frame :review/frame-a})))
    (let [node (algebra-node :review/frame-a value-q)]
      (is (some? node))
      (is (= "IMAGE A" (:doc node)) "the image-local doc, not \"GLOBAL\"")
      (is (= :static (:input-kind node)))
      (is (= [base-q] (:inputs node))
          "the declared edges of the derivation this frame runs")
      (is (= 5 (:value node)) "the value is unchanged"))))

(deftest missing-frame-still-returns-nil
  (testing "the nil contract for a missing/destroyed frame is preserved — the
            resolution seam binds nothing for a target it cannot resolve"
    (is (nil? (rf.subs.tooling/sub-cache-snapshot :review/no-such-frame)))
    (is (nil? (rf.subs.tooling/sub-cache-algebra-view :review/no-such-frame)))))
