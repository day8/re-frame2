(ns re-frame.schemas.walker-sanitize-path-fixtures
  "Shared, host-agnostic corpus for `sanitize-sensitive-path`'s closed-map
  extra-key scrub (rf2-j538f7.13). Both the JVM test
  (`re-frame.schemas-sensitive-test`) and the CLJS parity test
  (`re-frame.schemas-sensitive-path-cljs-test`) assert these cases, so the
  sanitizer is pinned to the SAME privacy behaviour on both runtimes —
  `walker.cljc` is shared CLJC and redaction must not diverge by host.

  Background — the sanitizer's `:map` arm keeps a DECLARED child key as a
  navigable `get-in` locator. But for a Malli `[:map {:closed true} …]`
  extra-key failure, the reported `:in` segment is the CALLER-SUPPLIED
  EXTRA KEY VALUE itself — arbitrary user data (decoded JSON keys, headers,
  hostile input) that may itself be a credential. The pre-fix key-not-found
  branch `(conj out seg)`-ed that segment before fail-closing the tail,
  shipping the secret verbatim through `:path` / `:reason`. The fix routes
  the unknown segment into the same fail-closed scrub as set element values,
  sensitive `:map-of` keys, and unresolved wrapper tails.

  Every case is pure vector-form data (no compiled schemas), so the corpus
  loads identically on the JVM and under `:node-test`."
  )

(def cases
  "Each case: `{:desc … :schema … :in … :expected …}` —
  `(sanitize-sensitive-path schema in)` must equal `expected` on BOTH hosts."
  [;; ---- the rf2-j538f7.13 leak shape: sensitive closed-map EXTRA key ----
   {:desc     "flat sensitive closed map — the undeclared extra key is user
               data, not a declared slot; it is scrubbed to the sentinel"
    :schema   [:map {:closed true :sensitive? true} [:known :int]]
    :in       ["SECRET-KEY-7f93"]
    :expected [:rf/redacted]}

   {:desc     "hostile extra key of composite shape — a map carrying a secret
               used AS the key is scrubbed whole (fail-closed is shape-blind)"
    :schema   [:map {:closed true :sensitive? true} [:known :int]]
    :in       [{:ssn "111-22-3333"}]
    :expected [:rf/redacted]}

   {:desc     "nested sensitive closed map — the outer DECLARED key stays a
               navigable locator; the inner undeclared extra key is scrubbed"
    :schema   [:map {:closed true}
               [:profile [:map {:closed true :sensitive? true} [:known :int]]]]
    :in       [:profile "SECRET-KEY-7f93"]
    :expected [:profile :rf/redacted]}

   {:desc     "unknown segment with a trailing tail — the ENTIRE remainder is
               scrubbed (the unknown key and everything past it)"
    :schema   [:map {:closed true :sensitive? true} [:known :int]]
    :in       ["SECRET-KEY-7f93" :sub]
    :expected [:rf/redacted :rf/redacted]}

   ;; ---- regression controls: declared locators stay navigable ----------
   {:desc     "declared map key of the SAME closed sensitive map is kept —
               only the undeclared segment fails closed"
    :schema   [:map {:closed true :sensitive? true} [:known :int]]
    :in       [:known]
    :expected [:known]}

   {:desc     "deep declared chain — every declared map key keeps its precise
               locator identity"
    :schema   [:map [:a [:map [:b {:sensitive? true} :string]]]]
    :in       [:a :b]
    :expected [:a :b]}

   ;; ---- regression controls: existing value-bearing scrubs unchanged ---
   {:desc     "a :set failure's segment is the failing ELEMENT VALUE — always
               scrubbed (rf2-ss06u.1); the declared outer map key survives"
    :schema   [:map [:s [:set [:string {:sensitive? true}]]]]
    :in       [:s "SECRET-SET-ELEMENT"]
    :expected [:s :rf/redacted]}

   {:desc     "a sensitive :map-of KEY is the secret itself — scrubbed; the
               navigable inner declared map key survives (rf2-612mri)"
    :schema   [:map-of [:string {:sensitive? true}] [:map [:age :int]]]
    :in       ["secret-token-123" :age]
    :expected [:rf/redacted :age]}

   {:desc     "a NON-sensitive :map-of key stays a navigable locator — no
               over-redaction of plain keyed containers"
    :schema   [:map-of :string [:map [:secret {:sensitive? true} :string]]]
    :in       ["plain-key" :secret]
    :expected ["plain-key" :secret]}

   {:desc     "a MULTI-child :or is ambiguous — the whole remaining tail fails
               closed (rf2-jqx2at), unchanged by the closed-map fix"
    :schema   [:or
               [:map-of :int :int]
               [:map-of [:string {:sensitive? true}] [:map [:age :int]]]]
    :in       ["secret-token-123" :age]
    :expected [:rf/redacted :rf/redacted]}])
