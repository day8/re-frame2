(ns re-frame.mcp-conformance.reply-envelope-test
  "Reply-envelope vocabulary visible through MCP trace reads.
  Schemas validate representative shapes; each family source pin catches
  dropped or renamed keys independently of the other families."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [malli.core :as m]
            [malli.error :as me]
            [re-frame.mcp-conformance.fixtures :as rf.mcp-conformance.fixtures]))

(defn- work-id-tuple?
  "True when `v` is a canonical `[:rf.work/* …]` attempt-identity tuple — a
  non-empty vector whose head is an `:rf.work/`-namespaced keyword. EP-0011
  one-attempt-one-:work/id: the work-id is a family-headed tuple, never a
  scalar."
  [v]
  (and (vector? v)
       (seq v)
       (keyword? (first v))
       (= "rf.work" (namespace (first v)))))

(def ReplyEnvelopeTraceRow
  "Open trace tags carry additive reply facts beside family-specific fields.
  Work identity has one spelling: :rf.reply/work-id."
  [:map
   {:closed false}
   [:rf.reply/status      [:enum :ok :partial :error :cancelled :stale]]
   [:rf.reply/work-id     [:and :any [:fn work-id-tuple?]]]
   [:rf.reply/work-status [:enum :completed :failed :timed-out :suppressed :cancelled]]
   [:rf.reply/carried     {:optional true} [:maybe :any]]
   [:rf.reply/current     {:optional true} [:maybe :any]]
   [:rf.reply/stale-reason {:optional true} [:maybe :any]]])

(def ^:private http-stale-suppressed-fixture
  "Mirrors `re-frame.http.transport`'s `:rf.http/stale-suppressed` emit — a
  superseded HTTP request suppressed for a fresh one (carried = superseded
  work-id, current = superseding work-id)."
  {:rf.reply/status       :stale
   :rf.reply/work-status  :suppressed
   :rf.reply/stale-reason :rf.reply/correlation-mismatch
   :rf.reply/work-id      [:rf.work/http :article/by-id 1 1]
   :rf.reply/work-kind    :http
   :rf.reply/carried      [:rf.work/http :article/by-id 1 1]
   :rf.reply/current      [:rf.work/http :article/by-id 2 1]
   :recovery              :superseded-by-fresh-request
   :frame                 :app/main})

(def ^:private resource-stale-suppressed-fixture
  "Mirrors `re-frame.resources.events`'s `:rf.resource/stale-suppressed`
  emit — the bespoke resource facts PLUS the additive reply vocabulary."
  {:rf.frame/id           :app/main
   :resource/key          [:rf.scope/global :article/by-slug {:slug "w"}]
   :generation            4
   :outcome               :superseded
   :rf.reply/status       :stale
   :rf.reply/work-status  :suppressed
   :rf.reply/work-id      [:rf.work/resource [:rf.scope/global :article/by-slug {:slug "w"}] 4]
   :rf.reply/stale-reason :resource/generation-mismatch
   :rf.reply/correlation  {:generation {:carried 4 :current 5}}
   :rf.reply/carried      {:work/id [:rf.work/resource [:rf.scope/global :article/by-slug {:slug "w"}] 4] :generation 4}
   :rf.reply/current      {:work/id [:rf.work/resource [:rf.scope/global :article/by-slug {:slug "w"}] 5] :generation 5}})

(def ^:private machine-stale-suppressed-fixture
  "Mirrors `re-frame.machines.lifecycle-fx.finalize`'s `:rf.machine/done` emit
  for a stale late spawn completion (the parent instance is gone) — the
  bespoke actor facts PLUS the additive reply vocabulary, with the stale
  reason and correlation riding on the stale arm."
  {:actor-id              :auth/flow#1
   :output                nil
   :parent-id             :auth/root
   :error?                false
   :frame                 :app/main
   :rf.reply/work-kind    :machine
   :rf.reply/status       :stale
   :rf.reply/work-id      [:rf.work/machine :auth/flow#1 [:authenticating] 1]
   :rf.reply/work-status  :suppressed
   :rf.reply/stale-reason :rf.machine/actor-not-live
   :rf.reply/correlation  {:generation {:carried 1 :current nil}}})

(def ^:private all-fixtures
  {:http     http-stale-suppressed-fixture
   :resource resource-stale-suppressed-fixture
   :machine  machine-stale-suppressed-fixture})

(def ^:private family-emit-sites
  "Each fixture family's ONE production emit site, as `[rel-path head]`
  pairs naming the form whose tag map the fixture mirrors (see
  `source-form`). The resource tag map is assembled across one call — the
  bespoke facts at the family's call site, the `:rf.reply/*` facts merged on
  in the shared emitter it calls — so that family names both forms."
  {:http     [["implementation/http/src/re_frame/http/transport.cljc"
               "(defn emit-superseded-stale-trace!"]]
   :resource [["implementation/resources/src/re_frame/resources/events.cljc"
               "(defn- emit-resource-stale-suppressed!"]
              ["implementation/resources/src/re_frame/resources/reply_handlers.cljc"
               "(defn emit-stale-suppressed!"]]
   :machine  [["implementation/machines/src/re_frame/machines/lifecycle_fx/finalize.cljc"
               "(rf.trace/emit! :rf.machine :rf.machine/done"]]})

(deftest reply-envelope-fixtures-conform-to-schema
  (doseq [[family fixture] all-fixtures]
    (is (m/validate ReplyEnvelopeTraceRow fixture)
        (str family " reply-envelope trace fixture failed schema validation:\n"
             (me/humanize (m/explain ReplyEnvelopeTraceRow fixture))))))

(deftest each-fixture-key-is-emitted-as-data-at-its-family-emit-site
  (doseq [[family fixture] all-fixtures
          :let [sites (family-emit-sites family)
                text  (str/join "\n" (map #(apply rf.mcp-conformance.fixtures/source-form %) sites))]
          k (keys fixture)]
    (is (re-find (rf.mcp-conformance.fixtures/variant-regex (pr-str k)) text)
        (str family " fixture key " (pr-str k) " is not emitted as DATA at its "
             "emit site " sites " — the fixture has drifted from production"))))
