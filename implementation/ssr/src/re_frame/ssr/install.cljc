(ns re-frame.ssr.install
  "Hydration PREFLIGHT and the IDEMPOTENT payload-install ledger (S5).

  A server-rendered page is **N roots referencing M frames** — M is
  routinely smaller than N, because several roots on one page hydrate the
  same frame. Every one of those roots boots, and every one of them reads
  the same page-wide `__rf_payload` script. Without a ledger the second
  root re-seeds a frame the first root already seeded, silently
  discarding everything that happened in between.

  [Spec 004C §6] ratifies the rule this namespace implements:

  > Payload install remains **idempotent and order-independent**: the
  > first hydrating root referencing a payload installs it; later roots
  > find it live and do not re-seed. Conflict is the exception, and it is
  > fail-loud (§7).

  ## The three-step preflight

  [004C §10] names the S5 hydrate sequence as **manifest
  discovery/validation -> payload install -> hydrate**. `preflight!`
  is the first two steps as one call; `re-frame.ssr.boot/hydrate!` runs
  the third against its verdict.

  1. **Manifest** — resolve the Root Manifest (explicitly supplied, or
     discovered positionally from a container on CLJS), validate it, and
     read `:root-id` from its CONTENT. A container whose manifest is
     absent or unreadable fails loud with
     `:rf.error/root-manifest-invalid` — a hydrating root never guesses
     identity ([004C §3]).
  2. **Install decision** — consult the ledger (below).
  3. *(the caller's)* **hydrate** — dispatch `:rf/hydrate`, but only on
     the `:install` verdict.

  ## The ledger

  `installed-payloads` maps a **payload id** to the record of what was
  installed under it. A payload id IS a frame id: [004C §6] defines the
  manifest's `:frame-payload-ids` as the render's full referenced set —
  plan ids union provider-scoped frame ids — so the thing a root
  \"references a payload for\" and the thing it hydrates into are one
  identifier, not two.

  Each record carries the payload's **content digest** and the
  `:installed-by` root-id, so a conflict can name both parties.

  Three outcomes, and only three:

  | Ledger state | Verdict |
  |---|---|
  | no entry | `:install` — this root is the first; record the claim |
  | entry, **equal** digest | `:already-installed` — the ratified idempotent no-op; do NOT re-seed |
  | entry, **differing** digest | `:rf.error/frame-payload-conflict`, thrown |

  The third arm is the S5 hydrate half of `:rf.error/frame-payload-conflict`
  that [004C §7] reserved (\"a referenced payload id already installed
  with a different **content** digest ... carries its own content-`:digest`
  slot when server rendering lands: the same error id, a distinct conflict
  trigger\"). Its sibling — the plan-`:config-fingerprint` arm — went with
  the retired compiled-view artefact, so this is the arm that remains.

  ## Why a digest rather than payload equality

  The digest is `re-frame.ssr.hash/render-tree-hash` — canonical EDN fed
  through FNV-1a, byte-stable across JVM and CLJS and already
  parity-pinned by `re-frame.ssr.hash-parity-fixtures`. Comparing digests
  rather than whole payload maps keeps the ledger O(1) in the payload's
  size and makes the recorded evidence small enough to ride an error's
  `ex-data` without dragging a whole app-db into a diagnostic.

  ## What this namespace deliberately does NOT do

  No reconciliation, no retry, no re-render. A conflicting root fails and
  **nothing else moves** — the installed payload and every root already
  using it are untouched, which is [06 §2]'s failure scoping, not a
  failed-root isolation mechanism (that is its own leaf). There is no
  Layer-2 per-response page registry here either: this is the CLIENT-side
  install ledger, keyed by payload id, and it takes no position on how a
  server assembles a page."
  (:require [re-frame.error :as rf.error]
            [re-frame.ssr.hash :as rf.ssr.hash]
            [re-frame.ssr.manifest :as rf.ssr.manifest]))

;; ---------------------------------------------------------------------------
;; The ledger
;; ---------------------------------------------------------------------------

(defonce installed-payloads
  ;; payload-id (a frame id) -> {:digest <hex> :installed-by <root-id>}
  ;;
  ;; `defonce` so a CLJS hot-reload of this namespace does not silently
  ;; drop live claims and let an already-hydrated frame be re-seeded.
  (atom {}))

(defn payload-content-digest
  "-> the content digest of a hydration `payload`: the canonical-EDN
  structural hash, lowercase hex.

  Cross-host stable by construction — the same fn the render-tree hash
  uses, whose JVM/CLJS byte-parity is pinned by
  `re-frame.ssr.hash-parity-fixtures`. Two roots reading the SAME
  `__rf_payload` script therefore compute the same digest on either host,
  which is what makes `:already-installed` a reliable verdict rather than
  a hopeful one."
  [payload]
  (rf.ssr.hash/render-tree-hash payload))

(defn installed-payload
  "-> the install record for `payload-id`, or `nil` when nothing is
  installed under it."
  [payload-id]
  (get @installed-payloads payload-id))

(defn claim-record
  "The ledger's record shape, in ONE place. `payload-install-decision!`
  writes it and `release-claim!` matches against it — a claim and its
  release must agree on the shape by construction, not by two literals
  kept in step by hand."
  [payload-digest root-id]
  {:digest payload-digest :installed-by root-id})

(defn release-payload!
  "Release `payload-id`'s install claim. Called from the SSR per-frame
  teardown (`re-frame.ssr.request/on-frame-destroyed!`) so a destroyed
  frame does not leave a stale digest behind — a later frame re-created
  under the same id would otherwise meet a phantom conflict from a
  lifetime that no longer exists. Idempotent."
  [payload-id]
  (swap! installed-payloads dissoc payload-id)
  nil)

(defn release-claim!
  "Release `payload-id` ONLY IF the ledger still holds exactly `claim`
  — the claim-side counterpart of the atomic claim in
  `payload-install-decision!`, and the mechanism that keeps a FAILED
  root from poisoning a payload id ([Spec 011 §Failed-root isolation]).

  A root that claims an id and then fails BEFORE its seed lands must put
  the id back. Leaving the claim would be the worst of both worlds: the
  frame was never seeded, but the next root to reference that payload
  reads `:already-installed` — a legitimate verdict — and skips its own
  install. The page would then run a frame that nobody ever hydrated,
  with no error anywhere. Releasing restores the pre-claim ledger, so the
  next root gets a true `:install`.

  **Why the record guard.** Release is never an unconditional `dissoc`:
  it evicts only the claim this root actually wrote. A `dissoc` would let
  a late release from a failed root evict a SUCCESSOR that legitimately
  re-claimed the id in the meantime — the same identity-guarded-release
  rule [004C §7] states for root claims. `compare-and-set!` on the whole
  ledger makes the check and the eviction one step, so the guard cannot
  be defeated by a claim landing between them.

  Returns `true` when this call released the claim, `false` when it did
  not hold it (already released, or replaced by another claim).
  Idempotent."
  [payload-id claim]
  (loop []
    (let [ledger @installed-payloads]
      (if (= claim (get ledger payload-id))
        (or (compare-and-set! installed-payloads ledger (dissoc ledger payload-id))
            (recur))
        false))))

(defn reset-installed-payloads!
  "Drop every install claim. The test-fixture / full-runtime-reset seam;
  production code releases per payload id via `release-payload!`."
  []
  (reset! installed-payloads {})
  nil)

;; ---------------------------------------------------------------------------
;; The conflict arm (004C §7 — the S5 content-digest trigger)
;; ---------------------------------------------------------------------------

(defn- throw-payload-conflict!
  [where payload-id arriving-digest root-id existing-claim]
  (rf.error/throw-error!
   :rf.error/frame-payload-conflict where
   (str "root " (pr-str root-id) " references payload "
        (pr-str payload-id) ", but a DIFFERENT payload is already "
        "installed under that id (installed by root "
        (pr-str (:installed-by existing-claim)) "). Content digests disagree: "
        "installed " (pr-str (:digest existing-claim)) ", arriving "
        (pr-str arriving-digest) ". One payload id, one content: two roots on a "
        "page must hydrate the same server slice for a frame they share. "
        "This usually means the page composed fragments rendered from "
        "two different server responses. The installed payload and the "
        "roots already using it are untouched — this root failed before "
        "any install")
   {:recovery :render-the-page-from-one-response
    :extra    {:payload-id payload-id
               :installed  existing-claim
               :arriving   {:digest arriving-digest :root-id root-id}}}))

(defn payload-install-decision!
  "The idempotence gate. -> `:install` when `payload-id` is unclaimed (the
  claim is recorded as a side effect, so this root owns it), or
  `:already-installed` when an identical payload is already live.
  Throws `:rf.error/frame-payload-conflict` when a DIFFERENT payload
  holds the id.

  **Atomic claim-or-observe.** The read and the record are one
  `swap-vals!`, so two roots racing the same unclaimed id cannot both
  see \"unclaimed\" and both install. The loser observes the winner's
  record and takes the `:already-installed` / conflict path against it.

  The conflict throw happens with the ledger UNCHANGED — a conflicting
  root never overwrites, never merges, and never partially claims."
  [where payload-id arriving-digest root-id]
  (let [arriving-claim (claim-record arriving-digest root-id)
        [prior-ledger _updated-ledger]
        (swap-vals! installed-payloads
                    (fn [ledger]
                      (if (contains? ledger payload-id)
                        ledger
                        (assoc ledger payload-id arriving-claim))))
        existing-claim (get prior-ledger payload-id)]
    (cond
      ;; We won the claim — the id was unheld when the swap landed.
      (nil? existing-claim) :install

      ;; The ratified idempotent no-op: same content, already live.
      (= arriving-digest (:digest existing-claim)) :already-installed

      :else
      (throw-payload-conflict! where payload-id arriving-digest root-id existing-claim))))

;; ---------------------------------------------------------------------------
;; Manifest resolution (preflight step 1)
;; ---------------------------------------------------------------------------

(defn- discover-manifest
  "CLJS-only positional discovery — the manifest is `container`'s
  immediately following element sibling (Spec 011 §Discovery). Returns
  nil on the JVM, where there is no DOM to read."
  [container]
  #?(:cljs (rf.ssr.manifest/discover container)
     :clj  (do container nil)))

(defn resolve-manifest!
  "Preflight step 1 — resolve and VALIDATE the Root Manifest for a
  hydrating root.

  `manifest` is used verbatim when supplied (the JVM / explicit path);
  otherwise, on CLJS, it is discovered positionally from `container`.
  Either way the result is run through `manifest/validate!`, so a
  hydrating root never adopts a value from outside the schema family.

  A `container` that yields NO manifest fails loud with
  `:rf.error/root-manifest-invalid` `{:missing :manifest}` — the arm
  Spec 009 reserved for exactly this. Asking for a hydrating root's
  manifest and finding none is not a degraded case to paper over:
  hydrating mounts take root-id and identifier-prefix FROM the manifest
  ([004C §3]), so there is nothing left to hydrate AS.

  With neither `manifest` nor `container` there is no manifest step at
  all and this returns `nil` — the manifest-less boot path (a host that
  calls `hydrate!` with an explicit payload and no container) is
  unchanged."
  [where {:keys [manifest container]}]
  (cond
    (some? manifest) (rf.ssr.manifest/validate! where manifest)

    (some? container)
    (or (discover-manifest container)
        (rf.error/throw-error!
         :rf.error/root-manifest-invalid where
         (str "no Root Manifest was found for this hydrating root. The "
              "manifest is the container's IMMEDIATELY FOLLOWING element "
              "sibling (one <script type=\"application/edn\" data-rf-root> "
              "per root) and nothing else is searched — adjacency is what "
              "binds a manifest to its root on a page of N roots. A "
              "hydrating root takes its identity FROM the manifest, so "
              "there is nothing to hydrate as. Either server-render this "
              "root so its manifest rides the response, or mount it "
              "client-only")
         {:recovery :use-ui-mount
          :extra    {:missing :manifest}}))

    :else nil))

;; ---------------------------------------------------------------------------
;; preflight! — steps 1 and 2 as one call
;; ---------------------------------------------------------------------------

(defn preflight!
  "Run hydration preflight for one root and -> the verdict map

      {:manifest … :root-id … :payload-id … :digest … :decision … :claim …}

  where `:decision` is `:install` or `:already-installed` ([004C §10]'s
  \"manifest discovery/validation -> payload install\"; the caller runs
  the remaining \"-> hydrate\" step, and MUST run it only on `:install`).

  `:claim` is the ledger record this preflight WROTE, present only on the
  `:install` verdict. The caller hands it back to `release-claim!` if its
  seed does not land, so a failed root returns the payload id rather than
  poisoning it ([Spec 011 §Failed-root isolation]). It is returned rather
  than reconstructed because the claim a root releases must be the exact
  claim it made.

  Opts:

    :payload    — REQUIRED. The hydration payload this root references.
    :payload-id — REQUIRED. The id the payload installs under — the
                  frame id ([004C §6]: payload ids ARE frame ids).
    :root-id    — the hydrating root's id, for conflict attribution.
                  Defaults to the manifest's `:root-id`, which is where
                  a server-rendered root's identity actually lives.
    :manifest   — an explicit Root Manifest v1 (validated).
    :container  — (CLJS) the root's container element; its adjacent
                  manifest is discovered and validated.

  Throws `:rf.error/root-manifest-invalid` when a `container` has no
  discoverable manifest, and `:rf.error/frame-payload-conflict` when a
  different payload already holds `:payload-id`. Both throw BEFORE the
  caller hydrates, which is the whole point of a preflight."
  [where {:keys [payload payload-id root-id] :as opts}]
  (let [resolved-manifest (resolve-manifest! where opts)
        resolved-root-id  (or root-id (:root-id resolved-manifest))
        payload-digest    (payload-content-digest payload)
        decision          (payload-install-decision! where payload-id
                                                     payload-digest
                                                     resolved-root-id)]
    (cond-> {:manifest   resolved-manifest
             :root-id    resolved-root-id
             :payload-id payload-id
             :digest     payload-digest
             :decision   decision}
      (= :install decision)
      (assoc :claim (claim-record payload-digest resolved-root-id)))))
