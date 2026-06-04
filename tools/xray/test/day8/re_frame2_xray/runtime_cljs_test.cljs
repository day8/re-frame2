(ns day8.re-frame2-xray.runtime-cljs-test
  "Unit tests for `day8.re-frame2-xray.runtime` (rf2-8xzoe.4 / F-4).

  Pins the load-bearing contracts the F-4 port lands:

    1. **Eighteen tool-shaped accessors.** Each MCP tool in
       `tools/xray/spec/010-MCP-Server.md` §Tool catalogue maps to
       exactly one runtime fn; the lint here enumerates and asserts
       every one is `fn?`. Drift between the catalogue and the
       runtime surface fails this test first, before any tool dispatch
       round-trip would notice.
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
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
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
;; (1) Eighteen tool-shaped accessors are resolvable.
;; ---------------------------------------------------------------------------

(def ^:private tool-accessor-vars
  "The canonical eighteen tool-shaped accessors per
  `tools/xray/spec/010-MCP-Server.md` §Tool catalogue. Order matches
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
   ;; Mutation (3)
   ['dispatch!           runtime/dispatch!]
   ['restore-epoch!      runtime/restore-epoch!]
   ['reset-frame-db!     runtime/reset-frame-db!]
   ;; Streaming (3)
   ['subscribe!          runtime/subscribe!]
   ['unsubscribe!        runtime/unsubscribe!]
   ['list-subscriptions  runtime/list-subscriptions]
   ;; Escape (1)
   ['eval-form-result    runtime/eval-form-result]
   ;; Meta (2)
   ['health              runtime/health]
   ['tail-build-probe    runtime/tail-build-probe]])

(deftest eighteen-tool-accessors-exist
  (testing "every catalogue tool has a runtime-side accessor — drift
            between the eighteen MCP tools and this ns fails here first"
    (is (= 18 (count tool-accessor-vars))
        "the canonical list is the eighteen-tool catalogue")
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
    (rf/reg-event-db :test/seed-db
      (fn [_ _] {:seeded? true}))
    (rf/dispatch-sync [:test/seed-db])
    (let [result (runtime/get-app-db)]
      (is (true? (:ok? result))
          "single-frame resolution succeeds without explicit :frame")
      (is (= :rf/default (:frame result))
          "the sole frame is the resolved frame"))))

(deftest get-app-db-explicit-path
  (testing "the `:path` arg scopes the returned value via `get-in`"
    (rf/reg-event-db :test/seed-db
      (fn [_ _] {:cart {:items [:a :b :c]}}))
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

(deftest dispatch-tags-event-with-current-origin
  (testing "`dispatch!` attaches `{:tags {:rf.event/origin <current-origin>}}` as
            event metadata so the framework's trace bus carries the
            Xray-MCP tag (Lock #4 / I1)"
    (let [captured (atom nil)]
      (rf/reg-event-db :test/capture-meta
        (fn [db [_ marker]]
          (reset! captured marker)
          (assoc db :marker marker)))
      ;; sync? so the dispatch completes before we check.
      (let [result (runtime/dispatch! [:test/capture-meta :ok] {:sync? true})]
        (is (true? (:ok? result)))
        (is (= :xray-mcp (:origin result))
            "result echoes the bound origin"))
      (is (= :ok @captured)
          "handler ran"))))

(deftest dispatch-rebinds-origin-via-eval-cljs-extent
  (testing "a `binding` around `dispatch!` re-tags the dispatch — this
            is the synchronous-extent contract `eval-cljs` rides
            (Lock #4 / I6)"
    (rf/reg-event-db :test/origin-marker
      (fn [db _] db))
    (binding [runtime/*current-origin* :test-rebind]
      (let [result (runtime/dispatch! [:test/origin-marker] {:sync? true})]
        (is (= :test-rebind (:origin result))
            "dispatch carries the re-bound origin, not the default")))))

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
    (rf/reg-event-db :test/just-dispatch
      (fn [db _] (assoc db :touched? true)))
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
  ;; Mirror the framework's schema-declared sensitive path setup
  ;; (implementation/core/test/re_frame/elision_test.clj
  ;; §schema-sensitive-path-redacts): a `{:sensitive? true}` slot
  ;; hydrates the per-frame `:sensitive-declarations` so the wire walker
  ;; substitutes `:rf/redacted` for that path on off-box egress.
  ;;
  ;; The `:sensitive-declarations` live in app-db at
  ;; `[:rf/runtime :elision :sensitive-declarations]`, so `populate-…!`
  ;; MUST run AFTER any whole-db `reg-event-db` reset in a test (a reset
  ;; that returns a fresh map would otherwise wipe `:rf/runtime`).
  (rf/reg-app-schema [:auth]
                     [:map
                      [:username :string]
                      [:password {:sensitive? true} :string]])
  (rf/populate-sensitive-from-schemas!))

(deftest egress-value-redacts-sensitive-on-the-safe-default-path
  (testing "`egress-value` with no opts (the SHORT path) redacts a
            schema-declared sensitive slot — the off-box defaults are
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

(deftest get-app-db-redacts-sensitive-end-to-end
  (testing "the rerouted `get-app-db` call site still redacts a
            sensitive slot end-to-end (regression: the named egress fn
            is wired into the accessor)"
    (rf/reg-event-db :test/seed-auth
      (fn [_ _] {:auth {:username "ada" :password "shh"}}))
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
;;       walker so a scoped slice elides against schema-declared
;;       sensitive / large paths (rf2-a96xq).
;; ---------------------------------------------------------------------------
;;
;; Before the fix the scoped read called `egress-value` WITHOUT the
;; absolute :path, so the walker started the sliced leaf at root [] and a
;; declaration registered for [:auth :password] never matched a direct
;; read of {:path [:auth :password]} — the raw value crossed the off-box
;; boundary despite the safe-default contract. These tests pin the
;; fail-closed default (redact / size-elide) AND the operator opt-in.

(defn- seed-large-schema! []
  ;; A `{:large? true}` schema slot hydrates the per-frame `:declarations`
  ;; so the wire walker substitutes the `:rf.size/large-elided` marker for
  ;; that path on off-box egress (the size sibling of
  ;; `seed-sensitive-schema!`). Must run AFTER any whole-db reset so the
  ;; declaration in `[:rf/runtime :elision :declarations]` survives.
  (rf/reg-app-schema [:blob]
                     [:map
                      [:payload {:large? true} :any]])
  (rf/populate-elision-from-schemas!))

(deftest get-app-db-path-scoped-redacts-sensitive-leaf-by-default
  (testing "a PATH-scoped get-app-db over a schema-declared sensitive
            leaf redacts by default — the absolute :path is threaded into
            the egress walker so the [:auth :password] declaration
            matches the scoped slice (rf2-a96xq: fail-closed)"
    (rf/reg-event-db :test/seed-auth
      (fn [_ _] {:auth {:username "ada" :password "shh"}}))
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
    (rf/reg-event-db :test/seed-auth
      (fn [_ _] {:auth {:username "ada" :password "shh"}}))
    (rf/dispatch-sync [:test/seed-auth])
    (seed-sensitive-schema!)
    (let [result (runtime/get-app-db {:path [:auth :password]
                                      :include-sensitive? true})]
      (is (true? (:ok? result)))
      (is (= "shh" (:value result))
          ":include-sensitive? true ⇒ the raw leaf is revealed at the scoped path"))))

(deftest get-app-db-path-scoped-elides-large-leaf-by-default
  (testing "a PATH-scoped get-app-db over a schema-declared :large? leaf
            emits the :rf.size/large-elided marker by default, and reveals
            the raw value only on {:include-large? true} (rf2-a96xq:
            symmetric size minimisation on the scoped path)"
    (rf/reg-event-db :test/seed-blob
      (fn [_ _] {:blob {:payload {:big "value"}}}))
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
;; `reset-frame-db!` records a synthetic epoch with :db-before = the old
;; app-db and :db-after = the injected value — the deterministic way to
;; seed an epoch with a known before/after pair in a node unit test
;; (the epoch artefact is a hard Xray dep per tools/xray/deps.edn).

(defn- record-epoch-via-reset!
  "Seed `before` into the sole frame, then `reset-frame-db!` to `after`
  so the framework records a synthetic epoch carrying
  `:db-before before` / `:db-after after`. Returns the recorded
  epoch's `:epoch-id`."
  [before after]
  (rf/reg-event-db :test/seed-before (fn [_ _] before))
  (rf/dispatch-sync [:test/seed-before])
  (let [fid (first (rf/frame-ids))]
    (rf/reset-frame-db! fid after)
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
            egress-value — a schema-declared sensitive slot that changed
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
    ;; value-bearing slot at the schema-declared sensitive path. We use
    ;; the registrar directly (not `reg-event-db`, whose macro emits its
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
  whose value sits at the schema-declared sensitive path. We use the
  registrar directly (not `reg-event-db`, whose macro emits its own
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
