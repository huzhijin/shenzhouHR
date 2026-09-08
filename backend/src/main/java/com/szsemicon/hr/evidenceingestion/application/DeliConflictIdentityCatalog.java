package com.szsemicon.hr.evidenceingestion.application;

import java.util.List;

/**
 * Verified July collisions used as overlays when Deli still prints another
 * person's intern/old 工号, or the Deli name spelling differs (李杨/李扬).
 * Replay and directory seeding cover everyone else; this is not an exclusive
 * person list.
 */
public final class DeliConflictIdentityCatalog {

    private DeliConflictIdentityCatalog() {
    }

    public record Entry(
            String displayName,
            String deliUserId,
            String deviceEmployeeNumber,
            String targetEmployeeNumber) {
    }

    public static List<Entry> knownBindings() {
        return List.of(
                new Entry("陆玉蕾", "932303205680513024", "SZST0285", "SZST0284"),
                new Entry("彭伟", "939805188834107393", "SZST0289", "SZST0335"),
                new Entry("李扬", "947095089162633216", "SZST0293", "SZST0291"),
                new Entry("王善源", "1248197527100440576", "SZSTSX77", "SZST0694"),
                new Entry("方鹏", "1142820074358341634", "SZST0497", "SZST0491"),
                new Entry("张晨阳", "1278767800065257473", "SZST0668", "SZST0663"),
                new Entry("仇容轩", "1290356442827120641", "SZST0713", "SZST0714"),
                new Entry("居军", "646292787763654657", "SZTD0019", "SZST0017"));
    }
}
