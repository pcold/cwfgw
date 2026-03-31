#!/usr/bin/env python3
"""Parse fantasy golf PDF data and generate a Flyway SQL seed migration."""

import hashlib

# Fixed UUIDs for reproducibility
LEAGUE_ID = "11111111-1111-1111-1111-111111111111"
DRAFT_ID  = "22222222-2222-2222-2222-222222222222"

TEAMS = [
    ("BROWN", "Brown"), ("BURRELL", "Burrell"), ("POCZIK", "Poczik"),
    ("KANHAM", "Kanham"), ("ROSEH2O", "Roseh2o"), ("KIRK", "Kirk"),
    ("WOMBLE", "Womble"), ("BHCP", "Bhcp"), ("BLAU", "Blau"),
    ("ROWLY", "Rowly"), ("PAYRAY", "Payray"), ("GALBRAITH", "Galbraith"),
    ("OBAZ", "Obaz"),
]

def team_uuid(i):
    return f"aaaaaaaa-aaaa-aaaa-aaaa-{i:012d}"

def golfer_uuid(name):
    h = hashlib.md5(name.encode()).hexdigest()
    return f"{h[:8]}-{h[8:12]}-{h[12:16]}-{h[16:20]}-{h[20:32]}"

def tournament_uuid(week):
    return f"bbbbbbbb-bbbb-bbbb-bbbb-{week:012d}"

# Draft grid: round -> list of (golfer_name, ownership_pct) in team order (1-13)
DRAFT_GRID = {
    1: [("SCHEFFLER", 75), ("FLEETWOOD", 100), ("SCHAUFFELE", 100), ("MCILROY", 100),
        ("B. GRIFFIN", 100), ("MACINTYRE", 100), ("SCHEFFLER", 25), ("MORIKAWA", 100),
        ("HENLEY", 100), ("THOMAS", 100), ("CANTLAY", 100), ("CAM. YOUNG", 100), ("STRAKA", 100)],
    2: [("ROSE", 100), ("GOTTERUP", 100), ("ENGLISH", 100), ("BRADLEY", 100),
        ("CONNERS", 100), ("MCNEALY", 100), ("FITZPATRICK", 100), ("MATSUYAMA", 100),
        ("HOVLAND", 100), ("SPAUN", 100), ("BURNS", 100), ("ABERG", 100), ("HOEY", 100)],
    3: [("LOWRY", 100), ("IM", 100), ("NOREN", 100), ("RAI", 100),
        ("CLARK", 100), ("N. TAYLOR", 100), ("THORBJORNSEN", 100), ("PENDRITH", 100),
        ("NOVAK", 100), ("CLANTON", 100), ("BHATIA", 100), ("M. KIM", 100), ("HALL", 100)],
    4: [("FINAU", 100), ("DAY", 100), ("KNAPP", 100), ("POSTON", 100),
        ("HARMAN", 100), ("BRIDGEMAN", 100), ("R. HOJGAARD", 100), ("BRENNAN", 100),
        ("GREYSERMAN", 100), ("M.W. LEE", 100), ("S.W. KIM", 100), ("MCCARTY", 100), ("KITAYAMA", 100)],
    5: [("GERARD", 100), ("VALIMAKI", 100), ("KEEFER", 100), ("BEZUIDENHOUT", 100),
        ("ECHAVARRIA", 100), ("FOX", 100), ("FOWLER", 100), ("POTGIETER", 100),
        ("AN", 100), ("SPIETH", 100), ("HIGGO", 100), ("HOGE", 100), ("STEVENS", 100)],
    6: [("CAMPBELL", 100), ("BERGER", 100), ("N. HOJGAARD", 100), ("GLOVER", 100),
        ("KIRK", 100), ("SHIPLEY", 100), ("REITAN", 100), ("HOMA", 100),
        ("DETRY", 100), ("THEEGALA", 100), ("JAEGER", 100), ("MCCARTHY", 100), ("WHALEY", 100)],
    7: [("PENGE", 100), ("COLE", 100), ("HORSHEL", 100), ("ZALATORIS", 100),
        ("DECHAMBEAU", 100), ("SMALLEY", 100), ("NEERGAARD-PETERSEN", 100), ("THOMPSON", 100),
        ("DUNLAP", 100), ("TOM KIM", 100), ("GRILLO", 100), ("CAULEY", 100), ("SCHMID", 100)],
    8: [("VEGAS", 100), ("ECKROAT", 100), ("FORD", 100), ("SCOTT", 100),
        ("RODGERS", 100), ("HUGHES", 100), ("TH. OLESON", 100), ("HIGHSMITH", 100),
        ("SUBER", 100), ("PIER. COODY", 100), ("SMOTHERMAN", 100), ("MCGREEVY", 100), ("FISHBURN", 100)],
}

# Split golfer display names into first/last for the DB
# Most are last-name only; some have initials/prefixes
GOLFER_NAMES = {
    "SCHEFFLER": ("Scottie", "Scheffler"),
    "FLEETWOOD": ("Tommy", "Fleetwood"),
    "SCHAUFFELE": ("Xander", "Schauffele"),
    "MCILROY": ("Rory", "McIlroy"),
    "B. GRIFFIN": ("Ben", "Griffin"),
    "MACINTYRE": ("Robert", "MacIntyre"),
    "MORIKAWA": ("Collin", "Morikawa"),
    "HENLEY": ("Russell", "Henley"),
    "THOMAS": ("Justin", "Thomas"),
    "CANTLAY": ("Patrick", "Cantlay"),
    "CAM. YOUNG": ("Cameron", "Young"),
    "STRAKA": ("Sepp", "Straka"),
    "ROSE": ("Justin", "Rose"),
    "GOTTERUP": ("Chris", "Gotterup"),
    "ENGLISH": ("Harris", "English"),
    "BRADLEY": ("Keegan", "Bradley"),
    "CONNERS": ("Corey", "Conners"),
    "MCNEALY": ("Maverick", "McNealy"),
    "FITZPATRICK": ("Matt", "Fitzpatrick"),
    "MATSUYAMA": ("Hideki", "Matsuyama"),
    "HOVLAND": ("Viktor", "Hovland"),
    "SPAUN": ("J.J.", "Spaun"),
    "BURNS": ("Sam", "Burns"),
    "ABERG": ("Ludvig", "Aberg"),
    "HOEY": ("David", "Hoey"),
    "LOWRY": ("Shane", "Lowry"),
    "IM": ("Sungjae", "Im"),
    "NOREN": ("Alex", "Noren"),
    "RAI": ("Aaron", "Rai"),
    "CLARK": ("Wyndham", "Clark"),
    "N. TAYLOR": ("Nick", "Taylor"),
    "THORBJORNSEN": ("Michael", "Thorbjornsen"),
    "PENDRITH": ("Taylor", "Pendrith"),
    "NOVAK": ("Andrew", "Novak"),
    "CLANTON": ("Carson", "Clanton"),
    "BHATIA": ("Akshay", "Bhatia"),
    "M. KIM": ("Michael", "Kim"),
    "HALL": ("Harry", "Hall"),
    "FINAU": ("Tony", "Finau"),
    "DAY": ("Jason", "Day"),
    "KNAPP": ("Jake", "Knapp"),
    "POSTON": ("J.T.", "Poston"),
    "HARMAN": ("Brian", "Harman"),
    "BRIDGEMAN": ("Jackson", "Bridgeman"),
    "R. HOJGAARD": ("Rasmus", "Hojgaard"),
    "BRENNAN": ("Brendon", "Brennan"),
    "GREYSERMAN": ("Max", "Greyserman"),
    "M.W. LEE": ("Min Woo", "Lee"),
    "S.W. KIM": ("Si Woo", "Kim"),
    "MCCARTY": ("Bud", "McCarty"),
    "KITAYAMA": ("Kurt", "Kitayama"),
    "GERARD": ("Rico", "Gerard"),
    "VALIMAKI": ("Sami", "Valimaki"),
    "KEEFER": ("Trevor", "Keefer"),
    "BEZUIDENHOUT": ("Christiaan", "Bezuidenhout"),
    "ECHAVARRIA": ("Nico", "Echavarria"),
    "FOX": ("Ryan", "Fox"),
    "FOWLER": ("Rickie", "Fowler"),
    "POTGIETER": ("Aldrich", "Potgieter"),
    "AN": ("Byeong Hun", "An"),
    "SPIETH": ("Jordan", "Spieth"),
    "HIGGO": ("Garrick", "Higgo"),
    "HOGE": ("Tom", "Hoge"),
    "STEVENS": ("Ben", "Stevens"),
    "CAMPBELL": ("Patrick", "Campbell"),
    "BERGER": ("Daniel", "Berger"),
    "N. HOJGAARD": ("Nicolai", "Hojgaard"),
    "GLOVER": ("Lucas", "Glover"),
    "KIRK": ("Chris", "Kirk"),
    "SHIPLEY": ("Neal", "Shipley"),
    "REITAN": ("Vincent", "Reitan"),
    "HOMA": ("Max", "Homa"),
    "DETRY": ("Thomas", "Detry"),
    "THEEGALA": ("Sahith", "Theegala"),
    "JAEGER": ("Stephan", "Jaeger"),
    "MCCARTHY": ("Denny", "McCarthy"),
    "WHALEY": ("Vincent", "Whaley"),
    "PENGE": ("John", "Penge"),
    "COLE": ("Eric", "Cole"),
    "HORSHEL": ("Billy", "Horschel"),
    "ZALATORIS": ("Will", "Zalatoris"),
    "DECHAMBEAU": ("Bryson", "DeChambeau"),
    "SMALLEY": ("Alex", "Smalley"),
    "NEERGAARD-PETERSEN": ("Niklas", "Neergaard-Petersen"),
    "THOMPSON": ("Davis", "Thompson"),
    "DUNLAP": ("Nick", "Dunlap"),
    "TOM KIM": ("Tom", "Kim"),
    "GRILLO": ("Emiliano", "Grillo"),
    "CAULEY": ("Bud", "Cauley"),
    "SCHMID": ("Matti", "Schmid"),
    "VEGAS": ("Jhonattan", "Vegas"),
    "ECKROAT": ("Austin", "Eckroat"),
    "FORD": ("Tyson", "Ford"),
    "SCOTT": ("Adam", "Scott"),
    "RODGERS": ("Patrick", "Rodgers"),
    "HUGHES": ("Mackenzie", "Hughes"),
    "TH. OLESON": ("Thorbjorn", "Olesen"),
    "HIGHSMITH": ("Joe", "Highsmith"),
    "SUBER": ("Trace", "Suber"),
    "PIER. COODY": ("Pierceson", "Coody"),
    "SMOTHERMAN": ("Austin", "Smotherman"),
    "MCGREEVY": ("Max", "McGreevy"),
    "FISHBURN": ("Patrick", "Fishburn"),
}

TOURNAMENTS = [
    (1, "2026 Week 1", "2026-01-15", "2026-01-18"),
    (2, "2026 Week 2", "2026-01-22", "2026-01-25"),
    (4, "2026 Weeks 3-4", "2026-02-05", "2026-02-08"),
    (5, "2026 Week 5", "2026-02-12", "2026-02-15"),
    (7, "2026 Weeks 6-7", "2026-02-26", "2026-03-01"),
    (10, "2026 Weeks 8-10", "2026-03-19", "2026-03-22"),
]

# Cumulative earnings per (team_idx, golfer_key) through each known week
CUM = {}

CUM[1] = {
    (0, "GERARD"): 12.0,
    (1, "GOTTERUP"): 18.0, (1, "BERGER"): 4.0,
    (4, "RODGERS"): 10.0,
    (5, "MACINTYRE"): 7.5, (5, "BRIDGEMAN"): 7.5,
    (7, "PENDRITH"): 4.0,
    (12, "HALL"): 4.0,
}

CUM[2] = {
    (0, "SCHEFFLER"): 13.5, (0, "GERARD"): 21.25,
    (1, "GOTTERUP"): 18.0, (1, "DAY"): 9.25, (1, "BERGER"): 4.0,
    (4, "RODGERS"): 10.0,
    (5, "MACINTYRE"): 7.5, (5, "BRIDGEMAN"): 7.5,
    (6, "SCHEFFLER"): 4.5,
    (7, "PENDRITH"): 4.0,
    (8, "HENLEY"): 1.8, (8, "HOVLAND"): 1.0,
    (9, "THEEGALA"): 1.8,
    (10, "S.W. KIM"): 5.5, (10, "SMOTHERMAN"): 1.8,
    (11, "MCCARTY"): 9.25, (11, "HOGE"): 1.8,
    (12, "HALL"): 4.0, (12, "STEVENS"): 5.5,
}

CUM[4] = {
    (0, "SCHEFFLER"): 18.9, (0, "ROSE"): 18.0, (0, "GERARD"): 21.25,
    (1, "GOTTERUP"): 36.0, (1, "DAY"): 9.25, (1, "BERGER"): 4.0,
    (2, "KNAPP"): 10.5, (2, "N. HOJGAARD"): 7.2,
    (4, "RODGERS"): 10.0,
    (5, "MACINTYRE"): 7.5, (5, "MCNEALY"): 2.0, (5, "BRIDGEMAN"): 7.5,
    (6, "SCHEFFLER"): 6.3, (6, "FITZPATRICK"): 3.0, (6, "THORBJORNSEN"): 7.2,
    (7, "MATSUYAMA"): 12.0, (7, "PENDRITH"): 4.0,
    (8, "HENLEY"): 1.8, (8, "HOVLAND"): 1.0, (8, "NOVAK"): 4.0,
    (9, "THEEGALA"): 5.8, (9, "PIER. COODY"): 11.0,
    (10, "S.W. KIM"): 22.7, (10, "BHATIA"): 7.2, (10, "JAEGER"): 6.5, (10, "SMOTHERMAN"): 1.8,
    (11, "MCCARTY"): 9.25, (11, "HOGE"): 1.8,
    (12, "HALL"): 4.0, (12, "STEVENS"): 5.5,
}

CUM[5] = {
    (0, "SCHEFFLER"): 24.525, (0, "ROSE"): 18.0, (0, "LOWRY"): 1.5, (0, "GERARD"): 21.25,
    (1, "FLEETWOOD"): 7.5, (1, "GOTTERUP"): 36.0, (1, "DAY"): 9.25, (1, "BERGER"): 4.0,
    (2, "KNAPP"): 12.0, (2, "N. HOJGAARD"): 7.2,
    (4, "ECHAVARRIA"): 1.5, (4, "RODGERS"): 10.0,
    (5, "MACINTYRE"): 7.5, (5, "MCNEALY"): 2.0, (5, "BRIDGEMAN"): 9.0,
    (6, "SCHEFFLER"): 8.175, (6, "FITZPATRICK"): 3.0, (6, "THORBJORNSEN"): 7.2,
    (7, "MORIKAWA"): 18.0, (7, "MATSUYAMA"): 13.5, (7, "PENDRITH"): 4.0,
    (8, "HENLEY"): 1.8, (8, "HOVLAND"): 1.0, (8, "NOVAK"): 4.0,
    (9, "THEEGALA"): 5.8, (9, "M.W. LEE"): 11.0, (9, "PIER. COODY"): 11.0,
    (10, "S.W. KIM"): 22.7, (10, "BHATIA"): 12.7, (10, "BURNS"): 5.5, (10, "JAEGER"): 6.5, (10, "SMOTHERMAN"): 1.8,
    (11, "MCCARTY"): 9.25, (11, "HOGE"): 1.8,
    (12, "HALL"): 4.0, (12, "STRAKA"): 11.0, (12, "STEVENS"): 5.5,
}

CUM[7] = {
    (0, "SCHEFFLER"): 24.525, (0, "ROSE"): 18.0, (0, "LOWRY"): 11.5, (0, "GERARD"): 21.25,
    (1, "FLEETWOOD"): 10.3, (1, "GOTTERUP"): 36.0, (1, "DAY"): 9.25, (1, "BERGER"): 4.0,
    (2, "SCHAUFFELE"): 2.8, (2, "KNAPP"): 18.0, (2, "N. HOJGAARD"): 12.2,
    (3, "MCILROY"): 11.0, (3, "SCOTT"): 8.0,
    (4, "ECHAVARRIA"): 19.5, (4, "RODGERS"): 10.0,
    (5, "MACINTYRE"): 7.5, (5, "MCNEALY"): 2.0, (5, "BRIDGEMAN"): 27.0, (5, "FOX"): 2.8,
    (6, "SCHEFFLER"): 8.175, (6, "FITZPATRICK"): 3.0, (6, "THORBJORNSEN"): 7.2, (6, "R. HOJGAARD"): 1.25,
    (7, "MORIKAWA"): 20.8, (7, "MATSUYAMA"): 13.5, (7, "PENDRITH"): 4.0, (7, "POTGIETER"): 7.0,
    (8, "HENLEY"): 1.8, (8, "HOVLAND"): 1.0, (8, "NOVAK"): 4.0,
    (9, "THEEGALA"): 5.8, (9, "M.W. LEE"): 11.0, (9, "PIER. COODY"): 11.0,
    (10, "S.W. KIM"): 22.7, (10, "BHATIA"): 12.7, (10, "BURNS"): 5.5, (10, "JAEGER"): 6.5, (10, "SMOTHERMAN"): 11.8,
    (11, "MCCARTY"): 9.25, (11, "HOGE"): 1.8, (11, "CAM. YOUNG"): 2.8,
    (12, "HALL"): 4.0, (12, "STRAKA"): 11.0, (12, "KITAYAMA"): 11.0, (12, "STEVENS"): 5.5, (12, "SCHMID"): 1.25,
}

CUM[10] = {
    (0, "SCHEFFLER"): 24.525, (0, "ROSE"): 18.0, (0, "LOWRY"): 11.5, (0, "GERARD"): 21.25, (0, "PENGE"): 7.0,
    (1, "FLEETWOOD"): 16.3, (1, "GOTTERUP"): 36.0, (1, "IM"): 7.0, (1, "DAY"): 9.25, (1, "BERGER"): 16.0,
    (2, "SCHAUFFELE"): 29.8, (2, "KNAPP"): 18.0, (2, "N. HOJGAARD"): 12.2,
    (3, "MCILROY"): 11.0, (3, "BEZUIDENHOUT"): 1.13, (3, "SCOTT"): 8.0,
    (4, "ECHAVARRIA"): 19.5, (4, "RODGERS"): 10.0,
    (5, "MACINTYRE"): 23.5, (5, "MCNEALY"): 2.0, (5, "BRIDGEMAN"): 39.0, (5, "FOX"): 2.8,
    (6, "SCHEFFLER"): 8.175, (6, "FITZPATRICK"): 45.0, (6, "THORBJORNSEN"): 7.2, (6, "R. HOJGAARD"): 1.25, (6, "FOWLER"): 2.5,
    (7, "MORIKAWA"): 27.8, (7, "MATSUYAMA"): 13.5, (7, "PENDRITH"): 4.0, (7, "POTGIETER"): 7.0, (7, "THOMPSON"): 8.0,
    (8, "HENLEY"): 6.8, (8, "HOVLAND"): 1.0, (8, "NOVAK"): 4.0,
    (9, "THOMAS"): 6.0, (9, "CLANTON"): 6.0, (9, "THEEGALA"): 10.8, (9, "M.W. LEE"): 16.0, (9, "PIER. COODY"): 11.0,
    (10, "CANTLAY"): 3.5, (10, "S.W. KIM"): 22.7, (10, "BHATIA"): 30.7, (10, "BURNS"): 5.5, (10, "JAEGER"): 10.0, (10, "GRILLO"): 3.5, (10, "SMOTHERMAN"): 11.8,
    (11, "CAM. YOUNG"): 47.8, (11, "ABERG"): 21.0, (11, "MCCARTY"): 9.25, (11, "HOGE"): 1.8,
    (12, "HALL"): 6.5, (12, "STRAKA"): 17.0, (12, "KITAYAMA"): 11.0, (12, "STEVENS"): 5.5, (12, "SCHMID"): 7.25,
}

KNOWN_WEEKS = [1, 2, 4, 5, 7, 10]
WEEK_TO_TOURNAMENT = {1: 1, 2: 2, 4: 4, 5: 5, 7: 7, 10: 10}

def diff_earnings(prev_week, curr_week):
    """Earnings between prev_week (exclusive) and curr_week (inclusive)."""
    results = {}
    if curr_week not in CUM:
        return results
    for key, cum_val in CUM[curr_week].items():
        prev = 0.0
        if prev_week > 0:
            for w in sorted(CUM.keys()):
                if w <= prev_week and key in CUM[w]:
                    prev = CUM[w][key]
        diff = round(cum_val - prev, 3)
        if diff > 0.001:
            results[key] = diff
    return results


def esc(s):
    return s.replace("'", "''")


lines = []
lines.append("-- V010: Seed 2026 season data from PDF weekly reports")
lines.append("-- Auto-generated by scripts/generate_seed.py")
lines.append("")

# League
lines.append(f"INSERT INTO leagues (id, name, season_year, status, max_teams) VALUES ('{LEAGUE_ID}', 'Fantasy Golf 2026', 2026, 'active', 13);")
lines.append("")

# Teams
for i, (name, owner) in enumerate(TEAMS):
    tid = team_uuid(i + 1)
    lines.append(f"INSERT INTO teams (id, league_id, owner_name, team_name) VALUES ('{tid}', '{LEAGUE_ID}', '{esc(owner)}', '{esc(name)}');")
lines.append("")

# Golfers
all_golfers = set()
for rd, picks in DRAFT_GRID.items():
    for name, pct in picks:
        all_golfers.add(name)
all_golfers = sorted(all_golfers)

for name in all_golfers:
    gid = golfer_uuid(name)
    first, last = GOLFER_NAMES[name]
    lines.append(f"INSERT INTO golfers (id, first_name, last_name) VALUES ('{gid}', '{esc(first)}', '{esc(last)}');")
lines.append("")

# Draft
lines.append(f"INSERT INTO drafts (id, league_id, draft_type, status) VALUES ('{DRAFT_ID}', '{LEAGUE_ID}', 'snake', 'completed');")
lines.append("")

# Draft picks + Roster entries
for rd in range(1, 9):
    picks = DRAFT_GRID[rd]
    for team_idx, (golfer_name, ownership_pct) in enumerate(picks):
        tid = team_uuid(team_idx + 1)
        gid = golfer_uuid(golfer_name)
        # Snake: odd rounds forward (1-13), even rounds reverse (13-1)
        if rd % 2 == 1:
            pick_num = (rd - 1) * 13 + team_idx + 1
        else:
            pick_num = (rd - 1) * 13 + (13 - team_idx)
        lines.append(f"INSERT INTO draft_picks (draft_id, team_id, golfer_id, round_num, pick_num) VALUES ('{DRAFT_ID}', '{tid}', '{gid}', {rd}, {pick_num});")
        lines.append(f"INSERT INTO team_rosters (team_id, golfer_id, acquired_via, draft_round, ownership_pct) VALUES ('{tid}', '{gid}', 'draft', {rd}, {ownership_pct});")
lines.append("")

# Tournaments (only the 6 we have data for, using consolidated names)
for week, name, start, end in TOURNAMENTS:
    tour_id = tournament_uuid(week)
    lines.append(f"INSERT INTO tournaments (id, name, season_year, start_date, end_date, status, is_major) VALUES ('{tour_id}', '{esc(name)}', 2026, '{start}', '{end}', 'completed', false);")
lines.append("")

# Fantasy scores
for i, week in enumerate(KNOWN_WEEKS):
    prev_week = KNOWN_WEEKS[i - 1] if i > 0 else 0
    earnings = diff_earnings(prev_week, week)
    if not earnings:
        continue

    tour_id = tournament_uuid(week)
    if week - prev_week > 1 and prev_week > 0:
        lines.append(f"-- Weeks {prev_week+1}-{week} combined")
    else:
        lines.append(f"-- Week {week}")

    for (team_idx, golfer_name), amount in sorted(earnings.items()):
        tid = team_uuid(team_idx + 1)
        gid = golfer_uuid(golfer_name)
        bd = '{"source": "pdf_import"}'
        lines.append(f"INSERT INTO fantasy_scores (league_id, team_id, tournament_id, golfer_id, points, breakdown) VALUES ('{LEAGUE_ID}', '{tid}', '{tour_id}', '{gid}', {amount}, '{bd}');")
    lines.append("")

# League standings
lines.append("-- League standings (from Week 10 PDF)")
TEAM_TOTALS = [82.275, 84.55, 60.0, 20.13, 29.5, 67.3, 64.125, 60.3, 11.8, 49.8, 87.7, 79.85, 47.25]
for i, total in enumerate(TEAM_TOTALS):
    tid = team_uuid(i + 1)
    lines.append(f"INSERT INTO league_standings (league_id, team_id, total_points, tournaments_played) VALUES ('{LEAGUE_ID}', '{tid}', {total}, 10);")

output = "\n".join(lines)
with open("/Users/peter.cold/code/pcold/cwfgw/src/main/resources/db/migration/V010__seed_2026_season.sql", "w") as f:
    f.write(output + "\n")

print(f"Generated {len(lines)} lines")

# Verify totals
total_cum = sum(v for v in CUM[10].values())
total_standings = sum(TEAM_TOTALS)
print(f"Sum of golfer cumulatives: {total_cum}")
print(f"Sum of standings totals: {total_standings}")
print(f"Golfers: {len(all_golfers)}, Roster entries: {sum(len(p) for p in DRAFT_GRID.values())}")
