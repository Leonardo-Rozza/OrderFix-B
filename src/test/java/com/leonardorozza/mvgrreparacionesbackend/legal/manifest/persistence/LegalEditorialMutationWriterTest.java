package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class LegalEditorialMutationWriterTest {

    @Test
    void exposesOnlyTheMutationAndSharedSessionBoundary() {
        assertThat(LegalEditorialMutationWriter.class.isInterface()).isTrue();
        assertThat(Arrays.stream(LegalEditorialMutationWriter.class.getDeclaredMethods())
                .map(Method::getName))
                .containsExactlyInAnyOrder("write", "usesJdbc");
        assertThat(LegalEditorialMutationWriter.class)
                .isAssignableFrom(LegalInitialPromotionCore.class);
        assertThat(Arrays.stream(LegalEditorialMutationWriter.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("write")))
                .singleElement()
                .satisfies(method -> {
                    assertThat(method.getReturnType()).isEqualTo(void.class);
                    assertThat(method.getParameterTypes())
                            .containsExactly(LegalEditorialExecutionPlan.class);
                });
    }

    @Test
    void concreteWriterKeepsTheExactJdbcIdentity() {
        JdbcTemplate jdbc = new JdbcTemplate();
        LegalEditorialMutationWriter writer = new LegalInitialPromotionCore(jdbc);

        assertThat(writer.usesJdbc(jdbc)).isTrue();
        assertThat(writer.usesJdbc(new JdbcTemplate())).isFalse();
    }
}
