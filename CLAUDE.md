# CLAUDE.md

Bank of Anthos is our retail banking platform: a web frontend, the account and contact services
behind it, and an append-only ledger of transactions, each running as its own service on
Kubernetes. This guide is for engineers joining a ledger or payments team, and covers what you
need to hold in your head before your first change.

## Standards

`compliance-rules.md` at the repo root is the engineering standard for the ledger and payment
services. Read it before changing anything under `src/ledger/`.

## Building and testing

The Java services are Maven modules under a root aggregator pom. Narrow the run to what you are
changing:

```sh
mvn test                                              # all modules
mvn test -pl src/ledger/ledgerwriter                  # one module
mvn test -pl src/ledger/ledgerwriter -Dtest=TransactionValidatorTest
mvn test -pl src/ledger/ledgerwriter -Dtest=TransactionValidatorTest#methodName
```

## How a payment is written

`POST /transactions` on `ledgerwriter` is the only code path that writes to the ledger. In order:

1. Reject the request if its `requestUuid` is already in the in-memory dedupe cache (1 hour TTL).
2. `TransactionValidator` checks account and routing number format, that the sender is the
   authenticated account when the transaction is local, that sender and receiver differ, and that
   the amount is positive.
3. For local senders, fetch the balance from `balancereader` and reject if it does not cover the
   amount.
4. Save through JPA.

The `TRANSACTIONS` table is append-only and the database enforces it: rules named `PREVENT_UPDATE`
and `PREVENT_DELETE` silently discard any update or delete. Nothing in the ledger can be edited
after the fact, so a mistake is corrected by writing a compensating transaction.

## How balances and history are read

`balancereader` and `transactionhistory` never query the ledger per request. Each runs a background
`LedgerReader` thread that polls `TRANSACTIONS` every `POLL_MS` milliseconds (default 100) for rows
newer than the last id it saw, and folds each one into an in-memory cache keyed by account.
Requests are served from that cache.

So a balance or history read can lag a committed write by up to one poll interval — write-then-read
tests have to allow for it — and the logic that mutates the cache lives in each controller's reader
callback, not in the repository.

## Money

Amounts are integer cents everywhere: `AMOUNT INT` in the schema, `Integer` in Java. `frontend.py`
is the only place dollars exist, converting on the way in and formatting on the way out. Nothing
behind it should ever see a decimal amount.

## Configuration

Values reach a service as environment variables from Kubernetes ConfigMaps, read in Java as
`@Value("${NAME}")`. Always give the annotation a default and document it there —
`@Value("${POLL_MS:100}")` — so the service behaves sensibly when the ConfigMap has not caught up.

## House conventions

- `src/ledgermonolith/` is a separate build for VM deployment. It is out of scope for ledger
  service tickets; keeping it in step is handled as its own piece of work.
- A service configuration change edits that service's own `k8s/base` manifest. Do not update the
  flat `kubernetes-manifests/` copy in the same change.
- Every source file carries an Apache-2.0 header with `Google LLC` as the copyright holder. A bot
  fails pull requests without one, so copy the header from a neighbouring file.
