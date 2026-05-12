import json
from collections import defaultdict

import matplotlib.pyplot as plt

# Update this if your run folder changes
INPUT_PATH = "target/gatling/basicllmuserssimulation-moderate-20260507141741/units-summary.jsonl"

def load_records(path):
    records = []
    with open(path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            records.append(json.loads(line))
    return records

def bucket_key(usage_type, remaining_units):
    has_remaining = remaining_units > 0
    if usage_type == "low" and has_remaining:
        return "Low usage + remaining"
    if usage_type == "high" and has_remaining:
        return "High usage + remaining"
    if usage_type == "low" and not has_remaining:
        return "Low usage + no remaining"
    return "High usage + no remaining"

def build_counts(records):
    counts = defaultdict(lambda: defaultdict(int))
    for rec in records:
        user_type = rec["userType"]
        usage_type = rec["usageType"]
        remaining_units = rec["remainingUnits"]
        key = bucket_key(usage_type, remaining_units)
        counts[user_type][key] += 1
    return counts

def plot_pies(counts):
    order = [
        "Low usage + remaining",
        "High usage + remaining",
        "Low usage + no remaining",
        "High usage + no remaining",
    ]

    user_types = sorted(counts.keys())
    fig, axes = plt.subplots(1, len(user_types), figsize=(5 * len(user_types), 5))
    if len(user_types) == 1:
        axes = [axes]

    colors = {
        "Low usage + remaining": "#4CAF50",  # Green
        "High usage + remaining": "#2196F3",  # Blue
        "Low usage + no remaining": "#FFC107",  # Amber
        "High usage + no remaining": "#F44336",  # Red
    }

    for ax, user_type in zip(axes, user_types):
        values = [counts[user_type].get(label, 0) for label in order]
        ax.pie(
            values,
            labels=order,
            autopct=lambda pct: f"{pct:.1f}%" if pct > 0 else "",
            startangle=90,
            colors=[colors[label] for label in order],
        )
        ax.set_title(f"{user_type.capitalize()} users")

    plt.tight_layout()
    plt.show()

def main():
    records = load_records(INPUT_PATH)
    counts = build_counts(records)
    plot_pies(counts)

if __name__ == "__main__":
    main()