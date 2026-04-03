# Castlewood Fantasy Golf (CWFGW)

A fantasy PGA Tour league tracker for the Castlewood group. 13 teams draft real PGA players and compete for weekly payouts based on tournament results.

> **[Full API Specification](docs/API_SPEC.md)** — complete backend spec including database schema, all 59 endpoints, scoring logic, ESPN integration, and report assembly.

## League Rules

### The Draft

- 13 teams, 8-round snake draft
- Each team drafts 8 PGA Tour players
- Players can be split between teams with ownership percentages (earnings divided proportionally)

### Weekly Payouts

Each PGA tournament, the top 10 finishers among all drafted players earn payouts:

| Position | Payout |
|----------|--------|
| 1st      | $18    |
| 2nd      | $12    |
| 3rd      | $10    |
| 4th      | $8     |
| 5th      | $7     |
| 6th      | $6     |
| 7th      | $5     |
| 8th      | $4     |
| 9th      | $3     |
| 10th     | $2     |

### Major Tournaments

All payouts are **doubled** for the four majors and two additional premier events:

- The Masters
- PGA Championship
- U.S. Open
- The Open Championship
- The Players Championship
- Tour Championship

### Tie Handling

- Tied positions average the payouts across all tied slots
- Example: T4 with 3 tied = ($8 + $7 + $6) / 3 = $7.00 each
- **Minimum payout of $1** per player for any tie that overlaps the top-10 zone
- Example: T9 with 5 tied = max(avg($3, $2, $0, $0, $0), $1) = $1.00 each

### Zero-Sum Scoring

- Each top-10 finish earns that payout from every other team
- Formula: `weekly +/- = (your top-10 earnings × 13) − total pot`
- The league is zero-sum — every dollar won is a dollar lost by someone else
- Undrafted players who finish top 10 are tracked but don't affect team payouts

### Late Row Side Bets (Rounds 5–8)

- 4 separate season-long races — one for each draft round (5, 6, 7, 8)
- Winner = the team whose round-N pick has the highest cumulative earnings across all tournaments
- Winner collects **$15** from every other team ($15 × 12 = $180 gross)
- Side bet is not active if all entries are at $0
