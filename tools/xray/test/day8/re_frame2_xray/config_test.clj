(ns day8.re-frame2-xray.config-test
  "JVM tests for Xray's config — the editor preference + configure!
  round-trip."
  (:require [clojure.test :refer [are deftest is testing use-fixtures]]
            [day8.re-frame2-xray.config :as config]))

(defn reset-config [test-fn]
  (letfn [(reset []
            (config/set-editor! nil)
            (config/set-auto-open! nil)
            (config/set-project-root! nil)
            (config/set-filter-seed! nil)
            (config/reset-settings!))]
    (reset)
    (test-fn)
    (reset)))

(use-fixtures :each reset-config)

(deftest set-editor-round-trips
  (config/set-editor! :cursor)
  (is (= :cursor (config/get-editor)))
  (config/set-editor! {:custom "helix://file/{path}:{line}"})
  (is (= {:custom "helix://file/{path}:{line}"} (config/get-editor))))

;; ---- editor-configured? (open-in-editor DX hint) -----------------------
;;
;; True iff the host explicitly set an editor OR a valid operator override
;; exists; false makes the open-in-editor event surface the 'pick an editor
;; in Settings' hint instead of navigating.

(deftest editor-configured-true-when-host-set
  (config/set-editor! :vscode)
  (is (true? (config/editor-configured?))
      "explicit :vscode counts — the host confirmed the editor"))

(deftest editor-configured-true-when-operator-override
  (config/update-setting! :general :editor-override :cursor)
  (is (true? (config/editor-configured?)))
  (config/update-setting! :general :editor-override {:custom "subl://open?url={path}"})
  (is (true? (config/editor-configured?))))

(deftest editor-configured-false-for-malformed-override
  (config/update-setting! :general :editor-override :not-a-real-editor)
  (is (false? (config/editor-configured?))))

(deftest configure-editor-nil-resets-configured-state
  (testing "configure! gates on key PRESENCE, so an explicit nil resets
            exactly like set-editor! nil does"
    (config/configure! {:rf.xray/editor :cursor})
    (is (= :cursor (config/get-editor)))
    (is (true? (config/editor-configured?)))
    (config/configure! {:rf.xray/editor nil})
    (is (= :vscode (config/get-editor)))
    (is (false? (config/editor-configured?)))))

(deftest configure-leaves-absent-keys-untouched
  (let [seed {:in [{:pattern :seeded}] :out []}]
    (config/configure! {:rf.xray/editor       :idea
                        :rf.xray/auto-open?   false
                        :rf.xray/project-root "/abs/code"
                        :rf.xray/filters      seed})
    (config/configure! {})
    (is (= [:idea true false "/abs/code" seed]
           [(config/get-editor) (config/editor-configured?)
            (config/auto-open-enabled?) (config/get-project-root)
            (config/get-filter-seed)]))))

(deftest configure-passes-auto-open-through
  (config/configure! {:rf.xray/auto-open? false})
  (is (false? (config/auto-open-enabled?)))
  (config/configure! {:rf.xray/auto-open? nil})
  (is (true? (config/auto-open-enabled?)) "nil resets to the default"))

(deftest configure-passes-project-root-through
  (config/configure! {:rf.xray/project-root "C:/Users/me/code/my-app"})
  (is (= "C:/Users/me/code/my-app" (config/get-project-root)))
  (config/configure! {:rf.xray/project-root ""})
  (is (nil? (config/get-project-root)) "a blank root normalises to nil"))

(deftest configure-passes-filters-through
  (let [seed {:in [{:pattern :auth/*}] :out []}]
    (config/configure! {:rf.xray/filters seed})
    (is (= seed (config/get-filter-seed)))))

;; ---- panel width and event-list column widths ------------------------------

(deftest clamp-panel-width-px-clamps-to-floor-and-ceil
  (are [px viewport expected] (= expected (config/clamp-panel-width-px px viewport))
    100    2000 320
    321    2000 321
    5000   2000 1800
    1799   2000 1799
    ;; A malformed persisted payload falls back to the default, which
    ;; matches the inline host's documented `560px` CSS fallback.
    "wide" 2000 560
    ;; A viewport narrower than the floor still floors.
    999    300  320))

(deftest clamp-event-list-col-width-unknown-col-returns-nil
  (is (nil? (config/clamp-event-list-col-width :event-id 100))))

(deftest resolve-event-list-col-widths-clamps-and-defaults
  (are [persisted expected] (= expected (config/resolve-event-list-col-widths persisted))
    nil                                       {:source 52 :timestamp 76 :duration 60}
    {:source 10 :timestamp 20 :duration 5}    {:source 40 :timestamp 60 :duration 48}
    {:source 100 :phantom 200 :event-id 999}  {:source 100 :timestamp 76 :duration 60}
    {:source "wide" :timestamp nil :duration "tall"}
    {:source 52 :timestamp 76 :duration 60}))

;; ---- editor-uri ----------------------------------------------------------

(deftest editor-uri-uses-current-preference
  (config/set-editor! :cursor)
  (is (= "cursor://file/src/x.cljs:12:4"
         (config/editor-uri {:file "src/x.cljs" :line 12 :column 4}))))

(deftest editor-uri-project-root-regression-rf2-5m5n2
  (testing "a classpath-relative source-coord resolves to an absolute URI
            when :project-root is set (editors reject relative paths)"
    (config/set-project-root! "C:/Users/me/code/my-app/tools/xray/testbeds")
    (is (= (str "vscode://file/"
                "C:/Users/me/code/my-app/tools/xray/testbeds/"
                "panel_gallery/event_detail_stories.cljs:115:3")
           (config/editor-uri
             {:file "panel_gallery/event_detail_stories.cljs"
              :line 115
              :column 3})))))

;; ---- the layered merge, through configure! --------------------------------
;;
;; The JVM sees only the `defaults < configure!` half: the persisted layer is
;; CLJS-only and is pinned in `settings/persistence_cljs_test.cljs`.

(deftest configure-settings-layers-the-seed-over-defaults
  (config/configure!
    {:rf.xray/settings {:general {:text-size             20
                                  :event-list-col-widths {:source 100}}}})
  (is (= 20 (config/get-setting :general :text-size)))
  (is (= :right-rail (config/get-setting :general :panel-position))
      "an untouched sibling in :general keeps the default")
  (is (= :light (config/get-setting :theme nil))
      "an untouched top-level section keeps the default")
  (is (= {:source 100 :timestamp 76 :duration 60}
         (config/get-setting :general :event-list-col-widths))
      "the merge is DEEP"))

(deftest configure-settings-recomputes-rather-than-accumulating
  (testing "a second configure! replaces the seed rather than layering on
            the first one's result"
    (config/configure! {:rf.xray/settings {:general {:text-size 20}}})
    (config/configure! {:rf.xray/settings {:theme :dark}})
    (is (= :dark (config/get-setting :theme nil)))
    (is (= 13 (config/get-setting :general :text-size)))))
