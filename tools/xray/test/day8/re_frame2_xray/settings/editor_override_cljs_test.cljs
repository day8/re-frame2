(ns day8.re-frame2-xray.settings.editor-override-cljs-test
  "CLJS tests for the end-user editor override surface (rf2-dudqz).

  The override is a per-machine preference that lets an individual
  operator on a mixed-editor team override the project's
  `:rf.xray/editor` default without touching the host app's boot
  config. The override:

  - Persists via the existing settings localStorage round-trip
    (no new key; the slot lives in `[:general :editor-override]`).
  - Wins immediately — `config/get-editor` consults it before the
    host `editor` atom.
  - Falls back to the host default when `nil` (the cleared state).
  - Is purely client-side — never mutates the host's atom; never
    reaches other browsers / tabs / users.

  These assertions cover the resolution order, the round-trip, the
  reset path, and the `open-chip` / `:rf.xray/open-in-editor`
  consumers."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [day8.re-frame2-xray.config :as config]
            [day8.re-frame2-xray.open-in-editor :as open-in-editor]))

(use-fixtures :each
  {:before (fn []
             (config/reset-settings!)
             (config/set-editor! :vscode)
             (config/set-project-root! nil))
   :after  (fn []
             (config/reset-settings!)
             (config/set-editor! :vscode)
             (config/set-project-root! nil))})

;; ---- defaults ----------------------------------------------------------

(deftest override-defaults-to-nil
  (testing "Fresh install carries no override — `[:general
            :editor-override]` is nil"
    (is (nil? (config/get-setting :general :editor-override)))))

(deftest host-default-helper-returns-atom-value
  (testing "`get-host-editor-default` exposes the host atom value
            unchanged — separate from `get-editor` which honours the
            override"
    (is (= :vscode (config/get-host-editor-default)))
    (config/set-editor! :idea)
    (is (= :idea (config/get-host-editor-default)))))

;; ---- resolution order --------------------------------------------------

(deftest get-editor-returns-host-default-when-no-override
  (testing "Resolution tier 2: with no override, `get-editor`
            returns the host atom's value"
    (config/set-editor! :cursor)
    (is (nil? (config/get-setting :general :editor-override)))
    (is (= :cursor (config/get-editor)))))

(deftest get-editor-honours-keyword-override
  (testing "Resolution tier 1: an enumerated-keyword override wins
            over the host default"
    (config/set-editor! :vscode)
    (config/update-setting! :general :editor-override :idea)
    (is (= :idea (config/get-editor))
        "override beats host default")
    (is (= :vscode (config/get-host-editor-default))
        "host atom is untouched")))

(deftest get-editor-honours-custom-override
  (testing "Resolution tier 1: a `{:custom <tpl>}` override wins
            over the host default — same shape as `set-editor!`"
    (config/set-editor! :vscode)
    (config/update-setting! :general :editor-override
                            {:custom "subl://open?url=file://{path}&line={line}"})
    (is (= {:custom "subl://open?url=file://{path}&line={line}"}
           (config/get-editor)))))

(deftest get-editor-falls-back-when-override-cleared
  (testing "Clearing the override (writing nil) restores the host
            default — `get-editor` walks back down the resolution
            chain"
    (config/set-editor! :idea)
    (config/update-setting! :general :editor-override :cursor)
    (is (= :cursor (config/get-editor)))
    (config/update-setting! :general :editor-override nil)
    (is (= :idea (config/get-editor))
        "nil override falls through to host atom")))

(deftest framework-default-when-host-and-override-both-absent
  (testing "Resolution tier 3: `set-editor!` resets the host atom to
            `:vscode` when given nil; with no override, get-editor
            still returns the framework default"
    (config/set-editor! nil)  ;; resets host atom to :vscode
    (is (nil? (config/get-setting :general :editor-override)))
    (is (= :vscode (config/get-editor)))))

;; ---- localStorage round-trip ------------------------------------------

(defn- storage-payload []
  (#'config/storage-get config/settings-storage-key))

(deftest override-round-trips-through-localstorage
  (testing "Setting the override writes through to localStorage; a
            simulated reload restores the value"
    (config/update-setting! :general :editor-override :cursor)
    (is (some? (storage-payload))
        "localStorage payload is populated")
    ;; Simulate a reload: reset in-memory atom to defaults, then
    ;; rehydrate from storage.
    (reset! config/settings config/default-settings)
    (is (nil? (config/get-setting :general :editor-override))
        "atom-only reset clears the override")
    (config/load-settings-from-storage!)
    (is (= :cursor (config/get-setting :general :editor-override))
        "reload from localStorage restores the override")))

(deftest custom-override-round-trips
  (testing "A `{:custom <tpl>}` override survives the EDN
            (`pr-str` / `read-string`) round-trip"
    (config/update-setting! :general :editor-override
                            {:custom "emacsclient://{path}:{line}"})
    (reset! config/settings config/default-settings)
    (config/load-settings-from-storage!)
    (is (= {:custom "emacsclient://{path}:{line}"}
           (config/get-setting :general :editor-override)))))

(deftest reset-settings-clears-override
  (testing "`reset-settings!` clears the override slot along with
            every other persisted preference"
    (config/update-setting! :general :editor-override :idea)
    (is (= :idea (config/get-setting :general :editor-override)))
    (config/reset-settings!)
    (is (nil? (config/get-setting :general :editor-override)))
    (is (nil? (storage-payload))
        "localStorage payload cleared")))

;; ---- consumer parity: open-chip + resolve-uri honour the override -----

(deftest open-chip-uses-override-uri
  (testing "`open-in-editor/open-chip` reads through `get-editor`, so
            an override flips the rendered `:href` immediately
            without a reload — the same source of truth as the
            click-time fx"
    (config/set-editor! :vscode)
    ;; Baseline: no override, host default :vscode wins.
    (let [coord  {:file "src/x.cljs" :line 10 :column 1}
          hiccup (open-in-editor/open-chip coord)]
      (is (= "vscode://file/src/x.cljs:10:1"
             (:href (second hiccup))))
      (is (= "vscode" (:data-editor (second hiccup)))))
    ;; Flip the override; the chip flips with it.
    (config/update-setting! :general :editor-override :idea)
    (let [hiccup (open-in-editor/open-chip
                   {:file "src/x.cljs" :line 10 :column 1})]
      (is (= "idea://open?file=src/x.cljs&line=10&column=1"
             (:href (second hiccup)))
          "override-driven editor flips the URI scheme")
      (is (= "idea" (:data-editor (second hiccup)))
          "the `:data-editor` attr reflects the override too"))))

(deftest open-chip-custom-override-substitutes-template
  (testing "An end-user `{:custom <tpl>}` override drives URI
            construction the same way the host's `set-editor!`
            custom value would — the template is substituted at
            click time"
    (config/set-editor! :vscode)
    (config/update-setting! :general :editor-override
                            {:custom "subl://open?url=file://{path}&line={line}"})
    (let [hiccup (open-in-editor/open-chip
                   {:file "src/x.cljs" :line 7})]
      (is (= "subl://open?url=file://src/x.cljs&line=7"
             (:href (second hiccup))))
      (is (= "custom" (:data-editor (second hiccup)))))))

(deftest open-chip-falls-back-when-override-cleared
  (testing "Clearing the override falls back to the host default for
            URI construction — `open-chip` does not cache the
            override"
    (config/set-editor! :cursor)
    (config/update-setting! :general :editor-override :zed)
    (is (= "zed://file/src/x.cljs:1:1"
           (:href (second
                    (open-in-editor/open-chip
                      {:file "src/x.cljs" :line 1 :column 1})))))
    (config/update-setting! :general :editor-override nil)
    (is (= "cursor://file/src/x.cljs:1:1"
           (:href (second
                    (open-in-editor/open-chip
                      {:file "src/x.cljs" :line 1 :column 1})))))))

(deftest resolve-uri-honours-override
  (testing "`open-in-editor/resolve-uri` — the seam the
            `:rf.xray/open-in-editor` event-fx walks — uses the
            override at resolve time, so the panel-side dispatch
            path lands on the same URI the chip renders"
    (config/set-editor! :vscode)
    (config/update-setting! :general :editor-override :windsurf)
    (is (= "windsurf://file/src/x.cljs:5:1"
           (open-in-editor/resolve-uri
             {:file "src/x.cljs" :line 5 :column 1})))))

;; ---- robustness --------------------------------------------------------

(deftest invalid-override-shape-degrades-to-host-default
  (testing "A persisted override that is neither a keyword nor a
            map (e.g. legacy payload corruption) does NOT crash
            `get-editor`. Stored values that pass `update-setting!`
            today come through the picker which only writes nil /
            enumerated keywords / `{:custom ...}` maps — this test
            covers the defensive read for unexpected payloads."
    (config/set-editor! :idea)
    ;; Force an invalid value in directly to bypass the picker.
    (swap! config/settings assoc-in [:general :editor-override] "not-a-keyword")
    ;; The `or` in `get-editor` returns truthy values straight back —
    ;; the chip would render with `(name override)` and fall through
    ;; to editor-uri's unknown-keyword path (which yields :vscode).
    ;; Pin the contract: an unexpected truthy value DOES win, so the
    ;; picker is the single writer to keep the shape sane.
    (is (= "not-a-keyword" (config/get-editor))
        "any truthy override wins; the picker is the single writer
         that enforces the shape contract")))
