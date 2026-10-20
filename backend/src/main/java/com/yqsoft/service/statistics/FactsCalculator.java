package com.yqsoft.service.statistics;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** Pure aggregation: no model, planned duration or missing-day interpolation enters these facts. */
public final class FactsCalculator {
    private FactsCalculator() {}

    public record Sample(
            long id,
            String type,
            Instant start,
            Instant end,
            Integer duration,
            String sleepKind,
            Integer energyLevel,
            String timeZone) {}

    public record TaskCounts(int pending, int completed, int skipped, int cancelled) {
        public TaskCounts(int pending, int completed, int skipped) {
            this(pending, completed, skipped, 0);
        }

        public int denominator() {
            return pending + completed + skipped;
        }
    }

    public record DailyFact(
            Integer mainSleepMinutes,
            Integer napMinutes,
            Integer activityMinutes,
            Double energyMedian,
            Integer energySum,
            int energySamples,
            int meals,
            TaskCounts tasks,
            Map<String, Integer> recordCounts,
            List<Long> recordIds,
            List<Long> excludedRecordIds,
            List<String> qualityFlags,
            List<String> timeZones) {}

    public static DailyFact calculate(List<Sample> samples, TaskCounts tasks) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String type : List.of("SLEEP", "ACTIVITY", "MEAL", "ENERGY")) counts.put(type, 0);
        samples.forEach(s -> counts.computeIfPresent(s.type(), (k, v) -> v + 1));
        List<Sample> main =
                samples.stream()
                        .filter(s -> "SLEEP".equals(s.type()) && "MAIN".equals(s.sleepKind()))
                        .toList();
        List<Sample> naps =
                samples.stream()
                        .filter(s -> "SLEEP".equals(s.type()) && "NAP".equals(s.sleepKind()))
                        .toList();
        Set<Long> excluded = new LinkedHashSet<>();
        List<String> flags = new ArrayList<>();
        Integer mainMinutes = null;
        if (main.size() == 1) mainMinutes = minutes(main.getFirst().start(), main.getFirst().end());
        if (main.size() > 1) {
            flags.add("MAIN_SLEEP_CONFLICT");
            main.forEach(s -> excluded.add(s.id()));
        }
        Integer napMinutes = naps.isEmpty() ? null : subtractMinutes(merge(naps), merge(main));
        List<Sample> activity = samples.stream().filter(s -> "ACTIVITY".equals(s.type())).toList();
        Set<Long> overlap = new LinkedHashSet<>();
        for (int i = 0; i < activity.size(); i++) {
            for (int j = i + 1; j < activity.size(); j++) {
                if (overlaps(activity.get(i), activity.get(j))) {
                    overlap.add(activity.get(i).id());
                    overlap.add(activity.get(j).id());
                }
            }
        }
        if (!overlap.isEmpty()) {
            flags.add("ACTIVITY_OVERLAP_EXCLUDED");
            excluded.addAll(overlap);
        }
        List<Sample> included = activity.stream().filter(s -> !overlap.contains(s.id())).toList();
        Integer activityMinutes =
                included.isEmpty()
                        ? null
                        : included.stream()
                                .mapToInt(s -> s.duration() == null ? 0 : s.duration())
                                .sum();
        List<Integer> energy =
                samples.stream()
                        .filter(s -> "ENERGY".equals(s.type()))
                        .map(Sample::energyLevel)
                        .filter(Objects::nonNull)
                        .sorted()
                        .toList();
        Double median =
                energy.isEmpty()
                        ? null
                        : energy.size() % 2 == 1
                                ? energy.get(energy.size() / 2).doubleValue()
                                : (energy.get(energy.size() / 2 - 1)
                                                + energy.get(energy.size() / 2))
                                        / 2.0;
        Integer energySum =
                energy.isEmpty() ? null : energy.stream().mapToInt(Integer::intValue).sum();
        return new DailyFact(
                mainMinutes,
                napMinutes,
                activityMinutes,
                median,
                energySum,
                energy.size(),
                counts.get("MEAL"),
                tasks,
                Collections.unmodifiableMap(counts),
                samples.stream().map(Sample::id).toList(),
                List.copyOf(excluded),
                List.copyOf(flags),
                samples.stream()
                        .map(Sample::timeZone)
                        .filter(Objects::nonNull)
                        .distinct()
                        .sorted()
                        .toList());
    }

    private static boolean overlaps(Sample a, Sample b) {
        return a.start() != null
                && a.end() != null
                && b.start() != null
                && b.end() != null
                && a.start().isBefore(b.end())
                && b.start().isBefore(a.end());
    }

    private record Interval(Instant start, Instant end) {}

    private static List<Interval> merge(List<Sample> source) {
        List<Interval> sorted =
                source.stream()
                        .filter(s -> s.start() != null && s.end() != null)
                        .map(s -> new Interval(s.start(), s.end()))
                        .sorted(Comparator.comparing(Interval::start))
                        .toList();
        List<Interval> result = new ArrayList<>();
        for (Interval next : sorted) {
            if (result.isEmpty() || result.getLast().end().isBefore(next.start())) {
                result.add(next);
            } else {
                Interval previous = result.removeLast();
                result.add(
                        new Interval(
                                previous.start(),
                                previous.end().isAfter(next.end()) ? previous.end() : next.end()));
            }
        }
        return result;
    }

    private static int subtractMinutes(List<Interval> base, List<Interval> remove) {
        long seconds = 0;
        for (Interval interval : base) {
            seconds += Duration.between(interval.start(), interval.end()).getSeconds();
            for (Interval excluded : remove) {
                Instant left =
                        interval.start().isAfter(excluded.start())
                                ? interval.start()
                                : excluded.start();
                Instant right =
                        interval.end().isBefore(excluded.end()) ? interval.end() : excluded.end();
                if (right.isAfter(left)) seconds -= Duration.between(left, right).getSeconds();
            }
        }
        return (int) Math.max(0, seconds / 60);
    }

    private static int minutes(Instant start, Instant end) {
        return (int) Duration.between(start, end).toMinutes();
    }
}
