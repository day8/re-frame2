(ns re-frame.events-test
  "Per rf2-bpmszk (the rf2-iczn3 resolution, Mike 2026-06-10) — the
  `reg-event-*` metadata-map carries a RESERVED `:interceptors` key, making
  the map the ONE superset middle-slot shape. The positional interceptor
  vector form (`[i1 i2]`) becomes definable SUGAR — `[i1 i2]` ≡
  `{:interceptors [i1 i2]}` — threaded into the identical position with
  identical semantics (declaration order; `:before` in order, `:after`
  reversed). Additive: zero call-site migration; the front-porch
  `(reg-event-db :id handler)` is unchanged.

  This SUPERSEDES the former rf2-bbea warning
  (`:rf.warning/interceptors-in-metadata-map`): `:interceptors` inside the
  metadata-map is now the documented home, not a typo. Two guard rails keep
  the superset honest: supplying interceptors via BOTH the map key AND the
  positional vector is a loud `:rf.error/interceptors-supplied-twice`
  (one-home-per-fact, EP-0007); a malformed `:interceptors` value is a loud
  `:rf.error/reg-event-bad-interceptors`."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.interceptor :as interceptor]
            [re-frame.late-bind :as late-bind]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.trace :as trace]))

;; ---- fixtures -------------------------------------------------------------

(defn reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (when-let [clear-schemas! (late-bind/get-fn :schemas/clear-by-frame!)]
    (clear-schemas!))
  (trace/clear-listeners!)
  (rf/init! plain-atom/adapter)
  ;; EP-0002 (rf2-9o48ih): `init!` no longer synthesises `:rf/default`;
  ;; framework operation surfaces require a carried frame stamp. Register
  ;; `:rf/default` + pin it as the body's ambient scope (the carried-
  ;; invariant equivalent of `(with-frame :rf/default …)`); explicit
  ;; `{:frame …}` opts in the test bodies still win.
  (rf/reg-frame :rf/default {})
  (rf/with-frame :rf/default
    (test-fn)))

(use-fixtures :each reset-runtime)

;; ---- helpers --------------------------------------------------------------

(defn- record-traces!
  "Attach a recording listener and return its atom. Forgetting to remove
  the listener doesn't matter — the fixture clears all listeners between
  deftests."
  [listener-id]
  (let [a (atom [])]
    (rf/register-listener! listener-id (fn [ev] (swap! a conj ev)))
    a))

(defn- warning-events
  [recorded operation]
  (filterv (fn [ev]
             (and (= :warning (:op-type ev))
                  (= operation (:operation ev))))
           @recorded))

(defn- error-events
  [recorded operation]
  (filterv (fn [ev]
             (and (= :error (:op-type ev))
                  (= operation (:operation ev))))
           @recorded))

(def ^:private noop-icpt
  ;; A no-op interceptor; just enough to populate a positional vector.
  {:id     :test/noop
   :before identity
   :after  identity})

;; ---- tests ----------------------------------------------------------------

(deftest metadata-map-interceptors-is-the-superset-form
  ;; Per rf2-bpmszk — `:interceptors` inside the metadata-map is now THE
  ;; superset middle-slot shape: the chain is threaded into the same position
  ;; the positional vector occupies, and the registrar carries it on the
  ;; effective `:interceptors` chain (user chain + the framework wrapper).
  (testing "reg-event-db with metadata-map :interceptors threads the chain (NOT dropped)"
    (let [recorded (record-traces! ::db-super)]
      (rf/reg-event-db :test.bpmszk/db-super
        {:doc "Superset form." :interceptors [noop-icpt]}
        (fn [db _] db))
      (is (empty? (warning-events recorded :rf.warning/interceptors-in-metadata-map))
          "the superset form does NOT fire the retired metadata-misuse warning")
      (let [meta (rf/handler-meta :event :test.bpmszk/db-super)
            ids  (mapv :id (:interceptors meta))]
        (is (= "Superset form." (:doc meta))
            "the reflection metadata is retained on the registry entry")
        (is (not (contains? meta :interceptors-as-raw))
            "the raw key is not duplicated under another name")
        (is (= [:test/noop :rf/db-handler] ids)
            "the metadata-map :interceptors chain sits before the runtime wrapper")
        (is (= :db (:event/kind meta))))))

  (testing "reg-event-fx with metadata-map :interceptors threads the chain"
    (rf/reg-event-fx :test.bpmszk/fx-super
      {:interceptors [noop-icpt]}
      (fn [_ _] {:db {}}))
    (let [ids (mapv :id (:interceptors (rf/handler-meta :event :test.bpmszk/fx-super)))]
      (is (= [:test/noop :rf/fx-handler] ids))))

  (testing "reg-event-ctx with metadata-map :interceptors threads the chain"
    (rf/reg-event-ctx :test.bpmszk/ctx-super
      {:interceptors [noop-icpt]}
      (fn [ctx] ctx))
    (let [ids (mapv :id (:interceptors (rf/handler-meta :event :test.bpmszk/ctx-super)))]
      (is (= [:test/noop :rf/ctx-handler] ids)))))

(deftest metadata-map-interceptors-runs-the-chain-identically
  ;; The chain registered via the metadata-map MUST run with identical
  ;; ordering semantics to the positional vector form: `:before` in
  ;; declaration order, `:after` in reverse declaration order.
  (testing "two interceptors via map :interceptors run :before in order, :after reversed"
    (let [order (atom [])
          mk    (fn [tag]
                  {:id     (keyword "test.bpmszk" (str "ord-" (name tag)))
                   :before (fn [ctx] (swap! order conj [:before tag]) ctx)
                   :after  (fn [ctx] (swap! order conj [:after tag]) ctx)})]
      (rf/reg-event-db :test.bpmszk/ordered
        {:interceptors [(mk :a) (mk :b)]}
        (fn [db _] (swap! order conj [:handler]) db))
      (rf/dispatch-sync [:test.bpmszk/ordered])
      (is (= [[:before :a] [:before :b] [:handler] [:after :b] [:after :a]]
             @order)
          ":before runs in declaration order; :after runs reversed — identical to the positional form"))))

(deftest interceptors-supplied-twice-is-rejected-loudly
  ;; Per rf2-bpmszk both-places guard: interceptors via the map :interceptors
  ;; AND the positional vector at once is a LOUD :rf.error/interceptors-supplied-twice
  ;; (never a silent merge; EP-0007 one-home-per-fact).
  (testing "metadata-map :interceptors + positional vector throws"
    (let [other {:id :test.bpmszk/other :before identity :after identity}
          ex    (try (rf/reg-event-db :test.bpmszk/twice
                       {:doc "both" :interceptors [noop-icpt]}
                       [other]
                       (fn [db _] db))
                     nil
                     (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex) "the both-places call throws")
      (is (= ":rf.error/interceptors-supplied-twice" (ex-message ex)))
      (let [data (ex-data ex)]
        (is (= :rf.error/interceptors-supplied-twice (:rf.error/id data)))
        (is (= "reg-event-db" (:reg-fn data)))
        (is (= :test.bpmszk/twice (:id data)))
        (is (= :fix-registration (:recovery data)))
        (is (string? (:reason data)))
        (is (re-find #"TWICE" (:reason data)) ":reason names the dual supply")
        (is (re-find #"metadata-map" (:reason data)) ":reason names the metadata-map source")
        (is (re-find #"positional" (:reason data)) ":reason names the positional source"))))

  (testing "the both-places rejection happens BEFORE the registry slot is written"
    (try (rf/reg-event-db :test.bpmszk/twice-no-side-effect
           {:interceptors [noop-icpt]}
           [{:id :x :before identity}]
           (fn [db _] db))
         (catch clojure.lang.ExceptionInfo _ nil))
    (is (nil? (registrar/lookup :event :test.bpmszk/twice-no-side-effect))
        "registry slot is untouched when the both-places guard throws"))

  (testing "the guard covers reg-event-fx and reg-event-ctx"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #":rf\.error/interceptors-supplied-twice"
          (rf/reg-event-fx :test.bpmszk/fx-twice
            {:interceptors [noop-icpt]}
            [{:id :y :before identity}]
            (fn [_ _] {}))))
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #":rf\.error/interceptors-supplied-twice"
          (rf/reg-event-ctx :test.bpmszk/ctx-twice
            {:interceptors [noop-icpt]}
            [{:id :z :after identity}]
            (fn [ctx] ctx)))))

  (testing "a metadata-map :interceptors with an EMPTY positional vector does NOT trip the guard"
    ;; An empty positional vector contributes no interceptors, so there is no
    ;; second source — the map form is honoured.
    (is (= :test.bpmszk/empty-positional
           (rf/reg-event-db :test.bpmszk/empty-positional
             {:interceptors [noop-icpt]}
             []
             (fn [db _] db))))
    (let [ids (mapv :id (:interceptors (rf/handler-meta :event :test.bpmszk/empty-positional)))]
      (is (= [:test/noop :rf/db-handler] ids)))))

(deftest malformed-metadata-map-interceptors-is-rejected-loudly
  ;; Per rf2-bpmszk malformed-value guard: a non-vector :interceptors value, or
  ;; a vector carrying a non-interceptor entry, is a LOUD
  ;; :rf.error/reg-event-bad-interceptors.
  (testing "a non-vector :interceptors value throws"
    (let [ex (try (rf/reg-event-db :test.bpmszk/bad-nonvec
                    {:interceptors noop-icpt}     ;; a bare map, not a vector
                    (fn [db _] db))
                  nil
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex))
      (is (= ":rf.error/reg-event-bad-interceptors" (ex-message ex)))
      (let [data (ex-data ex)]
        (is (= :rf.error/reg-event-bad-interceptors (:rf.error/id data)))
        (is (= "reg-event-db" (:reg-fn data)))
        (is (= :test.bpmszk/bad-nonvec (:id data)))
        (is (= :fix-registration (:recovery data)))
        (is (re-find #"non-vector" (:reason data))))))

  (testing "a vector with a non-interceptor entry (keyword) throws"
    (let [ex (try (rf/reg-event-db :test.bpmszk/bad-entry
                    {:interceptors [noop-icpt :not-an-interceptor]}
                    (fn [db _] db))
                  nil
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex))
      (is (= ":rf.error/reg-event-bad-interceptors" (ex-message ex)))
      (is (re-find #"non-interceptor" (:reason (ex-data ex))))))

  (testing "the malformed rejection happens BEFORE the registry slot is written"
    (try (rf/reg-event-db :test.bpmszk/bad-no-side-effect
           {:interceptors :nope}
           (fn [db _] db))
         (catch clojure.lang.ExceptionInfo _ nil))
    (is (nil? (registrar/lookup :event :test.bpmszk/bad-no-side-effect))
        "registry slot is untouched when the malformed guard throws"))

  (testing "an empty :interceptors vector is legitimate (no chain), not malformed"
    (is (= :test.bpmszk/empty-ok
           (rf/reg-event-db :test.bpmszk/empty-ok
             {:doc "no chain" :interceptors []}
             (fn [db _] db))))
    (let [ids (mapv :id (:interceptors (rf/handler-meta :event :test.bpmszk/empty-ok)))]
      (is (= [:rf/db-handler] ids) "no user interceptors; only the runtime wrapper"))))

(deftest sugar-equivalence-vector-equals-map-interceptors
  ;; Per rf2-bpmszk item 4: `[i1 i2]` ≡ `{:interceptors [i1 i2]}` — the two
  ;; middle-slot forms produce identical registrar entries (the raw key is
  ;; stripped, so there is no "modulo the retained key" delta; the effective
  ;; `:interceptors` chain is the single introspection surface).
  (testing "vector form and map form yield identical registrar entries"
    (let [a {:id :test.bpmszk/sugar-a :before identity :after identity}
          b {:id :test.bpmszk/sugar-b :before identity}]
      (rf/reg-event-db :test.bpmszk/via-vector
        [a b]
        (fn [db _] db))
      (rf/reg-event-db :test.bpmszk/via-map
        {:interceptors [a b]}
        (fn [db _] db))
      (let [vec-meta (-> (rf/handler-meta :event :test.bpmszk/via-vector)
                         (dissoc :handler-fn))   ;; the handler-fn differs by identity only
            map-meta (-> (rf/handler-meta :event :test.bpmszk/via-map)
                         (dissoc :handler-fn))]
        ;; The map form must NOT carry a stray raw :interceptors retain key.
        (is (= (mapv :id (:interceptors vec-meta))
               (mapv :id (:interceptors map-meta)))
            "both forms register the identical effective chain (ids + order)")
        ;; Neither entry carries any key the other lacks — clean sugar-equivalence.
        (is (= (set (keys vec-meta)) (set (keys map-meta)))
            "both registrar entries carry the identical key set (no retained-key delta)")))))

(deftest correct-positional-form-stays-silent
  (testing "reg-event-db with interceptors in the positional slot does NOT warn"
    (let [recorded (record-traces! ::db-quiet)]
      (rf/reg-event-db :test.bbea/db-good
        [noop-icpt]
        (fn [db _] db))
      (is (empty? (warning-events recorded :rf.warning/interceptors-in-metadata-map)))))

  (testing "reg-event-db with metadata-map (no :interceptors) AND positional interceptors does NOT warn"
    (let [recorded (record-traces! ::db-good-2)]
      (rf/reg-event-db :test.bbea/db-good-2
        {:doc "Right shape — metadata for reflection, interceptors positional."}
        [noop-icpt]
        (fn [db _] db))
      (is (empty? (warning-events recorded :rf.warning/interceptors-in-metadata-map)))))

  (testing "reg-event-db with metadata-map alone (no interceptors anywhere) does NOT warn"
    (let [recorded (record-traces! ::db-good-3)]
      (rf/reg-event-db :test.bbea/db-good-3
        {:doc "Plain metadata-only registration."}
        (fn [db _] db))
      (is (empty? (warning-events recorded :rf.warning/interceptors-in-metadata-map))))))

;; ---- clear-event round-trip (rf2-6z20) -----------------------------------
;;
;; Per Spec 002 / API.md row §Lifecycle: `rf/clear-event` is the public
;; alias of `events/clear-event` (re-exported at `core.cljc:867`), used
;; by hot-reload tooling and per-test isolation fixtures. Two arities:
;;
;;   (rf/clear-event)        ;; clear every registered :event
;;   (rf/clear-event :id)    ;; clear one event by id
;;
;; Pre-rf2-6z20 neither arity was touched in any test. A regression
;; that left the registry slot populated would only surface through
;; integration symptoms (a stale handler still firing).

(deftest clear-event-removes-a-single-handler
  (testing "(rf/clear-event id) removes the registered :event slot;
            a subsequent dispatch traces :rf.error/no-such-handler"
    (rf/reg-event-db :test.6z20/foo (fn [db _] (assoc db :touched? true)))
    ;; Pre-clear: reachable via lookup AND dispatch.
    (is (some? (registrar/lookup :event :test.6z20/foo))
        "the event handler is reachable via registrar/lookup pre-clear")
    (rf/dispatch-sync [:test.6z20/foo])
    (is (true? (:touched? (rf/app-db-value :rf/default)))
        "the handler ran when registered")

    ;; Clear.
    (rf/clear-event :test.6z20/foo)

    ;; Post-clear: gone from the registry, dispatch traces no-such-handler.
    (is (nil? (registrar/lookup :event :test.6z20/foo))
        "registry slot is gone after clear-event")
    (let [recorded (record-traces! ::post-clear)]
      (rf/dispatch-sync [:test.6z20/foo])
      (let [errs (filterv #(= :rf.error/no-such-handler (:operation %))
                          @recorded)]
        (is (= 1 (count errs))
            "a subsequent dispatch traces :rf.error/no-such-handler")
        (is (= :test.6z20/foo (-> errs first :tags :rf.trace/event-id))
            ":rf.trace/event-id carries the cleared handler's id")))))

(deftest clear-event-no-arg-clears-every-event
  (testing "(rf/clear-event) with no args clears every registered :event id"
    ;; Per events.cljc:227, the no-arg form is documented:
    ;;   ([] (registrar/clear-kind! :event))
    ;; This tests confirms the contract.
    (rf/reg-event-db :test.6z20/a (fn [db _] db))
    (rf/reg-event-db :test.6z20/b (fn [db _] db))
    (rf/reg-event-fx :test.6z20/c (fn [_ _] {}))
    (is (some? (registrar/lookup :event :test.6z20/a)))
    (is (some? (registrar/lookup :event :test.6z20/b)))
    (is (some? (registrar/lookup :event :test.6z20/c)))

    (rf/clear-event)

    (is (nil? (registrar/lookup :event :test.6z20/a))
        "all :event slots cleared by no-arg form")
    (is (nil? (registrar/lookup :event :test.6z20/b)))
    (is (nil? (registrar/lookup :event :test.6z20/c)))))

(deftest clear-event-leaves-other-kinds-untouched
  (testing "clear-event only touches :event; :sub, :fx, :cofx are preserved"
    ;; Defence-in-depth: confirm clear-event is narrow.
    (rf/reg-event-db :test.6z20/ev (fn [db _] db))
    (rf/reg-sub :test.6z20/sub (fn [_ _] :stub))
    (rf/reg-fx :test.6z20/fx (fn [_ _] nil))
    (rf/reg-cofx :test.6z20/cofx (fn [ctx] ctx))
    (rf/clear-event)
    (is (nil? (registrar/lookup :event :test.6z20/ev))
        ":event was cleared")
    (is (some? (registrar/lookup :sub :test.6z20/sub))
        ":sub kind is untouched")
    (is (some? (registrar/lookup :fx :test.6z20/fx))
        ":fx kind is untouched")
    (is (some? (registrar/lookup :cofx :test.6z20/cofx))
        ":cofx kind is untouched")))

;; ---- reg-event-fx bad return (rf2-k3bj) ----------------------------------
;;
;; Per rf2-k3bj — a `reg-event-fx` handler is contracted to return a map
;; (or nil, the documented no-op). Any other return type (vector, number,
;; string, ...) is a thinko: the runtime cannot extract `:db` / `:fx` and
;; cannot guess the handler's intent. Pre-fix the body silently no-opped.
;; The fix emits `:rf.error/effect-handler-bad-return` (Spec 009 §Error
;; contract, :recovery :no-recovery) so the misuse surfaces in dev / 10x.

(deftest reg-event-fx-non-map-return-traces-bad-return-error
  (testing "handler returning a string emits :rf.error/effect-handler-bad-return; app-db unchanged"
    (let [recorded (record-traces! ::bad-string)]
      (rf/reg-event-fx :test.k3bj/string-return
        (fn [_ _] "hello"))
      (let [db-before (rf/app-db-value :rf/default)]
        (rf/dispatch-sync [:test.k3bj/string-return])
        (let [errs (error-events recorded :rf.error/effect-handler-bad-return)]
          (is (= 1 (count errs))
              (str "expected exactly one :rf.error/effect-handler-bad-return, got " (count errs)))
          (let [t (:tags (first errs))]
            (is (= :test.k3bj/string-return (:event-id t)))
            (is (= [:test.k3bj/string-return] (:event t)))
            (is (= "hello" (:returned t)))
            (is (= (type "hello") (:returned-type t)))
            (is (string? (:reason t)))
            (is (re-find #"non-map" (:reason t))))
          (is (= :no-recovery (:recovery (first errs)))))
        (is (= db-before (rf/app-db-value :rf/default))
            "app-db is unchanged after a no-op recovery"))))

  (testing "handler returning a number emits :rf.error/effect-handler-bad-return"
    (let [recorded (record-traces! ::bad-number)]
      (rf/reg-event-fx :test.k3bj/number-return
        (fn [_ _] 42))
      (rf/dispatch-sync [:test.k3bj/number-return])
      (let [errs (error-events recorded :rf.error/effect-handler-bad-return)]
        (is (= 1 (count errs)))
        (is (= 42 (:returned (:tags (first errs))))))))

  (testing "handler returning a vector emits :rf.error/effect-handler-bad-return"
    (let [recorded (record-traces! ::bad-vector)]
      (rf/reg-event-fx :test.k3bj/vector-return
        (fn [_ _] [[:dispatch [:other]]]))
      (rf/dispatch-sync [:test.k3bj/vector-return])
      (let [errs (error-events recorded :rf.error/effect-handler-bad-return)]
        (is (= 1 (count errs)))
        (is (= [[:dispatch [:other]]] (:returned (:tags (first errs)))))))))

(deftest reg-event-fx-nil-return-stays-silent
  (testing "handler returning nil is a documented legal no-op; no :rf.error/effect-handler-bad-return"
    (let [recorded (record-traces! ::nil-quiet)]
      (rf/reg-event-fx :test.k3bj/nil-return
        (fn [_ _] nil))
      (let [db-before (rf/app-db-value :rf/default)]
        (rf/dispatch-sync [:test.k3bj/nil-return])
        (is (empty? (error-events recorded :rf.error/effect-handler-bad-return))
            "nil is the documented no-op return and must not fire the bad-return error")
        (is (= db-before (rf/app-db-value :rf/default))
            "app-db is unchanged after a nil-return no-op")))))

(deftest reg-event-fx-map-return-still-works
  (testing "handler returning a well-shaped {:db ...} effect-map still applies"
    (let [recorded (record-traces! ::map-good)]
      (rf/reg-event-fx :test.k3bj/map-return
        (fn [_ _] {:db {:k3bj/touched? true}}))
      (rf/dispatch-sync [:test.k3bj/map-return])
      (is (empty? (error-events recorded :rf.error/effect-handler-bad-return))
          "a map return must not fire the bad-return error")
      (is (true? (:k3bj/touched? (rf/app-db-value :rf/default)))
          ":db was applied as the effect-map specifies"))))

;; ---- normalise-args: all four documented user-facing shapes (rf2-fuudi) --
;;
;; Per the `reg-event-db` docstring (events.cljc), the variadic tail accepts
;; four shapes:
;;
;;   (reg-event-db :id                       handler)             ;; tail = 1
;;   (reg-event-db :id {:doc "..."}          handler)             ;; tail = 2 (meta)
;;   (reg-event-db :id [icpt]                handler)             ;; tail = 2 (intc)
;;   (reg-event-db :id {:doc "..."} [icpt]   handler)             ;; tail = 3
;;
;; `normalise-args` dispatches on the *tail* count via `case`. The 4-shape
;; (count=3) is the one historically miscounted by audit notes that compared
;; the full call-site arity (4) against the case branches (1/2/3). This
;; deftest locks in all four shapes: each must register cleanly, retain the
;; positional interceptors, surface the metadata, and dispatch cleanly. (The
;; retired rf2-bbea `:rf.warning/interceptors-in-metadata-map` no longer fires
;; for any shape — rf2-bpmszk made the metadata-map `:interceptors` the
;; documented superset home; the empty-warnings assertion below is a guard
;; against any accidental resurrection.)

(deftest normalise-args-accepts-all-four-documented-shapes
  (let [recorded (record-traces! ::shapes)
        marker   {:id :test.fuudi/marker :before identity :after identity}]
    (testing "shape 1 — bare handler: (reg-event-db :id handler)"
      (rf/reg-event-db :test.fuudi/shape-1
        (fn [db _] (assoc db :test.fuudi/touched-1? true)))
      (rf/dispatch-sync [:test.fuudi/shape-1])
      (is (true? (:test.fuudi/touched-1? (rf/app-db-value :rf/default))))
      (let [meta (rf/handler-meta :event :test.fuudi/shape-1)]
        (is (= :db (:event/kind meta)))
        (is (= 1 (count (:interceptors meta)))
            "no user interceptors; chain holds only the runtime :rf/db-handler wrapper")))

    (testing "shape 2 — metadata middle: (reg-event-db :id {:doc \"...\"} handler)"
      (rf/reg-event-db :test.fuudi/shape-2
        {:doc "metadata-only middle slot"}
        (fn [db _] (assoc db :test.fuudi/touched-2? true)))
      (rf/dispatch-sync [:test.fuudi/shape-2])
      (is (true? (:test.fuudi/touched-2? (rf/app-db-value :rf/default))))
      (let [meta (rf/handler-meta :event :test.fuudi/shape-2)]
        (is (= "metadata-only middle slot" (:doc meta))
            ":doc from the metadata-map is retained on the registry entry")
        (is (= 1 (count (:interceptors meta)))
            "no user interceptors; chain holds only the runtime :rf/db-handler wrapper")))

    (testing "shape 3 — interceptor-vector middle: (reg-event-db :id [icpt] handler)"
      (rf/reg-event-db :test.fuudi/shape-3
        [marker]
        (fn [db _] (assoc db :test.fuudi/touched-3? true)))
      (rf/dispatch-sync [:test.fuudi/shape-3])
      (is (true? (:test.fuudi/touched-3? (rf/app-db-value :rf/default))))
      (let [meta (rf/handler-meta :event :test.fuudi/shape-3)
            ids  (mapv :id (:interceptors meta))]
        (is (= [:test.fuudi/marker :rf/db-handler] ids)
            "the user interceptor sits before the runtime wrapper in registration order")))

    (testing "shape 4 — both middle slots: (reg-event-db :id {:doc \"...\"} [icpt] handler)"
      ;; This is the shape historically described as the '4-arity branch';
      ;; in `normalise-args` it triggers (case (count args)) → 3 because
      ;; `id` is the head &-rest separator and is not in `args`.
      (rf/reg-event-db :test.fuudi/shape-4
        {:doc "metadata AND positional interceptors"}
        [marker]
        (fn [db _] (assoc db :test.fuudi/touched-4? true)))
      (rf/dispatch-sync [:test.fuudi/shape-4])
      (is (true? (:test.fuudi/touched-4? (rf/app-db-value :rf/default))))
      (let [meta (rf/handler-meta :event :test.fuudi/shape-4)
            ids  (mapv :id (:interceptors meta))]
        (is (= "metadata AND positional interceptors" (:doc meta))
            ":doc from the metadata-map is retained on the registry entry")
        (is (= [:test.fuudi/marker :rf/db-handler] ids)
            "the user interceptor sits before the runtime wrapper in registration order")))

    (testing "none of the four shapes fire :rf.warning/interceptors-in-metadata-map"
      (is (empty? (warning-events recorded :rf.warning/interceptors-in-metadata-map))
          "all four shapes are well-formed; no metadata-misuse warning expected"))))

(deftest normalise-args-fx-and-ctx-also-accept-the-four-shape
  (testing "reg-event-fx accepts (id metadata interceptors handler)"
    (let [marker {:id :test.fuudi/fx-marker :before identity :after identity}]
      (rf/reg-event-fx :test.fuudi/fx-shape-4
        {:doc "fx-handler, both middle slots"}
        [marker]
        (fn [_ _] {:db {:test.fuudi/fx-touched? true}}))
      (rf/dispatch-sync [:test.fuudi/fx-shape-4])
      (is (true? (:test.fuudi/fx-touched? (rf/app-db-value :rf/default))))
      (let [meta (rf/handler-meta :event :test.fuudi/fx-shape-4)
            ids  (mapv :id (:interceptors meta))]
        (is (= :fx (:event/kind meta)))
        (is (= "fx-handler, both middle slots" (:doc meta)))
        (is (= [:test.fuudi/fx-marker :rf/fx-handler] ids)))))

  (testing "reg-event-ctx accepts (id metadata interceptors handler)"
    (let [marker {:id :test.fuudi/ctx-marker :before identity :after identity}]
      (rf/reg-event-ctx :test.fuudi/ctx-shape-4
        {:doc "ctx-handler, both middle slots"}
        [marker]
        (fn [ctx]
          ;; reach into the context properly via the interceptor API, then
          ;; set a :db effect so we can assert the handler actually ran
          (let [db (interceptor/get-coeffect ctx :db)]
            (interceptor/assoc-effect ctx :db
                                      (assoc db :test.fuudi/ctx-touched? true)))))
      (rf/dispatch-sync [:test.fuudi/ctx-shape-4])
      (is (true? (:test.fuudi/ctx-touched? (rf/app-db-value :rf/default))))
      (let [meta (rf/handler-meta :event :test.fuudi/ctx-shape-4)
            ids  (mapv :id (:interceptors meta))]
        (is (= :ctx (:event/kind meta)))
        (is (= "ctx-handler, both middle slots" (:doc meta)))
        (is (= [:test.fuudi/ctx-marker :rf/ctx-handler] ids))))))

(deftest normalise-args-rejects-overlong-and-malformed
  (testing "tail count > 3 throws the arity error"
    (let [ex (try
               (rf/reg-event-db :test.fuudi/too-many
                 {:doc "..."}
                 [{:id :a :before identity :after identity}]
                 (fn [db _] db)
                 :surplus)
               nil
               (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex))
      (is (= ":rf.error/reg-event-bad-arity" (ex-message ex))
          "message is the stringified discriminator kw")
      (is (= :rf.error/reg-event-bad-arity (:rf.error/id (ex-data ex))))
      (is (re-find #"reg-event-\*" (:reason (ex-data ex)))
          ":reason names the arity error")))
  (testing "two-arg middle slot that is neither a map nor a vector throws"
    (let [ex (try
               (rf/reg-event-db :test.fuudi/bad-middle
                 "not-a-map-or-vector"
                 (fn [db _] db))
               nil
               (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex))
      (is (= ":rf.error/reg-event-bad-middle-slot" (ex-message ex)))
      (is (= :rf.error/reg-event-bad-middle-slot (:rf.error/id (ex-data ex))))
      (is (re-find #"interceptor-vector" (:reason (ex-data ex)))))))

;; ---- rf2-twt7m Change 3 — :rf/default? tag on auto-wrappers ---------------
;;
;; The framework auto-wraps the user handler into a kind-appropriate
;; interceptor (:rf/db-handler / :rf/fx-handler / :rf/ctx-handler).
;; Pre-rf2-twt7m a tool wanting to distinguish "framework default" from
;; "user supplied" had to maintain a hardcoded allowlist of these three
;; ids. Per rf2-twt7m Change 3 the auto-wrapper carries `:rf/default?
;; true` on the interceptor map itself; self-describing.
;;
;; Xray, Story, and the Event lens redesign (rf2-zh2qc) read
;; `(rf/handler-meta :event id) :interceptors` and filter
;; `(remove :rf/default?)` to surface only the user's interceptor chain.

(deftest auto-wrapper-carries-rf-default-tag
  (testing "reg-event-db auto-wrapper has :rf/default? true"
    (rf/reg-event-db :test.twt7m/db-handler (fn [db _] db))
    (let [interceptors (-> (rf/handler-meta :event :test.twt7m/db-handler)
                           :interceptors)
          auto-wrapper (last interceptors)]
      (is (= :rf/db-handler (:id auto-wrapper))
          "the auto-wrapper sits at the tail of the interceptor chain")
      (is (= true (:rf/default? auto-wrapper))
          "the auto-wrapper carries :rf/default? true")))

  (testing "reg-event-fx auto-wrapper has :rf/default? true"
    (rf/reg-event-fx :test.twt7m/fx-handler (fn [_ _] {}))
    (let [interceptors (-> (rf/handler-meta :event :test.twt7m/fx-handler)
                           :interceptors)
          auto-wrapper (last interceptors)]
      (is (= :rf/fx-handler (:id auto-wrapper)))
      (is (= true (:rf/default? auto-wrapper)))))

  (testing "reg-event-ctx auto-wrapper has :rf/default? true"
    (rf/reg-event-ctx :test.twt7m/ctx-handler (fn [ctx] ctx))
    (let [interceptors (-> (rf/handler-meta :event :test.twt7m/ctx-handler)
                           :interceptors)
          auto-wrapper (last interceptors)]
      (is (= :rf/ctx-handler (:id auto-wrapper)))
      (is (= true (:rf/default? auto-wrapper))))))

(deftest user-supplied-interceptors-do-not-carry-rf-default-tag
  (testing "user-supplied interceptors do NOT carry :rf/default? true —
   only the framework-auto-wrapper at the chain tail does"
    (let [user-icpt {:id :test.twt7m/user :before identity :after identity}]
      (rf/reg-event-db :test.twt7m/with-user-icpt
        [user-icpt]
        (fn [db _] db))
      (let [interceptors (-> (rf/handler-meta :event :test.twt7m/with-user-icpt)
                             :interceptors)
            user-slot    (first interceptors)
            auto-wrapper (last interceptors)]
        (is (= 2 (count interceptors))
            "user interceptor + auto-wrapper = 2 entries")
        (is (= :test.twt7m/user (:id user-slot)))
        (is (not (contains? user-slot :rf/default?))
            "user-supplied interceptor carries no :rf/default? slot")
        (is (= true (:rf/default? auto-wrapper))
            "only the auto-wrapper carries :rf/default? true")))))

(deftest tooling-can-filter-defaults-via-rf-default-tag
  (testing "the self-describing tag lets tools filter without an id
   allowlist — `(remove :rf/default?)` surfaces user-supplied
   interceptors only"
    (let [a {:id :test.twt7m/a :before identity}
          b {:id :test.twt7m/b :after identity}]
      (rf/reg-event-db :test.twt7m/filtering
        [a b]
        (fn [db _] db))
      (let [interceptors (-> (rf/handler-meta :event :test.twt7m/filtering)
                             :interceptors)
            user-only    (vec (remove :rf/default? interceptors))]
        (is (= 3 (count interceptors)) "two user + one framework auto-wrapper")
        (is (= 2 (count user-only))
            "filtering by :rf/default? leaves the two user interceptors")
        (is (= [:test.twt7m/a :test.twt7m/b] (mapv :id user-only)))))))

;; ---- rf2-iftj4 — validate-at-boundary-interceptor without :schema is rejected at registration --
;;
;; Per Spec 010 §Production builds + rf2-iftj4 (audit rf2-ycqtv finding #8):
;; attaching `:rf.schema/at-boundary` to a handler that has no `:schema`
;; metadata is structurally meaningless — the interceptor has nothing to
;; validate against. Pre-rf2-iftj4 the registrar accepted the call and the
;; runtime emitted `:rf.warning/boundary-without-spec` at first dispatch in
;; production builds only (silent in dev). Now `register-event!` raises
;; `:rf.error/at-boundary-missing-schema` at registration time so the
;; developer learns immediately, regardless of dev/prod gate.
;;
;; These tests live alongside `events_test.clj` because the policing happens
;; inside `register-event!` (the common body of the three `reg-event-*`
;; surfaces), independently of the optional `day8/re-frame2-schemas`
;; artefact — the rejection is structural ("you attached a boundary
;; interceptor but declared no schema"), not a Malli validation. The
;; schemas-artefact test file carries the dispatch-time companion test.

(def ^:private at-boundary-stub
  ;; Surface-faithful stand-in for the boundary interceptor — same `:id` as
  ;; the canonical interceptor (`re-frame.spec/validate-at-boundary-interceptor`), which is what
  ;; `register-event!` looks for. Avoids pulling `re-frame.spec` and its
  ;; schemas-late-bind dance into this core test.
  {:id :rf.schema/at-boundary
   :before identity
   :after  identity})

(deftest at-boundary-without-schema-rejected-at-registration
  (testing "Per rf2-iftj4 — attaching :rf.schema/at-boundary to a handler
            that carries no :schema raises :rf.error/at-boundary-missing-schema
            at registration time."
    (testing "two-arg form (interceptors middle slot, no metadata-map)"
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #":rf\.error/at-boundary-missing-schema"
            (rf/reg-event-fx :test.iftj4/no-schema-2
              [at-boundary-stub]
              (fn [_ _] {})))))

    (testing "three-arg form (metadata without :schema + interceptors)"
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #":rf\.error/at-boundary-missing-schema"
            (rf/reg-event-fx :test.iftj4/no-schema-3
              {:doc "metadata-map but no :schema"}
              [at-boundary-stub]
              (fn [_ _] {})))))

    (testing "ex-data carries actionable diagnostic slots"
      (let [data (try (rf/reg-event-fx :test.iftj4/data-probe
                        [at-boundary-stub]
                        (fn [_ _] {}))
                      (catch clojure.lang.ExceptionInfo e (ex-data e)))]
        (is (= :rf.error/at-boundary-missing-schema (:rf.error/id data))
            ":rf.error/id matches the catalogued :rf.error/* category")
        (is (= "reg-event-fx" (:reg-fn data)))
        (is (= :test.iftj4/data-probe (:id data)))
        (is (string? (:reason data)))
        (is (re-find #":rf\.schema/at-boundary" (:reason data)))
        (is (re-find #":schema" (:reason data)))
        (is (= :no-recovery (:recovery data)))))

    (testing "rejection covers reg-event-db and reg-event-ctx as well"
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #":rf\.error/at-boundary-missing-schema"
            (rf/reg-event-db :test.iftj4/db-no-schema
              [at-boundary-stub]
              (fn [db _] db))))
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #":rf\.error/at-boundary-missing-schema"
            (rf/reg-event-ctx :test.iftj4/ctx-no-schema
              [at-boundary-stub]
              (fn [ctx] ctx)))))

    (testing "rejection happens BEFORE the registry slot is written"
      ;; Belt-and-braces: a failed registration must leave no partial
      ;; trace in the registrar. The `reject-...!` call is sequenced
      ;; before `registrar/register!` in `register-event!`, so the
      ;; handler-id should be absent from the :event kind after the throw.
      (try (rf/reg-event-fx :test.iftj4/no-side-effect
             [at-boundary-stub]
             (fn [_ _] {}))
           (catch clojure.lang.ExceptionInfo _ nil))
      (is (nil? (registrar/lookup :event :test.iftj4/no-side-effect))
          "registry slot is untouched when the validate-at-boundary-interceptor check throws"))))

(deftest at-boundary-with-schema-registers-cleanly
  (testing "Per rf2-iftj4 — attaching :rf.schema/at-boundary alongside a
            `:schema` metadata key completes registration without error.
            The check fires only when the schema is absent."
    (is (= :test.iftj4/with-schema
           (rf/reg-event-fx :test.iftj4/with-schema
             {:schema [:cat [:= :test.iftj4/with-schema] :int]}
             [at-boundary-stub]
             (fn [_ _] {})))
        "registration returns the event id when :schema is present"))

  (testing "registration without validate-at-boundary-interceptor is unaffected by the check"
    (is (= :test.iftj4/no-boundary
           (rf/reg-event-fx :test.iftj4/no-boundary
             (fn [_ _] {})))
        "no validate-at-boundary-interceptor, no schema, no error"))

  (testing "metadata-map without :schema is fine when validate-at-boundary-interceptor isn't attached"
    (is (= :test.iftj4/just-meta
           (rf/reg-event-fx :test.iftj4/just-meta
             {:doc "no boundary, no schema"}
             (fn [_ _] {}))))))

;; ---- rf2-3ut12 — a BARE interceptor is rejected loudly at registration ----
;;
;; Field-confirmed via the rf8 migration: `reg-event-db` / `reg-event-fx`
;; require the interceptors arg to be a VECTOR. A bare interceptor —
;; `(reg-event-db id mw/some-interceptor handler)` — used to be SILENTLY
;; dropped: an interceptor is a map (`{:id … :before … :after …}`), so the
;; two-arg branch of `normalise-args` read it as the metadata-map, the chain
;; never reached the registrar, and the interceptor never ran (no error, no
;; warning). Same silent-drop class as p806o / gro94 / cxo1h.
;;
;; The fix raises `:rf.error/reg-event-bare-interceptor` at registration
;; (ERROR, not warn — the chain cannot be honoured and a silent drop is a
;; dishonest signal; see Conventions §No silent swallow). We do NOT coerce
;; `bare → [bare]`; the caller must wrap it. These tests assert: (1) a bare
;; interceptor on BOTH the two-arg and three-arg forms, across db / fx / ctx,
;; throws; (2) a `[vector]` interceptors arg still works; (3) empty / absent
;; interceptors still works.

(def ^:private bare-icpt
  ;; A bare interceptor map — what `(->interceptor :after …)` returns. This
  ;; is exactly the shape that used to be silently dropped.
  {:id     :test.3ut12/bare
   :before identity
   :after  identity})

(deftest bare-interceptor-rejected-at-registration
  (testing "Per rf2-3ut12 — a bare interceptor (not in a vector) throws
            :rf.error/reg-event-bare-interceptor rather than being silently
            dropped."
    (testing "two-arg form: (reg-event-db id bare-icpt handler)"
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #":rf\.error/reg-event-bare-interceptor"
            (rf/reg-event-db :test.3ut12/db-bare-2
              bare-icpt
              (fn [db _] db)))))

    (testing "two-arg form covers reg-event-fx"
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #":rf\.error/reg-event-bare-interceptor"
            (rf/reg-event-fx :test.3ut12/fx-bare-2
              bare-icpt
              (fn [_ _] {})))))

    (testing "two-arg form covers reg-event-ctx"
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #":rf\.error/reg-event-bare-interceptor"
            (rf/reg-event-ctx :test.3ut12/ctx-bare-2
              bare-icpt
              (fn [ctx] ctx)))))

    (testing "three-arg form: (reg-event-db id metadata bare-icpt handler) —
              non-vector positional interceptors slot is rejected"
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #":rf\.error/reg-event-bare-interceptor"
            (rf/reg-event-db :test.3ut12/db-bare-3
              {:doc "metadata + bare interceptor in the positional slot"}
              bare-icpt
              (fn [db _] db)))))

    (testing "three-arg form covers reg-event-fx"
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #":rf\.error/reg-event-bare-interceptor"
            (rf/reg-event-fx :test.3ut12/fx-bare-3
              {:doc "metadata + bare interceptor"}
              bare-icpt
              (fn [_ _] {})))))

    (testing "a bare interceptor that carries ONLY :before is still caught"
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #":rf\.error/reg-event-bare-interceptor"
            (rf/reg-event-db :test.3ut12/before-only
              {:id :test.3ut12/before-only :before identity}
              (fn [db _] db)))))

    (testing "ex-data carries actionable diagnostic slots"
      (let [data (try (rf/reg-event-db :test.3ut12/data-probe
                        bare-icpt
                        (fn [db _] db))
                      (catch clojure.lang.ExceptionInfo e (ex-data e)))]
        (is (= :rf.error/reg-event-bare-interceptor (:rf.error/id data))
            ":rf.error/id matches the catalogued :rf.error/* category")
        (is (= "reg-event-db" (:reg-fn data)))
        (is (= :middle (:slot data)))
        (is (= :fix-registration (:recovery data)))
        (is (string? (:reason data)))
        (is (re-find #"BARE interceptor" (:reason data)))
        (is (re-find #"vector" (:reason data)))))

    (testing "rejection happens BEFORE the registry slot is written"
      (try (rf/reg-event-db :test.3ut12/no-side-effect
             bare-icpt
             (fn [db _] db))
           (catch clojure.lang.ExceptionInfo _ nil))
      (is (nil? (registrar/lookup :event :test.3ut12/no-side-effect))
          "registry slot is untouched when the bare-interceptor check throws"))))

(deftest legitimate-interceptor-forms-still-work
  (testing "Per rf2-3ut12 — the fix must NOT regress the legitimate shapes."
    (testing "a [vector] interceptors arg registers cleanly and the chain runs"
      (is (= :test.3ut12/good-vector
             (rf/reg-event-db :test.3ut12/good-vector
               [bare-icpt]
               (fn [db _] db))))
      (let [{:keys [interceptors]} (rf/handler-meta :event :test.3ut12/good-vector)
            ids (set (map :id interceptors))]
        (is (contains? ids :test.3ut12/bare)
            "the wrapped interceptor reached the registered chain (NOT dropped)")))

    (testing "metadata-map + [vector] interceptors three-arg form still works"
      (is (= :test.3ut12/good-3
             (rf/reg-event-fx :test.3ut12/good-3
               {:doc "metadata + interceptor vector"}
               [bare-icpt]
               (fn [_ _] {}))))
      (let [{:keys [interceptors]} (rf/handler-meta :event :test.3ut12/good-3)]
        (is (contains? (set (map :id interceptors)) :test.3ut12/bare))))

    (testing "absent interceptors (bare handler) still works"
      (is (= :test.3ut12/no-icpt
             (rf/reg-event-db :test.3ut12/no-icpt
               (fn [db _] db)))))

    (testing "metadata-map alone (no interceptors anywhere) still works"
      (is (= :test.3ut12/meta-only
             (rf/reg-event-db :test.3ut12/meta-only
               {:doc "plain metadata"}
               (fn [db _] db)))))

    (testing "an empty [] interceptors vector (legitimate) still works"
      (is (= :test.3ut12/empty-vec
             (rf/reg-event-db :test.3ut12/empty-vec
               []
               (fn [db _] db))))
      (is (= :test.3ut12/empty-vec-3
             (rf/reg-event-fx :test.3ut12/empty-vec-3
               {:doc "meta + empty interceptor vector"}
               []
               (fn [_ _] {})))))))
