(ns day8.re-frame2-xray.config-test
  "JVM tests for Xray's config — the editor preference + configure!
  round-trip (rf2-evgf5)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [day8.re-frame2-xray.config :as config]))

(defn reset-editor [test-fn]
  (config/set-editor! :vscode)
  (config/set-auto-open! true)
  (config/set-project-root! nil)
  (config/set-filter-seed! nil)
  (config/set-filters-storage-key! nil)
  (test-fn)
  (config/set-editor! :vscode)
  (config/set-auto-open! true)
  (config/set-project-root! nil)
  (config/set-filter-seed! nil)
  (config/set-filters-storage-key! nil))

(use-fixtures :each reset-editor)

(deftest default-editor-is-vscode
  (testing "Xray's default editor preference is :vscode"
    (is (= :vscode (config/get-editor)))))

(deftest set-editor-round-trips
  (testing "set-editor! writes and get-editor reads"
    (config/set-editor! :cursor)
    (is (= :cursor (config/get-editor)))
    (config/set-editor! :windsurf)
    (is (= :windsurf (config/get-editor)))
    (config/set-editor! :zed)
    (is (= :zed (config/get-editor)))
    (config/set-editor! :idea)
    (is (= :idea (config/get-editor)))
    (config/set-editor! {:custom "helix://file/{path}:{line}"})
    (is (= {:custom "helix://file/{path}:{line}"} (config/get-editor)))))

(deftest nil-editor-resets-to-vscode
  (testing "set-editor! with nil resets to :vscode"
    (config/set-editor! :cursor)
    (config/set-editor! nil)
    (is (= :vscode (config/get-editor)))))

(deftest configure-passes-editor-through
  (testing "configure! routes :rf.xray/editor through set-editor!"
    (config/configure! {:rf.xray/editor :cursor})
    (is (= :cursor (config/get-editor)))
    (config/configure! {:rf.xray/editor :idea})
    (is (= :idea (config/get-editor)))))

;; ---- editor-configured? (rf2-4s08ov — open-in-editor DX hint) ----------
;;
;; The predicate the open-in-editor event-fx reads to decide whether to
;; navigate or surface the 'pick an editor in Settings' hint. True iff
;; the host explicitly set an editor OR a valid operator override exists.

(deftest editor-configured-false-for-bare-default
  (testing "rf2-4s08ov — a host that never set an editor (the bare
            framework-default :vscode) is NOT configured"
    ;; Clear the explicit flag the fixture's `set-editor!` set, and the
    ;; override slot, to model the bare-preload host.
    (reset! config/editor-explicitly-set? false)
    (config/update-setting! :general :editor-override nil)
    (is (false? (config/editor-configured?)))))

(deftest editor-configured-true-when-host-set
  (testing "rf2-4s08ov — an explicit set-editor! (even :vscode) counts
            as configured"
    (reset! config/editor-explicitly-set? false)
    (config/update-setting! :general :editor-override nil)
    (config/set-editor! :vscode)
    (is (true? (config/editor-configured?))
        "explicit :vscode counts — the host confirmed the editor")
    (config/set-editor! :cursor)
    (is (true? (config/editor-configured?)))))

(deftest editor-configured-cleared-by-nil-reset
  (testing "rf2-4s08ov — set-editor! nil clears the explicit flag so the
            hint re-arms"
    (config/set-editor! :cursor)
    (is (true? (config/editor-configured?)))
    (config/set-editor! nil)
    (config/update-setting! :general :editor-override nil)
    (is (false? (config/editor-configured?)))))

(deftest editor-configured-true-when-operator-override
  (testing "rf2-4s08ov — a valid operator override counts as configured
            even with no host set"
    (reset! config/editor-explicitly-set? false)
    (config/update-setting! :general :editor-override :cursor)
    (is (true? (config/editor-configured?)))
    ;; A custom-template override also counts.
    (config/update-setting! :general :editor-override
                            {:custom "subl://open?url={path}"})
    (is (true? (config/editor-configured?)))
    ;; Clean up the override slot for the next test.
    (config/update-setting! :general :editor-override nil)))

(deftest editor-configured-false-for-malformed-override
  (testing "rf2-4s08ov — a malformed override (rejected by
            valid-editor-override?) does NOT count as configured — it
            degrades to the unconfigured state like get-editor does"
    (reset! config/editor-explicitly-set? false)
    (config/update-setting! :general :editor-override :not-a-real-editor)
    (is (false? (config/editor-configured?)))
    (config/update-setting! :general :editor-override nil)))

(deftest configure-editor-sets-configured-flag
  (testing "rf2-4s08ov — configure! {:rf.xray/editor …} flips
            editor-configured? on (it routes through set-editor!)"
    (reset! config/editor-explicitly-set? false)
    (config/update-setting! :general :editor-override nil)
    (is (false? (config/editor-configured?)))
    (config/configure! {:rf.xray/editor :cursor})
    (is (true? (config/editor-configured?)))))

(deftest configure-without-editor-leaves-preference
  (testing "configure! without :rf.xray/editor leaves the preference unchanged"
    (config/set-editor! :cursor)
    (config/configure! {})
    (is (= :cursor (config/get-editor)))))

(deftest configure-editor-nil-resets-configured-state
  (testing "rf2-eilutf — configure! {:rf.xray/editor nil} RESETS the
            editor-configured state, exactly like set-editor! nil does.
            Previously configure! gated on `some?` so an explicit nil was
            silently ignored, leaving editor-configured? stuck true after
            a host tried to reset via the bulk surface — non-equivalent
            to the per-key setter. The bulk surface now gates on key
            PRESENCE: an explicit nil resets, an absent key is untouched."
    ;; Configure an editor first so there is state to reset.
    (config/configure! {:rf.xray/editor :cursor})
    (is (= :cursor (config/get-editor)))
    (is (true? (config/editor-configured?))
        "precondition: editor is configured before the nil reset")
    ;; The bug: this was a no-op before the fix.
    (config/configure! {:rf.xray/editor nil})
    (is (= :vscode (config/get-editor))
        "explicit nil resets the preference to the :vscode default")
    (config/update-setting! :general :editor-override nil)
    (is (false? (config/editor-configured?))
        "explicit nil clears editor-explicitly-set? — the hint re-arms")))

(deftest configure-absent-editor-key-leaves-configured-state
  (testing "rf2-eilutf — a configure! call WITHOUT the :rf.xray/editor key
            leaves both the preference AND the configured flag untouched
            (the key-presence gate must distinguish absent from nil)"
    (config/configure! {:rf.xray/editor :idea})
    (is (true? (config/editor-configured?)))
    ;; A configure! that touches a DIFFERENT key must not disturb editor.
    (config/configure! {:rf.xray/auto-open? false})
    (is (= :idea (config/get-editor))
        "the editor preference survives a non-editor configure! call")
    (is (true? (config/editor-configured?))
        "the configured flag survives a non-editor configure! call")))

(deftest auto-open-defaults-to-enabled
  (testing "Xray's default launch auto-opens the inline host"
    (is (true? (config/auto-open-enabled?)))))

(deftest set-auto-open-round-trips
  (testing "set-auto-open! writes and auto-open-enabled? reads"
    (config/set-auto-open! false)
    (is (false? (config/auto-open-enabled?)))
    (config/set-auto-open! true)
    (is (true? (config/auto-open-enabled?)))))

(deftest nil-auto-open-resets-to-enabled
  (testing "set-auto-open! with nil resets to the default"
    (config/set-auto-open! false)
    (config/set-auto-open! nil)
    (is (true? (config/auto-open-enabled?)))))

(deftest configure-passes-auto-open-through
  (testing "configure! routes :rf.xray/auto-open? through set-auto-open!"
    (config/configure! {:rf.xray/auto-open? false})
    (is (false? (config/auto-open-enabled?)))
    (config/configure! {:rf.xray/auto-open? true})
    (is (true? (config/auto-open-enabled?)))))

(deftest configure-without-auto-open-leaves-preference
  (testing "configure! without :rf.xray/auto-open? leaves the flag unchanged"
    (config/set-auto-open! false)
    (config/configure! {:rf.xray/editor :cursor})
    (is (false? (config/auto-open-enabled?)))))

;; ---- :rf.xray/static-mode? flag — REMOVED (rf2-8l3uk) ------------------
;;
;; The Static-mode feature gate was removed per rf2-8l3uk. Static mode is
;; now unconditionally available; there is no per-host opt-in and no
;; `:rf.xray/static-mode?` configure key to assert. Tests that asserted
;; the gate's existence were removed wholesale.

(deftest editor-uri-uses-current-preference
  (testing "config/editor-uri reads from the live preference atom"
    (let [coord {:file "src/x.cljs" :line 12 :column 4}]
      (config/set-editor! :vscode)
      (is (= "vscode://file/src/x.cljs:12:4" (config/editor-uri coord)))
      (config/set-editor! :cursor)
      (is (= "cursor://file/src/x.cljs:12:4" (config/editor-uri coord)))
      (config/set-editor! :windsurf)
      (is (= "windsurf://file/src/x.cljs:12:4" (config/editor-uri coord)))
      (config/set-editor! :zed)
      (is (= "zed://file/src/x.cljs:12:4" (config/editor-uri coord)))
      (config/set-editor! :idea)
      (is (= "idea://open?file=src/x.cljs&line=12&column=4"
             (config/editor-uri coord))))))

(deftest editor-uri-nil-for-missing-file
  (testing "config/editor-uri returns nil when coord has no :file"
    (is (nil? (config/editor-uri {:line 10})))
    (is (nil? (config/editor-uri nil)))))

;; ---- project-root (rf2-5m5n2) -------------------------------------------
;;
;; Mirror of Story's rf2-zfy1e test matrix. Source-coords stamped at
;; registration time are classpath-relative; editor schemes resolve
;; against the filesystem and reject relative paths. The host plumbs an
;; on-disk root via `configure! :project-root`; the Xray-side helpers
;; prepend it before the URI ships.

(deftest default-project-root-is-nil
  (testing "Xray's default project-root is nil (preserves v1 behaviour
            for hosts that haven't plumbed the knob yet)"
    (is (nil? (config/get-project-root)))))

(deftest set-project-root-round-trips
  (testing "set-project-root! writes and get-project-root reads"
    (config/set-project-root! "/abs/code")
    (is (= "/abs/code" (config/get-project-root)))
    (config/set-project-root! "C:/Users/me/code/my-app")
    (is (= "C:/Users/me/code/my-app" (config/get-project-root)))
    (config/set-project-root! nil)
    (is (nil? (config/get-project-root)))))

(deftest set-project-root-normalises-blank-string-to-nil
  (testing "blank strings normalise to nil so the helper behaves as if
            unset (mirrors Story's rf2-zfy1e normalisation)"
    (config/set-project-root! "")
    (is (nil? (config/get-project-root)))
    (config/set-project-root! "/abs/code")
    (is (= "/abs/code" (config/get-project-root)))
    (config/set-project-root! "")
    (is (nil? (config/get-project-root)))))

(deftest configure-passes-project-root-through
  (testing "configure! routes :rf.xray/project-root through set-project-root!"
    (config/configure! {:rf.xray/project-root "C:/Users/me/code/my-app"})
    (is (= "C:/Users/me/code/my-app" (config/get-project-root)))
    (config/configure! {:rf.xray/project-root "/abs/code"})
    (is (= "/abs/code" (config/get-project-root)))
    (testing "explicit nil clears the slot"
      (config/configure! {:rf.xray/project-root nil})
      (is (nil? (config/get-project-root))))))

(deftest configure-without-project-root-leaves-slot-untouched
  (testing "configure! with no :rf.xray/project-root key leaves the slot
            unchanged (lets hosts call configure! multiple times for
            unrelated keys without clobbering the project-root)"
    (config/set-project-root! "/abs/code")
    (config/configure! {:rf.xray/editor :cursor})
    (is (= "/abs/code" (config/get-project-root)))))

(deftest editor-uri-prepends-project-root
  (testing "config/editor-uri threads :project-root through the helper's
            3-arg form so a relative coord resolves to an absolute on-
            disk URI"
    (config/set-project-root! "C:/Users/me/code/my-app")
    (is (= "vscode://file/C:/Users/me/code/my-app/src/app/views.cljs:42:7"
           (config/editor-uri {:file "src/app/views.cljs"
                               :line 42
                               :column 7})))))

(deftest editor-uri-without-project-root-ships-file-verbatim
  (testing "config/editor-uri preserves v1 behaviour when project-root
            is unset — the file string ships verbatim"
    (is (nil? (config/get-project-root)))
    (is (= "vscode://file/src/app/views.cljs:1:1"
           (config/editor-uri {:file "src/app/views.cljs"
                               :line 1
                               :column 1})))))

;; ---- filter seed + storage key (rf2-ak4ms) ------------------------------
;;
;; Per spec/018-Event-Spine.md §7 'Empty defaults' + rf2-ak4ms 'first-
;; session honesty beats first-session quietness': default filter set
;; is empty; hosts may inject a seed via configure!. Per spec/018 §7
;; Filter persistence: localStorage key is configurable so multiple
;; Xray instances (Story testbeds) can isolate their pill state.

(deftest default-filter-seed-is-nil
  (testing "default filter seed is nil per spec/018 §7 — first-session
            honesty / no auto-filters"
    (is (nil? (config/get-filter-seed)))))

(deftest set-filter-seed-round-trips
  (let [seed {:in [{:pattern :auth/*}] :out [{:pattern :mouse-move}]}]
    (config/set-filter-seed! seed)
    (is (= seed (config/get-filter-seed)))
    (config/set-filter-seed! nil)
    (is (nil? (config/get-filter-seed)))))

(deftest configure-passes-filters-through
  (let [seed {:in [{:pattern :auth/*}] :out []}]
    (config/configure! {:rf.xray/filters seed})
    (is (= seed (config/get-filter-seed)))))

(deftest configure-without-filters-leaves-seed-untouched
  (config/set-filter-seed! {:in [{:pattern :seeded}] :out []})
  (config/configure! {:rf.xray/editor :cursor})
  (is (= {:in [{:pattern :seeded}] :out []}
         (config/get-filter-seed))))

(deftest default-filters-storage-key-is-stable
  (testing "the published default key must not drift — host stylesheets
            and Story testbeds reference the literal string"
    (is (= "re-frame2.xray.filters.v1"
           (config/get-filters-storage-key)))))

(deftest set-filters-storage-key-round-trips
  (config/set-filters-storage-key! "myhost.filters.v1")
  (is (= "myhost.filters.v1" (config/get-filters-storage-key)))
  (config/set-filters-storage-key! nil)
  (is (= "re-frame2.xray.filters.v1"
         (config/get-filters-storage-key))
      "nil resets to the default"))

(deftest configure-passes-filters-storage-key-through
  (config/configure! {:rf.xray/filters-storage-key "story.testbed.a.filters"})
  (is (= "story.testbed.a.filters"
         (config/get-filters-storage-key))))

(deftest configure-without-storage-key-leaves-key-untouched
  (config/set-filters-storage-key! "myhost.filters")
  (config/configure! {:rf.xray/editor :cursor})
  (is (= "myhost.filters" (config/get-filters-storage-key))))

;; ---- panel-width (rf2-x8h9y resize handle) -----------------------------

(deftest default-panel-width-px-published
  (testing "config publishes a default panel width — the resize handle's
            double-click reset target. 560px mirrors the inline-host
            snippet's documented `var(--rf-xray-inline-width, 560px)`
            so reset reads the same value the host CSS falls back to."
    (is (= 560 config/default-panel-width-px))))

(deftest default-settings-include-panel-width-px
  (testing "the default settings map carries `:general :panel-width-px`
            so the persistence round-trip + the Settings popup's reads
            never see a nil"
    (is (= config/default-panel-width-px
           (get-in config/default-settings [:general :panel-width-px])))))

(deftest clamp-panel-width-px-floor
  (testing "min-clamp is published as `min-panel-width-px` (320) and a
            sub-floor input snaps to the floor"
    (is (= 320 config/min-panel-width-px))
    (is (= 320 (config/clamp-panel-width-px 100 2000)))
    (is (= 320 (config/clamp-panel-width-px 320 2000)))
    (is (= 321 (config/clamp-panel-width-px 321 2000)))))

(deftest clamp-panel-width-px-ceil
  (testing "ceil is viewport × 0.9 — a wider request snaps down"
    (is (= 1800 (config/clamp-panel-width-px 5000 2000))
        "2000 × 0.9 = 1800")
    (is (= 1800 (config/clamp-panel-width-px 1800 2000)))
    (is (= 1799 (config/clamp-panel-width-px 1799 2000)))))

(deftest clamp-panel-width-px-non-numeric-falls-back-to-default
  (testing "malformed persisted payload (string, nil, NaN) shouldn't
            leave the panel at an unusable size — fall back to default"
    (is (= config/default-panel-width-px
           (config/clamp-panel-width-px nil 2000)))
    (is (= config/default-panel-width-px
           (config/clamp-panel-width-px "wide" 2000)))))

(deftest clamp-panel-width-px-narrow-viewport-still-floors
  (testing "if viewport is narrower than the floor (mobile / odd
            test runtime), the floor still wins over the ceil — the
            panel never collapses below 320px"
    (is (= 320 (config/clamp-panel-width-px 200 300))
        "300 × 0.9 = 270 < 320 floor; floor wins")
    (is (= 320 (config/clamp-panel-width-px 999 300))
        "even a wide request clamps to the floor when the viewport
         is too narrow for the floor to cleanly fit")))

(deftest update-setting-round-trips-panel-width
  (testing "the standard settings round-trip drives the panel-width
            slot — same surface text-size + theme go through. After
            the round-trip get-setting reads the new value."
    (config/reset-settings!)
    (config/update-setting! :general :panel-width-px 720)
    (is (= 720 (config/get-setting :general :panel-width-px)))
    (config/update-setting! :general :panel-width-px 480)
    (is (= 480 (config/get-setting :general :panel-width-px)))
    (config/reset-settings!)))

;; ---- L2 event-list column widths (rf2-6ni62) -----------------------------
;;
;; The L2 event list's `source` / `timestamp` / `duration` columns are
;; user-resizable via drag handles between cells. Defaults mirror the
;; pre-resize fixed widths so a fresh install lays out identically; the
;; floors keep any column from collapsing below its lowercase header
;; label width. The helpers are CLJC-pure / JVM-testable — the drag-
;; handle dispatch path clamps to the floor BEFORE the persistence
;; write, so the persisted payload is always in-range.

(deftest event-list-col-defaults-mirror-pre-resize-widths
  (testing "rf2-6ni62 — defaults match the pre-resize fixed widths so a
            fresh install lays out identically to the pre-feature shell"
    (is (= {:source 52 :timestamp 76 :duration 60}
           config/event-list-col-default-widths))))

(deftest event-list-col-default-settings-include-widths-map
  (testing "rf2-6ni62 — the default settings map carries `:general
            :event-list-col-widths` so the persistence round-trip + the
            sub's read never see a nil"
    (is (= config/event-list-col-default-widths
           (get-in config/default-settings
                   [:general :event-list-col-widths])))))

(deftest clamp-event-list-col-width-floors
  (testing "rf2-6ni62 — clamp-event-list-col-width snaps a sub-floor
            request to that column's floor"
    (is (= 40 (config/clamp-event-list-col-width :source 10)))
    (is (= 40 (config/clamp-event-list-col-width :source 40)))
    (is (= 41 (config/clamp-event-list-col-width :source 41)))
    (is (= 60 (config/clamp-event-list-col-width :timestamp 20)))
    (is (= 60 (config/clamp-event-list-col-width :timestamp 60)))
    (is (= 48 (config/clamp-event-list-col-width :duration 10)))
    (is (= 48 (config/clamp-event-list-col-width :duration 48)))))

(deftest clamp-event-list-col-width-passes-in-range
  (testing "rf2-6ni62 — in-range values pass through verbatim"
    (is (= 100 (config/clamp-event-list-col-width :source 100)))
    (is (= 200 (config/clamp-event-list-col-width :timestamp 200)))
    (is (= 150 (config/clamp-event-list-col-width :duration 150)))))

(deftest clamp-event-list-col-width-unknown-col-returns-nil
  (testing "rf2-6ni62 — unknown column ids (event-id is flex, never
            sized; future unsupported keys) yield nil so the caller's
            update path can no-op on them"
    (is (nil? (config/clamp-event-list-col-width :event-id 100)))
    (is (nil? (config/clamp-event-list-col-width :unknown 100)))
    (is (nil? (config/clamp-event-list-col-width nil 100)))))

(deftest clamp-event-list-col-width-non-numeric-falls-back-to-default
  (testing "rf2-6ni62 — malformed persisted payload (string, nil, NaN)
            shouldn't leave the column at an unusable size — fall back
            to the column's default width"
    (is (= 52 (config/clamp-event-list-col-width :source nil)))
    (is (= 76 (config/clamp-event-list-col-width :timestamp "wide")))
    (is (= 60 (config/clamp-event-list-col-width :duration nil)))))

(deftest resolve-event-list-col-widths-from-nil
  (testing "rf2-6ni62 — nil persisted payload resolves to the defaults"
    (is (= config/event-list-col-default-widths
           (config/resolve-event-list-col-widths nil)))))

(deftest resolve-event-list-col-widths-from-empty
  (testing "rf2-6ni62 — empty / non-map payload resolves to the defaults"
    (is (= config/event-list-col-default-widths
           (config/resolve-event-list-col-widths {})))
    (is (= config/event-list-col-default-widths
           (config/resolve-event-list-col-widths "not-a-map")))))

(deftest resolve-event-list-col-widths-merges-partial
  (testing "rf2-6ni62 — a partial persisted payload merges over the
            defaults; columns the user has not touched read their
            defaults"
    (is (= {:source 120 :timestamp 76 :duration 60}
           (config/resolve-event-list-col-widths {:source 120})))
    (is (= {:source 52 :timestamp 100 :duration 60}
           (config/resolve-event-list-col-widths {:timestamp 100})))))

(deftest resolve-event-list-col-widths-clamps-each-column
  (testing "rf2-6ni62 — a legacy / hand-edited payload with a sub-floor
            value is clamped on read (defence-in-depth — the write path
            clamps too, but a payload that pre-dates the clamp would
            slip through without this read-side clamp)"
    (is (= {:source 40 :timestamp 76 :duration 60}
           (config/resolve-event-list-col-widths {:source 10}))
        "sub-floor source snaps to 40")
    (is (= {:source 52 :timestamp 60 :duration 60}
           (config/resolve-event-list-col-widths {:timestamp 20}))
        "sub-floor timestamp snaps to 60")
    (is (= {:source 52 :timestamp 76 :duration 48}
           (config/resolve-event-list-col-widths {:duration 5}))
        "sub-floor duration snaps to 48")))

(deftest resolve-event-list-col-widths-drops-unknown-keys
  (testing "rf2-6ni62 — unknown keys in the persisted payload are
            silently dropped (forward-compat: a future column id would
            land in the persisted payload but is ignored by older Xrays;
            the resolved map only carries the known shape)"
    (let [resolved (config/resolve-event-list-col-widths
                     {:source 100 :phantom 200 :event-id 999})]
      (is (= {:source 100 :timestamp 76 :duration 60} resolved))
      (is (not (contains? resolved :phantom)))
      (is (not (contains? resolved :event-id))))))

(deftest resolve-event-list-col-widths-handles-non-numeric-per-column
  (testing "rf2-6ni62 — a per-column non-numeric value falls back to
            that column's default; surrounding columns are unaffected"
    (is (= {:source 52 :timestamp 76 :duration 60}
           (config/resolve-event-list-col-widths
             {:source "wide" :timestamp nil :duration "tall"})))))

(deftest update-setting-round-trips-event-list-col-widths
  (testing "rf2-6ni62 — the standard settings round-trip drives the
            event-list-col-widths slot. After the round-trip get-setting
            reads the new map."
    (config/reset-settings!)
    (config/update-setting! :general :event-list-col-widths
                            {:source 120 :timestamp 90 :duration 80})
    (is (= {:source 120 :timestamp 90 :duration 80}
           (config/get-setting :general :event-list-col-widths)))
    (config/reset-settings!)))

(deftest event-list-col-keyboard-steps-published
  (testing "rf2-6ni62 — fine + coarse keyboard step constants are
            published for the divider's arrow-key handler"
    (is (= 10 config/event-list-col-keyboard-step-px))
    (is (= 3 config/event-list-col-keyboard-coarse-multiplier))))

(deftest editor-uri-project-root-regression-rf2-5m5n2
  (testing "regression: the relative source-coord case the editor's
            OS handler used to reject ('Path does not exist') now
            resolves to an absolute on-disk URI when :project-root is
            plumbed (mirror of Story's rf2-zfy1e regression)"
    (config/set-project-root!
      "C:/Users/me/code/my-app/tools/xray/testbeds")
    (is (= (str "vscode://file/"
                "C:/Users/me/code/my-app/tools/xray/testbeds/"
                "panel_gallery/event_detail_stories.cljs:115:3")
           (config/editor-uri
             {:file "panel_gallery/event_detail_stories.cljs"
              :line 115
              :column 3})))))

;; ---- merge-known-sections deep-merge (rf2-8j3gyt) -----------------------
;;
;; `merge-known-sections` is private; JVM tests reach it via `#'`
;; var-quote — Clojure Vars are directly invokable, the same idiom the
;; CLJS persistence test uses for `#'config/storage-get`.

(deftest merge-known-sections-deep-merges-nested-event-list-col-widths
  (testing "rf2-8j3gyt — a partial nested override under `:general`
            (`:event-list-col-widths`) must merge PER-KEY, not replace
            the whole nested sub-map — the prior shallow `merge`
            dropped the untouched sibling widths on any partial
            nested `src`"
    (let [merged (#'config/merge-known-sections
                  {:general {:event-list-col-widths {:source 100}}})]
      (is (= {:source 100 :timestamp 76 :duration 60}
             (get-in merged [:general :event-list-col-widths]))
          "the untouched :timestamp / :duration siblings survive a
           partial nested override"))))

(deftest merge-known-sections-two-arg-arity-composes-layers
  (testing "rf2-rr2yw3 — the 2-arg arity merges `src` over an explicit
            `base` (not always `default-settings`), letting
            `load-settings-from-storage!` compose the `hardcoded
            defaults < configure! overrides < persisted Settings
            overrides` merge order in one step"
    (let [seeded (#'config/merge-known-sections
                  config/default-settings {:general {:text-size 20}})
          final  (#'config/merge-known-sections
                  seeded {:general {:text-size 30}})]
      (is (= 30 (get-in final [:general :text-size]))
          "the later (persisted, in the real call chain) layer wins")
      (is (= :right-rail (get-in final [:general :panel-position]))
          "an untouched sibling in :general still carries the base's value"))))

;; ---- :rf.xray/settings seeds `configured-settings-seed` (rf2-rr2yw3) ----

(deftest configure-settings-seeds-configured-settings-seed
  (testing "rf2-rr2yw3 — `configure! {:rf.xray/settings ...}` captures
            the raw map into `configured-settings-seed` rather than
            unconditionally resetting + persisting the live settings
            atom — the seed `load-settings-from-storage!` deep-merges
            the persisted payload OVER, so a host that calls
            `configure!` on every boot can no longer clobber a user's
            already-persisted Settings-popup mutations"
    (config/reset-settings!)
    (config/configure! {:rf.xray/settings {:theme :dark}})
    (is (= {:theme :dark} @config/configured-settings-seed))
    (config/reset-settings!)
    (is (nil? @config/configured-settings-seed)
        "reset-settings! clears the seed too — no cross-test leakage")))
