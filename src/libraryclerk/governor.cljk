(ns libraryclerk.governor
  "LibraryServicesGovernor — the independent safety/traceability layer
  named in this repository's README/business-model.md (`:library-
  services-governor` per blueprint.edn), gating every circulation-desk
  operation an advisor may propose. The advisor has no notion of
  patron verification status or legal-hold provenance, so this MUST
  be a separate system able to reject a proposal (itonami actor
  pattern, per ADR-2607011000 / CLAUDE.md Actors section). Modeled on
  cloud-itonami-isco-4214's debtcollection.governor.

  `check` is a pure function of (request, context, proposal, store) ->
  verdict; it never mutates the store, never dispatches the shelving/
  sorting/retrieval robot, and never releases restricted/archival
  material outside this gate. The StateGraph's `:decide` node routes
  on the verdict:
    :hard? true                → :hold  (irreversible, no write)
    :escalate? true            → :request-approval (interrupt-before)
    otherwise                  → :commit

  HARD invariants (:hard? true, ALWAYS :hold, never overridable):
    1. patron provenance   — the request's patron must be a verified,
                              registered record in the store before
                              ANY circulation or account action.
    2. no-actuation        — proposal :effect must be :propose only
                              (the actor never directly actuates; the
                              governor never dispatches the robot or
                              circulates/releases an item itself, it
                              only gates what the advisor may propose).
    3. item basis          — an item-touching proposal must cite a
                              REGISTERED item.
    4. legal hold          — no action on an item flagged
                              :legal-hold? true (held-for-legal/under-
                              investigation, e.g. restricted or
                              archival material) — always hold
                              regardless of advisor confidence.
  ESCALATION invariants (:escalate? true, ALWAYS human sign-off, per
  business-model.md's Trust Controls — patron borrowing records are
  confidential and circulation records are auditable, not editable):
    5. :op :waive-late-fee with :fee-amount above
       `late-fee-escalation-threshold` (forgiving a large fine is a
       revenue/trust decision, not routine circulation).
    6. :op :restrict-patron-account (due-process implication — blocks
       a patron's borrowing privileges).
    7. :op :purge-patron-record (irreversible, privacy-sensitive
       deletion of a patron's borrowing history).
    8. low confidence (< `confidence-floor`)."
  (:require [libraryclerk.store :as store]))

(def confidence-floor 0.6)
(def late-fee-escalation-threshold 10)

(def ^:private item-touching-ops
  #{:check-out-item :check-in-item :reserve-item :report-lost-item})

(def ^:private always-escalate-ops
  #{:restrict-patron-account :purge-patron-record})

(defn- hard-violations [{:keys [proposal]} patron-record it]
  (let [{:keys [op]} proposal
        item-op? (contains? item-touching-ops op)]
    (cond-> []
      (not (:verified? patron-record))
      (conj {:rule :no-patron :detail "未登録または未確認 patron — circulation/account action は verified 済み patron のみ許可"})

      (not= :propose (:effect proposal))
      (conj {:rule :no-actuation :detail "effect は :propose のみ許可（robot 作動/item 貸出解除を actor が直接行うことは禁止）"})

      (and item-op? (nil? it))
      (conj {:rule :unknown-item :detail "未登録 item への操作は不可"})

      (and item-op? it (:legal-hold? it))
      (conj {:rule :item-under-legal-hold
             :detail "法的保留/調査中（制限・アーカイブ資料含む）フラグの item への操作は confidence に関わらず不可"}))))

(defn- fee-waiver-escalates? [{:keys [op fee-amount]}]
  (and (= :waive-late-fee op)
       (number? fee-amount)
       (> fee-amount late-fee-escalation-threshold)))

(defn check
  "Assess a proposal against `request`/`context`/`proposal` and a
  `store` implementing `libraryclerk.store/Store`. Returns
  `{:ok? bool :violations [...] :confidence n :hard? bool :escalate? bool}`."
  [request context proposal store]
  (let [patron-record (store/patron store (:patron-id request))
        it (some->> (:item-id proposal) (store/item store))
        hard (hard-violations {:proposal proposal} patron-record it)
        hard? (boolean (seq hard))
        conf (or (:confidence proposal) 0.0)
        low? (< conf confidence-floor)
        always-risky? (or (contains? always-escalate-ops (:op proposal))
                           (fee-waiver-escalates? proposal))]
    {:ok? (and (not hard?) (not low?) (not always-risky?))
     :violations hard
     :confidence conf
     :hard? hard?
     :escalate? (and (not hard?) (or low? always-risky?))}))
