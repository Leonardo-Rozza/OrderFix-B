package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import static org.assertj.core.api.Assertions.assertThat;

class ConfinedEditorialPlanReaderTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void readsOnlyTheExplicitRegularFilenameAndReturnsDefensiveBytes() throws IOException {
        byte[] expected = "{\"schemaVersion\":1}".getBytes(StandardCharsets.UTF_8);
        Path plan = testDirectory().resolve(ConfinedEditorialPlanReader.PLAN_FILENAME);
        Files.write(plan, expected);

        LegalManifestValidation<ConfinedEditorialPlanReader.EditorialPlanSource> result =
                new ConfinedEditorialPlanReader().read(plan);

        assertThat(result.status()).isEqualTo(LegalManifestStatus.PASS);
        byte[] exposed = result.value().orElseThrow().bytes();
        assertThat(exposed).containsExactly(expected);
        exposed[0] = '!';
        assertThat(result.value().orElseThrow().bytes()).containsExactly(expected);
    }

    @Test
    void rejectsMissingWrongFilenameDirectoryAndExplicitSymlink() throws IOException {
        ConfinedEditorialPlanReader reader = new ConfinedEditorialPlanReader();
        assertBlocked(reader.read(null), LegalManifestIssueCode.EDITORIAL_PLAN_PATH_REQUIRED);
        assertBlocked(
                reader.read(testDirectory().resolve("plan.json")),
                LegalManifestIssueCode.EDITORIAL_PLAN_FILENAME_INVALID);
        assertBlocked(
                reader.read(testDirectory().resolve(ConfinedEditorialPlanReader.PLAN_FILENAME)),
                LegalManifestIssueCode.EDITORIAL_PLAN_NOT_REGULAR);

        Path directory = testDirectory().resolve("nested")
                .resolve(ConfinedEditorialPlanReader.PLAN_FILENAME);
        Files.createDirectories(directory);
        assertBlocked(reader.read(directory), LegalManifestIssueCode.EDITORIAL_PLAN_NOT_REGULAR);

        Path target = testDirectory().resolve("target.json");
        Files.writeString(target, "{}", StandardCharsets.UTF_8);
        Path link = testDirectory().resolve("link")
                .resolve(ConfinedEditorialPlanReader.PLAN_FILENAME);
        Files.createDirectories(link.getParent());
        Files.createSymbolicLink(link, target);
        assertBlocked(reader.read(link), LegalManifestIssueCode.EDITORIAL_PLAN_SYMLINK_FORBIDDEN);

        Path realParent = testDirectory().resolve("real-parent");
        Files.createDirectories(realParent);
        Files.writeString(
                realParent.resolve(ConfinedEditorialPlanReader.PLAN_FILENAME),
                "{}",
                StandardCharsets.UTF_8);
        Path parentLink = testDirectory().resolve("linked-parent");
        Files.createSymbolicLink(parentLink, realParent);
        assertBlocked(
                reader.read(parentLink.resolve(ConfinedEditorialPlanReader.PLAN_FILENAME)),
                LegalManifestIssueCode.EDITORIAL_PLAN_SYMLINK_FORBIDDEN);
    }

    @Test
    void acceptsTheExactByteLimitAndRejectsTheNextByte() throws IOException {
        ConfinedEditorialPlanReader reader = new ConfinedEditorialPlanReader();
        Path plan = testDirectory().resolve(ConfinedEditorialPlanReader.PLAN_FILENAME);

        Files.write(plan, new byte[LegalEditorialPlanLimits.MAX_PLAN_BYTES]);
        assertThat(reader.read(plan).status()).isEqualTo(LegalManifestStatus.PASS);

        Files.write(plan, new byte[LegalEditorialPlanLimits.MAX_PLAN_BYTES + 1]);
        assertBlocked(
                reader.read(plan),
                LegalManifestIssueCode.EDITORIAL_PLAN_SIZE_LIMIT_EXCEEDED);
    }

    @Test
    void detectsAFileChangedDuringTheBoundedRead() throws IOException {
        Path plan = testDirectory().resolve(ConfinedEditorialPlanReader.PLAN_FILENAME);
        Files.writeString(plan, "{}", StandardCharsets.UTF_8);
        ConfinedEditorialPlanReader reader = new ConfinedEditorialPlanReader(path ->
                Files.writeString(path, "{\"changed\":true}", StandardCharsets.UTF_8));

        assertBlocked(
                reader.read(plan),
                LegalManifestIssueCode.EDITORIAL_PLAN_FILE_CHANGED);
    }

    @Test
    void rejectsARegularFileAbaSubstitutionAndRestoration() throws IOException {
        Path plan = testDirectory().resolve(ConfinedEditorialPlanReader.PLAN_FILENAME);
        Path backup = testDirectory().resolve("original.backup");
        Path substitute = testDirectory().resolve("substitute.json");
        byte[] originalBytes = "{\"original\":true}".getBytes(StandardCharsets.UTF_8);
        Files.write(plan, originalBytes);
        Files.writeString(substitute, "{\"substitute\":true}", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(testDirectory(), FileTime.fromMillis(0L));

        ConfinedEditorialPlanReader reader = new ConfinedEditorialPlanReader(
                new ConfinedEditorialPlanReader.ReadHook() {
                    @Override
                    public void beforeRead(Path path) throws IOException {
                        Files.move(path, backup);
                        Files.move(substitute, path);
                    }

                    @Override
                    public void afterRead(Path path) throws IOException {
                        Files.move(path, substitute);
                        Files.move(backup, path);
                    }
                });

        assertBlocked(
                reader.read(plan),
                LegalManifestIssueCode.EDITORIAL_PLAN_FILE_CHANGED);
        assertThat(Files.readAllBytes(plan)).containsExactly(originalBytes);
    }

    private static void assertBlocked(
            LegalManifestValidation<?> result,
            LegalManifestIssueCode expectedCode) {
        assertThat(result.status()).isEqualTo(LegalManifestStatus.BLOCKED);
        assertThat(result.value()).isEmpty();
        assertThat(result.issues())
                .extracting(LegalManifestIssue::code)
                .containsExactly(expectedCode);
        assertThat(result.issues())
                .extracting(LegalManifestIssue::location)
                .containsOnly(ConfinedEditorialPlanReader.PLAN_FILENAME);
    }

    private Path testDirectory() throws IOException {
        return temporaryDirectory.toRealPath();
    }
}
