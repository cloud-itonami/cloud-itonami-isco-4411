(ns libraryclerk.store
  "SSoT for the ISCO-08 4411 independent library & archive support
  practice actor. Store is a protocol injected into the
  `libraryclerk.actor` StateGraph — `MemStore` is the default,
  deterministic, zero-dep backend; a Datomic/kotoba-server-backed
  implementation can be swapped in without touching the actor or
  governor (itonami actor pattern, per ADR-2607011000 / CLAUDE.md
  Actors section). Modeled on cloud-itonami-isco-4214's
  debtcollection.store.

  Domain:

    patron  — a registered library patron {:patron-id :name
              :verified? boolean}. `:verified?` is the registered
              verification flag a circulation-desk operator sets once
              the patron's identity/membership has been confirmed —
              an unverified or unregistered patron may never be the
              subject of a circulation or account action.
    item    — a registered collection item {:item-id :title
              :legal-hold? boolean}. `:legal-hold?` marks an item as
              held-for-legal/under-investigation (e.g. restricted or
              archival material subject to a legal hold) — no
              circulation action may touch such an item regardless of
              advisor confidence.
    record  — a committed operating record under a patron (a
              check-out, check-in, reservation, fee waiver, lost-item
              report, account restriction or record purge) — written
              ONLY via commit-record!, never mutated in place.
    ledger  — an append-only audit trail of every proposal/verdict/
              disposition, regardless of outcome (commit or hold).")

(defprotocol Store
  (patron [s patron-id])
  (item [s item-id])
  (records-of [s patron-id])
  (ledger [s])
  (register-patron! [s patron])
  (register-item! [s it])
  (commit-record! [s record])
  (append-ledger! [s fact]))

(defrecord MemStore [a]
  Store
  (patron [_ patron-id] (get-in @a [:patrons patron-id]))
  (item [_ item-id] (get-in @a [:items item-id]))
  (records-of [_ patron-id] (filter #(= patron-id (:patron-id %)) (:records @a)))
  (ledger [_] (:ledger @a))
  (register-patron! [s patron]
    (swap! a assoc-in [:patrons (:patron-id patron)] patron) s)
  (register-item! [s it]
    (swap! a assoc-in [:items (:item-id it)] it) s)
  (commit-record! [s record]
    (swap! a update :records (fnil conj []) record) s)
  (append-ledger! [s fact]
    (swap! a update :ledger (fnil conj []) fact) s))

(defn mem-store
  ([] (mem-store {}))
  ([seed] (->MemStore (atom (merge {:patrons {} :items {} :records [] :ledger []}
                                    seed)))))
