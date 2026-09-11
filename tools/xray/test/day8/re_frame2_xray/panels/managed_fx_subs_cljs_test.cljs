(ns day8.re-frame2-xray.panels.managed-fx-subs-cljs-test
  "Composite-sub test for `:rf.xray/managed-fx-for-focused-event` +
  the `:rf.xray/focus-event` cross-link event (rf2-uyp86).

  Uses the same test-runtime + seed-buffer pattern as
  `event_detail_cljs_test.cljs` — install Xray's handlers, allocate
  the `:rf/xray` frame, push trace events through the production
  `trace-collector/seed-trace-for-test!` path, then read the composite via
  `subscribe`."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [clojure.string :as string]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            ;; rf2-90kv — the mount that composes the sub with the renderer
            ;; is itself the defect surface, so it is the instrument. rf2-fcy5
            ;; moved that composition into `panels/managed-fx-list-tree`, which
            ;; the row below calls by name, so the alias is now load-bearing at
            ;; runtime rather than only forcing the ns to load.
            [day8.re-frame2-xray.panels :as panels]
            [day8.re-frame2-xray.config :as config]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-support :as xray-test-support]
            [day8.re-frame2-xray.trace-collector :as trace-collector]
            ;; rf2-s6m6 — the disclosure rows below close the loop between the
            ;; state this ns owns and the renderer that consumes it, so the
            ;; template and its pure helpers are the instruments.
            [day8.re-frame2-xray.panels.managed-fx-helpers :as h]
            [day8.re-frame2-xray.panels.managed-fx-template :as template]))

;; ---- fixtures -----------------------------------------------------------

(use-fixtures :each
  ;; `make-xray-runtime-fixture` (rf2-vj80u8) folds the reset (plain-atom +
  ;; `:all` tier, which already covers the trace-collector rings the old init
  ;; reset a SECOND time) into one owner; `:post-reset` pins the egress
  ;; profile + clears the suppressed-count. (`trace-collector` stays required
  ;; for the `seed-trace-for-test!` seeding below.)
  (xray-test-support/make-xray-runtime-fixture
    {:post-reset (fn []
                   (config/set-egress-profile! config/default-egress-profile)
                   (config/reset-suppressed-count!))}))

(defn- seed-buffer! [evs]
  (registry/register-xray-handlers!)
  (rf/make-frame {:id :rf/xray})
  (doseq [ev evs]
    (trace-collector/seed-trace-for-test! ev)))

;; ---- trace fixture ------------------------------------------------------

(defn- cascade-evs-http
  "One cascade containing a single `:rf.http/managed` invocation. The
  fx args carry the standard Spec 014 shape so the helper can extract
  request / handler / correlation-id."
  [dispatch-id id-base]
  [{:id (+ id-base 1) :op-type :rf.event    :operation :rf.event/dispatched
    :tags {:rf.trace/dispatch-id dispatch-id :rf.event/v [:user/load]}}
   {:id (+ id-base 2) :op-type :rf.fx    :operation :rf.fx/do-fx
    :tags {:rf.trace/dispatch-id dispatch-id}}
   {:id (+ id-base 3) :op-type :rf.fx       :operation :rf.fx/handled
    :tags {:rf.trace/dispatch-id dispatch-id
           :rf.fx/id :rf.http/managed
           :rf.fx/args {:request {:method :get :url "/api/users/42"}
                     :request-id :req-abc
                     :on-success [:user/loaded]}}}])

(defn- cascade-evs-non-managed
  "A cascade with only `:db` / `:dispatch` fxs — should produce zero
  managed-fx records."
  [dispatch-id id-base]
  [{:id (+ id-base 1) :op-type :rf.event :operation :rf.event/dispatched
    :tags {:rf.trace/dispatch-id dispatch-id :rf.event/v [:counter/inc]}}
   {:id (+ id-base 2) :op-type :rf.fx :operation :rf.fx/do-fx
    :tags {:rf.trace/dispatch-id dispatch-id}}
   {:id (+ id-base 3) :op-type :rf.fx    :operation :rf.fx/handled
    :tags {:rf.trace/dispatch-id dispatch-id :rf.fx/id :db}}])

;; ---- tests --------------------------------------------------------------

(deftest empty-when-no-focus
  (testing "with cascades in the buffer but no focused dispatch-id, the
            composite returns empty records (the spine snaps to head in
            LIVE mode but the cascade picked may have no managed-fx)"
    (seed-buffer! (cascade-evs-non-managed 100 0))
    (rf/with-frame :rf/xray
      (let [out @(rf/subscribe [:rf.xray/managed-fx-for-focused-event])]
        (is (= [] (:records out))
            "non-managed cascade yields empty records in LIVE-head mode")))))

(deftest projects-records-for-focused-cascade
  (testing "focused cascade with managed-fx → records populated"
    (seed-buffer! (cascade-evs-http 200 0))
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/focus-event 200 :rf/default])
      (let [out @(rf/subscribe [:rf.xray/managed-fx-for-focused-event])]
        (is (= 200 (:dispatch-id out)))
        (is (= 1 (count (:records out))))
        (is (= :http (-> out :records first :surface)))
        (is (= :rf.http/managed (-> out :records first :fx-id)))
        (is (= [:user/loaded] (-> out :records first :handler)))
        (is (= :req-abc (-> out :records first :correlation-id)))))))

(deftest empty-records-for-cascade-without-managed-fx
  (testing "focused cascade with no managed-fx → empty records"
    (seed-buffer! (cascade-evs-non-managed 300 0))
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/focus-event 300 :rf/default])
      (let [out @(rf/subscribe [:rf.xray/managed-fx-for-focused-event])]
        (is (= 300 (:dispatch-id out)))
        (is (= [] (:records out)))))))

(deftest focus-event-writes-spine-slot
  (testing ":rf.xray/focus-event dispatches through to the spine slot —
            this is the cross-link the HANDLER DISPATCHED row uses to
            pivot the spine to the handler's event. The row reuses the
            spine's canonical `:rf.xray/focus-event`, so focusing a PAST
            (non-head) event pins the spine to RETRO — head-aware, per
            spine semantics (rf2-fsqlgz collapsed the panel-local
            wrapper onto the spine event)."
    ;; Seed 400 then a LATER head event (500) so 400 is genuinely a
    ;; PAST event — focusing it must flip the spine to :retro.
    (seed-buffer! (concat (cascade-evs-http 400 0)
                          (cascade-evs-http 500 100)))
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/focus-event 400 :rf/default])
      (let [focus @(rf/subscribe [:rf.xray/focus])]
        (is (= 400 (:dispatch-id focus)))
        (is (= :rf/default (:frame focus)))
        (is (= :retro (:mode focus)))))))

;; ---- section disclosure state (rf2-s6m6) ---------------------------------
;;
;; The record panel's five sections each draw a `▶`/`▼` glyph that
;; `theme/section/section-row` renders from `:expanded?` and nothing else —
;; "Click-to-toggle wiring is the caller's responsibility". This ns holds the
;; caller's half: the slot's read and its toggle write.

(def ^:private rec-key
  "The record key `managed-fx-helpers/record-key` composes for the HTTP
  fixture — `\"<surface>-<origin-event-id>-<fx-id>\"`, keyword prefixes and
  all. Written out rather than derived so a drift in the composer shows up
  here as a failure rather than being silently tracked."
  ":http-99-:rf.http/managed")

(defn- expanded-map []
  @(rf/subscribe [:rf.xray/managed-fx-expanded-sections]))

(defn- tree-nodes
  "Every node in a hiccup tree, payload values included. Walked structurally
  rather than through `rf.test-helpers/expand-tree`, which rebuilds nested
  vectors with `mapv` and would substitute its own vectors for the ones under
  test (it strips reader metadata besides).

  rf2-fcy5 — IT DESCENDS INTO MAP VALUES, and that is what keeps \"payload
  values included\" true. A Reagent component took its value as a POSITIONAL
  argument (`[ei/edn-inspector v opts]`), so a vectors-and-seqs walk reached
  it; a Fresco boundary takes ONE PROPS MAP (`[ei/edn-inspector-view
  {:value v …}]`), so the payload now sits behind a map key. Without this
  arm the walk still returns the props map itself and simply never looks
  inside it — an absence that reads exactly like a payload the panel failed
  to render, which is the direction that matters here, since every caller
  below asserts PRESENCE."
  [node]
  (cond
    (vector? node) (cons node (mapcat tree-nodes node))
    (seq? node)    (cons node (mapcat tree-nodes node))
    (map? node)    (cons node (mapcat tree-nodes (vals node)))
    :else          [node]))

(deftest expanded-sections-slot-starts-empty
  (testing "Nothing is stored before the operator touches a disclosure, and
            an empty slot resolves every section to its default — which is
            NOT the same as every section closed, since two default open."
    (seed-buffer! [])
    (rf/with-frame :rf/xray
      (is (nil? (expanded-map)))
      (is (false? (h/resolve-expanded? (expanded-map) rec-key :request)))
      (is (true?  (h/resolve-expanded? (expanded-map) rec-key :wire))))))

(deftest toggle-inverts-the-state-the-operator-can-see
  (testing "The first click must invert what is RENDERED, not a hard-coded
            assumption. A reducer flipping a nil override from `false` would
            be a silent no-op on the two sections that default OPEN — the
            operator clicks `▼ WIRE TIMING` and nothing happens. The event
            resolves through `section-defaults`, so both directions work.

            Not hollow: replace the resolve with a bare `(not (get …))` and
            the WIRE assertions below go red."
    (seed-buffer! [])
    (rf/with-frame :rf/xray
      ;; default-CLOSED section: first click opens, second shuts
      (rf/dispatch-sync [:rf.xray/managed-fx-toggle-section rec-key :request])
      (is (true? (h/resolve-expanded? (expanded-map) rec-key :request)))
      (rf/dispatch-sync [:rf.xray/managed-fx-toggle-section rec-key :request])
      (is (false? (h/resolve-expanded? (expanded-map) rec-key :request)))
      ;; default-OPEN section: first click SHUTS it
      (rf/dispatch-sync [:rf.xray/managed-fx-toggle-section rec-key :wire])
      (is (false? (h/resolve-expanded? (expanded-map) rec-key :wire)))
      (rf/dispatch-sync [:rf.xray/managed-fx-toggle-section rec-key :wire])
      (is (true? (h/resolve-expanded? (expanded-map) rec-key :wire))))))

(deftest toggle-is-keyed-per-record-and-per-section
  (testing "One event-bundle can carry several managed-fx records, and each
            record has five sections. A toggle must move exactly one pair."
    (seed-buffer! [])
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/managed-fx-toggle-section rec-key :request])
      (let [m (expanded-map)]
        (is (= 1 (count m)) "exactly one override was written")
        (is (true? (h/resolve-expanded? m rec-key :request)))
        ;; a sibling SECTION of the same record is untouched
        (is (false? (h/resolve-expanded? m rec-key :response)))
        ;; and so is the same section of a DIFFERENT record
        (is (false? (h/resolve-expanded? m ":flow-99-:rf.fx/reg-flow" :request)))))))

(deftest toggling-puts-the-request-payload-in-the-rendered-tree
  (testing "rf2-s6m6 end-to-end, across the two namespaces: the real event
            writes the slot, the real sub reads it back, and the renderer
            turns that into a payload the operator can actually see. Before
            the repair `record-panel` passed `:expanded? false` as a literal,
            so no state anywhere could put this payload in a tree."
    (seed-buffer! [])
    (rf/with-frame :rf/xray
      (let [req  {:method :get :url "/api/users/42"}
            rec  {:surface :http :fx-id :rf.http/managed
                  :req req
                  :res nil :handler nil :status :ok :phase :completed
                  :http-status 200 :duration-ms 250 :paths-touched []
                  :origin-event-id 99 :dispatch-id 7 :frame :rf/default}
            rk   (h/record-key rec)
            ;; Render through the panel exactly as `panels/ManagedFxList`
            ;; does — the sub's value threaded in as plain data.
            shown? (fn [] (->> (template/record-panel (fn [_]) (expanded-map) rec)
                               (tree-nodes)
                               (some #{req})
                               boolean))]
        (is (false? (shown?))
            "REQUEST is shut on first paint, so its payload is not in the tree")
        (rf/dispatch-sync [:rf.xray/managed-fx-toggle-section rk :request])
        (is (true? (shown?))
            "one toggle later the request payload is in the rendered tree")))))

;; ---- the mount's own composition (rf2-90kv) -------------------------------
;;
;; `panels/ManagedFxList` is the ONLY caller of `records-list`, and it is where
;; the composite sub's value meets the renderer. Nothing graded that seam:
;; `panels_mount_cljs_test`'s `mount-managed-fx-wraps-ManagedFxList` stubs
;; `rf.substrate.adapter/render`, so the view's BODY never executes, and every
;; template test calls `records-list` / `record-panel` directly with a vector
;; already in hand. Both tiers were blind to the one line that composes them,
;; which is how the panel came to throw before painting without a red row
;; anywhere.
;;
;; rf2-fcy5 — THE INSTRUMENT MOVED, AND THE SEAM DID NOT. `ManagedFxList` is
;; now an `rf.fresco/defview` boundary, a real React function component whose
;; body may only run inside a React render window, so `((rf/view id))` — what
;; this row used to drive — has no analogue. The composition it graded was
;; therefore EXTRACTED rather than duplicated here: `panels/managed-fx-list-tree`
;; is `defview`'s own documented extract-a-helper split, holding the
;; `(:records focused)` line and nothing else, and the boundary is a
;; three-argument pass-through into it. Reproducing the reads in this file
;; instead would have made the row assert against its OWN copy of the
;; composition, which is precisely the blindness rf2-90kv was filed about.

(defn- cascade-evs-two-managed-fx
  "One cascade carrying TWO managed-fx invocations.

  Two is what makes this row discriminating: the composite map has THREE
  entries (`:dispatch-id`, `:frame`, `:records`), so a renderer handed the
  map instead of the vector cannot answer 2.

  Seeded through the production trace path and read back through the real
  composite sub rather than a stub — a stub is not available here, and the
  refusal is the framework's rather than a limitation of the fixture:
  re-registering `:rf.xray/managed-fx-for-focused-event` from this ns makes
  `rf/make-frame` refuse image assembly with
  `:rf.error/image-duplicate-id`, because the id would then be selected from
  two source namespaces with different implementations. Measured."
  [dispatch-id id-base]
  [{:id (+ id-base 1) :op-type :rf.event :operation :rf.event/dispatched
    :tags {:rf.trace/dispatch-id dispatch-id :rf.event/v [:user/load]}}
   {:id (+ id-base 2) :op-type :rf.fx :operation :rf.fx/do-fx
    :tags {:rf.trace/dispatch-id dispatch-id}}
   {:id (+ id-base 3) :op-type :rf.fx :operation :rf.fx/handled
    :tags {:rf.trace/dispatch-id dispatch-id
           :rf.fx/id :rf.http/managed
           :rf.fx/args {:request    {:method :get :url "/api/users/1"}
                        :request-id :req-a
                        :on-success [:user/loaded]}}}
   {:id (+ id-base 4) :op-type :rf.fx :operation :rf.fx/handled
    :tags {:rf.trace/dispatch-id dispatch-id
           :rf.fx/id :rf.http/managed
           :rf.fx/args {:request    {:method :get :url "/api/users/2"}
                        :request-id :req-b
                        :on-success [:user/loaded]}}}])

(defn- record-panel-testids
  "Every record-panel `data-testid` in a rendered tree. One per record."
  [tree]
  (->> (tree-nodes tree)
       (keep (fn [n]
               (when (and (vector? n) (map? (second n)))
                 (:data-testid (second n)))))
       (filter #(string/starts-with? % "rf-xray-managed-fx-record-"))))

(deftest managed-fx-list-renders-one-panel-per-record
  (testing "rf2-90kv — the composite sub answers
            `{:dispatch-id … :frame … :records […]}`, and `records-list` takes
            the RECORDS VECTOR. Handing it the whole MAP made `(seq records)`
            truthy, `(count records)` read the map's ENTRY COUNT — 3, whatever
            the real record count — and `(for [rec records] …)` walk map
            entries, so `(name (:status rec))` got nil and threw before the
            panel could paint. No error boundary sits above this render path,
            so it presented as a panel that never appears (the rf2-qhoj shape).

            TWO records is what separates a pass from the bug: 2 panels against
            the map's 3 entries. Measured against the old binding
            `(managed-fx/records-list dispatch focused)` — it goes red by
            THROWING inside `record-panel` before the count is ever read, which
            is red either way and is why the count assertion is stated over a
            tree that has to have been built at all.

            rf2-fcy5 — the body is now driven through
            `panels/managed-fx-list-tree`, which IS the composition: the
            boundary reads the two slots and passes them straight in, so
            this row still grades the one line that meets the renderer.
            The WHOLE composite map is handed over, exactly as the
            boundary hands it, so the `(:records …)` extraction is inside
            the thing under test rather than performed by the test."
    (seed-buffer! (cascade-evs-two-managed-fx 600 0))
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/focus-event 600 :rf/default])
      ;; CONTROL FIRST, so a zero below is the renderer's answer and not the
      ;; seeding's — without it the two readings are indistinguishable, and a
      ;; seeding that quietly produced no records would read exactly like a
      ;; renderer that dropped them.
      (is (= 2 (count (:records @(rf/subscribe
                                   [:rf.xray/managed-fx-for-focused-event]))))
          "control: the composite really answers two records")
      (let [tree    (panels/managed-fx-list-tree
                      @(rf/subscribe [:rf.xray/managed-fx-for-focused-event])
                      @(rf/subscribe [:rf.xray/managed-fx-expanded-sections])
                      (:dispatch (rf/capture-frame)))
            testids (record-panel-testids tree)]
        (is (= 2 (count testids))
            "one record panel per RECORD, not one per entry of the composite map")
        (is (= 2 (count (distinct testids)))
            "and the two panels are distinct, so this is not one record twice")))))
