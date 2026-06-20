(ns re-frame2-pair-mcp.discover-app-reserved-frame-test
  "Pin discover-app's reserved-frame-aware operating-frame resolution.

  CONTEXT: any app instrumented with Xray carries a `:rf/xray` TOOL frame
  alongside its one app frame. The ambiguity logic excludes reserved
  `:rf/*` tool frames from the count, so an Xray-instrumented app (the
  common pairing case) with exactly ONE app frame is NOT reported
  `:ambiguous-frame` on discover-app — no `frames/select` + retry tax for
  a choice that doesn't exist.

  CONTRACT these tests pin (the runtime's `health` computes the
  reserved-frame-aware `:ambiguous-frame?` + `:app-frames`; discover-app
  shapes them):

   1. Single app frame (`:rf/default`) + `:rf/xray` tool frame is NOT
      ambiguous — discover-app returns the healthy success payload with
      the operating frame ECHOED and a note that the tool frame was
      excluded and the app frame auto-resolved (no `frames/select` tax).
   2. Two app frames + `:rf/xray` IS still ambiguous — the ambiguity
      note names the APP frames (the real choices), not the tool frame.

  `:rf/default` is the universal default APP frame (Conventions.md §The
  single-root reserved set) and is NEVER excluded, despite sharing the
  `:rf/*` root."
  (:require [cljs.test :refer-macros [deftest is async]]
            [clojure.string :as str]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.tools.discover-app :as discover-app]
            [re-frame2-pair-mcp.test-utils :as tu]))

(defn- fresh-conn []
  (let [conn (nrepl/make-conn 0 "127.0.0.1")]
    (swap! conn assoc :probed-builds #{} :resolved-build-id nil)
    conn))

(defn- prime! [conn build-id]
  (swap! conn update :probed-builds (fnil conj #{}) build-id))

;; A single-app-plus-Xray health payload: one app frame (:rf/default), one
;; :rf/* tool frame (:rf/xray). The runtime's reserved-frame-aware
;; `health` reports NOT ambiguous and surfaces the app-frame view +
;; resolved operating frame.
(def ^:private single-app-plus-xray-health
  {:ok?                        true
   :debug-enabled?             true
   :coord-annotation-enabled?  true
   :frames                     [:rf/default :rf/xray]
   :app-frames                 [:rf/default]
   :operating-frame            :rf/default
   :ambiguous-frame?           false})

;; Two genuine app frames plus a tool frame: still ambiguous.
(def ^:private two-app-frames-plus-xray-health
  {:ok?                        true
   :debug-enabled?             true
   :coord-annotation-enabled?  true
   :frames                     [:rf/default :stories :rf/xray]
   :app-frames                 [:rf/default :stories]
   :operating-frame            nil
   :ambiguous-frame?           true})

(deftest single-app-plus-xray-is-not-ambiguous
  ;; The headline case: an Xray-instrumented single-app session resolves
  ;; unambiguously — no :ambiguous-frame warning, the operating frame is
  ;; echoed, and the note explains the auto-resolution + the excluded
  ;; tool frame.
  (async done
    (let [conn (fresh-conn)
          _    (prime! conn :examples/my-app)
          args (tu/args->js {:build "examples/my-app"})]
      (-> (tu/with-stubbed-freshness! {:compile-cycle 1 :build-flushed-at 100
                                       :runtime-count 1 :heartbeat-age-ms 50}
            (fn []
              (tu/with-stubbed-eval! single-app-plus-xray-health
                (fn [] (discover-app/discover-app conn args)))))
          (.then
            (fn [result]
              (let [edn  (tu/extract-edn result)
                    text (tu/extract-text result)]
                (is (true? (:ok? edn)))
                (is (not= :ambiguous-frame (:warning edn))
                    "single-app + :rf/xray must NOT warn :ambiguous-frame")
                (is (= :rf/default (:operating-frame edn))
                    "the resolved operating frame is echoed (never a guess)")
                (is (str/includes? (:note edn) ":rf/default")
                    "the note echoes the auto-resolved app frame")
                (is (str/includes? (:note edn) ":rf/xray")
                    "the note names the excluded :rf/* tool frame")
                (is (str/includes? text "auto-resolved")
                    "the canonical text states the frame auto-resolved"))
              (done)))))))

(deftest two-app-frames-plus-xray-stays-ambiguous
  ;; Genuine ambiguity survives: two app frames are a real choice. The
  ;; ambiguity note names the APP frames, not the excluded tool frame.
  (async done
    (let [conn (fresh-conn)
          _    (prime! conn :examples/multi)
          args (tu/args->js {:build "examples/multi"})]
      (-> (tu/with-stubbed-freshness! {:compile-cycle 1 :build-flushed-at 100
                                       :runtime-count 1 :heartbeat-age-ms 50}
            (fn []
              (tu/with-stubbed-eval! two-app-frames-plus-xray-health
                (fn [] (discover-app/discover-app conn args)))))
          (.then
            (fn [result]
              (let [edn (tu/extract-edn result)]
                (is (= :ambiguous-frame (:warning edn))
                    "two app frames is genuinely ambiguous")
                (is (str/includes? (:note edn) ":stories")
                    "the note names the candidate APP frames")
                (is (str/includes? (:note edn) ":rf/default")
                    "...both of them")
                ;; The note must frame the choice as APP frames and flag
                ;; that tool frames were excluded.
                (is (str/includes? (:note edn) "app frames")
                    "the note frames the choice as APP frames")
                (is (str/includes? (:note edn) "tool frames are excluded")
                    "the note states :rf/* tool frames were excluded"))
              (done)))))))
