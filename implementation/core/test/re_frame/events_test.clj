(ns re-frame.events-test
  "Per EP-0018 Slice Z (rf2-xhfxcs.14) — re-frame2 event registration is
  collapsed to the ONE public form `reg-event` (coeffects in, a closed
  effects map out). The metadata-map carries a RESERVED `:interceptors` key,
  making the map the ONE superset middle-slot shape; the historical positional
  interceptor vector middle slot is retired (the chain belongs in metadata
  `:interceptors`).

  The retired public names `reg-event-db` / `reg-event-fx` / `reg-event-ctx`
  survive ONLY as throwing stubs (`:rf.error/reg-event-db-removed` /
  `-fx-removed` / `-ctx-removed`); they register nothing. There is ONE
  handler-wrapping interceptor `:rf/event-handler` (`:rf/default? true`) on
  every event, and the historical `:event/kind` sub-tag is gone.

  This SUPERSEDES the former rf2-bbea warning
  (`:rf.warning/interceptors-in-metadata-map`): `:interceptors` inside the
  metadata-map is now the documented home, not a typo. A malformed
  `:interceptors` value is a loud `:rf.error/reg-event-bad-interceptors`."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.events :as events]
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

(def ^:private noop-icpt-value
  ;; A no-op interceptor VALUE — used at the `reg-interceptor` registration
  ;; boundary (the authoring input) and in NEGATIVE tests of the
  ;; reference-only flip (an inline value in a chain is rejected). NEVER a
  ;; legal chain entry since EP-0022 (rf2-0adhqs.9).
  {:id     :test/noop
   :before identity
   :after  identity})

(defn- reg-noop!
  "Register a no-op interceptor under `id` and return `id` (the chain REF).
  EP-0022 reference-only: chains carry refs, so tests that just need a
  populated chain register + reference rather than dropping an inline value."
  [id]
  (rf/reg-interceptor* id {:before identity :after identity})
  id)

(defn- chain-ids
  "Map a STORED (unresolved) `:interceptors` chain to a vector of authored
  ids: a ref entry (a keyword) is itself the id; an `[id arg]` ref's head is
  the id; the framework handler-wrapper (an inline value map) yields its
  `:id`. Per EP-0022 §12 (handler-meta exposes authored refs), the stored
  chain holds refs UNRESOLVED + the framework wrapper at the tail."
  [chain]
  (mapv (fn [entry]
          (cond
            (keyword? entry)            entry
            (and (vector? entry)
                 (keyword? (first entry))) (first entry)
            (map? entry)                (:id entry)
            :else                       entry))
        chain))

;; ---- tests ----------------------------------------------------------------

(deftest metadata-map-interceptors-is-the-superset-form
  ;; Per rf2-bpmszk — `:interceptors` inside the metadata-map is THE superset
  ;; middle-slot shape: the registrar carries it on the effective
  ;; `:interceptors` chain (user chain + the framework wrapper). Per EP-0018
  ;; Slice Z there is ONE form (`reg-event`) and ONE wrapper id
  ;; (`:rf/event-handler`); the former per-kind db/fx/ctx variants collapse
  ;; into this one test.
  (testing "reg-event with metadata-map :interceptors threads the chain (NOT dropped)"
    (let [recorded (record-traces! ::super)]
      (reg-noop! :test/noop)
      (rf/reg-event :test.bpmszk/super
        {:doc "Superset form." :interceptors [:test/noop]}
        (fn [{:keys [db]} _] {:db db}))
      (is (empty? (warning-events recorded :rf.warning/interceptors-in-metadata-map))
          "the superset form does NOT fire the retired metadata-misuse warning")
      (let [meta (rf/handler-meta :event :test.bpmszk/super)
            ids  (chain-ids (:interceptors meta))]
        (is (= "Superset form." (:doc meta))
            "the reflection metadata is retained on the registry entry")
        (is (not (contains? meta :interceptors-as-raw))
            "the raw key is not duplicated under another name")
        (is (= [:test/noop :rf/event-handler] ids)
            "the metadata-map :interceptors chain (authored ref) sits before the runtime wrapper")
        (is (not (contains? meta :event/kind))
            "the :event/kind sub-tag is gone (one form, no kind)")))))

(deftest metadata-map-interceptors-runs-the-chain-identically
  ;; The chain registered via the metadata-map MUST run with the interceptor
  ;; ordering semantics: `:before` in declaration order, `:after` in reverse
  ;; declaration order.
  (testing "two interceptors via map :interceptors run :before in order, :after reversed"
    (let [order (atom [])
          reg-ord! (fn [tag]
                     (let [id (keyword "test.bpmszk" (str "ord-" (name tag)))]
                       (rf/reg-interceptor* id
                         {:before (fn [ctx] (swap! order conj [:before tag]) ctx)
                          :after  (fn [ctx] (swap! order conj [:after tag]) ctx)})
                       id))]
      (rf/reg-event :test.bpmszk/ordered
        {:interceptors [(reg-ord! :a) (reg-ord! :b)]}
        (fn [{:keys [db]} _] (swap! order conj [:handler]) {:db db}))
      (rf/dispatch-sync [:test.bpmszk/ordered])
      (is (= [[:before :a] [:before :b] [:handler] [:after :b] [:after :a]]
             @order)
          ":before runs in declaration order; :after runs reversed"))))

(deftest positional-interceptor-vector-is-rejected-loudly
  ;; The positional interceptor vector middle slot is retired. The chain's
  ;; home is the metadata-map `:interceptors` key.
  (testing "two-arg positional vector middle slot throws bad-middle-slot"
    (let [ex (try (rf/reg-event :test.bpmszk/vector-middle
                    [noop-icpt-value]
                    (fn [{:keys [db]} _] {:db db}))
                  nil
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex))
      (let [data (ex-data ex)]
        (is (= :rf.error/reg-event-bad-middle-slot (:rf.error/id data)))
        (is (= 'rf/reg-event (:where data)))
        (is (= :fix-registration (:recovery data)))
        (is (= [noop-icpt-value] (:got data)))
        (is (re-find #"positional interceptor vector is retired" (:reason data)))
        (is (re-find #":interceptors" (:expected data))))))

  (testing "the vector-middle rejection happens BEFORE the registry slot is written"
    (try (rf/reg-event :test.bpmszk/vector-no-side-effect
           [noop-icpt-value]
           (fn [{:keys [db]} _] {:db db}))
         (catch clojure.lang.ExceptionInfo _ nil))
    (is (nil? (registrar/lookup :event :test.bpmszk/vector-no-side-effect))
        "registry slot is untouched when the vector-middle guard throws"))

  (testing "metadata plus positional vector is the retired three-tail shape"
    (let [ex (try (rf/reg-event :test.bpmszk/meta-plus-vector
                    {:doc "old three-tail shape"}
                    [noop-icpt-value]
                    (fn [{:keys [db]} _] {:db db}))
                  nil
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex))
      (is (= :rf.error/reg-event-bad-arity (:rf.error/id (ex-data ex))))
      (is (re-find #"interceptor chains in metadata :interceptors" (:reason (ex-data ex)))))))

(deftest malformed-metadata-map-interceptors-is-rejected-loudly
  ;; Per rf2-bpmszk malformed-value guard: a non-vector :interceptors value, or
  ;; a vector carrying a non-interceptor entry, is a LOUD
  ;; :rf.error/reg-event-bad-interceptors.
  (testing "a non-vector :interceptors value throws"
    (let [ex (try (rf/reg-event :test.bpmszk/bad-nonvec
                    {:interceptors noop-icpt-value}     ;; a bare map, not a vector
                    (fn [{:keys [db]} _] {:db db}))
                  nil
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex))
      (let [data (ex-data ex)]
        (is (= :rf.error/reg-event-bad-interceptors (:rf.error/id data)))
        (is (= "reg-event" (:reg-fn data)))
        (is (= :test.bpmszk/bad-nonvec (:id data)))
        (is (= :fix-registration (:recovery data)))
        (is (re-find #"non-vector" (:reason data))))))

  (testing "a vector with a structurally-malformed entry (a string — neither ref nor value) throws bad-interceptors"
    ;; EP-0022 reference-only: a bare keyword is a valid interceptor REFERENCE
    ;; (an UNREGISTERED keyword throws `:rf.error/unregistered-interceptor` —
    ;; covered below); an INLINE value throws `:rf.error/inline-interceptor-removed`
    ;; (covered in the dedicated test). A string / number is the unambiguous
    ;; structurally-malformed entry — neither a ref nor a value — so it is the
    ;; generic `:rf.error/reg-event-bad-interceptors`.
    (let [_ (reg-noop! :test/ref-ok)
          ex (try (rf/reg-event :test.bpmszk/bad-entry
                    {:interceptors [:test/ref-ok "not-an-interceptor"]}
                    (fn [{:keys [db]} _] {:db db}))
                  nil
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex))
      (is (= :rf.error/reg-event-bad-interceptors (:rf.error/id (ex-data ex))))
      (is (re-find #"reference" (:reason (ex-data ex))))))

  (testing "EP-0022 reference-only flip: an INLINE interceptor value in a chain throws inline-interceptor-removed"
    (let [ex (try (rf/reg-event :test.0adhqs9/inline
                    {:interceptors [noop-icpt-value]}
                    (fn [{:keys [db]} _] {:db db}))
                  nil
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex))
      (let [data (ex-data ex)]
        (is (= :rf.error/inline-interceptor-removed (:rf.error/id data)))
        (is (= "reg-event" (:reg-fn data)))
        (is (= :test.0adhqs9/inline (:id data)))
        (is (= :fix-registration (:recovery data)))
        (is (= noop-icpt-value (:offending data)))
        (is (re-find #"reference-only" (:reason data)))
        (is (re-find #"reg-interceptor" (:reason data))))))

  (testing "a chain mixing a registered ref AND an inline value still fails on the inline value"
    (let [_ (reg-noop! :test/ref-mix)
          ex (try (rf/reg-event :test.0adhqs9/inline-mix
                    {:interceptors [:test/ref-mix noop-icpt-value]}
                    (fn [{:keys [db]} _] {:db db}))
                  nil
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex))
      (is (= :rf.error/inline-interceptor-removed (:rf.error/id (ex-data ex))))))

  (testing "EP-0022: a bare-keyword ref to an UNREGISTERED interceptor throws unregistered-interceptor"
    (let [ex (try (rf/reg-event :test.0adhqs/bad-ref
                    {:interceptors [:not/registered]}
                    (fn [{:keys [db]} _] {:db db}))
                  nil
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex))
      (is (= :rf.error/unregistered-interceptor (:rf.error/id (ex-data ex))))))

  (testing "the malformed rejection happens BEFORE the registry slot is written"
    (try (rf/reg-event :test.bpmszk/bad-no-side-effect
           {:interceptors :nope}
           (fn [{:keys [db]} _] {:db db}))
         (catch clojure.lang.ExceptionInfo _ nil))
    (is (nil? (registrar/lookup :event :test.bpmszk/bad-no-side-effect))
        "registry slot is untouched when the malformed guard throws"))

  (testing "an empty :interceptors vector is legitimate (no chain), not malformed"
    (is (= :test.bpmszk/empty-ok
           (rf/reg-event :test.bpmszk/empty-ok
             {:doc "no chain" :interceptors []}
             (fn [{:keys [db]} _] {:db db}))))
    (let [ids (mapv :id (:interceptors (rf/handler-meta :event :test.bpmszk/empty-ok)))]
      (is (= [:rf/event-handler] ids) "no user interceptors; only the runtime wrapper"))))

(deftest canonical-metadata-form-stays-silent
  (testing "reg-event with metadata-map :interceptors does NOT warn"
    (let [recorded (record-traces! ::db-quiet)]
      (reg-noop! :test/noop)
      (rf/reg-event :test.bbea/db-good
        {:interceptors [:test/noop]}
        (fn [{:keys [db]} _] {:db db}))
      (is (empty? (warning-events recorded :rf.warning/interceptors-in-metadata-map)))))

  (testing "reg-event with metadata-map alone (no interceptors anywhere) does NOT warn"
    (let [recorded (record-traces! ::db-good-3)]
      (rf/reg-event :test.bbea/db-good-3
        {:doc "Plain metadata-only registration."}
        (fn [{:keys [db]} _] {:db db}))
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
    (rf/reg-event :test.6z20/foo (fn [{:keys [db]} _] {:db (assoc db :touched? true)}))
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
    (rf/reg-event :test.6z20/a (fn [{:keys [db]} _] {:db db}))
    (rf/reg-event :test.6z20/b (fn [{:keys [db]} _] {:db db}))
    (rf/reg-event :test.6z20/c (fn [_ _] {}))
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
    (rf/reg-event :test.6z20/ev (fn [{:keys [db]} _] {:db db}))
    (rf/reg-sub :test.6z20/sub (fn [_ _] :stub))
    (rf/reg-fx :test.6z20/fx (fn [_ _] nil))
    (rf/reg-cofx :test.6z20/cofx (fn [] :stub))
    (rf/clear-event)
    (is (nil? (registrar/lookup :event :test.6z20/ev))
        ":event was cleared")
    (is (some? (registrar/lookup :sub :test.6z20/sub))
        ":sub kind is untouched")
    (is (some? (registrar/lookup :fx :test.6z20/fx))
        ":fx kind is untouched")
    (is (some? (registrar/lookup :cofx :test.6z20/cofx))
        ":cofx kind is untouched")))

;; ---- reg-event bad return (rf2-k3bj) -------------------------------------
;;
;; Per rf2-k3bj — a `reg-event` handler is contracted to return a map
;; (or nil, the documented no-op). Any other return type (vector, number,
;; string, ...) is a thinko: the runtime cannot extract `:db` / `:fx` and
;; cannot guess the handler's intent. Pre-fix the body silently no-opped.
;; The fix emits `:rf.error/effect-handler-bad-return` (Spec 009 §Error
;; contract, :recovery :no-recovery) so the misuse surfaces in dev / 10x.

(deftest reg-event-non-map-return-traces-bad-return-error
  (testing "handler returning a string emits :rf.error/effect-handler-bad-return; app-db unchanged"
    (let [recorded (record-traces! ::bad-string)]
      (rf/reg-event :test.k3bj/string-return
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
      (rf/reg-event :test.k3bj/number-return
        (fn [_ _] 42))
      (rf/dispatch-sync [:test.k3bj/number-return])
      (let [errs (error-events recorded :rf.error/effect-handler-bad-return)]
        (is (= 1 (count errs)))
        (is (= 42 (:returned (:tags (first errs))))))))

  (testing "handler returning a vector emits :rf.error/effect-handler-bad-return"
    (let [recorded (record-traces! ::bad-vector)]
      (rf/reg-event :test.k3bj/vector-return
        (fn [_ _] [[:dispatch [:other]]]))
      (rf/dispatch-sync [:test.k3bj/vector-return])
      (let [errs (error-events recorded :rf.error/effect-handler-bad-return)]
        (is (= 1 (count errs)))
        (is (= [[:dispatch [:other]]] (:returned (:tags (first errs)))))))))

(deftest reg-event-nil-return-stays-silent
  (testing "handler returning nil is a documented legal no-op; no :rf.error/effect-handler-bad-return"
    (let [recorded (record-traces! ::nil-quiet)]
      (rf/reg-event :test.k3bj/nil-return
        (fn [_ _] nil))
      (let [db-before (rf/app-db-value :rf/default)]
        (rf/dispatch-sync [:test.k3bj/nil-return])
        (is (empty? (error-events recorded :rf.error/effect-handler-bad-return))
            "nil is the documented no-op return and must not fire the bad-return error")
        (is (= db-before (rf/app-db-value :rf/default))
            "app-db is unchanged after a nil-return no-op")))))

(deftest reg-event-map-return-still-works
  (testing "handler returning a well-shaped {:db ...} effect-map still applies"
    (let [recorded (record-traces! ::map-good)]
      (rf/reg-event :test.k3bj/map-return
        (fn [_ _] {:db {:k3bj/touched? true}}))
      (rf/dispatch-sync [:test.k3bj/map-return])
      (is (empty? (error-events recorded :rf.error/effect-handler-bad-return))
          "a map return must not fire the bad-return error")
      (is (true? (:k3bj/touched? (rf/app-db-value :rf/default)))
          ":db was applied as the effect-map specifies"))))

;; ---- normalise-args: documented user-facing shapes (rf2-fuudi) -----------
;;
;; Per the `reg-event` docstring (events.cljc), the variadic tail accepts
;; two shapes:
;;
;;   (reg-event :id                       handler)             ;; tail = 1
;;   (reg-event :id {:doc "..."}          handler)             ;; tail = 2 (meta)
;;   (reg-event :id {:interceptors [icpt]} handler)             ;; tail = 2 (meta)
;;
;; `normalise-args` dispatches on the *tail* count via `case`. This deftest
;; locks in the canonical shapes: each must register cleanly, surface the
;; metadata, retain metadata `:interceptors`, and dispatch cleanly. The retired
;; rf2-bbea `:rf.warning/interceptors-in-metadata-map` no longer fires.

(deftest normalise-args-accepts-documented-shapes
  (let [recorded (record-traces! ::shapes)
        marker   (reg-noop! :test.fuudi/marker)]   ;; a registered ref (chains are reference-only)
    (testing "shape 1 — bare handler: (reg-event :id handler)"
      (rf/reg-event :test.fuudi/shape-1
        (fn [{:keys [db]} _] {:db (assoc db :test.fuudi/touched-1? true)}))
      (rf/dispatch-sync [:test.fuudi/shape-1])
      (is (true? (:test.fuudi/touched-1? (rf/app-db-value :rf/default))))
      (let [meta (rf/handler-meta :event :test.fuudi/shape-1)]
        (is (not (contains? meta :event/kind))
            "the :event/kind sub-tag is gone")
        (is (= 1 (count (:interceptors meta)))
            "no user interceptors; chain holds only the runtime :rf/event-handler wrapper")))

    (testing "shape 2 — metadata middle: (reg-event :id {:doc \"...\"} handler)"
      (rf/reg-event :test.fuudi/shape-2
        {:doc "metadata-only middle slot"}
        (fn [{:keys [db]} _] {:db (assoc db :test.fuudi/touched-2? true)}))
      (rf/dispatch-sync [:test.fuudi/shape-2])
      (is (true? (:test.fuudi/touched-2? (rf/app-db-value :rf/default))))
      (let [meta (rf/handler-meta :event :test.fuudi/shape-2)]
        (is (= "metadata-only middle slot" (:doc meta))
            ":doc from the metadata-map is retained on the registry entry")
        (is (= 1 (count (:interceptors meta)))
            "no user interceptors; chain holds only the runtime :rf/event-handler wrapper")))

    (testing "shape 3 — metadata :interceptors: (reg-event :id {:interceptors [icpt]} handler)"
      (rf/reg-event :test.fuudi/shape-3
        {:interceptors [marker]}
        (fn [{:keys [db]} _] {:db (assoc db :test.fuudi/touched-3? true)}))
      (rf/dispatch-sync [:test.fuudi/shape-3])
      (is (true? (:test.fuudi/touched-3? (rf/app-db-value :rf/default))))
      (let [meta (rf/handler-meta :event :test.fuudi/shape-3)
            ids  (chain-ids (:interceptors meta))]
        (is (= [:test.fuudi/marker :rf/event-handler] ids)
            "the user interceptor ref sits before the runtime wrapper in registration order")))

    (testing "shape 4 — metadata and interceptors in one map"
      (rf/reg-event :test.fuudi/shape-4
        {:doc "metadata AND interceptors" :interceptors [marker]}
        (fn [{:keys [db]} _] {:db (assoc db :test.fuudi/touched-4? true)}))
      (rf/dispatch-sync [:test.fuudi/shape-4])
      (is (true? (:test.fuudi/touched-4? (rf/app-db-value :rf/default))))
      (let [meta (rf/handler-meta :event :test.fuudi/shape-4)
            ids  (chain-ids (:interceptors meta))]
        (is (= "metadata AND interceptors" (:doc meta))
            ":doc from the metadata-map is retained on the registry entry")
        (is (= [:test.fuudi/marker :rf/event-handler] ids)
            "the user interceptor ref sits before the runtime wrapper in registration order")))

    (testing "none of the canonical shapes fire :rf.warning/interceptors-in-metadata-map"
      (is (empty? (warning-events recorded :rf.warning/interceptors-in-metadata-map))
          "canonical shapes are well-formed; no metadata-misuse warning expected"))))

(deftest reg-event-interceptor-can-set-effects-via-the-interceptor-api
  ;; Full-context work that the retired `reg-event-ctx` form once expressed is
  ;; now done with a registered interceptor (authored with `reg-interceptor`,
  ;; referenced by id from a `reg-event` registration's `:interceptors` chain;
  ;; `->interceptor` is internal-only post-EP-0022). This pins that an
  ;; interceptor :before can read a coeffect
  ;; and set a :db effect via the public interceptor API, threaded ahead of the
  ;; one `:rf/event-handler` wrapper.
  (testing "an interceptor :before reads :db coeffect and sets the :db effect"
    (rf/reg-interceptor* :test.fuudi/ctx-marker
      {:before (fn [ctx]
                 (let [db (interceptor/get-coeffect ctx :db)]
                   (interceptor/assoc-coeffect
                     ctx :db (assoc db :test.fuudi/ctx-touched? true))))
       :after  identity})
    (rf/reg-event :test.fuudi/ctx-shape-4
      {:doc "interceptor, metadata interceptors" :interceptors [:test.fuudi/ctx-marker]}
      (fn [{:keys [db]} _] {:db db}))
    (rf/dispatch-sync [:test.fuudi/ctx-shape-4])
    (is (true? (:test.fuudi/ctx-touched? (rf/app-db-value :rf/default)))
        "the interceptor :before ran and its db mutation committed via the handler")
    (let [meta (rf/handler-meta :event :test.fuudi/ctx-shape-4)
          ids  (chain-ids (:interceptors meta))]
      (is (not (contains? meta :event/kind)))
      (is (= "interceptor, metadata interceptors" (:doc meta)))
      (is (= [:test.fuudi/ctx-marker :rf/event-handler] ids)))))

(deftest normalise-args-rejects-overlong-and-malformed
  (testing "tail count > 3 throws the arity error"
    (let [ex (try
               (rf/reg-event :test.fuudi/too-many
                 {:doc "..."}
                 [{:id :a :before identity :after identity}]
                 (fn [{:keys [db]} _] {:db db})
                 :surplus)
               nil
               (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex))
      (is (= :rf.error/reg-event-bad-arity (:rf.error/id (ex-data ex)))
          ":rf.error/id is the canonical discriminator")
      (is (re-find #"reg-event expects" (:reason (ex-data ex)))
          ":reason names the arity error")))
  (testing "two-arg middle slot that is neither a map nor a vector throws"
    (let [ex (try
               (rf/reg-event :test.fuudi/bad-middle
                 "not-a-map-or-vector"
                 (fn [{:keys [db]} _] {:db db}))
               nil
               (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex))
      (is (= :rf.error/reg-event-bad-middle-slot (:rf.error/id (ex-data ex))))
      (is (re-find #"metadata-map" (:reason (ex-data ex)))))))

;; ---- rf2-twt7m Change 3 — :rf/default? tag on auto-wrappers ---------------
;;
;; The framework auto-wraps the user handler into the ONE handler-wrapping
;; interceptor `:rf/event-handler` (EP-0018 Slice Z — the per-kind
;; :rf/db-handler / :rf/fx-handler / :rf/ctx-handler ids are gone).
;; Pre-rf2-twt7m a tool wanting to distinguish "framework default" from
;; "user supplied" had to maintain a hardcoded allowlist. Per rf2-twt7m
;; Change 3 the auto-wrapper carries `:rf/default? true` on the interceptor
;; map itself; self-describing.
;;
;; Xray, Story, and the Event lens redesign (rf2-zh2qc) read
;; `(rf/handler-meta :event id) :interceptors` and filter
;; `(remove :rf/default?)` to surface only the user's interceptor chain.

(deftest auto-wrapper-carries-rf-default-tag
  (testing "reg-event auto-wrapper has :rf/default? true"
    (rf/reg-event :test.twt7m/handler (fn [{:keys [db]} _] {:db db}))
    (let [interceptors (-> (rf/handler-meta :event :test.twt7m/handler)
                           :interceptors)
          auto-wrapper (last interceptors)]
      (is (= :rf/event-handler (:id auto-wrapper))
          "the auto-wrapper sits at the tail of the interceptor chain")
      (is (= true (:rf/default? auto-wrapper))
          "the auto-wrapper carries :rf/default? true"))))

(deftest user-supplied-interceptors-do-not-carry-rf-default-tag
  (testing "user-supplied interceptor refs do NOT carry :rf/default? true —
   only the framework-auto-wrapper at the chain tail does"
    (reg-noop! :test.twt7m/user)
    (rf/reg-event :test.twt7m/with-user-icpt
      {:interceptors [:test.twt7m/user]}
      (fn [{:keys [db]} _] {:db db}))
    (let [interceptors (-> (rf/handler-meta :event :test.twt7m/with-user-icpt)
                           :interceptors)
          user-slot    (first interceptors)
          auto-wrapper (last interceptors)]
      (is (= 2 (count interceptors))
          "user interceptor ref + auto-wrapper = 2 entries")
      ;; The stored chain holds the AUTHORED ref (a keyword) for the user
      ;; entry; only the framework wrapper is a map carrying :rf/default?.
      (is (= :test.twt7m/user user-slot))
      (is (not (:rf/default? user-slot))
          "the user interceptor ref carries no :rf/default? — `(:rf/default? keyword)` is nil")
      (is (= true (:rf/default? auto-wrapper))
          "only the auto-wrapper carries :rf/default? true"))))

(deftest tooling-can-filter-defaults-via-rf-default-tag
  (testing "the self-describing tag lets tools filter without an id
   allowlist — `(remove :rf/default?)` surfaces user-supplied
   interceptor refs only"
    (reg-noop! :test.twt7m/a)
    (reg-noop! :test.twt7m/b)
    (rf/reg-event :test.twt7m/filtering
      {:interceptors [:test.twt7m/a :test.twt7m/b]}
      (fn [{:keys [db]} _] {:db db}))
    (let [interceptors (-> (rf/handler-meta :event :test.twt7m/filtering)
                           :interceptors)
          user-only    (vec (remove :rf/default? interceptors))]
      (is (= 3 (count interceptors)) "two user refs + one framework auto-wrapper")
      (is (= 2 (count user-only))
          "filtering by :rf/default? leaves the two user interceptor refs (keywords)")
      (is (= [:test.twt7m/a :test.twt7m/b] (chain-ids user-only))))))

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
;; inside `register-event!` (the common body of the one `reg-event`
;; surface), independently of the optional `day8/re-frame2-schemas`
;; artefact — the rejection is structural ("you attached a boundary
;; interceptor but declared no schema"), not a Malli validation. The
;; schemas-artefact test file carries the dispatch-time companion test.

(defn- reg-at-boundary-stub!
  "Register a surface-faithful stand-in for the boundary interceptor under the
  canonical id `:rf.schema/at-boundary` (what `register-event!` looks for) and
  return the chain REF `:rf.schema/at-boundary`. Avoids pulling `re-frame.spec`
  and its schemas-late-bind dance into this core test. EP-0022 reference-only:
  the boundary interceptor is attached by REF, never as an inline value."
  []
  (rf/reg-interceptor* :rf.schema/at-boundary {:before identity :after identity})
  :rf.schema/at-boundary)

(deftest at-boundary-without-schema-rejected-at-registration
  (testing "Per rf2-iftj4 — attaching :rf.schema/at-boundary to a handler
            that carries no :schema raises :rf.error/at-boundary-missing-schema
            at registration time."
    (testing "metadata :interceptors with no :schema"
      (let [at-boundary (reg-at-boundary-stub!)]
        (is (thrown-with-msg?
              clojure.lang.ExceptionInfo
              #":rf\.error/at-boundary-missing-schema"
              (rf/reg-event :test.iftj4/no-schema-2
                {:interceptors [at-boundary]}
                (fn [_ _] {}))))))

    (testing "metadata without :schema plus :interceptors"
      (let [at-boundary (reg-at-boundary-stub!)]
        (is (thrown-with-msg?
              clojure.lang.ExceptionInfo
              #":rf\.error/at-boundary-missing-schema"
              (rf/reg-event :test.iftj4/no-schema-3
                {:doc "metadata-map but no :schema"
                 :interceptors [at-boundary]}
                (fn [_ _] {}))))))

    (testing "ex-data carries actionable diagnostic slots"
      (let [at-boundary (reg-at-boundary-stub!)
            data (try (rf/reg-event :test.iftj4/data-probe
                        {:interceptors [at-boundary]}
                        (fn [_ _] {}))
                      (catch clojure.lang.ExceptionInfo e (ex-data e)))]
        (is (= :rf.error/at-boundary-missing-schema (:rf.error/id data))
            ":rf.error/id matches the catalogued :rf.error/* category")
        (is (= "reg-event" (:reg-fn data)))
        (is (= :test.iftj4/data-probe (:id data)))
        (is (string? (:reason data)))
        (is (re-find #":rf\.schema/at-boundary" (:reason data)))
        (is (re-find #":schema" (:reason data)))
        (is (= :no-recovery (:recovery data)))))

    (testing "rejection happens BEFORE the registry slot is written"
      ;; Belt-and-braces: a failed registration must leave no partial
      ;; trace in the registrar. The `reject-...!` call is sequenced
      ;; before `registrar/register!` in `register-event!`, so the
      ;; handler-id should be absent from the :event kind after the throw.
      (let [at-boundary (reg-at-boundary-stub!)]
        (try (rf/reg-event :test.iftj4/no-side-effect
               {:interceptors [at-boundary]}
               (fn [_ _] {}))
             (catch clojure.lang.ExceptionInfo _ nil))
        (is (nil? (registrar/lookup :event :test.iftj4/no-side-effect))
            "registry slot is untouched when the validate-at-boundary-interceptor check throws")))))

(deftest at-boundary-with-schema-registers-cleanly
  (testing "Per rf2-iftj4 — attaching :rf.schema/at-boundary alongside a
            `:schema` metadata key completes registration without error.
            The check fires only when the schema is absent."
    (let [at-boundary (reg-at-boundary-stub!)]
      (is (= :test.iftj4/with-schema
             (rf/reg-event :test.iftj4/with-schema
               {:schema [:cat [:= :test.iftj4/with-schema] :int]
                :interceptors [at-boundary]}
               (fn [_ _] {})))
          "registration returns the event id when :schema is present")))

  (testing "registration without validate-at-boundary-interceptor is unaffected by the check"
    (is (= :test.iftj4/no-boundary
           (rf/reg-event :test.iftj4/no-boundary
             (fn [_ _] {})))
        "no validate-at-boundary-interceptor, no schema, no error"))

  (testing "metadata-map without :schema is fine when validate-at-boundary-interceptor isn't attached"
    (is (= :test.iftj4/just-meta
           (rf/reg-event :test.iftj4/just-meta
             {:doc "no boundary, no schema"}
             (fn [_ _] {}))))))

;; ---- rf2-i3uxo2 — missing-schema detection fires for the BY-REF form too --
;;
;; Per EP-0022 + API.md §`validate-at-boundary-interceptor` a public
;; `:interceptors` chain carries REFS, not inline values: the canonical
;; opt-in is `{:interceptors [:rf.schema/at-boundary]}` (a bare-keyword ref),
;; not the inline `validate-at-boundary-interceptor` Var. The chain stores
;; refs UNRESOLVED, so the missing-schema detection (`register-event!` →
;; `reject-at-boundary-without-schema!`) sees the RAW bare keyword, not a map.
;; rf2-i3uxo2 extends the detection so it fires for BOTH the by-ref form and
;; the legacy inline-value form. The interceptor itself is registered by
;; `re-frame.spec/register-schema-interceptors!` (re-seeded by `rf/init!`,
;; which the fixture calls AFTER `clear-all!`), so the ref resolves at
;; registration's `validate-refs-registered!` step BEFORE the missing-schema
;; check runs — i.e. an unregistered-interceptor error does NOT pre-empt it.

(deftest at-boundary-ref-form-resolves-at-registration
  (testing "Per rf2-i3uxo2 — the bare-keyword ref `[:rf.schema/at-boundary]`
            resolves at chain assembly (the interceptor is registered, re-seeded
            by init!). A handler carrying the ref AND a `:schema` registers
            cleanly — no :rf.error/unregistered-interceptor, no missing-schema."
    (is (= :test.i3uxo2/ref-ok
           (rf/reg-event :test.i3uxo2/ref-ok
             {:schema [:cat [:= :test.i3uxo2/ref-ok] :int]
              :interceptors [:rf.schema/at-boundary]}
             (fn [_ _] {})))
        "by-ref form with :schema registers and returns the id")))

(deftest at-boundary-ref-form-without-schema-rejected-at-registration
  (testing "Per rf2-i3uxo2 — the missing-schema detection fires for the BY-REF
            form (bare keyword) exactly as it does for the inline value: a
            handler that references `:rf.schema/at-boundary` but declares no
            `:schema` raises :rf.error/at-boundary-missing-schema at reg time."
    (testing "bare-keyword ref, no :schema"
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #":rf\.error/at-boundary-missing-schema"
            (rf/reg-event :test.i3uxo2/ref-no-schema
              {:interceptors [:rf.schema/at-boundary]}
              (fn [_ _] {})))))

    (testing "ex-data carries the same actionable slots as the inline form"
      (let [data (try (rf/reg-event :test.i3uxo2/ref-probe
                        {:interceptors [:rf.schema/at-boundary]}
                        (fn [_ _] {}))
                      (catch clojure.lang.ExceptionInfo e (ex-data e)))]
        (is (= :rf.error/at-boundary-missing-schema (:rf.error/id data)))
        (is (= :test.i3uxo2/ref-probe (:id data)))
        (is (re-find #":rf\.schema/at-boundary" (:reason data)))))

    (testing "rejection happens BEFORE the registry slot is written (ref form)"
      (try (rf/reg-event :test.i3uxo2/ref-no-side-effect
             {:interceptors [:rf.schema/at-boundary]}
             (fn [_ _] {}))
           (catch clojure.lang.ExceptionInfo _ nil))
      (is (nil? (registrar/lookup :event :test.i3uxo2/ref-no-side-effect))
          "no partial registry trace after the ref-form rejection"))))

;; ---- rf2-48ypb6 / rf2-48ypb6.1 — at-boundary-entry? detects the bare keyword (unit) ----------
;;
;; `at-boundary-entry?` (events.cljc) detects the `:rf.schema/at-boundary`
;; attachment by REFERENCE in its ONLY reachable form: the bare keyword
;; `:rf.schema/at-boundary`. The existing registration-path tests above exercise
;; that bare-keyword form end-to-end; this pins the predicate directly.
;;
;; REMOVED ARM (rf2-48ypb6.1 verdict, resolves rf2-wjr8ow): the predicate
;; previously also detected an `[:rf.schema/at-boundary arg]` 2-vector, but that
;; arm was VESTIGIAL via the public `reg-event` registration path and has been
;; removed. The standard `:rf.schema/at-boundary` interceptor is registered as a
;; STATIC interceptor (no `:factory`), so an `[:rf.schema/at-boundary arg]` chain
;; ref is rejected at `validate-refs-registered!` with
;; `:rf.error/interceptor-factory-arity` BEFORE
;; `reject-at-boundary-without-schema!` (which calls this predicate) ever runs —
;; verified empirically (with AND without `:schema`). The 2-vector branch was
;; therefore unreachable for its intended missing-schema rejection purpose; the
;; `[id arg]` form is simply an unregistered-factory-shape misuse that fails
;; loud on its own. This test now pins the bare-keyword form and asserts the
;; 2-vector form is NOT detected.

(deftest at-boundary-entry?-detects-bare-keyword-ref
  (testing "Per rf2-48ypb6 / rf2-48ypb6.1 — the private at-boundary-entry?
            predicate detects the `:rf.schema/at-boundary` attachment in its
            ONLY reachable ref form: the bare keyword. The `[id arg]` 2-vector
            arm was removed (rf2-48ypb6.1, rf2-wjr8ow) as vestigial — a static
            interceptor's `[id arg]` ref is rejected at validate-refs-registered!
            with :rf.error/interceptor-factory-arity before this predicate runs."
    (let [at-boundary-entry? @#'events/at-boundary-entry?]
      (testing "bare-keyword ref"
        (is (true? (boolean (at-boundary-entry? :rf.schema/at-boundary)))
            "the bare keyword is detected"))

      (testing "non-matching entries are NOT detected"
        (is (false? (boolean (at-boundary-entry? :some/other-interceptor)))
            "an unrelated bare keyword is not detected")
        (is (false? (boolean (at-boundary-entry? [:rf.schema/at-boundary {:some :arg}])))
            "the `[id arg]` 2-vector is NOT detected — that arm was removed; the
             static-interceptor factory-arity rejection pre-empts this check")
        (is (false? (boolean (at-boundary-entry? [:some/other-interceptor {:k 1}])))
            "an unrelated [id arg] 2-vector is not detected")
        (is (false? (boolean (at-boundary-entry? [:rf.schema/at-boundary])))
            "a 1-vector ref is not detected (only the bare keyword is)")
        (is (false? (boolean (at-boundary-entry? {:id :rf.schema/at-boundary})))
            "an inline map value is not a ref form and is not detected")))))

;; ---- rf2-3ut12 — a BARE interceptor is rejected loudly at registration ----
;;
;; Field-confirmed via the rf8 migration: `reg-event` requires the
;; interceptor chain to live in metadata `:interceptors`. A bare interceptor —
;; `(reg-event id mw/some-interceptor handler)` — used to be SILENTLY
;; dropped: an interceptor is a map (`{:id … :before … :after …}`), so the
;; two-arg branch of `normalise-args` read it as the metadata-map, the chain
;; never reached the registrar, and the interceptor never ran (no error, no
;; warning). Same silent-drop class as p806o / gro94 / cxo1h.
;;
;; The fix raises `:rf.error/reg-event-bare-interceptor` at registration
;; (ERROR, not warn — the chain cannot be honoured and a silent drop is a
;; dishonest signal; see Conventions §No silent swallow). We do NOT coerce
;; `bare → {:interceptors [bare]}`; the caller must wrap it. These tests assert: (1) a bare
;; interceptor throws; (2) a metadata `:interceptors`
;; vector still works; (3) empty / absent
;; interceptors still works.

(def ^:private bare-icpt
  ;; A bare interceptor map — what `(->interceptor :after …)` returns. This
  ;; is exactly the shape that used to be silently dropped.
  {:id     :test.3ut12/bare
   :before identity
   :after  identity})

(deftest bare-interceptor-rejected-at-registration
  (testing "Per rf2-3ut12 — a bare interceptor (not in metadata :interceptors) throws
            :rf.error/reg-event-bare-interceptor rather than being silently
            dropped."
    (testing "two-arg form: (reg-event id bare-icpt handler)"
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #":rf\.error/reg-event-bare-interceptor"
            (rf/reg-event :test.3ut12/bare-2
              bare-icpt
              (fn [{:keys [db]} _] {:db db})))))

    (testing "a bare interceptor that carries ONLY :before is still caught"
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #":rf\.error/reg-event-bare-interceptor"
            (rf/reg-event :test.3ut12/before-only
              {:id :test.3ut12/before-only :before identity}
              (fn [{:keys [db]} _] {:db db})))))

    (testing "ex-data carries actionable diagnostic slots"
      (let [data (try (rf/reg-event :test.3ut12/data-probe
                        bare-icpt
                        (fn [{:keys [db]} _] {:db db}))
                      (catch clojure.lang.ExceptionInfo e (ex-data e)))]
        (is (= :rf.error/reg-event-bare-interceptor (:rf.error/id data))
            ":rf.error/id matches the catalogued :rf.error/* category")
        (is (= "reg-event" (:reg-fn data)))
        (is (= 'rf/reg-event (:where data)))
        (is (= :middle (:slot data)))
        (is (= :fix-registration (:recovery data)))
        (is (string? (:reason data)))
        (is (re-find #"BARE interceptor" (:reason data)))
        (is (re-find #":interceptors" (:reason data)))))

    (testing "rejection happens BEFORE the registry slot is written"
      (try (rf/reg-event :test.3ut12/no-side-effect
             bare-icpt
             (fn [{:keys [db]} _] {:db db}))
           (catch clojure.lang.ExceptionInfo _ nil))
      (is (nil? (registrar/lookup :event :test.3ut12/no-side-effect))
          "registry slot is untouched when the bare-interceptor check throws"))))

(deftest legitimate-interceptor-forms-still-work
  (testing "Per rf2-3ut12 — the fix must NOT regress the legitimate shapes."
    (testing "metadata :interceptors registers cleanly and the chain runs"
      (reg-noop! :test.3ut12/bare)
      (is (= :test.3ut12/good-interceptors
             (rf/reg-event :test.3ut12/good-interceptors
               {:interceptors [:test.3ut12/bare]}
               (fn [{:keys [db]} _] {:db db}))))
      (let [{:keys [interceptors]} (rf/handler-meta :event :test.3ut12/good-interceptors)
            ids (set (chain-ids interceptors))]
        (is (contains? ids :test.3ut12/bare)
            "the interceptor ref reached the registered chain (NOT dropped)")))

    (testing "metadata-map can carry reflection metadata and interceptors together"
      (reg-noop! :test.3ut12/bare)
      (is (= :test.3ut12/good-meta-interceptors
             (rf/reg-event :test.3ut12/good-meta-interceptors
               {:doc "metadata + interceptor ref vector"
                :interceptors [:test.3ut12/bare]}
               (fn [_ _] {}))))
      (let [{:keys [interceptors]} (rf/handler-meta :event :test.3ut12/good-meta-interceptors)]
        (is (contains? (set (chain-ids interceptors)) :test.3ut12/bare))))

    (testing "absent interceptors (bare handler) still works"
      (is (= :test.3ut12/no-icpt
             (rf/reg-event :test.3ut12/no-icpt
               (fn [{:keys [db]} _] {:db db})))))

    (testing "metadata-map alone (no interceptors anywhere) still works"
      (is (= :test.3ut12/meta-only
             (rf/reg-event :test.3ut12/meta-only
               {:doc "plain metadata"}
               (fn [{:keys [db]} _] {:db db})))))

    (testing "an empty metadata :interceptors vector (legitimate) still works"
      (is (= :test.3ut12/empty-vec
             (rf/reg-event :test.3ut12/empty-vec
               {:interceptors []}
               (fn [{:keys [db]} _] {:db db}))))
      (is (= :test.3ut12/empty-vec-3
             (rf/reg-event :test.3ut12/empty-vec-3
               {:doc "meta + empty interceptor vector"
                :interceptors []}
               (fn [_ _] {})))))))

;; ---- EP-0018 Slice Z — the retired public names are throwing stubs --------
;;
;; `reg-event-db` / `reg-event-fx` are REMOVED (no alias, EP-0007 rule 2) and
;; public `reg-event-ctx` is DEMOTED to a framework-internal primitive. The
;; facade names survive ONLY as `^:no-doc` throwing stubs so a stale call site
;; fails LOUDLY with an actionable hard error naming the replacement — never an
;; opaque "no such var". They register NOTHING. The -db / -fx errors name
;; `reg-event`; the -ctx error names `reg-interceptor` (the public interceptor
;; authoring form post-EP-0022 — `->interceptor` is internal-only).

(defn- stub-throw-id
  "Call `reg-fn` (one of the retired throwing stubs) and return the
  `:rf.error/id` it raises, or `:no-throw` if it did not throw."
  [reg-fn]
  (try (reg-fn)
       :no-throw
       (catch clojure.lang.ExceptionInfo e
         (:rf.error/id (ex-data e)))))

(deftest retired-reg-event-names-throw-their-removal-stubs
  (testing "Per EP-0018 Slice Z — the three retired public event-registration
            names are throwing stubs that register nothing and raise their
            naming hard error."
    (is (= :rf.error/reg-event-db-removed
           (stub-throw-id #(rf/reg-event-db :test.slice-z/db (fn [_ _] nil))))
        "reg-event-db raises :rf.error/reg-event-db-removed")
    (is (= :rf.error/reg-event-fx-removed
           (stub-throw-id #(rf/reg-event-fx :test.slice-z/fx (fn [_ _] nil))))
        "reg-event-fx raises :rf.error/reg-event-fx-removed")
    (is (= :rf.error/reg-event-ctx-removed
           (stub-throw-id #(rf/reg-event-ctx :test.slice-z/ctx (fn [_ _] nil))))
        "reg-event-ctx raises :rf.error/reg-event-ctx-removed"))

  (testing "the stubs register NOTHING — no registry slot is written"
    (try (rf/reg-event-db :test.slice-z/db-noreg (fn [_ _] nil))
         (catch clojure.lang.ExceptionInfo _ nil))
    (try (rf/reg-event-fx :test.slice-z/fx-noreg (fn [_ _] nil))
         (catch clojure.lang.ExceptionInfo _ nil))
    (try (rf/reg-event-ctx :test.slice-z/ctx-noreg (fn [_ _] nil))
         (catch clojure.lang.ExceptionInfo _ nil))
    (is (nil? (registrar/lookup :event :test.slice-z/db-noreg)))
    (is (nil? (registrar/lookup :event :test.slice-z/fx-noreg)))
    (is (nil? (registrar/lookup :event :test.slice-z/ctx-noreg))))

  (testing "the removal errors name the replacement surface in :reason"
    (let [db-reason  (try (rf/reg-event-db :test.slice-z/r-db (fn [_ _] nil))
                          (catch clojure.lang.ExceptionInfo e (:reason (ex-data e))))
          ctx-reason (try (rf/reg-event-ctx :test.slice-z/r-ctx (fn [_ _] nil))
                          (catch clojure.lang.ExceptionInfo e (:reason (ex-data e))))]
      (is (re-find #"reg-event" db-reason)
          "the reg-event-db error names reg-event as the replacement")
      (is (re-find #"reg-interceptor" ctx-reason)
          "the reg-event-ctx error names reg-interceptor as the replacement")
      (is (not (re-find #"->interceptor" ctx-reason))
          "the reg-event-ctx error does NOT name ->interceptor (internal-only post-EP-0022)"))))
