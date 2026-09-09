package com.leonardorozza.mvgrreparacionesbackend.photos.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MapPropertySource;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

class PrivatePhotoStorageConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(PrivatePhotoStorageConfiguration.class);

    @Test
    void absentOrDisabledFeatureNeverRequiresCredentialsOrCreatesStorage() {
        context.run(app -> assertThat(app).hasNotFailed().doesNotHaveBean(PrivatePhotoStorage.class));
        context.withPropertyValues("photos.private.enabled=false").run(app ->
                assertThat(app).hasNotFailed().doesNotHaveBean(PrivatePhotoStorage.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"TRUE", "False", "1", "", " true "})
    void invalidFlagsCannotSilentlyDisableProtection(String value) {
        context.withInitializer(app -> app.getEnvironment().getPropertySources().addFirst(
                new MapPropertySource("raw-flag", Map.of("photos.private.enabled", value))))
                .run(app -> assertThat(app).hasFailed());
    }

    @Test
    void enabledProviderRequiresItsOwnCompleteSafeConfiguration() {
        context.withPropertyValues("photos.private.enabled=true").run(app -> {
            assertThat(app).hasFailed();
            assertThat(app.getStartupFailure()).hasRootCauseInstanceOf(IllegalArgumentException.class)
                    .hasRootCauseMessage("La configuración de almacenamiento privado no es válida.");
        });
        validContext().withPropertyValues("photos.private.cloudinary.api-secret=bad secret").run(app -> {
            assertThat(app).hasFailed();
            assertThat(app.getStartupFailure()).hasRootCauseMessage("La configuración de almacenamiento privado no es válida.");
            assertThat(app.getStartupFailure().toString()).doesNotContain("bad secret");
        });
    }

    @Test
    void enabledStorageIsCreatedWithoutNetworkAndClosedWithTheContext() {
        CloudinaryPrivatePhotoStorage[] created = new CloudinaryPrivatePhotoStorage[1];
        validContext().run(app -> {
            assertThat(app).hasNotFailed().hasSingleBean(PrivatePhotoStorage.class);
            created[0] = app.getBean(CloudinaryPrivatePhotoStorage.class);
            assertThat(created[0].toString()).isEqualTo("CloudinaryPrivatePhotoStorage[redacted]");
        });
        assertThatThrownBy(() -> created[0].find("opaque-key"))
                .isInstanceOfSatisfying(PrivatePhotoStorageException.class,
                        error -> assertThat(error.reason()).isEqualTo(PrivatePhotoStorageException.Reason.UNAVAILABLE));
    }

    @Test
    void explicitFixtureStorageDoesNotCreateACloudinaryClientOrRequireItsCredentials() {
        PrivatePhotoStorage fake = mock(PrivatePhotoStorage.class);
        context.withBean(PrivatePhotoStorage.class, () -> fake).withPropertyValues("photos.private.enabled=true").run(app -> {
            assertThat(app).hasNotFailed().hasSingleBean(PrivatePhotoStorage.class);
            assertThat(app.getBean(PrivatePhotoStorage.class)).isSameAs(fake);
            assertThat(app).doesNotHaveBean(CloudinaryPrivatePhotoStorage.class);
        });
    }

    private ApplicationContextRunner validContext() {
        return context.withPropertyValues("photos.private.enabled=true", "photos.private.cloudinary.cloud-name=synthetic-cloud",
                "photos.private.cloudinary.api-key=123456789", "photos.private.cloudinary.api-secret=synthetic-private-secret");
    }
}
