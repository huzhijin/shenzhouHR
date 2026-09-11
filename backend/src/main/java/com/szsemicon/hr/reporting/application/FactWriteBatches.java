package com.szsemicon.hr.reporting.application;

import java.util.ArrayList;
import java.util.List;

public final class FactWriteBatches {

    public static final int DEFAULT_SIZE = 500;

    private FactWriteBatches() {
    }

    public static int boundedSize(int requested) {
        if (requested < 1) {
            return DEFAULT_SIZE;
        }
        return Math.min(requested, DEFAULT_SIZE);
    }

    public static <T> List<List<T>> partition(List<T> items, int size) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        int chunk = boundedSize(size);
        if (items.size() <= chunk) {
            return List.of(List.copyOf(items));
        }
        List<List<T>> parts = new ArrayList<>();
        for (int index = 0; index < items.size(); index += chunk) {
            parts.add(List.copyOf(items.subList(
                    index,
                    Math.min(index + chunk, items.size()))));
        }
        return List.copyOf(parts);
    }
}
