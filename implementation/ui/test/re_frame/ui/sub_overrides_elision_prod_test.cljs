(ns re-frame.ui.sub-overrides-elision-prod-test
  "Advanced-production control for the mounted compiled ViewCell override
  carriage (rf2-vxgfnd.12.2).

  This namespace runs ONLY in `:browser-test-prod-elision` (`:advanced`,
  goog.DEBUG=false).  The ordinary elision probe calls `sub-read` directly and
  therefore cannot reach the ViewCell's React hook/provider path.  This test
  mounts a generated sub-bearing view through that exact path and proves:

  - the shared override Context was never constructed;
  - the internal Provider is transparent;
  - compiled `ui/sub` takes the ordinary owned-node path, never a static
    override handle;
  - the compiled render path establishes NO dynamic `*sub-overrides*` binding
    (rf2-vxgfnd.216). The bundle string-scan owns the DEBUG-only validation
    sentinel, but a string can elide while the actual
    `(binding [reactive/*sub-overrides* ...] ...)` push/set/restore leaks into
    production with a runtime-nil value. Here a sub-bearing body run through the
    real `render-subs` -> `capture-with-overrides` path READS the seeded ROOT
    value of `re-frame.ui.reactive/*sub-overrides*`, so a leaked
    binding-to-nil would shadow the root during the body and flip that read to
    nil — a causal signal the scanner cannot see.

  The normal mounted browser gate supplies the DEBUG=true positive control: the
  same provider helper produces nested static handles there. The
  `render-body-observes-live-sub-overrides-binding` control below supplies the
  same-optimizer positive control for the binding probe: an ambient binding in
  the render's dynamic extent IS read through by that exact body."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            ["react" :as React]
            ["react-dom" :as ReactDOM]
            [re-frame.adapter.sub-override-context :as override-context]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.test-support :as test-support]
            [re-frame.ui :as ui :refer [defview]]
            [re-frame.ui.reactive :as reactive]
            [re-frame.ui.sub-overrides :as sub-overrides]
            [re-frame.ui.test :as uit])
  (:require-macros [re-frame.ui.hidden-sub-macros
                    :refer [hidden-public-sub]]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
   {:adapter ui/adapter :ambient-frame nil :async? true}))

(defview prod-sub-value []
  [:output {:data-role "prod-sub-value"}
   (str (ui/sub [::prod-value]))])

(defn prod-provider-tree []
  (sub-overrides/provider-element
   {[::prod-value] "forbidden-override"}
   (React/createElement React/Fragment nil
                        (React/createElement prod-sub-value nil)
                        (React/createElement prod-sub-value nil))))

(defview prod-mounted-tree []
  (ui/raw (React/createElement prod-provider-tree nil)))

(defn hidden-ordinary-helper []
  (ui/sub [:hidden/ordinary-helper]))

(defn hidden-macro-helper []
  (hidden-public-sub))

(deftest advanced-production-hidden-reads-fail-instead-of-going-nonreactive
  (doseq [helper [hidden-ordinary-helper hidden-macro-helper]]
    (is (= :rf.error/ui-tree-malformed
           (try (helper) nil
                (catch :default e (:rf.error/id (ex-data e)))))
        "advanced production preserves fail-loud public authoring semantics")))

(deftest mounted-viewcell-provider-carriage-elides-in-production
  (rf/reg-sub ::prod-value (fn [db _] (:prod-value db)))
  (let [f        (rf/make-frame {:initial-events [[:rf/set-db {:prod-value "ordinary"}]]})
        frame-id (frame/frame-target->id f)
        sub-key  [:sub frame-id [::prod-value]]
        container (js/document.createElement "div")
        root      (volatile! nil)]
    (async done
      (is (nil? override-context/override-context)
          "goog.DEBUG=false performed no React.createContext construction")
      (.appendChild js/document.body container)
      (try
        ;; Production React intentionally exports no `act`; flushSync is the
        ;; host-supported way to make this advanced-build mount observable.
        (ReactDOM/flushSync
         #(vreset! root
                   (ui/mount [ui/frame-provider {:frame f}
                              [prod-mounted-tree]]
                             container
                             {:root-id ::prod-mounted-elision})))
        (testing "provider is transparent and generated ui/sub stays ordinary"
          (is (= "ordinary"
                 (.-textContent
                  (.querySelector container "[data-role='prod-sub-value']"))))
          (let [cells   (vec (reactive/current-live-cells))
                records (mapv #(first (vals (reactive/committed-sites %))) cells)
                sids    (mapv #(first (keys (reactive/committed-sites %))) cells)]
            (is (= 2 (count cells)))
            (is (= 2 (.-length
                      (.querySelectorAll container
                                         "[data-role='prod-sub-value']"))))
            (is (every? #(= #{sub-key}
                            (reactive/committed-target-keys %))
                        cells))
            (is (= 1 (count (distinct sids)))
                "both view instances execute the same compiler lexical site")
            (is (.startsWith (first sids) "sid1-")
                "the advanced hot call retains the compact versioned sid")
            (is (identical? (:query (first records))
                            (:query (second records)))
                "the literal query is one module constant, not rebuilt per render")
            (is (= 2 (:ref-count
                      (get @(:sub-cache (frame/frame frame-id))
                           [::prod-value])))
                "each lexical view instance remains an independent owner")))
        (catch :default e
          (is false (str "advanced mounted override-elision control rejected: " e)))
        (finally
          (when @root
            (ReactDOM/flushSync #(ui/unmount! @root)))
          (.remove container)
          (rf/destroy-frame! f)
          (done))))))

;; ---------------------------------------------------------------------------
;; rf2-vxgfnd.216 — the compiled render path carries no `*sub-overrides*`
;; binding.
;;
;; `capture-with-overrides` binds `re-frame.ui.reactive/*sub-overrides*` ONLY
;; under `interop/debug-enabled?`; production goes straight to `with-capture`.
;; The bundle string-scan proves the DEBUG-only validation sentinel is absent,
;; but that string is not OWNED by the binding: an adversary could keep the
;; sentinel under goog.DEBUG (it still elides) yet leak the bare
;; `(binding [reactive/*sub-overrides* overrides] ...)` into the production
;; branch, where `overrides` is always nil — the dynamic-var push/set/restore
;; then ships and runs on every sub-bearing render while the scanner still
;; passes.
;;
;; This probe is causal, not lexical. A sub-bearing view (routed to
;; `render-subs` -> `capture-with-overrides`) reads the LIVE value of
;; `reactive/*sub-overrides*` from inside its body and renders it. The test
;; seeds a distinguishable non-nil ROOT (via `set!`, the persistent form that
;; survives to the async flushSync render — mirroring
;; `test-support/make-reset-runtime-fixture`). A correct production build runs
;; the body with the root intact, so the DOM reads the root token; a leaked
;; `(binding [reactive/*sub-overrides* nil] ...)` would shadow the root during
;; the body and the DOM would read nil. Deleting, retaining, or relocating the
;; validation string cannot mask that.
;; ---------------------------------------------------------------------------

(def ^:private probe-root-token "rf2-216-sub-overrides-root")
(def ^:private control-binding-token "rf2-216-sub-overrides-control")

(defview prod-binding-probe []
  ;; A `ui/sub` site routes this view to `render-subs` -> `capture-with-overrides`
  ;; (the exact wrapper that binds `*sub-overrides*` in a DEBUG build). The body
  ;; also reads that dynamic var, so the committed DOM records the value in
  ;; effect DURING this production render.
  [:output {:data-role "prod-binding-probe"}
   (str (ui/sub [::prod-value]) "|" (pr-str reactive/*sub-overrides*))])

(defn- probe-text [container]
  (.-textContent (.querySelector container "[data-role='prod-binding-probe']")))

(deftest advanced-production-render-body-carries-no-sub-overrides-binding
  (rf/reg-sub ::prod-value (fn [db _] (:prod-value db)))
  (let [f          (rf/make-frame {:initial-events [[:rf/set-db {:prod-value "ordinary"}]]})
        root-c     (js/document.createElement "div")
        control-c  (js/document.createElement "div")
        root-root  (volatile! nil)
        ctrl-root  (volatile! nil)
        prior      reactive/*sub-overrides*]
    (async done
      ;; Persistent root seed (survives to the async render), NOT a `binding`.
      (set! reactive/*sub-overrides* probe-root-token)
      (.appendChild js/document.body root-c)
      (.appendChild js/document.body control-c)
      (try
        ;; --- Causal probe: no binding in the production body -> root read ---
        (ReactDOM/flushSync
         #(vreset! root-root
                   (ui/mount [ui/frame-provider {:frame f}
                              [prod-binding-probe]]
                             root-c
                             {:root-id ::prod-binding-root})))
        (is (= (str "ordinary|" (pr-str probe-root-token))
               (probe-text root-c))
            (str "render-subs reached the body with *sub-overrides* at its "
                 "seeded ROOT — no dynamic binding shipped into production"))

        ;; --- Same-optimizer positive control: an ambient binding in the
        ;; render's dynamic extent IS observed by the very same body, so the
        ;; probe above would go red on a production-leaked binding. ---
        (binding [reactive/*sub-overrides* control-binding-token]
          (ReactDOM/flushSync
           #(vreset! ctrl-root
                     (ui/mount [ui/frame-provider {:frame f}
                                [prod-binding-probe]]
                               control-c
                               {:root-id ::prod-binding-control}))))
        (is (= (str "ordinary|" (pr-str control-binding-token))
               (probe-text control-c))
            (str "control: a live *sub-overrides* binding during render is read "
                 "through the compiled body — the probe can detect a leak"))
        (catch :default e
          (is false (str "advanced sub-overrides binding probe rejected: " e)))
        (finally
          (when @root-root
            (ReactDOM/flushSync #(ui/unmount! @root-root)))
          (when @ctrl-root
            (ReactDOM/flushSync #(ui/unmount! @ctrl-root)))
          (set! reactive/*sub-overrides* prior)
          (.remove root-c)
          (.remove control-c)
          (rf/destroy-frame! f)
          (done))))))
