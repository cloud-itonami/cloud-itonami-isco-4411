# cloud-itonami-isco-4411

Open Occupation Blueprint for **ISCO-08 4411**: Library Clerks.

This repository designs a forkable OSS business for an independent library and archive support practice: a shelving, sorting and retrieval robot manages the physical collection under a governor-gated actor, so the practice keeps its own circulation records instead of renting a closed library-management SaaS.

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot performs
the physical domain work**. Here a shelving, sorting and retrieval robot performs book/media reshelving, sorting and hold-shelf retrieval under an actor that proposes
actions and an independent **Library Services Governor** that gates them. The governor never
dispatches hardware itself; `:high`/`:safety-critical` actions (such as
restricted/archival material release) require human sign-off.

A live sample of the operator console (robotics safety console, shared template) is rendered in [docs/samples/operator-console.html](docs/samples/operator-console.html) — pure-data HTML output of `kotoba.robotics.ui`.

## Core Contract

```text
patron request + collection catalog + circulation policy
        |
        v
Library Advisor -> Library Services Governor -> circulate/release, or human sign-off
        |
        v
robot actions (gated) + operating records + audit ledger
```

No automated advice can dispatch a robot action the governor refuses, suppress
an operating record, or disclose sensitive data without governor approval and
audit evidence.

## Capability layer

Resolves via [`kotoba-lang/occupation`](https://github.com/kotoba-lang/occupation)
(ISCO-08 `4411`). Required capabilities:

- :robotics
- :forms
- :audit-ledger

See [`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md).

## License

AGPL-3.0-or-later.
