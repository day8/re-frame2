(ns re-frame.app-ns-fixture-cljs-test
  "Contract for `make-reset-runtime-fixture`'s `:app-ns` option — BUNDLE
  CO-LOAD HYGIENE (rf2-kuky.27), per Spec 008 §Test-support.

  THE CONDITION. A CLJS node runner loads EVERY test namespace into ONE bundle
  before any test runs, so two co-loaded example apps that register the same
  per-app id (`:rf.route/not-found`; the RealWorld twins also share
  `:settings/load`, `:auth/initialise`, …) leave TWO provenance rows in the
  shared source store — and any suite whose fixture baseline was captured after
  the second app loaded then fails default-image assembly LOUD
  (`:rf.error/image-duplicate-id`) on `make-frame {}`.

  THE OPTION. `:app-ns` is a provenance-namespace PREFIX naming the suite's OWN
  app. The fixture captures those rows and removes them from both stores when it
  is BUILT — before it takes its baselines, so no suite's baseline can hold
  them — and reinstates them through `rf.registrar/register!` before each test.
  Self-hiding is the invariant: every app suite names ITSELF, so no suite needs
  to know its sibling's name.

  WHY THE CAPTURES UNION RATHER THAN MEMOIZE. ClojureScript loads a required
  namespace ONCE, so if suite A removes an app's rows, suite B's later
  `:require` of that already-loaded app registers nothing and B would capture an
  empty set. The captures are therefore unioned per prefix and read back AT TEST
  TIME, which is what makes repeated suites for one app work in either order and
  what lets a late-loading part of an app reach a fixture built before it. The
  predecessor of this option kept a first-capture-WINS memo and had exactly that
  defect measured against it: a capture taken before an app finished loading
  pinned an incomplete set for every later suite.

  WHAT THESE ROWS DRIVE. Every case builds real fixtures and DRIVES them, in the
  load order the bundle would produce, over synthetic app namespaces — so the
  contract is pinned without depending on which example apps happen to be
  co-loaded. Each asserts BOTH legs the condition spans: registrar lookup (the
  resolver map) AND default-image assembly (`make-frame {}` over the source
  store). §2 opens with a POSITIVE CONTROL of its own: with two rival apps live
  and no `:app-ns`, `make-frame {}` really does raise
  `:rf.error/image-duplicate-id`, so the greens that follow are differential
  rather than vacuous.

  WHY THE APP REGISTRATIONS ARE INTERLEAVED WITH THE FIXTURE BUILDS. That is the
  bundle's own sequence, and it is what makes self-hiding sufficient: a test
  namespace's `use-fixtures` form is evaluated at that namespace's load, right
  after its `:require` chain brought its app live, so an app is hidden the moment
  it appears — before any rival has loaded. Registering both apps up front and
  only then building either fixture is a sequence the bundle does not produce,
  and it is not what these rows claim.

  §§6-8 are the provenance-safety guards the singular predecessor carried
  (rf2-22vzb), folded onto the option that replaced it: an absent row is a TRUE
  NO-OP, and a sibling namespace's live registration is never clobbered — the
  registrar's single `(kind, id)` slot is the LAST writer's, which for a shared
  id may be the sibling's.

  Named `*-cljs-test` so the shadow-cljs `:node-test` build (ns-regexp
  `cljs-test$`) discovers it; the `-test` suffix also satisfies the JVM
  cognitect runner, so this one `.cljc` file runs on both runtimes."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [clojure.string]
            [re-frame.core                 :as rf]
            [re-frame.registrar            :as rf.registrar]
            [re-frame.source-store         :as rf.source-store]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support         :as rf.test-support]))

;; This ns's OWN fixture is the plain default: every claim below is made by
;; DRIVING a separately-built fixture, the way
;; `re-frame.async-fixture-platform-shape-cljs-test` drives the `:async?`
;; shapes. Registering an `:app-ns` fixture here would make these rows depend on
;; the very machinery they are pinning.
(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter}))

;; ---- helpers --------------------------------------------------------------

(defn- reg!
  "Register `(kind, id)` authored in `provenance-ns` through the real
  `rf.registrar/register!` path — resolver map + provenance source store in
  lockstep, exactly as a `reg-*` macro would in dev. `provenance-ns` is a
  string, stamped into the descriptor's macro-captured `:ns` symbol so the
  source store keys the row under it. `tag` rides along so a test can tell WHICH
  app's registration currently owns the resolver slot."
  ([kind id provenance-ns] (reg! kind id provenance-ns provenance-ns))
  ([kind id provenance-ns tag]
   (rf.registrar/register! kind id
                           {:ns         (symbol provenance-ns)
                            :path       "/"
                            :app-tag    tag
                            :handler-fn (fn [& _] tag)})
   nil))

(defn- reg-slot
  "The live registrar metadata for `(kind, id)`, or nil."
  [kind id]
  (get-in @rf.registrar/kind->id->metadata [kind id]))

(defn- slot-tag
  "Which app's registration currently owns the resolver slot for `(kind, id)`."
  [kind id]
  (:app-tag (reg-slot kind id)))

(defn- src-row
  "The source-store descriptor for the exact `(kind, id, provenance-ns)` slot."
  [kind id provenance-ns]
  (get-in @rf.source-store/kind->id->ns->descriptor [kind id provenance-ns]))

(defn- src-rows-under
  "Every `[kind id provenance-ns]` source slot whose provenance namespace starts
  with `prefix`."
  [prefix]
  (vec (for [[kind id->ns] @rf.source-store/kind->id->ns->descriptor
             [id ns->d]    id->ns
             [pns _]       ns->d
             :when (and (string? pns) (clojure.string/starts-with? pns prefix))]
         [kind id pns])))

(defn- err-id
  "The `:rf.error/id` `thunk` failed with, or nil when it did not fail."
  [thunk]
  (try (thunk) nil
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
         (:rf.error/id (ex-data e)))))

(defn- app-fixture
  "A fixture that hides the app under `prefix`. `:ambient-frame nil` because
  every body below makes its own top-level frame."
  [prefix]
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.substrate.plain-atom/adapter
     :ambient-frame nil
     :app-ns        prefix}))

(defn- drive!
  "Run `body` inside `fixture` (the fn-form — this ns has no async rows)."
  [fixture body]
  (fixture body))

;; Two synthetic apps that share id vocabulary the way the RealWorld twins do:
;; the reserved per-app not-found route, plus one event id both implement.
(def ^:private shared-route :rf.route/not-found)
(def ^:private shared-event :kuky27.shared/load)

(defn- register-app!
  "Bring an app live: its two shared-vocabulary rows plus one id of its own."
  [prefix tag]
  (reg! :route shared-route (str prefix "routing") tag)
  (reg! :event shared-event (str prefix "settings") tag)
  (reg! :sub   (keyword (str "kuky27." tag) "own") (str prefix "subs") tag))

;; ===========================================================================
;; 1. Two suites for ONE app — the codex objection, in both orders
;; ===========================================================================

(deftest second-suite-for-one-app-still-sees-the-whole-app
  (testing "CLJS loads a required namespace ONCE, so the SECOND fixture built for
            an app finds its rows already gone and captures nothing — it must
            still reinstate what the FIRST capture took, or its tests run against
            an app that is not there. The union registry is what answers that"
    (register-app! "kuky27a." "a")
    (let [first-suite  (app-fixture "kuky27a.")
          ;; Between the two builds the app is ALREADY hidden: this is the
          ;; empty-capture the second suite must survive.
          hidden-mid   (src-rows-under "kuky27a.")
          second-suite (app-fixture "kuky27a.")]
      (is (= [] hidden-mid)
          "precondition: the first fixture build removed the app's rows, so the
           second build's own scan finds nothing to capture")
      (doseq [[label fixture] [["first-built suite" first-suite]
                               ["second-built suite" second-suite]]]
        (drive! fixture
          (fn []
            (is (= "a" (slot-tag :route shared-route))
                (str label ": the app's route resolves through the registrar"))
            (is (= "a" (slot-tag :event shared-event))
                (str label ": the app's event resolves through the registrar"))
            (is (some? (src-row :sub :kuky27.a/own "kuky27a.subs"))
                (str label ": the app's own source row is back in the store"))
            (is (nil? (err-id #(rf/make-frame {})))
                (str label ": default-image assembly succeeds — no duplicate id"))))))))

(deftest a-late-loading-part-of-an-app-reaches-a-fixture-built-before-it
  (testing "a part of an app that registers AFTER a suite built its fixture is
            captured by whichever fixture builds next, and the earlier suite sees
            it too — because reinstatement reads the union at TEST time rather
            than closing over the build-time capture. This is the trap the
            first-capture-wins memo this option replaced could not escape"
    (register-app! "kuky27b." "b")
    (let [early (app-fixture "kuky27b.")]
      ;; A later-loading part of the same app.
      (reg! :sub :kuky27.b/late "kuky27b.late" "b")
      (let [late (app-fixture "kuky27b.")]
        (is (= [] (src-rows-under "kuky27b."))
            "precondition: both builds together left no app row live")
        (doseq [[label fixture] [["fixture built BEFORE the late part loaded" early]
                                 ["fixture built AFTER it" late]]]
          (drive! fixture
            (fn []
              (is (some? (reg-slot :sub :kuky27.b/own))
                  (str label ": the early part of the app is live"))
              (is (some? (reg-slot :sub :kuky27.b/late))
                  (str label ": the LATE part of the app is live too"))
              (is (nil? (err-id #(rf/make-frame {})))
                  (str label ": default-image assembly succeeds")))))))))

;; ===========================================================================
;; 2. Two CONFLICTING apps — with the positive control that the clash is real
;; ===========================================================================

(deftest rival-apps-really-do-collide-without-the-option
  (testing "POSITIVE CONTROL for §2 and §3. With two co-loaded apps registering
            the same ids and nothing hiding either, default-image assembly fails
            exactly the way the bundle does — so the greens below are
            differential rather than vacuous"
    (register-app! "kuky27x." "x")
    (is (nil? (err-id #(rf/make-frame {})))
        "one app alone assembles cleanly")
    (register-app! "kuky27y." "y")
    (is (= :rf.error/image-duplicate-id (err-id #(rf/make-frame {})))
        "the SECOND app's provenance row for the same id is what breaks it")))

(deftest two-rival-apps-each-hiding-itself-both-assemble
  (testing "two co-loaded apps that share id vocabulary each name their OWN
            prefix; neither names the other. Each suite's baseline is free of
            both apps, each test sees its own app and only its own, and
            default-image assembly succeeds on both sides.

            The sequence below is the BUNDLE's: a test namespace's
            `use-fixtures` form is evaluated at that namespace's load, right
            after its `:require` chain brought its app live — so an app is
            hidden the moment it appears, before any rival is even loaded. That
            is what makes self-hiding sufficient, and it is why the app
            registrations here are interleaved with the fixture builds rather
            than all done up front"
    (register-app! "kuky27c." "c")
    (let [c-suite (app-fixture "kuky27c.")
          _       (register-app! "kuky27d." "d")
          d-suite (app-fixture "kuky27d.")]
      (drive! c-suite
        (fn []
          (is (= "c" (slot-tag :route shared-route))
              "app c's suite resolves the shared route to app c")
          (is (= "c" (slot-tag :event shared-event))
              "app c's suite resolves the shared event to app c")
          (is (nil? (src-row :route shared-route "kuky27d.routing"))
              "the rival's row is NOT in the store during app c's test")
          (is (nil? (err-id #(rf/make-frame {})))
              "default-image assembly succeeds inside app c's test")))
      (drive! d-suite
        (fn []
          (is (= "d" (slot-tag :route shared-route))
              "app d's suite resolves the shared route to app d")
          (is (= "d" (slot-tag :event shared-event))
              "app d's suite resolves the shared event to app d")
          (is (nil? (src-row :route shared-route "kuky27c.routing"))
              "the rival's row is NOT in the store during app d's test")
          (is (nil? (err-id #(rf/make-frame {})))
              "default-image assembly succeeds inside app d's test"))))))

;; ===========================================================================
;; 3. REVERSED order — the same two apps, built and driven the other way round
;; ===========================================================================

(deftest rival-apps-are-order-independent
  (testing "§2 with every order reversed — which app loads first, which fixture
            is built first, and which suite RUNS first. Nothing about a suite's
            outcome depends on where its sibling sits in the bundle"
    (register-app! "kuky27f." "f")
    (let [f-suite (app-fixture "kuky27f.")
          _       (register-app! "kuky27e." "e")
          e-suite (app-fixture "kuky27e.")]
      ;; run order reversed relative to build order
      (drive! e-suite
        (fn []
          (is (= "e" (slot-tag :route shared-route))
              "the LATER-built suite, run FIRST, resolves the shared route to its own app")
          (is (nil? (src-row :route shared-route "kuky27f.routing"))
              "and the earlier-built rival's row is absent from its test")
          (is (nil? (err-id #(rf/make-frame {})))
              "default-image assembly succeeds")))
      (drive! f-suite
        (fn []
          (is (= "f" (slot-tag :route shared-route))
              "the EARLIER-built suite, run SECOND, resolves the shared route to its own app")
          (is (nil? (src-row :route shared-route "kuky27e.routing"))
              "and the later-built rival's row is absent from its test")
          (is (nil? (err-id #(rf/make-frame {})))
              "default-image assembly succeeds"))))))

;; ===========================================================================
;; 4. An ABSENT prefix is a true no-op
;; ===========================================================================

(deftest absent-prefix-is-a-true-no-op
  (testing "a prefix that matches no provenance namespace touches neither store
            at build time and reinstates nothing per test — a suite that names an
            app which is not in this bundle is inert, not destructive"
    (register-app! "kuky27g." "g")
    (let [reg-before @rf.registrar/kind->id->metadata
          src-before @rf.source-store/kind->id->ns->descriptor
          fixture    (app-fixture "kuky27.no-such-app.")]
      (is (= reg-before @rf.registrar/kind->id->metadata)
          "the live registrar is untouched by the build")
      (is (= src-before @rf.source-store/kind->id->ns->descriptor)
          "the source store is untouched by the build")
      (drive! fixture
        (fn []
          (is (= "g" (slot-tag :route shared-route))
              "the unrelated app that IS live stays live")
          (is (nil? (err-id #(rf/make-frame {})))
              "default-image assembly succeeds — nothing was fabricated"))))))

;; ===========================================================================
;; 5. EXCEPTIONAL teardown — the store is restored and the app rows are gone
;; ===========================================================================

(deftest a-throwing-test-still-leaves-no-app-row-behind
  (testing "the reinstated rows are removed by the fixture's own `finally`
            restore, so a body that throws leaves the store exactly as clean as a
            body that returns — the caller never holds the rows between forms,
            which is the whole reason this is a fixture option rather than a pair
            of verbs the caller has to sequence"
    (register-app! "kuky27h." "h")
    (let [fixture (app-fixture "kuky27h.")
          seen    (atom nil)
          thrown  (try
                    (drive! fixture
                      (fn []
                        (reset! seen (slot-tag :route shared-route))
                        (throw (ex-info "boom" {:rf.error/id :kuky27/deliberate}))))
                    nil
                    (catch #?(:clj clojure.lang.ExceptionInfo
                              :cljs cljs.core/ExceptionInfo) e
                      (:rf.error/id (ex-data e))))]
      (is (= :kuky27/deliberate thrown)
          "precondition: the body really did throw, so this is the exceptional path")
      (is (= "h" @seen)
          "precondition: the app WAS live inside the body — otherwise the
           teardown claim below would be about nothing")
      (is (= [] (src-rows-under "kuky27h."))
          "no app row survives the throw: the source store is back at the
           baseline, which never held them")
      (is (nil? (err-id #(rf/make-frame {})))
          "and default-image assembly still succeeds afterwards"))))

;; ===========================================================================
;; 6. Guard — a sibling's live registrar slot is never clobbered (rf2-22vzb)
;; ===========================================================================

(deftest sibling-live-registration-is-not-clobbered
  (testing "the registrar's single (kind, id) slot is the LAST writer's, and for
            an id BOTH apps register that may be the SIBLING's. Hiding one app
            forgets only ITS source row and leaves the sibling's live slot
            standing — registrar and source-store authority stay coherent"
    ;; app-i registers first, app-j second → the resolver slot is app-j's.
    (register-app! "kuky27i." "i")
    (register-app! "kuky27j." "j")
    (is (= "j" (slot-tag :route shared-route))
        "precondition: the sibling app-j owns the live resolver slot")
    (app-fixture "kuky27i.")
    (is (= "j" (slot-tag :route shared-route))
        "hiding app-i did NOT remove app-j's live registrar slot")
    (is (nil? (src-row :route shared-route "kuky27i.routing"))
        "app-i's own source row IS forgotten")
    (is (some? (src-row :route shared-route "kuky27j.routing"))
        "app-j's source row survives — no registrar/source divergence")))

;; ===========================================================================
;; 7. Guard — the owning app's own live slot IS removed
;; ===========================================================================

(deftest the-owning-apps-live-slot-is-removed
  (testing "the mirror of §6: when the resolver slot's current writer IS under
            the hidden prefix, the slot goes — otherwise the app would stay
            reachable through the registrar while its source row was forgotten"
    (register-app! "kuky27k." "k")
    (is (= "k" (slot-tag :route shared-route))
        "precondition: the app owns its own resolver slot")
    (app-fixture "kuky27k.")
    (is (nil? (reg-slot :route shared-route))
        "the app's own live registrar slot is removed")
    (is (= [] (src-rows-under "kuky27k."))
        "and every one of its source rows with it")))

;; ===========================================================================
;; 8. Guard — hiding an app twice does not duplicate or lose its rows
;; ===========================================================================

(deftest repeated-capture-neither-duplicates-nor-loses-rows
  (testing "the union is keyed by [kind id provenance-ns], so a second capture of
            the same app contributes the same rows rather than a second copy —
            and reinstating them is idempotent"
    (register-app! "kuky27m." "m")
    (let [one (app-fixture "kuky27m.")
          two (app-fixture "kuky27m.")]
      (drive! two
        (fn []
          (is (= 3 (count (src-rows-under "kuky27m.")))
              "exactly the three rows the app registered are back — no duplicates")
          (is (nil? (err-id #(rf/make-frame {})))
              "default-image assembly succeeds")))
      (drive! one
        (fn []
          (is (= 3 (count (src-rows-under "kuky27m.")))
              "and the same three for the other suite"))))))
