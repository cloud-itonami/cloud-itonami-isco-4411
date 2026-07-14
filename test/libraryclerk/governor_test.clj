(ns libraryclerk.governor-test
  (:require [clojure.test :refer [deftest is testing]]
            [libraryclerk.store :as store]
            [libraryclerk.governor :as governor]))

(defn- fresh-store []
  (let [st (store/mem-store)]
    (store/register-patron! st {:patron-id "patron-1" :name "Alex Reader" :verified? true})
    (store/register-item! st {:item-id "item-1" :title "Middlemarch" :legal-hold? false})
    (store/register-item! st {:item-id "item-2" :title "Sealed Archive Box 7" :legal-hold? true})
    st))

(defn- checkout-op [item-id]
  {:op :check-out-item :effect :propose :item-id item-id :confidence 0.9 :stake :low})

(def ^:private req {:patron-id "patron-1"})

(deftest ok-on-clean-checkout
  (let [st (fresh-store)
        v (governor/check req {} (checkout-op "item-1") st)]
    (is (:ok? v))
    (is (not (:hard? v)))
    (is (not (:escalate? v)))))

(deftest hard-on-unregistered-patron
  (let [st (fresh-store)
        v (governor/check {:patron-id "no-such-patron"} {} (checkout-op "item-1") st)]
    (is (:hard? v))
    (is (some #(= :no-patron (:rule %)) (:violations v)))))

(deftest hard-on-unverified-patron
  (let [st (fresh-store)]
    (store/register-patron! st {:patron-id "patron-2" :name "Not Yet Verified" :verified? false})
    (let [v (governor/check {:patron-id "patron-2"} {} (checkout-op "item-1") st)]
      (is (:hard? v))
      (is (some #(= :no-patron (:rule %)) (:violations v))))))

(deftest hard-on-no-actuation-violation
  (let [st (fresh-store)
        v (governor/check req {} (assoc (checkout-op "item-1") :effect :direct-write) st)]
    (is (:hard? v))
    (is (some #(= :no-actuation (:rule %)) (:violations v)))))

(deftest hard-on-unknown-item
  (let [st (fresh-store)
        v (governor/check req {} (checkout-op "item-ghost") st)]
    (is (:hard? v))
    (is (some #(= :unknown-item (:rule %)) (:violations v)))))

(deftest hard-on-item-under-legal-hold-even-at-high-confidence
  (testing "no action on a legal-hold item regardless of advisor confidence"
    (let [st (fresh-store)
          v (governor/check req {} (assoc (checkout-op "item-2") :confidence 0.99) st)]
      (is (:hard? v))
      (is (some #(= :item-under-legal-hold (:rule %)) (:violations v))))))

(deftest escalates-on-large-late-fee-waiver
  (let [st (fresh-store)
        proposal {:op :waive-late-fee :effect :propose :fee-amount 25
                  :confidence 0.9 :stake :high}
        v (governor/check req {} proposal st)]
    (is (:escalate? v))
    (is (not (:hard? v)))))

(deftest ok-on-small-late-fee-waiver
  (let [st (fresh-store)
        proposal {:op :waive-late-fee :effect :propose :fee-amount 2
                  :confidence 0.9 :stake :low}
        v (governor/check req {} proposal st)]
    (is (:ok? v))
    (is (not (:escalate? v)))))

(deftest escalates-on-restrict-patron-account
  (let [st (fresh-store)
        proposal {:op :restrict-patron-account :effect :propose
                  :confidence 0.99 :stake :high}
        v (governor/check req {} proposal st)]
    (is (:escalate? v))
    (is (not (:hard? v)))))

(deftest escalates-on-purge-patron-record
  (let [st (fresh-store)
        proposal {:op :purge-patron-record :effect :propose
                  :confidence 0.99 :stake :high}
        v (governor/check req {} proposal st)]
    (is (:escalate? v))
    (is (not (:hard? v)))))

(deftest escalates-on-low-confidence
  (let [st (fresh-store)
        v (governor/check req {} (assoc (checkout-op "item-1") :confidence 0.2) st)]
    (is (:escalate? v))
    (is (not (:hard? v)))))

(deftest store-records-and-ledger-append-only
  (let [st (fresh-store)]
    (store/commit-record! st {:patron-id "patron-1" :op :check-out-item :item-id "item-1"})
    (store/append-ledger! st {:disposition :commit})
    (is (= 1 (count (store/records-of st "patron-1"))))
    (is (= 1 (count (store/ledger st))))))
