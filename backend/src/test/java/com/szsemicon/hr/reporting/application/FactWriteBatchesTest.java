package com.szsemicon.hr.reporting.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class FactWriteBatchesTest {

    @Test
    void emptyAndNullAreNoChunks() {
        assertThat(FactWriteBatches.partition(null, 500)).isEmpty();
        assertThat(FactWriteBatches.partition(List.of(), 500)).isEmpty();
    }

    @Test
    void singleRowIsOneChunk() {
        assertThat(FactWriteBatches.partition(List.of("a"), 500))
                .containsExactly(List.of("a"));
    }

    @Test
    void exactBatchSizeIsOneChunk() {
        List<Integer> rows = IntStream.range(0, 500).boxed().toList();
        List<List<Integer>> parts = FactWriteBatches.partition(rows, 500);
        assertThat(parts).hasSize(1);
        assertThat(parts.getFirst()).hasSize(500);
    }

    @Test
    void oneOverBatchSizeSplits() {
        List<Integer> rows = IntStream.range(0, 501).boxed().toList();
        List<List<Integer>> parts = FactWriteBatches.partition(rows, 500);
        assertThat(parts).hasSize(2);
        assertThat(parts.get(0)).hasSize(500);
        assertThat(parts.get(1)).hasSize(1);
    }

    @Test
    void invalidSizeFallsBackToDefault() {
        assertThat(FactWriteBatches.boundedSize(0))
                .isEqualTo(FactWriteBatches.DEFAULT_SIZE);
        assertThat(FactWriteBatches.boundedSize(10_000))
                .isEqualTo(FactWriteBatches.DEFAULT_SIZE);
    }
}
