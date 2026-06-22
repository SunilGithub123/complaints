package com.example.complaints.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalStorageServiceTest {

    private LocalStorageService newService(Path root) {
        return new LocalStorageService(new StorageProperties(
                StorageProperties.Type.LOCAL, root.toString(), null, 900));
    }

    @Test
    @DisplayName("store writes bytes under the resolved key and returns size")
    void store_writesFile(@TempDir Path tmp) throws Exception {
        LocalStorageService svc = newService(tmp);
        byte[] bytes = "hello".getBytes(StandardCharsets.UTF_8);

        StoredObject result = svc.store(
                "complaint/123/COMPLAINT/abc.txt",
                new ByteArrayInputStream(bytes),
                "text/plain",
                bytes.length);

        assertThat(result.key()).isEqualTo("complaint/123/COMPLAINT/abc.txt");
        assertThat(result.sizeBytes()).isEqualTo(5L);
        Path written = tmp.resolve("complaint/123/COMPLAINT/abc.txt");
        assertThat(Files.readAllBytes(written)).isEqualTo(bytes);
    }

    @Test
    @DisplayName("store rejects keys that escape the storage root")
    void store_rejectsEscapingKey(@TempDir Path tmp) {
        LocalStorageService svc = newService(tmp);

        assertThatThrownBy(() -> svc.store(
                "../../etc/passwd",
                new ByteArrayInputStream(new byte[]{1}),
                "application/octet-stream",
                1L))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("escapes root");
    }

    @Test
    @DisplayName("delete is idempotent for missing keys")
    void delete_missingKey_isNoOp(@TempDir Path tmp) {
        LocalStorageService svc = newService(tmp);

        // Should not throw.
        svc.delete("complaint/999/missing.jpg");
    }

    @Test
    @DisplayName("signedReadUrl returns a file:// URL pointing at the stored object")
    void signedReadUrl_returnsFileUri(@TempDir Path tmp) {
        LocalStorageService svc = newService(tmp);

        String url = svc.signedReadUrl("a/b.jpg", Duration.ofMinutes(15));

        assertThat(url).startsWith("file:").contains("a/b.jpg");
    }
}

