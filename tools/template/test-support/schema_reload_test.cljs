(ns acme.my-app.schema-reload-test
  "Executable regression for rf2-a0442 — the hot-reload schema seam.

   NOT part of the emitted scaffold. `emitted_test_run_test.clj` copies
   this file into a generated project's `test/` tree, where the emitted
   shadow-cljs `:test` build (`:ns-regexp \"-test$\"`) compiles and runs
   it alongside the scaffold's own `events_test.cljs`. It therefore runs
   against REAL generated source — the emitted `schema.cljs`,
   `events.cljs` and `subs.cljs` — not against a hand-built stand-in. The
   generated project is always `acme/my-app`, so the namespace is fixed.

   ## What it pins

   `reg-app-schema` is frame-local, so the scaffold cannot call it at
   namespace load: it lives inside `schema/register-schema!`. A hot
   reload therefore re-evaluates `schema.cljs`'s `CounterDb` `def` and
   re-registers NOTHING on its own. Before rf2-a0442 the only caller was
   `core/init`, which shadow runs once at bundle load — so an edited
   schema never reached the live frame, and the framework went on
   validating it against the boot-time value until a page refresh.

   `core.cljs`'s `^:dev/after-load` hook now calls `register-schema!`
   again, and `template_emission_test.clj` pins that call (its presence
   AND its position before the render) for all four emitted entry
   points. What THIS file proves is the other half — that the call does
   what the hook needs it to do on a LIVE frame:

     * a reload widens the schema, and a write the boot-time schema
       rejected then commits;
     * a reload tightens it back, and the same write is rejected again
       (the inverse direction rf2-a0442 also names);
     * across both, the frame is never recreated and the counter value
       set before the reload survives it.

   ## What a reload is, mechanically

   Two steps, and this file performs both:

     1. shadow re-evaluates the changed namespace's top-level forms —
        for `schema.cljs` that is the `CounterDb` `def`, modelled here
        by assigning the var.
     2. shadow calls the `^:dev/after-load` hooks — the hook calls
        `schema/register-schema!`, modelled here by calling the very
        same fn.

   The React root and `frame-root` render the hook also re-runs are out
   of scope: they need a DOM, and the retained-root half already has its
   own coverage (rf2-r0kk7 / rf2-w1k3i)."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core                 :as rf]
            [re-frame.schemas              :as schemas]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support         :as ts]
            ;; Requiring these installs the generated registrations.
            [acme.my-app.events]
            [acme.my-app.subs]
            [acme.my-app.schema            :as schema]))

;; The edit an author makes in schema.cljs: one optional string key added
;; to the closed map.
(def WidenedCounterDb
  [:map {:closed true}
   [:counter/value :int]
   [:counter/label {:optional true} :string]])

(use-fixtures :each
  (ts/make-reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn (fn []
                (rf/make-frame {:id :rf/default :preset :test}))}))

;; --- the reload seam -------------------------------------------------------

(defn- save-and-reload!
  "Model one `shadow-cljs watch` save of schema.cljs: re-evaluate the
  `CounterDb` def, then run what the `^:dev/after-load` hook runs. No
  frame is created, destroyed or re-seeded — exactly as in the browser,
  where the hook renders into the retained root and `frame-root` reuses
  the live frame."
  [schema-value]
  (set! schema/CounterDb schema-value)
  (schema/register-schema!))

(defn- app-db []
  (rf/app-db-value :rf/default))

(defn- write-label!
  "Dispatch an ordinary event that writes `:counter/label`. A write the
  registered schema forbids is rolled back; depending on the frame's
  error policy it may also throw, which is still a rejection — the
  caller asserts on app-db, the observable that matters."
  [label]
  (try
    (rf/dispatch-sync [:counter/set-label label])
    (catch :default _ nil)))

(defn- register-label-event! []
  (rf/reg-event :counter/set-label
    (fn [{:keys [db]} [_ label]]
      {:db (assoc db :counter/label label)})))

;; --- Tests -----------------------------------------------------------------

(deftest reload-replaces-the-live-app-schema
  (testing "re-registering after a schema edit installs the NEW schema on the
            already-live frame, and the pre-reload counter state survives"
    (register-label-event!)
    ;; Boot: the scaffold's own registration, closed CounterDb.
    (schema/register-schema!)
    (rf/dispatch-sync [:counter/initialise])
    (rf/dispatch-sync [:counter/increment])
    (rf/dispatch-sync [:counter/increment])
    (rf/dispatch-sync [:counter/increment])
    (is (= 3 (:counter/value (app-db)))
        "counter carries non-default state into the reload")
    (is (= schema/CounterDb
           (schemas/app-schema-at [] {:frame :rf/default}))
        "the boot-time schema is what validates the live frame")

    ;; NON-VACUITY CONTROL. Before the reload the closed boot schema must
    ;; REJECT the new key. Without this the widened-write assertion below
    ;; would pass even if nothing were ever re-registered.
    (write-label! "before")
    (is (nil? (:counter/label (app-db)))
        "the closed boot-time schema rejects :counter/label — the write is
         rolled back")
    (is (= 3 (:counter/value (app-db)))
        "a rejected write leaves the rest of app-db intact")

    ;; The save.
    (save-and-reload! WidenedCounterDb)
    (is (= WidenedCounterDb
           (schemas/app-schema-at [] {:frame :rf/default}))
        "the reload replaced the registered schema for [] on :rf/default —
         this is the assertion that fails when the ^:dev/after-load hook
         does not call register-schema! (rf2-a0442)")

    ;; The same write now commits, against the same frame and the same
    ;; app-db — no reseed, no frame recreation.
    (write-label! "after")
    (is (= "after" (:counter/label (app-db)))
        "the source-valid write commits once the edited schema is live")
    (is (= 3 (:counter/value (app-db)))
        "the counter value set before the reload is untouched by it")))

(deftest reload-tightening-a-schema-takes-effect-too
  (testing "the inverse direction: re-registering a TIGHTER schema makes a
            write the previous, laxer schema admitted fail — the runtime does
            not clear or rewind the app-db that no longer fits"
    (register-label-event!)
    (save-and-reload! WidenedCounterDb)
    (rf/dispatch-sync [:counter/initialise])
    (rf/dispatch-sync [:counter/increment])
    (write-label! "admitted")
    (is (= "admitted" (:counter/label (app-db)))
        "the widened schema admits :counter/label")

    ;; Tighten: back to the scaffold's own closed CounterDb.
    (save-and-reload! [:map {:closed true} [:counter/value :int]])
    (is (= [:map {:closed true} [:counter/value :int]]
           (schemas/app-schema-at [] {:frame :rf/default}))
        "the tightened schema is live after the reload")
    (is (= "admitted" (:counter/label (app-db)))
        "the live app-db is NOT cleared or rewound by a tightening reload —
         the runtime reports the drift and keeps running")

    (write-label! "rejected")
    (is (= "admitted" (:counter/label (app-db)))
        "a write the tightened schema forbids no longer commits — the stale
         permissive schema is gone (rf2-a0442)")
    (is (= 1 (:counter/value (app-db)))
        "the counter survives the tightening reload")))
