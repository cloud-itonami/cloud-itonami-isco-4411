(ns libraryclerk.advisor
  "LibraryCirculationAdvisor — the advisor named in this repository's
  README, proposing a circulation-desk operation (check out an item,
  check in an item, reserve an item, waive a late fee, report an item
  lost, restrict a patron's account, purge a patron's borrowing
  history) from a patron request, collection catalog and circulation
  policy. Swappable mock/llm; the advisor ONLY produces a PROPOSAL —
  it never writes to the store and has no notion of patron
  verification status or legal-hold provenance;
  `libraryclerk.governor` is the independent system that decides
  whether the proposal may proceed, per the itonami actor pattern.
  Modeled on cloud-itonami-isco-4214's debtcollection.advisor.

  A proposal is a map:
    {:op :check-out-item|:check-in-item|:reserve-item|:waive-late-fee|
         :report-lost-item|:restrict-patron-account|:purge-patron-record
     :effect :propose        ; the advisor NEVER emits a raw store write
     :item-id str            ; when the op touches a specific item
     :fee-amount number      ; when :op :waive-late-fee
     :stake :low|:medium|:high
     :confidence 0.0-1.0
     :rationale str}
  LLM parse failures always yield `:confidence 0.0` (never fabricate
  confidence), which forces the governor to escalate/hold."
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [kotoba.lang.text :as str]))

(defprotocol Advisor
  (-advise [advisor store request] "request -> proposal map"))

(defn- infer
  "Deterministic mock inference: reads the request's declared
  op/item/fee straight through (a stand-in for what an LLM would
  extract from free text), with a stake-derived confidence."
  [_store {:keys [op stake item-id fee-amount] :as request}]
  {:op op
   :effect :propose
   :item-id item-id
   :fee-amount fee-amount
   :stake (or stake :low)
   :confidence (case (or stake :low) :high 0.7 :medium 0.85 :low 0.95)
   :rationale (str "proposed " (name op) " for patron " (:patron-id request))})

(defn mock-advisor []
  (reify Advisor
    (-advise [_ store request] (infer store request))))

(def ^:private system-prompt
  "You are a library circulation-desk advisor. Given an operation
   request, propose an :op, the relevant :item-id and (for a fee
   waiver) :fee-amount, an honest :confidence (0.0-1.0), and a :stake
   (:low/:medium/:high). Never fabricate confidence you don't have.
   Never propose an action on an item under legal hold, and never
   propose restricting a patron's account or purging a patron's
   record as anything other than a proposal — the governor decides.")

(defn- parse-proposal [content]
  (try
    (let [p (edn/read-string content)]
      (if (map? p)
        (assoc p :effect :propose)
        {:op :unknown :effect :propose :confidence 0.0 :stake :high
         :rationale "unparseable LLM response"}))
    (catch #?(:clj Exception :cljs js/Error) _
      {:op :unknown :effect :propose :confidence 0.0 :stake :high
       :rationale "LLM response parse failure"})))

(defn llm-advisor
  "Wraps a `langchain.model/ChatModel`. `gen-opts` is passed through to
  `model/-generate`. Kept decoupled from any concrete model so this ns
  has no hard dependency beyond `langchain.model`'s protocol."
  [chat-model model-generate-fn gen-opts]
  (reify Advisor
    (-advise [_ _store request]
      (let [msgs [{:role :system :content system-prompt}
                  {:role :user :content (str "operation request: " (pr-str request))}]
            resp (model-generate-fn chat-model msgs gen-opts)]
        (parse-proposal (:content resp))))))
