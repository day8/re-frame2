(ns day8.re-frame2-xray.panels.trace-helpers-cljs-test
  "Pure-data tests for Xray's Trace panel helpers (spec/023-Trace-Panel.md):
  per-row projection and classification, the epoch-scoped feed, and the
  render-side redaction of each db-changed row's per-path diff.

  The redaction rows need a runtime: their subject is
  `re-frame.core/project-egress` resolving a NAMED frame's `:sensitive`
  classification, so the namespace carries the reset-runtime fixture
  `local_render_cljs_test.cljc` uses. The reset is inert for the pure rows."
  (:require #?(:clj  [clojure.test :refer [are deftest is testing use-fixtures]]
               :cljs [cljs.test    :refer-macros [are deftest is testing use-fixtures]])
            #?(:clj  [re-frame.test-support :as rf.test-support
                      :refer [with-trace-recorder!]]
               :cljs [re-frame.test-support :as rf.test-support
                      :refer-macros [with-trace-recorder!]])
            [clojure.string :as str]
            [re-frame.classification :as rf.classification]
            [re-frame.core :as rf]
            [re-frame.epoch :as rf.epoch]
            [re-frame.machines]
            [re-frame.source-coords :as rf.source-coords]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [day8.re-frame2-xray.panels.epoch.badge :as epoch-badge]
            [day8.re-frame2-xray.panels.trace-helpers :as h]
            [day8.re-frame2-xray.test-helpers.trace-event-builders :as teb]
            [day8.re-frame2-xray.theme.tokens :as tokens]))

;; ---- runtime fixture ----------------------------------------------------

(def ^:private secure-frame :trace-helpers.test/secure)
(def ^:private plain-frame  :trace-helpers.test/plain)

;; Declares the ANCESTOR `[:auth]` rather than the leaf: a walk rooted at the
;; changed `[:auth :token]` never matches a declaration above it.
(def ^:private ancestor-frame :trace-helpers.test/ancestor)

(defn- declare-sensitive!
  "Classify `paths` sensitive from namespace `ns-name`, recorded the way that
  namespace's `reg-classification` would record it."
  [ns-name paths]
  (binding [rf.source-coords/*pending-coords* {:ns (symbol ns-name) :line 1}]
    (rf.classification/reg-classification* (keyword ns-name "classified")
                                            {:sensitive paths})))

(defn- frame-viewing!
  "Make frame `id` whose one image is `image-spec`. A frame's classification
  is the registrations its own view resolves (Spec 015 §Static app-db
  classification), so each frame here sees exactly one declaration, or none."
  [id image-spec]
  (rf/make-frame {:id id :images [(rf/image (assoc image-spec :id (keyword "trace-helpers.img" (name id))))]}))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter
     :init-fn (fn []
                (declare-sensitive! "trace-helpers.secure"   [[:auth :token]])
                (declare-sensitive! "trace-helpers.ancestor" [[:auth]])
                (frame-viewing! secure-frame   {:select-ns {:include ["trace-helpers.secure"]}})
                (frame-viewing! ancestor-frame {:select-ns {:include ["trace-helpers.ancestor"]}})
                (frame-viewing! plain-frame
                                {:registrations {:reg-event [[::noop {} (fn [{:keys [db]} _] {:db db})]]}}))}))

;; ---- fixture builders ---------------------------------------------------

(defn- ev
  "A Spec 009-shaped trace event."
  [{:keys [id op-type operation time dispatch-id tags coord]
    :or {time 1000 tags {}}}]
  (cond-> {:id        id
           :op-type   op-type
           :operation operation
           :time      time
           :tags      (cond-> tags
                        dispatch-id (assoc :rf.trace/dispatch-id dispatch-id))}
    coord (assoc :rf.trace/trigger-handler {:source-coord coord})))

;; ---- per-row projection -------------------------------------------------

(deftest project-row-populates-the-row-shape
  (let [e (ev {:id 7 :op-type :rf.event :operation :rf.event/dispatched
               :time 500 :dispatch-id 42
               :tags {:rf.event/v [:counter/inc]}
               :coord {:file "src/foo.cljs" :line 12}})]
    (is (= {:id 7 :time 500 :op-type :rf.event :operation :rf.event/dispatched
            :area :event :area-badge "EVENT" :verb "dispatched"
            :target "[:counter/inc]" :dispatch-id 42
            :source-coord "src/foo.cljs:12" :raw e}
           (select-keys (h/project-row e)
                        [:id :time :op-type :operation :area :area-badge :verb
                         :target :dispatch-id :source-coord :raw])))))

(deftest project-rows-drops-nil-id-events
  (testing "a nil-:id event has no stable identity, so it never reaches
            `row-key`, where two of them would collide on `t:nil`"
    (is (= ["t:1" "t:2"]
           (mapv h/row-key
                 (h/project-rows
                   [(ev {:id 1   :op-type :rf.event :operation :rf.event/dispatched})
                    (ev {:id nil :op-type :rf.event :operation :rf.event/dispatched})
                    (ev {:id 2   :op-type :rf.fx    :operation :rf.fx/handled})
                    (ev {:id nil :op-type :rf.fx    :operation :rf.fx/handled})]))))))

;; ---- area-badge classification — spec/023 §3 / §5 ----------------------

(deftest area-classifies-the-full-vocabulary
  (are [area e] (= area (h/area e))
    :event    {:op-type :rf.event :operation :rf.event/dispatched}
    :db       {:op-type :rf.event :operation :rf.event/db-changed}
    :coeffect {:op-type :rf.event :operation :rf.cofx/run}
    :flow     {:op-type :rf.event :operation :rf.flow/computed}
    :fx       {:op-type :rf.fx :operation :rf.fx/handled}
    :sub      {:op-type :rf.sub :operation :rf.sub/run}
    :view     {:op-type :rf.view :operation :rf.view/render}
    :machine  {:op-type :rf.machine :operation :rf.machine/transition}
    :routing  {:op-type :rf.event :operation :rf.route/activated}
    :resource {:op-type :rf.event :operation :rf.resource/succeeded}
    :epoch    {:op-type :rf.epoch :operation :rf.epoch/snapshotted}
    :error    {:op-type :error :operation :rf.error/x}
    :warning  {:op-type :warning :operation :rf.warning/x}
    ;; severity wins over the resource namespace (spec/023 §7)
    :warning  {:op-type :warning :operation :rf.resource/hydrate-clock-skew}
    ;; namespace fallback when :op-type isn't stamped
    :machine  {:operation :rf.machine.timer/scheduled}
    :event    {:op-type :totally-made-up}
    ;; the `:info` rows badge by where they are emitted: the managed-HTTP
    ;; lifecycle inside its effect, a host-gate adoption inside a render
    :fx       {:op-type :info :operation :rf.http/issued}
    :fx       {:op-type :info :operation :rf.http/stale-suppressed}
    :view     {:op-type :info :operation :rf.ssr/host-adopted}
    ;; severity still wins over the http namespace
    :error    {:op-type :error :operation :rf.http/transport}))

(deftest nav-token-allocated-is-routing-not-a-bare-event
  ;; `:rf.route.nav-token/allocated` rides a SUB-namespace at op-type
  ;; :rf.event, so an exact `= "rf.route"` match would badge, stage and
  ;; describe it as a bare EVENT.
  (let [row (ev {:id 1 :op-type :rf.event
                 :operation :rf.route.nav-token/allocated
                 :tags {:route-id :dashboard :nav-token 7}})]
    (is (= ["ROUTING" :SIDE-EFFECTS ":dashboard"]
           ((juxt h/area-badge h/stage h/target-detail) row)))))

;; ---- what-happened verb — spec/023 §5 -----------------------------------

(deftest what-happened-builds-the-verb
  (are [verb e] (= verb (h/what-happened e))
    "handler ran"         {:operation :rf.event/run-end}
    "scheduled"           {:operation :rf.machine.timer/scheduled}
    "skipped on platform" {:operation :rf.cofx/skipped-on-platform}
    "—"                   {}
    "recalculated"        {:operation :rf.sub/run :tags {:rf.sub/value-changed? true}}
    "ran-unchanged"       {:operation :rf.sub/run :tags {:rf.sub/value-changed? false}}
    ;; a split op whose tag is absent keeps its terminal segment
    ;; rather than guessing a side
    "run"                 {:operation :rf.sub/run}))

;; ---- target / detail — spec/023 §3 / §5 ---------------------------------

(deftest target-detail-renders-the-subject
  (are [target e] (= target (h/target-detail e))
    "[:counter/inc]"
    {:op-type :rf.event :operation :rf.event/dispatched
     :tags {:rf.event/v [:counter/inc]}}

    ;; db-changed carries only the event and frame; the per-path list
    ;; beneath the row carries the change (spec/023 §10.1)
    nil
    (teb/ev :rf.event :rf.event/db-changed
            {:rf.event/v [:counter/inc] :frame :rf/default})

    ;; fx args ride the canonical plural `:rf.fx/args`, read by KEY
    ;; presence: false is a valid effect argument
    ":app/audit → {:message \"saved\"}"
    {:op-type :rf.fx :operation :rf.fx/handled
     :tags {:rf.fx/id :app/audit :rf.fx/args {:message "saved"}}}

    ":app/toggle → false"
    {:op-type :rf.fx :operation :rf.fx/handled
     :tags {:rf.fx/id :app/toggle :rf.fx/args false}}

    ":app/audit"
    {:op-type :rf.fx :operation :rf.fx/handled :tags {:rf.fx/id :app/audit}}

    ":app/counter"
    {:op-type :rf.sub :operation :rf.sub/run :tags {:rf.sub/id :app/counter}}

    ;; a recompute shows `prev-value → value`, by KEY presence
    ":app/counter  1 → 2"
    (teb/sub-run-ev [:app/counter] true 1 2)

    ":app/flag  false → nil"
    (teb/sub-run-ev [:app/flag] true false nil)

    ;; a first run has no prior value: the nil `:rf.sub/prev-value` the
    ;; producer stamps is not an old value
    ":app/counter"
    (teb/ev :rf.sub :rf.sub/run {:rf.sub/id         :app/counter
                                 :rf.sub/query-v    [:app/counter]
                                 :rf.sub/first-run? true
                                 :rf.sub/prev-value nil
                                 :rf.sub/value      0})

    ;; a view re-render names the sub that triggered it ...
    ":app/header ← :app/counter"
    (teb/view-rendered-ev :app/header [[:app/counter]] nil {:triggered-by :app/counter})

    ;; ... and a mount names none
    ":app/header"
    (teb/view-rendered-ev :app/header [[:app/counter]] nil {:mount? true})

    ":title/flow idle → loading"
    {:op-type :rf.machine :operation :rf.machine/transition
     :tags {:machine-id :title/flow :from :idle :to :loading}}

    ;; a macrostep snapshot redacted at emit carries no state to show
    ":p/door"
    {:op-type :rf.machine :operation :rf.machine/transition
     :tags {:actor-id :p/door :before :rf/redacted :after :rf/redacted}}

    ;; the db no-op says what it means (spec/023 Appendix A)
    "returned unchanged db — nothing committed"
    (teb/ev :rf.event :rf.event/db-noop {:rf.event/v [:p/same] :frame :rf/default})

    ":totals → [:totals]"
    (teb/flow-recomputed-ev :totals [:totals] nil 3)

    ;; lifecycle rows carry the [scope resource-id params] scoped key ...
    ":article/by-slug  gen 3"
    {:op-type :rf.event :operation :rf.resource/succeeded
     :tags {:resource/key [:rf.scope/global :article/by-slug {:slug "x"}]
            :generation 3}}

    ;; ... and registered carries the bare :resource-id
    ":article/by-slug"
    {:op-type :rf.event :operation :rf.resource/registered
     :tags {:resource-id :article/by-slug}}

    ;; coeffect → the PRODUCED value, not the requirement arg, by KEY presence
    ":session → {:user-id 42}"
    {:op-type :rf.event :operation :rf.cofx/run
     :tags {:rf.cofx/id :session :rf.cofx/value {:user-id 42} :rf.cofx/arg :auth-token}}

    ":feature/on? → false"
    (teb/cofx-run-ev :feature/on? false)

    ":session/user"
    (teb/ev :rf.cofx :rf.cofx/run {:rf.cofx/id :session/user})

    ;; a hydration-mismatch adoption row is a :warning envelope carrying
    ;; React's message under :error ...
    "Hydration failed because the server rendered text didn't match the client."
    {:op-type :warning :operation :rf.ssr/hydration-mismatch
     :tags {:error    "Hydration failed because the server rendered text didn't match the client."
            :where    're-frame.substrate.spine/make-render
            :recovery :warned-and-replaced}}

    ;; ... or nil when React supplied none
    nil
    {:op-type :warning :operation :rf.ssr/hydration-mismatch
     :tags {:error    nil
            :where    're-frame.fresco.impl.mount/hydrate-root!
            :recovery :warned-and-replaced}}

    ;; the epoch trailer shows its consumer-facing summary, not the cause
    ":blocked"
    {:op-type :rf.epoch :operation :rf.epoch/snapshotted
     :tags {:outcome :halted-depth :summary :blocked}}

    ;; no recognised subject → nil (the view renders an em-dash)
    nil
    {:op-type :rf.event :operation :rf.event/run-end :tags {}}))

;; ---- outcome colour — spec/023 §8 ---------------------------------------

(deftest outcome-colour-tints-the-verb-column
  (are [token e] (= (token tokens/tokens) (h/outcome-colour e))
    :text-primary  {:operation :rf.sub/run}
    :text-tertiary {:operation :rf.sub/skip}
    :dim           {:operation :rf.sub/dispose}
    :info          {:operation :rf.machine.timer/scheduled}
    :yellow        {:op-type :warning :operation :rf.warning/x}
    :red           {:op-type :error :operation :rf.error/x}))

(deftest outcome-tier-reads-an-unchanged-outcome-from-its-verb
  (testing "a recompute that moved nothing and a db no-op are INERT, though
            neither operation's name says so (spec/023 §8)"
    (are [tier e] (= tier (h/outcome-tier e))
      :inert  {:op-type :rf.sub :operation :rf.sub/run :tags {:rf.sub/value-changed? false}}
      :inert  (teb/ev :rf.event :rf.event/db-noop {:rf.event/v [:p/same]})
      ;; CONTROL — the recompute that moved its value is ACTIVE
      :active {:op-type :rf.sub :operation :rf.sub/run :tags {:rf.sub/value-changed? true}}
      :active (teb/ev :rf.event :rf.event/db-changed {:rf.event/v [:p/set]}))))

(deftest machine-transition-and-db-noop-rows-from-the-runtime
  (testing "rows the runtime records: the `:rf.machine/transition` macrostep
            shows `from → to` off its `:before` / `:after` snapshots, as the
            microstep does off its `:from` / `:to`; the db no-op reads inert
            with its target"
    (rf/reg-machine ::door {:initial :open
                            :states  {:open    {:on {:close :closing}}
                                      :closing {:always [{:target :closed}]}
                                      :closed  {:on {:open :open}}}})
    (rf/reg-event ::same (fn [{:keys [db]} _] {:db db}))
    (with-trace-recorder! [traces]
      (rf/dispatch-sync [::door [:close]])
      (rf/dispatch-sync [::same])
      (let [row (fn [op] (some #(when (= op (:operation %)) (h/project-row %)) @traces))]
        (is (= (str ::door " open → closed")
               (:target (row :rf.machine/transition))))
        (is (= (str ::door " closing → closed")
               (:target (row :rf.machine.microstep/transition)))
            "CONTROL: the microstep's own pair")
        (is (= [:inert "returned unchanged db — nothing committed"]
               ((juxt :outcome-tier :target) (row :rf.event/db-noop))))))))

(deftest info-rows-stage-where-they-are-emitted
  (testing "the managed-HTTP `:info` row emitted inside the issuing fx handler
            sits in EFFECT HANDLERS with the FX badge, beside the effect it
            belongs to; a host-gate adoption sits in VIEWS"
    (let [rows (h/project-rows
                 [{:id 1 :op-type :rf.event :operation :rf.event/run-start :tags {}}
                  {:id 2 :op-type :rf.fx :operation :rf.fx/handled
                   :tags {:rf.fx/id :rf.http/managed :rf.fx/args {:request {:url "/x"}}}}
                  {:id 3 :op-type :info :operation :rf.http/issued
                   :tags {:rf.reply/work-id [:rf.work/http 1 1] :url "/x" :method :get}}
                  {:id 4 :op-type :info :operation :rf.ssr/host-adopted
                   :tags {:host :app/map :where 're-frame.fresco.impl.codec/mint-host-gate!}}])]
      (is (= [["EVENT HANDLER" "EVENT"]
              ["EFFECT HANDLERS" "FX"]
              ["EFFECT HANDLERS" "FX"]
              ["VIEWS" "VIEW"]]
             (mapv (juxt :stage-label :area-badge) rows))))))

;; ---- pipeline stage — spec/023 §3a --------------------------------------

(deftest stage-cross-cutting-error-warning-classify-by-occurrence
  (testing "an error / warning row takes its own namespace's step when it
            names one, else the step of the nearest non-severity row before
            it — so the fire order is the input"
    (let [rows (h/project-rows
                 [{:id 1  :op-type :rf.event :operation :rf.event/dispatched}
                  {:id 2  :op-type :error    :operation :rf.error/no-such-handler}
                  {:id 3  :op-type :rf.event :operation :rf.event/run-start}
                  {:id 4  :op-type :warning  :operation :rf.cofx/skipped-on-platform}
                  {:id 5  :op-type :rf.event :operation :rf.event/run-end}
                  {:id 6  :op-type :rf.fx    :operation :rf.fx/handled}
                  {:id 7  :op-type :error    :operation :rf.error/fx-handler-exception}
                  {:id 8  :op-type :warning  :operation :rf.fx/skipped-on-platform}
                  {:id 9  :op-type :rf.sub   :operation :rf.sub/run}
                  {:id 10 :op-type :error    :operation :rf.error/sub-exception}
                  {:id 11 :op-type :warning  :operation :rf.warning/db-nil-coerced}
                  {:id 12 :op-type :rf.view  :operation :rf.view/rendered}])]
      (is (= [:DISPATCH :DISPATCH :HANDLER :COEFFECT :HANDLER :SIDE-EFFECTS
              :SIDE-EFFECTS :SIDE-EFFECTS :SUBSCRIPTIONS :SUBSCRIPTIONS
              :SUBSCRIPTIONS :VIEWS]
             (mapv :stage rows)))
      (is (= ["EFFECT HANDLERS" (epoch-badge/colour :SIDE-EFFECTS)]
             ((juxt :stage-label :stage-colour) (nth rows 6)))
          "a re-staged row's label and colour follow its stage")))
  (testing "a severity row with nothing before it falls back to HANDLER"
    (is (= :HANDLER (:stage (first (h/project-rows [{:id 1 :op-type :error
                                                       :operation :rf.error/x}])))))))

(deftest project-row-carries-stage-label-and-colour
  ;; The stage column reuses the Epoch panel's own badge label and colour.
  (is (= [:SIDE-EFFECTS "EFFECT HANDLERS" (epoch-badge/colour :SIDE-EFFECTS)]
         ((juxt :stage :stage-label :stage-colour)
          (h/project-row (ev {:id 1 :op-type :rf.fx :operation :rf.fx/handled
                              :tags {:rf.fx/id :http-xhrio}}))))))

;; ---- epoch-scoped feed projection — spec/018 §6 -------------------------

(defn- domino-trail-epoch
  "An `:rf/epoch-record` whose `:trace-events` fold the synchronous
  event-side rows (dispatch-id 42), the async reactive rows (nil
  dispatch-id) and the epoch envelope ops."
  []
  {:epoch-id 17
   :trace-events
   [(ev {:id 0 :op-type :rf.epoch :operation :rf.epoch/snapshotted :time 99})
    (ev {:id 1 :op-type :rf.event :operation :rf.event/dispatched
         :time 100 :dispatch-id 42 :tags {:rf.event/v [:counter/inc]}})
    (ev {:id 2 :op-type :rf.event :operation :rf.event/run-end
         :time 101 :dispatch-id 42})
    (ev {:id 3 :op-type :rf.event :operation :rf.event/db-changed
         :time 102 :dispatch-id 42})
    (ev {:id 4 :op-type :rf.fx :operation :rf.fx/handled
         :time 103 :dispatch-id 42})
    (ev {:id 5 :op-type :rf.sub :operation :rf.sub/run
         :time 110 :tags {:rf.sub/id :app/counter}})
    (ev {:id 6 :op-type :rf.sub :operation :rf.sub/run
         :time 111 :tags {:rf.sub/id :app/derived}})
    (ev {:id 7 :op-type :rf.view :operation :rf.view/render :time 120})
    (ev {:id 8 :op-type :rf.epoch :operation :rf.epoch/snapshotted
         :time 121 :tags {:outcome :ok :summary :ok}})]})

(deftest project-feed-from-epoch-folds-the-complete-domino-trail
  (testing "the focused epoch's WHOLE trail, oldest-first — the async
            nil-dispatch-id reactive rows and the envelope ops included"
    (let [feed (h/project-feed-from-epoch (domino-trail-epoch) :focused)]
      (is (= [{:total 9 :rendered 9 :epoch-id 17 :empty-kind nil}
              [0 1 2 3 4 5 6 7 8]]
             [(select-keys feed [:total :rendered :epoch-id :empty-kind])
              (mapv :id (:rows feed))])))))

(deftest project-feed-from-epoch-no-events
  (is (= {:total 0 :rendered 0 :rows [] :epoch-id 3 :empty-kind :no-events}
         (select-keys (h/project-feed-from-epoch {:epoch-id 3 :trace-events []}
                                                 :focused)
                      [:total :rendered :rows :epoch-id :empty-kind]))))

(deftest project-feed-from-epoch-reads-the-record-only-when-focused
  (testing "a :no-focus or :epoch-evicted status names its empty state and
            projects no rows even when a record is passed"
    (are [status]
         (= {:empty-kind status :total 0 :rendered 0 :rows []}
            (select-keys (h/project-feed-from-epoch (domino-trail-epoch) status)
                         [:empty-kind :total :rendered :rows]))
      :no-focus
      :epoch-evicted)))

;; ---- relative timing + duration — spec/023 §3 / §6 ----------------------

(deftest relative-time-figma-form
  (is (= 100 (h/epoch-t0 [{:time 300} {:time 100} {:time 200}])))
  (is (= ["+0.0" "+3.0"]
         (mapv :rel-time (h/with-rel-times [{:time 100} {:time 103}]))))
  (is (nil? (h/format-rel-time nil 100))))

(deftest duration-ms-reads-canonical-per-area-elapsed-tags
  ;; The substrate stamps a per-area namespaced elapsed tag; a reader of only
  ;; the bare `:elapsed-ms` would render `—` for every fx row.
  (is (= 12.0 (h/duration-ms (teb/fx-handled-ev :http/post {:url "/x"} 12.0))))
  (testing "flows carry the bare :elapsed-ms tag"
    (is (= 0.9 (h/duration-ms (teb/flow-recomputed-ev :total [:total] 1 2 0.9)))))
  (testing "a point-in-time emit carries no elapsed → nil (renders —)"
    (is (nil? (h/duration-ms (teb/sub-run-ev [:items] true nil [1 2 3]))))))

(deftest format-duration-figma-form
  (is (= "0.4 ms" (h/format-duration 0.4)))
  (is (= "12.0 ms" (h/format-duration 12)))
  (is (nil? (h/format-duration nil))))

;; ---- source-coord -------------------------------------------------------

(deftest source-coord-projection
  (is (nil? (h/source-coord {:id 1 :op-type :rf.event})))
  (is (= "src/foo.cljs"
         (h/source-coord {:rf.trace/trigger-handler
                          {:source-coord {:file "src/foo.cljs"}}}))))

;; ---- per-path db-changed diff -------------------------------------------
;;
;; The `:rf.event/db-changed` trace event carries no per-path diff; the feed
;; derives it from the epoch record's `:db-before` / `:db-after` and attaches
;; it to every db-changed row's `:db-diff`.

(defn- diff-epoch
  "An epoch record with the given db pair and a db-changed row of id 2."
  [db-before db-after]
  {:epoch-id     71
   :db-before    db-before
   :db-after     db-after
   :trace-events
   [(ev {:id 1 :op-type :rf.event :operation :rf.event/dispatched
         :time 100 :dispatch-id 42 :tags {:rf.event/v [:counter/inc]}})
    (ev {:id 2 :op-type :rf.event :operation :rf.event/db-changed
         :time 102 :dispatch-id 42})]})

(defn- db-row [feed]
  (some #(when (= 2 (:id %)) %) (:rows feed)))

(deftest project-feed-attaches-db-diff-to-db-changed-rows
  (is (= [{:op :modified :path [:counter] :before 1 :after 2}
          {:op :added :path [:flag] :before nil :after true}]
         (:db-diff (db-row (h/project-feed-from-epoch
                             (diff-epoch {:counter 1} {:counter 2 :flag true})
                             :focused))))))

;; ---- render-side redaction ----------------------------------------------
;;
;; The 3-arity derives the changed-path set from the RAW db pair and takes
;; each triple's values from the whole-db egress projection under the
;; observed frame's policy. A declared path therefore still renders its row,
;; reading `:rf/redacted` on both sides: projecting before diffing would make
;; the two sides equal and drop the row, which
;; `tools/xray/spec/004-App-DB-Diff.md` §Count semantics rules out.

(def ^:private secret-before "old-session-jwt-AAA")
(def ^:private secret-after  "new-session-jwt-BBB")

(defn- sensitive-diff-epoch
  "`[:auth :token]` (declared on the secure frame) and the undeclared
  `[:ui :tab]` both change."
  []
  (diff-epoch {:auth {:token secret-before} :ui {:tab :home}}
              {:auth {:token secret-after}  :ui {:tab :cart}}))

(defn- feed-carries-secret?
  "Does either secret appear ANYWHERE in the projected feed?"
  [feed]
  (let [s (pr-str feed)]
    (or (str/includes? s secret-before)
        (str/includes? s secret-after))))

(defn- db-diff-by-path [feed]
  (into {} (map (juxt :path identity)) (:db-diff (db-row feed))))

(deftest project-feed-3-arity-redacts-the-declared-sensitive-path
  (testing "the declared path still RENDERS ITS ROW with the sentinel where
            the values would be, whether the frame declares the leaf or an
            ANCESTOR of it; the undeclared sibling keeps its values"
    (doseq [frame [secure-frame ancestor-frame]]
      (let [feed (h/project-feed-from-epoch (sensitive-diff-epoch) :focused frame)]
        (is (not (feed-carries-secret? feed)) (str frame))
        (is (= {[:auth :token] {:op :modified :path [:auth :token]
                                :before :rf/redacted :after :rf/redacted}
                [:ui :tab]     {:op :modified :path [:ui :tab]
                                :before :home :after :cart}}
               (db-diff-by-path feed))
            (str frame))))))

(deftest project-feed-3-arity-under-a-plain-frame-keeps-every-value
  (testing "CONTROL — the same fixture under a frame that declares nothing
            keeps both values, so the redaction above is the frame's policy
            and the probe can see the secret"
    (let [feed (h/project-feed-from-epoch (sensitive-diff-epoch) :focused plain-frame)]
      (is (feed-carries-secret? feed))
      (is (= {[:auth :token] {:op :modified :path [:auth :token]
                              :before secret-before :after secret-after}
              [:ui :tab]     {:op :modified :path [:ui :tab]
                              :before :home :after :cart}}
             (db-diff-by-path feed))))))

(deftest project-feed-3-arity-fails-closed-but-not-silent
  (testing "a nil or never-registered frame redacts every value but keeps
            every row, so the operator still sees WHICH paths moved"
    (doseq [frame-id [:trace-helpers.test/no-such-frame nil]]
      (let [feed (h/project-feed-from-epoch (sensitive-diff-epoch) :focused frame-id)]
        (is (not (feed-carries-secret? feed)) (pr-str frame-id))
        (is (= {[:auth :token] {:op :modified :path [:auth :token]
                                :before :rf/redacted :after :rf/redacted}
                [:ui :tab]     {:op :modified :path [:ui :tab]
                                :before :rf/redacted :after :rf/redacted}}
               (db-diff-by-path feed))
            (pr-str frame-id))))))

;; ---- recorded classification --------------------------------------------
;;
;; A past epoch keeps the classification it was RECORDED under (Spec 015
;; §Retained values keep their recorded classification), as the App-DB tab
;; renders it: a declaration cleared or re-registered since — what a hot
;; reload editing a `reg-classification` does — must not expose the values
;; that epoch recorded. The path sits outside the fixture's `[:auth]`
;; declarations, which an image-less frame would also see.

(def ^:private recorded-frame :trace-helpers.test/recorded)

(defn- jwt-triple
  "The `[:session :jwt]` triple of `record`'s db-changed row, rendered for
  the frame that recorded it."
  [record]
  (some #(when (= [:session :jwt] (:path %)) %)
        (:db-diff (some #(when (= :rf.event/db-changed (:operation %)) %)
                        (:rows (h/project-feed-from-epoch record :focused recorded-frame))))))

(deftest project-feed-keeps-a-past-epochs-recorded-classification
  (rf/reg-classification ::recorded-session {:sensitive [[:session :jwt]]})
  (rf/make-frame {:id recorded-frame})
  (rf/reg-event ::login (fn [{:keys [db]} [_ jwt]] {:db (assoc-in db [:session :jwt] jwt)}))
  (rf/dispatch-sync [::login secret-before] {:frame recorded-frame})
  (rf/dispatch-sync [::login secret-after] {:frame recorded-frame})
  (let [record   (last (rf.epoch/epoch-history recorded-frame))
        withheld {:op :modified :path [:session :jwt] :before :rf/redacted :after :rf/redacted}]
    (is (= secret-after (get-in record [:db-after :session :jwt]))
        "PRECONDITION: the record holds the raw value")
    (is (= withheld (jwt-triple record))
        "under the live declaration the values are withheld")
    (rf/clear :classification ::recorded-session)
    (is (= withheld (jwt-triple record))
        "and with the declaration since cleared, the record still withholds them")
    (rf/dispatch-sync [::login "after-clear"] {:frame recorded-frame})
    (is (= "after-clear"
           (:after (jwt-triple (last (rf.epoch/epoch-history recorded-frame)))))
        "CONTROL: an epoch recorded after the clear carries no declaration")))
