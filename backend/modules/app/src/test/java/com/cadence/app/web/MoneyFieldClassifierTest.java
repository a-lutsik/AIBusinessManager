package com.cadence.app.web;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guard for the name-based {@link MasterViewPolicy}: every numeric component of every response type a MASTER can
 * receive must be either recognised as money by the policy's own rule ({@link MasterViewPolicy#isMoneyKey}) or be
 * listed in {@link #NOT_MONEY} with a reason. A new money field named without a money word (cost, total, fee, sum,
 * balance...) fails here and forces a decision, instead of silently reaching masters.
 *
 * <p>Response types are discovered by classpath scanning of the {@code com.cadence.*.api} and
 * {@code com.cadence.app.web} packages (records only), then followed through component types, collections, maps and
 * arrays into records declared elsewhere. The property name is taken to be the record component name.
 */
class MoneyFieldClassifierTest {

    /** {@code Type.component} (nested types as {@code Outer.Inner}) of numeric fields that are not money, with the reason. */
    private static final Map<String, String> NOT_MONEY = new LinkedHashMap<>();

    static {
        NOT_MONEY.put("AppointmentView.bufferAfterSnapshot", "minutes of buffer after the visit");
        NOT_MONEY.put("AppointmentView.bufferBeforeSnapshot", "minutes of buffer before the visit");
        NOT_MONEY.put("AppointmentView.durationSnapshot", "visit duration in minutes");
        NOT_MONEY.put("BookingRulesView.horizonDays", "booking horizon in days");
        NOT_MONEY.put("BookingRulesView.lateCancellationHours", "late-cancellation window in hours");
        NOT_MONEY.put("BookingRulesView.minNoticeMinutes", "minimum booking notice in minutes");
        NOT_MONEY.put("BookingRulesView.slotStepMinutes", "slot grid step in minutes");
        NOT_MONEY.put("BookingRulesView.weekStartsOn", "weekday number");
        NOT_MONEY.put("ClientView.completedVisitCount", "count of visits");
        NOT_MONEY.put("ClientView.lateCancelCount", "count of late cancellations");
        NOT_MONEY.put("ClientView.noShowCount", "count of no-shows");
        NOT_MONEY.put("MasterServiceView.bufferAfterMinutesOverride", "minutes");
        NOT_MONEY.put("MasterServiceView.bufferBeforeMinutesOverride", "minutes");
        NOT_MONEY.put("MasterServiceView.durationMinutesOverride", "minutes");
        NOT_MONEY.put("MatrixRowView.bufferAfterMinutesOverride", "minutes");
        NOT_MONEY.put("MatrixRowView.bufferBeforeMinutesOverride", "minutes");
        NOT_MONEY.put("MatrixRowView.defaultBufferAfterMinutes", "minutes");
        NOT_MONEY.put("MatrixRowView.defaultBufferBeforeMinutes", "minutes");
        NOT_MONEY.put("MatrixRowView.defaultDurationMinutes", "minutes");
        NOT_MONEY.put("MatrixRowView.durationMinutesOverride", "minutes");
        NOT_MONEY.put("MetricDefinitionView.minSampleSize", "sample-size threshold, a count");
        NOT_MONEY.put("MetricView.deltaMoM", "relative month-over-month change; metrics are owner-only (assertOwner, empty on the master dashboard), and the metric value itself is redacted");
        NOT_MONEY.put("MetricView.sampleSize", "number of observations");
        NOT_MONEY.put("Offering.bufferAfterMinutes", "minutes");
        NOT_MONEY.put("Offering.bufferBeforeMinutes", "minutes");
        NOT_MONEY.put("Offering.durationMinutes", "minutes");
        NOT_MONEY.put("RetentionService.PackageView.remainingSessions", "session count");
        NOT_MONEY.put("RetentionService.PackageView.totalSessions", "session count");
        NOT_MONEY.put("ScheduleExceptionView.endMinute", "minute of day");
        NOT_MONEY.put("ScheduleExceptionView.startMinute", "minute of day");
        NOT_MONEY.put("ServiceView.bufferAfterMinutes", "minutes");
        NOT_MONEY.put("ServiceView.bufferBeforeMinutes", "minutes");
        NOT_MONEY.put("ServiceView.durationMinutes", "minutes");
        NOT_MONEY.put("WeeklyInterval.endMinute", "minute of day");
        NOT_MONEY.put("WeeklyInterval.startMinute", "minute of day");
        NOT_MONEY.put("WeeklyInterval.weekday", "weekday number");
    }

    private static final int MIN_SCANNED_RECORDS = 10;

    @Test
    void everyNumericResponseComponentIsMoneyOrExplicitlyNotMoney() {
        Set<Class<?>> records = discoverResponseRecords();
        assertThat(records.size()).as("scanned response records").isGreaterThanOrEqualTo(MIN_SCANNED_RECORDS);

        Set<String> numeric = numericComponents(records);
        assertThat(numeric).as("numeric components found").isNotEmpty();

        Set<String> unclassified = unclassified(numeric, NOT_MONEY.keySet());
        assertThat(unclassified)
                .as("Numeric response components that are neither matched by MasterViewPolicy.MONEY_WORDS nor listed "
                        + "in NOT_MONEY. If the field is money, rename it to contain a money word "
                        + MasterViewPolicy.MONEY_WORDS + " (or extend the list); if it is not money, add it to NOT_MONEY "
                        + "with a reason")
                .isEmpty();
    }

    @Test
    void allowListHasNoStaleOrRedundantEntries() {
        Set<String> numeric = numericComponents(discoverResponseRecords());

        Set<String> stale = new TreeSet<>(NOT_MONEY.keySet());
        stale.removeAll(numeric);
        assertThat(stale).as("NOT_MONEY entries that no longer exist as numeric response components").isEmpty();

        Set<String> redundant = new TreeSet<>();
        for (String entry : NOT_MONEY.keySet()) {
            if (MasterViewPolicy.isMoneyKey(componentOf(entry))) {
                redundant.add(entry);
            }
        }
        assertThat(redundant)
                .as("NOT_MONEY entries whose name the policy already treats as money (they are redacted for masters)")
                .isEmpty();
    }

    @Test
    void sanityAnchorsKnownMoneyFieldsAreClassifiedAsMoney() {
        Set<String> numeric = numericComponents(discoverResponseRecords());

        assertThat(numeric).contains("ServiceView.priceMinor");
        assertThat(MasterViewPolicy.isMoneyKey("priceMinor")).isTrue();
        assertThat(unclassified(numeric, NOT_MONEY.keySet())).doesNotContain("ServiceView.priceMinor");
        assertThat(NOT_MONEY).doesNotContainKey("ServiceView.priceMinor");
    }

    /** Proves the classifier would catch the risk: a money field named without any money word is reported. */
    @Test
    void classifierReportsMoneyFieldWithoutMoneyWord() {
        record SyntheticView(String name, int priceMinor, long totalFee, java.util.List<BigDecimal> balances) {
        }

        Set<String> numeric = numericComponents(Set.of(SyntheticView.class));

        assertThat(numeric).containsExactlyInAnyOrder(
                "MoneyFieldClassifierTest.SyntheticView.priceMinor",
                "MoneyFieldClassifierTest.SyntheticView.totalFee",
                "MoneyFieldClassifierTest.SyntheticView.balances");
        assertThat(unclassified(numeric, Set.of()))
                .containsExactlyInAnyOrder(
                        "MoneyFieldClassifierTest.SyntheticView.totalFee",
                        "MoneyFieldClassifierTest.SyntheticView.balances");
        assertThat(unclassified(numeric, Set.of("MoneyFieldClassifierTest.SyntheticView.totalFee")))
                .containsExactly("MoneyFieldClassifierTest.SyntheticView.balances");
    }

    /** Numeric components that the policy does not recognise as money and that are not allow-listed, sorted. */
    private static Set<String> unclassified(Set<String> numeric, Set<String> allowList) {
        Set<String> result = new TreeSet<>();
        for (String entry : numeric) {
            if (!MasterViewPolicy.isMoneyKey(componentOf(entry)) && !allowList.contains(entry)) {
                result.add(entry);
            }
        }
        return result;
    }

    private static String componentOf(String entry) {
        return entry.substring(entry.lastIndexOf('.') + 1);
    }

    /** All records in the response packages, found by scanning, plus every record reachable through their components. */
    private static Set<Class<?>> discoverResponseRecords() {
        Set<Class<?>> found = new LinkedHashSet<>();
        Deque<Class<?>> queue = new ArrayDeque<>();
        for (JavaClass javaClass : new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("com.cadence")) {
            if (javaClass.isRecord() && isResponsePackage(javaClass.getPackageName())) {
                queue.add(javaClass.reflect());
            }
        }
        while (!queue.isEmpty()) {
            Class<?> type = queue.poll();
            if (!found.add(type)) {
                continue;
            }
            for (RecordComponent component : type.getRecordComponents()) {
                collectRecords(component.getGenericType(), queue);
            }
        }
        return found;
    }

    private static boolean isResponsePackage(String packageName) {
        if (packageName.equals("com.cadence.app.web") || packageName.startsWith("com.cadence.app.web.")) {
            return true;
        }
        return packageName.startsWith("com.cadence.") && (packageName.endsWith(".api") || packageName.contains(".api."));
    }

    private static void collectRecords(Type type, Deque<Class<?>> out) {
        for (Type leaf : leaves(type)) {
            if (leaf instanceof Class<?> clazz && clazz.isRecord()) {
                out.add(clazz);
            }
        }
    }

    /** Numeric components as {@code Type.component}, including numbers wrapped in collections, maps, arrays, Optional. */
    private static Set<String> numericComponents(Set<Class<?>> records) {
        Set<String> result = new TreeSet<>();
        for (Class<?> record : records) {
            for (RecordComponent component : record.getRecordComponents()) {
                for (Type leaf : leaves(component.getGenericType())) {
                    if (leaf instanceof Class<?> clazz && isNumeric(clazz)) {
                        result.add(displayName(record) + "." + component.getName());
                    }
                }
            }
        }
        return result;
    }

    /** The type itself plus all type arguments / array element types, recursively. */
    private static Set<Type> leaves(Type type) {
        Set<Type> result = new LinkedHashSet<>();
        Deque<Type> queue = new ArrayDeque<>();
        queue.add(type);
        while (!queue.isEmpty()) {
            Type current = queue.poll();
            if (current instanceof Class<?> clazz) {
                if (clazz.isArray()) {
                    queue.add(clazz.getComponentType());
                } else {
                    result.add(clazz);
                }
            } else if (current instanceof ParameterizedType parameterized) {
                queue.add(parameterized.getRawType());
                queue.addAll(java.util.List.of(parameterized.getActualTypeArguments()));
            } else if (current instanceof GenericArrayType array) {
                queue.add(array.getGenericComponentType());
            } else if (current instanceof WildcardType wildcard) {
                queue.addAll(java.util.List.of(wildcard.getUpperBounds()));
            }
        }
        return result;
    }

    private static boolean isNumeric(Class<?> type) {
        return type == byte.class || type == short.class || type == int.class || type == long.class
                || type == float.class || type == double.class
                || Number.class.isAssignableFrom(type) && (type.getName().startsWith("java.lang.")
                || type == BigDecimal.class || type == BigInteger.class
                || type.getName().startsWith("java.util.concurrent.atomic."));
    }

    private static String displayName(Class<?> type) {
        String name = type.getName();
        return name.substring(name.lastIndexOf('.') + 1).replaceAll("\\$\\d*", ".");
    }
}
