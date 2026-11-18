# OBSI PED Extension Proposal (`--new-ped`)

## 1. Problem Statement
The current static Potentially Erroneous Data (PED) threshold does not reflect predictable shifts in market liquidity for the XYZ token:
1. **Weekday vs. Weekend:** Lower weekend liquidity forced a wide static PED limit, leaving weekday calculations under-protected.
2. **Token Unlock Events:** Announced unlocks cause a ~48-hour surge in liquidity where tighter outlier detection is warranted.
3. **Persistent Exchange Premiums:** Certain exchanges trade at a structural premium over the median, which may trigger false-positive PED exclusions.

---

## 2. Approaches Compared

### Approach A: Deterministic Regime-Based PED (Selected)
Replace the single static PED limit with three transparent, calendar- and event-driven liquidity regimes evaluated against `calculationTime` (UTC):

| Regime | Condition (UTC) | PED Limit | Financial Rationale |
| :--- | :--- | :--- | :--- |
| **Unlock Window** | `0 <= (calculationTime - unlockStart) <= 48h` | **1.0%** | Elevated order book depth compresses legitimate cross-exchange dispersion. |
| **Weekday Baseline** | Monday 00:00 – Friday 23:59 (outside unlock) | **2.0%** | Normal institutional liquidity supports standard outlier bounds. |
| **Weekend** | Saturday 00:00 – Sunday 23:59 (outside unlock) | **5.0%** | Thinner books naturally produce wider, legitimate price dispersion. |

* **Precedence:** If an unlock window overlaps with a weekend, the **Unlock Window** regime takes precedence because unlock-driven liquidity dominates weekend thinness.
* **Pros:** Deterministic, stateless, easy to audit, and simple to explain to index clients.
* **Cons:** Does not address persistent exchange premiums in this phase.

### Approach B: Live Spread-Scaled PED with Exchange Premium Offsets
Dynamically set the PED limit as a multiple of the median bid-ask spread in the current snapshot, and subtract a configured `structuralPremiumBps` per exchange before testing for deviation.

* **Pros:** Automatically reacts to liquidity shifts (weekends and unlocks) and accounts for persistent exchange premiums.
* **Cons:**
    * **Low transparency:** The threshold changes every snapshot, making client communication and auditability harder.
    * **Masking risk:** A malfunctioning exchange with a blown-out spread can inflate the threshold, and static premium offsets risk hiding genuine exchange solvency or de-peg events.
    * **Governance overhead:** Requires ongoing statistical calibration to maintain per-exchange offsets.

---

## 3. Decision & Rationale
We select **Approach A (Deterministic Regime-Based PED)**.

1. **Financial Grounding:** Matching outlier tolerances to known liquidity regimes is a standard institutional index practice—tighter bounds when depth is high, wider bounds when depth is thin.
2. **Client Clarity:** End clients can immediately understand and independently verify which PED limit applied at any `calculationTime`.
3. **Pragmatic Scope:** Solving weekday/weekend and unlock-window regimes delivers immediate accuracy gains without introducing the governance and masking risks of per-exchange premium offsets. Persistent premiums can be revisited in a future phase once sufficient historical data is collected.

---

## 4. Input JSON Schema Changes (`data/new-ped.json`)
To support unlock windows without breaking backward compatibility, one optional field is added to the root JSON object:

* **`unlockEvents`** (`array of ISO-8601 UTC timestamp strings`, optional): List of announced Token XYZ unlock start times. If omitted or empty, only Weekday/Weekend regimes apply.

### Example (`data/new-ped.json`)
```json
{
  "calculationTime": "2026-10-27T12:00:00Z",
  "unlockEvents": [
    "2026-10-26T18:00:00Z"
  ],
  "books": [
    {
      "exchange": "Alpha",
      "bids": [[100.0, 1.2], [99.8, 1.3], [99.0, 2.0], [98.0, 2.0]],
      "asks": [[100.4, 1.1], [100.6, 1.4], [101.2, 2.0], [102.0, 2.0]]
    },
    {
      "exchange": "Beta",
      "bids": [[100.2, 0.8], [99.8, 1.0], [99.1, 2.0], [98.1, 2.0]],
      "asks": [[100.3, 0.8], [100.6, 1.0], [101.1, 2.0], [102.1, 2.0]]
    },
    {
      "exchange": "Gamma",
      "bids": [[100.6, 1.0]],
      "asks": [[100.5, 1.0]]
    },
    {
      "exchange": "Delta",
      "bids": [[149.0, 1.0]],
      "asks": [[151.0, 1.0]]
    }
  ]
}
