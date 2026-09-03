(ns re-frame.subs-image-local-classification-cljs-test
  "rf2-vxgfnd.220 — a subscription's `:rf.sub/run` trace projects observability
  from the EXACT registration classification captured for THAT reaction, not a
  later/global re-resolution.

  The reactive recompute already captures the authoritative image-local / global
  `sub-meta` for schema validation; it now also carries that reaction's
  classification declaration through the `:rf.sub/run` trace chokepoint under an
  internal `:rf.sub/classification` slot. `project-sub-tags` redacts from the
  carried declaration instead of re-resolving `classification-when :sub sub-id`
  through the ambient registrar — which after the frame-generation binding
  unwinds (one-shot deref) or across HMR/incarnation replacement (an ongoing
  reaction) sees no image metadata or a CONFLICTING same-id global registration.

  Focused chokepoint fixtures drive `rf.classification/project-trace-event` on
  `:rf.sub/run` events directly (pure data — the fixtures a mutation restoring
  ambient re-resolution must fail); an end-to-end leg subscribes to a real
  sensitive sub and proves the subscriber reads RAW while the projected trace
  redacts, across an ongoing recompute.

  rf2-vxgfnd.258 — the chokepoint fixtures HAND a `:rf.sub/classification` map
  to the projector, so they stay green even if REAL image-local resolution fell
  back to the global/nil. The `image-local-*` deftests below close that gap:
  they ASSEMBLE a real inline `:reg-sub` (`rf.image/image` → `rf.live-frame/make-frame` →
  `image-assembly/lower-inline-descriptors`, the rf2-vxgfnd.219/.257 path),
  install it on a LIVE frame carrying a resolved image GENERATION, and observe
  the Xray-facing `:trace` listener stream. They prove the image-local
  `:sensitive` declaration is the one that redacts even against a conflicting
  same-id GLOBAL, a SECOND frame with the same id resolves its OWN declaration,
  and REPLACING frame A's generation reflects the NEW declaration without leaking
  the old generation or the global. Mutating image-local sub-meta resolution to
  nil/global (the projector re-resolving through the ambient registrar) makes
  these fail on CLJ and CLJS.

  `.cljc` — runs under both `clojure -M:test` (JVM) and `npm run test:cljs`."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.classification :as rf.classification]
            [re-frame.elision :as rf.elision]
            [re-frame.image :as rf.image]
            [re-frame.live-frame :as rf.live-frame]
            [re-frame.privacy :as rf.privacy]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

;; The reset fixture gives each test a clean, isolated runtime (a pinned
;; :rf/default frame bound as the ambient scope, with registrar snapshot/restore
;; so sibling namespaces in the shared :node-test bundle survive).
(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

;; ---------------------------------------------------------------------------
;; Focused chokepoint — project-trace-event on :rf.sub/run (pure data)
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
  (testing "an image-local sub's captured :sensitive declaration redacts
            :rf.sub/value and :rf.sub/prev-value — with NO global registration"
    (let [out (project-sub-run
                {:rf.sub/id :img/secret
                 :rf.sub/value      {:token "SECRET" :public 1}
                 :rf.sub/prev-value {:token "OLD"    :public 0}
                 :rf.sub/classification {:sensitive [[:token]]}})]
      (is (= rf.privacy/redacted-sentinel (get-in out [:rf.sub/value :token])))
      (is (= 1 (get-in out [:rf.sub/value :public])) "unclassified sibling rides raw")
      (is (= rf.privacy/redacted-sentinel (get-in out [:rf.sub/prev-value :token])))
      ;; EP-0025: a path-classified sub redacts only its declared paths — there
      ;; is no whole-output :sensitive? stamp (no sub-output propagation). The
      ;; captured path redacts exactly as the registrar path would.
      (is (not (contains? out :rf.sub/classification))
          "the internal carrier is stripped — it never egresses"))))

(deftest captured-large-declaration-marks-value
  (testing "a captured :large declaration emits the canonical large marker"
    (let [out (project-sub-run
                {:rf.sub/id :img/big
                 :rf.sub/value {:blob (vec (range 100))}
                 :rf.sub/classification {:large [[:blob]]}})]
      (is (rf.elision/marker? (get-in out [:rf.sub/value :blob])))
      (is (not (contains? out :rf.sub/classification))))))

(deftest conflicting-global-cannot-supply-or-override-captured-classification
  (testing "a same-id GLOBAL registration with no classification cannot make a
            captured-sensitive sub leak"
    (rf/reg-sub :dup/id (fn [db _] db))                 ;; global: no classification
    (let [out (project-sub-run {:rf.sub/id :dup/id
                                :rf.sub/value {:token "SECRET"}
                                :rf.sub/classification {:sensitive [[:token]]}})]
      (is (= rf.privacy/redacted-sentinel (get-in out [:rf.sub/value :token]))
          "captured classification wins over the (unclassified) global")))
  (testing "a captured EMPTY declaration wins over a conflicting SENSITIVE global
            — presence is authoritative, so the value rides raw"
    (rf/reg-sub :dup/id2 {:sensitive [[:token]]} (fn [db _] db))  ;; global: sensitive
    (let [out (project-sub-run {:rf.sub/id :dup/id2
                                :rf.sub/value {:token "SECRET"}
                                :rf.sub/classification {}})]      ;; captured: none
      (is (= "SECRET" (get-in out [:rf.sub/value :token]))
          "the captured (no-classification) declaration is authoritative"))))

(deftest same-sub-id-different-captured-classifications-stay-isolated
  (testing "two reactions sharing a sub id but capturing different declarations
            (two live frames / an HMR successor) project independently"
    (let [frame-a (project-sub-run {:rf.sub/id :shared/id
                                    :rf.sub/value {:token "A"}
                                    :rf.sub/classification {:sensitive [[:token]]}})
          frame-b (project-sub-run {:rf.sub/id :shared/id
                                    :rf.sub/value {:token "B"}
                                    :rf.sub/classification {}})]
      (is (= rf.privacy/redacted-sentinel (get-in frame-a [:rf.sub/value :token])))
      (is (= "B" (get-in frame-b [:rf.sub/value :token]))))))

(deftest absent-carrier-falls-back-to-registrar-resolution
  (testing "a :rf.sub/run trace with NO captured carrier (a non-memo path) still
            redacts via the registrar — the fallback is preserved"
    (rf/reg-sub :fallback/s {:sensitive [[:token]]} (fn [db _] db))
    (let [out (project-sub-run {:rf.sub/id :fallback/s
                                :rf.sub/value {:token "SECRET"}})]  ;; no carrier
      (is (= rf.privacy/redacted-sentinel (get-in out [:rf.sub/value :token]))))))

;; ---------------------------------------------------------------------------
;; End-to-end — a real sensitive sub: subscriber RAW, projected trace REDACTED
;; ---------------------------------------------------------------------------

(defn- captured-sub-runs [recorded sub-id]
  (filterv (fn [ev] (and (= :rf.sub/run (:operation ev))
                         (= sub-id (get-in ev [:tags :rf.sub/id]))))
           @recorded))

(deftest subscriber-reads-raw-while-projected-sub-run-trace-redacts
  (rf/reg-event :sec/seed (fn [{:keys [db]} [_ v]] {:db (assoc db :token v)}))
  (rf/reg-sub :sec/read {:sensitive [[:token]]} (fn [db _] {:token (:token db)}))
  (let [recorded (atom [])]
    (rf/register-listener! :trace :sec-e2e (fn [ev] (swap! recorded conj ev)))
    (try
      (rf/dispatch-sync [:sec/seed "secret-1"])
      ;; ongoing reactive read #1 — a compute emits :rf.sub/run
      (is (= {:token "secret-1"} @(rf/subscribe [:sec/read]))
          "the subscriber derefs the RAW value")
      ;; ongoing reactive read #2 — value change drives a recompute
      (rf/dispatch-sync [:sec/seed "secret-2"])
      (is (= {:token "secret-2"} @(rf/subscribe [:sec/read]))
          "the recomputed subscriber value is still RAW")
      (let [runs (captured-sub-runs recorded :sec/read)]
        (is (seq runs) "at least one :rf.sub/run trace was captured")
        (doseq [ev runs]
          (is (= rf.privacy/redacted-sentinel
                 (get-in ev [:tags :rf.sub/value :token]))
              "every projected :rf.sub/run redacts the sensitive path")
          (is (not (contains? (:tags ev) :rf.sub/classification))
              "the internal carrier never reaches a listener")))
      (finally
        (rf/unregister-listener! :trace :sec-e2e)))))

;; ===========================================================================
;; rf2-vxgfnd.258 — REAL image-local assembly + generation replacement
;;
;; Not `project-trace-event` fed a fabricated classification: an inline
;; `:reg-sub` ASSEMBLED by `rf.image/image` → `rf.live-frame/make-frame` → image-assembly's
;; `lower-inline-descriptors`, installed on a live frame's resolved GENERATION,
;; observed through the Xray-facing `:trace` listener. If image-local sub-meta
;; resolution fell back to the global/nil, the projected value would ride RAW and
;; these fail.
;; ===========================================================================

(defn- inline-sub-image
  "An image whose ONLY registration is an inline layer-1 `:reg-sub` under `id`
  carrying `classification` metadata and returning the constant `value`. The
  inline descriptor is NORMALIZED + lowered by image assembly when the frame's
  generation is sealed (the REAL path — not a hand-built descriptor). The
  `rf.image/image` inline `:reg-sub` path stamps `:input-kind :db` for us."
  [image-id id classification value]
  (rf.image/image {:id            image-id
                :registrations {:reg-sub [[id classification (fn [_db _q] value)]]}}))

(defn- install-image-frame!
  "Create/update a runnable frame under `frame-id` and seal its generation from
  `image` — the REAL image-assembly path (`asm/assemble` → lower-inline-
  descriptors). The empty descriptor pool keeps the generation to the image's
  OWN inline registrations (no live source-store contamination). Returns the
  frame value."
  [frame-id image]
  (rf.live-frame/make-frame {:id frame-id :images [image]} []))

(defn- image-local-sub-runs
  "The projected `:tags` of every `:rf.sub/run` the `:trace` listener saw for
  `sub-id` in `frame-id` — the Xray-facing evidence, post-projection."
  [recorded sub-id frame-id]
  (into []
        (comp (filter (fn [ev] (and (= :rf.sub/run (:operation ev))
                                    (= sub-id  (get-in ev [:tags :rf.sub/id]))
                                    (= frame-id (get-in ev [:tags :frame])))))
              (map :tags))
        @recorded))

(defn- with-trace-listener
  "Register a `:trace` listener that accumulates into a fresh atom, run `(f
  recorded)`, and unregister in a finally."
  [f]
  (let [recorded (atom [])
        lid      ::image-local-e2e]
    (rf/register-listener! :trace lid (fn [ev] (swap! recorded conj ev)))
    (try (f recorded)
         (finally (rf/unregister-listener! :trace lid)))))

(deftest image-local-sensitive-redacts-over-conflicting-global-through-real-assembly
  (testing "an ASSEMBLED inline sub's :sensitive declaration redacts its
            :rf.sub/run evidence in the frame that owns it — even though a same-id
            GLOBAL registration declares NO classification (a fallback to the
            global/nil would leak the value RAW)"
    ;; Conflicting global: SAME id, NO classification.
    (rf/reg-sub :img/read (fn [_db _q] {:token "GLOBAL" :public 0}))
    (install-image-frame! :img/frame-a
      (inline-sub-image :img/a :img/read {:sensitive [[:token]]}
                        {:token "SECRET" :public 1}))
    (with-trace-listener
      (fn [recorded]
        (is (= {:token "SECRET" :public 1}
               @(rf/subscribe [:img/read] {:frame :img/frame-a}))
            "the subscriber derefs the RAW image-local value")
        (let [runs (image-local-sub-runs recorded :img/read :img/frame-a)]
          (is (seq runs) "the :trace listener saw the image-local sub run")
          (doseq [tags runs]
            (is (= rf.privacy/redacted-sentinel (get-in tags [:rf.sub/value :token]))
                "image-local :sensitive redacted the value, NOT the unclassified global")
            (is (= 1 (get-in tags [:rf.sub/value :public]))
                "the unclassified sibling rides raw")
            (is (not (contains? tags :rf.sub/classification))
                "the internal carrier never reaches the listener")))))))

(deftest second-frame-same-id-resolves-its-own-image-local-classification
  (testing "two frames install the SAME inline id with DIFFERENT classifications;
            each frame's evidence redacts per its OWN image-local declaration —
            no cross-frame classification bleed, no shared global"
    (install-image-frame! :img/frame-a
      (inline-sub-image :img/a :img/read {:sensitive [[:token]]}
                        {:token "A-SECRET" :public "A-pub"}))
    (install-image-frame! :img/frame-b
      (inline-sub-image :img/b :img/read {:sensitive [[:public]]}
                        {:token "B-tok" :public "B-SECRET"}))
    (with-trace-listener
      (fn [recorded]
        @(rf/subscribe [:img/read] {:frame :img/frame-a})
        @(rf/subscribe [:img/read] {:frame :img/frame-b})
        (let [a (last (image-local-sub-runs recorded :img/read :img/frame-a))
              b (last (image-local-sub-runs recorded :img/read :img/frame-b))]
          (is (some? a) "frame A's sub ran")
          (is (some? b) "frame B's sub ran")
          (is (= rf.privacy/redacted-sentinel (get-in a [:rf.sub/value :token]))
              "frame A redacts :token (its own declaration)")
          (is (= "A-pub" (get-in a [:rf.sub/value :public]))
              "frame A leaves :public raw")
          (is (= rf.privacy/redacted-sentinel (get-in b [:rf.sub/value :public]))
              "frame B redacts :public (ITS declaration), independently")
          (is (= "B-tok" (get-in b [:rf.sub/value :token]))
              "frame B leaves :token raw — no bleed from A's :token declaration"))))))

(deftest replacing-frame-a-generation-updates-evidence-without-old-or-global-leak
  (testing "replacing frame A's image generation with a NEW :sensitive
            declaration makes subsequent evidence redact per the NEW generation —
            not the OLD generation's path, not the conflicting global"
    ;; Conflicting global: SAME id, NO classification.
    (rf/reg-sub :img/read (fn [_db _q] {:token "GLOBAL" :public "GLOBAL"}))
    ;; Generation 1 — classifies :token.
    (install-image-frame! :img/frame-a
      (inline-sub-image :img/g1 :img/read {:sensitive [[:token]]}
                        {:token "SECRET" :public "pub"}))
    (with-trace-listener
      (fn [recorded]
        (let [g1 (last (do @(rf/subscribe [:img/read] {:frame :img/frame-a})
                          (image-local-sub-runs recorded :img/read :img/frame-a)))]
          (is (= rf.privacy/redacted-sentinel (get-in g1 [:rf.sub/value :token]))
              "generation 1 redacts :token"))
        ;; Swap to generation 2 — classifies :public instead. Frame memory
        ;; (sub-cache) is preserved across the swap, so clear it to force a fresh
        ;; reaction resolved against the NEW generation (an HMR sub reload).
        (install-image-frame! :img/frame-a
          (inline-sub-image :img/g2 :img/read {:sensitive [[:public]]}
                            {:token "tok2" :public "SECRET2"}))
        (rf/clear-sub-cache! :img/frame-a)
        (reset! recorded [])
        @(rf/subscribe [:img/read] {:frame :img/frame-a})
        (let [g2 (last (image-local-sub-runs recorded :img/read :img/frame-a))]
          (is (some? g2) "the new generation's sub ran")
          (is (= rf.privacy/redacted-sentinel (get-in g2 [:rf.sub/value :public]))
              "the NEW generation redacts :public")
          (is (= "tok2" (get-in g2 [:rf.sub/value :token]))
              "the OLD generation's :token classification did NOT persist, and the
               unclassified global did not supply one")
          (is (not (contains? g2 :rf.sub/classification))
              "the internal carrier never reaches the listener"))))))
