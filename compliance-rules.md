# FinTechCo — engineering standards for ledger and payment services

Draft. This is the file the review pass at beat 5 checks a change against. It stands in
for what FinTechCo would write for themselves, so it should read like an internal
standards doc, not like a prompt.

Kept deliberately short. A standards file nobody reads is a standards file nobody
follows, and a long one is worse on screen.

---

## Configuration

- No business threshold, limit, or rate is compiled into source. Values that Treasury,
  Compliance, or Risk can change are configuration, injected at startup.
- Configuration has an explicit default and the default is documented where it is
  declared.

## Money

- Monetary amounts are integer minor units. Never floating point, anywhere, for any
  reason.
- Comparisons against a threshold state whether the boundary is inclusive. "At or above"
  and "above" are different controls and Compliance means one of them.

## Audit

- Any decision that holds, rejects, or flags a customer transaction writes an audit
  event.
- An audit event records: the account, the amount, the rule that fired, the configured
  value it fired against, and the timestamp. It does not record credentials or full
  tokens.
- Audit events are written at INFO or above. Never DEBUG — DEBUG is off in production.

## Errors surfaced to customers

- Customer-facing messages come from the shared message constants, not from string
  literals at the throw site.
- A customer-facing message says what happened and what happens next. It does not name
  internal services, thresholds, or rule identifiers.

## Tests

- Every new control has a test at the boundary, one either side of it, and one for the
  configured value being absent.
- Tests assert on the audit event as well as the outcome. A control that works silently
  is not evidence.

## Backward compatibility

- A change that adds a control must not alter behaviour for transactions the control
  does not apply to. Say so in the test.
