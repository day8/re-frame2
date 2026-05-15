(ns day8.re-frame2-causa.config-test
  "JVM tests for Causa's config — the editor preference + configure!
  round-trip (rf2-evgf5)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [day8.re-frame2-causa.config :as config]))

(defn reset-editor [test-fn]
  (config/set-editor! :vscode)
  (config/set-auto-open! true)
  (test-fn)
  (config/set-editor! :vscode)
  (config/set-auto-open! true))

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
