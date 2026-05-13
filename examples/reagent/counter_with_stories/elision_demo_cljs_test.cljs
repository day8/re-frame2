(ns counter-with-stories.elision-demo-cljs-test
  "Integration tests for the rf2-vw0to elision demo. Asserts each
  branch of the privacy + size elision arc actually fires:

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
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [re-frame.trace        :as trace]
            ;; Loading the demo ns fires its registrations against the
            ;; registrar — the tests then exercise the registered
            ;; handlers + the listener install/uninstall surface.
            [counter-with-stories.elision-demo]))

;; ---- fixtures -------------------------------------------------------------

(def ^:private registrar-snapshot (atom nil))

(defn- before! []
  (reset! registrar-snapshot (test-support/snapshot-registrar))
  ;; Causa (and any other on-classpath dev tools) register
  ;; trace-cbs at preload time. Those callbacks dispatch their own
  ;; events on every sensitive trace event — which would pollute
  ;; the elision tests' deterministic listener-counts. Clear so
  ;; each test runs against an empty trace-cb registry. The
  ;; per-test isolation is fine; the next suite that needs
  ;; Causa-installed trace-cbs re-installs them via its own
  ;; fixture.
  (trace/clear-trace-cbs!)
  (reset! frame/frames {})
  (try (rf/init! plain-atom/adapter) (catch :default _ nil))
  (frame/ensure-default-frame!)
  (event-emit/clear-event-emit-listeners!)
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
  (event-emit/clear-event-emit-listeners!))

(use-fixtures :each {:before before! :after after!})

;; ---- 1. :sensitive? registration metadata is queryable -------------------

(deftest auth-sign-in-carries-sensitive-flag
  (testing "The :auth/sign-in handler registered by elision-demo carries
            `:sensitive? true` in its registry-meta — Causa / pair2 /
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
             — AI consumers (pair2-mcp, Causa) see this without
             drilling into the blob")))))

;; ---- 4. runtime auto-detect at the event-emit boundary -------------------

(deftest event-emit-listener-elides-inline-large-payload
  (testing "Dispatching an event whose payload carries an inline string
            ≥ 16 kB (the default `:rf.size/threshold-bytes`) causes the
            event-emit substrate's `elide-wire-value` pass to substitute
            the leaf with a `:rf.size/large-elided` marker BEFORE the
            listener receives the record. The runtime auto-detect
            branch of the walker — orthogonal to the schema-driven
            branch — keeps inline blobs off the wire even when no
            schema declared the path. Per Spec 009 §Auto-detect
            threshold + rf2-rirbq."
    (let [seen (atom [])]
      (rf/register-event-emit-listener!
        ::test-recorder
        (fn [record] (swap! seen conj record)))

      ;; A 20 kB string — well over the 16 kB default threshold.
      (rf/dispatch-sync [:user.avatar/upload
                         {:blob (str/join (repeat 20000 "B"))}])

      (is (= 1 (count @seen)) "listener fired exactly once")
      (let [r       (first @seen)
            payload (second (:event r))
            slot    (:blob payload)]
        (is (= :user.avatar/upload (:event-id r)))
        (is (= :ok (:outcome r)))
        ;; The listener received the elided shape: where the original
        ;; payload carried a 20 kB string, the walker's auto-detect
        ;; substituted in the marker.
        (is (map? slot)
            ":blob slot is the marker map, NOT the raw 20 kB string")
        (is (contains? slot :rf.size/large-elided)
            "the marker key is present — wire-walker auto-detect fired")
        (is (number? (get-in slot [:rf.size/large-elided :bytes]))
            "marker carries the original byte count for sizing
             pipelines downstream")))))

;; ---- 5. event-emit listener fires for every processed event --------------

(deftest event-emit-listener-fires-on-every-dispatch
  (testing "The always-on event-emit substrate fires one record per
            processed event. Asserted alongside the elision tests so
            the demo's listener install path is exercised end-to-end
            without relying on the prod-mode runner (the production
            elision pinning lives in
            `re-frame.event-emit-elision-prod-test`)."
    (let [seen (atom [])]
      (rf/register-event-emit-listener!
        ::test-recorder
        (fn [record] (swap! seen conj record)))
      (rf/dispatch-sync [:auth/sign-in
                         {:email "u@example.com" :password "secret"}])
      (rf/dispatch-sync [:user.avatar-pdf/set {:bytes 1024}])
      (rf/dispatch-sync [:user.avatar-pdf/clear])
      (is (= 3 (count @seen)) "three dispatches → three records")
      (is (= [:auth/sign-in :user.avatar-pdf/set :user.avatar-pdf/clear]
             (mapv :event-id @seen))
          "records arrive in dispatch order with the right :event-id slots")
      (is (every? #(= :ok (:outcome %)) @seen)
          "every dispatch settled cleanly"))))
