(ns re-frame2-pair-mcp.egress-elision-test
  "Form-level pins for off-box redaction on the pull-mode tools.

  Redaction runs app-side, inside the eval form each tool ships over
  nREPL: `re-frame.core/project-egress` reads the frame's runtime-db
  elision registry, which exists only in the live app. So these tests
  capture the form the real tool sends and assert that every egressed
  value crosses the door under the right `:rf.egress/*` boundary, with
  the `--allow-sensitive-reads` gate off (the default) and on. What the
  door does with those opts is pinned in `implementation/core`."
  (:require [cljs.test :refer-macros [deftest is async use-fixtures]]
            [clojure.string :as str]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.test-utils :as tu]
            [re-frame2-pair-mcp.tools.list-subscriptions :as ls]
            [re-frame2-pair-mcp.tools.raw-state :as raw-state]
            [re-frame2-pair-mcp.tools.record :as record]
            [re-frame2-pair-mcp.tools.snapshot :as snap]
            [re-frame2-pair-mcp.tools.trace-window :as tw]
            [re-frame2-pair-mcp.tools.watch-epochs :as we]
            [re-frame2-pair-mcp.tools.watch-until :as watch-until]))

;; The gate and the in-flight raw-state signal map are process-global.
(use-fixtures :each
  {:before (fn [] (raw-state/reset-runtime-signal-cache!))
   :after  (fn []
             (raw-state/set-allow-raw-state! false)
             (raw-state/reset-runtime-signal-cache!))})

(defn- with-capture!
  "Run `body-fn` against a stub runtime that passes the probe, accepts the
  raw-state signal, and answers every other form with `canned`, recording
  it in `forms`."
  [forms canned body-fn]
  (let [orig nrepl/cljs-eval-value
        answer (fn [form-str]
                 (cond
                   (str/includes? form-str "__re_frame2_pair_runtime")
                   (js/Promise.resolve true)

                   (str/includes? form-str "configure-raw-state!")
                   (js/Promise.resolve nil)

                   :else
                   (do (swap! forms conj form-str)
                       (js/Promise.resolve canned))))
        stub (fn
               ([_conn _build-id form-str]       (answer form-str))
               ([_conn _build-id form-str _opts] (answer form-str)))]
    (set! nrepl/cljs-eval-value stub)
    (-> (js/Promise.resolve nil)
        (.then (fn [_] (body-fn)))
        (.finally (fn [] (tu/restore-eval! stub orig))))))

(defn- form-of
  "Promise of the first form `tool` ships for `args` with the
  sensitive-reads gate at `gate?`."
  [gate? tool canned args]
  (raw-state/set-allow-raw-state! gate?)
  (let [forms (atom [])]
    (-> (with-capture! forms canned (fn [] (tool nil (tu/args->js args))))
        (.then (fn [_] (first @forms))))))

(defn- check-forms
  "Run each `[label gate? tool canned args has lacks]` row in turn: the form
  the tool ships must contain every `has` needle and no `lacks` needle."
  [rows done]
  (-> (reduce (fn [p [label gate? tool canned args has lacks]]
                (-> p
                    (.then (fn [_] (form-of gate? tool canned args)))
                    (.then (fn [form]
                             (doseq [n has]
                               (is (str/includes? form n) (str label ": missing " n)))
                             (doseq [n lacks]
                               (is (not (str/includes? form n)) (str label ": carries " n)))))))
              (js/Promise.resolve nil)
              rows)
      (.catch (fn [e] (is false (str "tool call failed: " e))))
      (.then (fn [_] (done)))))

;; ---------------------------------------------------------------------------
;; Epoch records. `:include-sensitive` is two-key (launch gate AND per-call
;; arg) and threads THROUGH the door, lifting only the app-db sensitive
;; axis; it is never a raw bypass, and the gate alone changes nothing.
;; ---------------------------------------------------------------------------

(def ^:private off-box "{:rf.egress/profile :rf.egress/off-box-tool}")

(def ^:private off-box+sensitive
  "{:rf.egress/profile :rf.egress/off-box-tool :rf.egress/include-sensitive? true}")

(defn- projected
  "Needles proving each record is checked for its `:kind :rf/epoch-record`
  stamp (GUARD G3: the door would bare-walk an unstamped record from
  `:path []` and ship it raw) and then reaches the door with exactly `opts`."
  [opts]
  ["mapv (fn [r#] (when-not (= :rf/epoch-record (:kind r#))"
   ":rf.error/pair-mcp-unstamped-epoch-record"
   (str "(re-frame.core/project-egress r# " opts ")")])

(def ^:private epoch-canned
  {:epochs [] :matches [] :id-aged-out? false :requested-id nil :head-id nil
   :next-id nil :history-count 0 :since-count 0 :remaining 0})

(deftest epoch-records-cross-the-guarded-door
  (async done
    (check-forms
      [["trace-window, gate off" false tw/trace-window-tool epoch-canned
        {:ms 1000} (projected off-box)]
       ["trace-window, gate on alone" true tw/trace-window-tool epoch-canned
        {:ms 1000} (projected off-box)]
       ["trace-window, gate on + include-sensitive" true tw/trace-window-tool epoch-canned
        {:ms 1000 :include-sensitive true} (projected off-box+sensitive)]
       ["watch-epochs, gate off" false we/watch-epochs-tool epoch-canned
        {:pred {:event-id :auth/sign-in}} (projected off-box)]
       ["watch-epochs, gate on alone" true we/watch-epochs-tool epoch-canned
        {} (projected off-box)]
       ["watch-epochs, gate on + include-sensitive" true we/watch-epochs-tool epoch-canned
        {:include-sensitive true} (projected off-box+sensitive)]]
      done)))

;; ---------------------------------------------------------------------------
;; snapshot. The client-side scrub only drops whole sensitive epochs, so the
;; `:epochs` slice crosses the same guarded door. `:app-db` walks whole and
;; `:sub-cache` per entry, threading each entry's query-v so a route read sub
;; re-seeds at its storage position; the runtime-db `:machines` slice is
;; redacted whole unless the sensitive opt-in is given.
;; ---------------------------------------------------------------------------

(def ^:private snapshot-canned
  {:value {:rf/default {:app-db {} :epochs []}} :elided-count 0 :tool-frames-excluded []})

(defn- full-epochs
  "snapshot args expanding the `:epochs` slice. `:frames` / `:include`
  arrive as JSON arrays over MCP."
  [extra]
  (merge {:frames #js [":rf/default"] :include #js ["epochs"] :mode "full"} extra))

(deftest snapshot-slices-cross-the-door
  (async done
    (check-forms
      [["gate off" false snap/snapshot-tool snapshot-canned (full-epochs {})
        (into (projected off-box)
              ["snapshot-state {:frames [:rf/default], :include [:epochs]}"
               "(update fmap :app-db f)"
               "(re-frame.core/project-egress v (assoc opts :query-v qv))"
               "(assoc fmap :machines :rf/redacted)"])]
       ["gate on alone" true snap/snapshot-tool snapshot-canned (full-epochs {})
        (projected off-box)]
       ["gate on + include-sensitive" true snap/snapshot-tool snapshot-canned
        (full-epochs {:include-sensitive true})
        (projected off-box+sensitive)
        [":machines :rf/redacted"]]
       ["gate on + elision false" true snap/snapshot-tool snapshot-canned
        (full-epochs {:include #js ["app-db" "sub-cache" "epochs"] :elision false})
        (conj (projected off-box) ":rf.egress/include-large? true")]]
      done)))

(deftest snapshot-default-scope-names-the-tool-frames-it-dropped
  (async done
    (check-forms
      [["default :app scope" false snap/snapshot-tool snapshot-canned
        {:include #js ["app-db"]}
        [":tool-frames-excluded (filterv re-frame2-pair.runtime/reserved-tool-frame? (re-frame.core/frame-ids))"]]
       ["frames all" false snap/snapshot-tool snapshot-canned
        {:frames "all" :include #js ["app-db"]}
        [":tool-frames-excluded []"]]]
      done)))

;; ---------------------------------------------------------------------------
;; Sampled values: list-subscriptions `:include-values`, record and
;; watch-until samples. A bare `:elision false` only overlays the large
;; inclusion on the off-box-tool floor; only the two-key sensitive opt-in
;; names local-raw, and even then the door is called.
;; ---------------------------------------------------------------------------

(def ^:private sub-cache-canned
  {:ok? true :frame :rf/default :count 1
   :subs [{:query-v ["auth-token"] :value "raw-from-runtime" :ref-count 1}]})

(def ^:private record-canned
  {:ok? true :recording-id "rec-x" :signals [{:app-db [:auth :token]}]
   :frame :rf/default :stop {:ms 30000}})

(def ^:private poll-canned {:held? true :sample {0 :rf/redacted} :t 1})

(deftest sampled-values-cross-the-door-under-the-named-boundary
  (async done
    (check-forms
      [["list-subscriptions, gate off" false ls/list-subscriptions-tool sub-cache-canned
        {:frame "app/other" :include-values true}
        ["/sub-cache-info {:frame :app/other, :include-values? true})"
         "(merge {:frame :app/other :query-v (:query-v entry)}"
         "re-frame.core/project-egress"
         ":rf.egress/profile :rf.egress/off-box-tool"
         "(if (and (map? res) (vector? (:subs res)))"]]
       ["list-subscriptions, gate on + elision false" true ls/list-subscriptions-tool sub-cache-canned
        {:include-values true :elision false}
        ["re-frame.core/project-egress"
         ":rf.egress/profile :rf.egress/off-box-tool"
         ":rf.egress/include-large? true"]]
       ["list-subscriptions, gate on + elision false + include-sensitive" true
        ls/list-subscriptions-tool sub-cache-canned
        {:include-values true :elision false :include-sensitive true}
        ["re-frame.core/project-egress" ":rf.egress/profile :rf.egress/local-raw"]]
       ["record, gate off" false record/record-tool record-canned
        {:signals "[{:app-db [:auth :token]}]" :stop "{:ms 1000}"}
        [":elide-opts" ":rf.egress/profile :rf.egress/off-box-tool"]]
       ["record, gate on + include-sensitive" true record/record-tool record-canned
        {:signals "[{:app-db [:auth :token]}]" :stop "{:ms 1000}" :include-sensitive true}
        [":rf.egress/profile :rf.egress/local-raw"]]
       ["watch-until, gate off" false watch-until/watch-until-tool poll-canned
        {:signals "[{:app-db [:auth :token]}]" :pred #js {:signal 0 :changed true}}
        ["re-frame2-pair.runtime/sample-signals" ":rf.egress/profile :rf.egress/off-box-tool"]]
       ["watch-until, gate on + include-sensitive" true watch-until/watch-until-tool poll-canned
        {:signals "[{:app-db [:auth :token]}]" :pred #js {:signal 0 :changed true}
         :include-sensitive true}
        [":rf.egress/profile :rf.egress/local-raw"]]]
      done)))
