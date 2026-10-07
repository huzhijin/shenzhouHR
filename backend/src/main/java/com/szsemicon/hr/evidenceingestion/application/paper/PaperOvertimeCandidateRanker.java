package com.szsemicon.hr.evidenceingestion.application.paper;

import com.szsemicon.hr.evidenceingestion.infrastructure.persistence.PaperOvertimeRows.EmployeeCandidateRow;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class PaperOvertimeCandidateRanker {

    private PaperOvertimeCandidateRanker() {
    }

    public static List<EmployeeCandidateRow> rank(
            List<EmployeeCandidateRow> candidates,
            String name,
            String department) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        String wantedName = normalize(name);
        String wantedDepartment = normalize(department);
        List<EmployeeCandidateRow> ranked = new ArrayList<>(candidates);
        ranked.sort(Comparator
                .comparingInt((EmployeeCandidateRow row) ->
                        nameScore(row.displayName(), wantedName))
                .thenComparingInt(row ->
                        departmentScore(row.departmentName(), wantedDepartment))
                .thenComparing(row ->
                        row.employeeNumber() == null ? "" : row.employeeNumber()));
        return List.copyOf(ranked);
    }

    private static int nameScore(String displayName, String wantedName) {
        String actual = normalize(displayName);
        if (wantedName.isEmpty()) {
            return 1;
        }
        if (actual.equals(wantedName)) {
            return 0;
        }
        if (actual.contains(wantedName) || wantedName.contains(actual)) {
            return 1;
        }
        return 2 + levenshtein(actual, wantedName);
    }

    private static int departmentScore(String departmentName, String wanted) {
        String actual = normalize(departmentName);
        if (wanted.isEmpty()) {
            return 1;
        }
        if (actual.equals(wanted)) {
            return 0;
        }
        if (actual.contains(wanted) || wanted.contains(actual)) {
            return 1;
        }
        return 2 + levenshtein(actual, wanted);
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("[\\s部中心组科处室厂]", "")
                .toLowerCase(Locale.ROOT);
    }

    static int levenshtein(String left, String right) {
        int[] previous = new int[right.length() + 1];
        int[] current = new int[right.length() + 1];
        for (int j = 0; j <= right.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= left.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= right.length(); j++) {
                int cost = left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(
                        Math.min(current[j - 1] + 1, previous[j] + 1),
                        previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[right.length()];
    }
}
