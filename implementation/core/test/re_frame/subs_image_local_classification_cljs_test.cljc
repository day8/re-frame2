(ns re-frame.subs-image-local-classification-cljs-test
  "A subscription's `:rf.sub/run` trace projects observability from the EXACT
  registration classification captured for THAT reaction, not a later or global
  re-resolution.

  The reactive recompute carries its reaction's classification declaration
  through the `:rf.sub/run` trace chokepoint under an internal
  `:rf.sub/classification` slot, and `project-sub-tags` redacts from it rather
  than re-resolving `classification-when :sub sub-id` through the ambient
  registrar, which after the frame-generation binding unwinds, or across an
  HMR or incarnation replacement, sees no image metadata or a CONFLICTING
  same-id global registration.

  The chokepoint fixtures drive `rf.classification/project-trace-event`
  directly with a handed carrier. The `image-local` deftests close the gap
  that leaves: they assemble a real inline `:reg-sub` through `rf.image/image`
  and `rf.live-frame/make-frame`, install it on a live frame's resolved
  generation, and read the Xray-facing `:trace` listener stream.

  `.cljc` — runs under both `clojure -M:test` (JVM) and `npm run test:cljs`."
  (:require #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.classification :as rf.classification]
            [re-frame.elision :as rf.elision]
            [re-frame.image :as rf.image]
            [re-frame.live-frame :as rf.live-frame]
            [re-frame.privacy :as rf.privacy]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(def ^:private redacted rf.privacy/redacted-sentinel)

;; ---------------------------------------------------------------------------
;; The chokepoint — project-trace-event on :rf.sub/run (pure data)
;; ---------------------------------------------------------------------------

(defn- project-sub-run
  "Project a `:rf.sub/run` envelope with the given tags and return the projected
  tags. `:frame` defaults to :rf/default so the projector does not fail-closed."
  [tags]
  (-> (rf.classification/project-trace-event
        {:operation :rf.sub/run :op-type :rf.sub
         :tags (merge {:frame :rf/default} tags)})
      :tags))

(deftest captured-sensitive-declaration-redacts-value-and-prev-value
  ;; With no global registration, only the declared path redacts, and the
  ;; internal carrier is stripped.
  (is (= {:rf.sub/value {:token redacted :public 1} :rf.sub/prev-value {:token redacted :public 0}}
         (select-keys (project-sub-run {:rf.sub/id             :img/secret
                                        :rf.sub/value          {:token "SECRET" :public 1}
                                        :rf.sub/prev-value     {:token "OLD" :public 0}
                                        :rf.sub/classification {:sensitive [[:token]]}})
                      [:rf.sub/value :rf.sub/prev-value :rf.sub/classification]))))

(deftest captured-large-declaration-marks-value
  (let [out (project-sub-run {:rf.sub/id             :img/big
                              :rf.sub/value          {:blob (vec (range 100))}
                              :rf.sub/classification {:large [[:blob]]}})]
    (is (= [true false] [(rf.elision/marker? (get-in out [:rf.sub/value :blob]))
                         (contains? out :rf.sub/classification)]))))

(deftest conflicting-global-cannot-supply-or-override-captured-classification
  ;; Presence is authoritative both ways: a captured :sensitive redacts over an
  ;; unclassified global, and a captured EMPTY declaration rides raw over a
  ;; sensitive one.
  (rf/reg-sub :dup/plain (fn [db _] db))
  (rf/reg-sub :dup/sensitive {:sensitive [[:token]]} (fn [db _] db))
  (is (= [redacted "SECRET"]
         (mapv (fn [[id carrier]]
                 (get-in (project-sub-run {:rf.sub/id             id
                                           :rf.sub/value          {:token "SECRET"}
                                           :rf.sub/classification carrier})
                         [:rf.sub/value :token]))
               [[:dup/plain {:sensitive [[:token]]}] [:dup/sensitive {}]]))))

(deftest absent-carrier-falls-back-to-registrar-resolution
  ;; A non-memo path hands no carrier and still redacts via the registrar.
  (rf/reg-sub :fallback/s {:sensitive [[:token]]} (fn [db _] db))
  (is (= redacted (get-in (project-sub-run {:rf.sub/id :fallback/s :rf.sub/value {:token "SECRET"}})
                          [:rf.sub/value :token]))))

;; ===========================================================================
;; Real image-local assembly and generation replacement
;; ===========================================================================

(defn- inline-sub-image
  "An image whose ONLY registration is an inline layer-1 `:reg-sub` under
  `:img/read` carrying `classification` and returning the constant `value`,
  normalized and lowered by real image assembly when the frame's generation
  is sealed."
  [image-id classification value]
  (rf.image/image {:id            image-id
                   :registrations {:reg-sub [[:img/read classification (fn [_db _q] value)]]}}))

(defn- install-image-frame!
  "Seal `frame-id`'s generation from `image`. The empty descriptor pool keeps
  the generation to the image's own inline registrations."
  [frame-id image]
  (rf.live-frame/make-frame {:id frame-id :images [image]} []))

(defn- register-conflicting-global! []
  ;; SAME id, NO classification: a fallback to it would leak the value raw.
  (rf/reg-sub :img/read (fn [_db _q] {:token "GLOBAL" :public "GLOBAL"})))

(defn- traced-read
  "Deref `[:img/read]` in `frame-id` under a `:trace` listener. Returns
  `[subscriber-value last-projected-run-tags]` for that frame."
  [frame-id]
  (let [recorded (atom [])]
    (rf/register-listener! :trace ::image-local (fn [ev] (swap! recorded conj ev)))
    (try
      (let [v @(rf/subscribe [:img/read] {:frame frame-id})]
        [v (->> @recorded
                (filter #(and (= :rf.sub/run (:operation %))
                              (= :img/read (get-in % [:tags :rf.sub/id]))
                              (= frame-id (get-in % [:tags :frame]))))
                last
                :tags)])
      (finally (rf/unregister-listener! :trace ::image-local)))))

(deftest second-frame-same-id-resolves-its-own-image-local-classification
  ;; Two frames install the SAME inline id over a conflicting global with
  ;; DIFFERENT declarations. The subscriber derefs the raw value; each frame's
  ;; evidence redacts per its OWN declaration, with no bleed.
  (register-conflicting-global!)
  (install-image-frame! :img/frame-a
    (inline-sub-image :img/a {:sensitive [[:token]]} {:token "A-SECRET" :public "A-pub"}))
  (install-image-frame! :img/frame-b
    (inline-sub-image :img/b {:sensitive [[:public]]} {:token "B-tok" :public "B-SECRET"}))
  (is (= [[{:token "A-SECRET" :public "A-pub"} {:token redacted :public "A-pub"}]
          [{:token "B-tok" :public "B-SECRET"} {:token "B-tok" :public redacted}]]
         (mapv #(let [[v tags] (traced-read %)] [v (:rf.sub/value tags)])
               [:img/frame-a :img/frame-b]))))

(deftest replacing-frame-a-generation-updates-evidence-without-old-or-global-leak
  ;; The new generation's declaration replaces the old one's rather than
  ;; adding to it, and the conflicting global supplies none.
  (register-conflicting-global!)
  (install-image-frame! :img/frame-a
    (inline-sub-image :img/g1 {:sensitive [[:token]]} {:token "SECRET" :public "pub"}))
  (let [[_ g1] (traced-read :img/frame-a)]
    ;; Frame memory survives the swap; the clear forces a fresh reaction
    ;; against the new generation, as an HMR sub reload does.
    (install-image-frame! :img/frame-a
      (inline-sub-image :img/g2 {:sensitive [[:public]]} {:token "tok2" :public "SECRET2"}))
    (rf/clear-sub-cache! :img/frame-a)
    (let [[_ g2] (traced-read :img/frame-a)]
      (is (= [{:token redacted :public "pub"} {:rf.sub/value {:token "tok2" :public redacted}}]
             [(:rf.sub/value g1) (select-keys g2 [:rf.sub/value :rf.sub/classification])])))))
