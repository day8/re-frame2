(ns re-frame.story-runtime-test
  "JVM tests for re-frame2-story Stage 3 (rf2-von3) — runtime.

  Covers:

  - Args precedence: global < story < mode < variant < cell-overrides
    with deep-merge on nested maps and replace on vectors.
  - Decorator composition: classification by `:kind`, ordering (story
    decorators before variant decorators), hiccup wrap composition,
    fx-override registration.
  - Snapshot-identity: stability across re-runs; sensitivity to
    every input axis per IMPL-SPEC §5.6.
  - Lifecycle state machine: `:pre-mount → :mounting → :loading →
    :ready` via runtime fns + watcher firing.
  - `run-variant` end-to-end: registered variant → frame allocated →
    events drained → result map populated.
  - Frame teardown via `destroy-variant!`.
  - Error projection per IMPL-SPEC §5.5.

  All tests run on the JVM via `clojure -M:test`. Per the
  `jvm_interop_must_work` user-feedback rule the runtime must be JVM-
  portable — `run-variant` returns a CompletableFuture on JVM (vs JS
  Promise on CLJS); the tests `deref` it for the result map."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core            :as rf]
            [re-frame.frame           :as frame]
            [re-frame.late-bind       :as late-bind]
            [re-frame.machines        :as machines]
            [re-frame.registrar       :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.story           :as story]
            [re-frame.story.args      :as args]
            [re-frame.story.async     :as async]
            [re-frame.story.config    :as config]
            [re-frame.story.decorators :as decorators]
            [re-frame.story.frames    :as frames]
            [re-frame.story.identity  :as ident]
            [re-frame.story.loaders   :as loaders]
            [re-frame.story.runtime   :as runtime]))

;; ---- fixtures -------------------------------------------------------------

(defn reset-all [test-fn]
  ;; Tear down Story's side-table.
  (story/clear-all!)
  ;; Tear down the framework registrar so test isolation holds.
  (registrar/clear-all!)
  ;; Tear down every non-default frame.
  (reset! frame/frames {})
  ;; Install the plain-atom adapter (matches the machines test fixture
  ;; pattern). `rf/init!` is idempotent once seated; we tolerate the
  ;; double-install error if the adapter is already in place.
  (try (rf/init! plain-atom/adapter)
       (catch clojure.lang.ExceptionInfo _ nil))
  ;; Re-require machines so its `reg-sub :rf/machine` survives the
  ;; registrar/clear-all! call. Mirrors the machines test fixtures.
  (require 're-frame.machines :reload)
  (machines/reset-timers!)
  (loaders/clear-watchers!)
  (config/set-global-args! {})
  ;; Re-install the canonical vocabulary (tags + lifecycle machine).
  (story/install-canonical-vocabulary!)
  ;; Make sure :rf/default is always present.
  (frame/ensure-default-frame!)
  (test-fn))

(use-fixtures :each reset-all)

;; ---- stage marker --------------------------------------------------------

(deftest stage-marker-runtime
  (testing "Stage 6 supersedes Stage 5 — the loaded surface advertises :sota-features"
    (is (= :sota-features story/stage))))

;; ===========================================================================
;; ARGS PRECEDENCE
;; ===========================================================================

(deftest deep-merge-merges-nested-maps
  (testing "deep-merge recurses into maps"
    (is (= {:a {:b 1 :c 2}}
           (args/deep-merge {:a {:b 1}} {:a {:c 2}}))))

  (testing "non-map values replace"
    (is (= {:a [3]}
           (args/deep-merge {:a [1 2]} {:a [3]}))))

  (testing "nil right-hand is a no-op"
    (is (= {:a 1} (args/deep-merge {:a 1} nil))))

  (testing "scalar replaces nested map"
    (is (= {:a 5} (args/deep-merge {:a {:b 1}} {:a 5})))))

(deftest resolve-args-precedence-chain
  (testing "global < story < mode < variant < cell-overrides"
    (story/configure! {:rf.story/global-args {:theme :light :verbose? false}})
    (story/reg-story :story.ui.button
      {:doc       "btn"
       :component :app.ui/button
       :args      {:label "Story label" :verbose? true}})
    (story/reg-mode :Mode.app/dark
      {:args {:theme :dark}})
    (story/reg-variant :story.ui.button/default
      {:args   {:label "Variant label" :icon :star}
       :events []})
    (let [resolved (story/resolve-args :story.ui.button/default
                                       {:active-modes  [:Mode.app/dark]
                                        :cell-overrides {:label "Cell label"}})]
      (is (= :dark         (:theme resolved))    "mode wins over global")
      (is (= true          (:verbose? resolved)) "story wins over global")
      (is (= "Cell label"  (:label resolved))    "cell-overrides win over variant")
      (is (= :star         (:icon resolved))     "variant arg passes through"))))

(deftest resolve-args-deep-merge-on-nested
  (testing "nested maps deep-merge across layers"
    (story/configure! {:rf.story/global-args {:layout {:max-width 1024 :padding 8}}})
    (story/reg-story :story.layout.box
      {:args {:layout {:padding 16}}})
    (story/reg-variant :story.layout.box/deep
      {:args   {:layout {:margin 4}}
       :events []})
    (let [r (story/resolve-args :story.layout.box/deep)]
      (is (= 1024 (get-in r [:layout :max-width])))
      (is (= 16   (get-in r [:layout :padding]))   "story wins")
      (is (= 4    (get-in r [:layout :margin]))))))

(deftest resolve-args-unregistered-variant
  (testing "unregistered variant resolves to {} without throwing"
    (is (= {} (story/resolve-args :story.missing/x)))))

;; ===========================================================================
;; DECORATOR COMPOSITION
;; ===========================================================================

(deftest decorators-classified-by-kind
  (testing "hiccup / frame-setup / fx-override decorators land in their slots"
    (story/reg-decorator :centered
      {:kind :hiccup
       :wrap (fn [body _args] [:div.centered body])})
    (story/reg-decorator :mock-auth
      {:kind :frame-setup
       :init [[:auth/restore-session {:user "alice"}]]})
    (story/reg-decorator :stub-http
      {:kind :fx-override
       :fx-id :http
       :response {:status :pending}})
    (story/reg-variant :story.composed/v
      {:decorators [[:centered] [:mock-auth] [:stub-http]]
       :events     []})
    (let [r (story/resolve-decorators :story.composed/v)]
      (is (= 1 (count (:hiccup r))))
      (is (= :centered (-> r :hiccup first :id)))
      (is (= 1 (count (:frame-setup r))))
      (is (= :mock-auth (-> r :frame-setup first :id)))
      (is (= 1 (count (:fx-override r))))
      (is (= :stub-http (-> r :fx-override first :id)))
      (is (empty? (:errors r))))))

(deftest decorators-story-then-variant-order
  (testing "story decorators come before variant decorators in declared order"
    (story/reg-decorator :outer
      {:kind :hiccup
       :wrap (fn [body _args] [:div.outer body])})
    (story/reg-decorator :inner
      {:kind :hiccup
       :wrap (fn [body _args] [:div.inner body])})
    (story/reg-story :story.compose
      {:decorators [[:outer]]})
    (story/reg-variant :story.compose/v
      {:decorators [[:inner]]
       :events     []})
    (let [r       (story/resolve-decorators :story.compose/v)
          ids     (mapv :id (:hiccup r))]
      (is (= [:outer :inner] ids)
          "story decorators precede variant decorators in declared order"))))

(deftest decorators-apply-hiccup-outermost-first
  (testing "apply-hiccup-decorators wraps innermost first, outermost last"
    (story/reg-decorator :outer
      {:kind :hiccup
       :wrap (fn [body _args] [:div.outer body])})
    (story/reg-decorator :inner
      {:kind :hiccup
       :wrap (fn [body _args] [:div.inner body])})
    (story/reg-story :story.wrap
      {:decorators [[:outer]]})
    (story/reg-variant :story.wrap/v
      {:decorators [[:inner]]
       :events     []})
    (let [r       (story/resolve-decorators :story.wrap/v)
          wrapped (decorators/apply-hiccup-decorators (:hiccup r) [:span "x"] {})]
      ;; Outermost is :outer; inside it is :inner; inside that is the span.
      (is (= [:div.outer [:div.inner [:span "x"]]] wrapped)))))

(deftest decorators-unknown-id-becomes-error
  (testing "an unregistered decorator id surfaces in :errors"
    (story/reg-variant :story.bad/v
      {:decorators [[:totally-unregistered]]
       :events     []})
    (let [r (story/resolve-decorators :story.bad/v)]
      (is (= 1 (count (:errors r))))
      (is (= :rf.error/decorator-unknown
             (-> r :errors first :rf.error))))))

(deftest fx-overrides-map-last-wins
  (testing "two fx-override decorators with the same :fx-id resolve last-wins"
    (story/reg-decorator :first-stub
      {:kind :fx-override :fx-id :http :response {:n 1}})
    (story/reg-decorator :second-stub
      {:kind :fx-override :fx-id :http :response {:n 2}})
    (story/reg-variant :story.fx/v
      {:decorators [[:first-stub] [:second-stub]]
       :events     []})
    (let [r       (story/resolve-decorators :story.fx/v)
          stack   (decorators/fx-overrides-map (:fx-override r))]
      (is (= 1 (count (:overrides stack)))
          "last-wins: only one entry per fx-id")
      (is (contains? (:overrides stack) :http))
      ;; Stage 5 (rf2-h8et) — the stub-id is now namespaced by fx-id
      ;; so the ref-args-driven `:rf.story/force-fx-stub` decorator
      ;; can register distinct stubs for distinct fx-ids referenced
      ;; from the same decorator id. The decorator-id segment is
      ;; preserved verbatim; the fx-id is appended with `+` separator.
      (is (= :rf.story.fx-stub/second-stub+http
             (get-in stack [:overrides :http]))))))

;; ===========================================================================
;; GLOBAL DECORATORS (rf2-835ey — Storybook preview.ts parity, F-1)
;; ===========================================================================

(deftest reg-global-decorator-appends-to-resolved-stack
  (testing "a global decorator prefixes the resolved decorator stack for
            every variant — outermost wrap layer per rf2-835ey"
    (story/reg-global-decorator :app/theme
      {:kind :hiccup :wrap (fn [body _] [:div.theme body])})
    (story/reg-decorator :story-deco
      {:kind :hiccup :wrap (fn [body _] [:div.story body])})
    (story/reg-decorator :variant-deco
      {:kind :hiccup :wrap (fn [body _] [:div.variant body])})
    (story/reg-story :story.gd
      {:decorators [[:story-deco]]})
    (story/reg-variant :story.gd/v
      {:decorators [[:variant-deco]]
       :events     []})
    (let [r       (story/resolve-decorators :story.gd/v)
          ids     (mapv :id (:hiccup r))
          wrapped (decorators/apply-hiccup-decorators
                    (:hiccup r) [:span "leaf"] {})]
      (is (= [:app/theme :story-deco :variant-deco] ids)
          "global decorator comes first (outermost), then story, then variant")
      (is (= [:div.theme [:div.story [:div.variant [:span "leaf"]]]] wrapped)
          ":app/theme wraps outermost; the leaf is innermost"))))

(deftest reg-global-decorator-applies-to-variants-with-no-story-decorators
  (testing "a global decorator wraps a variant whose parent story has no
            :decorators slot and whose own :decorators slot is empty —
            the global stack still applies"
    (story/reg-global-decorator :app/wrap
      {:kind :hiccup :wrap (fn [body _] [:div.wrap body])})
    (story/reg-story :story.gd2 {})
    (story/reg-variant :story.gd2/bare {:events []})
    (let [r   (story/resolve-decorators :story.gd2/bare)
          ids (mapv :id (:hiccup r))]
      (is (= [:app/wrap] ids)
          "bare variant inherits the global stack even with no story
           / variant decorators"))))

(deftest reg-global-decorator-multiple-earliest-first
  (testing "two global decorators apply in registration order — earliest
            first (outermost wrap)"
    (story/reg-global-decorator :app/g1
      {:kind :hiccup :wrap (fn [body _] [:div.g1 body])})
    (story/reg-global-decorator :app/g2
      {:kind :hiccup :wrap (fn [body _] [:div.g2 body])})
    (story/reg-variant :story.gd3/v {:events []})
    (let [r       (story/resolve-decorators :story.gd3/v)
          ids     (mapv :id (:hiccup r))
          wrapped (decorators/apply-hiccup-decorators
                    (:hiccup r) [:span "x"] {})]
      (is (= [:app/g1 :app/g2] ids)
          "earliest-registered first")
      (is (= [:div.g1 [:div.g2 [:span "x"]]] wrapped)
          ":app/g1 wraps :app/g2 (earliest is outermost)"))))

(deftest reg-global-decorator-replaces-in-place-on-re-registration
  (testing "re-registering the same global decorator id replaces in place —
            hot-reloading the body must not reshuffle the order"
    (story/reg-global-decorator :app/first
      {:kind :hiccup :wrap (fn [body _] [:div.first.v1 body])})
    (story/reg-global-decorator :app/second
      {:kind :hiccup :wrap (fn [body _] [:div.second body])})
    ;; Re-register :app/first with a new body — its position must stay at 0.
    (story/reg-global-decorator :app/first
      {:kind :hiccup :wrap (fn [body _] [:div.first.v2 body])})
    (let [refs (story/global-decorators)
          ids  (mapv first refs)]
      (is (= [:app/first :app/second] ids)
          ":app/first stays at position 0; re-registration did not push it
           to the end"))
    ;; And the new body is the one applied.
    (story/reg-variant :story.gd4/v {:events []})
    (let [r       (story/resolve-decorators :story.gd4/v)
          wrapped (decorators/apply-hiccup-decorators
                    (:hiccup r) [:span "x"] {})]
      (is (= [:div.first.v2 [:div.second [:span "x"]]] wrapped)
          "the replacement body (v2) is the one applied"))))

(deftest reg-global-decorator-mixed-kinds
  (testing "a global :frame-setup decorator's :init events fire before any
            story / variant events — the global slot lands in the
            :frame-setup bucket exactly like a story-level decorator would"
    (story/reg-global-decorator :app/setup
      {:kind :frame-setup :init [[:noop]]})
    (story/reg-global-decorator :app/theme
      {:kind :hiccup :wrap (fn [body _] [:div.theme body])})
    (story/reg-global-decorator :app/stub
      {:kind :fx-override :fx-id :http :response {:ok? true}})
    (story/reg-variant :story.gd5/v {:events []})
    (let [r (story/resolve-decorators :story.gd5/v)]
      (is (= 1 (count (:hiccup r))))
      (is (= 1 (count (:frame-setup r))))
      (is (= 1 (count (:fx-override r))))
      (is (empty? (:errors r))
          "global decorators classify into all three kind-buckets cleanly")
      (is (= :app/theme (-> r :hiccup first :id)))
      (is (= :app/setup (-> r :frame-setup first :id)))
      (is (= :app/stub  (-> r :fx-override first :id))))))

(deftest unreg-global-decorator-removes-from-stack
  (testing "unreg-global-decorator! removes the entry from the global
            vector; subsequent resolutions do not see it"
    (story/reg-global-decorator :app/keep
      {:kind :hiccup :wrap (fn [body _] [:div.keep body])})
    (story/reg-global-decorator :app/drop
      {:kind :hiccup :wrap (fn [body _] [:div.drop body])})
    (story/reg-variant :story.gd6/v {:events []})
    (is (= [:app/keep :app/drop]
           (mapv :id (:hiccup (story/resolve-decorators :story.gd6/v)))))
    (story/unreg-global-decorator! :app/drop)
    (is (= [:app/keep]
           (mapv :id (:hiccup (story/resolve-decorators :story.gd6/v))))
        "after unreg, :app/drop is gone from the resolved stack")))

(deftest reg-global-decorator-with-ref-args
  (testing "reg-global-decorator three-arity form lands ref-args at the
            :wrap fn under (:decorator/args args-map)"
    (story/reg-global-decorator :app/wrap-tagged
      {:kind :hiccup
       :wrap (fn [body args]
               [:div.tagged {:tag (-> args :decorator/args first)} body])}
      [:my-tag])
    (story/reg-variant :story.gd7/v {:events []})
    (let [r       (story/resolve-decorators :story.gd7/v)
          wrapped (decorators/apply-hiccup-decorators
                    (:hiccup r) [:span "x"] {})]
      (is (= [:div.tagged {:tag :my-tag} [:span "x"]] wrapped)
          "ref-args from the global registration land at the :wrap fn"))))

;; ===========================================================================
;; SNAPSHOT IDENTITY
;; ===========================================================================

(deftest snapshot-identity-stable-across-runs
  (testing "two calls with the same inputs produce the same hash"
    (story/reg-story :story.id
      {:component :app/v :args {:a 1}})
    (story/reg-variant :story.id/v
      {:events [[:init]] :args {:b 2} :tags #{:dev}})
    (let [a (story/snapshot-identity :story.id/v {:substrate :reagent})
          b (story/snapshot-identity :story.id/v {:substrate :reagent})]
      (is (= (:content-hash a) (:content-hash b)))
      (is (= 8 (count (:content-hash a)))
          "8-char hex"))))

(deftest snapshot-identity-changes-with-args
  (testing "changing a variant's :args changes the hash"
    (story/reg-story :story.id-args
      {:component :app/v})
    (story/reg-variant :story.id-args/v {:args {:x 1} :events []})
    (let [h1 (-> (story/snapshot-identity :story.id-args/v) :content-hash)]
      (story/reg-variant :story.id-args/v {:args {:x 2} :events []})
      (let [h2 (-> (story/snapshot-identity :story.id-args/v) :content-hash)]
        (is (not= h1 h2))))))

(deftest snapshot-identity-changes-with-mode
  (testing "different active-modes produce different hashes"
    (story/reg-story :story.id-mode
      {:component :app/v :args {:theme :light}})
    (story/reg-mode :Mode.app/dark  {:args {:theme :dark}})
    (story/reg-mode :Mode.app/light {:args {:theme :light}})
    (story/reg-variant :story.id-mode/v {:events []})
    (let [hd (-> (story/snapshot-identity :story.id-mode/v
                                          {:active-modes [:Mode.app/dark]})
                 :content-hash)
          hl (-> (story/snapshot-identity :story.id-mode/v
                                          {:active-modes [:Mode.app/light]})
                 :content-hash)]
      (is (not= hd hl)))))

(deftest snapshot-identity-changes-with-substrate
  (testing "different substrate produces different hash"
    (story/reg-story :story.id-sub
      {:component :app/v})
    (story/reg-variant :story.id-sub/v {:events []})
    (let [hr (-> (story/snapshot-identity :story.id-sub/v {:substrate :reagent})
                 :content-hash)
          hu (-> (story/snapshot-identity :story.id-sub/v {:substrate :uix})
                 :content-hash)]
      (is (not= hr hu)))))

(deftest snapshot-identity-changes-with-variant-decorators
  (testing "Per spec/007 §Variant snapshot identity (lines 424-429) — a
            variant-level :decorators change MUST perturb the content-hash.
            Closes rf2-9g48l: watch-mode auto-rerun keys off this identity,
            so a decorator-only edit was silently dropped before this fix."
    (story/reg-decorator :centered
      {:kind :hiccup
       :wrap (fn [body _args] [:div.centered body])})
    (story/reg-decorator :boxed
      {:kind :hiccup
       :wrap (fn [body _args] [:div.boxed body])})
    (story/reg-story :story.id-dec
      {:component :app/v})
    (story/reg-variant :story.id-dec/v
      {:events     []
       :decorators [[:centered]]})
    (let [h1 (-> (story/snapshot-identity :story.id-dec/v) :content-hash)]
      (story/reg-variant :story.id-dec/v
        {:events     []
         :decorators [[:boxed]]})
      (let [h2 (-> (story/snapshot-identity :story.id-dec/v) :content-hash)]
        (is (not= h1 h2)
            "swapping the variant's decorator must produce a fresh hash"))
      (testing "adding a decorator to a previously-decoratorless variant also perturbs the hash"
        (story/reg-variant :story.id-dec/v
          {:events     []
           :decorators []})
        (let [h-empty (-> (story/snapshot-identity :story.id-dec/v) :content-hash)]
          (story/reg-variant :story.id-dec/v
            {:events     []
             :decorators [[:centered]]})
          (let [h-with (-> (story/snapshot-identity :story.id-dec/v) :content-hash)]
            (is (not= h-empty h-with)
                "appending a decorator must produce a fresh hash")))))))

(deftest snapshot-identity-changes-with-view-schema-digest
  (testing "Per spec/007 §Variant snapshot identity (line 429) — the
            *registered* schema digest of the view (per spec/011
            §:rf/schema-digest) participates in the hash. A schema change
            on the view MUST invalidate the snapshot identity (and the
            visual-regression baseline keyed off it). Closes rf2-9g48l:
            the digest is sourced via the `:schemas/app-schemas-digest`
            late-bind hook so identity.cljc does NOT statically :require
            the schemas artefact."
    (story/reg-story :story.id-sd
      {:component :app/v})
    (story/reg-variant :story.id-sd/v {:events []})
    (let [prior (late-bind/get-fn :schemas/app-schemas-digest)]
      (try
        ;; Simulate a registered schema by installing a hook with a
        ;; fixed digest value.
        (late-bind/set-fn! :schemas/app-schemas-digest
                           (fn [] "sha256:0000000000000001"))
        (let [h1 (-> (story/snapshot-identity :story.id-sd/v) :content-hash)]
          ;; Now simulate a schema change by mutating the hook's
          ;; return value. The framework actually re-installs the hook
          ;; each time schemas mutate; we model that here.
          (late-bind/set-fn! :schemas/app-schemas-digest
                             (fn [] "sha256:0000000000000002"))
          (let [h2 (-> (story/snapshot-identity :story.id-sd/v) :content-hash)]
            (is (not= h1 h2)
                "a view schema-digest change must produce a fresh hash")))
        (testing "when the schemas artefact is absent (no hook registered),
                  the digest slot is nil and the hash is still stable"
          (late-bind/set-fn! :schemas/app-schemas-digest nil)
          (let [a (-> (story/snapshot-identity :story.id-sd/v) :content-hash)
                b (-> (story/snapshot-identity :story.id-sd/v) :content-hash)]
            (is (= a b)
                "absent-hook path must be deterministic across calls")))
        (finally
          (late-bind/set-fn! :schemas/app-schemas-digest prior))))))

(deftest snapshot-identity-canonical-key-stable
  (testing "the canonical-form key :rf/snapshot-canonical-v1 is included"
    (let [canon (ident/canonical-form [:rf/snapshot-canonical-v1 :x])]
      (is (some? canon)))
    (is (= (ident/content-hash {:a 1 :b 2})
           (ident/content-hash {:b 2 :a 1}))
        "map key order doesn't affect the hash")))

;; ===========================================================================
;; LIFECYCLE STATE MACHINE
;; ===========================================================================

(deftest lifecycle-machine-registered
  (testing "the lifecycle machine is registered after install-canonical-vocabulary!"
    (is (some (set [loaders/lifecycle-machine-id]) (machines/machines)))))

(deftest lifecycle-transitions-pre-mount-to-ready
  (testing "the lifecycle progresses through every documented state"
    ;; rf2-043cm — `:loaders` keeps `allocate!` on the classical
    ;; four-phase route (`:pre-mount → :mounting → :loading → :ready`).
    ;; The events-only fast-path (`:pre-mount → :ready`) is exercised
    ;; separately by `lifecycle-events-only-fast-path-to-ready` /
    ;; `events-only-variant-classifier` below.
    (rf/reg-event-db :test/noop (fn [db _] db))
    (story/reg-variant :story.life/v {:events [] :loaders [[:test/noop]]})
    (let [r       (story/resolve-decorators :story.life/v)]
      (frames/allocate! :story.life/v r)
      (is (= :mounting (loaders/current-state :story.life/v)))
      (loaders/start-loaders! :story.life/v)
      (is (= :loading (loaders/current-state :story.life/v)))
      (loaders/finish-loaders! :story.life/v)
      (is (= :ready (loaders/current-state :story.life/v)))
      (frames/destroy! :story.life/v))))

(deftest lifecycle-mirror-to-friendly-path
  (testing "the discrete state is mirrored to [:rf.story/lifecycle]"
    ;; rf2-043cm — `:loaders` keeps the classical four-phase route so
    ;; the test reaches `:loading`.
    (rf/reg-event-db :test/noop (fn [db _] db))
    (story/reg-variant :story.mirror/v {:loaders [[:test/noop]]})
    (let [r (story/resolve-decorators :story.mirror/v)]
      (frames/allocate! :story.mirror/v r)
      (loaders/start-loaders! :story.mirror/v)
      (let [db (rf/get-frame-db :story.mirror/v)]
        (is (= :loading (:rf.story/lifecycle db))))
      (frames/destroy! :story.mirror/v))))

(deftest lifecycle-watcher-fires-on-transitions
  (testing "watch-variant callbacks see every transition"
    ;; rf2-043cm — `:loaders` keeps the classical four-phase route so
    ;; watchers observe the full transition cascade.
    (rf/reg-event-db :test/noop (fn [db _] db))
    (story/reg-variant :story.watch/v {:loaders [[:test/noop]]})
    (let [transitions (atom [])
          unsubscribe (story/watch-variant
                        :story.watch/v
                        (fn [t] (swap! transitions conj t)))
          r           (story/resolve-decorators :story.watch/v)]
      (frames/allocate! :story.watch/v r)
      (loaders/start-loaders! :story.watch/v)
      (loaders/finish-loaders! :story.watch/v)
      (is (= [:pre-mount :mounting :loading]
             (mapv :from @transitions)))
      (is (= [:mounting :loading :ready]
             (mapv :to @transitions)))
      (unsubscribe)
      (frames/destroy! :story.watch/v))))

;; rf2-043cm — events-only fast-path coverage.
;;
;; A variant declaring `:events` only (no `:loaders`, no `:frame-setup`
;; decorators, no `:loaders-complete-when`) has nothing to wait for
;; between mount and render. The runtime's `frames/allocate!` selects
;; the fast-path branch (`loaders/mount-ready!`) which drives the
;; lifecycle machine from `:pre-mount` directly to `:ready` in a
;; single transition — never visiting `:mounting` or `:loading`.
;;
;; This pins:
;; 1. The classifier `loaders/events-only-variant?` returns true for
;;    the events-only shape and false for any of the four shapes that
;;    bind loader-style work.
;; 2. `frames/allocate!` against an events-only body lands directly
;;    in `:ready`.
;; 3. A `watch-variant` callback receives ONE transition
;;    (`:pre-mount → :ready`), not the three the classical path
;;    fires (`:pre-mount → :mounting`, `:mounting → :loading`,
;;    `:loading → :ready`).
;; 4. `run-variant` against an events-only body resolves to a result
;;    map whose `:lifecycle` is `:ready` and whose `:assertions` is
;;    empty (no `:rf.error/loader-incomplete` projection).
;; 5. Calling `start-loaders!` against a frame already at `:ready`
;;    is a benign no-op — the machine has no `:loaders-started`
;;    transition out of `:ready`, so the state stays `:ready`.

(deftest events-only-variant-classifier
  (testing "loaders/events-only-variant? — true for the events-only
            shape; false for any body / decorator-stack that binds
            loader work"
    (is (true?  (loaders/events-only-variant? {:events [[:x]]} {}))
        "no :loaders, no :frame-setup, no :loaders-complete-when → events-only")
    (is (true?  (loaders/events-only-variant? {} {}))
        "empty body → events-only (nothing to wait for)")
    (is (false? (loaders/events-only-variant? {:loaders [[:l]]} {}))
        "presence of :loaders → not events-only")
    (is (false? (loaders/events-only-variant? {:loaders-complete-when :p?} {}))
        "presence of :loaders-complete-when → not events-only")
    (is (false? (loaders/events-only-variant? {} {:frame-setup [{:body {}}]}))
        "presence of :frame-setup decorators → not events-only")
    (is (true?  (loaders/events-only-variant? {:play [[:assert]]} {}))
        ":play does not gate the lifecycle (runs strictly after :ready)")
    (is (true?  (loaders/events-only-variant? {} {:hiccup    [{:body {}}]
                                                  :fx-override [{:body {}}]}))
        ":hiccup + :fx-override decorators don't drive the lifecycle machine")))

(deftest lifecycle-events-only-fast-path-to-ready
  (testing "rf2-043cm — an events-only variant's frame allocation
            drives the lifecycle from :pre-mount directly to :ready
            in a single transition. The skeleton (rf2-0s4p1) reads
            `:ready` immediately and never engages."
    (story/reg-variant :story.eo.fast/v {:events []})
    (let [r (story/resolve-decorators :story.eo.fast/v)]
      (is (= :pre-mount (loaders/current-state :story.eo.fast/v))
          "before allocate the snapshot reads the initial state")
      (frames/allocate! :story.eo.fast/v r)
      (is (= :ready (loaders/current-state :story.eo.fast/v))
          "after allocate the lifecycle is :ready — no :mounting / :loading")
      (frames/destroy! :story.eo.fast/v))))

(deftest lifecycle-events-only-watcher-sees-single-transition
  (testing "rf2-043cm — a watcher registered before allocate observes
            ONE transition (:pre-mount → :ready) for events-only
            variants, not the three the classical path fires"
    (story/reg-variant :story.eo.watch/v {:events []})
    (let [transitions (atom [])
          unsub       (story/watch-variant
                        :story.eo.watch/v
                        (fn [t] (swap! transitions conj t)))
          r           (story/resolve-decorators :story.eo.watch/v)]
      (frames/allocate! :story.eo.watch/v r)
      (is (= 1 (count @transitions))
          "exactly one transition fired")
      (is (= {:from :pre-mount :to :ready}
             (select-keys (first @transitions) [:from :to]))
          "the single transition was :pre-mount → :ready")
      (is (= [:rf.story.lifecycle/mount-ready]
             (:event (first @transitions)))
          "the firing event was :mount-ready (the rf2-043cm fast-path)")
      (unsub)
      (frames/destroy! :story.eo.watch/v))))

(deftest lifecycle-events-only-run-variant-lands-ready
  (testing "rf2-043cm — `run-variant` against an events-only body
            resolves to a result whose :lifecycle is :ready and whose
            :assertions vector is empty (no loader-incomplete projection)"
    (rf/reg-event-db :test/seed (fn [db _] (assoc db :seeded? true)))
    (story/reg-variant :story.eo.run/v {:events [[:test/seed]]})
    (let [r (async/deref-blocking (story/run-variant :story.eo.run/v) 5000)]
      (is (= :ready (:lifecycle r))
          "the events-only variant lands :ready")
      (is (true? (:seeded? (:app-db r)))
          "events still dispatched after the fast-path mount")
      (is (empty? (:assertions r))
          "no `:rf.error/loader-incomplete` projection on the fast-path"))
    (story/destroy-variant! :story.eo.run/v)))

(deftest lifecycle-start-loaders-from-ready-is-noop
  (testing "rf2-043cm — `start-loaders!` against a frame already at
            :ready (an events-only variant) is a benign no-op. The
            :ready node has no transition out for :loaders-started so
            the discrete state stays :ready."
    (story/reg-variant :story.eo.idem/v {:events []})
    (let [r (story/resolve-decorators :story.eo.idem/v)]
      (frames/allocate! :story.eo.idem/v r)
      (is (= :ready (loaders/current-state :story.eo.idem/v)))
      (loaders/start-loaders! :story.eo.idem/v)
      (is (= :ready (loaders/current-state :story.eo.idem/v))
          ":ready is terminal-for-mount; :loaders-started doesn't transition out")
      (loaders/finish-loaders! :story.eo.idem/v)
      (is (= :ready (loaders/current-state :story.eo.idem/v))
          ":loaders-complete also a no-op against :ready")
      (frames/destroy! :story.eo.idem/v))))

;; ===========================================================================
;; RUN-VARIANT END-TO-END
;; ===========================================================================

(deftest run-variant-basic
  (testing "run-variant returns a future of the result map"
    (rf/reg-event-db :test/inc
      (fn [db _] (update db :counter (fnil inc 0))))
    (story/reg-variant :story.run/v
      {:events [[:test/inc] [:test/inc]]})
    (let [fut (story/run-variant :story.run/v)
          r   (async/deref-blocking fut 5000)]
      (is (= :story.run/v        (:frame r)))
      (is (= :ready              (:lifecycle r)))
      (is (= 2                   (:counter (:app-db r))))
      (is (number?               (:elapsed-ms r)))
      (is (= :story.run/v        (-> r :snapshot :variant-id)))
      (is (string?               (-> r :snapshot :content-hash))))
    (story/destroy-variant! :story.run/v)))

(deftest run-variant-with-loaders-and-events
  (testing "run-variant drains loaders before events"
    (rf/reg-event-db :test/load
      (fn [db _] (assoc db :loaded? true)))
    (rf/reg-event-db :test/use
      (fn [db _]
        (assoc db :used-loaded? (boolean (:loaded? db)))))
    (story/reg-variant :story.flow/v
      {:loaders [[:test/load]]
       :events  [[:test/use]]})
    (let [r (async/deref-blocking (story/run-variant :story.flow/v) 5000)]
      (is (true? (-> r :app-db :loaded?)))
      (is (true? (-> r :app-db :used-loaded?))
          "events ran AFTER loaders"))
    (story/destroy-variant! :story.flow/v)))

(deftest run-variant-blocks-events-when-loaders-incomplete
  (testing "a false loaders-complete-when predicate keeps the variant in :loading and skips events/play"
    (rf/reg-event-db :test/not-ready?
      (fn [db _]
        (assoc db :rf.story/loaders-complete? false)))
    (rf/reg-event-db :test/load-but-not-ready
      (fn [db _] (assoc db :loaded? true)))
    (rf/reg-event-db :test/should-not-run
      (fn [db _] (assoc db :events-ran? true)))
    (story/reg-variant :story.flow/blocked
      {:loaders               [[:test/load-but-not-ready]]
       :loaders-complete-when :test/not-ready?
       :events                [[:test/should-not-run]]
       :play                  [[:rf.assert/path-equals [:events-ran?] true]]})
    (let [r (async/deref-blocking (story/run-variant :story.flow/blocked) 5000)
          incomplete (->> (:assertions r)
                          (filter #(= :rf.error/loader-incomplete (:assertion %)))
                          first)]
      (is (= :loading (:lifecycle r))
          "the lifecycle remains in the loader phase")
      (is (true? (-> r :app-db :loaded?))
          "loader events still run")
      (is (not (contains? (:app-db r) :events-ran?))
          "normal events are blocked until loaders complete")
      (is (some? incomplete)
          "the failure projection is explicit instead of a quiet hang")
      (is (= :phase-1-loaders (:phase incomplete)))
      (is (= 1 (count (:assertions r)))
          "play assertions are skipped because the variant never became ready"))
    (story/destroy-variant! :story.flow/blocked)))

(deftest run-variant-frame-setup-decorator
  (testing ":frame-setup decorators fire :init events before loaders"
    (rf/reg-event-db :test/mock-init
      (fn [db _] (assoc db :mock {:user "alice"})))
    (rf/reg-event-db :test/observe
      (fn [db _] (assoc db :observed-mock (:mock db))))
    (story/reg-decorator :mock-frame
      {:kind :frame-setup
       :init [[:test/mock-init]]})
    (story/reg-variant :story.fs/v
      {:decorators [[:mock-frame]]
       :events     [[:test/observe]]})
    (let [r (async/deref-blocking (story/run-variant :story.fs/v) 5000)]
      (is (= {:user "alice"} (-> r :app-db :observed-mock))
          ":init events ran before :events; observe saw the mock"))
    (story/destroy-variant! :story.fs/v)))

(deftest run-variant-unknown-variant
  (testing "run-variant of an unregistered variant produces an error result"
    (let [r (async/deref-blocking (story/run-variant :story.nope/x) 5000)]
      (is (= :error (:lifecycle r)))
      (is (= :rf.error/unknown-variant
             (-> r :assertions first :assertion))))))

(deftest reset-variant-tears-down-then-runs-fresh
  (testing "reset-variant produces a fresh app-db"
    (rf/reg-event-db :test/inc
      (fn [db _] (update db :counter (fnil inc 0))))
    (story/reg-variant :story.reset/v
      {:events [[:test/inc]]})
    (let [r1 (async/deref-blocking (story/run-variant :story.reset/v) 5000)
          r2 (async/deref-blocking (story/reset-variant :story.reset/v) 5000)]
      (is (= 1 (:counter (:app-db r1))))
      (is (= 1 (:counter (:app-db r2)))
          "reset gives a fresh frame; counter starts again at 0 then increments to 1"))
    (story/destroy-variant! :story.reset/v)))

;; ===========================================================================
;; FRAME-META INTROSPECTION
;; ===========================================================================

(deftest variant-frames-marked
  (testing "variant frames carry :rf/story? + :rf/variant on their config"
    (story/reg-variant :story.fm/v {:events []})
    (let [r (story/resolve-decorators :story.fm/v)]
      (frames/allocate! :story.fm/v r)
      (let [m (rf/frame-meta :story.fm/v)]
        (is (true?              (:rf/story? m)))
        (is (= :story.fm/v      (:rf/variant m)))
        (is (= :story           (:preset m))))
      (is (contains? (story/variant-frames) :story.fm/v))
      (is (true? (story/variant-frame? :story.fm/v)))
      (frames/destroy! :story.fm/v))))

;; ===========================================================================
;; ERROR PROJECTION
;; ===========================================================================

(deftest event-throwing-projects-as-assertion
  (testing "a thrown exception during :events lands in :assertions"
    (rf/reg-event-db :test/boom
      (fn [_ _] (throw (ex-info "bang" {:why :test}))))
    (story/reg-variant :story.err/v
      {:events [[:test/boom]]})
    (let [r (async/deref-blocking (story/run-variant :story.err/v) 5000)]
      ;; Phase-2 errors don't roll back the lifecycle; :ready is the
      ;; terminal state per IMPL-SPEC §5.5 — we record and continue.
      (is (some #(= :rf.error/exception (:assertion %)) (:assertions r))
          "an exception assertion was recorded")
      (is (some #(= :phase-2-events (:phase %)) (:assertions r))))
    (story/destroy-variant! :story.err/v)))

;; ===========================================================================
;; CONFIGURE!
;; ===========================================================================

(deftest configure-sets-global-args
  (testing "configure! writes the global-args layer"
    (story/configure! {:rf.story/global-args {:theme :dark}})
    (is (= {:theme :dark} (config/get-global-args)))
    (story/reg-variant :story.cfg/v {:events []})
    (let [r (story/resolve-args :story.cfg/v)]
      (is (= :dark (:theme r))))))

(deftest configure-sets-editor-preference
  (testing "configure! writes the :rf.story/editor preference (rf2-evgf5)"
    ;; Default is :vscode.
    (config/set-editor! :vscode)
    (is (= :vscode (config/get-editor)))
    (story/configure! {:rf.story/editor :cursor})
    (is (= :cursor (config/get-editor)))
    (story/configure! {:rf.story/editor :idea})
    (is (= :idea (config/get-editor)))
    (story/configure! {:rf.story/editor {:custom "zed://file/{path}:{line}"}})
    (is (= {:custom "zed://file/{path}:{line}"} (config/get-editor)))
    ;; Reset for downstream tests.
    (config/set-editor! :vscode))
  (testing "configure! with no :rf.story/editor leaves the preference untouched"
    (config/set-editor! :cursor)
    (story/configure! {:rf.story/global-args {:theme :dark}})
    (is (= :cursor (config/get-editor)))
    (config/set-editor! :vscode)))

(deftest configure-sets-project-root
  (testing "configure! writes the :rf.story/project-root config slot (rf2-zfy1e)"
    ;; Default is nil — no prefix applied to source-coord files.
    (config/set-project-root! nil)
    (is (nil? (config/get-project-root)))
    (story/configure! {:rf.story/project-root "C:/Users/me/code/my-app"})
    (is (= "C:/Users/me/code/my-app" (config/get-project-root)))
    (story/configure! {:rf.story/project-root "/abs/code"})
    (is (= "/abs/code" (config/get-project-root)))
    ;; Explicit nil clears the slot.
    (story/configure! {:rf.story/project-root nil})
    (is (nil? (config/get-project-root))))
  (testing "configure! with no :rf.story/project-root key leaves the slot untouched"
    (config/set-project-root! "/abs/code")
    (story/configure! {:rf.story/global-args {:theme :dark}})
    (is (= "/abs/code" (config/get-project-root)))
    ;; Reset for downstream tests.
    (config/set-project-root! nil))
  (testing "set-project-root! normalises blank strings to nil"
    (config/set-project-root! "")
    (is (nil? (config/get-project-root)))
    (config/set-project-root! "/abs/code")
    (is (= "/abs/code" (config/get-project-root)))
    (config/set-project-root! nil)))

;; ===========================================================================
;; ASYNC ABSTRACTION
;; ===========================================================================

(deftest async-resolved-and-then
  (testing "async/resolved produces a complete future"
    (is (= 42 (async/deref-blocking (async/resolved 42) 1000)))))

(deftest async-then-chains
  (testing "async/then chains over a resolved promise"
    (is (= 43
           (async/deref-blocking
             (async/then (async/resolved 42) inc)
             1000)))))

(deftest async-rejected-catch
  (testing "async/catch* recovers a rejection"
    (let [recovered (async/catch* (async/rejected (ex-info "no" {}))
                                  (fn [_] :recovered))]
      (is (= :recovered (async/deref-blocking recovered 1000))))))

(deftest async-promise-resolver
  (testing "async/promise's resolver completes the future"
    (let [p (async/promise (fn [resolve] (resolve :ok)))]
      (is (= :ok (async/deref-blocking p 1000))))))

;; ===========================================================================
;; PUBLIC API STABILITY
;; ===========================================================================

(deftest public-api-surface
  (testing "every Stage 3 IMPL-SPEC §3.2 fn is present on the public ns"
    (is (fn? @#'story/run-variant))
    (is (fn? @#'story/reset-variant))
    (is (fn? @#'story/watch-variant))
    (is (fn? @#'story/snapshot-identity))
    (is (fn? @#'story/destroy-variant!))
    (is (fn? @#'story/configure!))
    (is (fn? @#'story/resolve-args))
    (is (fn? @#'story/resolve-decorators))
    (is (fn? @#'story/lifecycle-state))
    (is (fn? @#'story/variant-frames))
    (is (fn? @#'story/variant-frame?))))
