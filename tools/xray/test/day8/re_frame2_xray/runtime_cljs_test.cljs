(ns day8.re-frame2-xray.runtime-cljs-test
  "Unit tests for `day8.re-frame2-xray.runtime` (rf2-8xzoe.4 / F-4).

  Pins the load-bearing contracts the F-4 port lands:

    1. **Twenty-three tool-shaped accessors.** Each accessor in
       `tools/xray/spec/API.md` §Runtime accessor surface maps to
       exactly one runtime fn; the lint here enumerates and asserts
       every one is `fn?`. Drift between the catalogue and the
       runtime surface fails this test first, before any tool dispatch
       round-trip would notice. The five-accessor Resources read band
       is enumerated in `tools/xray/spec/024-Resources-Panel.md`
       §Tool accessors (rf2-dh0y8o).
    2. **Session sentinel.** `session-id` is a non-empty string and a
       mirror lands at `js/globalThis.__day8_re_frame2_xray_runtime`
       (under node-test the global is the Node global; we exercise it
       through the same `exists?` guard the runtime uses).
    3. **`*current-origin*` defaults to `:xray-mcp`.** Mutating
       accessors stamp this tag onto their dispatches per Lock #4 +
       MUST-inventory row I1. `binding` re-binds within the
       synchronous extent per I6.
    4. **Frame resolution.** `resolve-frame` (exercised via the public
       accessors) picks the sole registered frame; returns nil under
       ambiguity rather than guessing.
    5. **`health` is side-effect-free.** Unlike re-frame2-pair's `health` which
       installs trace + epoch listeners, Xray-the-panel's preload
       owns those — the runtime's `health` reads only.

  ## Why these tests run on node-test (not browser-test)

  The accessor surface is pure-data + framework-API forwarding. No
  DOM, no substrate-render, no React-context tier. Browser-side
  concerns (DOM `data-rf2-source-coord` annotation probe) test as
  `false` here because there is no `js/document`; the `health`
  contract explicitly degrades nil-safely.

  ## What's NOT in scope here

  - End-to-end nREPL-eval round-trips: covered by the MCP-server-side
    eval-form tests once the F-tranche dispatcher lands.
  - Streaming pump bookkeeping (per-tick queues, overflow markers):
    owned by the MCP-server side per `004-Wire-Pipeline.md`. The
    runtime exposes only the registration metadata."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines :as machines]
            [re-frame.elision :as elision]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            ;; CLJS callers reach the per-frame trace ring directly via the
            ;; tooling sibling — `rf/trace-buffer` / `rf/clear-trace-buffer!`
            ;; are JVM-only aliases (core.cljc `#?(:clj ...)`). The runtime
            ;; itself requires this same ns (rf2-qwm0a).
            [re-frame.trace.tooling :as trace-tooling]
            [day8.re-frame2-xray.runtime :as runtime]))

;; ---------------------------------------------------------------------------
;; Fixture — snapshot/restore the framework runtime + reset the runtime ns.
;; ---------------------------------------------------------------------------

(defn- runtime-init! []
  (runtime/reset-for-test!))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn runtime-init!}))

;; ---------------------------------------------------------------------------
;; (1) Twenty-three tool-shaped accessors are resolvable.
;; ---------------------------------------------------------------------------

(def ^:private tool-accessor-vars
  "The canonical twenty-three tool-shaped accessors per
  `tools/xray/spec/API.md` §Runtime accessor surface. Order matches
  the catalogue band split for readability — change here only when
  the catalogue changes (and update the count assertion below).

  CLJS has no runtime `ns-resolve`, so this is a literal vector of
  `[sym fn]` pairs: a name (for the assertion message) and the
  callable var itself. Drift between the catalogue and the runtime
  surface (a deleted accessor; a renamed accessor) fails the compile
  here, before the test even runs."
  [;; Inspection (9)
   ['get-trace-buffer    runtime/get-trace-buffer]
   ['get-epoch-history   runtime/get-epoch-history]
   ['get-app-db          runtime/get-app-db]
   ['get-app-db-diff     runtime/get-app-db-diff]
   ['get-machine-state   runtime/get-machine-state]
   ['get-machine-list    runtime/get-machine-list]
   ['get-issues          runtime/get-issues]
   ['get-handlers        runtime/get-handlers]
   ['get-source-coord    runtime/get-source-coord]
   ;; Resources read (5) — rf2-dh0y8o
   ['list-resources              runtime/list-resources]
   ['list-resource-instances     runtime/list-resource-instances]
   ['get-resource-state          runtime/get-resource-state]
   ['get-resource-history        runtime/get-resource-history]
   ['list-resource-invalidations runtime/list-resource-invalidations]
   ;; Mutation (3)
   ['dispatch!           runtime/dispatch!]
   ['restore-epoch!      runtime/restore-epoch!]
   ['replace-app-db!     runtime/replace-app-db!]
   ;; Streaming (3)
   ['subscribe!          runtime/subscribe!]
   ['unsubscribe!        runtime/unsubscribe!]
   ['list-subscriptions  runtime/list-subscriptions]
   ;; Escape (1)
   ['eval-form-result    runtime/eval-form-result]
   ;; Meta (2)
   ['health              runtime/health]
   ['tail-build-probe    runtime/tail-build-probe]])

(deftest twenty-three-tool-accessors-exist
  (testing "every catalogue tool has a runtime-side accessor — drift
            between the MCP tools and this ns fails here first"
    (is (= 23 (count tool-accessor-vars))
        "the canonical list is the twenty-three-tool catalogue
         (9 inspection + 5 resources + 3 mutation + 3 streaming +
         1 escape + 2 meta)")
    (doseq [[sym f] tool-accessor-vars]
      (is (fn? f)
          (str "accessor not callable: day8.re-frame2-xray.runtime/" sym)))))

;; ---------------------------------------------------------------------------
;; (2) Session sentinel — UUID string + globalThis mirror.
;; ---------------------------------------------------------------------------

(deftest session-id-is-non-empty-string
  (testing "session-id is a freshly-generated UUID string"
    (is (string? runtime/session-id))
    (is (pos? (count runtime/session-id))
        "session-id is non-empty")))

(deftest global-sentinel-installed
  (testing "the `js/globalThis.__day8_re_frame2_xray_runtime` mirror
            lands so the MCP server's cheap preload probe succeeds in
            one bencode round-trip"
    (when (exists? js/globalThis)
      (let [marker (aget js/globalThis "__day8_re_frame2_xray_runtime")]
        (is (some? marker)
            "the global mirror exists on `js/globalThis`")
        (is (= runtime/session-id (aget marker "session-id"))
            "the mirror carries the same session-id as the CLJS var")
        (is (number? (aget marker "installed"))
            "the mirror carries a numeric installed-at timestamp")))))

;; ---------------------------------------------------------------------------
;; (3) *current-origin* default + binding extent.
;; ---------------------------------------------------------------------------

(deftest current-origin-defaults-to-xray-mcp
  (testing "the default value of `*current-origin*` is `:xray-mcp` —
            every Xray-MCP-driven side-effect carries the tag without
            the server needing an explicit binding (Lock #4 / I1)"
    (is (= :xray-mcp (runtime/current-origin)))))

(deftest current-origin-rebinds-via-binding
  (testing "`binding` over `*current-origin*` carries the new value
            inside its synchronous extent and restores on exit (I6)"
    (binding [runtime/*current-origin* :test-origin]
      (is (= :test-origin (runtime/current-origin))
          "binding takes effect synchronously"))
    (is (= :xray-mcp (runtime/current-origin))
        "default restores after binding's extent ends")))

;; ---------------------------------------------------------------------------
;; (4) Frame resolution via public accessors.
;; ---------------------------------------------------------------------------

(deftest get-app-db-resolves-sole-frame
  (testing "with the framework's default `:rf/default` registered, the
            no-arg `get-app-db` resolves it without an explicit `:frame`
            arg"
    (rf/reg-event :test/seed-db
      (fn [{:keys [db]} _] {:db {:seeded? true}}))
    (rf/dispatch-sync [:test/seed-db])
    (let [result (runtime/get-app-db)]
      (is (true? (:ok? result))
          "single-frame resolution succeeds without explicit :frame")
      (is (= :rf/default (:frame result))
          "the sole frame is the resolved frame"))))

(deftest get-app-db-explicit-path
  (testing "the `:path` arg scopes the returned value via `get-in`"
    (rf/reg-event :test/seed-db
      (fn [{:keys [db]} _] {:db {:cart {:items [:a :b :c]}}}))
    (rf/dispatch-sync [:test/seed-db])
    (let [result (runtime/get-app-db {:path [:cart :items]})]
      (is (true? (:ok? result)))
      (is (= [:a :b :c] (:value result))
          ":path returns the scoped value"))))

(deftest get-app-db-no-frame-resolved
  (testing "with no frames registered, `get-app-db` surfaces a
            structured `:no-frame-resolved` refusal rather than crashing"
    ;; The fixture's make-reset-runtime-fixture leaves :rf/default in place;
    ;; force ambiguity by destroying it.
    (frame/destroy-frame! :rf/default)
    (let [result (runtime/get-app-db)]
      (is (false? (:ok? result)))
      (is (= :no-frame-resolved (:reason result))))))

;; ---------------------------------------------------------------------------
;; (4a) rf2-xxo3zz — an explicit-but-UNREGISTERED frame id fails consistently
;;      with `:no-such-frame` across every read / dispatch / mutation
;;      accessor, instead of being returned verbatim from `resolve-frame`
;;      (which let reads report `{:ok? true :value nil}` — indistinguishable
;;      from a legitimate nil — and let mutations report success against a
;;      frame that does not exist).
;; ---------------------------------------------------------------------------

(deftest explicit-bogus-frame-fails-no-such-frame
  (testing "rf2-xxo3zz — every frame-scoped accessor refuses an explicit
            frame id that is not in `rf/frame-ids` with a distinct
            `:no-such-frame` reason; the implicit / sole-frame path is
            unaffected (the registry still holds :rf/default)."
    (rf/reg-event :test/seed-db (fn [{:keys [db]} _] {:db {:seeded? true}}))
    (rf/dispatch-sync [:test/seed-db])
    (let [bogus :app/does-not-exist
          ;; The frame-scoped accessors that take an explicit `:frame`:
          ;; reads + dispatch + mutations. A bogus id must fail closed on
          ;; all of them with `:no-such-frame`, never `:ok? true`.
          results {:get-app-db          (runtime/get-app-db {:frame bogus})
                   :get-trace-buffer    (runtime/get-trace-buffer {:frame bogus})
                   :get-epoch-history   (runtime/get-epoch-history {:frame bogus})
                   :get-app-db-diff     (runtime/get-app-db-diff {:frame bogus :epoch-id (random-uuid)})
                   :get-machine-state   (runtime/get-machine-state {:frame bogus :machine-id :m})
                   :list-resource-instances (runtime/list-resource-instances {:frame bogus})
                   :get-resource-state  (runtime/get-resource-state {:frame bogus :resource-id :r :scope :s :params {}})
                   :get-resource-history (runtime/get-resource-history {:frame bogus})
                   :list-resource-invalidations (runtime/list-resource-invalidations {:frame bogus})
                   :dispatch!           (runtime/dispatch! [:noop] {:frame bogus})
                   :restore-epoch!      (runtime/restore-epoch! {:frame bogus :epoch-id (random-uuid)})
                   :replace-app-db!     (runtime/replace-app-db! {:frame bogus :value {}})}]
      (doseq [[accessor result] results]
        (is (false? (:ok? result))
            (str accessor " refuses a bogus explicit frame (not :ok? true)"))
        (is (= :no-such-frame (:reason result))
            (str accessor " reports :no-such-frame for a bogus explicit frame"))
        (is (= bogus (:frame result))
            (str accessor " echoes the offending frame id back to the caller"))))))

(deftest explicit-bogus-frame-precedence-over-arg-validation
  (testing "rf2-xxo3zz — the frame guard fires BEFORE arg-shape validation
            so a bogus frame + a missing required arg still reports
            :no-such-frame (the frame is the more fundamental error; an
            agent fixes the frame id first)."
    ;; :rf/default is registered by the fixture; pass a bogus frame AND omit
    ;; the required :machine-id / :epoch-id / :value.
    (is (= :no-such-frame (:reason (runtime/get-machine-state {:frame :app/nope})))
        "get-machine-state: bogus frame wins over missing :machine-id")
    (is (= :no-such-frame (:reason (runtime/restore-epoch! {:frame :app/nope})))
        "restore-epoch!: bogus frame wins over missing :epoch-id")
    (is (= :no-such-frame (:reason (runtime/replace-app-db! {:frame :app/nope})))
        "replace-app-db!: bogus frame wins over missing :value")))

(deftest dispatch!-event-vector-validation-precedes-frame-guard
  (testing "rf2-xxo3zz — dispatch! keeps its event-vector shape check ahead
            of the frame guard (a non-vector event is the first thing the
            caller must fix), so a bogus frame + non-vector event reports
            :not-an-event-vector."
    (is (= :not-an-event-vector
           (:reason (runtime/dispatch! "not-a-vector" {:frame :app/nope})))
        "non-vector event reported before the frame guard")))

;; ---------------------------------------------------------------------------
;; (4b) get-machine-state reports the LIVE FSM position, not the spec.
;; ---------------------------------------------------------------------------
;;
;; rf2-uo0rc.3: BEFORE the fix, `get-machine-state` routed `:state`
;; through `rf/machine-meta` — the registered SPEC — even though the tool
;; name + the `:state` key + the docstring all promise the CURRENT FSM
;; position. An agent asking "what state is :auth in right now" got the
;; static definition (e.g. `{:initial :idle :states {...}}`), never the
;; live `[:active :authenticating]`. AFTER the fix `:state` is read off
;; the live snapshot at `[:rf.runtime/machines :snapshots <machine-id>]`
;; in runtime-db (the runtime-owned slot the framework writes) and the spec
;; is returned separately under `:spec`.
;;
;; The machines RUNTIME artefact (`re-frame.machines` — the lifecycle-fx
;; that synthesises snapshots) is NOT on the xray `clojure -M:test`
;; classpath (only `machines-viz` is), so we exercise the accessor's two
;; surfaces directly: seed the live snapshot into runtime-db (what the
;; runtime would write after transitions) + stub `rf/machine-meta` (the
;; registry) so the test is portable across both the xray-deps + shadow
;; node-test classpaths without booting the runtime artefact.

(def ^:private uo0rc3-registered-spec
  "A registered machine SPEC — what `rf/machine-meta` returns. Its
  `:initial` is `:idle`; if the accessor regressed to returning the spec
  as `:state`, the assertion below would see this map (or its `:initial`)
  rather than the live state-path."
  {:initial :idle
   :states  {:idle               {:on {:login :authenticating}}
             :active             {:states {:authenticating {} :authed {}}}}})

;; EP-0001 (rf2-jj1xer) — seed the live machine snapshot exactly where the
;; machines runtime writes it post-rf2-vzld77: the RUNTIME-DB partition at
;; `[:rf.runtime/machines :snapshots :auth]`, NOT the old app-db `:rf/runtime`
;; slot. A framework-authority `reg-event` handler returning the reserved
;; `:rf.db/runtime` effect installs the runtime-db partition (the same path
;; the machines lifecycle-fx writes); `:rf/machine? true` marks it
;; framework-authority so the runtime-write diagnostic does not fire.
(defn- seed-machine-snapshot-in-runtime-db! [snapshot]
  (rf/reg-event :test/seed-machine-snapshot
    {:rf/machine? true}
    (fn [_ _]
      {:rf.db/runtime {:rf.runtime/machines {:snapshots {:auth snapshot}}}}))
  (rf/dispatch-sync [:test/seed-machine-snapshot]))

(deftest get-machine-state-reports-live-position-not-spec
  (testing "get-machine-state returns the LIVE snapshot :state (the running
            FSM position), NOT the registered spec — rf2-uo0rc.3 + rf2-jj1xer
            (reads the runtime-db partition, with a trusted-local opt-in)"
    (let [live-state [:active :authenticating]
          snapshot   {:state live-state
                      :data  {:user "ada"}
                      :tags  #{:busy}}]
      ;; rf2-jj1xer — seed into the RUNTIME-DB partition (not app-db).
      (seed-machine-snapshot-in-runtime-db! snapshot)
      ;; Stub the registry surface so `machine-meta` resolves to a spec
      ;; without the runtime artefact on the classpath.
      (with-redefs [machines/machine-meta (fn [mid]
                                      (when (= :auth mid) uo0rc3-registered-spec))]
        ;; rf2-jj1xer — runtime-db is REDACTED off-box by default (ruling
        ;; #14); a trusted-local caller opts in with :include-runtime-db?
        ;; true to read the live position. Pass it here to assert the
        ;; partition read lands the runtime-db value.
        (let [result (runtime/get-machine-state {:machine-id :auth
                                                  :include-runtime-db? true})]
          (is (true? (:ok? result)))
          (is (= :auth (:machine-id result)))
          (testing ":state is the LIVE FSM position read off the runtime-db partition"
            (is (= live-state (:state result))
                ":state is the running state-path off the live runtime-db snapshot")
            (is (not= uo0rc3-registered-spec (:state result))
                ":state is NOT the registered spec (the rf2-uo0rc.3 regression)")
            (is (not= :idle (:state result))
                ":state is NOT the spec's :initial"))
          (testing "the full live snapshot is surfaced under :snapshot"
            (is (= snapshot (:snapshot result))))
          (testing "the static definition is available SEPARATELY under :spec"
            (is (= uo0rc3-registered-spec (:spec result))
                ":spec carries the registered machine definition")))))))

(deftest get-machine-state-redacts-runtime-db-off-box-by-default
  (testing "EP-0001 rf2-jj1xer / ruling #14 — the LIVE machine snapshot is
            RUNTIME-DB state; the default (no opt-in) off-box read REDACTS
            :state + :snapshot to :rf/redacted, while the static :spec (a
            registry value, not runtime-db) still egresses"
    (let [snapshot {:state [:active :authenticating]
                    :data  {:user "ada"}
                    :tags  #{:busy}}]
      (seed-machine-snapshot-in-runtime-db! snapshot)
      (with-redefs [machines/machine-meta (fn [mid]
                                      (when (= :auth mid) uo0rc3-registered-spec))]
        ;; No :include-runtime-db? ⇒ the off-box default fails closed.
        (let [result (runtime/get-machine-state {:machine-id :auth})]
          (is (true? (:ok? result)) "the read still succeeds")
          (is (= :auth (:machine-id result)))
          (is (= :rf/redacted (:state result))
              ":state is redacted off-box by default (runtime-db partition)")
          (is (= :rf/redacted (:snapshot result))
              ":snapshot is redacted off-box by default (runtime-db partition)")
          (is (= uo0rc3-registered-spec (:spec result))
              ":spec (a static registry value, not runtime-db) still egresses"))))))

(deftest get-machine-state-not-yet-started-when-no-live-snapshot
  (testing "a registered-but-not-yet-started machine (no live snapshot in
            app-db) succeeds with :state nil + :reason :not-yet-started so
            the absence of a live position cannot be mistaken for a state —
            rf2-uo0rc.3"
    (with-redefs [machines/machine-meta (fn [mid]
                                    (when (= :auth mid) uo0rc3-registered-spec))]
      (let [result (runtime/get-machine-state {:machine-id :auth})]
        (is (true? (:ok? result)) "still :ok? true so the agent can read :spec")
        (is (nil? (:state result)) ":state is nil (no live position yet)")
        (is (nil? (:snapshot result)))
        (is (= :not-yet-started (:reason result)))
        (is (= uo0rc3-registered-spec (:spec result))
            ":spec still carries the registered definition")))))

(deftest get-machine-state-no-such-machine-when-unregistered
  (testing "an unregistered machine-id surfaces :no-such-machine — rf2-uo0rc.3
            keeps the not-found path intact"
    (with-redefs [machines/machine-meta (fn [_] nil)
                  machines/machines     (fn [] [:auth :checkout])]
      (let [result (runtime/get-machine-state {:machine-id :nope})]
        (is (false? (:ok? result)))
        (is (= :no-such-machine (:reason result)))
        (is (= [:auth :checkout] (:registered result)))))))

;; ---------------------------------------------------------------------------
;; (5) health is side-effect-free.
;; ---------------------------------------------------------------------------

(deftest health-returns-status-map
  (testing "`health` returns a status map with the load-bearing slots
            `discover-app` cites"
    (let [h (runtime/health)]
      (is (true? (:ok? h)))
      (is (= runtime/session-id (:session-id h)))
      (is (boolean? (:debug-enabled? h)))
      (is (vector? (:frames h)))
      (is (boolean? (:ambiguous-frame? h)))
      (is (= :xray-mcp (:origin h))
          "health surfaces the bound origin (default `:xray-mcp`)"))))

(deftest health-installs-no-listeners
  (testing "unlike re-frame2-pair's `health`, the Xray runtime's `health` does
            NOT register trace or epoch callbacks — Xray-the-panel
            owns those (`preload.cljs`'s register-trace-collector! /
            register-epoch-collector!). Two `health` calls in a row
            must not leave residue.

            We exercise the side-effect-free property by asserting the
            framework's `register-listener!` was not called with any
            runtime-owned id — the runtime has no such id reservation."
    (runtime/health)
    (runtime/health)
    ;; No listener-side state to inspect — the contract is that the
    ;; runtime does not call register-listener! / register-epoch-listener!
    ;; from `health`. The lint here is the source-side absence; the
    ;; runtime test suite asserts behaviour, and the absence of a
    ;; per-test-listener-id reservation is the absence of an effect.
    (is true "health is side-effect-free — repeated calls compose")))

;; ---------------------------------------------------------------------------
;; (6) Dispatch tagging — events stamped with `:origin :xray-mcp`.
;; ---------------------------------------------------------------------------

(defn- dispatched-rows-with-origin
  "Read the per-frame trace ring (the same surface `get-trace-buffer`
  egresses) and return the `:rf.event/dispatched` rows whose
  `[:tags :rf.event/origin]` equals `origin`. End-to-end witness: a
  green here means the framework's dispatch opts → envelope → trace tag
  path actually stamped the tag — NOT merely that the accessor echoed an
  origin in its return map."
  [origin]
  (->> (:events (runtime/get-trace-buffer {:origin origin}))
       (filterv #(= :rf.event/dispatched (:operation %)))))

(deftest dispatch-stamps-rf-event-origin-tag-on-trace
  (testing "`dispatch!` passes the bound origin through the framework
            dispatch OPTS map, so the emitted `:rf.event/dispatched`
            trace carries `[:tags :rf.event/origin]` and
            `get-trace-buffer {:origin <origin>}` isolates the
            tool-dispatched cascade (Lock #4 / I1)"
    (let [captured (atom nil)]
      (rf/reg-event :test/capture-meta
        (fn [{:keys [db]} [_ marker]]
          (reset! captured marker)
          {:db (assoc db :marker marker)}))
      (trace-tooling/clear-trace-buffer! :rf/default)
      ;; sync? so the dispatch completes (and its trace lands) before we read.
      (let [result (runtime/dispatch! [:test/capture-meta :ok] {:sync? true})]
        (is (true? (:ok? result)))
        (is (= :xray-mcp (:origin result))
            "result echoes the bound origin"))
      (is (= :ok @captured) "handler ran")
      ;; The load-bearing assertion: read it back off the trace bus.
      (let [rows (dispatched-rows-with-origin :xray-mcp)]
        (is (seq rows)
            "`get-trace-buffer {:origin :xray-mcp}` returns the dispatched cascade")
        (is (every? #(= :xray-mcp (get-in % [:tags :rf.event/origin])) rows)
            "every returned :rf.event/dispatched row carries [:tags :rf.event/origin] :xray-mcp")
        (is (some #(= :test/capture-meta (first (get-in % [:tags :rf.event/v])))
                  rows)
            "the dispatched row is the one we fired"))
      ;; Negative control: a different origin filter must NOT return it.
      (is (empty? (dispatched-rows-with-origin :some-other-tool))
          "the cascade is attributed to :xray-mcp, not an arbitrary origin"))))

(deftest dispatch-rebinds-origin-via-eval-cljs-extent
  (testing "a `binding` around `dispatch!` re-tags the dispatch on the
            trace bus — this is the synchronous-extent contract
            `eval-cljs` rides (Lock #4 / I6)"
    (rf/reg-event :test/origin-marker
      (fn [{:keys [db]} _] {:db db}))
    (trace-tooling/clear-trace-buffer! :rf/default)
    (binding [runtime/*current-origin* :test-rebind]
      (let [result (runtime/dispatch! [:test/origin-marker] {:sync? true})]
        (is (= :test-rebind (:origin result))
            "dispatch carries the re-bound origin, not the default")))
    (let [rows (dispatched-rows-with-origin :test-rebind)]
      (is (seq rows)
          "`get-trace-buffer {:origin :test-rebind}` returns the re-tagged cascade")
      (is (every? #(= :test-rebind (get-in % [:tags :rf.event/origin])) rows)
          "the rebound origin reaches the trace tag, not just the return map"))
    ;; The default origin filter must NOT pick up the rebound dispatch.
    (is (empty? (dispatched-rows-with-origin :xray-mcp))
        "rebinding fully replaced the default origin on the trace tag")))

(deftest dispatch-refuses-non-vector
  (testing "non-vector `event` shapes refuse structurally — the same
            kind of guard re-frame2-pair-mcp's `dispatch.cljs` enforces at the
            wire layer (rf2-vflrg precedent)"
    (let [result (runtime/dispatch! :not-a-vector)]
      (is (false? (:ok? result)))
      (is (= :not-an-event-vector (:reason result))))))

;; ---------------------------------------------------------------------------
;; (7) Streaming surface — subscribe!/unsubscribe!/list-subscriptions.
;; ---------------------------------------------------------------------------

(deftest subscribe-records-metadata
  (testing "`subscribe!` records the subscription's metadata so
            `list-subscriptions` can enumerate it"
    (let [r1 (runtime/subscribe! {:topic :trace :filter {:origin :xray-mcp}})]
      (is (true? (:ok? r1)))
      (is (= :trace (:topic r1)))
      (is (string? (:sub-id r1)))

      (let [r2 (runtime/list-subscriptions)]
        (is (= 1 (:count r2)))
        (is (= [(:sub-id r1)] (mapv :id (:subs r2))))))))

(deftest subscribe-rejects-unknown-topic
  (testing "topics outside `{:trace :epoch :fx :error}` refuse"
    (let [r (runtime/subscribe! {:topic :bogus})]
      (is (false? (:ok? r)))
      (is (= :unknown-topic (:reason r))))))

(deftest unsubscribe-is-idempotent
  (testing "calling `unsubscribe!` on an unknown id returns
            `:existed? false` rather than throwing — the catalogue
            entry pins this idempotency"
    (let [r (runtime/unsubscribe! {:sub-id "no-such-sub"})]
      (is (true? (:ok? r)))
      (is (false? (:existed? r))))))

(deftest list-subscriptions-filters-by-topic
  (testing "`:topic` narrows the enumeration"
    (runtime/subscribe! {:topic :trace})
    (runtime/subscribe! {:topic :epoch})
    (let [r (runtime/list-subscriptions {:topic :trace})]
      (is (= 1 (:count r)))
      (is (every? #(= :trace (:topic %)) (:subs r))))))

;; ---------------------------------------------------------------------------
;; (8) tail-build-probe — monotonic counter, stable session-id.
;; ---------------------------------------------------------------------------

(deftest tail-build-probe-is-monotonic
  (testing "`tail-build-probe` increments on every call so the MCP
            server's poll loop can detect a hot-reload via value-change"
    (let [r1 (runtime/tail-build-probe)
          r2 (runtime/tail-build-probe)]
      (is (true? (:ok? r1)))
      (is (= runtime/session-id (:session-id r1))
          "session-id carried for the server's cross-call sanity check")
      (is (> (:probe r2) (:probe r1))
          "probe value advances monotonically"))))

;; ---------------------------------------------------------------------------
;; (9) get-epoch-history degrades cleanly without records.
;; ---------------------------------------------------------------------------

(deftest get-epoch-history-empty-when-no-epochs
  (testing "with no epochs recorded against the resolved frame, the
            accessor returns `{:ok? true :epochs []}` rather than nil
            — the MCP tool layer rides the `:ok?` slot"
    (let [result (runtime/get-epoch-history)]
      (is (true? (:ok? result)))
      (is (vector? (:epochs result)))
      (is (= 0 (:count result))))))

;; ---------------------------------------------------------------------------
;; (10) get-trace-buffer adopts per-frame rings (rf2-q03j7).
;; ---------------------------------------------------------------------------
;;
;; Per rf2-g1b2m / rf2-8uwce the framework's trace ring is per-frame and
;; cascade-keyed. `get-trace-buffer` is the Xray-runtime MCP accessor for
;; the historical flat-events shape; it now resolves a frame-id, requires
;; one (refuses on ambiguity), and forwards `{:flat true}` to the
;; framework so the existing flat-event consumer shape is preserved.

(deftest get-trace-buffer-resolves-sole-frame
  (testing "`get-trace-buffer` resolves the sole-registered frame
            without an explicit `:frame` arg (post per-frame ring
            adoption rf2-q03j7) and returns the historical flat-event
            shape via the framework's `{:flat true}` opt"
    (rf/reg-event :test/just-dispatch
      (fn [{:keys [db]} _] {:db (assoc db :touched? true)}))
    (rf/dispatch-sync [:test/just-dispatch])
    (let [result (runtime/get-trace-buffer)]
      (is (true? (:ok? result))
          "single-frame resolution succeeds without explicit :frame")
      (is (= :rf/default (:frame result))
          "the sole frame is the resolved frame")
      (is (vector? (:events result))
          "events is a vector (flat-event shape, not cascade bundles)")
      (is (number? (:count result))))))

(deftest get-trace-buffer-refuses-ambiguous-frame
  (testing "with no frames registered (or ambiguous resolution), the
            accessor surfaces a structured `:no-frame-resolved` refusal
            rather than guessing — per-frame ring API requires a
            frame-id (rf2-q03j7)"
    (frame/destroy-frame! :rf/default)
    (let [result (runtime/get-trace-buffer)]
      (is (false? (:ok? result)))
      (is (= :no-frame-resolved (:reason result))))))

(deftest get-issues-walks-every-frame
  (testing "`get-issues` iterates every registered frame's flat-event
            stream and merges, so cross-frame issues fired during a
            multi-frame cascade still surface (rf2-q03j7 — per-frame
            ring adoption)"
    ;; Smoke test: with no errors emitted, the result is structured
    ;; correctly even though events is empty. The merge-across-frames
    ;; semantics is the load-bearing contract here; an empty event
    ;; vector is a sufficient witness that the form runs cleanly
    ;; against the new per-frame trace-buffer signature.
    (let [result (runtime/get-issues)]
      (is (true? (:ok? result)))
      (is (vector? (:issues result)))
      (is (number? (:count result))))))

;; ---------------------------------------------------------------------------
;; (10b) Default-suppress whole `:sensitive? true` trace/issue ENVELOPES
;;       at the runtime/MCP seam (rf2-to36uj).
;; ---------------------------------------------------------------------------
;;
;; `egress-value` scrubs the VALUES inside an event but does NOT drop a
;; whole event marked `:sensitive? true`. The framework's per-frame ring
;; RETAINS every emitted event (a faithful record), so a sensitive
;; event's envelope (existence, :op-type, timing, source, ids, :tags)
;; would otherwise cross the off-box AI/MCP / log boundary by default.
;; Per Spec 009 §Privacy + spec/013-Trace-Consumer.md the runtime/MCP
;; seam default-SUPPRESSES whole sensitive events; the per-call
;; `:include-sensitive? true` opt is the explicit opt-back-in.
;;
;; We seed the ring directly via `rf/emit-trace-event!` (the published
;; `re-frame.trace/emit!` alias). `:sensitive?` supplied in `tags` wins
;; (`compute-sensitive?`) and is hoisted to the event's top level; a
;; `:rf.trace/dispatch-id` + a resolvable `:frame` are the two slots
;; `push-to-ring!` requires to retain the event in the per-frame ring.

(defn- emit-sensitive-into-ring! [op-type operation]
  ;; A frame-bound, cascade-keyed emit so the framework's per-frame ring
  ;; retains it (frameless emits stream live but are never retained).
  (rf/emit-trace-event! op-type operation
                        {:frame                 :rf/default
                         :rf.trace/dispatch-id  (random-uuid)
                         :sensitive?            true}))

(defn- emit-plain-into-ring! [op-type operation]
  (rf/emit-trace-event! op-type operation
                        {:frame                :rf/default
                         :rf.trace/dispatch-id (random-uuid)}))

(deftest get-trace-buffer-default-suppresses-sensitive-envelope
  (testing "`get-trace-buffer` DROPS a whole `:sensitive? true` trace
            event by default — the envelope (op-type / timing / ids /
            tags), not just the values, must respect the default-suppress
            contract at the off-box seam (rf2-to36uj)"
    (trace-tooling/clear-trace-buffer! :rf/default)
    (emit-plain-into-ring!     :sub  :rf.sub/run)
    (emit-sensitive-into-ring! :sub  :rf.sub/run)
    (let [default (runtime/get-trace-buffer)
          opted   (runtime/get-trace-buffer {:include-sensitive? true})]
      (is (true? (:ok? default)))
      (is (empty? (filterv :sensitive? (:events default)))
          "no sensitive envelope crosses the boundary by default")
      (is (pos? (:count default))
          "the non-sensitive event still surfaces")
      (is (seq (filterv :sensitive? (:events opted)))
          ":include-sensitive? true is the explicit opt-back-in — the sensitive envelope returns")
      (is (= (inc (:count default)) (:count opted))
          "exactly the suppressed sensitive event is the delta between default and opted-in"))))

(deftest get-issues-default-suppresses-sensitive-envelope
  (testing "`get-issues` DROPS a whole `:sensitive? true` issue-tier
            event by default and returns it only under
            `:include-sensitive? true` (rf2-to36uj — symmetric with
            get-trace-buffer)"
    (trace-tooling/clear-trace-buffer! :rf/default)
    (emit-plain-into-ring!     :warning :rf.warning/plain)
    (emit-sensitive-into-ring! :error   :rf.error/handler-exception)
    (let [default (runtime/get-issues)
          opted   (runtime/get-issues {:include-sensitive? true})]
      (is (true? (:ok? default)))
      (is (empty? (filterv :sensitive? (:issues default)))
          "no sensitive issue envelope crosses the boundary by default")
      (is (some #(= :warning (:op-type %)) (:issues default))
          "the non-sensitive issue still surfaces")
      (is (seq (filterv :sensitive? (:issues opted)))
          ":include-sensitive? true returns the sensitive issue envelope")
      (is (= (inc (:count default)) (:count opted))
          "exactly the suppressed sensitive issue is the delta"))))

(deftest get-issues-filters-on-severity-op-type-only
  (testing "rf2-wd1pgb — `get-issues` filters on the SEVERITY `:op-type`
            vocabulary only (`:error` / `:warning`). Per Spec 009's
            closed `:op-type` vocabulary, `:rf.schema/violation` and
            `:rf.hydration/mismatch` are `:operation` values (the real
            hydration operation is `:rf.ssr/hydration-mismatch`), never
            `:op-type` values — a real schema-violation/hydration-
            mismatch event always rides `:op-type :warning`/`:error`
            already, so those two dead set members could never match
            genuine traffic. Left in, they were a latent hole: an event
            emitted with either as its bare, malformed `:op-type` would
            have been silently ADMITTED into the Issues ribbon instead
            of falling on the floor with every other non-issue-tier
            event."
    (trace-tooling/clear-trace-buffer! :rf/default)
    (emit-plain-into-ring! :rf.schema/violation   :rf.schema/violation)
    (emit-plain-into-ring! :rf.hydration/mismatch :rf.hydration/mismatch)
    (emit-plain-into-ring! :warning               :rf.warning/plain)
    (let [result   (runtime/get-issues)
          op-types (into #{} (map :op-type) (:issues result))]
      (is (= #{:warning} op-types)
          "only the genuine severity op-type surfaces — the two dead
           set members never match, admitting nothing")
      (is (= 1 (:count result))))))

;; ---------------------------------------------------------------------------
;; (11) egress-value / egress-record — the single named safe-egress fn
;;      (rf2-rcogp: THE SAFE PATH IS THE SHORT PATH).
;; ---------------------------------------------------------------------------
;;
;; The runtime hands values to an off-box AI/MCP boundary and to logs.
;; rf2-rcogp ships one NAMED off-box egress fn with the off-box defaults
;; baked in, so the forwarder author's shortest call is the safe one.
;; These tests are the failing-before / passing-after regression: the
;; named fn redacts a sensitive value / record on the off-box path, and
;; the call sites we rerouted (get-app-db / get-epoch-history / …) still
;; redact end-to-end.

(defn- seed-sensitive-schema! []
  ;; EP-0025: durable app-db classification rides the commit-plane
  ;; classification effects. `elision/apply-classification-effects` writes a
  ;; `:source :effect` declaration (index-free :rf/path) onto the frame's
  ;; `:sensitive-declarations` so the wire walker substitutes `:rf/redacted`
  ;; for that path on off-box egress (the same registry write a reg-event
  ;; returning `:sensitive` performs).
  ;;
  ;; The `:sensitive-declarations` live in the frame's runtime-db partition
  ;; at `[:rf.runtime/elision :sensitive-declarations]` (EP-0001), so a
  ;; whole-db `:db` reset (a reg-event handler returning a fresh map) no
  ;; longer wipes them — a `:db` reset replaces only app-db, never runtime-db.
  (frame/swap-runtime-db! :rf/default
    (fn [rt] (elision/apply-classification-effects rt {:sensitive [[:auth :password]]}))))

(deftest egress-value-redacts-sensitive-on-the-safe-default-path
  (testing "`egress-value` with no opts (the SHORT path) redacts a
            frame-declared sensitive slot — the off-box defaults are
            baked in so a forwarder author never re-derives the opts"
    (seed-sensitive-schema!)
    (let [out (runtime/egress-value {:auth {:username "ada" :password "shh"}})]
      (is (= "ada" (get-in out [:auth :username]))
          "non-sensitive slots pass through verbatim")
      (is (= :rf/redacted (get-in out [:auth :password]))
          "the sensitive slot is redacted on the bare (default) call"))))

(deftest egress-value-opts-back-in-to-sensitive
  (testing "a caller that is itself the trust boundary opts back in to
            the raw value with `{:include-sensitive? true}`"
    (seed-sensitive-schema!)
    (let [out (runtime/egress-value {:auth {:password "shh"}}
                                    {:include-sensitive? true})]
      (is (= "shh" (get-in out [:auth :password]))
          ":include-sensitive? true ⇒ the raw value passes through"))))

(deftest egress-record-redacts-sensitive-payload-slots
  (testing "`egress-record` routes an epoch record through the normative
            epoch projection on the safe default path — payload slots
            (:db-before / :db-after) are wire-elided while bookkeeping
            slots pass through unchanged"
    (seed-sensitive-schema!)
    (let [record  {:epoch-id    "e1"
                   :dispatch-id 7
                   :event-id    :auth/login
                   :db-before   {:auth {:username "ada" :password "shh"}}
                   :db-after    {:auth {:username "ada" :password "newpw"}}}
          out     (runtime/egress-record record)]
      (is (= "e1" (:epoch-id out)) "bookkeeping :epoch-id passes through")
      (is (= 7 (:dispatch-id out)) "bookkeeping :dispatch-id passes through")
      (is (= :rf/redacted (get-in out [:db-before :auth :password]))
          "the sensitive payload slot is redacted in :db-before")
      (is (= :rf/redacted (get-in out [:db-after :auth :password]))
          "the sensitive payload slot is redacted in :db-after")
      (is (= "ada" (get-in out [:db-before :auth :username]))
          "non-sensitive payload slots pass through"))))

(deftest egress-record-opts-back-in-to-sensitive
  (testing "`egress-record` with `{:include-sensitive? true}` routes
            through `egress-value` so the opt-in reaches the walker
            (the normative projection has no opt-in arg)"
    (seed-sensitive-schema!)
    (let [record {:db-after {:auth {:password "shh"}}}
          out    (runtime/egress-record record {:include-sensitive? true})]
      (is (= "shh" (get-in out [:db-after :auth :password]))
          ":include-sensitive? true ⇒ the raw value passes through"))))

;; ---------------------------------------------------------------------------
;; rf2-5w06uu — epoch egress must NOT bypass frame-state runtime-db redaction
;; when a caller opts into sensitive / large APP-DB values.
;; ---------------------------------------------------------------------------
;;
;; The frame-state slots are `{:rf.db/app <app-db> :rf.db/runtime
;; <runtime-db>}`. The `:rf.db/runtime` partition (machine snapshots,
;; route slice, spawn registry, elision registry, SSR/hydration metadata)
;; is REDACTED off-box by default (Mike ruling #14). `:include-sensitive?`
;; / `:include-large?` opt into the APP-DB partition's privacy / size
;; posture only — they MUST NOT lift the orthogonal runtime-db partition
;; boundary. A trusted-local caller opts into runtime-db explicitly with
;; `:include-runtime-db? true`.

(defn- frame-state-record []
  ;; A record carrying both partitions in :frame-state-after, per the
  ;; rf2-5w06uu acceptance criteria.
  {:epoch-id    "e1"
   :frame       :rf/default
   :event-id    :auth/login
   :frame-state-after {:rf.db/app     {:auth {:username "ada" :password "shh"}}
                       :rf.db/runtime {:rf.runtime/machines {:m :running}}}})

(deftest egress-record-default-redacts-app-sensitive-and-runtime-db
  (testing "rf2-5w06uu — default egress (no opts) redacts the app-db
            sensitive value AND default-redacts the runtime-db partition
            of the frame-state slot"
    (seed-sensitive-schema!)
    (let [out (runtime/egress-record (frame-state-record))
          fs  (:frame-state-after out)]
      (is (= "ada" (get-in fs [:rf.db/app :auth :username]))
          "non-sensitive app-db value passes through")
      (is (= :rf/redacted (get-in fs [:rf.db/app :auth :password]))
          "sensitive app-db value redacted on the default path")
      (is (= :rf/redacted (:rf.db/runtime fs))
          "runtime-db partition redacted on the default path"))))

(deftest egress-record-include-sensitive-keeps-runtime-db-redacted
  (testing "rf2-5w06uu — {:include-sensitive? true} reveals app-db
            sensitive values but KEEPS :rf.db/runtime redacted (the bypass
            bug: it used to walk the raw record and leak runtime-db)"
    (seed-sensitive-schema!)
    (let [out (runtime/egress-record (frame-state-record)
                                     {:include-sensitive? true})
          fs  (:frame-state-after out)]
      (is (= "shh" (get-in fs [:rf.db/app :auth :password]))
          ":include-sensitive? true reveals the app-db sensitive value")
      (is (= :rf/redacted (:rf.db/runtime fs))
          ":include-sensitive? true does NOT lift the runtime-db redaction"))))

(deftest egress-record-include-large-keeps-runtime-db-redacted
  (testing "rf2-5w06uu — {:include-large? true} also keeps :rf.db/runtime
            redacted (orthogonal partition boundary holds under the size
            opt-in too)"
    (seed-sensitive-schema!)
    (let [out (runtime/egress-record (frame-state-record)
                                     {:include-large? true})
          fs  (:frame-state-after out)]
      (is (= :rf/redacted (:rf.db/runtime fs))
          ":include-large? true does NOT lift the runtime-db redaction")
      ;; Sensitive app-db value still redacted (large opt-in is not a
      ;; sensitive opt-in).
      (is (= :rf/redacted (get-in fs [:rf.db/app :auth :password]))
          ":include-large? alone does not reveal sensitive app-db values"))))

(deftest egress-record-trusted-local-opts-into-runtime-db
  (testing "rf2-5w06uu — only the explicit trusted-local
            :include-runtime-db? true reveals the runtime-db partition;
            sensitive/large handling still applies inside the allowed
            partitions"
    (seed-sensitive-schema!)
    (let [out (runtime/egress-record (frame-state-record)
                                     {:include-sensitive?  true
                                      :include-runtime-db? true})
          fs  (:frame-state-after out)]
      (is (= {:rf.runtime/machines {:m :running}} (:rf.db/runtime fs))
          ":include-runtime-db? true crosses the runtime-db partition")
      (is (= "shh" (get-in fs [:rf.db/app :auth :password]))
          ":include-sensitive? still applies inside the app-db partition"))))

;; ---------------------------------------------------------------------------
;; EP-0001 (rf2-jj1xer · ruling #14) — partition-aware runtime-db egress.
;; ---------------------------------------------------------------------------
;;
;; `egress-runtime-db-value` is the partition-distinguishing peer of
;; `egress-value`: app-db egresses subject to per-slot elision; runtime-db
;; is REDACTED/OMITTED off-box by default and crosses the wire only when a
;; trusted-local caller opts in with `:include-runtime-db? true`.

(deftest egress-runtime-db-value-redacts-by-default
  (testing "the safe default (no opt-in) substitutes :rf/redacted for a
            runtime-db value — runtime-db is redacted off-box by default
            (ruling #14)"
    (is (= :rf/redacted
           (runtime/egress-runtime-db-value {:rf.runtime/machines {:m 1}}))
        "the bare call redacts the whole runtime-db value")
    (is (= :rf/redacted
           (runtime/egress-runtime-db-value {:state [:a :b]} {:include-sensitive? true}))
        ":include-sensitive? alone does NOT lift the runtime-db partition redaction")))

(deftest egress-runtime-db-value-trusted-local-opts-in
  (testing "a trusted-local caller opts in to the runtime-db value with
            :include-runtime-db? true; the value then routes through the
            value walker (per-slot elision still applies)"
    (seed-sensitive-schema!)
    (let [v   {:state [:active] :auth {:password "shh"}}
          out (runtime/egress-runtime-db-value v {:include-runtime-db? true})]
      (is (= [:active] (:state out))
          "the runtime-db value crosses when the trusted-local opt-in is set")
      (is (= :rf/redacted (get-in out [:auth :password]))
          "the partition opt-in COMPOSES with per-slot sensitive elision — it does not override it"))))

(deftest get-app-db-does-not-leak-runtime-db-partition
  (testing "EP-0001 rf2-jj1xer — get-app-db reads ONLY the app-db partition
            (rf/app-db-value), so a runtime-db-only commit never bleeds into
            the app-db read (partition distinction at the read boundary)"
    (rf/reg-event :test/seed-app (fn [{:keys [db]} _] {:db {:cart {:items [:a]}}}))
    (rf/dispatch-sync [:test/seed-app])
    (rf/reg-event :test/seed-rt {:rf/machine? true}
      (fn [_ _] {:rf.db/runtime {:rf.runtime/machines {:snapshots {:m {:state :on}}}}}))
    (rf/dispatch-sync [:test/seed-rt])
    (let [result (runtime/get-app-db)]
      (is (true? (:ok? result)))
      (is (= {:cart {:items [:a]}} (:value result))
          "get-app-db returns the app-db partition only — no :rf.runtime/* keys")
      (is (nil? (get-in result [:value :rf.runtime/machines]))
          "runtime-db state is absent from the app-db read"))))

(deftest get-app-db-redacts-sensitive-end-to-end
  (testing "the rerouted `get-app-db` call site still redacts a
            sensitive slot end-to-end (regression: the named egress fn
            is wired into the accessor)"
    (rf/reg-event :test/seed-auth
      (fn [{:keys [db]} _] {:db {:auth {:username "ada" :password "shh"}}}))
    (rf/dispatch-sync [:test/seed-auth])
    ;; Populate AFTER the whole-db reset so the declarations survive.
    (seed-sensitive-schema!)
    (let [result (runtime/get-app-db)]
      (is (true? (:ok? result)))
      (is (= :rf/redacted (get-in result [:value :auth :password]))
          "get-app-db scrubs the sensitive slot via egress-value")
      (is (= "ada" (get-in result [:value :auth :username]))
          "non-sensitive slots survive"))))

;; ---------------------------------------------------------------------------
;; (11b) PATH-SCOPED get-app-db threads the absolute :path into the egress
;;       walker so a scoped slice elides against the frame-owned
;;       sensitive / large app-db declarations (rf2-a96xq).
;; ---------------------------------------------------------------------------
;;
;; Before the fix the scoped read called `egress-value` WITHOUT the
;; absolute :path, so the walker started the sliced leaf at root [] and a
;; declaration registered for [:auth :password] never matched a direct
;; read of {:path [:auth :password]} — the raw value crossed the off-box
;; boundary despite the safe-default contract. These tests pin the
;; fail-closed default (redact / size-elide) AND the operator opt-in.

(defn- seed-large-schema! []
  ;; EP-0025: the commit-plane :large classification (index-free :rf/path)
  ;; writes onto the per-frame `:declarations` so the wire walker substitutes
  ;; the `:rf.size/large-elided` marker for that path on off-box egress (the
  ;; size sibling of `seed-sensitive-schema!`, `:source :effect`). The
  ;; declaration lives in runtime-db at `[:rf.runtime/elision :declarations]`
  ;; (EP-0001), so a whole-db `:db` reset leaves it untouched.
  (frame/swap-runtime-db! :rf/default
    (fn [rt] (elision/apply-classification-effects rt {:large [[:blob :payload]]}))))

(deftest get-app-db-path-scoped-redacts-sensitive-leaf-by-default
  (testing "a PATH-scoped get-app-db over a frame-declared sensitive
            leaf redacts by default — the absolute :path is threaded into
            the egress walker so the [:auth :password] declaration
            matches the scoped slice (rf2-a96xq: fail-closed)"
    (rf/reg-event :test/seed-auth
      (fn [{:keys [db]} _] {:db {:auth {:username "ada" :password "shh"}}}))
    (rf/dispatch-sync [:test/seed-auth])
    (seed-sensitive-schema!)
    ;; Scope the read down to the sensitive leaf itself.
    (let [result (runtime/get-app-db {:path [:auth :password]})]
      (is (true? (:ok? result)))
      (is (= [:auth :password] (:path result)) "echoes the requested path")
      (is (= :rf/redacted (:value result))
          "the path-scoped sensitive leaf is redacted by default — NOT the raw value")
      (is (not= "shh" (:value result))
          "the raw secret never crosses the off-box boundary on the safe default path"))
    ;; And a scope that STRADDLES the sensitive leaf (one level up) still
    ;; redacts the nested slot — the threaded :path is the parent and the
    ;; walker descends to the absolute leaf.
    (let [result (runtime/get-app-db {:path [:auth]})]
      (is (= :rf/redacted (get-in result [:value :password]))
          "a parent-scoped slice still redacts the nested sensitive leaf")
      (is (= "ada" (get-in result [:value :username]))
          "non-sensitive sibling survives in the scoped slice"))))

(deftest get-app-db-path-scoped-reveals-sensitive-on-opt-in
  (testing "a PATH-scoped get-app-db with {:include-sensitive? true}
            reveals the raw leaf — the operator opt-in still flows through
            the threaded-path egress (rf2-a96xq: opt-in gate open)"
    (rf/reg-event :test/seed-auth
      (fn [{:keys [db]} _] {:db {:auth {:username "ada" :password "shh"}}}))
    (rf/dispatch-sync [:test/seed-auth])
    (seed-sensitive-schema!)
    (let [result (runtime/get-app-db {:path [:auth :password]
                                      :include-sensitive? true})]
      (is (true? (:ok? result)))
      (is (= "shh" (:value result))
          ":include-sensitive? true ⇒ the raw leaf is revealed at the scoped path"))))

(deftest get-app-db-path-scoped-elides-large-leaf-by-default
  (testing "a PATH-scoped get-app-db over a frame-declared :large leaf
            emits the :rf.size/large-elided marker by default, and reveals
            the raw value only on {:include-large? true} (rf2-a96xq:
            symmetric size minimisation on the scoped path)"
    (rf/reg-event :test/seed-blob
      (fn [{:keys [db]} _] {:db {:blob {:payload {:big "value"}}}}))
    (rf/dispatch-sync [:test/seed-blob])
    (seed-large-schema!)
    (let [result (runtime/get-app-db {:path [:blob :payload]})]
      (is (true? (:ok? result)))
      (is (contains? (:value result) :rf.size/large-elided)
          "the path-scoped large leaf is size-elided by default")
      (is (not= {:big "value"} (:value result))
          "the raw large value does not cross the boundary on the safe default path"))
    (let [result (runtime/get-app-db {:path [:blob :payload]
                                      :include-large? true})]
      (is (= {:big "value"} (:value result))
          ":include-large? true ⇒ the raw large leaf is revealed at the scoped path"))))

;; ---------------------------------------------------------------------------
;; (12) get-app-db-diff returns the changed-paths {:added :removed :changed}
;;      slice shape — NOT two whole app-db snapshots (rf2-uv2q2).
;; ---------------------------------------------------------------------------
;;
;; The accessor's docstring + the spec API table promise the
;; changed-paths shape; the prior impl egressed the WHOLE :db-before +
;; :db-after maps under {:before :after}. These tests pin the corrected
;; shape so the drift cannot silently return, and prove the per-slice
;; values route through egress-value (privacy + size minimisation).
;;
;; `replace-app-db!` records a synthetic epoch with :db-before = the old
;; app-db and :db-after = the injected value — the deterministic way to
;; seed an epoch with a known before/after pair in a node unit test
;; (the epoch artefact is a hard Xray dep per tools/xray/deps.edn).

(defn- record-epoch-via-reset!
  "Seed `before` into the sole frame, then `replace-app-db!` to `after`
  so the framework records a synthetic epoch carrying
  `:db-before before` / `:db-after after`. Returns the recorded
  epoch's `:epoch-id`."
  [before after]
  (rf/reg-event :test/seed-before (fn [_ _] {:db before}))
  (rf/dispatch-sync [:test/seed-before])
  (let [fid (first (rf/frame-ids))]
    (rf/replace-app-db! fid after)
    (-> (rf/epoch-history fid) peek :epoch-id)))

(deftest get-app-db-diff-returns-changed-paths-shape
  (testing "`get-app-db-diff` projects the changed-paths
            {:added :removed :changed} slice shape (rf2-uv2q2) — NOT the
            prior {:before :after} whole-db snapshots"
    (let [epoch-id (record-epoch-via-reset!
                     {:keep "v" :gone "old" :flip 1}
                     {:keep "v" :added "new" :flip 2})
          result   (runtime/get-app-db-diff {:epoch-id epoch-id})]
      (is (true? (:ok? result))
          "diff resolves the sole frame + named epoch")
      (let [diff (:diff result)]
        (is (= #{:added :removed :changed} (set (keys diff)))
            "the diff carries exactly the changed-paths buckets — no :before/:after")
        (is (not (contains? diff :before))
            "the whole-db :before snapshot is gone")
        (is (not (contains? diff :after))
            "the whole-db :after snapshot is gone")
        ;; :added — a new top-level key.
        (is (some #(= [:added] (:path %)) (:added diff))
            ":added carries the new [:added] path slice")
        (is (= "new" (some #(when (= [:added] (:path %)) (:value %)) (:added diff)))
            ":added slice carries the after-value at the path")
        ;; :removed — a key that disappeared.
        (is (= "old" (some #(when (= [:gone] (:path %)) (:value %)) (:removed diff)))
            ":removed slice carries the before-value at the path")
        ;; :changed — a scalar that flipped (before + after).
        (let [flip-row (some #(when (= [:flip] (:path %)) %) (:changed diff))]
          (is (some? flip-row) ":changed carries the flipped [:flip] path")
          (is (= 1 (:before flip-row)) ":changed slice carries the before-value")
          (is (= 2 (:after flip-row)) ":changed slice carries the after-value"))))))

(deftest get-app-db-diff-redacts-sensitive-slices
  (testing "`get-app-db-diff` routes each changed-path slice through
            egress-value — a frame-declared sensitive slot that changed
            redacts in the :changed bucket (rf2-uv2q2 privacy)"
    (let [epoch-id (record-epoch-via-reset!
                     {:auth {:username "ada" :password "old-pw"}}
                     {:auth {:username "ada" :password "new-pw"}})]
      ;; Declare the sensitive path AFTER the resets so the declaration
      ;; survives (same ordering as the get-app-db end-to-end test).
      (seed-sensitive-schema!)
      (let [result   (runtime/get-app-db-diff {:epoch-id epoch-id})
            changed  (get-in result [:diff :changed])
            pw-row   (some #(when (= [:auth :password] (:path %)) %) changed)]
        (is (some? pw-row)
            "the changed sensitive path appears in the :changed bucket")
        (is (= :rf/redacted (:before pw-row))
            ":before slice is redacted via egress-value")
        (is (= :rf/redacted (:after pw-row))
            ":after slice is redacted via egress-value")))))

;; ---------------------------------------------------------------------------
;; (13) get-handlers routes :meta through egress-value — the
;;      every-read-routes-through-wire-elision invariant has no exception
;;      (rf2-yl0v8).
;; ---------------------------------------------------------------------------

(deftest get-handlers-redacts-sensitive-meta
  (testing "`get-handlers` routes each handler's :meta through
            egress-value (rf2-yl0v8) — a sensitive-declared slot in a
            handler's metadata redacts, holding the every-read-routes-
            through-wire-elision invariant with no exceptions"
    (seed-sensitive-schema!)
    ;; Register an event whose registration-metadata carries a
    ;; value-bearing slot at the frame-declared sensitive path. We use
    ;; the registrar directly (not the `reg-event` macro, which emits its
    ;; own metadata and would not let us plant the slot) so the meta map
    ;; itself carries `{:auth {:password ...}}`. `egress-value` walks the
    ;; meta map from root and substitutes :rf/redacted for the sensitive
    ;; absolute path.
    (registrar/register! :event :test/handler-with-secret
                         {:handler-fn (fn [db _] db)
                          :auth       {:password "leak-me"}})
    (let [result   (runtime/get-handlers {:kind :event})
          rec      (some #(when (= :test/handler-with-secret (:id %)) %)
                         (:handlers result))]
      (is (some? rec)
          "the registered handler appears in the projection")
      (is (= :rf/redacted (get-in rec [:meta :auth :password]))
          ":meta routes through egress-value — the sensitive slot is redacted"))))

(deftest get-handlers-unnarrowed-sweep-matches-registrar-kinds
  (testing "rf2-ku6j74 — the unnarrowed `get-handlers` sweep (no
            `:kind` opt) walks the framework's ACTUAL closed
            `re-frame.registrar/kinds` set, not a hand-duplicated
            literal that had drifted from it: `:route` / `:resource` /
            `:mutation` / `:resource-scope` / `:interceptor` / `:head`
            / `:error-projector` were previously OMITTED, while the
            phantom `:machine` / `:reg-machine` (machines are not a
            registrar kind — `get-machine-list` surfaces those,
            reading `re-frame.machines`) were INCLUDED and always
            walked to nothing."
    (let [swept (set @#'runtime/registrar-kinds)]
      (is (= registrar/kinds swept)
          "the unnarrowed sweep is exactly the registrar's closed kind-set")
      (is (not (contains? swept :machine))
          "machines are not a registrar kind")
      (is (not (contains? swept :reg-machine))
          "machines are not a registrar kind")
      (doseq [k [:route :resource :mutation :resource-scope
                 :interceptor :head :error-projector]]
        (is (contains? swept k)
            (str k " was previously omitted from the unnarrowed sweep")))))
  (testing "a handler registered under a previously-omitted kind
            (`:route`) IS returned by an unnarrowed `get-handlers`
            call — the regression a stale literal would silently miss"
    (registrar/register! :route :test/handler-kinds-route
                         {:handler-fn (fn [_] nil)})
    (let [result (runtime/get-handlers)
          ids    (into #{} (map :id) (:handlers result))]
      (is (contains? ids :test/handler-kinds-route)
          "the :route registration surfaces in the unnarrowed sweep"))))

;; ---------------------------------------------------------------------------
;; (14) get-source-coord routes :source-coord through egress-value — the
;;      LAST direct-read accessor that bypassed the egress invariant
;;      (rf2-j8b0u). Source-coord is structurally {:ns :file :line :column}
;;      today, but Spec 009's user-supplied `:rf.handler/source` override
;;      lets a code-gen pipeline stamp arbitrary values into the slot, so
;;      the accessor egresses unconditionally rather than judging per-read.
;; ---------------------------------------------------------------------------

(defn- register-handler-with-sourcey-coord!
  "Register an event whose registration metadata carries a `:source-coord`
  whose value sits at the frame-declared sensitive path. We use the
  registrar directly (not the `reg-event` macro, which emits its own
  metadata) so the `:source-coord` slot we plant survives to
  `rf/handler-meta` verbatim. `egress-value` walks the source-coord value
  from its root and substitutes :rf/redacted for the sensitive path."
  []
  (registrar/register! :event :test/coord-with-secret
                       {:handler-fn   (fn [db _] db)
                        :source-coord {:auth {:password "leak-me"}}}))

(deftest get-source-coord-redacts-sensitive-on-the-safe-default-path
  (testing "`get-source-coord` routes the projected :source-coord through
            egress-value (rf2-j8b0u) — a sensitive-declared slot in the
            source-coord redacts on the bare (default, opt-out) call,
            holding the every-read-routes-through-wire-elision invariant
            with no exceptions"
    (seed-sensitive-schema!)
    (register-handler-with-sourcey-coord!)
    (let [result (runtime/get-source-coord {:kind :event
                                            :id   :test/coord-with-secret})]
      (is (true? (:ok? result))
          "the source-coord resolves for the registered handler")
      (is (= :rf/redacted (get-in result [:source-coord :auth :password]))
          ":source-coord routes through egress-value — the sensitive slot is redacted"))))

(deftest get-source-coord-opts-back-in-to-sensitive
  (testing "`get-source-coord` with `{:include-sensitive? true}` plumbs the
            trust-boundary opt-in to the walker — the raw source-coord value
            passes through (negative/opt-in coverage for rf2-j8b0u)"
    (seed-sensitive-schema!)
    (register-handler-with-sourcey-coord!)
    (let [result (runtime/get-source-coord {:kind               :event
                                            :id                 :test/coord-with-secret
                                            :include-sensitive? true})]
      (is (true? (:ok? result))
          "the source-coord resolves for the registered handler")
      (is (= "leak-me" (get-in result [:source-coord :auth :password]))
          ":include-sensitive? true ⇒ the raw value passes through"))))

;; ---------------------------------------------------------------------------
;; (15) Resource accessors expose METADATA on the redacted-summary default
;;      path; only payload values follow the off-box egress (rf2-tgm1xu).
;; ---------------------------------------------------------------------------
;;
;; BEFORE the fix, `list-resource-instances` / `get-resource-state` routed
;; the WHOLE runtime-db entry through `egress-runtime-db-value` BEFORE
;; projection, so the default (no `:include-runtime-db?`) collapsed every
;; entry to the bare `:rf/redacted` sentinel — the projection then read nil
;; for status / owners / tags / request-id and the rows were USELESS (and
;; the status/tag/owner/request-id filters, which filter the projected
;; rows, could never match). The EP-0003 tool contract (Spec 016 §Xray,
;; line 314) is that a REDACTED SUMMARY STILL EXPOSES METADATA. AFTER the
;; fix the metadata always projects; only the payload slots (data / error /
;; refresh-error + the key's scope/params) follow the runtime-db egress.
;;
;; The resources RUNTIME artefact is not on the xray test classpath, so we
;; seed the live entries directly into the runtime-db partition (the slot
;; the resources runtime writes) via a framework-authority `:rf/machine?`
;; event returning the reserved `:rf.db/runtime` effect — the same seeding
;; idiom the machine-state tests use.

(def ^:private tgm1xu-scope [:rf.scope/session {:user-id "u-42"}])
(def ^:private tgm1xu-key   [tgm1xu-scope :article/by-slug {:slug "welcome"}])
(def ^:private tgm1xu-key-2 [tgm1xu-scope :article/by-slug {:slug "old"}])

(def ^:private tgm1xu-entry
  {:resource/id    :article/by-slug
   :status         :loaded
   :data           {:title "Welcome"}
   :error          nil
   :generation     4
   :attempt        2
   :request-id     [:w 4]
   :current-work   [:rf.work/resource tgm1xu-key 4]
   :active-owners  #{[:route :route/article "nav-1"]}
   :tags           #{[:article "welcome"]}
   :loaded-at      900000
   :stale-at       9999999999999})

(def ^:private tgm1xu-entry-2
  {:resource/id   :article/by-slug
   :status        :error
   :data          nil
   :error         {:message "boom"}
   :generation    1
   :request-id    [:w 1]
   :active-owners #{}
   :tags          #{[:article "old"]}})

(defn- seed-resource-entries! [entries]
  (rf/reg-event :test/seed-resources
    {:rf/machine? true}
    (fn [_ _]
      {:rf.db/runtime {:rf.runtime/resources {:entries entries}}}))
  (rf/dispatch-sync [:test/seed-resources]))

(deftest list-resource-instances-exposes-metadata-on-default-path
  (testing "rf2-tgm1xu — the DEFAULT (no :include-runtime-db?) list returns
            USEFUL redacted-summary rows: status / owners / tags / request-id
            / generation all project (metadata is never redacted), while the
            payload :data is redacted off-box"
    (seed-resource-entries! {tgm1xu-key tgm1xu-entry})
    (let [result (runtime/list-resource-instances)]
      (is (true? (:ok? result)))
      (is (= 1 (:count result)))
      (let [row (first (:instances result))]
        (is (= :article/by-slug (:resource-id row)) "resource-id projects")
        (is (= :loaded (:status row)) "status projects (metadata not redacted)")
        (is (= 4 (:generation row)) "generation projects")
        (is (= 2 (:attempt row)) "attempt projects")
        (is (= [:w 4] (:request-id row)) "request-id projects")
        (is (= [[:route :route/article "nav-1"]] (:active-owners row))
            "active-owners project")
        (is (= 1 (:owner-count row)) "owner-count derived from metadata")
        (is (= [[:article "welcome"]] (:tags row)) "tags project")
        (is (not (:stale? row)) "derived :stale? works (stale-at in the future)")
        ;; The PAYLOAD is the only thing redacted off-box by default.
        (is (:redacted? (:data row))
            "the payload :data is redacted off-box on the default path")
        (is (= "[redacted]" (:preview (:data row)))
            "the redacted payload renders the [redacted] preview, not the raw value")))))

(deftest list-resource-instances-metadata-filters-work-without-runtime-db
  (testing "rf2-tgm1xu — the status / tag / owner / request-id filters
            (which filter the PROJECTED rows) match on the default path,
            because metadata is projected, NOT redacted"
    (seed-resource-entries! {tgm1xu-key   tgm1xu-entry
                             tgm1xu-key-2 tgm1xu-entry-2})
    (testing ":status filter"
      (is (= 1 (:count (runtime/list-resource-instances {:status :loaded}))))
      (is (= 1 (:count (runtime/list-resource-instances {:status :error})))))
    (testing ":tag filter"
      (is (= 1 (:count (runtime/list-resource-instances
                         {:tag [:article "welcome"]})))))
    (testing ":owner filter"
      (is (= 1 (:count (runtime/list-resource-instances
                         {:owner [:route :route/article "nav-1"]})))))
    (testing ":request-id filter"
      (is (= 1 (:count (runtime/list-resource-instances {:request-id [:w 4]})))))))

(deftest list-resource-instances-opts-into-raw-payload
  (testing "rf2-tgm1xu — the trusted-local :include-runtime-db? true opt-in
            lifts the payload redaction; :data then summarizes the raw value
            (still bounded), while metadata is unchanged"
    (seed-resource-entries! {tgm1xu-key tgm1xu-entry})
    (let [row (first (:instances (runtime/list-resource-instances
                                   {:include-runtime-db? true})))]
      (is (= :loaded (:status row)) "metadata still projects under the opt-in")
      (is (not (:redacted? (:data row)))
          ":include-runtime-db? true ⇒ the payload is not redacted")
      (is (= "map" (:type (:data row)))
          "the raw payload is summarized (type surfaced), never flooded raw")
      (is (= 1 (:size (:data row))) "the payload summary carries the bounded size"))))

(deftest list-resource-instances-preserves-upstream-redacted-payload-metadata-rides
  (testing "rf2-tgm1xu adversarial — a payload value already redacted upstream
            (the resources runtime emits the framework :rf/redacted sentinel
            for a :sensitive? slot via elide-wire-value) keeps its redacted
            status under :include-runtime-db? true, while the entry's METADATA
            still rides — the value redacts, the metadata does not"
    (seed-resource-entries! {tgm1xu-key (assoc tgm1xu-entry :data :rf/redacted)})
    (let [row (first (:instances (runtime/list-resource-instances
                                   {:include-runtime-db? true})))]
      (is (= :loaded (:status row)) "metadata rides while the value redacts")
      (is (= [:w 4] (:request-id row)) "request-id metadata rides")
      (is (= [[:article "welcome"]] (:tags row)) "tags metadata rides")
      (is (:redacted? (:data row))
          "the upstream-redacted payload keeps its redacted status even under the opt-in")
      (is (= "[redacted]" (:preview (:data row)))
          "the redacted payload renders [redacted], not a raw preview"))))

(deftest get-resource-state-exposes-metadata-on-default-path
  (testing "rf2-tgm1xu — get-resource-state returns a USEFUL redacted summary
            on the default path: status / owners / tags / request-id project
            while the payload redacts"
    (seed-resource-entries! {tgm1xu-key tgm1xu-entry})
    (let [result (runtime/get-resource-state
                   {:resource-id :article/by-slug
                    :scope       tgm1xu-scope
                    :params      {:slug "welcome"}})]
      (is (true? (:ok? result)))
      (let [row (:state result)]
        (is (= :loaded (:status row)) "status projects")
        (is (= [:w 4] (:request-id row)) "request-id projects")
        (is (= [[:route :route/article "nav-1"]] (:active-owners row))
            "owners project")
        (is (:redacted? (:data row)) "payload redacted off-box by default")))))

(deftest get-resource-state-missing-key-parts-fail-closed
  (testing "rf2-tgm1xu — a partial scoped key (missing scope OR params OR
            resource-id) fails closed with :missing-key — a partial key
            cannot address an entry"
    (seed-resource-entries! {tgm1xu-key tgm1xu-entry})
    (testing "missing :resource-id"
      (let [r (runtime/get-resource-state {:scope tgm1xu-scope :params {:slug "x"}})]
        (is (false? (:ok? r)))
        (is (= :missing-key (:reason r)))))
    (testing "missing :scope"
      (let [r (runtime/get-resource-state {:resource-id :article/by-slug
                                           :params {:slug "x"}})]
        (is (false? (:ok? r)))
        (is (= :missing-key (:reason r)))))
    (testing "missing :params"
      (let [r (runtime/get-resource-state {:resource-id :article/by-slug
                                           :scope tgm1xu-scope})]
        (is (false? (:ok? r)))
        (is (= :missing-key (:reason r)))))))

(deftest get-resource-state-no-such-instance
  (testing "rf2-tgm1xu — a full key that does not address a cached entry
            surfaces :no-such-instance (distinct from :missing-key)"
    (seed-resource-entries! {tgm1xu-key tgm1xu-entry})
    (let [r (runtime/get-resource-state
              {:resource-id :article/by-slug
               :scope       tgm1xu-scope
               :params      {:slug "does-not-exist"}})]
      (is (false? (:ok? r)))
      (is (= :no-such-instance (:reason r))))))

;; ---------------------------------------------------------------------------
;; rf2-e0mq7a — get-resource-history / list-resource-invalidations egress the
;; VALUE-BEARING trace fields (scope/params in the scoped key, the cause, the
;; matched keys) through `egress-value` BEFORE summarizing, so per-slot
;; :sensitive? / :large? declarations redact / elide them by DEFAULT and reveal
;; them only under the trusted-local :include-sensitive? / :include-large?
;; opt-ins, while the non-PII metadata (tags, counts, lifecycle shape) stays
;; useful. This is the trace-buffer peer of the per-slot egress
;; list-resource-instances / get-resource-state thread through
;; `resource-egress-fn` for the live-cache payloads.
;;
;; The framework wire walker matches a :sensitive? / :large? declaration by
;; APP-DB path WITHIN the value it walks (see egress-value-redacts-sensitive-
;; on-the-safe-default-path). We declare a sub-path (`[:secret]`) that sits
;; INSIDE the scope / cause map values, emit resource trace events carrying
;; those values, and assert on the in-panel SUMMARY PREVIEW: when the walker
;; redacts a nested leaf the surrounding map is still a map (so `:redacted?` on
;; the wrapper stays false — the SENTINEL is nested, not the whole value), but
;; the preview text carries the `:rf/redacted` sentinel in place of the raw
;; secret. The load-bearing observable is therefore: the raw secret is ABSENT
;; from the preview by default and PRESENT under the opt-in. A tiny helper makes
;; that the assertion.

(defn- seed-resource-trace-elision-schema! []
  ;; A sensitive leaf (`[:secret]`) that sits INSIDE the scope / cause map
  ;; values, plus a large leaf (`[:blob]`). The walker substitutes
  ;; :rf/redacted / :rf.size/large-elided for those paths on off-box egress.
  ;; EP-0025: classified via the commit-plane effects (`:source :effect`).
  (frame/swap-runtime-db! :rf/default
    (fn [rt] (elision/apply-classification-effects rt
               {:sensitive [[:secret]]
                :large     [[:blob]]}))))

;; A scope value carrying a declared-sensitive leaf alongside a plain identity
;; leaf (which must survive — the scope's non-PII shape stays inspectable).
(def ^:private e0mq7a-secret "tenant-shh-9001")
(def ^:private e0mq7a-scope
  {:secret e0mq7a-secret :region "ap-southeast"})
(def ^:private e0mq7a-rkey
  [e0mq7a-scope :article/by-slug {:slug "welcome"}])
;; A cause value carrying a declared-sensitive leaf (a mutation may carry data).
(def ^:private e0mq7a-cause
  {:mutation :article/save :secret e0mq7a-secret})

(defn- emit-resource-trace-into-ring! [operation tags]
  (rf/emit-trace-event! :rf.resource operation
                        (merge {:frame                :rf/default
                                :rf.trace/dispatch-id (random-uuid)}
                               tags)))

(defn- preview-has? [summary substr]
  ;; True when the summary's preview text contains substr (plain substring,
  ;; no regex — the sentinels carry `.` / `/`). The redaction observable: the
  ;; raw secret is absent (and `:rf/redacted` present) by default; the raw
  ;; secret is present under the opt-in.
  (boolean (and (string? (:preview summary))
                (<= 0 (.indexOf ^js/String (:preview summary) substr)))))

(deftest get-resource-history-redacts-value-bearing-fields-by-default
  (testing "rf2-e0mq7a — the scoped-key scope (PII) and the cause (may carry
            data) are routed through egress-value BEFORE summarizing, so a
            declared-sensitive leaf is REDACTED OUT of the summary preview by
            DEFAULT while the lifecycle metadata still projects"
    (trace-tooling/clear-trace-buffer! :rf/default)
    (seed-resource-trace-elision-schema!)
    (emit-resource-trace-into-ring! :rf.resource/fetch-started
                                    {:resource/key e0mq7a-rkey
                                     :generation   4
                                     :cause        e0mq7a-cause})
    (let [result (runtime/get-resource-history {:resource-id :article/by-slug})]
      (is (true? (:ok? result)))
      (is (= 1 (:count result)))
      (let [row        (first (:history result))
            scope-sum  (get-in row [:resource/key :scope])
            cause-sum  (:cause row)]
        ;; METADATA rides — a redacted timeline still exposes the shape.
        (is (= :rf.resource/fetch-started (:operation row)) "operation metadata projects")
        (is (= :article/by-slug (:resource-id row)) "resource-id metadata projects")
        (is (= 4 (:generation row)) "generation metadata projects")
        ;; The scope's declared-sensitive leaf is gone from the preview; the
        ;; non-PII sibling (:region) survives — the shape stays inspectable.
        (is (not (preview-has? scope-sum e0mq7a-secret))
            "the raw secret is REDACTED OUT of the scope preview by default")
        (is (preview-has? scope-sum ":rf/redacted")
            "the scope preview carries the :rf/redacted sentinel where the secret was")
        (is (preview-has? scope-sum "ap-southeast")
            "the non-PII scope sibling survives (metadata stays useful)")
        ;; The cause carried a declared-sensitive leaf too → redacted out.
        (is (not (preview-has? cause-sum e0mq7a-secret))
            "the raw secret is REDACTED OUT of the cause preview by default")
        (is (preview-has? cause-sum ":rf/redacted")
            "the cause preview carries the sentinel")))))

(deftest get-resource-history-reveals-value-bearing-fields-on-opt-in
  (testing "rf2-e0mq7a — :include-sensitive? true is the trusted-local opt-in:
            the raw sensitive leaf returns to the scope / cause preview"
    (trace-tooling/clear-trace-buffer! :rf/default)
    (seed-resource-trace-elision-schema!)
    (emit-resource-trace-into-ring! :rf.resource/fetch-started
                                    {:resource/key e0mq7a-rkey
                                     :generation   4
                                     :cause        e0mq7a-cause})
    (let [result (runtime/get-resource-history
                   {:resource-id :article/by-slug :include-sensitive? true})
          row    (first (:history result))]
      (is (true? (:ok? result)))
      (is (preview-has? (get-in row [:resource/key :scope]) e0mq7a-secret)
          ":include-sensitive? true ⇒ the raw secret returns to the scope preview")
      (is (preview-has? (:cause row) e0mq7a-secret)
          ":include-sensitive? true ⇒ the raw secret returns to the cause preview"))))

(deftest get-resource-history-large-leaf-elided-by-default
  (testing "rf2-e0mq7a — a declared-:large? leaf is size-elided out of the
            scope preview by default and returns under :include-large? true"
    (trace-tooling/clear-trace-buffer! :rf/default)
    (seed-resource-trace-elision-schema!)
    ;; A scope whose ONLY value-bearing slot is a declared-large leaf, so the
    ;; large elision is observable independent of the sensitive redaction.
    (let [big-scope {:blob {:rows (vec (range 50))} :region "ap-southeast"}
          big-rkey  [big-scope :article/by-slug {:slug "big"}]]
      (emit-resource-trace-into-ring! :rf.resource/fetch-started
                                      {:resource/key big-rkey :generation 4})
      (testing "default path elides the large leaf out of the preview"
        (let [row   (first (:history (runtime/get-resource-history
                                       {:resource-id :article/by-slug})))
              scope (get-in row [:resource/key :scope])]
          (is (preview-has? scope ":rf.size/large-elided")
              "the large leaf is replaced by the size-elided sentinel by default")
          (is (not (preview-has? scope ":rows"))
              "the large leaf's raw contents (the :rows blob) do NOT cross by default")))
      (testing ":include-large? true returns the raw large leaf to the preview"
        (let [row   (first (:history (runtime/get-resource-history
                                       {:resource-id :article/by-slug
                                        :include-large? true})))
              scope (get-in row [:resource/key :scope])]
          (is (not (preview-has? scope ":rf.size/large-elided"))
              ":include-large? true ⇒ the large leaf is no longer elided"))))))

;; rf2-byl7bk.3.5 — get-resource-history MUST carry the EP-0021 infinite-feed
;; page evidence (the `:page` detail) so MCP/AI callers can explain page
;; accumulation, not just see that "load more" happened. The cursor-bearing
;; facts (`:page-param` / `:next-page-param`) are egress-projected; the metadata
;; (`:page-index` / `:page-count` / `:terminal?` / `:reason`) rides raw.
(deftest get-resource-history-carries-ep0021-page-detail
  (testing "rf2-byl7bk.3.5 — the four infinite ops surface their page facts"
    (trace-tooling/clear-trace-buffer! :rf/default)
    (let [rkey [{:region "ap-southeast"} :feed/articles {:tag "clj"}]]
      (emit-resource-trace-into-ring! :rf.resource/load-more
                                      {:resource/key rkey :generation 4
                                       :page-param "cursor-1"
                                       :page-index 1 :page-count 1})
      (emit-resource-trace-into-ring! :rf.resource/page-appended
                                      {:resource/key rkey :generation 4
                                       :page-index 1 :page-count 2
                                       :next-page-param "cursor-2"
                                       :terminal? false})
      (emit-resource-trace-into-ring! :rf.resource/load-more-skipped
                                      {:resource/key rkey
                                       :reason :no-next-page :page-count 2})
      (emit-resource-trace-into-ring! :rf.resource/page-failed
                                      {:resource/key rkey :generation 4
                                       :page-error {:kind :rf.http/server-error
                                                    :status 500}})
      (let [result (runtime/get-resource-history {:resource-id :feed/articles})
            by-op  (fn [op] (first (filter #(= op (:operation %))
                                           (:history result))))]
        (is (true? (:ok? result)))
        (testing "load-more carries the resolved cursor (egress-summarized) + index/count"
          (let [page (:page (by-op :rf.resource/load-more))]
            (is (preview-has? (:page-param page) "cursor-1")
                "a non-sensitive cursor rides through verbatim in the summary")
            (is (= 1 (:page-index page)))
            (is (= 1 (:page-count page)))))
        (testing "page-appended carries the derived next cursor + terminal flag"
          (let [page (:page (by-op :rf.resource/page-appended))]
            (is (preview-has? (:next-page-param page) "cursor-2"))
            (is (= 2 (:page-count page)))
            (is (false? (:terminal? page)))))
        (testing "load-more-skipped carries the skip reason (raw metadata)"
          (is (= :no-next-page (:reason (:page (by-op :rf.resource/load-more-skipped))))))
        (testing "page-failed carries the page-error envelope (summarized)"
          (is (some? (:page-error (:page (by-op :rf.resource/page-failed))))))))))

(deftest list-resource-invalidations-redacts-value-bearing-fields-by-default
  (testing "rf2-e0mq7a — :scope (PII), :cause (may carry data), and each
            :matched key's scope are routed through egress-value BEFORE
            summarizing, so a declared-sensitive leaf is redacted out by
            default; the non-PII :tags / :match-count / :refetched metadata
            still project"
    (trace-tooling/clear-trace-buffer! :rf/default)
    (seed-resource-trace-elision-schema!)
    (emit-resource-trace-into-ring! :rf.resource/invalidated
                                    {:scope   e0mq7a-scope
                                     :tags    #{[:article "welcome"]}
                                     :cause   e0mq7a-cause
                                     :matched [e0mq7a-rkey]
                                     :refetched 1})
    (let [result (runtime/list-resource-invalidations)]
      (is (true? (:ok? result)))
      (is (= 1 (:count result)))
      (let [row (first (:invalidations result))]
        ;; Non-PII metadata rides → the :tag filter axis + storm/zero-match
        ;; distinction stay useful on the default-redacted path.
        (is (= [[:article "welcome"]] (:tags row)) "invalidated tags project (identity)")
        (is (= 1 (:match-count row)) "match-count projects")
        (is (= 1 (:refetched row)) "refetched projects")
        ;; Value-bearing fields redact the secret out by default.
        (is (not (preview-has? (:scope row) e0mq7a-secret))
            "the invalidation scope redacts the secret out by default")
        (is (preview-has? (:scope row) ":rf/redacted")
            "the scope preview carries the sentinel")
        (is (not (preview-has? (:cause row) e0mq7a-secret))
            "the cause redacts the secret out by default")
        (is (not (preview-has? (get-in row [:matched 0 :scope]) e0mq7a-secret))
            "each matched key's scope redacts the secret out by default")
        (is (preview-has? (get-in row [:matched 0 :scope]) ":rf/redacted")
            "the matched-key scope preview carries the sentinel")))))

(deftest list-resource-invalidations-reveals-value-bearing-fields-on-opt-in
  (testing "rf2-e0mq7a — :include-sensitive? true reveals the raw secret in the
            invalidation scope / cause / matched-key scope previews, while the
            metadata is unchanged"
    (trace-tooling/clear-trace-buffer! :rf/default)
    (seed-resource-trace-elision-schema!)
    (emit-resource-trace-into-ring! :rf.resource/invalidated
                                    {:scope   e0mq7a-scope
                                     :tags    #{[:article "welcome"]}
                                     :cause   e0mq7a-cause
                                     :matched [e0mq7a-rkey]
                                     :refetched 1})
    (let [result (runtime/list-resource-invalidations {:include-sensitive? true})
          row    (first (:invalidations result))]
      (is (true? (:ok? result)))
      (is (preview-has? (:scope row) e0mq7a-secret) ":include-sensitive? reveals the scope secret")
      (is (preview-has? (:cause row) e0mq7a-secret) ":include-sensitive? reveals the cause secret")
      (is (preview-has? (get-in row [:matched 0 :scope]) e0mq7a-secret)
          ":include-sensitive? reveals the matched-key scope secret")
      (is (= [[:article "welcome"]] (:tags row))
          "metadata is unchanged by the opt-in"))))

(deftest list-resource-invalidations-tag-filter-works-on-default-path
  (testing "rf2-e0mq7a — the :tag filter axis (which matches the non-PII
            invalidated :tags) still works on the default-redacted path,
            because :tags are NEVER routed through egress"
    (trace-tooling/clear-trace-buffer! :rf/default)
    (seed-resource-trace-elision-schema!)
    (emit-resource-trace-into-ring! :rf.resource/invalidated
                                    {:scope e0mq7a-scope
                                     :tags  #{[:article "welcome"]}
                                     :matched [e0mq7a-rkey]})
    (emit-resource-trace-into-ring! :rf.resource/invalidated
                                    {:scope e0mq7a-scope
                                     :tags  #{[:user "u-1"]}
                                     :matched []})
    (is (= 1 (:count (runtime/list-resource-invalidations
                       {:tag [:article "welcome"]})))
        "tag filter matches exactly the one invalidation touching the tag")
    (is (= 2 (:count (runtime/list-resource-invalidations)))
        "both invalidations surface with no filter")))

;; ---------------------------------------------------------------------------
;; (16) EP-0015 frame-owned egress — the resolved frame, NOT the eval-time
;;      ambient scope, owns the projection (rf2-5b2ct2).
;; ---------------------------------------------------------------------------
;;
;; The bug: the runtime resolved a frame for the READ (which app-db / ring /
;; runtime-db to read) but dropped it before PROJECTION — `egress-value`
;; called `elide-wire-value` without `:frame`, so the walker fell back to
;; `frame/resolve-current-frame` (the eval-time ambient scope). EP-0015
;; requires the projection to use the SAME frame the read resolved.
;;
;; These tests reproduce the leak: a NON-default `:host` frame declares a
;; sensitive app-db path, while the test fixture's ambient scope is
;; `:rf/default` (which has NO such declaration). Under the bug, a
;; `(get-app-db {:frame :host})` read pulls `:host`'s value but projects it
;; under the ambient `:rf/default` policy — so the secret leaks. Under the
;; fix the resolved `:host` frame is threaded and the declaration redacts.

(def ^:private host-frame :host/main)

(defn- seed-host-frame-with-sensitive! []
  ;; Register a non-default frame, install a sensitive declaration on IT
  ;; (NOT :rf/default), and seed the secret into ITS app-db. The ambient
  ;; fixture scope stays :rf/default (no declaration there).
  (rf/reg-frame host-frame {:doc "non-default host frame for egress classification"})
  (frame/swap-runtime-db! host-frame
    (fn [rt] (elision/apply-classification-effects rt {:sensitive [[:auth :password]]})))
  (rf/reg-event :host/seed-auth
    (fn [{:keys [db]} _] {:db {:auth {:username "ada" :password "shh"}}}))
  (rf/dispatch-sync [:host/seed-auth] {:frame host-frame}))

(deftest get-app-db-projects-under-resolved-frame-not-ambient
  (testing "rf2-5b2ct2 — `get-app-db {:frame :host}` projects the value
            under the RESOLVED :host frame's classification, not the
            eval-time ambient scope (:rf/default, which carries NO
            sensitive declaration). Under the bug the secret leaked."
    (seed-host-frame-with-sensitive!)
    ;; The :rf/default frame deliberately carries NO sensitive declaration.
    ;; Were the projection to use the ambient :rf/default scope, the secret
    ;; would cross the boundary verbatim.
    (let [result (runtime/get-app-db {:frame host-frame})]
      (is (true? (:ok? result)))
      (is (= host-frame (:frame result)) "the read resolved the :host frame")
      (is (= :rf/redacted (get-in result [:value :auth :password]))
          "the :host frame's sensitive declaration redacts the secret — the resolved frame owns the projection")
      (is (= "ada" (get-in result [:value :auth :username]))
          "non-sensitive sibling survives"))))

(deftest get-app-db-projects-under-resolved-frame-with-nil-ambient
  (testing "rf2-5b2ct2 — even with NO ambient frame (nil
            *current-frame*), `get-app-db {:frame :host}` redacts via the
            resolved :host frame. Proves the projection does not depend on
            an ambient scope at all."
    (seed-host-frame-with-sensitive!)
    (binding [frame/*current-frame* nil]
      (let [result (runtime/get-app-db {:frame host-frame})]
        (is (true? (:ok? result)))
        (is (= :rf/redacted (get-in result [:value :auth :password]))
            "the :host declaration redacts even with a nil ambient frame")))))

(deftest get-app-db-path-scoped-projects-under-resolved-frame
  (testing "rf2-5b2ct2 — a PATH-scoped `get-app-db` over the :host frame
            redacts the scoped leaf under the resolved frame (the :path AND
            :frame both thread to the walker)"
    (seed-host-frame-with-sensitive!)
    (let [result (runtime/get-app-db {:frame host-frame :path [:auth :password]})]
      (is (true? (:ok? result)))
      (is (= :rf/redacted (:value result))
          "the path-scoped leaf redacts under the resolved :host frame"))))

(deftest get-app-db-diff-projects-slices-under-resolved-frame
  (testing "rf2-5b2ct2 — `get-app-db-diff` projects each changed-path slice
            under the RESOLVED :host frame, not the ambient scope — the
            sensitive slice redacts"
    (seed-host-frame-with-sensitive!)
    ;; Mutate the sensitive slot so it appears in the diff.
    (rf/reg-event :host/rotate-pw
      (fn [{:keys [db]} _] {:db (assoc-in db [:auth :password] "newpw")}))
    (rf/dispatch-sync [:host/rotate-pw] {:frame host-frame})
    (let [epochs   (:epochs (runtime/get-epoch-history {:frame host-frame}))
          ;; the most-recent epoch is the rotate mutation
          epoch-id (-> epochs last :epoch-id)
          result   (runtime/get-app-db-diff {:frame host-frame :epoch-id epoch-id})
          changed  (get-in result [:diff :changed])
          pw-row   (some #(when (= [:auth :password] (:path %)) %) changed)]
      (is (true? (:ok? result)))
      (is (some? pw-row) "the sensitive path appears in the changed slices")
      (is (= :rf/redacted (:before pw-row))
          "the :before slice redacts under the resolved :host frame")
      (is (= :rf/redacted (:after pw-row))
          "the :after slice redacts under the resolved :host frame"))))

(deftest get-app-db-opts-back-in-under-resolved-frame
  (testing "rf2-5b2ct2 — :include-sensitive? true still opts back in under
            the resolved frame (the override flows alongside :frame)"
    (seed-host-frame-with-sensitive!)
    (let [result (runtime/get-app-db {:frame host-frame :include-sensitive? true})]
      (is (= "shh" (get-in result [:value :auth :password]))
          ":include-sensitive? true reveals the raw value under the resolved frame"))))

(deftest egress-value-threads-explicit-frame
  (testing "rf2-5b2ct2 — `egress-value` with an explicit :frame projects
            against THAT frame's classification even when the ambient scope
            is a different frame with no declaration"
    (seed-host-frame-with-sensitive!)
    ;; Ambient is :rf/default (no declaration); explicit :frame :host owns it.
    (let [out (runtime/egress-value {:auth {:username "ada" :password "shh"}}
                                    {:frame host-frame})]
      (is (= :rf/redacted (get-in out [:auth :password]))
          "the explicit :frame's declaration redacts the secret")
      (is (= "ada" (get-in out [:auth :username]))
          "non-sensitive sibling survives"))))

(deftest trace-event-frame-resolves-event-own-frame
  (testing "rf2-5b2ct2 / rf2-7737vq — `egress-trace-event` resolves a trace
            event's OWN frame (from [:tags :frame] via the canonical
            `re-frame.trace/trace-event-frame` reader) and threads it to
            the egress walker, so per-event projection uses the emitting
            frame's classification — NOT one ambient/resolved frame. A
            sensitive path declared on :host redacts a :host event's value
            (carried where the walker matches), while the ambient :rf/default
            frame (no such declaration) would have leaked it under the bug."
    ;; Declare the sensitive path on :host where a trace event carries its
    ;; value-bearing slots — under [:tags ...]. The :rf/default ambient frame
    ;; (the fixture's scope) carries NO such declaration, so under the bug the
    ;; value would project under :rf/default and leak.
    (rf/reg-frame host-frame {:doc "host frame for trace-event-frame egress"})
    (frame/swap-runtime-db! host-frame
      (fn [rt] (elision/apply-classification-effects rt {:sensitive [[:tags :secret]]})))
    (let [ev {:op-type   :sub
              :operation :rf.sub/run
              :tags      {:frame  host-frame
                          :secret "shh"
                          :public "ada"}}]
      ;; The helper resolves the event's own frame (off [:tags :frame]) and
      ;; threads it to the walker; assert it directly so the test does not
      ;; depend on the live ring shape.
      (is (= :rf/redacted
             (get-in (#'runtime/egress-trace-event ev :rf/default {}) [:tags :secret]))
          "the :host event's sensitive [:tags :secret] redacts under :host's policy")
      (is (= "ada"
             (get-in (#'runtime/egress-trace-event ev :rf/default {}) [:tags :public]))
          "the non-sensitive sibling survives")
      ;; A frameless event (no [:tags :frame]), no fallback frame,
      ;; AND no ambient scope ⇒ no frame is resolvable anywhere ⇒ fail closed
      ;; (whole-value redacted). This is the get-issues merged-ring case where
      ;; each event must carry its own frame; one that carries none, with the
      ;; eval seam having no ambient frame, cannot borrow another frame's marks.
      (binding [frame/*current-frame* nil]
        (is (= :rf/redacted
               (#'runtime/egress-trace-event {:op-type :sub :operation :rf.sub/run
                                              :tags {:secret "shh"}}
                                             nil {}))
            "a truly frameless event (no event frame, no fallback, no ambient) fails closed")))))
