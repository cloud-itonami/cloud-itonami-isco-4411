(ns libraryclerk.actor-test
  (:require [clojure.test :refer [deftest is testing]]
            [libraryclerk.actor :as actor]
            [libraryclerk.store :as store]))

(defn- fresh-store []
  (let [st (store/mem-store)]
    (store/register-patron! st {:patron-id "patron-1" :name "Alex Reader" :verified? true})
    (store/register-item! st {:item-id "item-1" :title "Middlemarch" :legal-hold? false})
    (store/register-item! st {:item-id "item-2" :title "Sealed Archive Box 7" :legal-hold? true})
    st))

(deftest commits-a-clean-low-risk-checkout
  (let [st (fresh-store)
        graph (actor/build-graph {:store st})
        request {:patron-id "patron-1" :op :check-out-item :stake :low :item-id "item-1"}
        result (actor/run-request! graph request {} "thread-1")]
    (is (= :done (:status result)))
    (is (some? (get-in result [:state :record])))
    (is (= 1 (count (store/records-of st "patron-1"))))))

(deftest holds-on-unverified-patron-without-committing
  (let [st (fresh-store)
        graph (actor/build-graph {:store st})
        request {:patron-id "no-such-patron" :op :check-out-item :stake :low :item-id "item-1"}
        result (actor/run-request! graph request {} "thread-2")]
    (is (= :done (:status result)))
    (is (nil? (get-in result [:state :record])))
    (is (empty? (store/records-of st "no-such-patron")))
    (is (= :hold (:disposition (:state result))))))

(deftest holds-on-item-under-legal-hold-without-committing
  (let [st (fresh-store)
        graph (actor/build-graph {:store st})
        request {:patron-id "patron-1" :op :check-out-item :stake :high :item-id "item-2"}
        result (actor/run-request! graph request {} "thread-3")]
    (is (= :done (:status result)))
    (is (nil? (get-in result [:state :record])))
    (is (empty? (store/records-of st "patron-1")))
    (is (= :hold (:disposition (:state result))))))

(deftest interrupts-then-commits-on-human-approval-for-record-purge
  (let [st (fresh-store)
        graph (actor/build-graph {:store st})
        ;; purging a patron's borrowing history always escalates (governor invariant)
        request {:patron-id "patron-1" :op :purge-patron-record :stake :high}
        interrupted (actor/run-request! graph request {} "thread-4")]
    (is (= :interrupted (:status interrupted)))
    (is (empty? (store/records-of st "patron-1")))
    (let [resumed (actor/approve! graph "thread-4")]
      (is (= :done (:status resumed)))
      (is (some? (get-in resumed [:state :record])))
      (is (= 1 (count (store/records-of st "patron-1")))))))
