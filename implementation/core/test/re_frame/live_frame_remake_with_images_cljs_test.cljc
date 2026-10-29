(ns re-frame.live-frame-remake-with-images-cljs-test
  "A frame made without `:images` resolves the whole store live, so its cached
  subscriptions were built against no generation. Re-made under the same id
  with `:images`, it serves only what the new generation selects: a cached sub
  the image does not select is evicted and then reports
  `:rf.error/no-such-sub`, exactly as on a frame made with that image from the
  start (Spec 002 §Duplicate id — idempotent replacement, §Image resolution and
  composition). A re-make that stays live leaves the cache alone.

  `.cljc`, posture-independent: runs under `clojure -M:test`, the production
  gate and `npm run test:cljs`."
  (:require #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.frame :as rf.frame]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- reg-subs!
  "`:remake/alpha` from namespace remake.alpha, `:remake/beta` from remake.beta."
  []
  (rf/reg-sub :remake/alpha {:ns 'remake.alpha} (fn [_db _q] :alpha-body))
  (rf/reg-sub :remake/beta  {:ns 'remake.beta}  (fn [_db _q] :beta-body)))

(def ^:private alpha-image
  (rf/image {:id :remake/alpha-image :select-ns {:include ["remake.alpha"]}}))

(def ^:private both-image
  (rf/image {:id :remake/both-image :select-ns {:include ["remake.alpha" "remake.beta"]}}))

(defn- read-sub [frame-id query-v]
  @(rf/subscribe query-v {:frame frame-id}))

(defn- cached? [frame-id query-v]
  (contains? @(:sub-cache (rf.frame/frame frame-id)) query-v))

(defn- no-such-sub-queries
  "Call `f`, and return the query vectors it reported as `:rf.error/no-such-sub`."
  [f]
  (let [seen (atom [])]
    (rf.error-emit/register-error-listener! ::recorder #(swap! seen conj %))
    (try
      (f)
      (finally
        (rf.error-emit/unregister-error-listener! ::recorder)))
    (into [] (comp (filter #(= :rf.error/no-such-sub (:rf.error/id %))) (map :event)) @seen)))

(deftest a-live-frame-re-made-with-images-stops-serving-a-sub-its-image-does-not-select
  (reg-subs!)
  (rf/make-frame {:id :remake/app})
  (let [before (read-sub :remake/app [:remake/beta])]
    (rf/make-frame {:id :remake/app :images [alpha-image]})
    (let [evicted? (not (cached? :remake/app [:remake/beta]))
          missing  (no-such-sub-queries #(read-sub :remake/app [:remake/beta]))]
      (is (= [:beta-body true [[:remake/beta]] :alpha-body]
             [before evicted? missing (read-sub :remake/app [:remake/alpha])])))))

(deftest a-frame-made-with-the-image-from-the-start-reports-no-such-sub
  (reg-subs!)
  (rf/make-frame {:id :remake/fresh :images [alpha-image]})
  (is (= [[:remake/beta]]
         (no-such-sub-queries #(read-sub :remake/fresh [:remake/beta])))))

(deftest an-image-to-image-re-make-evicts-a-sub-the-new-image-drops
  (reg-subs!)
  (rf/make-frame {:id :remake/img :images [both-image]})
  (let [before (read-sub :remake/img [:remake/beta])]
    (rf/make-frame {:id :remake/img :images [alpha-image]})
    (is (= [:beta-body false [[:remake/beta]]]
           [before
            (cached? :remake/img [:remake/beta])
            (no-such-sub-queries #(read-sub :remake/img [:remake/beta]))]))))

(deftest a-live-frame-re-made-live-keeps-its-cached-reactions
  (reg-subs!)
  (rf/make-frame {:id :remake/live})
  (let [held (rf/subscribe [:remake/beta] {:frame :remake/live})]
    (rf/make-frame {:id :remake/live})
    (is (identical? held (rf/subscribe [:remake/beta] {:frame :remake/live})))))
