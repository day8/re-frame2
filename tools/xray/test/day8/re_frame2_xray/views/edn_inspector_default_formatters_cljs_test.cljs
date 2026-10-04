(ns day8.re-frame2-xray.views.edn-inspector-default-formatters-cljs-test
  "Unit tests for the default `IXrayEdnInspector` formatters.

  ## What's under test

  1. **`format-relative` bucketing** — `just now`, `Ns ago`, `Nm ago`,
     `Nh ago`, `Nd ago`, ISO-date for older than 30 days, and the
     symmetric `in N…` future-tense buckets.
  2. **UUID header + body** — compact `…last8` collapsed header,
     full canonical body, title attribute on header carries the full
     form so hover reveals the long uuid.
  3. **Inst (js/Date) header + body** — relative-time header against
     a pinned `now`, ISO body, title carries the full ISO.
  4. **Render-node integration** — mounting `[edn-inspector some-uuid]`
     routes through the protocol path (asserted via
     `:data-rf-protocol \"1\"`); same for inst.
  5. **Consumer override wins** — a consumer's `extend-type` against
     the same type takes precedence (CLJS protocol-dispatch contract).
     This is the regression-anchor for the precedence guarantee in
     the namespace docstring.

  Pure-data unit tests; no DOM mount."
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
    "just now" (js/Date. epoch-2026)
    "just now" (ms-ago epoch-2026 2000)
    "10s ago"  (ms-ago epoch-2026 10000)
    "59s ago"  (ms-ago epoch-2026 59999)
    "in 10s"   (ms-future epoch-2026 10000)
    "1m ago"   (ms-ago epoch-2026 (* 60 1000))
    "59m ago"  (ms-ago epoch-2026 (* 59 60 1000))
    "in 3m"    (ms-future epoch-2026 (* 3 60 1000))
    "1h ago"   (ms-ago epoch-2026 (* 60 60 1000))
    "23h ago"  (ms-ago epoch-2026 (* 23 60 60 1000))
    "in 2h"    (ms-future epoch-2026 (* 2 60 60 1000))
    "1d ago"   (ms-ago epoch-2026 (* 24 60 60 1000))
    "29d ago"  (ms-ago epoch-2026 (* 29 24 60 60 1000))
    "in 7d"    (ms-future epoch-2026 (* 7 24 60 60 1000)))
  (let [s (ddf/format-relative (ms-ago epoch-2026 (* 60 24 60 60 1000)) epoch-2026)]
    (is (re-find #"^\d{4}-\d{2}-\d{2}$" s)
        (str "older than 30d falls to ISO date; got: " s))))

;; =========================================================================
;; UUID — header + body
;; =========================================================================

(def ^:private sample-uuid (random-uuid))

(deftest uuid-header-compact-tail
  (let [h (ddf/render-uuid-header sample-uuid)
        text (collect-text h)
        attrs (second h)]
    (is (re-find #"#uuid \"…[0-9a-f]{8}\"" text)
        (str "compact header shows `#uuid \"…<8>\"`; got: " text))
    (is (= (str "#uuid \"" sample-uuid "\"") (:title attrs))
        "title attribute carries the full canonical form")
    (is (= "uuid" (get attrs :data-rf-type)))
    (is (= "uuid" (get attrs :data-rf-default-fmt)))))

(deftest uuid-body-shows-full-form
  (let [b (ddf/render-uuid-body sample-uuid)
        text (collect-text b)]
    (is (re-find (re-pattern (str "#uuid \"" sample-uuid "\"")) text)
        "expanded body shows the full canonical uuid")))

(deftest uuid-body-rendered-when-expanded
  ;; A mounted uuid routes through the protocol path rather than the
  ;; built-in :uuid scalar. Default-expanded? on protocol nodes is true
  ;; (see render-protocol-node), so the body container appears without
  ;; operator interaction.
  (let [h (ei/render-node {:value sample-uuid
                           :panel-id :test
                           :mount-id "m2"
                           :path []
                           :depth 0
                           :expansion-map {}
                           :opts {}})]
    (is (some? (find-attr h :data-rf-protocol "1"))
        "uuid goes through the protocol path, not the built-in :uuid scalar")
    (is (some? (find-attr h :data-rf-default-fmt "uuid"))
        "default formatter's header is rendered")
    (is (re-find #"#uuid \"…[0-9a-f]{8}\"" (collect-text h))
        "compact form appears in rendered output")
    (is (some? (find-attr h :data-rf-default-fmt-body "uuid"))
        "uuid body container rendered when expanded")
    (is (re-find (re-pattern (str sample-uuid)) (collect-text h))
        "full uuid appears in body text")))

;; =========================================================================
;; inst (js/Date) — header + body
;; =========================================================================

(deftest inst-header-relative-time
  (let [now epoch-2026
        d   (ms-ago now (* 3 60 1000))   ;; 3 min ago
        h   (ddf/render-inst-header d now)
        attrs (second h)
        text  (collect-text h)]
    (is (= "#inst \"3m ago\"" text)
        (str "header renders #inst-style relative time; got: " text))
    (is (= (.toISOString d) (:title attrs))
        "title attribute carries the full ISO")
    (is (= "inst" (get attrs :data-rf-type)))
    (is (= "inst" (get attrs :data-rf-default-fmt)))))

(deftest inst-body-shows-iso
  (let [d (js/Date. epoch-2026)
        b (ddf/render-inst-body d)
        text (collect-text b)]
    (is (re-find (re-pattern (str "#inst \"" (.toISOString d) "\"")) text)
        "body shows the full ISO-8601 form")))

(deftest inst-body-rendered-when-expanded
  ;; A mounted js/Date routes through the protocol path, and its body
  ;; renders default-expanded.
  (let [d (js/Date. epoch-2026)
        h (ei/render-node {:value d
                           :panel-id :test
                           :mount-id "m4"
                           :path []
                           :depth 0
                           :expansion-map {}
                           :opts {}})]
    (is (some? (find-attr h :data-rf-protocol "1"))
        "inst goes through the protocol path")
    (is (some? (find-attr h :data-rf-default-fmt "inst"))
        "default formatter's inst header rendered")
    (is (some? (find-attr h :data-rf-default-fmt-body "inst"))
        "inst body container rendered when expanded")
    (is (re-find #"#inst \"\d{4}-\d{2}-\d{2}T" (collect-text h))
        "ISO form appears in body text")))

;; =========================================================================
;; Consumer override wins — CLJS protocol-dispatch precedence
;; =========================================================================

(deftest consumer-extend-type-wins-over-the-uuid-default
  ;; A consumer's `extend-type` on `cljs.core/UUID` itself — the type the
  ;; bundled default already extends — replaces the default, and the
  ;; widget renders the consumer's header. The extension is process-wide,
  ;; so the prototype is snapshotted first and put back afterwards.
  (let [proto  (.-prototype cljs.core/UUID)
        saved  (js/Object.assign #js {} proto)
        render (fn [] (ei/render-node {:value         (random-uuid)
                                       :panel-id      :test
                                       :mount-id      "m6"
                                       :path          []
                                       :depth         0
                                       :expansion-map {}
                                       :opts          {}}))]
    (try
      (extend-type cljs.core/UUID
        IXrayEdnInspector
        (-xray-render-header [_ _opts]
          [:span {:data-testid "consumer-uuid-header"} "consumer-uuid"])
        (-xray-render-body [_ _opts] nil))
      (let [h (render)]
        (is (some? (find-attr h :data-testid "consumer-uuid-header"))
            "the consumer's extension renders the uuid's header")
        (is (nil? (find-attr h :data-rf-default-fmt "uuid"))
            "and the default uuid formatter does not"))
      (finally
        (js/Object.assign proto saved)))
    (is (some? (find-attr (render) :data-rf-default-fmt "uuid"))
        "the default is back once the consumer's extension is withdrawn")))
