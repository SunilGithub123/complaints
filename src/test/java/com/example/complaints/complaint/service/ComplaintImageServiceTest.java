package com.example.complaints.complaint.service;

import com.example.complaints.common.exception.BusinessException;
import com.example.complaints.common.exception.ErrorCode;
import com.example.complaints.complaint.ComplaintProperties;
import com.example.complaints.complaint.model.ComplaintImage;
import com.example.complaints.complaint.model.ComplaintImageType;
import com.example.complaints.complaint.repository.ComplaintImageRepository;
import com.example.complaints.storage.StorageException;
import com.example.complaints.storage.StorageService;
import com.example.complaints.storage.StoredObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ComplaintImageServiceTest {

    private StorageService storage;
    private ComplaintImageRepository repo;
    private ComplaintImageService service;

    @BeforeEach
    void setUp() {
        storage = mock(StorageService.class);
        repo = mock(ComplaintImageRepository.class);
        service = new ComplaintImageService(
                new ComplaintProperties(24, 3, 1_048_576L, "MH"), storage, repo);
        when(repo.save(any(ComplaintImage.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("storeAll persists each valid image and returns them in input order")
    void storeAll_happyPath() {
        MultipartFile a = new MockMultipartFile("images", "a.jpg", "image/jpeg", new byte[]{1, 2, 3});
        MultipartFile b = new MockMultipartFile("images", "b.png", "image/png",  new byte[]{4, 5});
        when(storage.store(anyString(), any(), anyString(), anyLong()))
                .thenAnswer(inv -> new StoredObject(inv.getArgument(0), inv.getArgument(2),
                        (long) inv.getArgument(3)));

        List<ComplaintImage> result = service.storeAll(42L, List.of(a, b));

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getContentType()).isEqualTo("image/jpeg");
        assertThat(result.get(0).getSizeBytes()).isEqualTo(3);
        assertThat(result.get(0).getImageType()).isEqualTo(ComplaintImageType.COMPLAINT);
        assertThat(result.get(0).getStorageKey()).startsWith("complaint/42/COMPLAINT/").endsWith(".jpg");
        assertThat(result.get(1).getStorageKey()).endsWith(".png");
        verify(storage, times(2)).store(anyString(), any(), anyString(), anyLong());
        verify(repo, times(2)).save(any(ComplaintImage.class));
    }

    @Test
    @DisplayName("storeAll rejects an unsupported content type and never writes to storage")
    void storeAll_invalidType_rejected() {
        MultipartFile bad = new MockMultipartFile("images", "x.gif", "image/gif", new byte[]{1});

        assertThatThrownBy(() -> service.storeAll(1L, List.of(bad)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.IMAGE_INVALID_TYPE));
        verify(storage, never()).store(anyString(), any(), anyString(), anyLong());
    }

    @Test
    @DisplayName("storeAll rejects when more than maxImages are supplied")
    void storeAll_tooMany_rejected() {
        MultipartFile f = new MockMultipartFile("images", "a.jpg", "image/jpeg", new byte[]{1});

        assertThatThrownBy(() -> service.storeAll(1L, List.of(f, f, f, f)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.IMAGE_LIMIT_EXCEEDED));
        verify(storage, never()).store(anyString(), any(), anyString(), anyLong());
    }

    @Test
    @DisplayName("storeAll cleans up already-written keys when a later image fails")
    void storeAll_partialFailure_cleansUp() {
        MultipartFile a = new MockMultipartFile("images", "a.jpg", "image/jpeg", new byte[]{1, 2});
        MultipartFile b = new MockMultipartFile("images", "b.jpg", "image/jpeg", new byte[]{3, 4});
        when(storage.store(anyString(), any(), eq("image/jpeg"), anyLong()))
                .thenAnswer(inv -> new StoredObject(inv.getArgument(0), "image/jpeg", 2L))
                .thenThrow(new StorageException("disk full"));

        assertThatThrownBy(() -> service.storeAll(7L, List.of(a, b)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.IMAGE_UPLOAD_FAILED));
        verify(storage, times(1)).delete(anyString());
    }

    @Test
    @DisplayName("storeAll with null or empty list returns an empty list and does nothing")
    void storeAll_emptyList_noOp() {
        assertThat(service.storeAll(1L, null)).isEmpty();
        assertThat(service.storeAll(1L, List.of())).isEmpty();
        verify(storage, never()).store(anyString(), any(), anyString(), anyLong());
        verify(repo, never()).save(any());
    }
}


