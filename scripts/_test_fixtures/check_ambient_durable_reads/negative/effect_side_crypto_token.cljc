(ns fixtures.effect-side-crypto-token
  "NEGATIVE fixture: effect-side crypto (EP-0010 Open-Issue 3 rider c). A
  session token / nonce minted from `js/crypto.getRandomValues` in an effect
  interpreter is EXCLUDED from recordable world inputs entirely — a recorded
  choice would BE the secret. The `getRandomValues` wrapper in the enclosing
  window allowlists the site even though the derived value lands in a durable
  `:id` slot. Must stay GREEN (0 findings).")

(defn mint-session-token
  []
  (let [buf (js/Uint8Array. 16)]
    (js/crypto.getRandomValues buf)
    ;; The random-uuid below would normally FLAG into a durable :id, but the
    ;; getRandomValues call within the +/-3-line window marks this as
    ;; sanctioned effect-side crypto.
    {:id    (random-uuid)
     :token (.from js/Array buf)}))
