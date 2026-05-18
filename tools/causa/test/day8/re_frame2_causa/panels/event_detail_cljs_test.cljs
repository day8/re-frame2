(ns day8.re-frame2-causa.panels.event-detail-cljs-test
  "Tests for the Causa Event lens panel (rf2-zh2qc — rewrite of the
  v1 six-domino renderer per Mike's verbatim 6-section design).

  ## What this suite covers

    1. Default-focus / cascade selection / clear (carried over from v1
       — the panel's spine plumbing didn't change).
    2. Cascade-outcome line (top-of-panel) — glyph + colour + SSR badge
       per §5.1 + the hydration-outcome addendum.
    3. The 6 sections render in order: EVENT, DISPATCH SITE,
       INTERCEPTORS, HANDLER, EFFECTS RETURNED, EFFECTS HANDLERS RAN.
    4. Silent-by-default — sections ABSENT (not '(none)') when their
       data is empty.
    5. Handler threw → §5/§6 suppressed + Issues-tab footer renders.
    6. Pure projection helpers (`user-interceptors`, `cascade-outcome`,
       `effects-handlers-ran`, `hydration-outcome-row`).
    7. The meta-on-vector pattern (rf2-ppzid) — :key reaches every
       row inside :for blocks.

  ## Pure-data scope

  The view is pure hiccup; the tests assert against the hiccup tree
  rather than booting a substrate adapter / mounting to the DOM."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-causa.config :as config]
            [day8.re-frame2-causa.preload :as preload]
            [day8.re-frame2-causa.registry :as registry]
            [day8.re-frame2-causa.test-support :as causa-test-support]
            [day8.re-frame2-causa.trace-bus :as trace-bus]
            [day8.re-frame2-causa.panels.event-detail :as event-detail]))

;; ---- fixtures -----------------------------------------------------------

(defn- causa-init! []
  (causa-test-support/reset-all!)
  (trace-bus/clear-buffer!)
  (config/set-show-sensitive! false)
  (config/reset-suppressed-count!))

(use-fixtures :each
  (test-support/reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn causa-init!}))

;; ---- fixture stream builders -------------------------------------------

(defn- cascade-evs
  "Build the canonical Event-lens cascade stream for a given dispatch-
  id + event vector. Mirrors v1 `cascade-evs` but additionally:

    - Hoists `:rf.trace/call-site` to the top level of the
      `:event/dispatched` trace (rf2-twt7m Change 1) so the DISPATCH
      SITE section has data.
    - Stamps `:source` + `:origin` (also top-level — `build-event`'s
      success-path hoist).
    - Stamps `:fx` + `:db-present?` on the `:event/do-fx` trace's
      `:tags` (rf2-twt7m Change 2) so EFFECTS RETURNED has data."
  ([dispatch-id event-vec id-base]
   (cascade-evs dispatch-id event-vec id-base nil))
  ([dispatch-id event-vec id-base {:keys [frame-id call-site source origin fx db-present?]
                                    :or   {fx          [[:db nil] [:dispatch [:bar]]]
                                           db-present? true
                                           source      :ui
                                           origin      :app}}]
   [(cond-> {:id (+ id-base 1) :op-type :event :operation :event/dispatched
             :tags (cond-> {:dispatch-id dispatch-id :event event-vec}
                     frame-id (assoc :frame frame-id))}
      call-site (assoc :rf.trace/call-site call-site)
      source    (assoc :source source)
      origin    (assoc :origin origin))
    {:id (+ id-base 2) :op-type :event :operation :event
     :tags (cond-> {:dispatch-id dispatch-id :phase :run-start}
             frame-id (assoc :frame frame-id))}
    {:id (+ id-base 3) :op-type :event :operation :event
     :tags (cond-> {:dispatch-id dispatch-id :phase :run-end :duration-ms 11}
             frame-id (assoc :frame frame-id))}
    {:id (+ id-base 4) :op-type :event :operation :event/do-fx
     :tags (cond-> {:dispatch-id dispatch-id}
             frame-id    (assoc :frame frame-id)
             fx          (assoc :fx fx)
             db-present? (assoc :db-present? true))}
    {:id (+ id-base 5) :op-type :fx :operation :rf.fx/handled
     :tags (cond-> {:dispatch-id dispatch-id :fx-id :db :duration-ms 1}
             frame-id (assoc :frame frame-id))}
    {:id (+ id-base 6) :op-type :fx :operation :rf.fx/handled
     :tags (cond-> {:dispatch-id dispatch-id :fx-id :dispatch :fx-args [[:bar]]
                    :duration-ms 0}
             frame-id (assoc :frame frame-id))}]))

(defn- seed-buffer!
  [evs]
  (registry/register-causa-handlers!)
  (frame/reg-frame :rf/causa {})
  (doseq [ev evs]
    (trace-bus/collect-trace! ev)))

(defn- expand-fn-component
  [node]
  (if (and (vector? node) (fn? (first node)))
    (apply (first node) (rest node))
    node))

(defn- hiccup-seq
  "Walk a hiccup tree and emit every node (vectors only). Vectors
  whose first element is a function are invoked first so the walker
  descends into the rendered sub-tree."
  [tree]
  (let [children (fn [node]
                   (let [expanded (expand-fn-component node)]
                     (when (or (vector? expanded) (seq? expanded))
                       (seq expanded))))]
    (->> (tree-seq (some-fn vector? seq?) children (expand-fn-component tree))
         (map expand-fn-component))))

(defn- find-by-testid
  "Find the first node in a hiccup tree whose attrs map has the given
  `:data-testid`. Returns nil when no such node exists."
  [tree testid]
  (some (fn [node]
          (when (and (vector? node)
                     (map? (second node))
                     (= testid (:data-testid (second node))))
            node))
        (hiccup-seq tree)))

(defn- find-all-by-testid
  "Find every node in a hiccup tree whose attrs map has the given
  `:data-testid`. Returns a (possibly empty) vector — useful for
  asserting on counts."
  [tree testid]
  (vec
    (filter (fn [node]
              (and (vector? node)
                   (map? (second node))
                   (= testid (:data-testid (second node)))))
            (hiccup-seq tree))))

;; ---- (1) selection plumbing — survives from v1 -------------------------

(deftest live-focus-renders-head-cascade-detail
  (testing "with cascades in the buffer + no explicit selection, the
            spine LIVE-tracks head and the panel renders the head
            cascade's detail"
    (seed-buffer! (concat (cascade-evs 100 [:user/login {:id 42}] 0)
                          (cascade-evs 200 [:user/logout] 100)))
    (rf/with-frame :rf/causa
      (let [data @(rf/subscribe [:rf.causa/event-detail])]
        (is (= 200 (:selected-dispatch-id data))
            "head cascade (200, latest) is the default selection"))
      (let [tree (event-detail/Panel)]
        (is (some? (find-by-testid tree "rf-causa-event-detail-cascade"))
            "cascade-detail container renders for the head cascade")
        (is (nil? (find-by-testid tree "rf-causa-event-detail-empty"))
            "no empty-state container when there's a head to focus on")))))

(deftest cold-start-with-no-cascades-renders-empty-container
  (testing "with an empty buffer + no selection the panel still
            renders the empty-state container — no head to focus on"
    (seed-buffer! [])
    (rf/with-frame :rf/causa
      (let [tree (event-detail/Panel)]
        (is (some? (find-by-testid tree "rf-causa-event-detail-empty")))
        (is (nil? (find-by-testid tree "rf-causa-event-detail-cascade")))))))

(deftest selecting-non-existent-dispatch-id-shows-orphaned-state
  (testing "selecting a dispatch-id that's not in the buffer surfaces
            the orphaned-selection branch"
    (seed-buffer! (cascade-evs 100 [:user/login] 0))
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-dispatch-id 999])
      (let [tree (event-detail/Panel)]
        (is (some? (find-by-testid tree "rf-causa-event-detail-orphaned")))
        (is (nil? (find-by-testid tree "rf-causa-event-detail-cascade")))))))

(deftest clear-selected-dispatch-id-snaps-to-live-head
  (testing "after select + clear the panel snaps back to LIVE head-tracking"
    (seed-buffer! (concat (cascade-evs 100 [:user/login] 0)
                          (cascade-evs 200 [:user/logout] 100)))
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-dispatch-id 100])
      (rf/dispatch-sync [:rf.causa/clear-selected-dispatch-id])
      (let [data @(rf/subscribe [:rf.causa/event-detail])]
        (is (= 200 (:selected-dispatch-id data)))))))

;; ---- (2) the 6 sections render in order --------------------------------

(deftest event-lens-renders-all-six-sections-when-fully-populated
  (testing "a cascade with call-site + fx + interceptors yields the
            full 6-section layout: EVENT, DISPATCH SITE, INTERCEPTORS,
            HANDLER, EFFECTS RETURNED, EFFECTS HANDLERS RAN"
    (rf/with-frame :rf/default
      (rf/reg-event-fx :widget/poke
        [(rf/->interceptor :id :auth/require-login)]
        (fn [_ _] {})))
    (seed-buffer!
      (cascade-evs 100 [:widget/poke {:id 1}] 0
                   {:call-site {:file "src/widget.cljs" :line 42}
                    :source :ui :origin :app
                    :fx [[:db nil] [:dispatch [:bar]]]
                    :db-present? true}))
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-dispatch-id 100])
      (let [tree (event-detail/Panel)]
        (is (some? (find-by-testid tree "rf-causa-event-detail-section-event"))
            "§1 EVENT section present")
        (is (some? (find-by-testid tree "rf-causa-event-detail-section-dispatch"))
            "§2 DISPATCH SITE section present")
        (is (some? (find-by-testid tree "rf-causa-event-detail-section-interceptors"))
            "§3 INTERCEPTORS section present")
        (is (some? (find-by-testid tree "rf-causa-event-detail-section-handler"))
            "§4 HANDLER section present")
        (is (some? (find-by-testid tree "rf-causa-event-detail-section-effects-returned"))
            "§5 EFFECTS RETURNED section present")
        (is (some? (find-by-testid tree "rf-causa-event-detail-section-effects-ran"))
            "§6 EFFECTS HANDLERS RAN section present")))))

(deftest cascade-outcome-line-replaces-literal-event-detail-h1
  (testing "the literal 'Event detail' h1 is gone; the cascade-outcome
            line carries the event-id + outcome glyph + duration"
    (seed-buffer! (cascade-evs 100 [:counter/inc] 0))
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-dispatch-id 100])
      (let [tree (event-detail/Panel)
            outcome (find-by-testid tree "rf-causa-event-detail-outcome")
            event-id (find-by-testid tree "rf-causa-event-detail-outcome-event-id")
            ok-glyph (find-by-testid tree "rf-causa-event-detail-outcome-glyph-ok")
            text     (->> (hiccup-seq tree) (filter string?) (apply str))]
        (is (some? outcome) "outcome line present")
        (is (some? event-id) "event-id shown in outcome line")
        (is (some? ok-glyph) "✓ glyph rendered for happy path")
        (is (not (re-find #"Event detail" text))
            "literal 'Event detail' h1 has been removed")))))

;; ---- (3) cascade-outcome glyph + SSR badge -----------------------------

(deftest cascade-outcome-error-glyph-when-handler-threw
  (testing "a handler exception flips the outcome to ✗ error (red)"
    (seed-buffer!
      (conj (cascade-evs 100 [:foo] 0)
            {:id 99 :op-type :error :operation :rf.error/handler-exception
             :tags {:dispatch-id 100 :event-id :foo}}))
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-dispatch-id 100])
      (let [tree (event-detail/Panel)]
        (is (some? (find-by-testid tree "rf-causa-event-detail-outcome-glyph-error")))
        (is (nil?  (find-by-testid tree "rf-causa-event-detail-outcome-glyph-ok")))))))

(deftest cascade-outcome-warning-glyph-when-depth-exceeded
  (testing "a depth-exceeded warning flips the outcome to ⚠ warning"
    (seed-buffer!
      (conj (cascade-evs 100 [:foo] 0)
            {:id 99 :op-type :warning :operation :rf.warning/depth-exceeded
             :tags {:dispatch-id 100}}))
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-dispatch-id 100])
      (let [tree (event-detail/Panel)]
        (is (some? (find-by-testid tree "rf-causa-event-detail-outcome-glyph-warning")))))))

(deftest cascade-outcome-ssr-badge-when-hydrated
  (testing ":rf.ssr/hydrated event surfaces the SSR✓ badge on the
            outcome line"
    (seed-buffer! (cascade-evs 100 [:rf.ssr/hydrated {:duration-ms 87 :mismatches 0}] 0))
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-dispatch-id 100])
      (let [tree (event-detail/Panel)]
        (is (some? (find-by-testid tree "rf-causa-event-detail-outcome-ssr-badge")))))))

(deftest cascade-outcome-no-ssr-badge-for-ordinary-event
  (testing "ordinary client-only cascades do NOT carry the SSR badge"
    (seed-buffer! (cascade-evs 100 [:counter/inc] 0))
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-dispatch-id 100])
      (let [tree (event-detail/Panel)]
        (is (nil? (find-by-testid tree "rf-causa-event-detail-outcome-ssr-badge")))))))

;; ---- (4) DISPATCH SITE section -----------------------------------------

(deftest dispatch-site-renders-call-site-coord-and-open-chip
  (testing "the DISPATCH SITE section reads :rf.trace/call-site off the
            :event/dispatched trace (rf2-twt7m Change 1) and renders
            both the coord display + the open-in-editor chip"
    (seed-buffer! (cascade-evs 100 [:counter/inc] 0
                                {:call-site {:file "src/views.cljs" :line 127}
                                 :source :ui :origin :app}))
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-dispatch-id 100])
      (let [tree (event-detail/Panel)
            coord (find-by-testid tree "rf-causa-event-detail-dispatch-coord")
            chip  (find-by-testid tree "rf-causa-event-detail-dispatch-open-chip")
            caption (find-by-testid tree "rf-causa-event-detail-dispatch-caption")
            text  (->> (hiccup-seq coord) (filter string?) (apply str))]
        (is (some? coord) "dispatch coord rendered")
        (is (some? chip)  "open-in-editor chip rendered alongside coord")
        (is (re-find #"src/views\.cljs:127" text)
            "coord display includes the file:line")
        (is (some? caption) "via :source · origin :origin caption rendered")))))

(deftest dispatch-site-without-call-site-renders-placeholder
  (testing "when no :rf.trace/call-site is captured the DISPATCH SITE
            section renders the absent placeholder (not the open chip)"
    (seed-buffer! (cascade-evs 100 [:counter/inc] 0 {:call-site nil}))
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-dispatch-id 100])
      (let [tree (event-detail/Panel)]
        (is (some? (find-by-testid tree "rf-causa-event-detail-dispatch-coord-absent")))
        (is (nil? (find-by-testid tree "rf-causa-event-detail-dispatch-open-chip")))))))

;; ---- (5) INTERCEPTORS section — silent-by-default ----------------------

(deftest interceptors-section-absent-when-zero-non-standard
  (testing "per §4.2 + §7.2 — when the event has no non-standard
            interceptors the INTERCEPTORS section is ABSENT entirely
            (silent-by-default, NOT '(none)' placeholder)"
    (rf/with-frame :rf/default
      (rf/reg-event-db :counter/inc (fn [db _] db)))
    (seed-buffer! (cascade-evs 100 [:counter/inc] 0))
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-dispatch-id 100])
      (let [tree (event-detail/Panel)]
        (is (nil? (find-by-testid tree "rf-causa-event-detail-section-interceptors"))
            "INTERCEPTORS section absent when zero user interceptors")))))

(deftest interceptors-section-renders-user-interceptors
  (testing "with a user interceptor on the chain INTERCEPTORS is
            shown with one row per non-default interceptor"
    (rf/with-frame :rf/default
      (rf/reg-event-fx :auth/login
        [(rf/->interceptor :id :auth/require-login)
         (rf/->interceptor :id :auth/log-action)]
        (fn [_ _] {})))
    (seed-buffer! (cascade-evs 100 [:auth/login] 0))
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-dispatch-id 100])
      (let [tree (event-detail/Panel)
            section (find-by-testid tree "rf-causa-event-detail-section-interceptors")]
        (is (some? section) "section rendered")
        (is (some? (find-by-testid tree
                                    "rf-causa-event-detail-interceptor-row-auth/require-login")))
        (is (some? (find-by-testid tree
                                    "rf-causa-event-detail-interceptor-row-auth/log-action")))))))

;; ---- (6) HANDLER section -----------------------------------------------

(deftest handler-section-shows-flavour-and-source-coord
  (testing "HANDLER section shows reg-event-* flavour (per Q2 — does
            NOT duplicate the event-id)"
    (rf/with-frame :rf/default
      (rf/reg-event-fx :cart/add-item (fn [_ _] {})))
    (seed-buffer! (cascade-evs 100 [:cart/add-item {:id 1}] 0))
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-dispatch-id 100])
      (let [tree (event-detail/Panel)
            flav (find-by-testid tree "rf-causa-event-detail-handler-flavour")
            flav-text (->> (hiccup-seq flav) (filter string?) (apply str))]
        (is (some? flav) "flavour caption rendered")
        (is (= "reg-event-fx" flav-text)
            "shows reg-event-fx flavour for :fx-kind handler")))))

(deftest handler-section-absent-coord-when-no-registration
  (testing "an event with no registered handler renders the absent
            placeholder (the lens never crashes on an unregistered id)"
    (seed-buffer! (cascade-evs 100 [:never-registered/event] 0))
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-dispatch-id 100])
      (let [tree (event-detail/Panel)]
        (is (some? (find-by-testid tree "rf-causa-event-detail-handler-coord-absent")))))))

;; ---- (7) EFFECTS RETURNED — silent-by-default + hydration row ----------

(deftest effects-returned-renders-db-marker-and-fx-vector
  (testing "rf2-twt7m Change 2 stamps :fx + :db-present? on
            :event/do-fx — EFFECTS RETURNED surfaces both rows"
    (seed-buffer! (cascade-evs 100 [:counter/inc] 0
                                {:fx [[:dispatch [:bar]]]
                                 :db-present? true}))
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-dispatch-id 100])
      (let [tree (event-detail/Panel)]
        (is (some? (find-by-testid tree "rf-causa-event-detail-effects-returned-row-db"))
            ":db marker row present")
        (is (some? (find-by-testid tree "rf-causa-event-detail-effects-returned-row-fx"))
            ":fx vector row present")))))

(deftest effects-returned-section-absent-when-no-effects
  (testing "per §7.3 — when neither :fx nor :db is present, §5 is ABSENT"
    (seed-buffer! (cascade-evs 100 [:noop/event] 0
                                {:fx nil :db-present? false}))
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-dispatch-id 100])
      (let [tree (event-detail/Panel)]
        (is (nil? (find-by-testid tree "rf-causa-event-detail-section-effects-returned")))))))

(deftest hydration-outcome-row-renders-for-rf-ssr-hydrated
  (testing "the hydration-outcome addendum surfaces a dedicated row
            when the focused event is :rf.ssr/hydrated"
    (seed-buffer!
      (concat (cascade-evs 100
                            [:rf.ssr/hydrated {:duration-ms 87 :subs-ran 142 :mismatches 0}]
                            0
                            {:fx nil :db-present? false})
              [{:id 50 :op-type :event :operation :rf.ssr/hydration-outcome
                :tags {:dispatch-id 100 :duration-ms 87 :subs-ran 142 :mismatches 0}}]))
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-dispatch-id 100])
      (let [tree (event-detail/Panel)]
        (is (some? (find-by-testid tree "rf-causa-event-detail-effects-returned-row-hydration"))
            "hydration-outcome row renders inside §5")
        (is (nil? (find-by-testid tree "rf-causa-event-detail-hydration-issues-jump"))
            "no jump-to-Issues affordance when :mismatches is 0")))))

(deftest hydration-outcome-row-jumps-to-issues-when-mismatches-pos
  (testing "when :mismatches > 0 the hydration row carries the
            jump-to-Issues affordance"
    (seed-buffer!
      (concat (cascade-evs 100 [:rf.ssr/hydrated {:mismatches 3}] 0
                            {:fx nil :db-present? false})
              [{:id 50 :op-type :event :operation :rf.ssr/hydration-outcome
                :tags {:dispatch-id 100 :duration-ms 91 :mismatches 3}}]))
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-dispatch-id 100])
      (let [tree (event-detail/Panel)]
        (is (some? (find-by-testid tree "rf-causa-event-detail-hydration-issues-jump"))
            "jump-to-Issues affordance renders when mismatches > 0")))))

;; ---- (8) EFFECTS HANDLERS RAN — silent-by-default + managed-fx inline -

(deftest effects-handlers-ran-renders-one-row-per-fx
  (testing "EFFECTS HANDLERS RAN renders one row per :rf.fx/handled
            trace, keyed by trace :id"
    (seed-buffer! (cascade-evs 100 [:counter/inc] 0))
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-dispatch-id 100])
      (let [tree (event-detail/Panel)]
        (is (some? (find-by-testid tree "rf-causa-event-detail-effects-ran-row-db")))
        (is (some? (find-by-testid tree "rf-causa-event-detail-effects-ran-row-dispatch")))))))

(deftest effects-handlers-ran-section-absent-when-no-fx-ran
  (testing "per §7.3 — when no fx-handlers ran, §6 is ABSENT"
    (let [evs (filterv #(not= :rf.fx/handled (:operation %))
                       (cascade-evs 100 [:noop/event] 0))]
      (seed-buffer! evs))
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-dispatch-id 100])
      (let [tree (event-detail/Panel)]
        (is (nil? (find-by-testid tree "rf-causa-event-detail-section-effects-ran")))))))

(deftest effects-handlers-ran-mounts-managed-fx-record-inline
  (testing "per §8.3 — when an fx-handler is a managed-fx surface
            (:rf.http/* etc.) the managed-fx record-panel mounts
            INLINE beneath its causing row, not in a trailing block"
    (seed-buffer!
      [{:id 1 :op-type :event :operation :event/dispatched
        :tags {:dispatch-id 100 :event [:cart/refresh]}}
       {:id 2 :op-type :event :operation :event
        :tags {:dispatch-id 100 :phase :run-end :duration-ms 3}}
       {:id 3 :op-type :event :operation :event/do-fx
        :tags {:dispatch-id 100}}
       {:id 4 :op-type :fx :operation :rf.fx/handled
        :tags {:dispatch-id 100 :fx-id :rf.http/get :duration-ms 87
               :source :http :origin :app}}])
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-dispatch-id 100])
      (let [tree (event-detail/Panel)
            inline (find-by-testid tree "rf-causa-event-detail-effects-ran-managed-fx-4")]
        (is (some? inline)
            "managed-fx record-panel mounts inline beneath fx-handler row")))))

;; ---- (9) handler-threw footer + suppression of §5/§6 -------------------

(deftest handler-threw-suppresses-effects-sections-and-renders-footer
  (testing "per §7.5 — when the handler threw, §5 + §6 are absent and
            the footer caption pointing at Issues tab renders"
    (seed-buffer!
      (concat (cascade-evs 100 [:checkout/submit] 0
                            {:fx nil :db-present? false})
              [{:id 50 :op-type :error :operation :rf.error/handler-exception
                :tags {:dispatch-id 100 :event-id :checkout/submit
                       :exception-message "NullPointerException"}}]))
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-dispatch-id 100])
      (let [tree (event-detail/Panel)]
        (is (some? (find-by-testid tree "rf-causa-event-detail-handler-threw-footer"))
            "footer caption renders when handler threw")
        (is (nil? (find-by-testid tree "rf-causa-event-detail-section-effects-returned"))
            "§5 EFFECTS RETURNED absent — handler never returned")
        (is (nil? (find-by-testid tree "rf-causa-event-detail-section-effects-ran"))
            "§6 EFFECTS HANDLERS RAN absent — fx walk never started")
        (is (some? (find-by-testid tree "rf-causa-event-detail-section-event"))
            "§1 EVENT still present")
        (is (some? (find-by-testid tree "rf-causa-event-detail-section-handler"))
            "§4 HANDLER still present")))))

;; ---- (10) pure projection helpers --------------------------------------

(deftest user-interceptors-filters-rf-default-flagged-entries
  (testing "user-interceptors removes anything carrying :rf/default? true
            (rf2-twt7m Change 3) — no allowlist needed"
    (let [chain [{:id :rf/db-handler :rf/default? true :before identity}
                 {:id :auth/require-login :before identity}
                 {:id :path :before identity}]
          user  (event-detail/user-interceptors chain)]
      (is (= 2 (count user))
          "the auto-wrapper is filtered; the user interceptor + std :path remain")
      (is (= #{:auth/require-login :path} (set (map :id user)))))))

(deftest user-interceptors-falls-back-to-allowlist-when-flag-missing
  (testing "for legacy registrations missing the :rf/default? flag, the
            three known auto-wrapper ids are still filtered as a
            belt-and-braces fallback"
    (let [chain [{:id :rf/db-handler :before identity}
                 {:id :rf/fx-handler :before identity}
                 {:id :rf/ctx-handler :before identity}
                 {:id :user-icpt :before identity}]
          user  (event-detail/user-interceptors chain)]
      (is (= [:user-icpt] (map :id user))))))

(deftest cascade-outcome-projection-shape-is-stable
  (testing "cascade-outcome returns the documented keys"
    (let [out (event-detail/cascade-outcome
                {:event [:foo] :handler {:tags {:duration-ms 5}}
                 :dispatch-id 1 :other []})]
      (is (= #{:event-id :glyph :outcome :duration-ms :dispatch-id :ssr?}
             (set (keys out)))))))

(deftest effects-handlers-ran-projects-rows-from-effects-bucket
  (testing "effects-handlers-ran reads cascade :effects directly"
    (let [rows (event-detail/effects-handlers-ran
                 {:effects [{:id 5 :operation :rf.fx/handled
                             :tags {:fx-id :db}}
                            {:id 6 :operation :rf.fx/handled
                             :tags {:fx-id :dispatch :fx-args [[:foo]]}}]})]
      (is (= [:db :dispatch] (mapv :fx-id rows)))
      (is (= [:rf.fx/handled :rf.fx/handled] (mapv :operation rows)))
      (is (= [5 6] (mapv :id rows))))))

(deftest hydration-outcome-row-nil-for-ordinary-events
  (testing "hydration-outcome-row returns nil unless the event is
            :rf.ssr/hydrated / :rf.ssr/hydration-complete"
    (is (nil? (event-detail/hydration-outcome-row
                {:event [:counter/inc] :other []})))
    (is (some? (event-detail/hydration-outcome-row
                 {:event [:rf.ssr/hydrated]
                  :other [{:operation :rf.ssr/hydration-outcome
                           :tags {:mismatches 0 :duration-ms 87}}]})))))

;; ---- (11) meta-on-vector pattern (rf2-ppzid) ---------------------------

(deftest fx-rows-carry-distinct-react-keys
  (testing "rf2-ppzid — `with-meta` on the fn return preserves :key on
            each row inside :for. Without the wrapper Reagent's
            `get-react-key` reads from the source list and gets nil for
            every row, causing reconciliation churn + a console warning"
    (seed-buffer! (cascade-evs 100 [:counter/inc] 0))
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-dispatch-id 100])
      (let [tree (event-detail/Panel)
            rows (find-all-by-testid tree "rf-causa-event-detail-effects-ran-row-db")]
        (is (= 1 (count rows))
            "exactly one :db row rendered (sanity)")
        (let [section (find-by-testid tree "rf-causa-event-detail-section-effects-ran")
              body    (some #(when (and (vector? %)
                                        (map? (second %))
                                        (= "rf-causa-event-detail-section-effects-ran-body"
                                           (:data-testid (second %))))
                               %)
                            (hiccup-seq section))
              row-elts (->> (hiccup-seq body)
                            (filter (fn [n]
                                      (and (vector? n)
                                           (map? (second n))
                                           (let [tid (str (or (:data-testid (second n)) ""))]
                                             (str/starts-with?
                                               tid
                                               "rf-causa-event-detail-effects-ran-row-"))))))]
          (is (every? #(some? (:key (meta %))) row-elts)
              "every fx row vector carries a :key in its meta"))))))
