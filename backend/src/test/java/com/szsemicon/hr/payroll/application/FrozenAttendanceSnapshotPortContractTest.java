package com.szsemicon.hr.payroll.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class FrozenAttendanceSnapshotPortContractTest {

    @Test
    void exposesLookupOnly() {
        Set<String> publicAbstractMethods = Arrays.stream(
                        FrozenAttendanceSnapshotPort.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .filter(Method::isDefault)
                .map(Method::getName)
                .collect(Collectors.toSet());
        Set<String> allDeclaredMethods = Arrays.stream(
                        FrozenAttendanceSnapshotPort.class.getDeclaredMethods())
                .map(Method::getName)
                .collect(Collectors.toSet());

        assertThat(publicAbstractMethods).isEmpty();
        assertThat(allDeclaredMethods).containsExactly("findById");
        assertThat(allDeclaredMethods).noneMatch(name -> name.matches(
                "(?i).*(create|close|reopen|update|delete|write|recalculate).*"));
    }
}
