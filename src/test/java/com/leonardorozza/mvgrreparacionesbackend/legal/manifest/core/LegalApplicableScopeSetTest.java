package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.AudienciaLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.ContextoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.LocaleLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.PerfilAgregadoLegal;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LegalApplicableScopeSetTest {

    @Test
    void sortsAllEightContextsByTheFrozenEnumOrder() {
        List<ContextoLegal> reversed = new ArrayList<>(Arrays.asList(ContextoLegal.values()));
        Collections.reverse(reversed);

        LegalApplicableScopeSet scopes = authenticated(reversed);

        assertThat(scopes.contexts()).containsExactly(
                ContextoLegal.REGISTRO,
                ContextoLegal.PRIMER_INGRESO_EMPLEADO,
                ContextoLegal.USO_CONTINUADO,
                ContextoLegal.CONTRATACION_PRO,
                ContextoLegal.ATESTACION_FOTOS,
                ContextoLegal.ATESTACION_CREDENCIALES,
                ContextoLegal.CIERRE_CUENTA,
                ContextoLegal.ARREPENTIMIENTO);
    }

    @Test
    void defensivelyCopiesThePolicyCollectionAndExposesAnImmutableList() {
        ArrayList<ContextoLegal> mutable = new ArrayList<>(List.of(
                ContextoLegal.ATESTACION_FOTOS,
                ContextoLegal.USO_CONTINUADO));
        LegalApplicableScopeSet scopes = authenticated(mutable);

        mutable.clear();

        assertThat(scopes.contexts()).containsExactly(
                ContextoLegal.USO_CONTINUADO,
                ContextoLegal.ATESTACION_FOTOS);
        assertThatThrownBy(() -> scopes.contexts().add(ContextoLegal.CIERRE_CUENTA))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsEmptyDuplicateNullAndMissingBaselineContexts() {
        assertThatThrownBy(() -> authenticated(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> authenticated(List.of(
                ContextoLegal.USO_CONTINUADO,
                ContextoLegal.USO_CONTINUADO)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicados");

        ArrayList<ContextoLegal> withNull = new ArrayList<>();
        withNull.add(ContextoLegal.USO_CONTINUADO);
        withNull.add(null);
        assertThatThrownBy(() -> authenticated(withNull))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> authenticated(List.of(ContextoLegal.ATESTACION_FOTOS)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("USO_CONTINUADO");
    }

    @Test
    void rejectsOversizedCollectionBeforeCopyingOrIteratingIt() {
        List<ContextoLegal> huge = Collections.nCopies(
                Integer.MAX_VALUE,
                ContextoLegal.USO_CONTINUADO);

        assertThatThrownBy(() -> authenticated(huge))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("entre 1 y 8");
    }

    @Test
    void registrationRequiresTheExactOwnerRegistrationVector() {
        LegalApplicableScopeSet valid = new LegalApplicableScopeSet(
                PerfilAgregadoLegal.REGISTRATION,
                LocaleLegal.ES_AR,
                AudienciaLegal.ADMIN_TITULAR,
                List.of(ContextoLegal.REGISTRO));

        assertThat(valid.profile()).isEqualTo(PerfilAgregadoLegal.REGISTRATION);
        assertThat(valid.locale()).isEqualTo(LocaleLegal.ES_AR);
        assertThat(valid.audience()).isEqualTo(AudienciaLegal.ADMIN_TITULAR);
        assertThat(valid.contexts()).containsExactly(ContextoLegal.REGISTRO);
        assertThatThrownBy(() -> new LegalApplicableScopeSet(
                PerfilAgregadoLegal.REGISTRATION,
                LocaleLegal.ES_AR,
                AudienciaLegal.USER,
                List.of(ContextoLegal.REGISTRO)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LegalApplicableScopeSet(
                PerfilAgregadoLegal.REGISTRATION,
                LocaleLegal.ES_AR,
                AudienciaLegal.ADMIN_TITULAR,
                List.of(ContextoLegal.REGISTRO, ContextoLegal.USO_CONTINUADO)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void hasNoPublicOrProtectedConstructorAndKeepsValueSemantics() {
        assertThat(Modifier.isFinal(LegalApplicableScopeSet.class.getModifiers())).isTrue();
        assertThat(Arrays.stream(LegalApplicableScopeSet.class.getDeclaredConstructors()))
                .allMatch(constructor -> !Modifier.isPublic(constructor.getModifiers())
                        && !Modifier.isProtected(constructor.getModifiers()));
        assertThat(Arrays.stream(LegalApplicableScopeSet.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .filter(method -> Modifier.isStatic(method.getModifiers())))
                .isEmpty();

        LegalApplicableScopeSet first = authenticated(List.of(
                ContextoLegal.ATESTACION_FOTOS,
                ContextoLegal.USO_CONTINUADO));
        LegalApplicableScopeSet second = authenticated(List.of(
                ContextoLegal.USO_CONTINUADO,
                ContextoLegal.ATESTACION_FOTOS));

        assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
        assertThat(first.toString())
                .contains("AUTHENTICATED_PENDING", "es-AR", "USER", "USO_CONTINUADO");
    }

    private static LegalApplicableScopeSet authenticated(List<ContextoLegal> contexts) {
        return new LegalApplicableScopeSet(
                PerfilAgregadoLegal.AUTHENTICATED_PENDING,
                LocaleLegal.ES_AR,
                AudienciaLegal.USER,
                contexts);
    }
}
