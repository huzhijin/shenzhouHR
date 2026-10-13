package com.szsemicon.hr.people.application.roster;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public record RosterRow(
        int rowNumber,
        String sequence,
        String companyName,
        String employeeNumber,
        String displayName,
        String level1,
        String level2,
        String level3,
        String groupName,
        String title,
        LocalDate hireDate) {

    public List<String> pathTokens(String companyCode) {
        List<String> tokens = new ArrayList<>();
        for (String part : List.of(level1, level2, level3, groupName)) {
            String aliased = RosterNames.alias(companyCode, part);
            if (aliased.isEmpty()) {
                continue;
            }
            if (!tokens.isEmpty() && tokens.get(tokens.size() - 1).equalsIgnoreCase(aliased)) {
                continue;
            }
            tokens.add(aliased);
        }
        return List.copyOf(tokens);
    }
}
