(ns re-frame.story-runtime-test
  "JVM tests for re-frame2-story Stage 3 (rf2-von3) — runtime.

  Covers:

  - Args precedence: global < story < mode < variant < cell-overrides
    with deep-merge on nested maps and replace on vectors.
  - Decorator composition: classification by `:kind`, ordering (story
    decorators before variant decorators), hiccup wrap composition,
    fx-override registration.
  - Snapshot-identity: stability across re-runs; sensitivity to
    every input axis per `002-Runtime.md` §Snapshot-identity computation.
  - Lifecycle state machine: `:pre-mount → :mounting → :loading →
    :ready` via runtime fns + watcher firing.
  - `run-variant` end-to-end: registered variant → frame allocated →
    events drained → result map populated.
  - Frame teardown via `destroy-variant!`.
  - Error projection per `002-Runtime.md` §Error projection.

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
            [re-frame.story.fingerprint :as fp]
            [re-frame.story.frames    :as frames]
            [re-frame.story.identity  :as ident]
            [re-frame.story.loaders   :as loaders]
            [re-frame.story.runtime   :as runtime]
            ;; EP-0023 behaviour-variant image fixtures (rf2-fpr0b5): two
            ;; namespaces register the SAME event id with DIFFERENT meanings;
            ;; a variant's `:images` `:select-ns` selects one or the other.
            [story.test-helpers.image-behaviour-v1]
            [story.test-helpers.image-behaviour-v2]))

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
  ;; Re-require machines so its framework `:rf/machine` runtime-db sub
  ;; (EP-0001: reads `[:rf.runtime/machines :snapshots <id>]`) survives the
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

(deftest snapshot-identity-distinct-modes-same-args
  (testing "two DISTINCT modes registering IDENTICAL args still produce
            DIFFERENT snapshot hashes — proving the top-level :active-modes
            slot is load-bearing, not vacuously covered by :effective-args
            (rf2-oy4c9 / guards the rf2-z86vu 'do not simplify away' slot).
            Contrast snapshot-identity-changes-with-mode, whose two modes
            carry DIFFERENT args, so its hash difference is explained by
            :effective-args alone and survives deleting :active-modes."
    (story/reg-story :story.id-mode-same
      {:component :app/v :args {:theme :light}})
    ;; Two distinct mode IDS with byte-for-byte IDENTICAL :args.
    (story/reg-mode :Mode.app/a {:args {:theme :dark}})
    (story/reg-mode :Mode.app/b {:args {:theme :dark}})
    (story/reg-variant :story.id-mode-same/v {:events []})
    (let [args-a (args/resolve-args :story.id-mode-same/v
                                    {:active-modes [:Mode.app/a]})
          args-b (args/resolve-args :story.id-mode-same/v
                                    {:active-modes [:Mode.app/b]})
          ha (-> (story/snapshot-identity :story.id-mode-same/v
                                          {:active-modes [:Mode.app/a]})
                 :content-hash)
          hb (-> (story/snapshot-identity :story.id-mode-same/v
                                          {:active-modes [:Mode.app/b]})
                 :content-hash)]
      ;; Precondition: the args path CANNOT explain the difference.
      (is (= args-a args-b)
          "the two modes resolve identical :effective-args")
      ;; The whole point: the mode id set IS identity-bearing. This fails
      ;; if the :active-modes top-level slot is removed from snapshot-tuple.
      (is (not= ha hb)
          "distinct modes with identical args still hash differently")
      ;; Direct slot witness — the snapshot-tuple keeps the mode-id set.
      (is (not= (:active-modes (ident/snapshot-tuple :story.id-mode-same/v
                                                     {:active-modes [:Mode.app/a]}))
                (:active-modes (ident/snapshot-tuple :story.id-mode-same/v
                                                     {:active-modes [:Mode.app/b]})))
          "the :active-modes slot distinguishes the two contexts"))))

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
  (testing "Per /spec/007-Stories.md §Variant snapshot identity — a
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

(deftest snapshot-identity-changes-with-play-script
  (testing "Per /spec/007-Stories.md §Variant snapshot identity — a variant-level
            :play-script change MUST perturb the content-hash. Closes
            rf2-bgwnf: variant-body-slice selected the legacy :play key
            (removed by rf2-0wrud), so play-script edits were silently
            dropped from the hash — breaking watch-mode auto-rerun and
            visual-regression baseline invalidation."
    (story/reg-story :story.id-ps
      {:component :app/v})
    (story/reg-variant :story.id-ps/v
      {:events      []
       :play-script [[:dispatch-sync [:rf.assert/path-equals [:n] 1]]]})
    (let [h1 (-> (story/snapshot-identity :story.id-ps/v) :content-hash)]
      (story/reg-variant :story.id-ps/v
        {:events      []
         :play-script [[:dispatch-sync [:rf.assert/path-equals [:n] 2]]]})
      (let [h2 (-> (story/snapshot-identity :story.id-ps/v) :content-hash)]
        (is (not= h1 h2)
            "editing the play-script must produce a fresh hash"))
      (testing "adding a play-script to a previously play-less variant also perturbs the hash"
        (story/reg-variant :story.id-ps/v {:events []})
        (let [h-none (-> (story/snapshot-identity :story.id-ps/v) :content-hash)]
          (story/reg-variant :story.id-ps/v
            {:events      []
             :play-script [[:dispatch-sync [:rf.assert/path-equals [:n] 1]]]})
          (let [h-with (-> (story/snapshot-identity :story.id-ps/v) :content-hash)]
            (is (not= h-none h-with)
                "appending a play-script must produce a fresh hash")))))))

(deftest snapshot-identity-changes-with-plays
  (testing "Per /spec/007-Stories.md §Variant snapshot identity — the multi-play
            :plays surface (rf2-tl7zk) also participates in the hash.
            Companion to rf2-bgwnf: both play surfaces (:play-script and
            :plays) must perturb snapshot identity."
    (story/reg-story :story.id-plays
      {:component :app/v})
    (story/reg-variant :story.id-plays/v
      {:events []
       :plays  [{:name "happy" :script [[:dispatch-sync [:rf.assert/path-equals [:n] 1]]]}]})
    (let [h1 (-> (story/snapshot-identity :story.id-plays/v) :content-hash)]
      (story/reg-variant :story.id-plays/v
        {:events []
         :plays  [{:name "happy" :script [[:dispatch-sync [:rf.assert/path-equals [:n] 2]]]}]})
      (let [h2 (-> (story/snapshot-identity :story.id-plays/v) :content-hash)]
        (is (not= h1 h2)
            "editing a play's script must produce a fresh hash")))))

(deftest snapshot-identity-changes-with-view-schema-digest
  (testing "Per /spec/007-Stories.md §Variant snapshot identity — the
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
        ;; fixed digest value. EP-0002 (rf2-bd4div) — the digest is
        ;; frame-local, so `view-schema-digest` now invokes the hook with
        ;; the variant's TARGET frame-id (no ambient resolution). The stub
        ;; takes (and ignores) that frame-id arg, matching the real
        ;; `app-schemas-digest` hook's keyword-frame-id arity.
        (late-bind/set-fn! :schemas/app-schemas-digest
                           (fn [_frame-id] "sha256:0000000000000001"))
        (let [h1 (-> (story/snapshot-identity :story.id-sd/v) :content-hash)]
          ;; Now simulate a schema change by mutating the hook's
          ;; return value. The framework actually re-installs the hook
          ;; each time schemas mutate; we model that here.
          (late-bind/set-fn! :schemas/app-schemas-digest
                             (fn [_frame-id] "sha256:0000000000000002"))
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
  (testing "the canonical-version tag canonicalises and map key order is
            hash-stable"
    (let [canon (fp/canonical-form [fp/canonical-version :x])]
      (is (some? canon)))
    (is (= (fp/content-hash {:a 1 :b 2})
           (fp/content-hash {:b 2 :a 1}))
        "map key order doesn't affect the hash")))

(deftest snapshot-tuple-canonical-slot-tracks-fingerprint-version
  ;; rf2-e8hgr — doc↔code drift guard. The snapshot tuple's
  ;; `:rf/snapshot-canonical` slot is NOT an independently-versioned
  ;; marker: it reads its value straight from the single source of truth,
  ;; `fingerprint/canonical-version`. This locks them together so a future
  ;; canonical-version bump cannot leave the tuple slot pinned to a stale
  ;; literal (the v1/v2 drift this bead fixed).
  (testing "the tuple's :rf/snapshot-canonical slot equals fingerprint/canonical-version"
    (story/reg-story :story.id-canon {:component :app/c})
    (story/reg-variant :story.id-canon/v {:events []})
    (is (= fp/canonical-version
           (:rf/snapshot-canonical (ident/snapshot-tuple :story.id-canon/v)))
        "the snapshot tuple stamps the live canonical-version, not a literal")))

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
    (rf/reg-event :test/noop (fn [{:keys [db]} _] {:db db}))
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
    (rf/reg-event :test/noop (fn [{:keys [db]} _] {:db db}))
    (story/reg-variant :story.mirror/v {:loaders [[:test/noop]]})
    (let [r (story/resolve-decorators :story.mirror/v)]
      (frames/allocate! :story.mirror/v r)
      (loaders/start-loaders! :story.mirror/v)
      (let [db (rf/app-db-value :story.mirror/v)]
        (is (= :loading (:rf.story/lifecycle db))))
      (frames/destroy! :story.mirror/v))))

(deftest lifecycle-watcher-fires-on-transitions
  (testing "watch-variant callbacks see every transition"
    ;; rf2-043cm — `:loaders` keeps the classical four-phase route so
    ;; watchers observe the full transition cascade.
    (rf/reg-event :test/noop (fn [{:keys [db]} _] {:db db}))
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
    (is (true?  (loaders/events-only-variant? {:play-script [[:dispatch-sync [:assert]]]} {}))
        ":play-script does not gate the lifecycle (runs strictly after :ready)")
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
    (rf/reg-event :test/seed (fn [{:keys [db]} _] {:db (assoc db :seeded? true)}))
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
    (rf/reg-event :test/inc
      (fn [{:keys [db]} _] {:db (update db :counter (fnil inc 0))}))
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

;; ===========================================================================
;; EP-0023 BEHAVIOUR-VARIANT IMAGES (rf2-fpr0b5)
;; ===========================================================================

(deftest behaviour-variant-images-resolve-same-id-differently
  (testing "EP-0023 §Stories — two variants declaring DIFFERENT `:images`
            resolve the SAME event id `:img.counter/step` to DIFFERENT
            behaviour. The image's `:select-ns` selects which namespace's
            registration the variant frame resolves against; the runtime
            attaches the resolved generation via `rf/make-frame` in
            `allocate!`, and `process-event!`'s frame-resolution routes the
            dispatch through it. This is 'behavior variant -> image' end-to-end."
    ;; The fixture's `registrar/clear-all!` wipes the helper namespaces'
    ;; top-level `reg-event`s from the source store, so re-run their loads
    ;; before building the selecting images (a zero-match `:select-ns :include`
    ;; fails loud by design).
    (require 'story.test-helpers.image-behaviour-v1 :reload)
    (require 'story.test-helpers.image-behaviour-v2 :reload)
    ;; Variant A mounts under the v1 image (adds 1).
    (story/reg-variant :story.img/v1
      {:images [(rf/image {:id :img/behaviour-v1
                           :select-ns {:include ["story.test-helpers.image-behaviour-v1"]}})]
       :events [[:img.counter/step]]})
    ;; Variant B mounts under the v2 image (adds 100) — SAME event id.
    (story/reg-variant :story.img/v2
      {:images [(rf/image {:id :img/behaviour-v2
                           :select-ns {:include ["story.test-helpers.image-behaviour-v2"]}})]
       :events [[:img.counter/step]]})
    (let [ra (async/deref-blocking (story/run-variant :story.img/v1) 5000)
          rb (async/deref-blocking (story/run-variant :story.img/v2) 5000)]
      (is (= 1 (-> ra :app-db :n))
          "variant under the v1 image ran the add-one handler")
      (is (= :v1-add-one (-> ra :app-db :behaviour)))
      (is (= 100 (-> rb :app-db :n))
          "variant under the v2 image ran the add-hundred handler — SAME id,
           DIFFERENT behaviour, resolved through the variant frame's own image")
      (is (= :v2-add-hundred (-> rb :app-db :behaviour)))
      ;; item 4 — the result reports WHICH behaviour set ran.
      (is (= [:img/behaviour-v1] (:images ra))
          "the result surfaces the resolved behaviour-variant image ids")
      (is (= [:img/behaviour-v2] (:images rb))))
    (story/destroy-variant! :story.img/v1)
    (story/destroy-variant! :story.img/v2)))

(deftest behaviour-variant-image-ids-on-frame-meta
  (testing "EP-0023 — a behaviour variant's image ids land on frame-meta
            (`frames/variant-image-ids`); a state variant (no `:images`)
            reports none and resolves against the shared default registrar."
    (require 'story.test-helpers.image-behaviour-v1 :reload)
    (story/reg-variant :story.img/meta
      {:images [(rf/image {:id :img/behaviour-v1
                           :select-ns {:include ["story.test-helpers.image-behaviour-v1"]}})]
       :events [[:img.counter/step]]})
    (story/reg-variant :story.img/state-only
      {:events []})
    (frames/allocate! :story.img/meta (story/resolve-decorators :story.img/meta))
    (frames/allocate! :story.img/state-only (story/resolve-decorators :story.img/state-only))
    (is (= [:img/behaviour-v1] (frames/variant-image-ids :story.img/meta))
        "behaviour variant carries its image ids on frame-meta")
    (is (nil? (frames/variant-image-ids :story.img/state-only))
        "a state-only variant carries no :rf/images slot")
    (frames/destroy! :story.img/meta)
    (frames/destroy! :story.img/state-only)))

(deftest run-variant-with-loaders-and-events
  (testing "run-variant drains loaders before events"
    (rf/reg-event :test/load
      (fn [{:keys [db]} _] {:db (assoc db :loaded? true)}))
    (rf/reg-event :test/use
      (fn [{:keys [db]} _]
        {:db (assoc db :used-loaded? (boolean (:loaded? db)))}))
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
    (rf/reg-event :test/not-ready?
      (fn [{:keys [db]} _]
        {:db (assoc db :rf.story/loaders-complete? false)}))
    (rf/reg-event :test/load-but-not-ready
      (fn [{:keys [db]} _] {:db (assoc db :loaded? true)}))
    (rf/reg-event :test/should-not-run
      (fn [{:keys [db]} _] {:db (assoc db :events-ran? true)}))
    (story/reg-variant :story.flow/blocked
      {:loaders               [[:test/load-but-not-ready]]
       :loaders-complete-when :test/not-ready?
       :events                [[:test/should-not-run]]
       :play-script [[:dispatch-sync [:rf.assert/path-equals [:events-ran?] true]]]})
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
    (rf/reg-event :test/mock-init
      (fn [{:keys [db]} _] {:db (assoc db :mock {:user "alice"})}))
    (rf/reg-event :test/observe
      (fn [{:keys [db]} _] {:db (assoc db :observed-mock (:mock db))}))
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
    (rf/reg-event :test/inc
      (fn [{:keys [db]} _] {:db (update db :counter (fnil inc 0))}))
    (story/reg-variant :story.reset/v
      {:events [[:test/inc]]})
    (let [r1 (async/deref-blocking (story/run-variant :story.reset/v) 5000)
          r2 (async/deref-blocking (story/reset-variant :story.reset/v) 5000)]
      (is (= 1 (:counter (:app-db r1))))
      (is (= 1 (:counter (:app-db r2)))
          "reset gives a fresh frame; counter starts again at 0 then increments to 1"))
    (story/destroy-variant! :story.reset/v)))

;; ===========================================================================
;; PLAN-ROUTED RUNTIME (rf2-5x1wt.22 — §B8 Runtime Migration)
;;
;; The runtime now routes phase 2 (setup) and phase 4 (script) through the
;; normalized variant plan (`re-frame.story.plan`) rather than reading the
;; shipping `:events` / `:play-script` slots off the registered body. These
;; tests pin the migration's load-bearing behaviour: the PUBLIC `:setup` /
;; `:script` vocabulary runs, composed-fragment setup is executed in
;; phase 2 (a behaviour the pre-migration runtime did NOT deliver — it
;; ignored `:compose` entirely for setup), and named `:plays` remain
;; driven as named scripts.
;; ===========================================================================

(deftest run-variant-public-setup-and-script-vocabulary
  (testing "a variant authored with the PUBLIC :setup / :script keys runs
            through the plan-routed runtime (setup applied, script asserts)"
    (rf/reg-event :test/seed
      (fn [{:keys [db]} _] {:db (assoc db :seeded? true :count 0)}))
    (rf/reg-event :test/bump
      (fn [{:keys [db]} _] {:db (update db :count inc)}))
    (story/reg-variant :story.public/v
      {:setup  [[:test/seed]]
       :script [[:dispatch [:test/bump]]
                [:assert [:rf.assert/path-equals [:count] 1]]]})
    (let [r (async/deref-blocking (story/run-variant :story.public/v) 5000)]
      (is (= :ready (:lifecycle r)))
      (is (true? (-> r :app-db :seeded?))
          ":setup ran through the plan's [:world :setup]")
      (is (= 1 (-> r :app-db :count))
          ":script :dispatch ran through the plan's [:world :scripts]")
      (is (= :pass (:status r))
          "the in-script [:assert …] checkpoint passed")
      (let [pe (->> (:assertions r)
                    (filter #(= :rf.assert/path-equals (:assertion %)))
                    first)]
        (is (some? pe) "the checkpoint assertion was recorded")
        (is (true? (:passed? pe)))))
    (story/destroy-variant! :story.public/v)))

(deftest run-variant-composed-fragment-setup-runs-in-phase-2
  (testing "a :compose fragment's :setup is executed in phase 2 — the plan
            compiler resolves :compose, so fragment preconditions land in
            the frame (the pre-migration runtime ignored :compose for setup)"
    (rf/reg-event :test/frag-seed
      (fn [{:keys [db]} _] {:db (assoc db :from-fragment :alice)}))
    (rf/reg-event :test/observe-frag
      (fn [{:keys [db]} _] {:db (assoc db :observed (:from-fragment db))}))
    (story/reg-fragment :fragment.test/seeded
      {:setup [[:test/frag-seed]]})
    (story/reg-variant :story.compose/v
      {:compose [:fragment.test/seeded]
       :setup   [[:test/observe-frag]]})
    (let [r (async/deref-blocking (story/run-variant :story.compose/v) 5000)]
      (is (= :ready (:lifecycle r)))
      (is (= :alice (-> r :app-db :from-fragment))
          "the composed fragment's :setup ran")
      (is (= :alice (-> r :app-db :observed))
          "fragment setup APPENDS BEFORE the variant's own setup (the
           variant's observe step saw the fragment's seed)"))
    (story/destroy-variant! :story.compose/v)))

(deftest run-variant-named-plays-from-plan
  (testing "a variant's named :plays are driven as named scripts from the
            plan's [:world :scripts] (auto-run? default: first true, rest
            false — only the first auto-play executes on run)"
    (rf/reg-event :test/init-n
      (fn [{:keys [db]} [_ n]] {:db (assoc db :n n)}))
    (story/reg-variant :story.plays/v
      {:plays [{:name      "happy"
                :script    [[:dispatch-sync [:test/init-n 3]]
                            [:assert [:rf.assert/path-equals [:n] 3]]]}
               {:name      "edge"
                :auto-run? false
                :script    [[:dispatch-sync [:test/init-n 0]]
                            [:assert [:rf.assert/path-equals [:n] 0]]]}]})
    (let [r (async/deref-blocking (story/run-variant :story.plays/v) 5000)]
      (is (= :ready (:lifecycle r)))
      (is (= 3 (-> r :app-db :n))
          "only the first (auto-run?) play executed")
      (is (= :pass (:status r))
          "the first play's checkpoint passed"))
    (story/destroy-variant! :story.plays/v)))

;; ===========================================================================
;; TERMINAL ASSERTIONS AUTO-RUN (rf2-nyjoa — Mike RULED B)
;;
;; The terminal `:assertions` slot is the handler-backed "check the FINAL
;; settled state" surface. It AUTO-RUNS after the script phase settles and
;; contributes :pass / :fail verdicts recorded as assertion records on the
;; SAME `:rf.story/assertions` accumulator the in-script `[:assert …]`
;; checkpoints write — folded into the unified `:status` by
;; `result/run-result`. These pin the canonical reg-variant example (a
;; variant with ONLY a terminal `:assertions` block, no in-script
;; `[:assert]`), the pass + fail verdicts, and the no-double-processing
;; guarantee for the tape-evaluated kinds.
;; ===========================================================================

(deftest run-variant-terminal-assertions-only-pass
  (testing "the CANONICAL reg-variant example — a variant with ONLY a
            terminal :assertions block (no in-script [:assert], no :script)
            now AUTO-RUNS the terminal assertion against the FINAL settled
            state and produces a :pass verdict (rf2-nyjoa)"
    (rf/reg-event :test/seed-state
      (fn [{:keys [db]} _] {:db (assoc-in db [:checkout :state] :submitted)}))
    (story/reg-variant :story.nyjoa/pass
      {:setup      [[:test/seed-state]]
       :assertions [[:rf.assert/path-equals [:checkout :state] :submitted]]})
    (let [r (async/deref-blocking (story/run-variant :story.nyjoa/pass) 5000)]
      (is (= :ready (:lifecycle r)))
      (is (= :pass (:status r))
          "the terminal assertion auto-ran and passed → :pass")
      (let [rec (->> (:assertions r)
                     (filter #(= :rf.assert/path-equals (:assertion %)))
                     first)]
        (is (some? rec)
            "the terminal assertion was recorded on :rf.story/assertions
             with no in-script checkpoint authoring it")
        (is (true? (:passed? rec)))
        (is (= [[:checkout :state] :submitted] (:payload rec)))))
    (story/destroy-variant! :story.nyjoa/pass)))

(deftest run-variant-terminal-assertions-only-fail
  (testing "a FAILING terminal assertion (no in-script [:assert]) flips the
            unified verdict to :fail (rf2-nyjoa)"
    (rf/reg-event :test/seed-other
      (fn [{:keys [db]} _] {:db (assoc-in db [:checkout :state] :draft)}))
    (story/reg-variant :story.nyjoa/fail
      {:setup      [[:test/seed-other]]
       :assertions [[:rf.assert/path-equals [:checkout :state] :submitted]]})
    (let [r (async/deref-blocking (story/run-variant :story.nyjoa/fail) 5000)]
      (is (= :ready (:lifecycle r)))
      (is (= :fail (:status r))
          "the terminal assertion auto-ran and failed → :fail")
      (let [rec (->> (:assertions r)
                     (filter #(= :rf.assert/path-equals (:assertion %)))
                     first)]
        (is (some? rec))
        (is (false? (:passed? rec))
            "the failing terminal assertion recorded :passed? false")))
    (story/destroy-variant! :story.nyjoa/fail)))

(deftest run-variant-terminal-assertion-evaluates-final-state-after-script
  (testing "a terminal assertion evaluates the FINAL settled state — it sees
            the state AFTER the script's dispatches commit, not the
            pre-script state (rf2-nyjoa: terminal = check the FINAL state)"
    (rf/reg-event :test/set-n (fn [{:keys [db]} [_ n]] {:db (assoc db :n n)}))
    (story/reg-variant :story.nyjoa/after-script
      {:script     [[:dispatch [:test/set-n 7]]]
       :assertions [[:rf.assert/path-equals [:n] 7]]})
    (let [r (async/deref-blocking
              (story/run-variant :story.nyjoa/after-script) 5000)]
      (is (= 7 (-> r :app-db :n)) "the script dispatch committed")
      (is (= :pass (:status r))
          "the terminal assertion saw the post-script value 7"))
    (story/destroy-variant! :story.nyjoa/after-script)))

(deftest run-variant-terminal-tape-evaluated-not-double-counted
  (testing "a tape-evaluated terminal assertion (:rf.assert/schema-error) is
            NOT double-processed by the terminal auto-run — it carries no
            reg-event handler, so the auto-run records NO handler-backed
            record for it; the result boundary owns its single verdict
            against the epoch tape. Pinned alongside a handler-backed
            terminal assertion in the same block so the split is exercised
            (rf2-nyjoa critical guard)."
    (rf/reg-event :test/seed-ok (fn [{:keys [db]} _] {:db (assoc db :ok? true)}))
    (story/reg-variant :story.nyjoa/mixed
      {:setup      [[:test/seed-ok]]
       :assertions [[:rf.assert/path-equals [:ok?] true]
                    [:rf.assert/schema-error {:where :event :event :some/evt}]]})
    (let [r (async/deref-blocking (story/run-variant :story.nyjoa/mixed) 5000)
          path-recs (->> (:assertions r)
                         (filter #(= :rf.assert/path-equals (:assertion %))))
          schema-recs (->> (:assertions r)
                           (filter #(= :rf.assert/schema-error (:assertion %))))]
      (is (= 1 (count path-recs))
          "the handler-backed terminal assertion recorded EXACTLY ONE record
           (not double-counted)")
      (is (true? (:passed? (first path-recs))))
      (is (<= (count schema-recs) 1)
          "the schema-error expectation is minted at most ONCE — by the
           result boundary's tape matcher, NOT a second time by the terminal
           auto-run dispatching a (non-existent) handler"))
    (story/destroy-variant! :story.nyjoa/mixed)))

(deftest run-variant-plan-error-projects-as-run-error
  (testing "a plan-construction failure (an [:assert …] checkpoint placed
            in :setup) surfaces through the run-error projection rather
            than crashing the orchestrator"
    (rf/reg-event :test/noop (fn [{:keys [db]} _] {:db db}))
    (story/reg-variant :story.planerr/v
      {:setup [[:dispatch [:test/noop]]
               [:assert [:rf.assert/path-equals [:x] 1]]]})
    (let [r (async/deref-blocking (story/run-variant :story.planerr/v) 5000)]
      (is (= :error (:lifecycle r))
          "the plan-compile failure rolled the lifecycle to :error")
      (is (= :error (:status r))
          "the unified verdict is :error")
      (let [exc (->> (:assertions r)
                     (filter #(= :rf.error/story-assert-in-setup (:assertion %)))
                     first)]
        (is (some? exc)
            "the structured :rf.error/story-* id rides the assertion record")
        (is (false? (:passed? exc)))))
    (story/destroy-variant! :story.planerr/v)))

(deftest run-variant-non-dispatch-setup-step-is-refused
  (testing "a :setup carrying a non-dispatch step (e.g. [:wait …] / [:click …])
            is REFUSED at phase-2 with :rf.error/story-setup-step-unrunnable
            rather than SILENTLY DROPPED (rf2-zaiwl). The step is legal in
            :setup and lifts :required-runner to :dom/:cljs-reactive, but the
            headless runner cannot honour that boundary — so it fails closed
            (:cannot-run shape) instead of vanishing the precondition."
    (rf/reg-event :test/seed-z (fn [{:keys [db]} _] {:db (assoc db :seeded? true)}))
    (doseq [[vid bad-step] [[:story.zaiwl/wait  [:wait 100]]
                            [:story.zaiwl/click [:click "[data-test=open]"]]]]
      (story/reg-variant vid
        {:setup [[:dispatch [:test/seed-z]] bad-step]})
      (let [r (async/deref-blocking (story/run-variant vid) 5000)]
        (is (= :error (:lifecycle r))
            "the unrunnable setup step rolled the lifecycle to :error")
        (is (= :error (:status r))
            "the unified verdict is :error, not a vacuous green")
        (let [exc (->> (:assertions r)
                       (filter #(= :rf.error/story-setup-step-unrunnable
                                   (get-in % [:error :data :rf.error/id])))
                       first)]
          (is (some? exc)
              "the structured :rf.error/story-setup-step-unrunnable id rides
               the recorded exception record (not a silent drop)")
          (is (false? (:passed? exc)))
          (is (= [bad-step]
                 (get-in exc [:error :data :offending-steps]))
              "the refusal names the offending non-dispatch step")))
      (story/destroy-variant! vid))))

(deftest run-variant-legit-setup-steps-still-compile-and-run
  (testing "the legit setup shapes — a tagged [:dispatch …] AND a bare event
            vector (coerced to [:dispatch …]) — still run cleanly through
            phase 2 (rf2-zaiwl positive control: the refusal targets ONLY
            non-dispatch steps)"
    (rf/reg-event :test/seed-a (fn [{:keys [db]} _] {:db (assoc db :a true)}))
    (rf/reg-event :test/seed-b (fn [{:keys [db]} _] {:db (assoc db :b true)}))
    (story/reg-variant :story.zaiwl/ok
      {:setup [[:dispatch [:test/seed-a]]   ; tagged dispatch
               [:test/seed-b]]})            ; bare event vector → [:dispatch …]
    (let [r (async/deref-blocking (story/run-variant :story.zaiwl/ok) 5000)]
      (is (= :ready (:lifecycle r))
          "both legit setup shapes ran without refusal")
      (is (true? (-> r :app-db :a)) "the tagged [:dispatch …] setup step ran")
      (is (true? (-> r :app-db :b)) "the bare-event-vector setup step ran"))
    (story/destroy-variant! :story.zaiwl/ok)))

;; ---- plan-construction-error? discrimination (rf2-x3mol) -----------------
;;
;; PR #2430 (rf2-5x1wt.22) narrowed `plan-construction-error?` from the
;; over-broad "`:rf.error/id` present" to "`:where` = `'rf.story/variant-
;; plan`". The discrimination is load-bearing: a FRAMEWORK runtime error
;; thrown AFTER frame allocation that ALSO carries an `:rf.error/id` (the
;; cited case is `:rf.error/no-adapter-installed` from `reg-frame` →
;; `make-state-container` on a host with no adapter installed) MUST take
;; the frame-bound record/transition branch (`record-error!` +
;; `loaders/error!`), NOT `plan-error-result` (which would stamp the raw
;; `:rf.error/id` as the assertion id, misreporting a runtime failure as a
;; plan-construction failure). The pre-existing suite pinned only the
;; POSITIVE branch (a true plan error projects correctly); these pin the
;; NEGATIVE branch + the precise `:where`-marker scoping.

(deftest plan-construction-error?-discriminates-on-where-marker
  (testing "the predicate keys on :where 'rf.story/variant-plan ONLY — an
            :rf.error/id-bearing error WITHOUT that marker is NOT a plan-
            construction error (it must route through the frame-bound
            path), while a :where-marked plan failure IS"
    (let [plan-construction-error? @#'runtime/plan-construction-error?]
      ;; NEGATIVE: a framework runtime error carrying :rf.error/id but no
      ;; :where marker — the #2430 cum40 regression case. Must be false so
      ;; handle-run-error! takes the frame-bound branch, NOT plan-error-result.
      (is (false? (plan-construction-error?
                    (ex-info "no adapter installed"
                             {:rf.error/id :rf.error/no-adapter-installed})))
          "an :rf.error/id-bearing runtime error with NO :where marker is
           NOT a plan-construction error")
      ;; A non-plan :where symbol (registrar / extends / macros stamp
      ;; distinct :where symbols) is likewise not a plan-construction error.
      (is (false? (plan-construction-error?
                    (ex-info "reg failure"
                             {:rf.error/id :rf.error/story-reg-variant-invalid
                              :where        'rf.story/reg-variant})))
          "a sibling :where symbol does not satisfy the predicate")
      ;; An error with no ex-data at all is not a plan-construction error.
      (is (false? (plan-construction-error? (ex-info "bare" {})))
          "an error with empty ex-data is not a plan-construction error")
      ;; POSITIVE control: only the :where 'rf.story/variant-plan marker
      ;; (what plan/fail! stamps) makes the predicate true.
      (is (true? (plan-construction-error?
                   (ex-info "plan invalid"
                            {:rf.error/id :rf.error/story-assert-in-setup
                             :where        'rf.story/variant-plan})))
          "a plan/fail! error (:where 'rf.story/variant-plan) IS a plan-
           construction error"))))

(deftest post-frame-rf-error-id-routes-through-frame-bound-path
  (testing "a framework runtime error carrying an :rf.error/id thrown AFTER
            frame allocation routes through handle-run-error!'s frame-bound
            record path (an :rf.error/exception assertion at :phase-0-setup)
            — it is NOT misrouted as a plan-construction error (which would
            stamp the raw :rf.error/id as the assertion id)"
    (story/reg-variant :story.postframe/v {:events []})
    ;; Redef a phase fn that runs AFTER run-phase-0! (so the frame is
    ;; allocated and the lifecycle is past :pre-mount) to throw an ex-info
    ;; carrying an :rf.error/id but NO :where 'rf.story/variant-plan marker
    ;; — simulating the :rf.error/no-adapter-installed case the #2430 fix
    ;; guards against.
    (with-redefs [runtime/run-phase-2!
                  (fn [_ctx]
                    (throw (ex-info "no adapter installed"
                                    {:rf.error/id :rf.error/no-adapter-installed})))]
      (let [r (async/deref-blocking (story/run-variant :story.postframe/v) 5000)]
        (is (= :error (:lifecycle r))
            "the post-frame throw rolled the lifecycle to :error via
             loaders/error!")
        (let [recs (:assertions r)]
          (is (some #(and (= :rf.error/exception (:assertion %))
                          (= :phase-0-setup (:phase %)))
                    recs)
              "the error was recorded via the frame-bound record-error!
               path as an :rf.error/exception at :phase-0-setup")
          (is (not-any? #(= :rf.error/no-adapter-installed (:assertion %)) recs)
              "the raw :rf.error/id was NOT stamped as the assertion id —
               i.e. it did NOT misroute through plan-error-result"))))
    (story/destroy-variant! :story.postframe/v)))

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
    (rf/reg-event :test/boom
      (fn [_ _] (throw (ex-info "bang" {:why :test}))))
    (story/reg-variant :story.err/v
      {:events [[:test/boom]]})
    (let [r (async/deref-blocking (story/run-variant :story.err/v) 5000)]
      ;; Phase-2 errors don't roll back the lifecycle; :ready is the
      ;; terminal state per `002-Runtime.md` §Error projection — we record and continue.
      (is (some #(= :rf.error/exception (:assertion %)) (:assertions r))
          "an exception assertion was recorded")
      (is (some #(= :phase-2-events (:phase %)) (:assertions r))))
    (story/destroy-variant! :story.err/v)))

;; ---- rf2-294yq5.5 — exception ex-data wire-elision -----------------------

(deftest exception-ex-data-redacts-sensitive-slot-jvm
  (testing "a handler throwing ex-info with a value at a frame-owned sensitive
            key records :rf/redacted in :error :data, NOT the raw secret; the
            :error :message survives verbatim (rf2-294yq5.5 / rf2-bsk1d9). JVM
            gate (the CLJS twin lives in error_projection_redaction_cljs_test.cljs)"
    (rf/reg-event :auth/boom-jvm
      (fn [_ _]
        (throw (ex-info "Invalid credentials"
                        {:token  "BEARER-secret-12345"
                         :reason :bad-password}))))
    ;; rf2-bsk1d9 — declare the sensitive ex-data path on the variant body
    ;; (EP-0015 frame-owned classification, installed at frame creation); no
    ;; public add-marks mutation, no run-once-then-mark-then-rerun dance.
    (story/reg-variant :story.err-redaction-jvm/v
      {:events      []
       :sensitive   {:app-db [[:token]]}
       :play-script [[:dispatch-sync [:auth/boom-jvm]]]})
    (let [r    (async/deref-blocking (story/run-variant :story.err-redaction-jvm/v) 5000)
          ex   (last (filter #(= :rf.error/exception (:assertion %)) (:assertions r)))
          data (get-in ex [:error :data])]
      (is (some? ex) "the throwing handler was captured as an exception record")
      (is (= :rf/redacted (:token data))
          "the sensitive ex-data slot is redacted, NOT the raw bearer token")
      (is (= :bad-password (:reason data))
          "a non-sensitive ex-data slot passes through")
      (is (= "Invalid credentials" (get-in ex [:error :message]))
          "the message string survives verbatim (NOT auto-walked)"))
    (story/destroy-variant! :story.err-redaction-jvm/v)))

(deftest exception-ex-data-non-sensitive-passes-through-jvm
  (testing "with NO marks, captured ex-data passes through unredacted —
            frame-scoped elision only redacts marked paths (rf2-294yq5.5)"
    (rf/reg-event :plain/boom-jvm
      (fn [_ _] (throw (ex-info "boom" {:detail "not-secret"}))))
    (story/reg-variant :story.err-plain-jvm/v
      {:events [[:plain/boom-jvm]]})
    (let [r    (async/deref-blocking (story/run-variant :story.err-plain-jvm/v) 5000)
          ex   (last (filter #(= :rf.error/exception (:assertion %)) (:assertions r)))]
      (is (= "not-secret" (get-in ex [:error :data :detail]))
          "an unmarked ex-data slot is not redacted"))
    (story/destroy-variant! :story.err-plain-jvm/v)))

;; ---- rf2-294yq5.1 — :frame-setup :init failures are captured -------------

(deftest frame-setup-init-throw-is-captured-as-failed-assertion
  (testing "a :frame-setup :init handler that THROWS is captured as a
            :phase-0-setup :rf.error/exception assertion — NOT a silent
            :pass / :ready against a frame missing its declared
            preconditions (rf2-294yq5.1)"
    (rf/reg-event :test/setup-boom
      (fn [_ _] (throw (ex-info "setup blew up" {:why :setup}))))
    (story/reg-decorator :boom-setup
      {:kind :frame-setup
       :init [[:test/setup-boom]]})
    (story/reg-variant :story.init-boom/v
      {:decorators [[:boom-setup]]
       :events     []})
    (let [r    (async/deref-blocking (story/run-variant :story.init-boom/v) 5000)
          recs (:assertions r)]
      (is (some #(and (= :rf.error/exception (:assertion %))
                      (= :phase-0-setup (:phase %)))
                recs)
          "the throwing :init handler was captured as a :phase-0-setup
           exception assertion")
      (is (not= :pass (:status r))
          "the run does NOT aggregate to :pass — a broken setup is a
           failed run, not a false green")
      (is (seq recs)
          "the assertions vector is non-empty (regression: it was [] before
           the listener-before-setup fix)"))
    (story/destroy-variant! :story.init-boom/v)))

;; ---- rf2-294yq5.2 — cofx / interceptor failures are captured -------------

(deftest cofx-injection-throw-is-captured
  (testing "a phase-2 event whose injected COFX throws is captured as an
            :rf.error/exception assertion carrying the
            :rf.error/coeffect-exception operation (rf2-294yq5.2) — the
            old handler-exception-only capture was a false green"
    (rf/reg-cofx :test/boom-cofx
      (fn [] (throw (ex-info "cofx blew up" {:why :cofx}))))
    (rf/reg-event :test/uses-boom-cofx
      {:rf.cofx/requires [:test/boom-cofx]}
      (fn [_ _] {}))
    (story/reg-variant :story.cofx-boom/v
      {:events [[:test/uses-boom-cofx]]})
    (let [r    (async/deref-blocking (story/run-variant :story.cofx-boom/v) 5000)
          recs (:assertions r)
          exc  (first (filter #(= :rf.error/exception (:assertion %)) recs))]
      (is (some? exc)
          "the cofx-injection throw was captured as an exception assertion")
      (is (= :phase-2-events (:phase exc)))
      (is (= :rf.error/coeffect-exception (:operation exc))
          "the originating coeffect-exception operation is preserved")
      (is (not= :pass (:status r))
          "a cofx failure flips the run off :pass"))
    (story/destroy-variant! :story.cofx-boom/v)))

(deftest user-interceptor-throw-is-captured
  (testing "a phase-2 event whose USER INTERCEPTOR :before throws is
            captured as an :rf.error/exception assertion carrying the
            :rf.error/interceptor-exception operation (rf2-294yq5.2)"
    (let [boom-icpt (rf/->interceptor
                      :id :test/boom-icpt
                      :before (fn [_ctx] (throw (ex-info "icpt blew up" {:why :icpt}))))]
      ;; EP-0022 reference-only flip: register the interceptor value then
      ;; reference it by id from the chain (the value's `:id` must match).
      (rf/reg-interceptor* :test/boom-icpt boom-icpt)
      (rf/reg-event :test/uses-boom-icpt
        {:interceptors [:test/boom-icpt]}
        (fn [{:keys [db]} _] {:db db}))
      (story/reg-variant :story.icpt-boom/v
        {:events [[:test/uses-boom-icpt]]})
      (let [r    (async/deref-blocking (story/run-variant :story.icpt-boom/v) 5000)
            recs (:assertions r)
            exc  (first (filter #(= :rf.error/exception (:assertion %)) recs))]
        (is (some? exc)
            "the interceptor :before throw was captured as an exception assertion")
        (is (= :rf.error/interceptor-exception (:operation exc))
            "the originating interceptor-exception operation is preserved")
        (is (= :test/boom-icpt (:failing-id exc))
            "the failing interceptor id is preserved")
        (is (not= :pass (:status r))))
      (story/destroy-variant! :story.icpt-boom/v))))

;; ---- rf2-294yq5.3 — run-variant enforces a fresh-run boundary ------------

(deftest run-variant-twice-is-stateless
  (testing "two consecutive run-variant calls on the same id produce the
            SAME fresh app-db — run-variant does not reuse the prior frame
            (rf2-294yq5.3)"
    (rf/reg-event :test/inc-counter
      (fn [{:keys [db]} _] {:db (update db :counter (fnil inc 0))}))
    (story/reg-variant :story.fresh/v
      {:events [[:test/inc-counter]]})
    (let [r1 (async/deref-blocking (story/run-variant :story.fresh/v) 5000)
          r2 (async/deref-blocking (story/run-variant :story.fresh/v) 5000)]
      (is (= 1 (:counter (:app-db r1))))
      (is (= 1 (:counter (:app-db r2)))
          "the SECOND run starts from a fresh app-db (counter 0 → 1), NOT
           the first run's leftover state (which would give 2)"))
    (story/destroy-variant! :story.fresh/v)))

(deftest run-variant-twice-epoch-tape-does-not-bleed
  (testing "rf2-xj0bj0 — a second run-variant on the same id projects
            evidence from ITS OWN epoch records only. The reset-in-place
            path (frames/reset-state!, the fresh-run boundary) resets
            app-db/runtime-db but never touches the epoch ring (only
            destroy-frame! drops it) — so a naive whole-frame
            epoch-history read would inherit the FIRST run's records too,
            producing a tape roughly twice as long on the second run and
            polluting the projected evidence."
    (rf/reg-event :test/inc-counter3
      (fn [{:keys [db]} _] {:db (update db :counter (fnil inc 0))}))
    (story/reg-variant :story.fresh3/v
      {:events [[:test/inc-counter3]]})
    (let [r1 (async/deref-blocking (story/run-variant :story.fresh3/v) 5000)
          r2 (async/deref-blocking (story/run-variant :story.fresh3/v) 5000)]
      (is (= 1 (:counter (:app-db r1))))
      (is (= 1 (:counter (:app-db r2))))
      (is (pos? (count (:epoch-tape r1)))
          "sanity: the epoch surface is live for this test (a non-empty
           tape) — otherwise the assertion below would pass vacuously")
      (is (= (count (:epoch-tape r1)) (count (:epoch-tape r2)))
          "identical scripts against a fresh frame produce a SAME-SIZED
           epoch tape on both runs — the second run's tape is scoped to
           its OWN records, not the first run's tape plus its own (which
           would be roughly double-length before the fix)")
      (is (= (:schema-violations r1) (:schema-violations r2))
          "identical re-runs project IDENTICAL evidence — no stale
           violation/warning carries over from the first run"))
    (story/destroy-variant! :story.fresh3/v)))

(deftest run-variant-twice-reruns-loaders
  (testing "a LOADER variant reruns its loaders on the second run-variant —
            the prior :ready frame is destroyed, so run-loaders! does not
            short-circuit (rf2-294yq5.3)"
    (rf/reg-event :test/load-mark
      (fn [{:keys [db]} _] {:db (update db :loads (fnil inc 0))}))
    (story/reg-variant :story.fresh-loader/v
      {:loaders [[:test/load-mark]]})
    (let [r1 (async/deref-blocking (story/run-variant :story.fresh-loader/v) 5000)
          r2 (async/deref-blocking (story/run-variant :story.fresh-loader/v) 5000)]
      (is (= 1 (:loads (:app-db r1)))
          "first run runs the loader once")
      (is (= 1 (:loads (:app-db r2)))
          "second run reruns the loader against a FRESH frame (1, not a
           skipped loader leaving the slot absent, and not 2 from carryover)"))
    (story/destroy-variant! :story.fresh-loader/v)))

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

(deftest configure-sets-global-decorators
  (testing "configure! :rf.story/global-decorators replaces the global
            ref vector wholesale (rf2-9qpk3 · audit C-1/F-1 — preview.ts
            parity)"
    ;; The decorator bodies must already be registered; configure! is
    ;; the opt-in surface, not the body-registration surface.
    (story/reg-decorator :app/theme
      {:kind :hiccup :wrap (fn [body _] [:div.theme body])})
    (story/reg-decorator :app/wrap
      {:kind :hiccup :wrap (fn [body _] [:div.wrap body])})
    (story/configure!
      {:rf.story/global-decorators [[:app/theme] [:app/wrap]]})
    (is (= [[:app/theme] [:app/wrap]] (config/get-global-decorators))
        "the configure! call lands the vector verbatim — earliest first")
    (is (= [[:app/theme] [:app/wrap]] (story/global-decorators))
        "and the public accessor reflects the same shape"))
  (testing "every variant inherits the global stack as the outermost
            wrap layer — composition, not override"
    (story/reg-decorator :app/theme
      {:kind :hiccup :wrap (fn [body _] [:div.theme body])})
    (story/reg-decorator :variant-deco
      {:kind :hiccup :wrap (fn [body _] [:div.variant body])})
    (story/configure!
      {:rf.story/global-decorators [[:app/theme]]})
    (story/reg-variant :story.cfg.gd/v
      {:decorators [[:variant-deco]]
       :events     []})
    (let [r       (story/resolve-decorators :story.cfg.gd/v)
          ids     (mapv :id (:hiccup r))
          wrapped (decorators/apply-hiccup-decorators
                    (:hiccup r) [:span "leaf"] {})]
      (is (= [:app/theme :variant-deco] ids)
          "global is outermost; variant decorator composes inside")
      (is (= [:div.theme [:div.variant [:span "leaf"]]] wrapped)
          ":app/theme wraps :variant-deco wraps the leaf")))
  (testing "configure! :rf.story/global-decorators nil clears the vector"
    (story/reg-decorator :app/theme
      {:kind :hiccup :wrap (fn [body _] [:div.theme body])})
    (story/configure!
      {:rf.story/global-decorators [[:app/theme]]})
    (is (seq (config/get-global-decorators)))
    (story/configure! {:rf.story/global-decorators nil})
    (is (= [] (config/get-global-decorators))
        "explicit nil clears the global vector"))
  (testing "configure! :rf.story/global-decorators [] clears the vector"
    (story/reg-decorator :app/theme
      {:kind :hiccup :wrap (fn [body _] [:div.theme body])})
    (story/configure!
      {:rf.story/global-decorators [[:app/theme]]})
    (is (seq (config/get-global-decorators)))
    (story/configure! {:rf.story/global-decorators []})
    (is (= [] (config/get-global-decorators))
        "explicit empty vector clears the global vector"))
  (testing "configure! with no :rf.story/global-decorators key leaves
            the slot untouched (forward-compat for partial configure
            calls)"
    (story/reg-decorator :app/keep
      {:kind :hiccup :wrap (fn [body _] [:div.keep body])})
    (config/set-global-decorators! [[:app/keep]])
    (story/configure! {:rf.story/global-args {:theme :dark}})
    (is (= [[:app/keep]] (config/get-global-decorators))
        "the global-decorators slot is preserved across a global-args-only configure call")
    (config/set-global-decorators! []))
  (testing "configure! :rf.story/global-decorators accepts entries with
            ref-args — `[<id> & ref-args]` shape exactly like a variant's
            :decorators slot"
    (story/reg-decorator :app/tagged
      {:kind :hiccup
       :wrap (fn [body args]
               [:div.tagged {:tag (-> args :decorator/args first)} body])})
    (story/configure!
      {:rf.story/global-decorators [[:app/tagged :my-tag]]})
    (story/reg-variant :story.cfg.gd2/v {:events []})
    (let [r       (story/resolve-decorators :story.cfg.gd2/v)
          wrapped (decorators/apply-hiccup-decorators
                    (:hiccup r) [:span "x"] {})]
      (is (= [:div.tagged {:tag :my-tag} [:span "x"]] wrapped)
          "ref-args from the global-decorators ref reach the :wrap fn")))
  (testing "configure! :rf.story/global-decorators composes with both
            story-level and variant-level :decorators — all three layers
            apply (the precedence chain is additive, not override-style)"
    (story/reg-decorator :app/g    {:kind :hiccup :wrap (fn [b _] [:div.g b])})
    (story/reg-decorator :app/s    {:kind :hiccup :wrap (fn [b _] [:div.s b])})
    (story/reg-decorator :app/v    {:kind :hiccup :wrap (fn [b _] [:div.v b])})
    (story/configure!
      {:rf.story/global-decorators [[:app/g]]})
    (story/reg-story :story.cfg.gd3
      {:decorators [[:app/s]]})
    (story/reg-variant :story.cfg.gd3/v
      {:decorators [[:app/v]]
       :events     []})
    (let [r       (story/resolve-decorators :story.cfg.gd3/v)
          ids     (mapv :id (:hiccup r))
          wrapped (decorators/apply-hiccup-decorators
                    (:hiccup r) [:span "leaf"] {})]
      (is (= [:app/g :app/s :app/v] ids)
          "globals outermost, then story, then variant — full composition")
      (is (= [:div.g [:div.s [:div.v [:span "leaf"]]]] wrapped)))))

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
  (testing "every Stage 3 `002-Runtime.md` §Programmatic API fn is present on the public ns"
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

;; ===========================================================================
;; VARIANT-BODY CLASSIFICATION (rf2-7c6ecy)
;; ===========================================================================
;;
;; EP-0025: a variant declares its sensitive / large app-db paths on its body
;; via the carrier-keyed NESTED form `:sensitive {:app-db [[:auth :token]]}`
;; (the SAME owner shape `reg-frame` uses; spec/Conventions.md §Privacy). The
;; runtime lowers the `:app-db` paths into the variant frame's elision
;; registry as EP-0025 commit-plane classification effects right after
;; `make-frame`, BEFORE the lifecycle / init events
;; (`frames/apply-variant-classification!`, `:source :effect`).
;;
;; Two coupled defects this section guards against (the two were a three-way
;; doc/schema/lowering shape mismatch + a missing fail-loud validation):
;;
;;   1. POSITIVE — a documented (NESTED) variant classification actually
;;      classifies: the declared path REDACTS to `:rf/redacted` at wire
;;      egress. (Before the doc reconciliation an author following the
;;      docstring's FLAT example wrote `:sensitive [[:auth :token]]`, which
;;      the schema REJECTED and the lowering silently DROPPED.)
;;   2. NEGATIVE — a MALFORMED variant classification (a NESTED-but-bad
;;      `:app-db` payload that the loose schema admits) is routed through the
;;      SAME fail-loud commit-plane validator the router uses
;;      (`elision/classification-effect-defect`), so it FAILS LOUD pre-commit
;;      with `:rf.error/classification-effect-shape` rather than crashing
;;      inside `apply-classification-effects` / `allocate!`. The run records a
;;      failed `:rf.error/exception` assertion carrying that error id.

(deftest variant-classification-nested-form-redacts-at-egress
  (testing "rf2-7c6ecy POSITIVE — a documented NESTED variant `:sensitive`
            declaration (`{:app-db [[:auth :token]]}`) lowers into the variant
            frame's elision registry and REDACTS the path at wire egress"
    (rf/reg-event :auth/login-7c6ecy
      (fn [{:keys [db]} _] {:db (assoc-in db [:auth :token] "BEARER-secret-7c6ecy")}))
    (story/reg-variant :story.classif/sensitive
      {:events    [[:auth/login-7c6ecy]]
       :sensitive {:app-db [[:auth :token]]}})
    (let [r (async/deref-blocking (story/run-variant :story.classif/sensitive) 5000)]
      (is (= :ready (:lifecycle r))
          "the variant runs to :ready — classification did not abort the run")
      (is (= "BEARER-secret-7c6ecy" (get-in (rf/app-db-value :story.classif/sensitive)
                                            [:auth :token]))
          "the raw value is in app-db (classification is path-based, not value mutation)")
      ;; Wire egress over the frame's app-db substitutes the classified path.
      (let [walked (rf/elide-wire-value (rf/app-db-value :story.classif/sensitive)
                                        {:frame :story.classif/sensitive})]
        (is (= :rf/redacted (get-in walked [:auth :token]))
            "the documented NESTED :sensitive declaration redacts the path at egress")))
    (story/destroy-variant! :story.classif/sensitive)))

(deftest variant-classification-large-nested-form-elides-at-egress
  (testing "rf2-7c6ecy POSITIVE — a documented NESTED variant `:large`
            declaration (`{:app-db [[:docs :blob]]}`) elides the path to the
            `:rf.size/large-elided` marker at wire egress"
    (rf/reg-event :docs/upload-7c6ecy
      (fn [{:keys [db]} _] {:db (assoc-in db [:docs :blob] (apply str (repeat 2048 "x")))}))
    (story/reg-variant :story.classif/large
      {:events [[:docs/upload-7c6ecy]]
       :large  {:app-db [[:docs :blob]]}})
    (let [r (async/deref-blocking (story/run-variant :story.classif/large) 5000)]
      (is (= :ready (:lifecycle r)))
      (let [walked  (rf/elide-wire-value (rf/app-db-value :story.classif/large)
                                         {:frame :story.classif/large})
            elided  (get-in walked [:docs :blob])]
        (is (and (map? elided) (contains? elided :rf.size/large-elided))
            "the documented NESTED :large declaration elides the path to the
             `:rf.size/large-elided` marker at egress")))
    (story/destroy-variant! :story.classif/large)))

(deftest variant-classification-malformed-fails-loud-pre-commit
  (testing "rf2-7c6ecy NEGATIVE — a MALFORMED variant classification (a
            NESTED-but-bad `:app-db` payload the loose schema admits) FAILS
            LOUD pre-commit through `elision/classification-effect-defect`,
            recorded as a failed `:rf.error/classification-effect-shape`
            assertion rather than crashing `allocate!`"
    (rf/reg-event :noop-7c6ecy (fn [{:keys [db]} _] {:db db}))
    ;; `:app-db` is a vector (passes the schema) whose entry is NOT a valid
    ;; concrete :rf/path — a non-sequential scalar. The framework's pure
    ;; commit-plane validator rejects it; the variant path now routes through
    ;; that SAME validator pre-commit.
    (story/reg-variant :story.classif/bad
      {:events    [[:noop-7c6ecy]]
       :sensitive {:app-db [42]}})
    (let [r (async/deref-blocking (story/run-variant :story.classif/bad) 5000)
          assertion (->> (:assertions r)
                         (filter #(= :rf.error/exception (:assertion %)))
                         last)]
      (is (= :error (:lifecycle r))
          "the malformed classification aborts the run pre-commit")
      (is (some? assertion)
          "a structured failure assertion is recorded (no uncaught crash)")
      (is (= :rf.error/classification-effect-shape
             (-> assertion :error :data :rf.error/id))
          "the failure routes through the SAME fail-loud id the router's
           commit-plane boundary uses")
      ;; Fail-loud is PRE-commit: the bad path must NOT have been written into
      ;; the elision registry (no partial commit).
      (let [reg (frame/frame-runtime-db-value :story.classif/bad)]
        (is (not (contains? (get-in reg [:rf.runtime/elision :sensitive-declarations])
                            [42]))
            "the malformed path did not land in the elision registry (no partial commit)")))
    (story/destroy-variant! :story.classif/bad)))

;; ---- rf2-cmjly3 finding 12: inline-plan :sensitive/:large classification --
;;
;; Prior to the fix, `allocate-inline!` never called
;; `apply-variant-classification!` at all, and `plan.cljc`'s `context-keys`
;; did not carry `:sensitive`/`:large` into the compiled plan's `:world` —
;; so an inline plan run (`story/run` on a MAP target) declaring
;; `:sensitive`/`:large` got NO error and NO redaction: a value marked
;; sensitive rode into the wire trace unredacted (a silent privacy no-op).
;; The fix routes `:sensitive`/`:large` through `[:world :sensitive]` /
;; `[:world :large]` (plan.cljc `context-keys`) and applies the
;; classification in `allocate-inline!` (frames.cljc), validating BEFORE
;; `rf/reg-frame` so a malformed declaration never leaves an orphan
;; anonymous frame behind (see `validate-classification-effects!`).
;;
;; The inline frame is anonymous and torn down INSIDE the same promise that
;; resolves the run result (unlike a registered variant, whose frame stays
;; live until an explicit `destroy-variant!`) — so these tests can't probe
;; `rf/elide-wire-value` AFTER the run resolves the way the registered-
;; variant tests above do. Instead, an inline `:events` step calls
;; `rf/current-frame-id` (the dynamic scope an event handler runs under)
;; + `rf/elide-wire-value` itself WHILE the frame is still live, and
;; stashes the result into a test-side atom passed as an event arg.

(deftest inline-plan-sensitive-classification-redacts-at-egress
  (testing "rf2-cmjly3 finding 12 POSITIVE — an inline plan MAP declaring
            `:sensitive {:app-db [...]}` actually classifies its anonymous
            frame's elision registry — the declared path is redacted at
            wire egress, mirroring the registered-variant behaviour
            (rf2-7c6ecy)"
    (rf/reg-event :classif-inline-cmjly3/login+probe
      (fn [{:keys [db]} [_ probe-atom]]
        (let [db'      (assoc-in db [:auth :token] "BEARER-secret-cmjly3")
              frame-id (rf/current-frame-id)
              walked   (rf/elide-wire-value db' {:frame frame-id})]
          (reset! probe-atom (get-in walked [:auth :token]))
          {:db db'})))
    (let [probe (atom ::unset)
          r     (async/deref-blocking
                  (story/run {:events    [[:classif-inline-cmjly3/login+probe probe]]
                             :sensitive {:app-db [[:auth :token]]}})
                  5000)]
      (is (= :ready (:lifecycle r))
          "the inline run reaches :ready — classification did not abort it")
      (is (= :rf/redacted @probe)
          "the inline plan's :sensitive declaration redacts the path at
           wire egress — pre-fix this stayed the raw secret (no
           classification was ever applied for an inline run)"))))

(deftest inline-plan-without-classification-does-not-redact
  (testing "rf2-cmjly3 finding 12 — sanity / no-regression: an inline plan
            with NO `:sensitive` declaration leaves the same path
            unredacted, proving the probe mechanism (not some unrelated
            default redaction) is what the positive test exercises"
    (rf/reg-event :classif-inline-cmjly3/login+probe-plain
      (fn [{:keys [db]} [_ probe-atom]]
        (let [db'      (assoc-in db [:auth :token] "BEARER-secret-cmjly3-plain")
              frame-id (rf/current-frame-id)
              walked   (rf/elide-wire-value db' {:frame frame-id})]
          (reset! probe-atom (get-in walked [:auth :token]))
          {:db db'})))
    (let [probe (atom ::unset)
          r     (async/deref-blocking
                  (story/run {:events [[:classif-inline-cmjly3/login+probe-plain probe]]})
                  5000)]
      (is (= :ready (:lifecycle r)))
      (is (= "BEARER-secret-cmjly3-plain" @probe)
          "no :sensitive declaration -> the path passes through unredacted"))))

(deftest inline-plan-malformed-classification-fails-loud-with-no-frame-registered
  (testing "rf2-cmjly3 finding 12 NEGATIVE — a MALFORMED inline
            `:sensitive` declaration fails loud through the SAME
            `elision/classification-effect-defect` validator the
            registered-variant path uses — recorded as a failed
            `:rf.error/exception` assertion carrying
            `:rf.error/classification-effect-shape`, exactly like the
            registered-variant negative test above, rather than a silent
            vacuous pass. `apply-variant-classification!` validates AFTER
            `rf/reg-frame` (frames.cljc) precisely so this failure has a
            live frame to record itself against; the trade-off (an
            orphaned anonymous frame on this authoring-mistake path) is
            documented on that fn."
    (rf/reg-event :noop-cmjly3 (fn [{:keys [db]} _] {:db db}))
    (let [r         (async/deref-blocking
                      (story/run {:events    [[:noop-cmjly3]]
                                 :sensitive {:app-db [42]}})
                      5000)
          assertion (->> (:assertions r)
                         (filter #(= :rf.error/exception (:assertion %)))
                         last)]
      (is (some? assertion)
          "a structured failure assertion is recorded (no uncaught crash,
           no silent vacuous pass)")
      (is (= :rf.error/classification-effect-shape
             (-> assertion :error :data :rf.error/id))
          "the failure routes through the SAME fail-loud id the
           registered-variant path uses")
      (is (not= :pass (:status r))
          "the run does NOT report a vacuous :pass — pre-fix (validating
           before rf/reg-frame) this silently passed with zero
           assertions"))))
