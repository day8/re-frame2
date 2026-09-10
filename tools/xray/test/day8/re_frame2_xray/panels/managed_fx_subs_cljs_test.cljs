(ns day8.re-frame2-xray.panels.managed-fx-subs-cljs-test
  "Composite-sub test for `:rf.xray/managed-fx-for-focused-event` +
  the `:rf.xray/focus-event` cross-link event (rf2-uyp86).

  Uses the same test-runtime + seed-buffer pattern as
  `event_detail_cljs_test.cljs` — install Xray's handlers, allocate
  the `:rf/xray` frame, push trace events through the production
  `trace-collector/seed-trace-for-test!` path, then read the composite via
  `subscribe`."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
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
  test (it strips reader metadata besides)."
  [node]
  (cond
    (vector? node) (cons node (mapcat tree-nodes node))
    (seq? node)    (cons node (mapcat tree-nodes node))
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
