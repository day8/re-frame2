(ns re-frame2-pair-mcp.source-uri-test
  "Unit tests for the source-URI decorator (rf2-cibp8).

  Pins three properties:

  1. **Walk reach.** Every `:source-coord` map nested anywhere in the
     payload tree (top-level, inside a vector, inside a nested map,
     inside a seq) gets a sibling `:rf.source/uri` string.
  2. **URI shape.** The URI is built via
     `re-frame.source-coords.editor-uri/editor-uri`, so the scheme
     matches the configured editor (`:vscode` default, `:cursor` /
     `:idea` / etc. via `set-editor!`).
  3. **Skip-when-no-:file.** A `:source-coord` map without a `:file`
     slot (or with a blank file) produces no `:rf.source/uri` —
     editor-uri returns nil there and we omit the key rather than
     emit a `nil` slot.

  The decorator is pure data → data; tests poke `decorate` directly
  with an explicit editor and never need the live atom. The atom-read
  path is covered by an explicit `get-editor` round-trip at the head
  of the file."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame2-pair-mcp.config :as config]
            [re-frame2-pair-mcp.tools.source-uri :as source-uri]
            [re-frame2-pair-mcp.tools.wire-pipeline :as wp]))

;; ---------------------------------------------------------------------------
;; config/get-editor — atom round-trip.
;; ---------------------------------------------------------------------------

(deftest config-defaults-to-vscode
  (testing "default editor is :vscode (or whatever env-var set on this process)"
    ;; The env-var is not set in test, so the default fires.
    (is (= :vscode (config/get-editor)))))

(deftest config-set-editor-round-trips
  (testing "set-editor!/get-editor round-trips a keyword"
    (let [prior (config/get-editor)]
      (try
        (config/set-editor! :cursor)
        (is (= :cursor (config/get-editor)))
        (config/set-editor! {:custom "myide://{path}:{line}"})
        (is (= {:custom "myide://{path}:{line}"} (config/get-editor)))
        (finally
          (config/set-editor! prior))))))

(deftest config-set-editor-nil-resets-to-vscode
  (testing "set-editor! nil resets to :vscode"
    (let [prior (config/get-editor)]
      (try
        (config/set-editor! :cursor)
        (config/set-editor! nil)
        (is (= :vscode (config/get-editor)))
        (finally
          (config/set-editor! prior))))))

;; ---------------------------------------------------------------------------
;; decorate — walk reach.
;; ---------------------------------------------------------------------------

(def ^:private sample-coord
  {:ns 'app.events :file "src/app/events.cljs" :line 42 :column 7})

(def ^:private sample-coord-no-file
  {:ns 'app.events :line 42 :column 7})

(def ^:private sample-coord-blank-file
  {:ns 'app.events :file "" :line 42 :column 7})

(deftest decorate-splices-uri-on-top-level-source-coord
  (testing "a top-level :source-coord map gets a sibling :rf.source/uri"
    (let [v   {:event-id :user/login :source-coord sample-coord}
          out (source-uri/decorate v :vscode)]
      (is (= sample-coord (:source-coord out)))
      (is (= "vscode://file/src/app/events.cljs:42:7" (:rf.source/uri out))))))

(deftest decorate-respects-editor-choice
  (testing "the URI scheme matches the chosen editor"
    (let [v {:event-id :x :source-coord sample-coord}]
      (is (= "cursor://file/src/app/events.cljs:42:7"
             (:rf.source/uri (source-uri/decorate v :cursor))))
      (is (= "idea://open?file=src/app/events.cljs&line=42&column=7"
             (:rf.source/uri (source-uri/decorate v :idea))))
      (is (= "zed://file/src/app/events.cljs:42:7"
             (:rf.source/uri (source-uri/decorate v :zed))))
      (is (= "windsurf://file/src/app/events.cljs:42:7"
             (:rf.source/uri (source-uri/decorate v :windsurf)))))))

(deftest decorate-custom-editor-template
  (testing "a :custom editor template flows through to the URI"
    (let [v {:source-coord sample-coord}
          editor {:custom "myide://open?path={path}&row={line}"}]
      (is (= "myide://open?path=src/app/events.cljs&row=42"
             (:rf.source/uri (source-uri/decorate v editor)))))))

(deftest decorate-recurses-into-vectors
  (testing "source-coords inside a vector each get a URI"
    (let [v   [{:id 1 :source-coord sample-coord}
               {:id 2 :source-coord (assoc sample-coord :file "src/app/subs.cljs")}]
          out (source-uri/decorate v :vscode)]
      (is (= "vscode://file/src/app/events.cljs:42:7"
             (:rf.source/uri (nth out 0))))
      (is (= "vscode://file/src/app/subs.cljs:42:7"
             (:rf.source/uri (nth out 1)))))))

(deftest decorate-recurses-into-nested-maps
  (testing "source-coords inside a nested map slot get a URI"
    (let [v   {:epochs [{:event       [:cart/add]
                         :handler-meta {:id :cart/add
                                        :source-coord sample-coord}}]}
          out (source-uri/decorate v :vscode)
          hm  (-> out :epochs first :handler-meta)]
      (is (= "vscode://file/src/app/events.cljs:42:7" (:rf.source/uri hm))))))

(deftest decorate-recurses-into-seqs
  (testing "source-coords inside a seq each get a URI"
    (let [v   (list {:source-coord sample-coord})
          out (source-uri/decorate v :vscode)]
      (is (seq? out))
      (is (= "vscode://file/src/app/events.cljs:42:7"
             (:rf.source/uri (first out)))))))

(deftest decorate-recurses-into-sets
  (testing "source-coords inside a set each get a URI"
    (let [v   #{{:source-coord sample-coord}}
          out (source-uri/decorate v :vscode)]
      (is (set? out))
      (is (= "vscode://file/src/app/events.cljs:42:7"
             (:rf.source/uri (first out)))))))

;; ---------------------------------------------------------------------------
;; decorate — skip-when-no-:file.
;; ---------------------------------------------------------------------------

(deftest decorate-skips-source-coord-without-file
  (testing "a :source-coord without :file produces no :rf.source/uri key"
    (let [v   {:event-id :x :source-coord sample-coord-no-file}
          out (source-uri/decorate v :vscode)]
      (is (= sample-coord-no-file (:source-coord out)))
      (is (not (contains? out :rf.source/uri))))))

(deftest decorate-skips-source-coord-with-blank-file
  (testing "a :source-coord with blank :file produces no :rf.source/uri key"
    (let [v   {:event-id :x :source-coord sample-coord-blank-file}
          out (source-uri/decorate v :vscode)]
      (is (not (contains? out :rf.source/uri))))))

(deftest decorate-skips-non-map-source-coord-slot
  (testing "a non-map :source-coord slot is left alone (no URI splice)"
    ;; Some payloads carry `:source-coord nil` or a placeholder string;
    ;; the decorator MUST NOT crash and MUST NOT add a URI key.
    (let [v1 {:source-coord nil}
          v2 {:source-coord "n/a"}
          v3 {:source-coord [:not :a :map]}]
      (is (not (contains? (source-uri/decorate v1 :vscode) :rf.source/uri)))
      (is (not (contains? (source-uri/decorate v2 :vscode) :rf.source/uri)))
      (is (not (contains? (source-uri/decorate v3 :vscode) :rf.source/uri))))))

(deftest decorate-leaves-non-source-coord-maps-untouched
  (testing "maps without a :source-coord slot are returned shape-for-shape"
    (let [v {:event-id :x :payload {:y 1}}]
      (is (= v (source-uri/decorate v :vscode))))))

;; ---------------------------------------------------------------------------
;; decorate — flat-key carrier (handler-meta / frame-meta shape; rf2-87wee).
;;
;; `(rf/handler-meta kind id)` and `(rf/frame-meta id)` return registration-
;; metadata maps with flat `:ns` / `:line` / `:column` / `:file` keys (per
;; Spec-Schemas `:rf/source-coord-meta` and rf2-4h8ny). The decorator must
;; recognise this carrier shape and splice :rf.source/uri onto the map
;; itself — distinct from the trace-event :source-coord sub-map carrier.
;; ---------------------------------------------------------------------------

(deftest decorate-splices-uri-on-flat-coord-carrier-map
  (testing "a map with flat :ns/:line/:file keys (handler-meta shape) gets a :rf.source/uri"
    (let [v   {:ok? true :kind :event :id :user/login
               :doc "Sign the user in."
               :ns 'app.events :file "src/app/events.cljs" :line 42 :column 7}
          out (source-uri/decorate v :vscode)]
      (is (= "vscode://file/src/app/events.cljs:42:7" (:rf.source/uri out)))
      (is (= "Sign the user in." (:doc out)) ":doc passes through unchanged"))))

(deftest decorate-flat-coord-carrier-requires-multi-key-guard
  (testing "a map with only :file (no :ns/:line/:column) is NOT decorated"
    ;; Guards against accidental decoration of unrelated maps that carry
    ;; a :file key for some other reason (e.g. a tool arg map).
    (let [v {:file "src/app/events.cljs"}]
      (is (not (contains? (source-uri/decorate v :vscode) :rf.source/uri))))))

(deftest decorate-flat-coord-carrier-skips-blank-file
  (testing "a flat carrier with blank :file produces no :rf.source/uri"
    (let [v {:ns 'app.events :file "" :line 42 :column 7}]
      (is (not (contains? (source-uri/decorate v :vscode) :rf.source/uri))))))

(deftest decorate-nested-handler-meta-shape
  (testing "an :epochs walk through nested vectors decorates each handler-meta map"
    (let [v   {:epochs [{:event-id :a
                         :handler-meta {:ok? true :kind :event :id :a
                                        :ns 'app.events :file "src/app/events.cljs"
                                        :line 42 :column 7}}]}
          out (source-uri/decorate v :vscode)
          hm  (-> out :epochs first :handler-meta)]
      (is (= "vscode://file/src/app/events.cljs:42:7" (:rf.source/uri hm))))))

(deftest decorate-idempotent
  (testing "running the decorator twice yields the same result"
    (let [v       {:source-coord sample-coord}
          once    (source-uri/decorate v :vscode)
          twice   (source-uri/decorate once :vscode)]
      (is (= once twice)))))

(deftest decorate-overwrites-stale-rf-source-uri-when-editor-changes
  (testing "a re-decoration with a different editor replaces the URI"
    (let [v       {:source-coord sample-coord :rf.source/uri "vscode://stale"}
          out     (source-uri/decorate v :cursor)]
      (is (= "cursor://file/src/app/events.cljs:42:7" (:rf.source/uri out))))))

(deftest decorate-passes-through-scalars
  (testing "scalars (strings, numbers, keywords, nil) round-trip unchanged"
    (is (= 42      (source-uri/decorate 42 :vscode)))
    (is (= "x"     (source-uri/decorate "x" :vscode)))
    (is (= :foo    (source-uri/decorate :foo :vscode)))
    (is (= nil     (source-uri/decorate nil :vscode)))
    (is (= true    (source-uri/decorate true :vscode)))))

;; ---------------------------------------------------------------------------
;; Integration — run-wire-pipeline splices URIs on every kind.
;; ---------------------------------------------------------------------------

(defn- with-editor [editor f]
  (let [prior (config/get-editor)]
    (try
      (config/set-editor! editor)
      (f)
      (finally
        (config/set-editor! prior)))))

(deftest pipeline-epoch-vector-decorates-source-coords
  (testing "run-wire-pipeline :epoch-vector splices :rf.source/uri onto epoch source-coords"
    (with-editor :vscode
      #(let [epochs [{:event-id :a :source-coord sample-coord}
                     {:event-id :b
                      :source-coord (assoc sample-coord :file "src/app/subs.cljs")}]
             {:keys [value]} (wp/run-wire-pipeline epochs
                                                   {:kind   :epoch-vector
                                                    :incl?  false
                                                    :mode   :diff
                                                    :dedup? false})]
         (is (= "vscode://file/src/app/events.cljs:42:7"
                (:rf.source/uri (nth value 0))))
         (is (= "vscode://file/src/app/subs.cljs:42:7"
                (:rf.source/uri (nth value 1))))))))

(deftest pipeline-scalar-value-decorates-source-coord
  (testing "run-wire-pipeline :scalar-value walks the scalar tree and splices URIs"
    (with-editor :vscode
      #(let [v {:registered :user/login :source-coord sample-coord}
             {:keys [value]} (wp/run-wire-pipeline v
                                                   {:kind          :scalar-value
                                                    :server-elided 0})]
         (is (= "vscode://file/src/app/events.cljs:42:7"
                (:rf.source/uri value)))))))

(deftest pipeline-respects-live-editor
  (testing "the pipeline reads the live editor preference at decoration time"
    (with-editor :cursor
      #(let [v {:source-coord sample-coord}
             {:keys [value]} (wp/run-wire-pipeline v
                                                   {:kind          :scalar-value
                                                    :server-elided 0})]
         (is (= "cursor://file/src/app/events.cljs:42:7"
                (:rf.source/uri value)))))))
