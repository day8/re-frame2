(ns day8.re-frame2-xray.settings.editor-override-cljs-test
  "CLJS tests for the end-user editor override — the per-machine
  `[:general :editor-override]` slot `config/get-editor` consults before
  the host's `:rf.xray/editor` atom, without ever mutating that atom.

  Covers the resolution order, the localStorage round-trip of the
  map-valued override, its validation on read, and the `open-chip`
  consumer."
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
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

;; ---- resolution order --------------------------------------------------

(deftest get-editor-falls-back-when-override-cleared
  ;; The override wins over the host default without touching the host
  ;; atom; clearing it (writing nil) walks back to the host default.
  (config/set-editor! :idea)
  (config/update-setting! :general :editor-override :cursor)
  (is (= [:cursor :idea]
         [(config/get-editor) (config/get-host-editor-default)]))
  (config/update-setting! :general :editor-override nil)
  (is (= :idea (config/get-editor))))

;; ---- localStorage round-trip ------------------------------------------
;;
;; The write-through and reload are the generic `update-setting!` → storage →
;; `load-settings-from-storage!` path `persistence-cljs-test`'s
;; `text-size-round-trips` pins, and `reset-clears-everything` pins the
;; reset. The row here owns the map-valued `{:custom <tpl>}` shape.

(deftest custom-override-round-trips
  (config/update-setting! :general :editor-override
                          {:custom "emacsclient://{path}:{line}"})
  (reset! config/settings config/default-settings)
  (config/load-settings-from-storage!)
  (is (= {:custom "emacsclient://{path}:{line}"}
         (config/get-setting :general :editor-override))))

;; ---- consumer: open-chip ----------------------------------------------

(deftest open-chip-custom-override-substitutes-template
  ;; A `{:custom <tpl>}` override drives the chip's URI and its
  ;; `:data-editor`, and a non-forbidden custom scheme passes through.
  (config/update-setting! :general :editor-override
                          {:custom "subl://open?url=file://{path}&line={line}"})
  (let [[_ attrs] (open-in-editor/open-chip {:file "src/x.cljs" :line 7})]
    (is (= ["subl://open?url=file://src/x.cljs&line=7" "custom"]
           [(:href attrs) (:data-editor attrs)]))))

;; ---- robustness --------------------------------------------------------

(deftest invalid-override-shape-degrades-to-host-default
  ;; `get-editor` filters the slot through `valid-editor-override?`, so a
  ;; corrupt persisted value degrades to the host default and never
  ;; reaches the URI builder.
  (config/set-editor! :idea)
  (doseq [bad [:unknown-editor {:custom ""} {:custom 42}]]
    (swap! config/settings assoc-in [:general :editor-override] bad)
    (is (= :idea (config/get-editor)) (pr-str bad))))
