(ns panel-gallery.fixtures-issues
  "Pure fixture builders for the Causa Issues tab gallery (rf2-sszlr —
  rebuild for new 6-tab Causa shape).

  The issues-ribbon panel reads its rows from
  `:rf.causa/issues-ribbon`, a composite over:

    - `:rf.causa/trace-buffer`     — the raw trace ring buffer
    - `:rf.causa/issues-filters`   — {:severities :prefixes :since-ms}

  Each variant seeds via `:rf.causa/sync-trace-buffer` (same as the
  trace panel) — the panel's projection keeps only events whose
  op-type is `:error`, `:warning`, or `:info` (per
  `issues_ribbon_helpers/issue-event?`). All other events are dropped
  silently, so a buffer of mixed event-types still drives the issues
  feed.

  Where a variant needs to demonstrate the active-filter state it
  dispatches `:rf.causa.issues/toggle-severity` /
  `:rf.causa.issues/toggle-prefix` after the seed.

  ## Issue-event shape

  Per `issues_ribbon_helpers/project-issue` the panel reads:

      {:id        <int>
       :time      <ms>
       :op-type   <:error :warning :info>
       :operation <kw under one of the Spec 009 catalogue prefixes>
       :recovery  <kw-or-nil>
       :tags      {:dispatch-id   <int>
                   :reason        <string>
                   :event         <event-vec>
                   :exception-message <string>
                   :failing-id    <kw-or-nil>
                   :path          <vec-or-nil>}}")

;; ---- per-event builders -------------------------------------------------

(defn- issue
  "Build a single issue trace event. `severity` maps to op-type:
  :error → :error, :warning → :warning, :advisory → :info."
  [{:keys [id time severity operation reason event dispatch-id
           failing-id path exception-message recovery]
    :or {time 1000}}]
  (let [op-type (case severity
                  :error    :error
                  :warning  :warning
                  :advisory :info)]
    (cond-> {:id        id
             :time      time
             :op-type   op-type
             :operation operation
             :tags      (cond-> {}
                          reason            (assoc :reason reason)
                          event             (assoc :event event)
                          dispatch-id       (assoc :dispatch-id dispatch-id)
                          failing-id        (assoc :failing-id failing-id)
                          path              (assoc :path path)
                          exception-message (assoc :exception-message exception-message))}
      recovery (assoc :recovery recovery))))

(defn- non-issue
  "Build a non-issue trace event (a success-path event the panel
  silently drops). Useful for mixing into a buffer to demonstrate
  that the panel projection is filter-correct."
  [id]
  {:id id :time (+ 500 id) :op-type :event :operation :event/dispatched
   :tags {:dispatch-id (+ 100 id) :event [:demo/event id]}})

;; ---- buffer builders ----------------------------------------------------

(defn empty-buffer
  "No issues — panel renders the :no-issues empty-state ('All clear')."
  []
  [])

(defn no-issues-but-events-buffer
  "Only success-path events; panel still renders :no-issues. Per
  `issues_ribbon_helpers/issue-event?` non-issue ops drop silently."
  []
  (mapv non-issue (range 1 8)))

(defn one-issue-buffer
  "Single error event — panel surfaces one feed row."
  []
  [(issue {:id 1 :time 1000 :severity :error
           :operation :rf.error/handler-threw
           :event [:cart/add :apple] :dispatch-id 100
           :reason "handler threw NPE on :cart/add"
           :exception-message "NullPointerException at cart/add-h"})])

(defn dozens-of-issues-buffer
  "Two dozen issues spanning multiple categories and severities;
  exercises the feed list at typical mid-session depth."
  []
  (vec
    (for [i (range 24)]
      (let [sev (nth [:error :warning :advisory] (mod i 3))
            ops [:rf.error/handler-threw
                 :rf.error/fx-failed
                 :rf.warning/large-value-unschema'd
                 :rf.warning/handler-replaced
                 :rf.info/snapshot-restored
                 :rf.ssr/hydration-mismatch
                 :rf.fx/dispatch-loop-suspected
                 :rf.epoch/ring-evict
                 :rf.http/request-timeout]
            op  (nth ops (mod i (count ops)))]
        (issue {:id (+ i 1) :time (+ 1000 (* 10 i)) :severity sev
                :operation op :dispatch-id (+ 100 (quot i 3))
                :reason (str "synthetic issue #" i)})))))

(defn severity-mix-buffer
  "Six issues — two of each severity. Exercises the chip-row counts
  (`error · 2`, `warning · 2`, `advisory · 2`) at exact balance so
  the chip ladder is readable at a glance."
  []
  [(issue {:id 1 :time 1000 :severity :error
           :operation :rf.error/handler-threw :dispatch-id 100
           :reason "handler threw NPE"})
   (issue {:id 2 :time 1001 :severity :error
           :operation :rf.error/fx-failed :dispatch-id 101
           :reason "HTTP 503 — upstream timeout"})
   (issue {:id 3 :time 1002 :severity :warning
           :operation :rf.warning/large-value-unschema'd :dispatch-id 100
           :reason "payload truncated at 1MB"})
   (issue {:id 4 :time 1003 :severity :warning
           :operation :rf.warning/handler-replaced :dispatch-id 102
           :reason "handler :cart/add replaced"})
   (issue {:id 5 :time 1004 :severity :advisory
           :operation :rf.info/snapshot-restored :dispatch-id 103
           :reason "Restored from snapshot #42"})
   (issue {:id 6 :time 1005 :severity :advisory
           :operation :rf.info/route-changed :dispatch-id 104
           :reason "Navigated to /cart"})])

(defn redacted-buffer
  "Issue whose `:event` tag carries `:rf/redacted` markers — the
  panel's description renders the marker verbatim per Spec 009
  §Privacy."
  []
  [(issue {:id 1 :time 1000 :severity :error
           :operation :rf.error/handler-threw
           :event [:auth/sign-in {:email "ada@example.com"
                                  :password :rf/redacted
                                  :totp :rf/redacted}]
           :dispatch-id 100
           :reason "auth handler threw NPE on :totp slot"})])

;; ---- panel-specific axes -----------------------------------------------

(defn schema-violation-buffer
  "Panel-specific axis A: schema-violation issues carry `:path`
  under tags — the description column lifts the path into the
  one-line summary. Two schema violations + one regular error so
  the panel renders the path detail alongside a baseline."
  []
  [(issue {:id 1 :time 1000 :severity :error
           :operation :rf.schema/violation
           :path [:user :profile :email] :dispatch-id 100
           :reason "expected string, got nil"})
   (issue {:id 2 :time 1001 :severity :error
           :operation :rf.schema/violation
           :path [:cart :items 0 :id] :dispatch-id 101
           :reason "expected keyword, got integer"})
   (issue {:id 3 :time 1002 :severity :error
           :operation :rf.error/handler-threw
           :event [:user/save :profile] :dispatch-id 100
           :reason "handler threw"})])

(defn ssr-hydration-mismatch-buffer
  "Panel-specific axis B: SSR hydration-mismatch issues carry the
  `:rf.ssr` prefix — the prefix chip row surfaces it as its own
  axis. Two SSR mismatches + one HTTP error so the prefix ladder
  has ≥2 chips."
  []
  [(issue {:id 1 :time 1000 :severity :warning
           :operation :rf.ssr/hydration-mismatch
           :dispatch-id 100
           :reason "SSR/CSR markup divergence on :cart/list"})
   (issue {:id 2 :time 1001 :severity :warning
           :operation :rf.ssr/hydration-mismatch
           :dispatch-id 101
           :reason "missing prop key :user/id"})
   (issue {:id 3 :time 1002 :severity :error
           :operation :rf.http/request-timeout
           :dispatch-id 102
           :reason "GET /api/cart timed out after 30s"})])

(defn handler-exception-buffer
  "Panel-specific axis C: handler-exception issues carry
  `:exception-message` under tags — the description lifts it
  verbatim. Four handler exceptions across distinct handler-ids so
  the description column shows the diversity."
  []
  (vec
    (for [i (range 4)]
      (issue {:id (+ i 1) :time (+ 1000 (* 10 i)) :severity :error
              :operation :rf.error/handler-threw
              :event [(nth [:cart/add :auth/sign-in :report/upload :dashboard/refresh] i)]
              :dispatch-id (+ 100 i)
              :exception-message (nth ["NullPointerException at cart/add-h:42"
                                       "ClassCastException at auth/sign-in-h:18"
                                       "TimeoutException at report/upload-h:103"
                                       "OutOfMemoryError at dashboard/refresh-h:88"] i)}))))

(defn multiple-issues-stacked-buffer
  "Eight issues in a tight time window — pins the feed's visual
  rendering when issues stack quickly (one fault cascade producing
  many warnings + errors). Mixes exceptions + schema + SSR + HTTP."
  []
  [(issue {:id 1 :time 1000 :severity :error
           :operation :rf.error/handler-threw :dispatch-id 100
           :event [:cart/add :apple]
           :reason "handler threw"
           :exception-message "NullPointerException at cart/add-h:42"})
   (issue {:id 2 :time 1001 :severity :error
           :operation :rf.schema/violation :dispatch-id 100
           :path [:cart :items 0 :id]
           :reason "expected keyword, got integer"})
   (issue {:id 3 :time 1002 :severity :warning
           :operation :rf.warning/large-value-unschema'd :dispatch-id 100
           :reason "payload truncated at 1MB"})
   (issue {:id 4 :time 1003 :severity :error
           :operation :rf.http/request-timeout :dispatch-id 101
           :reason "GET /api/cart timed out after 30s"})
   (issue {:id 5 :time 1004 :severity :warning
           :operation :rf.ssr/hydration-mismatch :dispatch-id 102
           :reason "SSR/CSR markup divergence on :cart/list"})
   (issue {:id 6 :time 1005 :severity :error
           :operation :rf.error/fx-failed :dispatch-id 103
           :reason "fx :http failed"})
   (issue {:id 7 :time 1006 :severity :warning
           :operation :rf.fx/dispatch-loop-suspected :dispatch-id 104
           :reason "dispatch loop suspected at depth 24"})
   (issue {:id 8 :time 1007 :severity :advisory
           :operation :rf.info/snapshot-restored :dispatch-id 105
           :reason "Restored from snapshot #42 after error cascade"})])

(defn recovery-spans-buffer
  "Panel-specific axis D: issues carry `:recovery` slot per Spec 009
  §Recovery taxonomy — the panel's row could render the recovery
  hint as a chip. Three recoveries + one no-recovery so the panel
  surfaces the spread."
  []
  [(issue {:id 1 :time 1000 :severity :error
           :operation :rf.error/handler-threw :dispatch-id 100
           :reason "handler threw" :recovery :rollback})
   (issue {:id 2 :time 1001 :severity :error
           :operation :rf.error/fx-failed :dispatch-id 101
           :reason "fx failed" :recovery :retry})
   (issue {:id 3 :time 1002 :severity :warning
           :operation :rf.warning/large-value-unschema'd :dispatch-id 102
           :reason "large elided" :recovery :skip})
   (issue {:id 4 :time 1003 :severity :error
           :operation :rf.epoch/ring-evict :dispatch-id 103
           :reason "epoch ring evicted under load" :recovery :no-recovery})])
