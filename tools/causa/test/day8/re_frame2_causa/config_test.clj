(ns day8.re-frame2-causa.config-test
  "JVM tests for Causa's config — the editor preference + configure!
  round-trip (rf2-evgf5)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [day8.re-frame2-causa.config :as config]))

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
  (testing "Causa's default editor preference is :vscode"
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
  (testing "configure! routes :editor through set-editor!"
    (config/configure! {:editor :cursor})
    (is (= :cursor (config/get-editor)))
    (config/configure! {:editor :idea})
    (is (= :idea (config/get-editor)))))

(deftest configure-without-editor-leaves-preference
  (testing "configure! without :editor leaves the preference unchanged"
    (config/set-editor! :cursor)
    (config/configure! {})
    (is (= :cursor (config/get-editor)))))

(deftest auto-open-defaults-to-enabled
  (testing "Causa's default launch auto-opens the inline host"
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
  (testing "configure! routes :launch/auto-open? through set-auto-open!"
    (config/configure! {:launch/auto-open? false})
    (is (false? (config/auto-open-enabled?)))
    (config/configure! {:launch/auto-open? true})
    (is (true? (config/auto-open-enabled?)))))

(deftest configure-without-auto-open-leaves-preference
  (testing "configure! without :launch/auto-open? leaves the flag unchanged"
    (config/set-auto-open! false)
    (config/configure! {:editor :cursor})
    (is (false? (config/auto-open-enabled?)))))

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
;; on-disk root via `configure! :project-root`; the Causa-side helpers
;; prepend it before the URI ships.

(deftest default-project-root-is-nil
  (testing "Causa's default project-root is nil (preserves v1 behaviour
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
  (testing "configure! routes :project-root through set-project-root!"
    (config/configure! {:project-root "C:/Users/me/code/my-app"})
    (is (= "C:/Users/me/code/my-app" (config/get-project-root)))
    (config/configure! {:project-root "/abs/code"})
    (is (= "/abs/code" (config/get-project-root)))
    (testing "explicit nil clears the slot"
      (config/configure! {:project-root nil})
      (is (nil? (config/get-project-root))))))

(deftest configure-without-project-root-leaves-slot-untouched
  (testing "configure! with no :project-root key leaves the slot unchanged
            (lets hosts call configure! multiple times for unrelated
            keys without clobbering the project-root)"
    (config/set-project-root! "/abs/code")
    (config/configure! {:editor :cursor})
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
;; Causa instances (Story testbeds) can isolate their pill state.

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
    (config/configure! {:filters seed})
    (is (= seed (config/get-filter-seed)))))

(deftest configure-without-filters-leaves-seed-untouched
  (config/set-filter-seed! {:in [{:pattern :seeded}] :out []})
  (config/configure! {:editor :cursor})
  (is (= {:in [{:pattern :seeded}] :out []}
         (config/get-filter-seed))))

(deftest default-filters-storage-key-is-stable
  (testing "the published default key must not drift — host stylesheets
            and Story testbeds reference the literal string"
    (is (= "re-frame2.causa.filters.v1"
           (config/get-filters-storage-key)))))

(deftest set-filters-storage-key-round-trips
  (config/set-filters-storage-key! "myhost.filters.v1")
  (is (= "myhost.filters.v1" (config/get-filters-storage-key)))
  (config/set-filters-storage-key! nil)
  (is (= "re-frame2.causa.filters.v1"
         (config/get-filters-storage-key))
      "nil resets to the default"))

(deftest configure-passes-filters-storage-key-through
  (config/configure! {:filters/storage-key "story.testbed.a.filters"})
  (is (= "story.testbed.a.filters"
         (config/get-filters-storage-key))))

(deftest configure-without-storage-key-leaves-key-untouched
  (config/set-filters-storage-key! "myhost.filters")
  (config/configure! {:editor :cursor})
  (is (= "myhost.filters" (config/get-filters-storage-key))))

;; ---- panel-width (rf2-x8h9y resize handle) -----------------------------

(deftest default-panel-width-px-published
  (testing "config publishes a default panel width — the resize handle's
            double-click reset target. 560px mirrors the inline-host
            snippet's documented `var(--rf-causa-inline-width, 560px)`
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

(deftest editor-uri-project-root-regression-rf2-5m5n2
  (testing "regression: the relative source-coord case the editor's
            OS handler used to reject ('Path does not exist') now
            resolves to an absolute on-disk URI when :project-root is
            plumbed (mirror of Story's rf2-zfy1e regression)"
    (config/set-project-root!
      "C:/Users/miket/code/re-frame2/tools/causa/testbeds")
    (is (= (str "vscode://file/"
                "C:/Users/miket/code/re-frame2/tools/causa/testbeds/"
                "panel_gallery/event_detail_stories.cljs:115:3")
           (config/editor-uri
             {:file "panel_gallery/event_detail_stories.cljs"
              :line 115
              :column 3})))))
