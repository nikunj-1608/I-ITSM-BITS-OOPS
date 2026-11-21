#!/usr/bin/env python3
"""Repair the title/description pairing of src/main/resources/sim/corpus.csv (ADR-035, Phase F, decision D16-A).

The corpus has 70 distinct titles but only 20 (HARDWARE: 30) distinct descriptions per category, cycled in an order that
does not follow the titles, so most rows pair a title with the description of another ticket (431 of 630 rows share no
content word). The category of every row is right, so Naive Bayes was trained on the right words; the pairing matters for
the document frequencies of the TF-IDF vectorizer and for anyone reading the file.

Rule (conservative, deterministic): a row whose description already shares a content word with its title is kept; any
other row gets the description of the same category that shares the most content words with its title (ties: the
description used by the fewest rows so far, then the first). A row for which no description shares a word is kept.

Usage: python scripts/data/repair_corpus.py [--check]    (--check only prints the statistics)
"""
import csv
import re
import sys
from collections import Counter, defaultdict

PATH = "src/main/resources/sim/corpus.csv"
STOP = set("""a an the and or of to in on for with is are was were be been it its at by from as that this these those not no
do does did has have had when where which while who we you your i my our their they them he she his her cannot can will
would could should may might must also into onto over under between after before during than then there here very just
only some any all each every both few more most other such same so too""".split())


def words(text):
    return {w for w in re.findall(r"[a-z0-9]+", text.lower()) if w not in STOP and len(w) > 2}


def stats(rows):
    no_overlap = sum(1 for _, t, d in rows if not (words(t) & words(d)))
    distinct = len({d for _, _, d in rows})
    return no_overlap, distinct


def repair(rows):
    pools = defaultdict(list)
    for category, _, description in rows:
        if description not in pools[category]:
            pools[category].append(description)
    used = Counter()
    repaired = []
    for category, title, description in rows:
        if words(title) & words(description):
            repaired.append((category, title, description))
            used[(category, description)] += 1
            continue
        best, best_key = description, (0, 0)
        for candidate in pools[category]:
            score = len(words(title) & words(candidate))
            key = (score, -used[(category, candidate)])
            if score > 0 and key > best_key:
                best, best_key = candidate, key
        repaired.append((category, title, best))
        used[(category, best)] += 1
    return repaired


def main():
    with open(PATH, encoding="utf-8", newline="") as handle:
        reader = csv.reader(handle)
        header = next(reader)
        rows = [tuple(r) for r in reader]
    before = stats(rows)
    repaired = repair(rows)
    after = stats(repaired)
    changed = sum(1 for a, b in zip(rows, repaired) if a != b)
    print("rows %d; rows without a shared content word: %d -> %d; rows changed: %d" % (len(rows), before[0], after[0], changed))
    if "--check" in sys.argv:
        return
    with open(PATH, "w", encoding="utf-8", newline="") as handle:
        writer = csv.writer(handle, lineterminator="\r\n")
        writer.writerow(header)
        writer.writerows(repaired)


if __name__ == "__main__":
    main()
