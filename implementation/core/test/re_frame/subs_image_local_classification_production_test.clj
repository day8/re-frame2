(ns re-frame.subs-image-local-classification-production-test
  "rf2-7vk3z — \"no cross-frame classification bleed\", witnessed without the
  trace stream.

  ## The invariant, and where it really lives

  `re-frame.subs-image-local-classification-cljs-test` proves that two frames
  installing the SAME inline sub id with DIFFERENT `:sensitive` declarations
  each redact per their OWN image-local declaration — no bleed from a sibling
  frame, no fallback to a conflicting same-id GLOBAL registration. It proves it
  by subscribing on a live frame and reading `:rf.sub/run` off a
  `register-listener! :trace` stream, which is dev-only, so those legs are red
  in the `jvm-core-prod-gate` lane.

  Dropping the trace assertions would make that suite green while retiring live
  coverage of a privacy invariant, so this namespace re-proves it through
  production-visible witnesses instead. Doing that first required establishing
  WHICH HALF of the invariant is production-real, because the two halves have
  different answers:

  **The RESOLUTION half is production-real and is pinned here.** Which image's
  `sub-meta` a frame's reaction resolves for a given sub id is decided by
  `rf.registrar/lookup`, through the frame's own generation resolver
  (`live-frame/call-with-frame-resolution` binds `rf.registrar/*generation*`).
  That is the same one map from which `re-frame.subs.memo` takes BOTH the
  `:handler-fn` it runs AND — as `(select-keys sub-meta [:sensitive :large
  :large?])` — the classification declaration it carries. Nothing on that path
  consults `interop/debug-enabled?`. A bleed here would hand frame A's reaction
  frame B's declaration in a production build, so it is witnessable and it is
  witnessed below: through `@(rf/subscribe …)`, a public return value, and
  through the resolved metadata map itself.

  **The PROJECTION half has no production egress inside `implementation/core`,
  and that is a finding rather than a gap.** The carried declaration's only
  consumer here is `rf.classification/project-sub-tags`, reached only from
  `trace/build-event` inside `trace/emit!`'s `interop/debug-enabled?` gate.
  Under the production gate no `:rf.sub/run` event is built at all, so there is
  no production surface for a sub-output classification to leak onto — the
  redaction it performs protects the dev / Xray / MCP trace stream. Inventing
  an observable by adding production instrumentation to make a test possible
  would be the wrong fix.

  That half is nonetheless already proven under the gate: the projector is a
  pure function of the tags handed to it, and the five chokepoint fixtures at
  the head of the dev-posture suite drive it directly and pass in BOTH
  postures. `projector-redacts-per-the-actually-resolved-declaration` below
  closes the last link by feeding the projector the declaration the LIVE frame
  really resolved — reconstructing the carrier exactly as `subs.memo` does —
  rather than a fabricated one.

  ## Posture-independence

  Every assertion holds in dev AND under `-Dre-frame.debug=false`, so this
  namespace runs in the ordinary `clojure -M:test` suite and joins
  `scripts/test-core-prod-gate.sh` automatically (that lane's roster is an
  EXCLUSION list — a new namespace joins by default). Nothing here rebinds
  `interop/debug-enabled?`: the flag is read once at namespace-load time and a
  rebind cannot reach it (rf2-f7qj4)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.classification :as rf.classification]
            [re-frame.core :as rf]
            [re-frame.image :as rf.image]
            [re-frame.live-frame :as rf.live-frame]
            [re-frame.privacy :as rf.privacy]
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- inline-sub-image
  "An image whose ONLY registration is an inline layer-1 `:reg-sub` under `id`
  carrying `classification` metadata and returning the constant `value`. The
  inline descriptor is normalized and lowered by REAL image assembly when the
  frame's generation is sealed."
  [image-id id classification value]
  (rf.image/image {:id            image-id
                :registrations {:reg-sub [[id classification (fn [_db _q] value)]]}}))

(defn- install-image-frame!
  "Seal `frame-id`'s generation from `image` through the real image-assembly
  path. The empty descriptor pool keeps the generation to the image's own
  inline registrations (no live source-store contamination)."
  [frame-id image]
  (rf.live-frame/make-frame {:id frame-id :images [image]} []))

(defn- resolved-sub-meta
  "The registration metadata `frame-id`'s reactions resolve for `sub-id` — the
  SAME `rf.registrar/lookup` call `re-frame.subs` makes, under the SAME
  frame-generation binding (`live-frame/call-with-frame-resolution`). Always-on:
  nothing on this path reads `interop/debug-enabled?`."
  [frame-id sub-id]
  (rf.live-frame/call-with-frame-resolution frame-id #(rf.registrar/lookup :sub sub-id)))

(defn- carried-declaration
  "Reconstruct the classification carrier exactly as `re-frame.subs.memo` does
  — `(select-keys sub-meta [:sensitive :large :large?])` off the resolved
  metadata. This is the value a reaction would hand the trace chokepoint."
  [frame-id sub-id]
  (select-keys (resolved-sub-meta frame-id sub-id) [:sensitive :large :large?]))

(defn- project-sub-run
  "Project a `:rf.sub/run` envelope and return the projected tags — the pure
  chokepoint, posture-independent."
  [tags]
  (-> (rf.classification/project-trace-event
        {:operation :rf.sub/run :op-type :rf.sub
         :tags      (merge {:frame :rf/default} tags)})
      :tags))

;; ===========================================================================
;; The resolution half — production-visible, per frame, no global fallback
;; ===========================================================================

(deftest two-frames-resolve-their-own-image-local-declaration-in-every-posture
  (testing "rf2-7vk3z — two frames install the SAME inline sub id with
            DIFFERENT `:sensitive` declarations. Each resolves ITS OWN, and
            neither falls back to the conflicting same-id GLOBAL. Witnessed by
            the public subscription return value and by the resolved metadata
            map — not by the dev-only trace stream.

            A bleed here is a production data-exposure defect: frame A's
            reaction would carry frame B's declaration into every redaction
            decision made about A's output."
    ;; Conflicting global: SAME id, NO classification. A fallback to it would
    ;; leave both frames' values entirely unclassified.
    (rf/reg-sub :img/read (fn [_db _q] {:token "GLOBAL" :public "GLOBAL"}))
    (install-image-frame! :img/frame-a
      (inline-sub-image :img/a :img/read {:sensitive [[:token]]}
                        {:token "A-SECRET" :public "A-pub"}))
    (install-image-frame! :img/frame-b
      (inline-sub-image :img/b :img/read {:sensitive [[:public]]}
                        {:token "B-tok" :public "B-SECRET"}))

    (testing "the public return value is each frame's OWN image-local sub"
      (is (= {:token "A-SECRET" :public "A-pub"}
             @(rf/subscribe [:img/read] {:frame :img/frame-a})))
      (is (= {:token "B-tok" :public "B-SECRET"}
             @(rf/subscribe [:img/read] {:frame :img/frame-b}))))

    (testing "and so is the classification declaration on the one resolved
              metadata map the reaction takes both its handler and its
              declaration from"
      (is (= {:sensitive [[:token]]} (carried-declaration :img/frame-a :img/read))
          "frame A carries ITS declaration")
      (is (= {:sensitive [[:public]]} (carried-declaration :img/frame-b :img/read))
          "frame B carries ITS declaration — no bleed from A")
      (is (empty? (carried-declaration :rf/default :img/read))
          "and the unclassified GLOBAL supplied neither"))))

(deftest replacing-a-generation-swaps-value-and-declaration-together
  (testing "rf2-7vk3z — replacing frame A's image generation makes subsequent
            resolution read the NEW declaration: not the OLD generation's path,
            and not the conflicting global. Because handler and declaration
            come from ONE resolved map, a stale-generation read would show up
            in the public return value too."
    (rf/reg-sub :img/read (fn [_db _q] {:token "GLOBAL" :public "GLOBAL"}))
    (install-image-frame! :img/frame-a
      (inline-sub-image :img/g1 :img/read {:sensitive [[:token]]}
                        {:token "SECRET" :public "pub"}))
    (is (= {:sensitive [[:token]]} (carried-declaration :img/frame-a :img/read))
        "generation 1 classifies :token")
    (is (= {:token "SECRET" :public "pub"}
           @(rf/subscribe [:img/read] {:frame :img/frame-a})))

    ;; Generation 2 classifies :public instead. Frame memory (the sub cache) is
    ;; preserved across the swap, so clear it to force a fresh reaction
    ;; resolved against the NEW generation — an HMR sub reload.
    (install-image-frame! :img/frame-a
      (inline-sub-image :img/g2 :img/read {:sensitive [[:public]]}
                        {:token "tok2" :public "SECRET2"}))
    (rf/clear-sub-cache! :img/frame-a)

    (is (= {:sensitive [[:public]]} (carried-declaration :img/frame-a :img/read))
        "the NEW generation's declaration is what resolves")
    (is (= {:token "tok2" :public "SECRET2"}
           @(rf/subscribe [:img/read] {:frame :img/frame-a}))
        "and the NEW generation's handler is what runs")))

;; ===========================================================================
;; The last link — the projector, fed what the LIVE frame really resolved
;; ===========================================================================

(deftest projector-redacts-per-the-actually-resolved-declaration
  (testing "rf2-7vk3z — the dev-posture suite's chokepoint fixtures HAND the
            projector a fabricated declaration, so they stay green even if real
            image-local resolution had fallen back to the global. This closes
            that gap without a trace listener: the declaration fed in is the one
            `resolved-sub-meta` really produced for each live frame,
            reconstructed exactly as `re-frame.subs.memo` reconstructs it.

            `project-trace-event` is a pure function of its argument and is not
            debug-gated, so this assertion is posture-independent even though
            the trace stream that would normally call it is dev-only."
    (rf/reg-sub :img/read (fn [_db _q] {:token "GLOBAL" :public "GLOBAL"}))
    (install-image-frame! :img/frame-a
      (inline-sub-image :img/a :img/read {:sensitive [[:token]]}
                        {:token "A-SECRET" :public "A-pub"}))
    (install-image-frame! :img/frame-b
      (inline-sub-image :img/b :img/read {:sensitive [[:public]]}
                        {:token "B-tok" :public "B-SECRET"}))
    (let [a (project-sub-run {:rf.sub/id             :img/read
                              :frame                 :img/frame-a
                              :rf.sub/value          @(rf/subscribe [:img/read] {:frame :img/frame-a})
                              :rf.sub/classification (carried-declaration :img/frame-a :img/read)})
          b (project-sub-run {:rf.sub/id             :img/read
                              :frame                 :img/frame-b
                              :rf.sub/value          @(rf/subscribe [:img/read] {:frame :img/frame-b})
                              :rf.sub/classification (carried-declaration :img/frame-b :img/read)})]
      (is (= rf.privacy/redacted-sentinel (get-in a [:rf.sub/value :token]))
          "frame A redacts :token — its own declaration")
      (is (= "A-pub" (get-in a [:rf.sub/value :public]))
          "and leaves :public raw")
      (is (= rf.privacy/redacted-sentinel (get-in b [:rf.sub/value :public]))
          "frame B redacts :public — ITS declaration, independently")
      (is (= "B-tok" (get-in b [:rf.sub/value :token]))
          "and leaves :token raw: no bleed from A's declaration")
      (is (not (contains? a :rf.sub/classification))
          "the internal carrier is stripped — it never egresses")
      (is (not (contains? b :rf.sub/classification))))))
