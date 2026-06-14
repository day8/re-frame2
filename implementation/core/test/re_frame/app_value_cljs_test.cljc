(ns re-frame.app-value-cljs-test
  "EP-0013 D2 stage-5 — the internal app-value projection over the default
  realm (rf2-yozjzo, sequenced behind the merged D1 `re-frame.realm`).

  D2 stage 5 is INTERNAL + read-only: no public `rf/app` / `rf/module`
  constructor (stage 6), no public `rf/install!` / `rf/reinstall!` (stage 7),
  no `re-frame.core` facade export, no api-manifest change. The registrations
  a realm carries become an enumerable, recomputable VALUE projected over its
  registrar — but the ordinary `reg-*` sugar path is byte-identical (zero
  ergonomic regression). These tests pin:

    (1) the app value projects correctly over the default-realm registrar —
        the descriptor shape (kind / id / handler / source / metadata), the
        top-level shape (`:rf.app/id` / `:registrations` / `:rf.app/requires`), and
        the source-coord lifting + non-double-carry;
    (2) the SUGAR PATH updates it — an ordinary `reg-*` is reflected by the
        next projection, with no invalidation step (the registrar is the
        single source of truth, the app value is a projection over it);
    (3) it is RECOMPUTABLE — two projections at the same registrar state are
        equal values; a projection after a `reg-*` carries the new descriptor;
        hot-reload (re-registration) is reflected for free;
    (4) the realm-side `installed-app` seam returns the projection (via the
        `:app-value/project` late-bind hook), and reads a stored `:app` slot
        first (forward-stable across the stage-6/7 graduation);
    (5) default-realm ergonomics are UNCHANGED — reg-* / dispatch / subscribe
        behave exactly as before (the headline).

  Dual-runtime: named `*_cljs_test.cljc` so the shadow-cljs `:node-test`
  build (`npm run test:cljs`) AND the JVM `clojure -M:test` runner both pick
  it up. Pure CLJC — no DOM dependency; uses the plain-atom adapter."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.app-value :as av]
            [re-frame.realm :as realm]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as ts]))

(use-fixtures :each
  (ts/make-reset-runtime-fixture
    {:adapter plain-atom/adapter}))

;; ---------------------------------------------------------------------------
;; (1) the app value projects correctly over the default-realm registrar
;; ---------------------------------------------------------------------------

(deftest app-value-top-level-shape
  (testing "the projection carries the app-value top-level shape — the app id
            (the realm's id), the :registrations grouping, and the
            :rf.app/requires set (empty in stage 5 — declared on modules, D3)"
    (let [v (av/app-value)]
      (is (= :rf.realm/default (:rf.app/id v))
          "the app id is the default realm's id — the app it carries")
      (is (map? (:registrations v))
          "registrations is a kind → id → descriptor map")
      (is (= #{} (:rf.app/requires v))
          "requires is empty in stage 5 (capability requirements are module/D3)")
      (is (not (contains? v :modules))
          ":modules is D3-reserved, never produced in stage 5")
      (is (not (contains? v :diagnostics))
          ":diagnostics is stage-6 (compose), never produced in stage 5"))))

(deftest event-descriptor-shape-and-coord-lifting
  (testing "an event registration projects to a normalized descriptor: kind /
            id / handler lifted out of :handler-fn, source coords lifted into
            :source, the rest of the Spec 001 metadata under :metadata, each
            fact carried exactly once"
    (rf/reg-event-db :av/add
      {:doc "Add an item."}
      (fn add-handler [db _] (update db :items (fnil conj []) :x)))
    (let [d (get-in (av/app-value) [:registrations :event :av/add])]
      (is (= :event (:kind d)) "the descriptor carries the kind")
      (is (= :av/add (:id d)) "the descriptor carries the id")
      (is (fn? (:handler d)) "the handler is lifted out of :handler-fn")
      (is (identical? (:handler d)
                      (:handler-fn (registrar/lookup :event :av/add)))
          "the lifted handler IS the registrar's :handler-fn")
      ;; The Spec 001 metadata is preserved under :metadata, including the
      ;; kind-specific :event/kind extra — those are part of the registration's
      ;; DESCRIPTION, not framework book-keeping.
      (is (= "Add an item." (get-in d [:metadata :doc]))
          ":doc is preserved under :metadata")
      (is (= :db (get-in d [:metadata :event/kind]))
          "the kind-specific :event/kind extra is preserved under :metadata")
      ;; The handler and source-coord slots are NOT double-carried in :metadata.
      (is (not (contains? (:metadata d) :handler-fn))
          ":handler-fn is removed from :metadata (carried under :handler)")
      (is (not (contains? (:metadata d) :ns))
          "source coords are removed from :metadata (carried under :source)"))))

(deftest sub-descriptor-shape
  (testing "a sub registration projects to a descriptor preserving its
            kind-specific extras (:input-kind / :input-signals) under :metadata"
    (rf/reg-sub :av/items
      {:doc "The items."}
      (fn [db _] (:items db)))
    (let [d (get-in (av/app-value) [:registrations :sub :av/items])]
      (is (= :sub (:kind d)))
      (is (= :av/items (:id d)))
      (is (fn? (:handler d)) "the sub handler is lifted out of :handler-fn")
      (is (= :db (get-in d [:metadata :input-kind]))
          "the sub's :input-kind extra is preserved under :metadata")
      (is (= [] (get-in d [:metadata :input-signals]))
          "the sub's :input-signals extra is preserved under :metadata"))))

(deftest frame-descriptor-carries-no-handler
  (testing "a :frame registration carries no :handler-fn, so the descriptor
            omits :handler rather than carrying a nil one"
    (rf/reg-frame :av/main {:doc "the main frame"})
    (let [d (get-in (av/app-value) [:registrations :frame :av/main])]
      (is (= :frame (:kind d)))
      (is (= :av/main (:id d)))
      (is (not (contains? d :handler))
          "no :handler key — :frame registrations carry no :handler-fn")
      (is (= "the main frame" (get-in d [:metadata :doc]))
          "the frame's :doc is preserved under :metadata"))))

(deftest fx-cofx-descriptors-project
  (testing "fx and cofx registrations project to descriptors with their
            handlers lifted"
    (rf/reg-fx :av/send {:doc "send"} (fn [_] nil))
    (rf/reg-cofx :av/now {:doc "now"} (fn [cofx] (assoc cofx :now 42)))
    (let [v (av/app-value)]
      (is (fn? (get-in v [:registrations :fx :av/send :handler]))
          "the fx handler is lifted")
      (is (fn? (get-in v [:registrations :cofx :av/now :handler]))
          "the cofx handler is lifted"))))

(deftest programmatic-registration-has-no-source-coords
  (testing "a programmatic registration (no macro source capture) omits
            :source rather than carrying an empty envelope — absence never
            changes behaviour (EP-0013 issue 8 disposition)"
    (registrar/register! :event :av/programmatic
      {:doc "no coords" :handler-fn (fn [db _] db)})
    (let [d (get-in (av/app-value) [:registrations :event :av/programmatic])]
      (is (not (contains? d :source))
          "no :source key when the host captured no coordinates")
      (is (fn? (:handler d)) "the handler is still lifted")
      (is (= "no coords" (get-in d [:metadata :doc]))
          "the metadata is still projected"))))

;; ---------------------------------------------------------------------------
;; (2) the sugar path updates it — reg-* is reflected, no invalidation step
;; ---------------------------------------------------------------------------

(deftest sugar-path-updates-the-projection
  (testing "an ordinary reg-* (the sugar path) is reflected by the NEXT
            projection — the registrar is the single source of truth and the
            app value is a projection over it, so there is nothing to invalidate"
    (is (nil? (get-in (av/app-value) [:registrations :event :av/sugar]))
        "absent before registration")
    (rf/reg-event-db :av/sugar {:doc "sugar"} (fn [db _] db))
    (is (some? (get-in (av/app-value) [:registrations :event :av/sugar]))
        "present in the next projection after the sugar-path reg-* — no
         invalidation step, no desync")))

(deftest sugar-path-and-unregister-round-trip
  (testing "unregister removes the descriptor from the next projection too —
            the projection tracks the registrar exactly"
    (rf/reg-event-db :av/ephemeral {:doc "ephemeral"} (fn [db _] db))
    (is (some? (get-in (av/app-value) [:registrations :event :av/ephemeral]))
        "present after registration")
    (registrar/unregister! :event :av/ephemeral)
    (is (nil? (get-in (av/app-value) [:registrations :event :av/ephemeral]))
        "absent in the next projection after unregister")))

;; ---------------------------------------------------------------------------
;; (3) the projection is RECOMPUTABLE
;; ---------------------------------------------------------------------------

(deftest projection-is-recomputable-equal-values
  (testing "two projections taken at the same registrar state are EQUAL values
            — the projection is pure with respect to the registrar deref"
    (rf/reg-event-db :av/r1 {:doc "r1"} (fn [db _] db))
    (rf/reg-sub :av/r2 {:doc "r2"} (fn [db _] (:r2 db)))
    (is (= (av/app-value) (av/app-value))
        "recomputing at the same registrar state yields an equal app value")))

(deftest projection-reflects-hot-reload-re-registration
  (testing "re-registering an id (hot-reload) is reflected by the next
            projection for free — the descriptor carries the NEW handler"
    (let [h1 (fn handler-1 [db _] (assoc db :v 1))
          h2 (fn handler-2 [db _] (assoc db :v 2))]
      (rf/reg-event-db :av/reload {:doc "v1"} h1)
      (is (identical? h1 (get-in (av/app-value)
                                 [:registrations :event :av/reload :handler]))
          "the projection carries the first handler")
      ;; Hot-reload: re-register the same id with a different handler.
      (rf/reg-event-db :av/reload {:doc "v2"} h2)
      (is (identical? h2 (get-in (av/app-value)
                                 [:registrations :event :av/reload :handler]))
          "the next projection carries the new handler — no invalidation step")
      (is (= "v2" (get-in (av/app-value)
                          [:registrations :event :av/reload :metadata :doc]))
          "the re-registered :doc is reflected too"))))

(deftest empty-realm-projects-an-empty-app
  (testing "a realm whose registrar is empty projects an app value with an
            empty :registrations — still a valid app value, never an error"
    (registrar/clear-all!)
    (let [v (av/app-value)]
      (is (= :rf.realm/default (:rf.app/id v)))
      (is (= {} (:registrations v))
          "an empty registrar projects an empty registrations map")
      (is (= #{} (:rf.app/requires v))))))

;; ---------------------------------------------------------------------------
;; (4) the realm-side installed-app seam (the late-bind bridge)
;; ---------------------------------------------------------------------------

(deftest installed-app-seam-returns-the-projection
  (testing "realm/installed-app returns the recomputable projection over the
            realm's registrar, reached through the :app-value/project hook"
    (rf/reg-event-db :av/seam {:doc "seam"} (fn [db _] db))
    (is (= (av/app-value) (realm/installed-app))
        "the realm-side seam equals the direct projection (default realm)")
    (is (= (av/app-value :rf.realm/default)
           (realm/installed-app :rf.realm/default))
        "explicit default-realm id resolves the same way")
    (is (= (av/app-value) (realm/installed-app nil))
        "nil resolves to the default realm (absence = default realm)")))

(deftest installed-app-reconciles-stored-app-with-live-projection
  (testing "installed-app reconciles a STORED :app slot WITH the live registrar
            projection (rf2-77ewnm): the seated value's identity / :modules /
            :rf.app/requires provenance is overlaid, but the registrations are
            always the LIVE registrar's, so the public read never desyncs from the
            registrar / av/app-value. (Was: stored slot returned verbatim,
            precedence over projection — the desync the reconcile fix closes.)"
    ;; Seat a sentinel :app on a fresh test realm — the shape a stage-7 install!
    ;; would write. Its :rf.app/id + :rf.app/requires must overlay the live projection.
    (let [sentinel {:rf.app/id :av/sentinel
                    :modules   {:av/m {:rf.module/id :av/m :rf.module/owns {} :rf.module/requires #{}}}
                    :registrations {}
                    :rf.app/requires  #{:rf.capability/example}}
          test-rid :av/test-realm]
      (swap! realm/realms assoc test-rid
             (assoc (realm/make-realm test-rid) :app sentinel))
      (let [read (realm/installed-app test-rid)
            proj (av/app-value test-rid)]
        (is (= :av/sentinel (:rf.app/id read))
            "the seated app's identity is overlaid")
        (is (= {:av/m {:rf.module/id :av/m :rf.module/owns {} :rf.module/requires #{}}} (:modules read))
            "the seated app's :modules provenance is overlaid")
        (is (= #{:rf.capability/example} (:rf.app/requires read))
            "the seated app's :rf.app/requires is overlaid")
        (is (= (:registrations proj) (:registrations read))
            "the registrations are the LIVE projection's — not the frozen
             sentinel's empty map (the source-of-truth reconcile)"))
      ;; Cleanup the test realm so the registry returns to its fixture state.
      (swap! realm/realms dissoc test-rid))))

;; ---------------------------------------------------------------------------
;; (5) default-realm ergonomics are UNCHANGED — the headline
;; ---------------------------------------------------------------------------

(deftest default-realm-ergonomics-unchanged
  (testing "reg-* / dispatch / subscribe behave EXACTLY as before — the
            app-value projection is read-only and adds no registration-path
            behaviour (zero ergonomic regression)"
    (rf/reg-frame :av/app {:doc "ergonomics app"})
    (rf/reg-event-db :av/set {:doc "set n"} (fn [db [_ v]] (assoc db :n v)))
    (rf/reg-sub :av/read-n {:doc "read n"} (fn [db _] (:n db)))
    ;; Dispatch + subscribe against the default-realm frame — unchanged.
    (rf/dispatch-sync [:av/set 9] {:frame :av/app})
    (is (= {:n 9} (rf/app-db-value :av/app))
        "app-db carries the dispatched value — single-app dispatch unchanged")
    (let [reaction (rf/subscribe :av/app [:av/read-n])]
      (is (= 9 @reaction)
          "subscribe reads the value — single-app subscribe unchanged"))
    ;; And the registrar read API the existing tooling uses is byte-stable.
    (is (some? (registrar/lookup :event :av/set))
        "registrar/lookup resolves a default-realm registration unchanged")))
