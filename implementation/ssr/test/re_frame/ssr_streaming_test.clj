(ns re-frame.ssr-streaming-test
  "Streaming SSR — `:rf/suspense-boundary` walker, continuation drain,
  failure semantics, per-subtree hydration delta. Per Spec 011 §Streaming
  SSR (rf2-ojakd / rf2-olb64 (a))."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.late-bind :as late-bind]
            [re-frame.ssr :as ssr]
            [re-frame.ssr.html-helpers :as html]
            [re-frame.ssr.streaming :as streaming]
            [re-frame.ssr.streaming.constants :as wire]
            [re-frame.ssr.test-fixture :as tf]
            [re-frame.trace :as trace]))

(defn- reset+reg-test-handlers
  "Reset the runtime via the canonical fixture, then re-register the
  test-local event handlers that the fixture's `clear-all!` step wiped."
  [test-fn]
  (tf/reset-runtime
    (fn []
      (rf/reg-event :rf.test/noop     (fn [{:keys [db]} _] {:db db}))
      (rf/reg-event :rf.test/seed-db  (fn [{:keys [db]} [_ new-db]] {:db new-db}))
      (test-fn))))

(use-fixtures :each reset+reg-test-handlers)

(defn- with-trace-capture
  "Capture every emitted trace event into `coll-atom` during `(body-fn)`.
  Removes the listener on exit so test isolation holds."
  [coll-atom body-fn]
  (let [k (str (gensym "streaming-test-cb"))]
    (trace/register-listener! k (fn [ev] (swap! coll-atom conj ev)))
    (try (body-fn)
         (finally
           (trace/unregister-listener! k)))))

(rf/reg-event :rf.test/noop (fn [{:keys [db]} _] {:db db}))
(rf/reg-event :rf.test/seed-db (fn [{:keys [db]} [_ new-db]] {:db new-db}))

(defn- make-frame
  "Register a per-request server frame and seed its app-db via an
  `:initial-events` setup event so the value lands inside the frame's
  container, not on :rf/default."
  [{:keys [db on-create]}]
  (let [fid (keyword "rf.frame" (str (gensym "")))]
    (rf/reg-frame fid
      {:doc       "streaming-test frame"
       :platform  :server
       :initial-events [(or on-create
                            (if db
                              [:rf.test/seed-db db]
                              [:rf.test/noop]))]})
    fid))

(deftest render-shell-emits-fallback-template
  (testing "Shell walk emits a `<template>` fallback at the boundary and registers a continuation"
    (let [tree   [:div
                  [:h1 "Header"]
                  [:rf/suspense-boundary
                   {:id :test/comments :fallback [:p "Loading…"]}
                   [:section.comments "Body"]]
                  [:footer "Footer"]]
          {:keys [shell-html continuations]} (streaming/render-shell tree)]
      (is (= 1 (count continuations)) "one continuation registered")
      (is (= :test/comments (-> continuations first :id)) "id propagates")
      (is (str/includes? shell-html "<h1>Header</h1>") "shell content above boundary preserved")
      (is (str/includes? shell-html "<footer>Footer</footer>") "shell content below boundary preserved")
      (is (str/includes? shell-html "data-rf2-suspense-id=\":test/comments\"") "boundary id stamped")
      (is (str/includes? shell-html "data-rf2-suspense-fallback=\"1\"") "fallback marker stamped")
      (is (str/includes? shell-html "<p>Loading…</p>") "fallback hiccup rendered inline"))))

(deftest render-shell-fallback-is-inert-template-not-painted-dom
  (testing "rf2-xzhf2a — the streaming first shell chunk carries each
            boundary's fallback markup ONLY inside an inert
            `<template data-rf2-suspense-fallback>` — NOT as painted
            (template-free) DOM. This is the no-JS / first-byte contract:
            a `<template>`'s content is inert by the HTML spec (it does not
            paint until the client runtime materialises it into a live
            `<rf-suspense>` mount), so the server emit is intentionally a
            wrapped placeholder, NOT first-byte visible content. The fallback
            text appears EXACTLY once, and exactly once inside the template —
            i.e. there is no second, painted copy outside the `<template>`."
    (let [tree   [:main
                  [:rf/suspense-boundary
                   {:id :news/comments :fallback [:p.skeleton "Loading comments…"]}
                   [:section.comments "Body"]]]
          {:keys [shell-html]} (streaming/render-shell tree)
          ;; Strip every <template …>…</template> block; whatever fallback
          ;; markup the server painted OUTSIDE a template survives.
          painted (str/replace shell-html
                               #"(?s)<template[^>]*>.*?</template>"
                               "")]
      (is (str/includes? shell-html "data-rf2-suspense-fallback=\"1\"")
          "the fallback is emitted as a marked <template> placeholder")
      (is (str/includes? shell-html "<p class=\"skeleton\">Loading comments…</p>")
          "the fallback markup is present (inside the template)")
      ;; The fallback markup must NOT survive template-stripping — i.e. there
      ;; is NO painted (template-free) copy. The shell, sans templates, is just
      ;; the surrounding structure with NO fallback content.
      (is (not (str/includes? painted "Loading comments…"))
          "no painted (template-free) fallback copy — fallback content is ONLY inside the inert <template>")
      (is (not (str/includes? painted "skeleton"))
          "no painted fallback element outside the inert <template>"))))

(deftest render-shell-handles-multiple-boundaries
  (testing "Multiple boundaries register multiple continuations in document order"
    (let [tree [:main
                [:rf/suspense-boundary {:id :a :fallback [:p "A loading"]} [:p "A body"]]
                [:rf/suspense-boundary {:id :b :fallback [:p "B loading"]} [:p "B body"]]
                [:rf/suspense-boundary {:id :c :fallback [:p "C loading"]} [:p "C body"]]]
          {:keys [continuations]} (streaming/render-shell tree)]
      (is (= [:a :b :c] (mapv :id continuations)) "FIFO registration in document order"))))

(deftest render-shell-handles-nested-boundaries
  (testing "Boundary nested inside DOM children registers correctly"
    (let [tree [:div
                [:section
                 [:rf/suspense-boundary {:id :nested :fallback [:p "nested loading"]}
                  [:p "nested body"]]]]
          {:keys [shell-html continuations]} (streaming/render-shell tree)]
      (is (= 1 (count continuations)))
      (is (= :nested (-> continuations first :id)))
      (is (str/includes? shell-html "<section>"))
      (is (str/includes? shell-html "data-rf2-suspense-id=\":nested\"")))))

(deftest render-shell-rejects-malformed-boundary
  (testing "Boundary without {:id … :fallback …} attrs throws structurally"
    (let [bad [:rf/suspense-boundary {:id :missing-fallback}
               [:p "body"]]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #":rf.error/suspense-boundary-invalid-attrs"
                            (streaming/render-shell bad))))))

(deftest render-continuation-resolves-and-deltas
  (testing "Continuation render returns subtree HTML + (empty) delta when db is unchanged across render"
    (let [fid (make-frame {:db {:initial true}})
          tree [:rf/suspense-boundary {:id :c :fallback [:p "..."]}
                [:ul [:li "comment"]]]
          {:keys [continuations]} (streaming/render-shell tree)
          entry (first continuations)
          {:keys [id html delta failed?]} (streaming/render-continuation fid entry)]
      (is (= :c id))
      (is (not failed?))
      (is (= "<ul><li>comment</li></ul>" html))
      (is (map? delta) "delta is a map (possibly empty when no app-db keys changed during render)"))))

(deftest render-continuation-delta-ships-full-value-for-changed-nested-key
  (testing "rf2-ee38b.10: a CHANGED nested top-level key ships its FULL
            after-db value in the delta — not clojure.data/diff's partial
            second-slot sub-map — so the client's documented top-level
            (into existing delta) merge is lossless (untouched sibling
            sub-keys are not silently dropped). Per Spec 011 §Hydration
            interleaving."
    (rf/reg-event :rf.test/change-nested
      (fn [{:keys [db]} _] {:db (assoc-in db [:user :name] "after")}))
    (let [;; before-db: {:user {:name "before" :role "admin"}}
          fid     (make-frame {:db {:user {:name "before" :role "admin"}
                                    :other :unchanged}})
          ;; A view that mutates ONLY [:user :name] during render — the
          ;; canonical "changed nested key" shape that exposes the lossy
          ;; partial-diff merge.
          _       (rf/reg-view ^{:rf/id :rf.test/nested-mutator} nested-mutator []
                    (rf/dispatch-sync [:rf.test/change-nested] {:frame fid})
                    [:p "mutated"])
          tree    [:rf/suspense-boundary {:id :n :fallback [:p "..."]}
                   [:rf.test/nested-mutator]]
          {:keys [continuations]} (streaming/render-shell tree)
          entry   (first continuations)
          {:keys [delta]} (streaming/render-continuation fid entry)]
      (is (contains? delta :user)
          ":user is a changed top-level key, so it is in the delta")
      (is (= {:name "after" :role "admin"} (:user delta))
          "the delta ships the FULL after-db :user value (with :role
           preserved) — NOT the partial {:name \"after\"} sub-diff that
           would drop :role under the client's top-level into-merge")
      (is (not (contains? delta :other))
          "unchanged top-level keys are omitted from the delta")
      ;; Prove the documented client merge is lossless with this delta.
      (is (= {:user {:name "after" :role "admin"} :other :unchanged}
             (into {:user {:name "before" :role "admin"} :other :unchanged}
                   delta))
          "(into existing delta) over the top-level keys reconstructs the
           full after-db with no sub-key loss"))))

(deftest render-continuation-failure-emits-trace-and-inlines-fallback
  (testing "Subtree-render throw → :rf.ssr/suspense-boundary-failed trace + fallback materialised"
    (let [fid    (make-frame {:db {}})
          throws (fn [] (throw (ex-info "boom" {})))
          ;; Attach a fn-headed component that throws during render
          tree   [:rf/suspense-boundary {:id :flaky :fallback [:p "Loading…"]}
                  [throws]]
          {:keys [continuations]} (streaming/render-shell tree)
          ;; rf2-405ld — exercise the REAL record→fail path: the
          ;; continuation entry must already carry its declared :fallback
          ;; from `record-continuation!`. Previously this test masked the
          ;; bug by manually `(assoc … :fallback [:p "Loading…"])`; with
          ;; the manual assoc removed, an empty fallback on failure (the
          ;; bug) would surface here as a "" :html.
          entry  (first continuations)
          captured (atom [])
          result (with-trace-capture captured
                   #(streaming/render-continuation fid entry))]
      (is (= [:p "Loading…"] (:fallback entry))
          "record-continuation! stored the declared :fallback on the entry")
      (is (:failed? result) ":failed? truthy")
      (is (nil? (:delta result)) "delta omitted on failure")
      (is (= "<p>Loading…</p>" (:html result))
          "declared fallback hiccup materialised in place (not empty)")
      (is (some #(= :rf.ssr/suspense-boundary-failed (:operation %))
                @captured)
          ":rf.ssr/suspense-boundary-failed trace emitted"))))

(deftest duplicate-id-emits-trace-and-keeps-last
  (testing "Two boundaries with the same :id emit :rf.error/suspense-boundary-duplicate-id"
    (let [tree [:div
                [:rf/suspense-boundary {:id :dup :fallback [:p "first"]} [:p "first body"]]
                [:rf/suspense-boundary {:id :dup :fallback [:p "second"]} [:p "second body"]]]
          captured (atom [])
          {:keys [continuations]}
          (with-trace-capture captured #(streaming/render-shell tree))]
      (is (= 1 (count continuations)) "only one continuation survives dedup")
      (is (some #(= :rf.error/suspense-boundary-duplicate-id (:operation %))
                @captured)
          ":rf.error/suspense-boundary-duplicate-id trace emitted"))))

;; ===========================================================================
;; rf2-yvc0t7 — duplicate detection keys on the WIRE id, not the raw :id
;;
;; Boundary ids are serialised to the wire as `(str id)` —
;; `data-rf2-suspense-id` / `data-rf2-suspense-hydrate` — and the client
;; matches mounts by that one string. The old `dedupe-continuations`
;; grouped by the RAW `:id`, so two boundaries whose ids DIFFER as values
;; but COLLIDE under `str` (a keyword `:a` and a string `":a"`) escaped
;; detection: both stamped the same `data-rf2-suspense-id=":a"`, yet two
;; continuations survived. The client could then materialise/resolve the
;; wrong mount or skip the later chunk via its seen-set. The fix keys
;; dedup on the canonical wire id `(str id)`.
;; ===========================================================================

(deftest duplicate-wire-id-collision-emits-trace-and-keeps-last
  (testing "rf2-yvc0t7: two boundaries whose raw ids differ (`:a` keyword
            vs `\":a\"` string) but whose WIRE ids collide under `str`
            are detected as duplicates — one continuation survives
            (last-write-wins), the trace fires, and both fallback
            templates carry the same wire id attribute"
    (let [tree [:div
                [:rf/suspense-boundary {:id :a :fallback [:p "first"]} [:p "first body"]]
                [:rf/suspense-boundary {:id ":a" :fallback [:p "second"]} [:p "second body"]]]
          captured (atom [])
          {:keys [continuations shell-html]}
          (with-trace-capture captured #(streaming/render-shell tree))]
      ;; The wire surface: both boundaries stamp the SAME wire id, so the
      ;; client cannot tell them apart — the collision is real.
      (is (= 2 (count (re-seq (re-pattern (str wire/attr-suspense-id "=\":a\""))
                              shell-html)))
          "both fallback templates stamp the same wire id `:a` — the
           collision the client would face")
      ;; The fix: dedup collapses the colliding ids to a single
      ;; continuation (the old raw-:id grouping left two).
      (is (= 1 (count continuations))
          "only one continuation survives dedup on the wire id")
      ;; Last-write-wins: the surviving entry is the SECOND (string `:a`)
      ;; registration.
      (is (= ":a" (:id (first continuations)))
          "last-write-wins keeps the LAST registration (the string id)")
      (is (= [:p "second"] (:fallback (first continuations)))
          "the surviving entry carries the last registration's :fallback")
      ;; The documented programmer-error trace fires.
      (let [dup-traces (filterv #(= :rf.error/suspense-boundary-duplicate-id
                                    (:operation %))
                                @captured)]
        (is (= 1 (count dup-traces))
            ":rf.error/suspense-boundary-duplicate-id fires once for the
             colliding pair")
        (when-let [ev (first dup-traces)]
          (is (= ":a" (get-in ev [:tags :id]))
              "the trace reports the colliding WIRE id")
          (is (= 2 (get-in ev [:tags :count]))
              ":count reports the colliding cardinality")
          (is (= [:a ":a"] (get-in ev [:tags :raw-ids]))
              ":raw-ids surfaces the distinct raw ids that collided")
          (is (= :last-write-wins (:recovery ev))
              ":recovery names the applied policy"))))))

(deftest distinct-wire-ids-are-not-deduped
  (testing "rf2-yvc0t7 guard: boundaries with genuinely distinct wire ids
            (the common keyword case) are NOT collapsed"
    (let [tree [:div
                [:rf/suspense-boundary {:id :a :fallback [:p "fa"]} [:p "ba"]]
                [:rf/suspense-boundary {:id :b :fallback [:p "fb"]} [:p "bb"]]]
          captured (atom [])
          {:keys [continuations]}
          (with-trace-capture captured #(streaming/render-shell tree))]
      (is (= 2 (count continuations))
          "distinct wire ids both survive — no false collapse")
      (is (empty? (filterv #(= :rf.error/suspense-boundary-duplicate-id
                               (:operation %))
                           @captured))
          "no duplicate-id trace for distinct ids"))))

(deftest build-final-payload-shape
  (testing "Final payload carries the canonical :rf/hydration-payload shape"
    (let [fid (make-frame {:db {:articles [{:id "a"}]}})
          ;; rf2-gtgf9: explicit fail-closed payload policy. This test
          ;; pins the shape of the canonical payload — opt in to whole-
          ;; app-db so the existing :rf/app-db assertion still holds.
          payload (streaming/build-final-payload fid "deadbeef"
                                                 {:version       7
                                                  :schema-digest "abc123"
                                                  :payload :rf.ssr.payload/whole-app-db})]
      (is (= 7 (:rf/version payload)))
      (is (= fid (:rf/frame-id payload)))
      (is (= "deadbeef" (:rf/render-hash payload)))
      (is (= "abc123" (:rf/schema-digest payload)))
      (is (= {:articles [{:id "a"}]} (:rf/app-db payload))))))

(deftest build-final-payload-version-honours-runtime-version-hook
  (testing "rf2-via0g / rf2-g00l2t — streaming payload :rf/version reads the
            :rf2/runtime-version late-bind hook (mirroring the non-
            streaming rf2-asmj1 S8 fix), not a hard-coded 1, and the hook
            value is coerced to the canonical INTEGER pattern-protocol
            version (per Spec-Schemas §:rf/hydration-payload). The prior
            `(or version 1)` silently disagreed with the client-side
            hook and defeated the :rf.ssr/version-mismatch check."
    (let [fid (make-frame {:db {:k 1}})]
      (testing "hook present + no explicit :version → hook value wins (not 1)"
        (late-bind/set-fn! :rf2/runtime-version (constantly 99))
        (try
          (let [payload (streaming/build-final-payload
                          fid "hash"
                          {:payload :rf.ssr.payload/whole-app-db})]
            (is (= 99 (:rf/version payload))
                "runtime-version hook value lands in :rf/version")
            (is (not= 1 (:rf/version payload))
                "the hard-coded 1 must NOT win when the hook is registered"))
          (finally
            (swap! late-bind/hooks dissoc :rf2/runtime-version))))

      (testing "explicit :version opt wins over the hook"
        (late-bind/set-fn! :rf2/runtime-version (constantly 99))
        (try
          (let [payload (streaming/build-final-payload
                          fid "hash"
                          {:version        42
                           :payload :rf.ssr.payload/whole-app-db})]
            (is (= 42 (:rf/version payload))
                "caller-supplied :version is the highest-priority source"))
          (finally
            (swap! late-bind/hooks dissoc :rf2/runtime-version))))

      (testing "no hook + no explicit :version → terminal v1 fallback"
        (swap! late-bind/hooks dissoc :rf2/runtime-version)
        (let [payload (streaming/build-final-payload
                        fid "hash"
                        {:payload :rf.ssr.payload/whole-app-db})]
          (is (= 1 (:rf/version payload))
              "absent hook + absent opt → v1 pattern-protocol stamp"))))))

(deftest late-bind-hooks-published
  (testing "All three :ssr.streaming/* late-bind hooks resolve to the streaming fns"
    (is (= streaming/render-shell        (re-frame.late-bind/get-fn :ssr.streaming/render-shell!)))
    (is (= streaming/render-continuation (re-frame.late-bind/get-fn :ssr.streaming/render-continuation!)))
    (is (= streaming/build-final-payload (re-frame.late-bind/get-fn :ssr.streaming/build-final-payload)))))

(deftest facade-exposes-streaming-surface
  (testing "`re-frame.ssr` re-exports the streaming public surface"
    (is (= streaming/render-shell        ssr/streaming-render-shell))
    (is (= streaming/render-continuation ssr/streaming-render-continuation))
    (is (= streaming/build-final-payload ssr/streaming-build-final-payload))))

;; ===========================================================================
;; rf2-3w6dmy finding 2 — streaming wire-attribute single-source parity
;;
;; The server emitter (`re-frame.ssr.streaming`) and the client runtime
;; (`re-frame.ssr.streaming.client`) both stamp/read the `data-rf2-suspense-*`
;; attribute names. Those names now live in ONE place
;; (`re-frame.ssr.streaming.constants`), so a rename is a one-edit change
;; rather than a grep-driven sweep that could silently drift the two sides.
;;
;; This test locks the parity at the SERVER boundary: each server-emitted
;; chunk must carry the corresponding `wire` constant attribute. The client
;; reads the SAME `wire` ns (its `attr-*` aliases are `def`s OVER the `wire`
;; vars), so server↔client agreement is enforced by the shared source — a
;; future edit that hard-codes a divergent literal in either builder fails
;; this test (server side) or breaks every client query (client side).
;; ===========================================================================

(deftest streaming-wire-attributes-single-sourced
  (testing "rf2-3w6dmy: every server-emitted suspense chunk carries the
            attribute name pinned in re-frame.ssr.streaming.constants — the
            emitter reads the wire constants, so a divergent literal cannot
            slip in unnoticed"
    (let [id        :card/revenue
          fallback  (streaming/fallback-template id "<div>fb</div>")
          resolved  (streaming/resolved-template id "<div>ok</div>")
          failed    (streaming/failed-template   id "<div>fb</div>")
          delta     (streaming/hydrate-delta-script id (pr-str {:k 1}))]
      ;; The boundary-id attribute anchors every template chunk + the delta
      ;; script — the client matches on it, so server + client MUST agree.
      (is (str/includes? fallback (str wire/attr-suspense-id "="))
          "fallback template stamps the wire id attribute")
      (is (str/includes? resolved (str wire/attr-suspense-id "="))
          "resolved template stamps the wire id attribute")
      (is (str/includes? failed   (str wire/attr-suspense-id "="))
          "failed template stamps the wire id attribute")
      (is (str/includes? delta    (str wire/attr-suspense-hydrate "="))
          "delta script stamps the wire hydrate attribute")
      ;; The per-kind markers the client branches on.
      (is (str/includes? fallback (str wire/attr-suspense-fallback "=\"1\""))
          "fallback template stamps the wire fallback marker")
      (is (str/includes? resolved (str wire/attr-suspense-resolved "=\"1\""))
          "resolved template stamps the wire resolved marker")
      (is (str/includes? failed   (str wire/attr-suspense-resolved "=\"1\""))
          "failed template is a resolved chunk (carries the resolved marker)")
      (is (str/includes? failed   (str wire/attr-suspense-failed "=\"1\""))
          "failed template adds the wire failed marker")
      ;; Belt-and-braces: NO server-emitted chunk may carry a stale literal
      ;; that diverges from the wire constants (catches a hard-coded rename
      ;; on one side only).
      (is (not (str/includes? failed wire/attr-suspense-fallback))
          "failed chunk is NOT a fallback (markers don't bleed across kinds)")
      (is (not (str/includes? resolved wire/attr-suspense-failed))
          "a non-failed resolved chunk carries no failed marker"))))

;; ===========================================================================
;; rf2-rdxxa — per-subtree hydration-delta <script> body escaping
;;
;; `hydrate-delta-script` drops a `(pr-str delta)` EDN body inside a
;; `<script data-rf2-suspense-hydrate type="application/edn">`. The body
;; MUST (a) never carry a literal `</script` breakout and (b) round-trip
;; through the client's EDN reader unchanged — including delta values that
;; carry `</script>` in a string AND delta map keys that are keywords with
;; a (non-breakout) `<`. The earlier whole-string `<`→`<` escape
;; corrupted such keyword tokens (unreadable `:a<b`), which would
;; silently drop the speculative delta on the client. EDN-aware escape via
;; `html-helpers/escape-edn-script-body` fixes both.
;; ===========================================================================

(defn- delta-script-body
  "Extract the EDN body between the delta script's `>` and `</script>`."
  [script-html]
  (second (re-find #"type=\"application/edn\">(.*?)</script>" script-html)))

(deftest hydrate-delta-script-escapes-breakout-and-round-trips
  (testing "rf2-rdxxa: a delta carrying a `</script>` substring in a string
            value AND a keyword key containing `<` cannot close the envelope
            and round-trips through the EDN reader verbatim"
    (let [delta   {:public/title "</script><script>alert('xss')</script>"
                   :a<b 1
                   :tag :<}
          script  (streaming/hydrate-delta-script :boundary/x (pr-str delta))
          body    (delta-script-body script)]
      ;; (a) no breakout — the raw closing-tag pattern must not survive.
      (is (not (str/includes? (str/lower-case body) "</script"))
          "delta body carries no literal </script breakout")
      ;; the string-literal `<` chars are escaped as <.
      (is (str/includes? body "\\u003c/script>")
          "string-literal `<` escaped as \\u003c")
      ;; (b) the keyword-token `<` is left intact so the body round-trips.
      (is (str/includes? body ":a<b")
          "keyword-token `<` left intact (no token corruption)")
      (is (= delta (clojure.edn/read-string body))
          "rf2-rdxxa: the EDN reader recovers the full delta verbatim"))))

(deftest hydrate-delta-script-token-breakout-fails-loud
  (testing "rf2-rdxxa: a `</` breakout precursor in a non-string TOKEN (a
            symbol value `a</script>b` — valid, readable EDN that prints the
            literal `</`) has no readable in-token EDN escape, so emission
            fails loud rather than corrupting the body"
    ;; A symbol value prints the bare token `a</script>b` carrying a literal
    ;; `</` outside any string literal — the genuine token-position breakout.
    ;; (A keyword can't carry `</` in its NAME: the first `/` is the
    ;; namespace separator, so `:a</b` prints the `<` in the namespace half
    ;; — `<{`, not `</` — and is safe.)
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #":rf.error/ssr-edn-script-breakout"
                          (streaming/hydrate-delta-script
                            :boundary/x (pr-str {:k (symbol "a</script>b")}))))))

;; ===========================================================================
;; rf2-g15jtb — EDN char literals must not be mis-scanned as string state
;;
;; `escape-edn-script-body` scans the already-`pr-str`'d EDN document
;; tracking string-literal context. An EDN CHARACTER LITERAL also opens
;; with a backslash, so `(char 34)` prints as a backslash + a raw `"`
;; byte (`\"`). The old scanner had no char-literal state: it read that
;; raw `"` as the START of a string literal, flipping its in-string
;; tracking out of phase. A later REAL string literal carrying `</script>`
;; was then scanned in token position and rejected fail-loud with
;; `:rf.error/ssr-edn-script-breakout` — a data-dependent false positive
;; on otherwise-safe app-db data. The fix treats a token-position
;; backslash as a char-literal introducer: it consumes the following
;; payload char verbatim, so `\"` does not toggle string state.
;; ===========================================================================

(deftest escape-edn-script-body-handles-char-literals
  (testing "rf2-g15jtb: a char literal for double-quote (`(char 34)` →
            prints `\\\"`) does NOT toggle string state, so a later string
            literal's `</script>` is escaped (not mis-read as a token
            breakout); the body carries no literal `</script` and
            round-trips through the EDN reader"
    (let [value {:x (char 34) :y "</script>"}
          edn-doc (pr-str value)
          body  (html/escape-edn-script-body edn-doc)]
      ;; The pre-fix behaviour THREW here; the fix must produce a body.
      (is (string? body) "escaper returns a body (no false-positive throw)")
      (is (not (str/includes? (str/lower-case body) "</script"))
          "no literal </script breakout survives")
      (is (= value (edn/read-string body))
          "the EDN reader recovers the full value verbatim — char literal
           and the (now-escaped) string both round-trip")))

  (testing "rf2-g15jtb: char literals for the breakout chars themselves
            (`<`, `/`) are single-char literals separated from neighbours
            by pr-str, so they round-trip without a false breakout"
    (doseq [v [(char 60) (char 47) (char 33)]]
      (let [value {:c v :s "safe"}
            body  (html/escape-edn-script-body (pr-str value))]
        (is (= value (edn/read-string body))
            (str "char literal " (pr-str v) " round-trips")))))

  (testing "rf2-g15jtb: a genuine token-position breakout (a SYMBOL value
            printing a literal `</`) still fails loud — the char-literal
            relaxation does not weaken the breakout guard"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #":rf.error/ssr-edn-script-breakout"
                          (html/escape-edn-script-body
                            (pr-str {:k (symbol "a</script>b")}))))))

(deftest hydrate-delta-script-char-literal-round-trips
  (testing "rf2-g15jtb: the streaming delta call site (`hydrate-delta-script`)
            shares the fixed helper, so a delta whose value is a char
            literal `(char 34)` alongside a string carrying `</script>`
            emits cleanly and round-trips"
    (let [delta  {:x (char 34) :y "</script>"}
          script (streaming/hydrate-delta-script :boundary/x (pr-str delta))
          body   (delta-script-body script)]
      (is (not (str/includes? (str/lower-case body) "</script"))
          "delta body carries no literal </script breakout")
      (is (= delta (edn/read-string body))
          "delta round-trips through the EDN reader verbatim"))))
