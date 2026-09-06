(ns re-frame.hicasso.instance-key-payload-ssr-dom-cljs-test
  "THE INSTANCE-KEY PAYLOAD OBLIGATION, WITNESSED BOTH WAYS — the
  prototype's `ssr/instance_key_payload_dom_cljs_test`, re-expressed on
  [[re-frame.hicasso.server]].

  [[re-frame.hicasso/reg-state]] puts a widget's own state at
  `[:ui ::concern ikey]`, which is APP-SPACE data — so whether it reaches
  the client is decided by the hydration payload policy and by nothing
  else. The ruling states the obligation that follows:

  > an allowlist MUST name `:ui` whenever server-side events write
  > render-affecting instance state; strip it only if the server never
  > writes it. Whole-app-db carries it automatically.

  ## Why prose was not enough, stated precisely

  The payload policy is fail-closed and stays fail-closed: an ABSENT
  `:payload` throws `:rf.error/ssr-missing-payload-policy` at the
  framework's own validator, and nothing here softens that (it is pinned
  by `server_render_ssr_dom_cljs_test`'s
  `the-payload-is-fail-closed-and-allowlisted`). But `[:panels]` is a
  perfectly well-formed allowlist. There is no shape for the validator to
  refuse, no default to fall back on, and no way for the framework to know
  that `:ui` mattered on THIS page — so a policy that is fail-closed
  against absence is silent about omission, and the obligation is a rule
  the author keeps or does not.

  What catches the author who does not is React. The divergence between
  the server's open panel and the client's shut one is a hydration
  mismatch, and `impl.mount/hydration-reporter` surfaces one as Spec 011's
  `:rf.ssr/hydration-mismatch`. **That is the enforcement**, and
  [[omitting-ui-from-the-allowlist-fires-the-structured-mismatch]] asserts
  it POSITIVELY — the row is red-by-design and the error is the row's
  subject, not an accident it survives.

  ## What this file DOES NOT re-express, and why

  The prototype suite is 493 lines and this one is not, because three of
  its rows now have package owners and re-stating them here would be
  duplicate coverage wearing the shape of thoroughness:

  - **determinism** — `server_render_ssr_dom_cljs_test`'s
    `two-renders-of-one-request-are-the-same-bytes` makes the byte-identity
    claim on the package entry, and `frame_doors_ssr_cljs_test` supplies the
    red control the prototype's own determinism row never had;
  - **the round trip through the real doors** — that suite's §5 drives
    `server/render`'s own `__rf_payload` script through `re-frame.ssr`'s
    `hydrate!` and then `h/hydrate!`. This file therefore
    seeds the client frame from the payload map directly, exactly as the
    prototype did and for a sharper reason: what is under test is the
    CONTENT of the payload, so the seeding door is deliberately the least
    interesting part of the row;
  - **the render-hash measurement** — the prototype kept
    `ssr-hash/render-tree-hash`'s constant value live to explain WHY the
    hash was dropped. That is a fact about `re-frame.ssr.hash` rather than
    about this entry, and it stays in the bench tree. The half that is a
    fact about the entry — the wire carries no `:rf/render-hash` for an
    adoption-tier root — is one assertion in [[the-two-requests-differ-by-exactly-the-ui-key]]
    and had no package owner before this file.

  What is left is the obligation and its enforcement, which is what the
  prototype suite exists for and what nothing in the package asserted.

  ## The two hydration rows are each other's control

  A witness that only manufactured a fault could be reading a diagnostic
  that fires on every adoption; a witness that only took a clean adoption
  could be reading a capture that never fires at all. So the green row
  asserts zero emits, zero console complaints and the server's own nodes
  adopted — which is what stops the red row's diagnostic being a constant
  — and the red row asserts the diagnostic firing, tagged with the door's
  own site, which is what makes the green row's silence a reading.

  ## Where the structured error is read from, and why not `thrown?`

  From the live trace stream — `roots-frames-support/watch-mismatches!`,
  which filters `re-frame.trace.tooling`'s listener on
  `:rf.ssr/hydration-mismatch`. It is the only place this diagnostic can
  be read: it fires from a React root-error callback, outside any dispatch
  scope, so it is frameless and never reaches a per-frame ring.

  `(is (thrown? …))` would be wrong here twice over. React routes a
  recoverable hydration error to `reportError`, so nothing throws on any
  stack `cljs.test` is watching — a `thrown?` row would be RED over a
  working door. And the recoverable error is exactly the thing the door is
  supposed to have RECOVERED from, so there is no exception left to catch
  even in principle. The `window` `error` and `console.error` channels are
  watched as well because a row asserting \"nothing complained\" that
  watched neither would be green over a live failure — and in the red row
  they carry the other half of the claim: the door COMPOSES over React's
  default, so the uncaught report is still there beside the diagnostic and
  the pageerror rule is not softened.

  Runtime: `-dom-cljs-test`, so `:browser-test` runs it against a real
  React DOM. The payload-shape row needs no DOM and runs under
  `:node-test` too; every DOM claim degrades to a stated skip there."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.core :as rf]
            [re-frame.hicasso :as rf.hicasso]
            [re-frame.hicasso.impl.collector :as rf.hicasso.impl.collector]
            [re-frame.hicasso.impl.mount :as rf.hicasso.impl.mount]
            [re-frame.hicasso.impl.state :as rf.hicasso.impl.state]
            [re-frame.hicasso.roots-frames-support :as rf.hicasso.roots-frames-support]
            [re-frame.hicasso.server :as rf.hicasso.server]
            [re-frame.test-support :as rf.test-support]))

(def ^:private wire-frame ::instance-key-payload)

;; ---------------------------------------------------------------------------
;; The page the obligation is witnessed on
;; ---------------------------------------------------------------------------
;;
;; One screen, two disclosure panels, one `reg-state` concern. The server's
;; boot event opens the first panel through the ordinary setter `reg-state`
;; mints, so this is a page whose APPEARANCE depends on instance state a
;; server-side event wrote — which is the only kind of page the obligation
;; is about.
;;
;; Registered ABOVE `use-fixtures` for the sibling suites' reason: the reset
;; fixture captures its source-store baseline when the `use-fixtures` form is
;; EVALUATED, so a registration written below it is erased before the first
;; row runs.

(def ^:private open?
  "The concern. Its `:default` is `false`, which is what makes the
  omission row's client render a CLOSED panel: an absent
  `[:ui ::open? ikey]` entry reads the default, and the default is the
  shut one."
  ::open?)

(def ^:private panels
  "The roster, as authored data. Instance keys are the `:id` values —
  domain ids, per `impl.state`'s first taught rule, and stable across a
  server render and its client hydration because both read them from this
  same seeded value. Never a render-order index, never a counter, never
  `random-uuid`, never `useId`."
  [{:id "billing" :title "Billing"}
   {:id "shipping" :title "Shipping"}])

(def ^:private open-key
  "The panel the SERVER's boot event opens. The other stays shut in both
  rows, so \"the payload did not arrive\" is distinguishable from \"the
  page rendered nothing\"."
  "billing")

(def ^:private state-path
  "Where the opened panel's flag sits — `[:ui ::open? \"billing\"]`, the
  documented tier. Spelled through `impl-state/ui-root` so the witness
  asserts on the same vector the sugar writes rather than on a
  hand-spelled copy of it."
  [rf.hicasso.impl.state/ui-root open? open-key])

(rf/reg-sub ::panel-ids (fn [db _] (mapv :id (:panels db))))

(rf/reg-sub ::panel-title
  (fn [db [_ id]] (some (fn [p] (when (= id (:id p)) (:title p))) (:panels db))))

(rf.hicasso/reg-state open? {:default false})

(rf.hicasso/defview panel
  "A disclosure. It holds its own open flag under the instance key it was
  handed and knows nothing else about where it sits.

  The body is a CONDITIONAL ELEMENT rather than a changed attribute or a
  changed string: an element the server rendered and the client does not
  is an unambiguous structural divergence, where a text difference would
  be measuring React's SSR text-separator behaviour alongside the thing
  under test."
  [{:keys [ikey title]}]
  (let [shown? (rf.hicasso/sub [open? ikey])]
    [:section.panel {:data-ikey ikey}
     [:button.panel-toggle {:on-click [open? ikey (not shown?)]} title]
     (when shown?
       [:div.panel-body "the details"])]))

(rf.hicasso/defview screen
  "The page. A heading, then one [[panel]] per roster entry, keyed by the
  same authored id it is instance-keyed by."
  [_]
  [:div.instance-key-page
   [:h1.title "instance state across the wire"]
   [:div.panels
    (for [id (rf.hicasso/sub [::panel-ids])]
      [panel {:key id :ikey id :title (rf.hicasso/sub [::panel-title id])}])]])

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.uix/adapter
     :ambient-frame nil
     :async?        true
     :init-fn       (fn [] (rf.hicasso.impl.collector/reset-runtime!))}))

;; ---------------------------------------------------------------------------
;; The two requests, differing by ONE allowlist entry
;; ---------------------------------------------------------------------------

(def ^:private names-ui
  "The allowlist that MEETS the obligation." [:panels :ui])

(def ^:private omits-ui
  "The allowlist that breaks it — and is perfectly well formed, which is
  the entire problem." [:panels])

(defn- request
  "One request. The two rows render the same hiccup from the same boot
  events; `payload` is the only thing that moves, and it moves exactly one
  key."
  [payload]
  {:hiccup          [screen {}]
   :snapshot        {:panels panels}
   ;; The whole obligation in one line: a render-affecting write into
   ;; `[:ui …]` performed by a SERVER-side event.
   :initial-events  [[open? open-key true]]
   :payload         payload
   :client-frame-id wire-frame})

(defn- client-frame!
  "Boot a CLIENT frame out of the payload the server shipped, and NOTHING
  else.

  `[:rf/set-db (:rf/app-db payload)]` is the reserved framework seeding
  event carrying exactly the slice `re-frame.ssr.hydrate`'s own
  `:rf/hydrate` handler installs. It is spelled this way rather than by
  driving `ssr/hydrate!` off the emitted script because THAT round trip
  already has a package owner — `server_render_ssr_dom_cljs_test` §5 —
  and what is under test here is the CONTENT of the payload,
  so the seeding door is deliberately the least interesting part of the
  row."
  [payload]
  (rf.hicasso.roots-frames-support/leave-act-environment!)
  (rf/make-frame {:id             wire-frame
                  :platform       :client
                  :initial-events [[:rf/set-db (:rf/app-db payload)]]})
  wire-frame)

(defn- open-panel-in [container] (.querySelector container ".panel-body"))
(defn- panel-count [container] (.-length (.querySelectorAll container ".panel")))

(defn- adopt!
  "Put `html` on the page, stamp every node, hydrate it through the PUBLIC
  door on `wire-frame`, and answer a promise of what happened across the
  WHOLE adoption.

  `capture-opts` reaches `sup/open-console-capture!`; the only caller that
  sets `:swallow-uncaught?` is the row that manufactures the fault and
  asserts on it."
  [html capture-opts]
  (let [container                 (rf.hicasso.roots-frames-support/stamp-server-nodes! (rf.hicasso.roots-frames-support/server-dom! html))
        {:keys [seen stop!]}      (rf.hicasso.roots-frames-support/watch-mismatches!)
        {:keys [captured close!]} (rf.hicasso.roots-frames-support/open-console-capture! capture-opts)
        before                    (some? (open-panel-in container))
        handle                    (rf.hicasso/hydrate! container {:frame wire-frame} [screen {}])]
    (-> (rf.hicasso.roots-frames-support/adopted! handle)
        (.then (fn [shut?]
                 (close!)
                 (stop!)
                 {:handle       handle
                  :container    container
                  :adopted?     shut?
                  :open-before? before
                  :mismatches   @seen
                  :warnings     @captured})))))

;; ===========================================================================
;; The payload itself — the omission is a fact about the WIRE
;; ===========================================================================

(deftest the-two-requests-differ-by-exactly-the-ui-key
  (testing "**the variable, isolated.** The two requests render the same
           hiccup from the same boot events, so the SERVER BYTES are
           identical; the allowlist is the only thing that moves. Without
           this row the two hydration rows below could be measuring any
           difference at all between two pages"
    (let [green (rf.hicasso.server/render (request names-ui))
          red   (rf.hicasso.server/render (request omits-ui))
          gdb   (:rf/app-db (:payload green))
          rdb   (:rf/app-db (:payload red))]
      (is (= (:html green) (:html red))
          "the server rendered the same markup for both — the payload
           policy is a decision about what CROSSES, never about what is
           drawn")
      (is (re-find #"class=\"panel-body\"" (:html green))
          (str "and that markup carries the OPEN panel the boot event
                wrote, so there is something for the payload to be obliged
                about: " (:html green)))

      (testing "the green request ships the instance state at the documented tier"
        (is (= true (get-in gdb state-path))
            (str "[:ui ::open? \"billing\"] crossed the wire as written. Saw: "
                 (pr-str gdb))))

      (testing "the red request ships a WELL-FORMED allowlist that strands it"
        (is (not (contains? rdb :ui))
            (str "no :ui partition at all — not an empty one, not a
                  defaulted one; absent: " (pr-str rdb)))
        (is (nil? (get-in rdb state-path))))

      (testing "and everything else is identical, so `:ui` is the whole
               difference"
        (is (= (:panels gdb) (:panels rdb) panels))
        (is (= #{:panels :ui} (set (keys gdb))))
        (is (= #{:panels} (set (keys rdb)))))

      (testing "read off the EDN the client bootstrap actually parses,
               because a claim about a payload map is a claim about the
               wire only if the bytes agree with it"
        (is (re-find #":ui\b" (:payload-edn green)))
        (is (nil? (re-find #":ui\b" (:payload-edn red)))))

      (testing "and neither carries a render hash — an adoption-tier root
               ships none, which is Spec 011's own answer for this tier
               rather than a gap, and is what stops any claim in this file
               resting on one"
        (is (not (contains? (:payload green) :rf/render-hash)))
        (is (not (contains? (:payload red) :rf/render-hash)))))))

;; ===========================================================================
;; ROW 1 — GREEN. The obligation met.
;; ===========================================================================

(deftest naming-ui-on-the-allowlist-hydrates-with-zero-mismatch
  (testing "**the obligation met.** `:ui` is on the allowlist, so the panel
           the server's boot event opened crosses the wire, the client's
           first render draws the same open panel, and React finds nothing
           to reconcile — asserted on the framework's own diagnostic AND on
           both channels React complains through"
    (if-not (rf.hicasso.impl.mount/browser?)
      (rf.hicasso.roots-frames-support/skip! "a payload-obligation hydration claim needs a real React DOM")
      (async done
        (let [{:keys [html payload]} (rf.hicasso.server/render (request names-ui))]
          (client-frame! payload)
          (is (= true (get-in (rf/app-db-value wire-frame) state-path))
              "the client frame booted holding the instance state the server
               wrote — which is the whole of what the allowlist bought")
          (-> (adopt! html nil)
              (.then
                (fn [{:keys [handle container adopted? open-before? mismatches warnings]}]
                  (try
                    (is (true? adopted?) "the adoption completed")
                    (is (true? open-before?)
                        "the panel was open in the bytes the client was handed,
                         BEFORE any JavaScript adopted them")

                    (is (= [] mismatches)
                        (str "**zero `:rf.ssr/hydration-mismatch`.** The
                              framework's own instrument stayed silent across
                              the whole adoption. Saw: " (pr-str mismatches)))
                    (is (= [] warnings)
                        (str "and React reported nothing on either channel —
                              `window` `error` or `console.error`: "
                             (pr-str warnings)))

                    (is (some? (open-panel-in container))
                        "the panel is STILL open after adoption — open before,
                         open after, which is what \"hydrated\" means for a
                         page whose appearance is instance state")
                    (is (rf.hicasso.roots-frames-support/server-node? (open-panel-in container))
                        "and it is the SERVER'S own node. The stamp is an
                         expando and cannot survive re-serialisation, so React
                         reused the markup rather than building a replacement
                         that happens to look alike")
                    (is (rf.hicasso.roots-frames-support/every-server-node? container "*")
                        "as is every element on the page — no partial adoption,
                         which is what a mismatch on one subtree would look
                         like")
                    (is (= 2 (panel-count container))
                        "both panels are there, so the row above is a claim
                         about a page and not about an empty container")
                    (finally (rf.hicasso/unmount! handle)))))
              ;; Reports and UNMOUNTS; it never finishes. `done` runs the whole
              ;; remainder of the run synchronously, so a `.catch` downstream of
              ;; it would claim a later namespace's throw as this row's and fire
              ;; `done` a second time. The unmount stays in the `finally`: it is
              ;; the success arm's, and only that arm was ever handed a handle.
              (.catch (fn [e] (is false (str "row 1 threw: " e)) nil))
              (.then (fn [_] (done)))))))))

;; ===========================================================================
;; ROW 2 — RED BY DESIGN. The obligation broken, and caught.
;; ===========================================================================

(deftest omitting-ui-from-the-allowlist-fires-the-structured-mismatch
  (testing "**the teaching witness, and it EXPECTS the error.** The same page
           with `:ui` omitted: the client boots without the instance state,
           its first render reads `reg-state`'s default and draws a SHUT
           panel where the server's bytes say open, and the structured
           `:rf.ssr/hydration-mismatch` fires. Asserted positively — a row
           that merely documented the obligation, or that passed because
           nothing happened, would be worth nothing.

           **This row MANUFACTURES the fault and is the assertion about
           it**, which is the one case `open-console-capture!`'s
           `:swallow-uncaught?` exists for. `rf2-mwx08` is not softened: the
           error is not unasserted here, it is the subject, and the row goes
           on to assert that the door reported it as well as emitting it"
    (if-not (rf.hicasso.impl.mount/browser?)
      (rf.hicasso.roots-frames-support/skip! "a payload-obligation hydration claim needs a real React DOM")
      (async done
        (let [{:keys [html payload]} (rf.hicasso.server/render (request omits-ui))]
          (client-frame! payload)
          (is (nil? (get-in (rf/app-db-value wire-frame) state-path))
              "the client frame booted WITHOUT the instance state — the entry
               is absent, so `reg-state`'s sub will read its `:default`,
               which is `false`")
          ;; MANUFACTURED fault, asserted on — see the testing string.
          (-> (adopt! html {:swallow-uncaught? true})
              (.then
                (fn [{:keys [handle container adopted? open-before? mismatches warnings]}]
                  (try
                    (is (true? adopted?) "the adoption completed")
                    (is (true? open-before?)
                        "the server's bytes DID carry the open panel — the
                         divergence is the payload's, not the render's")

                    (is (pos? (count mismatches))
                        (str "**the structured hydration-mismatch FIRED.** The
                              taught obligation is enforced by the mismatch
                              machinery, and this is that machinery answering.
                              Saw " (count mismatches) ": " (pr-str mismatches)))
                    (doseq [mm mismatches]
                      (let [tags (rf.hicasso.roots-frames-support/tags-of mm)]
                        (is (= :rf.ssr/hydration-mismatch (:operation mm)))
                        (is (= 're-frame.hicasso.impl.mount/hydrate-root! (:where tags))
                            "tier-discriminated by :where — this arm's hydrate
                             door, so this is React's own reconciliation report
                             and not a hash comparison")
                        (is (= :warned-and-replaced (:recovery tags))
                            "React had already patched the DOM when the callback
                             ran")
                        (is (string? (:error tags))
                            "and the recoverable error's own message rides
                             along")))

                    (is (seq warnings)
                        (str "and the complaint ALSO reached a console channel —
                              the door composes over React's default rather than
                              replacing it, so composing a diagnostic in did not
                              make the mismatch quieter than React left it: "
                             (pr-str warnings)))

                    (is (nil? (open-panel-in container))
                        "**and the client's model won, which is the cost.** The
                         panel the server rendered open is SHUT on the adopted
                         page: the server's work was thrown away and the user
                         sees the default. That is what omitting `:ui` buys,
                         stated as a DOM reading")
                    (is (= 2 (panel-count container))
                        "the rest of the page adopted normally — a stranded
                         partition is a wrong screen, not a blank one")
                    (finally (rf.hicasso/unmount! handle)))))
              ;; Reports and UNMOUNTS, as above.
              (.catch (fn [e] (is false (str "row 2 threw: " e)) nil))
              (.then (fn [_] (done)))))))))
