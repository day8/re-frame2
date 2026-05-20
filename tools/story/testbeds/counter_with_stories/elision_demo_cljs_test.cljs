(ns counter-with-stories.elision-demo-cljs-test
  "Integration tests for the privacy + size elision demo. Asserts each
  branch of the elision arc actually fires:

  1. `:auth/sign-in` carries `:sensitive? true` in its registration
     metadata — `rf/handler-meta` returns the flag for trace-surface
     consumers (Causa, error-monitor forwarders) to filter on.
  2. The handler runs cleanly under `dispatch-sync`; the password
     does NOT leak into app-db.
  3. The schema-driven `:large?` branch — `rf/elide-wire-value`
     substitutes the `:user/avatar-pdf` slot with a
     `:rf.size/large-elided` marker carrying byte-count + path +
     `:hint` from the schema.
  4. The runtime auto-detect branch — dispatching an event with a
     ≥ 16 kB inline string in the payload causes the event-emit
     listener to receive the elided marker instead of the raw blob.
  5. The always-on event-emit substrate fires once per processed
     event under the dev runtime.

  Runs under the top-level `node-test` build alongside the existing
  `stories_cljs_test.cljs`."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [re-frame.core         :as rf]
            [re-frame.event-emit   :as event-emit]
            [re-frame.frame        :as frame]
            [re-frame.late-bind    :as late-bind]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            ;; Loading the demo ns fires its registrations against the
            ;; registrar — the tests then exercise the registered
            ;; handlers + the listener install/uninstall surface.
            [counter-with-stories.elision-demo]))

;; ---- fixtures -------------------------------------------------------------

(def ^:private registrar-snapshot (atom nil))

(defn- before! []
  (reset! registrar-snapshot (test-support/snapshot-registrar))
  ;; Causa's preload-time trace-cb registers its bookkeeping
  ;; handlers with `:rf.trace/no-emit? true`, so the framework
  ;; short-circuits emission for them and the collector never
  ;; re-enters itself. The previous workaround that wiped the
  ;; trace-cb registry per fixture is obsolete — Causa's cb runs
  ;; as production preload wires it.
  (reset! frame/frames {})
  ;; Clear cross-namespace schemas from the per-frame registry
  ;; before re-registering this demo's
  ;; declarations. Sibling test namespaces (auth-flow story tests,
  ;; nine-states.core, Conduit) register schemas against :rf/default
  ;; at ns-load; the post-commit validation rollback would otherwise
  ;; fire on every dispatch in this file against those unrelated
  ;; schemas.
  (when-let [clear! (late-bind/get-fn :schemas/clear-by-frame!)]
    (clear!))
  (try (rf/init! plain-atom/adapter) (catch :default _ nil))
  (frame/ensure-default-frame!)
  (event-emit/clear-event-listeners!)
  ;; Re-register this demo's app-db schema. The demo namespace's
  ;; ns-load `reg-app-schema` call ran once at module load; the
  ;; preceding `(clear!)` step wiped it. Re-stamping it here keeps
  ;; the schema-driven elision branch (test 3) working.
  (rf/reg-app-schema [:user/avatar-pdf]
                     [:maybe [:string {:large? true
                                       :hint   "Avatar PDF blob"}]])
  ;; Re-run the schema-driven elision-registry boot population
  ;; against the post-init frame. The demo ns's `reg-app-schema`
  ;; call lives in registry-meta land; the per-frame `[:rf/elision
  ;; :declarations]` slot is written by this fn from the schemas
  ;; artefact's walker. Without this, `elide-wire-value` finds no
  ;; declared-large entry and the assertion that the marker fires
  ;; on the schema-flagged slot fails.
  (rf/populate-elision-from-schemas! :rf/default))

(defn- after! []
  (when-let [snap @registrar-snapshot]
    (test-support/restore-registrar! snap)
    (reset! registrar-snapshot nil))
  (reset! frame/frames {})
  (event-emit/clear-event-listeners!))

(use-fixtures :each {:before before! :after after!})

;; ---- 1. :sensitive? registration metadata is queryable -------------------

(deftest auth-sign-in-carries-sensitive-flag
  (testing "The :auth/sign-in handler registered by elision-demo carries
            `:sensitive? true` in its registry-meta — Causa / re-frame2-pair /
            error-monitor forwarders read this off `(rf/handler-meta
            :event :auth/sign-in)` and apply tool-side policy."
    (let [m (rf/handler-meta :event :auth/sign-in)]
      (is (some? m) ":auth/sign-in registration meta is populated")
      (is (true? (:sensitive? m))
          "the registrar copied :sensitive? true from the metadata map"))))

;; ---- 2. handler doesn't leak the password into app-db --------------------

(deftest sign-in-handler-doesnt-leak-password-to-app-db
  (testing "Dispatching :auth/sign-in runs the handler and stashes only
            non-sensitive feedback — `:auth/last-sign-in` carries
            `:email` + `:submitted?`, NEVER `:password`. Passwords
            don't live in app-db; this is the in-handler contract."
    (rf/dispatch-sync [:auth/sign-in
                       {:email "test@example.com"
                        :password "real-password-1234"}])
    (let [feedback (-> (rf/get-frame-db :rf/default)
                       :auth/last-sign-in)]
      (is (= "test@example.com" (:email feedback))
          "the email rode through")
      (is (true? (:submitted? feedback)))
      (is (not (contains? feedback :password))
          "password never reached app-db"))))

;; ---- 3. :large? schema slot drives the wire-walker substitution ----------

(deftest large-schema-slot-becomes-wire-marker
  (testing "The `:user/avatar-pdf` schema slot declares :large? true.
            Running `rf/elide-wire-value` against a snapshot of the
            app-db payload that includes a non-trivial value at the
            slot substitutes the value with a `:rf.size/large-elided`
            marker map carrying byte-count + path + hint. Spec 010
            §`:large?` schema-driven size-elision nomination."
    (rf/dispatch-sync [:user.avatar-pdf/set {:bytes 5000}])
    (let [db     (rf/get-frame-db :rf/default)
          blob   (:user/avatar-pdf db)
          elided (rf/elide-wire-value db {:frame :rf/default})]
      (is (string? blob) "the handler wrote the synthetic blob into app-db")
      (is (>= (count blob) 5000) "the blob is the requested size")
      (let [marker (get-in elided [:user/avatar-pdf :rf.size/large-elided])]
        (is (some? marker)
            ":rf.size/large-elided marker substituted the blob on the wire")
        (is (number? (:bytes marker))
            "marker carries a :bytes count")
        (is (= "Avatar PDF blob" (:hint marker))
            "the schema's :hint propagated verbatim into the marker
             — AI consumers (re-frame2-pair-mcp, Causa) see this without
             drilling into the blob")))))

;; ---- 4. unschema'd inline event payloads are not size-elided -------------

(deftest event-emit-listener-leaves-unschema'd-inline-large-payload
  (testing "Path D removes runtime size auto-elision. Inline event
            payloads without schema `{:large? true}` metadata ride
            through unchanged; authors declare large app-db slots in
            schemas instead."
    (let [seen (atom [])]
      (rf/register-event-listener!
        ::test-recorder
        (fn [record] (swap! seen conj record)))

      ;; A 20 kB string without schema metadata.
      (rf/dispatch-sync [:user.avatar/upload
                         {:blob (str/join (repeat 20000 "B"))}])

      (is (= 1 (count @seen)) "listener fired exactly once")
      (let [r       (first @seen)
            payload (second (:event r))
            slot    (:blob payload)]
        (is (= :user.avatar/upload (:event-id r)))
        (is (= :ok (:outcome r)))
        (is (string? slot)
            ":blob remains raw because no schema declared it large")
        (is (= 20000 (count slot))
            "the listener received the original inline payload")))))

;; ---- 5. event-emit listener fires for every processed event --------------

(deftest event-emit-listener-fires-on-every-dispatch
  (testing "The always-on event-emit substrate fires one record per
            processed event. The handler-meta `:sensitive?` annotation
            has been removed, so substrate-level drop based on handler
            sensitivity is gone — all three dispatches now deliver a
            record. Per-path elision still applies inside the record's
            `:event` slot."
    (let [seen (atom [])]
      (rf/register-event-listener!
        ::test-recorder
        (fn [record] (swap! seen conj record)))
      (rf/dispatch-sync [:auth/sign-in
                         {:email "u@example.com" :password "secret"}])
      (rf/dispatch-sync [:user.avatar-pdf/set {:bytes 1024}])
      (rf/dispatch-sync [:user.avatar-pdf/clear])
      (is (= 3 (count @seen))
          "three dispatches → three records (no handler-meta drop)")
      (is (= [:auth/sign-in :user.avatar-pdf/set :user.avatar-pdf/clear]
             (mapv :event-id @seen))
          "records arrive in dispatch order")
      (is (every? #(= :ok (:outcome %)) @seen)
          "every delivered record settled cleanly"))))
