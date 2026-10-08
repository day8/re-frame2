(ns day8.re-frame2-xray.views.edn-inspector-default-formatters-cljs-test
  "The default `IXrayEdnInspector` formatters: `format-relative`'s buckets,
  the uuid and inst renders through the protocol seam, and a consumer's
  `extend-type` taking precedence over a default."
  (:require [cljs.test :refer-macros [are deftest is use-fixtures]]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [day8.re-frame2-xray.views.edn-inspector :as ei]
            [day8.re-frame2-xray.views.edn-inspector-default-formatters
             :as ddf]
            [day8.re-frame2-xray.views.edn-inspector-protocol
             :refer [IXrayEdnInspector]]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

;; ---- helpers ------------------------------------------------------------

(defn- walk-hiccup
  "Depth-first collect every hiccup vector in `tree`."
  [tree]
  (let [out (atom [])]
    (letfn [(walk [node]
              (cond
                (vector? node)
                (do (swap! out conj node)
                    (doseq [child (rest node)] (walk child)))
                (seq? node) (doseq [c node] (walk c))))]
      (walk tree))
    @out))

(defn- find-attr
  [tree k v]
  (->> (walk-hiccup tree)
       (filter (fn [n]
                 (and (vector? n)
                      (map? (second n))
                      (= v (get (second n) k)))))
       first))

(defn- collect-text
  [tree]
  (let [out (atom [])]
    (letfn [(walk [node]
              (cond
                (string? node) (swap! out conj node)
                (vector? node) (doseq [c (rest node)] (walk c))
                (seq? node)    (doseq [c node] (walk c))))]
      (walk tree))
    (apply str @out)))

(defn- render-at-root
  [v]
  (ei/render-node {:value         v
                   :panel-id      :test
                   :mount-id      "m"
                   :path          []
                   :depth         0
                   :expansion-map {}
                   :opts          {}}))

;; =========================================================================
;; format-relative — pure-data bucketing
;; =========================================================================

(def ^:private epoch-2026 1764547200000) ;; 2025-12-01T00:00:00.000Z

(defn- ms-ago [now ms] (js/Date. (- now ms)))
(defn- ms-future [now ms] (js/Date. (+ now ms)))

(deftest format-relative-buckets
  ;; Anything under 5s collapses to `just now`; a future instant reads
  ;; `in N…`; past 30 days an ISO date is more honest than `247d ago`.
  (are [expected d] (= expected (ddf/format-relative d epoch-2026))
    "just now"   (js/Date. epoch-2026)
    "59s ago"    (ms-ago epoch-2026 59999)
    "in 10s"     (ms-future epoch-2026 10000)
    "1m ago"     (ms-ago epoch-2026 (* 60 1000))
    "59m ago"    (ms-ago epoch-2026 (* 59 60 1000))
    "in 3m"      (ms-future epoch-2026 (* 3 60 1000))
    "1h ago"     (ms-ago epoch-2026 (* 60 60 1000))
    "23h ago"    (ms-ago epoch-2026 (* 23 60 60 1000))
    "in 2h"      (ms-future epoch-2026 (* 2 60 60 1000))
    "1d ago"     (ms-ago epoch-2026 (* 24 60 60 1000))
    "29d ago"    (ms-ago epoch-2026 (* 29 24 60 60 1000))
    "in 7d"      (ms-future epoch-2026 (* 7 24 60 60 1000))
    "2025-10-02" (ms-ago epoch-2026 (* 60 24 60 60 1000))))

;; =========================================================================
;; UUID
;; =========================================================================

(def ^:private sample-uuid (uuid "6ba7b810-9dad-11d1-80b4-00c04fd430c8"))

(deftest uuid-body-rendered-when-expanded
  ;; A mounted uuid takes the protocol path, which renders open: the
  ;; compact header (full form on hover) and the full-form body together.
  (let [h      (render-at-root sample-uuid)
        header (find-attr h :data-rf-default-fmt "uuid")]
    (is (= ["#uuid \"…4fd430c8\""
            "#uuid \"6ba7b810-9dad-11d1-80b4-00c04fd430c8\""
            "#uuid \"6ba7b810-9dad-11d1-80b4-00c04fd430c8\""]
           [(collect-text header)
            (:title (second header))
            (collect-text (find-attr h :data-rf-default-fmt-body "uuid"))]))))

;; =========================================================================
;; inst (js/Date)
;; =========================================================================

(deftest inst-header-relative-time
  (let [h (ddf/render-inst-header (ms-ago epoch-2026 (* 3 60 1000)) epoch-2026)]
    (is (= ["#inst \"3m ago\"" "2025-11-30T23:57:00.000Z"]
           [(collect-text h) (:title (second h))]))))

(deftest inst-body-rendered-when-expanded
  ;; A mounted js/Date takes the protocol path, and its ISO body renders open.
  (is (= "#inst \"2025-12-01T00:00:00.000Z\""
         (collect-text (find-attr (render-at-root (js/Date. epoch-2026))
                                  :data-rf-default-fmt-body "inst")))))

;; =========================================================================
;; Consumer override wins — CLJS protocol-dispatch precedence
;; =========================================================================

(deftest consumer-extend-type-wins-over-the-uuid-default
  ;; A consumer's `extend-type` on `cljs.core/UUID` itself — the type the
  ;; bundled default already extends — replaces the default. The extension
  ;; is process-wide, so the prototype is snapshotted and put back.
  (let [proto (.-prototype cljs.core/UUID)
        saved (js/Object.assign #js {} proto)]
    (try
      (extend-type cljs.core/UUID
        IXrayEdnInspector
        (-xray-render-header [_ _opts]
          [:span {:data-testid "consumer-uuid-header"} "consumer-uuid"])
        (-xray-render-body [_ _opts] nil))
      (is (some? (find-attr (render-at-root (random-uuid))
                            :data-testid "consumer-uuid-header")))
      (finally
        (js/Object.assign proto saved)))))
