(ns day8.re-frame2-xray.panels.image-view-reads-cljs-test
  "CLJS coverage for the EP-0023 live image/frame read seam + the
  Xray-as-its-own-image DOGFOODING (rf2-32siq3.12 — EP-0023 §Xray Beside The
  Target).

  This is the test the .29 dogfooding review verifies: Xray models itself as a
  SEPARATE image/frame that inspects the target frame as DATA — NOT as shared
  registration state. Concretely:

    - Xray constructs its OWN `rf/image` (`image-view-reads/xray-image`),
      selecting its own source namespaces (`day8.re-frame2-xray.**`);
    - Xray's `:rf.xray/*` registrations do not leak INTO a target frame's
      image (a target image selects the target's own namespaces, not Xray's);
    - a target frame's registrations do not leak INTO Xray's image;
    - Xray reads the target frame's generation + state as DATA through the
      live-read fns, with the two images registration-disjoint.

  The reads are also exercised against the real EP-0023 live-frame registry +
  sealed generations (the fail-soft seam) so the projection runs end-to-end on
  the values `make-frame` / `assemble` actually produce."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [clojure.set :as set]
            [re-frame.image :as image]
            [re-frame.live-frame :as live-frame]
            [re-frame.frame :as frame]
            [re-frame.image-assembly :as image-assembly]
            [re-frame.trace :as trace]
            [day8.re-frame2-xray.panels.image-view-reads :as reads]))

;; Each case starts from a clean frame registry so an `:id` from one case never
;; carries over to the next. EP-0024 (rf2-tu2vr7): the registries collapsed —
;; an image-loaded frame is a `re-frame.frame/frames` record carrying a
;; `:generation`, so resetting `frame/frames` clears the image-loaded frames
;; (`clear-live-frames!` is now a no-op kept for back-compat). The frame-no-emit
;; gate (rf2-2qaqh) lives in a persistent `re-frame.trace` set the registry
;; reset does NOT touch, so the seating cases also clear the shell ids they
;; exercise so each starts un-gated.
(def ^:private seat-test-frame-ids
  [:rf.xray/seat-a :rf.xray/seat-b :rf.xray/seat-c])

(use-fixtures :each
  {:before (fn []
             (reset! frame/frames {})
             (doseq [fid seat-test-frame-ids]
               (trace/set-frame-no-emit! fid false)))
   :after  (fn []
             (reset! frame/frames {})
             (doseq [fid seat-test-frame-ids]
               (trace/set-frame-no-emit! fid false)))})

;; A target image + a target frame, built against an EXPLICIT descriptor pool
;; (so the test does not depend on the live source store carrying any host
;; registrations). The target image selects the target's OWN namespaces.
(def ^:private target-pool
  ;; The explicit descriptor pool `assemble`'s 2-arity selects from: a FLAT seq
  ;; of descriptor maps (each carrying :kind / :id / :rf.provenance/ns — the
  ;; source store's output shape), matching `image/select-descriptors`'s input.
  [{:kind :event :id :counter/inc   :rf.provenance/ns "app.counter" :impl :inc}
   {:kind :sub   :id :counter/value :rf.provenance/ns "app.counter" :impl :val}])

(def ^:private target-image
  (image/image {:id :app/counter :include-ns ["app.counter"]}))

;; ---- Xray's own image (the dogfooding) -----------------------------------

(deftest xray-image-is-its-own-value
  (testing "Xray constructs its OWN inert image VALUE selecting its own source
            namespaces — the inspector's instruction set as data (EP-0023
            §Xray Beside The Target)"
    (let [img (reads/xray-image)]
      (is (= :rf.xray/image (:rf.image/id img)))
      (is (= ["day8.re-frame2-xray.**"] (:rf.image/include-ns img))
          "Xray selects ONLY its own source namespaces")
      ;; rf2-rjml45 — the include glob is NARROWED by `:exclude-ns` so Xray's
      ;; OWN `*-cljs-test` + `test-helpers.**` namespaces (which co-register the
      ;; same `:rf.xray/*` ids in a dev/test build) are subtracted, keeping the
      ;; production image registration-disjoint from Xray's own test
      ;; registrations so the singleton seats without an assembly dup-id.
      (is (= ["day8.re-frame2-xray.**.*-cljs-test"
              "day8.re-frame2-xray.test-helpers.**"]
             (:rf.image/exclude-ns img))
          "Xray excludes its own test + test-support namespaces")
      ;; PURE — an image is data, not registration: constructing it twice
      ;; yields equal values and touches no registry.
      (is (= img (reads/xray-image)) "rf/image is pure — equal values"))))

(deftest xray-image-excludes-its-own-test-registrations
  (testing "rf2-rjml45 — against a pool carrying a production `:rf.xray/*` id AND
            its `*-cljs-test` sibling co-registering the SAME id, `xray-image`
            selects ONLY the production descriptor — the exclude prevents the
            assembly dup-id that blocked flipping the production singleton"
    (let [pool [{:kind :fx :id :rf.editor/open
                 :rf.provenance/ns "day8.re-frame2-xray.open-in-editor" :impl :prod}
                {:kind :fx :id :rf.editor/open
                 :rf.provenance/ns "day8.re-frame2-xray.open-in-editor-cljs-test" :impl :test}
                {:kind :event :id :counter/inc
                 :rf.provenance/ns "day8.re-frame2-xray.test-helpers.host-fixtures.counter"
                 :impl :fixture}]
          sel  (image/select-descriptors (reads/xray-image) pool)]
      ;; only the production :rf.editor/open survives; the test sibling +
      ;; test-helpers fixture are excluded.
      (is (= 1 (count sel)) "exactly one descriptor selected")
      (is (= :rf.editor/open (:id (first sel))))
      (is (= "day8.re-frame2-xray.open-in-editor" (:rf.provenance/ns (first sel)))
          "the PRODUCTION descriptor, not the `*-cljs-test` sibling")
      ;; and assembly seals it WITHOUT a dup-id throw (the blocker is gone).
      (let [gen (image-assembly/assemble [(reads/xray-image)] pool)]
        (is (contains? (reads/application-resolver-keyset gen) [:fx :rf.editor/open])
            "the production :rf.editor/open is in the sealed generation")))))

;; A descriptor authored under XRAY's OWN source namespace (the shape the live
;; source store stamps for every :rf.xray/* registration). Used to give Xray's
;; `day8.re-frame2-xray.**` glob something to select from an EXPLICIT pool, so
;; the resolver-keyset comparison can be exercised deterministically without
;; depending on what the live store carries in the test JVM.
(def ^:private xray-pool
  [{:kind :event :id :rf.xray/refresh :rf.provenance/ns "day8.re-frame2-xray.panels.foo" :impl :refresh}
   {:kind :sub   :id :rf.xray/tab     :rf.provenance/ns "day8.re-frame2-xray.panels.bar" :impl :tab}])

;; The combined pool both images select from in the explicit-pool arity. Xray's
;; glob selects the xray-authored descriptors; the target's glob selects the
;; app.counter ones. Disjoint by construction.
(def ^:private combined-pool (into target-pool xray-pool))

(deftest xray-image-isolated-from-target-image
  (testing "Xray's image and the target frame's image are REGISTRATION-DISJOINT
            — assemble BOTH and compare resolver keysets; Xray's [kind id]s do
            not leak into / from the target's image (the strengthened
            .29-review invariant)"
    ;; Explicit-pool arity: both images select from `combined-pool`. Xray
    ;; resolves its own :rf.xray/* ids; the target resolves :counter/* — the two
    ;; resolver keysets are disjoint → isolated.
    (is (true? (reads/xray-image-isolated-from? target-image combined-pool))
        "Xray (day8.re-frame2-xray.**) and the target (app.counter) resolve
         disjoint [kind id] sets → isolated")
    ;; The negative: an image that DID select Xray's namespaces resolves Xray's
    ;; OWN ids, so the keysets OVERLAP → NOT isolated. The predicate is a real
    ;; keyset-disjointness check, not a constant true.
    (let [leaky (image/image {:id :leaky/img
                              :include-ns ["day8.re-frame2-xray.**" "app.counter"]})]
      (is (false? (reads/xray-image-isolated-from? leaky combined-pool))
          "an image selecting Xray's namespaces shares Xray's [kind id]s → NOT
           isolated"))))

(deftest xray-isolation-is-keyset-not-selector-string
  (testing "the predicate compares RESOLVER KEYSETS, not :include-ns selector
            STRINGS — two DIFFERENT globs that select OVERLAPPING namespaces are
            correctly reported NOT isolated (the proxy a string comparison would
            miss)"
    ;; `day8.re-frame2-xray.**` (Xray's glob) and `day8.re-frame2-xray.panels.*`
    ;; (this target's glob) are DIFFERENT strings — a string-intersection check
    ;; would wrongly call them isolated. But BOTH select the xray-authored
    ;; descriptor under `day8.re-frame2-xray.panels.foo`, so their resolver
    ;; keysets SHARE [:event :rf.xray/refresh] → the keyset check correctly
    ;; reports NOT isolated.
    (let [overlap-target (image/image {:id :overlap/img
                                       :include-ns ["day8.re-frame2-xray.panels.*"]})
          xray-sel       (set (:rf.image/include-ns (reads/xray-image)))
          target-sel     (set (:rf.image/include-ns overlap-target))]
      (is (empty? (set/intersection xray-sel target-sel))
          "the two :include-ns selector STRINGS are disjoint (the old proxy
           would call this isolated)")
      (is (false? (reads/xray-image-isolated-from? overlap-target xray-pool))
          "but the two RESOLVER KEYSETS overlap → the strengthened predicate
           correctly reports NOT isolated"))))

(deftest xray-and-target-resolvers-do-not-share-registrations
  (testing "a frame built from Xray's image and a frame built from the target's
            image have DISJOINT resolver keysets BIDIRECTIONALLY — neither sees
            the other's registrations (Xray is NOT part of the thing being
            inspected)"
    ;; Assemble BOTH generations against the same combined pool, then compare
    ;; their resolver keysets directly (the invariant `xray-image-isolated-from?`
    ;; encodes), in BOTH directions.
    (let [xray-gen    (image-assembly/assemble [(reads/xray-image)] combined-pool)
          target-gen  (image-assembly/assemble [target-image] combined-pool)
          xray-keys   (reads/resolver-keyset xray-gen)
          target-keys (reads/resolver-keyset target-gen)
          ;; The APPLICATION-owned keysets exclude the framework standard the
          ;; assembly unions into EVERY generation (`:rf.interceptor/path`,
          ;; stamped :standard true; rf2-32siq3.41) — a framework standard is
          ;; shared by every frame by construction, NOT a leak between images.
          xray-app    (reads/application-resolver-keyset xray-gen)
          target-app  (reads/application-resolver-keyset target-gen)]
      ;; Each frame resolves ITS own ids.
      (is (contains? xray-keys [:event :rf.xray/refresh])
          "Xray's frame resolves Xray's own [kind id]")
      (is (contains? target-keys [:event :counter/inc])
          "the target's frame resolves the target's own [kind id]")
      ;; The framework standard rides into BOTH generations (the rf2-32siq3.41
      ;; fix: the standard registry is no longer empty) — shared by construction,
      ;; so it is excluded from the leak comparison rather than flagged.
      (is (contains? xray-keys [:interceptor :rf.interceptor/path])
          "the framework standard is unioned into Xray's generation")
      (is (contains? target-keys [:interceptor :rf.interceptor/path])
          "the framework standard is unioned into the target's generation too")
      ;; Bidirectional non-leakage on the APPLICATION-owned keysets: no Xray app
      ;; id in the target's app keyset, and no target app id in Xray's.
      (is (empty? (set/intersection xray-app target-app))
          "the two APPLICATION-owned resolver keysets are disjoint")
      (is (every? (fn [[_ id]] (not= "rf.xray" (namespace id))) target-app)
          "no :rf.xray/* id leaked INTO the target frame's image")
      (is (every? (fn [[_ id]] (= "rf.xray" (namespace id))) xray-app)
          "no target id leaked INTO Xray's image (app-owned ids are all :rf.xray/*)"))))

;; ---- live reads of the real registry (fail-soft seam) --------------------

(deftest live-reads-project-real-frames
  (testing "the live-read seam projects the REAL EP-0023 live-frame registry +
            sealed generations end-to-end"
    ;; A target frame loaded with the target image against its explicit pool,
    ;; registered under an :id so it enters the live-frame registry.
    (live-frame/make-frame {:id :app/main :images [target-image]} target-pool)
    (let [data (reads/image-view-data)
          row  (first (filter #(= :app/main (:frame-id %)) (:frames data)))
          kids (set (map (juxt :kind :id) (:descriptors (:image row))))]
      (is (true? (:images? data)) "a live image-loaded frame → :images? true")
      (is (some? row) "the registered frame is projected")
      ;; EP-0023 §Image: the resolved generation = the 2 selected application
      ;; descriptors + the framework-standard registrations the assembly unions
      ;; into EVERY generation (`:rf.interceptor/path`, stamped :standard true;
      ;; rf2-32siq3.41) — so the frame resolves 3 [kind id] entries, not 2.
      (is (= 3 (:descriptor-count (:image row)))
          "the frame's resolved image carries its 2 app descriptors + the
           framework standard the assembly unions in")
      (is (contains? kids [:event :counter/inc])
          "the resolved descriptor set is the frame's image as a value")
      (is (contains? kids [:interceptor :rf.interceptor/path])
          "the framework standard :rf.interceptor/path rides into the generation")
      (let [std-row (first (filter #(= [:interceptor :rf.interceptor/path]
                                       [(:kind %) (:id %)])
                                   (:descriptors (:image row))))]
        (is (= :rf.prov/standard (:kind (:provenance std-row)))
            "the standard is surfaced with the framework-standard provenance marker")))))

(deftest live-reads-fail-soft-empty-registry
  (testing "an empty live-frame registry projects to the honest no-image state"
    (let [data (reads/image-view-data)]
      (is (= 0 (:frame-count data)))
      (is (false? (:images? data))))))

(deftest resolve-descriptor-is-frame-derived
  (testing "resolving a [kind id] through a real frame's generation yields the
            frame's OWN descriptor (the frame-derived resolution path)"
    (let [fval (live-frame/make-frame {:images [target-image]} target-pool)
          gen   (live-frame/frame-generation fval)
          desc  (reads/resolve-descriptor gen :event :counter/inc)]
      (is (= :counter/inc (:id desc)))
      (is (= "app.counter" (:rf.provenance/ns desc))
          "resolves to the target image's descriptor, not Xray's"))
    (testing "a nil generation is fail-soft → nil (no throw)"
      (is (nil? (reads/resolve-descriptor nil :event :counter/inc))))))

;; ---- TRUE runtime self-seating (EP-0023 §Xray Beside The Target) ----------
;;
;; rf2-32siq3.36 — the dogfood's runtime arm: Xray SEATS a running frame in its
;; OWN image-loaded frame (built from `(xray-image)`), so its registrations are
;; resolved through that frame's OWN sealed generation in genuine registration
;; isolation — not the shared default registrar. These cases exercise the seating
;; against an EXPLICIT pool (deterministic, no dependence on the live store) and
;; assert the trace-emission gate + idempotency the production singleton relies on.

(deftest seat-xray-frame-seats-in-its-own-image
  (testing "seat-xray-frame! seats a LIVE frame whose resolved generation carries
            Xray's OWN registrations (the frame runs Xray's image, not the shared
            registrar) — the true runtime dogfood"
    (let [obj (reads/seat-xray-frame! :rf.xray/seat-a xray-pool)
          kids (reads/application-resolver-keyset (live-frame/frame-generation obj))]
      (is (some? obj) "a fresh seat returns the live frame value")
      (is (= :rf.xray/seat-a (:rf.frame/id obj)) "registered under the shell id")
      (is (true? (reads/xray-frame-seated? :rf.xray/seat-a))
          "the frame is now live in the EP-0023 registry")
      (is (contains? kids [:event :rf.xray/refresh])
          "the seated frame resolves Xray's OWN [kind id] through its image")
      (is (every? (fn [[_ id]] (= "rf.xray" (namespace id))) kids)
          "the seated frame resolves ONLY Xray's app-owned ids — registration
           isolation from any inspected target"))))

(deftest seat-xray-frame-sets-the-trace-no-emit-gate
  (testing "seat-xray-frame! marks the shell frame trace-disabled (rf2-2qaqh) so
            Xray's own reactivity does not flood the ring it inspects — preserved
            across the make-frame seating that cannot carry the record-config flag"
    (is (false? (trace/frame-trace-disabled? :rf.xray/seat-b))
        "not gated before seating")
    (reads/seat-xray-frame! :rf.xray/seat-b xray-pool)
    (is (true? (trace/frame-trace-disabled? :rf.xray/seat-b))
        "seating sets the frame-no-emit gate")))

(deftest seat-xray-frame-is-idempotent
  (testing "a re-seat (re-open / hot-reload / repeated testbed mount) finds the
            frame already live and SKIPS the fail-loud duplicate-:id make-frame,
            re-asserting only the trace gate — no throw"
    (let [first-obj (reads/seat-xray-frame! :rf.xray/seat-c xray-pool)]
      (is (some? first-obj) "first seat creates the frame")
      ;; A second call must NOT throw :rf.error/live-frame-id-conflict.
      (let [second-obj (reads/seat-xray-frame! :rf.xray/seat-c xray-pool)]
        (is (nil? second-obj) "re-seat is a skip (returns nil), not a re-create")
        (is (true? (reads/xray-frame-seated? :rf.xray/seat-c))
            "the frame stays live")
        (is (true? (trace/frame-trace-disabled? :rf.xray/seat-c))
            "the trace gate is re-asserted on the re-seat")))))
