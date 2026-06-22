(ns re-frame.facade-frame-read-cljs-test
  "EP-0023 (rf2-wkw8na) — the FACADE frame-image-generation READ API.

  The EP-0023 model promised a public READ over a frame's resolved image
  generation (\"target frame -> resolved image generation -> registration
  resolution\"), but the graduated facade exported only the constructors /
  mutators (`rf/image`, `rf/make-frame`, `rf/reload-images!`). This errata-class
  forward-extension ships the read:

    * a `{:frame f :kind k …}` map ARITY on each of the three existing public
      registrar reads — `rf/registrations` / `rf/handler-meta` / `rf/handler-ids`
      — resolving the (kind, id) set through the target frame's OWN sealed image
      generation (NOT the realm/default registrar);
    * a dedicated raw read `rf/frame-generation` returning the sealed generation
      map with the documented stable public keys (:rf.gen/resolver,
      :rf.gen/images, :rf.gen/kinds).

  Pins the bead's enumerated coverage:

    * arity resolution through a 2-image / SAME-ID frame pair — two frames
      running different images each resolve the same `[kind id]` to their OWN
      image's descriptor (with the descriptor's `:rf.provenance/ns` carried);
    * `:frame` accepts a registered frame ID and a direct frame OBJECT alike;
    * `frame-generation` returns the four `:rf.gen/*` keys;
    * the fail-loud cases — an unresolvable `:frame`
      (`:rf.error/frame-no-generation`, NO default fallback) and a map WITHOUT
      `:frame` (`:rf.error/registrar-query-needs-frame`, rf2-10nggz — a map is
      ALWAYS a frame-targeted read);
    * the existing keyword arity stays BYTE-IDENTICAL (no regression — a caller
      that never spells a map reaches the unchanged default source-store path).

  Each fail-loud assertion checks the `:rf.error/id` discriminator, NEVER the
  message bytes (Spec 009 §The thrown-error shape rule 3).

  The arity tests resolve against an explicit synthetic descriptor pool (the
  `make-frame` 2-arity `(opts descriptors)`), so there is no live source-store
  wiring — the same decoupling idiom `live-frame-cljs-test` /
  `live-frame-reload-cljs-test` use. `.cljc` ends `-cljs-test` so it rides
  `npm run test:cljs` AND `clojure -M:test`."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core           :as rf]
            [re-frame.image          :as image]
            [re-frame.image-assembly :as asm]
            [re-frame.live-frame     :as lf]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support   :as ts]))

;; ---------------------------------------------------------------------------
;; Fixture — clear the framework-standard registry per case, alongside the
;; runtime fixture (registrar snapshot/restore + plain-atom adapter, which
;; `make-frame`'s backing runnable record needs; the fixture also resets
;; `frame/frames`, so image-loaded frame records do not leak across cases).
;; EP-0024 (rf2-tu2vr7): the second live-frame registry dissolved into the ONE
;; `frames` registry, so the runtime fixture's `(reset! frame/frames {})` clears
;; every record AND its generation — no separate live-frame index to clear
;; (rf2-ji3tvy). Mirrors live_frame_reload_cljs_test.
;; ---------------------------------------------------------------------------

(use-fixtures :each
  (ts/make-reset-runtime-fixture {:adapter plain-atom/adapter})
  (fn [t]
    (asm/clear-standards!)
    (t)
    (asm/clear-standards!)))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- reg-desc
  "A synthetic REGISTERED descriptor authored in `provenance-ns` (mirrors the
  source-store output shape the selector consumes)."
  [provenance-ns kind id impl]
  {:rf.provenance/ns provenance-ns
   :kind             kind
   :id               id
   :handler-fn       impl})

(defn- err-id
  "The `:rf.error/id` discriminator of a thrown re-frame2 error, or nil."
  [thunk]
  (try (thunk) nil
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
         (:rf.error/id (ex-data e)))))

(defn- public-id?
  "True when `id` is a PUBLIC frame id (not a no-id frame's private
  `:rf.frame/<gensym>` id). EP-0024 (rf2-tu2vr7): the registries collapsed; a
  no-id frame's record is keyed under a private `:rf.frame/<gensym>` id and is
  EXCLUDED from `live-frame-ids` (which enumerates only public image-loaded
  frame ids), so a no-id frame contributes no PUBLIC id; assert that by filtering
  the reserved namespace."
  [id]
  (not= "rf.frame" (namespace id)))

;; Two images selecting two DISJOINT namespaces, each authoring the SAME
;; `[:event :counter/inc]` + `[:sub :counter/value]` ids with DIFFERENT impls
;; and DIFFERENT provenance. That is the bead's headline case: two frames
;; running different images each resolve the SAME [kind id] to their OWN image's
;; descriptor (with provenance).
(def ^:private blue-pool
  [(reg-desc "blue.core" :event :counter/inc   ::blue-inc)
   (reg-desc "blue.core" :sub   :counter/value ::blue-value)])

(def ^:private green-pool
  [(reg-desc "green.core" :event :counter/inc   ::green-inc)
   (reg-desc "green.core" :sub   :counter/value ::green-value)
   (reg-desc "green.core" :event :counter/reset ::green-reset)]) ;; green-only id

(def ^:private blue-img  (image/image {:id :blue/img  :select-ns {:include ["blue.core"]}}))
(def ^:private green-img (image/image {:id :green/img :select-ns {:include ["green.core"]}}))

;; ===========================================================================
;; 1. :frame arity resolves through the TARGET frame's OWN image generation —
;;    the 2-image / same-id pair, each resolving its OWN descriptor + provenance
;; ===========================================================================

(deftest frame-arity-resolves-through-the-targets-own-generation
  (testing "two frames running DIFFERENT images each resolve the same [kind id]
            through their OWN image's generation (EP-0023 §Frame-derived live
            registration resolution) — handler-meta carries the per-frame impl +
            its :rf.provenance/ns"
    (let [_blue  (rf/make-frame {:id :blue/main  :images [blue-img]}  blue-pool)
          _green (rf/make-frame {:id :green/main :images [green-img]} green-pool)]
      (testing "handler-meta {:frame …} resolves the BLUE frame's :counter/inc"
        (let [m (rf/handler-meta {:frame :blue/main :kind :event :id :counter/inc})]
          (is (= ::blue-inc (:handler-fn m)))
          (is (= "blue.core" (:rf.provenance/ns m))
              "the BLUE descriptor's provenance is surfaced")))
      (testing "the SAME query against the GREEN frame resolves GREEN's descriptor"
        (let [m (rf/handler-meta {:frame :green/main :kind :event :id :counter/inc})]
          (is (= ::green-inc (:handler-fn m)))
          (is (= "green.core" (:rf.provenance/ns m)))))
      (testing "an id the frame's image does NOT carry resolves to nil through the
                frame form (the green-only :counter/reset is absent in blue)"
        (is (nil? (rf/handler-meta {:frame :blue/main :kind :event :id :counter/reset})))
        (is (some? (rf/handler-meta {:frame :green/main :kind :event :id :counter/reset})))))))

(deftest registrations-frame-arity-projects-only-the-frames-ids
  (testing "registrations {:frame …} returns only the {id metadata} the frame's
            OWN image carries for the kind — each frame sees its own universe"
    (let [_blue  (rf/make-frame {:id :blue/main  :images [blue-img]}  blue-pool)
          _green (rf/make-frame {:id :green/main :images [green-img]} green-pool)
          blue-events  (rf/registrations {:frame :blue/main  :kind :event})
          green-events (rf/registrations {:frame :green/main :kind :event})]
      (testing "blue's :event registrations: only :counter/inc, with blue's impl"
        (is (= #{:counter/inc} (set (keys blue-events))))
        (is (= ::blue-inc (:handler-fn (get blue-events :counter/inc)))))
      (testing "green's :event registrations include the green-only :counter/reset"
        (is (= #{:counter/inc :counter/reset} (set (keys green-events))))
        (is (= ::green-inc (:handler-fn (get green-events :counter/inc)))))
      (testing "a :pred narrows the frame-targeted result by metadata"
        (let [only-reset (rf/registrations {:frame :green/main
                                            :kind  :event
                                            :pred  #(= :counter/reset (:id %))})]
          (is (= #{:counter/reset} (set (keys only-reset)))))))))

(deftest handler-ids-frame-arity-enumerates-only-the-frames-ids
  (testing "handler-ids {:frame …} returns the id SET under the kind resolved
            through the frame's OWN generation"
    (let [_blue  (rf/make-frame {:id :blue/main  :images [blue-img]}  blue-pool)
          _green (rf/make-frame {:id :green/main :images [green-img]} green-pool)]
      (is (= #{:counter/inc}              (rf/handler-ids {:frame :blue/main  :kind :event})))
      (is (= #{:counter/inc :counter/reset} (rf/handler-ids {:frame :green/main :kind :event})))
      (is (= #{:counter/value}            (rf/handler-ids {:frame :blue/main  :kind :sub}))))))

;; ===========================================================================
;; 2. :frame accepts a DIRECT frame OBJECT, not only a registered id
;; ===========================================================================

(deftest frame-arity-accepts-a-direct-frame-object
  (testing ":frame is EITHER a registered frame id OR a direct frame object (the
            same target shapes rf/reload-images! accepts) — a no-id direct object
            resolves through its own generation"
    (let [blue-obj (rf/make-frame {:images [blue-img]} blue-pool)]
      (is (not-any? public-id? (lf/live-frame-ids))
          "a no-id frame contributes no PUBLIC id (its record is keyed under a
           private :rf.frame/<gensym> id, which DOES carry a generation and so
           appears in live-frame-ids — EP-0024)")
      (testing "handler-meta via the OBJECT resolves blue's descriptor"
        (is (= ::blue-inc (:handler-fn (rf/handler-meta {:frame blue-obj
                                                         :kind  :event
                                                         :id    :counter/inc})))))
      (testing "registrations + handler-ids via the OBJECT project blue's ids"
        (is (= #{:counter/inc} (set (keys (rf/registrations {:frame blue-obj :kind :event})))))
        (is (= #{:counter/inc} (rf/handler-ids {:frame blue-obj :kind :event}))))
      (testing "frame-generation via the OBJECT returns blue's sealed generation"
        (is (= ::blue-inc (:handler-fn (asm/resolve-descriptor
                                         (rf/frame-generation blue-obj)
                                         :event :counter/inc))))))))

;; ===========================================================================
;; 3. frame-generation returns the sealed generation (the four :rf.gen/* keys)
;; ===========================================================================

(deftest frame-generation-returns-the-sealed-generation-key-shape
  (testing "frame-generation returns the sealed image generation map with the
            documented stable public keys (EP-0023 §Specification Summary;
            EP-0026 retired :rf.gen/requires with the image-capability feature)"
    (let [_blue (rf/make-frame {:id :blue/main :images [blue-img]} blue-pool)
          gen   (rf/frame-generation :blue/main)]
      (testing "the :rf.gen/* keys are present"
        (is (contains? gen :rf.gen/resolver))
        (is (contains? gen :rf.gen/images))
        (is (contains? gen :rf.gen/kinds)))
      (testing "the retired :rf.gen/requires key is absent (EP-0026)"
        (is (not (contains? gen :rf.gen/requires))))
      (testing "the resolver is the id-disjoint [kind id] -> descriptor map"
        (is (= ::blue-inc (:handler-fn (get (:rf.gen/resolver gen) [:event :counter/inc])))))
      (testing ":rf.gen/kinds names the kinds the frame's image carries"
        (is (contains? (:rf.gen/kinds gen) :event))
        (is (contains? (:rf.gen/kinds gen) :sub)))
      (testing "it equals the generation the live frame is running (verbatim)"
        (is (= gen (lf/frame-generation (lf/live-frame :blue/main))))))))

(deftest frame-generation-accepts-a-registered-id-and-a-direct-object
  (testing "frame-generation resolves a registered id AND a direct object to the
            same target's generation"
    (let [blue-id-frame (rf/make-frame {:id :blue/main :images [blue-img]} blue-pool)
          green-obj     (rf/make-frame {:images [green-img]} green-pool)]
      (is (= (lf/frame-generation blue-id-frame) (rf/frame-generation :blue/main)))
      (is (= (lf/frame-generation green-obj)      (rf/frame-generation green-obj))))))

;; ===========================================================================
;; 4. FAIL-LOUD — both ruled cases
;; ===========================================================================

(deftest frame-target-not-resolving-fails-loud
  (testing "a :frame that does not resolve to a live frame generation fails loud
            (:rf.error/frame-no-generation) on EVERY read — NO fallback to the
            default/realm registrar"
    ;; A registered id that names no live frame.
    (is (= :rf.error/frame-no-generation
           (err-id #(rf/handler-meta {:frame :nope/missing :kind :event :id :counter/inc}))))
    (is (= :rf.error/frame-no-generation
           (err-id #(rf/registrations {:frame :nope/missing :kind :event}))))
    (is (= :rf.error/frame-no-generation
           (err-id #(rf/handler-ids {:frame :nope/missing :kind :event}))))
    (is (= :rf.error/frame-no-generation
           (err-id #(rf/frame-generation :nope/missing))))
    ;; A non-frame value that is neither a frame object nor a registered id.
    (is (= :rf.error/frame-no-generation
           (err-id #(rf/frame-generation "not-a-frame"))))
    ;; A bare map with no generation slot is NOT a frame object → fail loud, no
    ;; silent default-registrar fallback.
    (is (= :rf.error/frame-no-generation
           (err-id #(rf/handler-ids {:frame {:totally :fake} :kind :event}))))))

(deftest map-without-frame-fails-loud
  (testing "a registrar-query map WITHOUT :frame fails loud
            (:rf.error/registrar-query-needs-frame, rf2-10nggz) — post-EP-0023 a
            map is ALWAYS a frame-targeted read; the retired :realm / absence-as-
            default spellings are removed"
    (rf/reg-event :ff/inc {:doc "inc"} (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
    (is (= :rf.error/registrar-query-needs-frame
           (err-id #(rf/registrations {:kind :event}))))
    (is (= :rf.error/registrar-query-needs-frame
           (err-id #(rf/handler-meta {:kind :event :id :ff/inc}))))
    (is (= :rf.error/registrar-query-needs-frame
           (err-id #(rf/handler-ids {:kind :event}))))
    ;; the retired :realm spelling is now the same missing-:frame error
    (is (= :rf.error/registrar-query-needs-frame
           (err-id #(rf/registrations {:realm nil :kind :event}))))))

;; ===========================================================================
;; 5. NO REGRESSION — the existing keyword arity stays byte-identical
;; ===========================================================================

(deftest keyword-arity-stays-byte-identical
  (testing "a caller that never spells a map reaches the UNCHANGED default
            source-store path — the frame-arity addition is purely additive"
    (rf/reg-event :ff/inc {:doc "inc"} (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
    (rf/reg-sub :ff/n {:doc "n"} (fn [db _] (:n db)))
    (testing "(registrations kind) — the default source-store keyword arity is unchanged"
      (is (contains? (rf/registrations :event) :ff/inc)))
    (testing "(handler-meta kind id) — unchanged"
      (is (some? (rf/handler-meta :event :ff/inc))))
    (testing "(handler-ids kind) — unchanged"
      (is (contains? (rf/handler-ids :event) :ff/inc)))))
