"""
Progression simulator for the gens economy.

    python tools/balance_sim.py            # the numbers in config.yml's defaults (edit PARAMS below to try others)

Plays an efficient player: income comes from the belt (speed x average item value x belt-length bonus), and whenever
an upgrade is affordable the one with the best extra-income-per-price is bought. Prints when gen tiers, full speed and
a full belt are reached, in hours of active play. Caches, tokens and milestones are left out (they add a little).

Prices: level n costs base * growth^n * ramp^(n*(n-1)/2). With ramp > 1 every step costs a little more than the step
before, so the start is quick and the late game slows right down.
"""
import math

PARAMS = dict(
    # gen tiers
    tiers=150, base_value=1.0, value_growth=1.12,
    gen_base=100, gen_growth=1.135, gen_extra=0.25, gen_fade=40,
    # belt speed: start x (1 + step*n), up to max_mult
    start_speed=0.5, step=0.25, max_mult=10, speed_base=800, speed_growth=1.5, speed_ramp=1.0,
    # belt length: 8 + 2n segments up to 61
    start_len=8, max_len=61, len_step=2, len_base=200, len_growth=1.5, len_ramp=1.0,
    value_per_segment=0.10,
)


def price(base, growth, ramp, n):
    return base * growth ** n * ramp ** (n * (n - 1) / 2)


def gen_price(p, n):
    """Price of going from gen tier n (0-based) to n + 1, as in FactorySettings.generateTiers."""
    f = 1.0
    for i in range(1, n + 1):
        f *= p["gen_growth"] + p["gen_extra"] * math.exp(-(i - 1) / p["gen_fade"])
    return p["gen_base"] * f


def simulate(p, hours=1000, report=True):
    t = 0.0
    money = 0.0
    tier, speed_lv, length = 0, 0, p["start_len"]
    speed_levels = int(round((p["max_mult"] - 1) / p["step"]))
    marks = {}
    dt = 1.0

    def income(tier, speed_lv, length):
        val = p["base_value"] * p["value_growth"] ** tier
        spd = p["start_speed"] * (1 + p["step"] * speed_lv)
        return spd * val * (1 + p["value_per_segment"] * length)

    while t < hours * 3600:
        money += income(tier, speed_lv, length) * dt
        t += dt
        # buy greedily while something is affordable
        while True:
            cur = income(tier, speed_lv, length)
            options = []
            if tier + 1 < p["tiers"]:
                c = gen_price(p, tier)
                options.append((income(tier + 1, speed_lv, length) - cur) / c if c else 1e9)
                options[-1] = (options[-1], c, "gen")
            if speed_lv < speed_levels:
                c = price(p["speed_base"], p["speed_growth"], p["speed_ramp"], speed_lv)
                options.append(((income(tier, speed_lv + 1, length) - cur) / c, c, "speed"))
            if length < p["max_len"]:
                n = (length - p["start_len"]) // p["len_step"]
                c = price(p["len_base"], p["len_growth"], p["len_ramp"], n)
                options.append(((income(tier, speed_lv, min(p["max_len"], length + p["len_step"])) - cur) / c, c, "len"))
            if not options:
                break
            best = max(options)
            if best[1] > money:
                break
            money -= best[1]
            if best[2] == "gen":
                tier += 1
                for m in (10, 25, 50, 75, 100, 125, 149):
                    if tier == m:
                        marks["gen tier %d" % (m + 1)] = t
            elif best[2] == "speed":
                speed_lv += 1
                if speed_lv == speed_levels:
                    marks["10x speed"] = t
            else:
                length = min(p["max_len"], length + p["len_step"])
                if length == p["max_len"]:
                    marks["full belt"] = t
        if report and t in (60, 300, 600, 1800, 3600):
            print("  %4d min: gen tier %d, speed %.2fx, belt %d" % (t / 60, tier + 1, 1 + p["step"] * speed_lv, length))
        if tier >= p["tiers"] - 1 and speed_lv >= speed_levels and length >= p["max_len"]:
            break
        if t > 3600 and dt < 10:
            dt = 10.0  # coarser steps once the start is past
    if report:
        for k, v in sorted(marks.items(), key=lambda kv: kv[1]):
            print("  %-14s %8.2f h" % (k, v / 3600))
        print("  after %d h: gen tier %d, speed %.2fx, belt %d" % (min(t, hours * 3600) / 3600, tier + 1,
              1 + p["step"] * speed_lv, length))
    return marks


def first_minutes(p, minutes=60):
    """Upgrades bought in the first hour, to check the start feels quick."""
    q = dict(p)
    marks = simulate(q, hours=minutes / 60, report=False)
    return marks


if __name__ == "__main__":
    print("config.yml defaults:")
    simulate(PARAMS, hours=1500)
